"""Assemble a new immutable save from independently accepted global chunk cores.

The source save used for a safe spawn contributes only its two modern config
files. Geometry is copied by exact core ownership; no source world is modified.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

from anvil import _fs_path, Tag, read_level_dat, write_level_dat
from grow_contract import validate_plan
from grow_regions import merge_regions
from grow_receipt import verify_grown_world

MERGED_ROOT = Path(r"C:\Users\User\AppData\Local\FORK-Tools\fork-singapore-full\merged")
CONFIG_FILES = ("level.dat", "data/minecraft/world_gen_settings.dat")


def digest(path):
    value = hashlib.sha256()
    with open(_fs_path(path), "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def read_json(path):
    with open(_fs_path(path), encoding="utf-8-sig") as stream:
        return json.load(stream)


def validate_destination(world, manifest, *, merged_root=MERGED_ROOT):
    world, manifest, merged_root = map(lambda p: Path(p).resolve(), (world, manifest, merged_root))
    # A single named snapshot owns its world, evidence and temporary siblings.
    if (world.name != "world" or world.parent.parent != merged_root
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", world.parent.name)):
        raise ValueError("world must be a new merged/<snapshot-id>/world")
    if manifest.parent != world.parent or manifest.name in ("world", ".", ".."):
        raise ValueError("manifest must be a new sibling file inside the snapshot root")
    if os.path.lexists(_fs_path(world)) or os.path.lexists(_fs_path(manifest)):
        raise FileExistsError("world and completed manifest must both be absent")
    return world, manifest


def validate_lease(path, world, *, now=None):
    lease = read_json(path)
    now = now or datetime.now(timezone.utc)
    if Path(lease.get("outputRoot", "")).resolve() != world.parent:
        raise ValueError("lease outputRoot must equal this immutable snapshot root")
    if (lease.get("machine") != "Desktop"
            or lease.get("approvedBy") != "/root/singapore_full_coordinator"
            or lease.get("heavyJobSlot") not in ("A", "B")
            or lease.get("cpuThreads") != 1):
        raise ValueError("an approved Desktop single-CPU coordinator lease is required")
    start = datetime.fromisoformat(lease["startsUtc"].replace("Z", "+00:00"))
    end = datetime.fromisoformat(lease["expiresUtc"].replace("Z", "+00:00"))
    if start.tzinfo is None or end.tzinfo is None or not start <= now < end:
        raise ValueError("coordinator lease is not currently valid")
    return {"id": lease.get("id"), "path": str(Path(path).resolve()),
            "sha256": digest(path), "heavyJobSlot": lease["heavyJobSlot"],
            "expiresUtc": lease["expiresUtc"]}


def assemble(plan_path, world, manifest, lease_path, *, merged_root=MERGED_ROOT):
    world, manifest = validate_destination(world, manifest, merged_root=merged_root)
    lease = validate_lease(lease_path, world)
    plan_hash = digest(plan_path)
    plan = read_json(plan_path)
    accepted = validate_plan(plan)
    sources = accepted["sources"]
    subset_preview = any(record.get("status") == "rendered_subset_preview"
                         for source in sources for record in source["coverage"].values())
    spawn_id = plan.get("spawn_source_id")
    matches = [source for source in sources if source["id"] == spawn_id]
    if len(matches) != 1:
        raise ValueError("plan spawn_source_id must name exactly one accepted owned core")
    spawn_source = matches[0]
    world_name = plan.get("world_name")
    if world_name is not None and (not isinstance(world_name, str) or not 1 <= len(world_name) <= 80):
        raise ValueError("optional world_name must contain 1 to 80 characters")
    for source in sources:
        path = Path(source["world_path"]).resolve()
        if path == world.parent or path in world.parent.parents or world.parent in path.parents:
            raise ValueError("source trees and snapshot output must be separate")
    os.makedirs(_fs_path(world.parent), exist_ok=True)
    created = tempfile.mkdtemp(prefix=".grow-", dir=_fs_path(world.parent))
    # Keep logical receipt paths free of Windows' native I/O prefix.
    staging_root = world.parent / Path(created).name
    staging_world = staging_root / "world"
    os.makedirs(_fs_path(staging_world))
    # A failed private stage is retained for diagnosis. There is never a partial
    # published world or a success receipt after a failed gate.
    for relative in CONFIG_FILES:
        source = Path(spawn_source["world_path"]) / relative
        target = staging_world / relative
        os.makedirs(_fs_path(target.parent), exist_ok=True)
        shutil.copyfile(_fs_path(source), _fs_path(target))
    if world_name is not None:
        level = read_level_dat(staging_world / "level.dat")
        level.root.value["Data"].value["LevelName"] = Tag(8, world_name)
        write_level_dat(staging_world / "level.dat", level)
    region_report = merge_regions(sources, staging_world)
    checked = verify_grown_world(staging_world, sources, region_report, spawn_id)
    if checked.get("status") != "PASS" or checked.get("assemblyAccepted") is not True:
        raise ValueError("grown world verification failed: " + json.dumps(checked.get("issues", [])))
    if digest(plan_path) != plan_hash:
        raise ValueError("input plan changed during assembly")
    validate_lease(lease_path, world)
    if digest(lease_path) != lease["sha256"]:
        raise ValueError("lease changed during assembly")
    # Recheck destination immediately before the one-way new-save promotion.
    validate_destination(world, manifest, merged_root=merged_root)
    report = {"schemaVersion": 1, "kind": "exact-core-grown-world",
              "status": "SOURCE_SUBSET_PREVIEW_RUNTIME_PENDING" if subset_preview else "STRUCTURAL_PASS_RUNTIME_PENDING", "world": str(world),
              "createdUtc": datetime.now(timezone.utc).isoformat(),
              "planPath": str(Path(plan_path).resolve()), "planSha256": plan_hash,
              "jobLease": lease, "spawnSourceId": spawn_id,
              "extent": accepted["extent"], "expectedChunks": accepted["expected_chunks"],
              "dataVersion": accepted["data_version"],
              "coordinateFrame": accepted["coordinate_frame"],
              "worldName": world_name,
              "sources": sources, "regions": region_report,
              "verification": checked, "assemblyAccepted": True, "runtimeLoadAccepted": False,
              "sourceSubsetPreviewAccepted": subset_preview,
              "sourceComplete": False, "routeComplete": False, "fullFidelity": False,
              "globalSourceGeometryComplete": False,
              "actualGroundAccepted": False,
              "clientVisualAccepted": False, "completeSingaporeAccepted": False}
    os.rename(_fs_path(staging_world), _fs_path(world))
    os.rmdir(_fs_path(staging_root))
    # Only a fully written receipt is promoted into the completed manifest path.
    handle, pending = tempfile.mkstemp(prefix=".receipt-", suffix=".json", dir=_fs_path(manifest.parent))
    with os.fdopen(handle, "w", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    if os.path.lexists(_fs_path(manifest)):
        raise FileExistsError("Completed manifest appeared during assembly")
    os.rename(pending, _fs_path(manifest))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", required=True)
    parser.add_argument("--world", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--job-lease", required=True)
    args = parser.parse_args()
    report = assemble(args.plan, args.world, args.manifest, args.job_lease)
    print(json.dumps({"world": report["world"], "status": report["status"],
                      "chunks": report["expectedChunks"], "manifest": args.manifest}))


if __name__ == "__main__":
    main()
