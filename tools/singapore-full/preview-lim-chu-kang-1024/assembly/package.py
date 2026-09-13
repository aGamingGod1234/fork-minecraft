"""Immutable, resumable merge receipts. No existing world is installed or edited."""
from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import os
from pathlib import Path
import stat
from typing import Any, Iterable
import uuid

SCHEMA = "fork.singapore.merge-package.v1"
OUTPUT_ROOT = Path(r"C:\Users\User\AppData\Local\FORK-Tools\fork-singapore-full\merged")
FORBIDDEN_PARTS = {"playerdata", "players", "session.lock", "fork", "entities",
                   "credentials", "secrets", ".git", ".ssh", ".codex"}
FORBIDDEN_FILES = {"auth.json", "accounts.json", "launcher_accounts.json", "credentials.json",
                   "credentials.yml", "credentials.yaml", "token.json", "tokens.json"}


class GateError(ValueError):
    """A failed receipt/gate must not be resumed or delivered."""


def _canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
                      allow_nan=False).encode("utf-8")


def _digest(value: Any) -> str:
    return hashlib.sha256(_canonical(value)).hexdigest()


def _plain_path(path: Path) -> None:
    # Windows junctions are not always reported as symlinks by Python 3.11.
    for item in (path, *path.parents):
        if item.exists() or item.is_symlink():
            info = item.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
                raise GateError(f"Reparse/symlink path is not accepted: {item}")


def _inside(path: Path, parent: Path) -> bool:
    return path == parent or parent in path.parents


def _resolve(path: str | Path) -> Path:
    candidate = Path(path).absolute()
    _plain_path(candidate)
    candidate = candidate.resolve(strict=False)
    _plain_path(candidate)
    return candidate


def _file_record(path: Path) -> dict[str, Any]:
    _plain_path(path)
    before = path.stat()
    if not stat.S_ISREG(before.st_mode):
        raise GateError(f"Expected regular file: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise GateError(f"File changed while hashing: {path}")
    return {"bytes": after.st_size, "sha256": digest.hexdigest()}


def snapshot_tree(root: str | Path) -> dict[str, Any]:
    root = _resolve(root)
    if not root.is_dir():
        raise GateError(f"Expected world directory: {root}")
    files: dict[str, dict[str, Any]] = {}
    for parent, dirs, names in os.walk(root, followlinks=False):
        for name in sorted(dirs + names):
            _plain_path(Path(parent) / name)
        for name in sorted(names):
            path = Path(parent) / name
            files[path.relative_to(root).as_posix()] = _file_record(path)
    files = dict(sorted(files.items()))
    return {"sha256": _digest(files), "files": files}


def _atomic_write(path: Path, value: dict[str, Any]) -> None:
    _plain_path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    value = dict(value)
    value.pop("manifest_sha256", None)
    value["manifest_sha256"] = _digest(value)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    # A crash leaves a uniquely named untrusted temp file; never use it to resume.
    with temporary.open("xb") as target:
        target.write(_canonical(value) + b"\n")
        target.flush()
        os.fsync(target.fileno())
    os.replace(temporary, path)


def _read(path: str | Path) -> dict[str, Any]:
    path = _resolve(path)
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
        expected = value.pop("manifest_sha256")
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise GateError(f"Invalid manifest: {path}") from exc
    if value.get("schema") != SCHEMA or _digest(value) != expected:
        raise GateError("Manifest schema or integrity hash mismatch")
    value["manifest_sha256"] = expected
    return value


def _output_path(path: str | Path) -> Path:
    candidate = _resolve(path)
    allowed = _resolve(OUTPUT_ROOT)
    if candidate == allowed or not _inside(candidate, allowed):
        raise GateError(f"New output must be below {allowed}")
    return candidate


def _seam_receipt(gate: dict[str, Any]) -> dict[str, Any]:
    if gate.get("status") != "PASS":
        raise GateError("Structural seam gate must be PASS; FAIL/UNKNOWN cannot publish")
    if not gate.get("evidence_path"):
        raise GateError("Seam PASS requires a retained evidence file")
    evidence = _resolve(gate["evidence_path"])
    record = _file_record(evidence)
    if gate.get("evidence_sha256") and gate["evidence_sha256"].lower() != record["sha256"]:
        raise GateError("Seam evidence hash mismatch")
    try:
        report = json.loads(evidence.read_text(encoding="utf-8-sig"))
        if gate.get("synthetic") is True:
            if report.get("synthetic") is not True or report.get("status") != "PASS":
                raise GateError("Synthetic receipt must explicitly declare synthetic PASS")
        else:
            if report.get("synthetic"):
                raise GateError("Synthetic seam report cannot certify real input")
            importlib.import_module("translation").require_seam_gate(report, report["left"], report["right"])
    except GateError:
        raise
    except Exception as exc:
        raise GateError("Structural seam evidence did not pass: " + str(exc)) from exc
    if _file_record(evidence) != record:
        raise GateError("Seam evidence changed during validation")
    return {**gate, "evidence_path": str(evidence), "evidence_sha256": record["sha256"]}


def initialize_manifest(manifest_path: str | Path, output_world: str | Path,
                        tile_inputs: Iterable[dict[str, Any]],
                        coordinate_contract: dict[str, Any], seam_gate: dict[str, Any],
                        *, synthetic: bool = False) -> dict[str, Any]:
    """Create an empty NEW output and a checkpoint outside it after all source checks."""
    manifest_path = _resolve(manifest_path)
    if manifest_path.exists():
        raise GateError("Manifest already exists; use verify_manifest to resume")
    output = _output_path(output_world)
    if output.exists():
        raise GateError("Output already exists; refusing to adopt or overwrite a world")
    if _inside(manifest_path, output):
        raise GateError("Manifest must be outside the hashed output world")
    if not isinstance(coordinate_contract, dict) or not coordinate_contract:
        raise GateError("An explicit coordinate contract is required")
    seam = _seam_receipt(seam_gate)
    if seam.get("synthetic") and not synthetic:
        raise GateError("Synthetic seam evidence cannot certify a real world")
    tiles = []
    seen = set()
    for item in tile_inputs:
        tile_id = item.get("id")
        bounds = item.get("core_bounds")
        if not isinstance(tile_id, str) or not tile_id or tile_id in seen:
            raise GateError("Tile IDs must be unique nonempty strings")
        if (not isinstance(bounds, (list, tuple)) or len(bounds) != 4
                or any(type(n) is not int for n in bounds)
                or bounds[0] >= bounds[2] or bounds[1] >= bounds[3]):
            raise GateError(f"Invalid half-open core_bounds for tile {tile_id}")
        source = _resolve(item["path"])
        if _inside(output, source) or _inside(source, output):
            raise GateError("Input and output directories cannot overlap")
        if _inside(manifest_path, source):
            raise GateError("Manifest cannot alter an input directory")
        snapshot = snapshot_tree(source)
        if not snapshot["files"]:
            raise GateError(f"Tile input is empty: {tile_id}")
        tiles.append({**item, "id": tile_id, "path": str(source), "core_bounds": list(bounds),
                      "input_sha256": snapshot["sha256"], "files": snapshot["files"]})
        seen.add(tile_id)
    if not tiles:
        raise GateError("At least one tile input is required")
    if not synthetic:
        report = json.loads(Path(seam["evidence_path"]).read_text(encoding="utf-8-sig"))
        if seen != {report["left"], report["right"]}:
            raise GateError("This seam receipt certifies exactly its two input tiles; more tiles need aggregate seam evidence")
    receipt = {"schema": SCHEMA, "state": "IN_PROGRESS", "synthetic": bool(synthetic),
               "evidence_scope": "SYNTHETIC FIXTURE ONLY" if synthetic else "REAL TILE INPUTS",
               "package_ready": False, "install_ready": False,
               "runtime_load_gate": {"status": "PENDING", "minecraft": "26.1.2"},
               "manual_appearance_gate": {"status": "PENDING", "device": "Laptop"},
               "output_world": str(output),
               "coordinate_contract": coordinate_contract,
               "coordinate_contract_sha256": _digest(coordinate_contract),
               "seam_gate": seam, "tiles": sorted(tiles, key=lambda tile: tile["id"]),
               "outputs": {}, "output_tree_sha256": _digest({}), "clean_world_gate": None}
    output.mkdir(parents=True, exist_ok=False)
    _atomic_write(manifest_path, receipt)
    return _read(manifest_path)


def verify_manifest(manifest_path: str | Path, *, allow_new_outputs: bool = False) -> dict[str, Any]:
    """Resume only with the same tiles, contract, seam evidence and recorded output bytes."""
    receipt = _read(manifest_path)
    if receipt["state"] not in ("IN_PROGRESS", "SEALED"):
        raise GateError("Unknown checkpoint state")
    output = _output_path(receipt["output_world"])
    if _digest(receipt["coordinate_contract"]) != receipt["coordinate_contract_sha256"]:
        raise GateError("Coordinate contract changed")
    if _seam_receipt(receipt["seam_gate"]) != receipt["seam_gate"]:
        raise GateError("Seam receipt changed")
    for tile in receipt["tiles"]:
        current = snapshot_tree(tile["path"])
        if current["sha256"] != tile["input_sha256"] or current["files"] != tile["files"]:
            raise GateError(f"Input tile changed: {tile['id']}")
    current = snapshot_tree(output)
    if _digest(receipt["outputs"]) != receipt["output_tree_sha256"]:
        raise GateError("Recorded output tree hash mismatch")
    for relative, previous in receipt["outputs"].items():
        if current["files"].get(relative) != previous:
            raise GateError(f"Checkpointed output changed or missing: {relative}")
    unknown = sorted(set(current["files"]) - set(receipt["outputs"]))
    if unknown and (not allow_new_outputs or receipt["state"] == "SEALED"):
        raise GateError(f"Uncheckpointed output is not reusable: {unknown[0]}")
    if receipt["state"] == "SEALED":
        clean = validate_clean_world(output)
        if not clean["ok"]:
            raise GateError("Sealed world no longer clean: " + "; ".join(clean["issues"]))
    return receipt


def checkpoint_manifest(manifest_path: str | Path,
                        completed_files: Iterable[str] | None = None) -> dict[str, Any]:
    """Checkpoint completed, atomically written files; old files must stay immutable."""
    receipt = verify_manifest(manifest_path, allow_new_outputs=True)
    if receipt["state"] != "IN_PROGRESS":
        raise GateError("Cannot add files to a sealed manifest")
    current = snapshot_tree(receipt["output_world"])
    selected = set(current["files"]) if completed_files is None else set(completed_files)
    if not selected.issubset(current["files"]):
        raise GateError("Completed file is absent, noncanonical or outside the output world")
    receipt["outputs"].update({name: current["files"][name] for name in selected})
    receipt["outputs"] = dict(sorted(receipt["outputs"].items()))
    receipt["output_tree_sha256"] = _digest(receipt["outputs"])
    _atomic_write(Path(manifest_path), receipt)
    return _read(manifest_path)


def _has_player_tag(tag: Any) -> bool:
    if getattr(tag, "type_id", None) == 10:
        return any(str(name).casefold() == "player" or _has_player_tag(child)
                   for name, child in tag.value.items())
    if getattr(tag, "type_id", None) == 9:
        return any(_has_player_tag(child) for child in tag.value.items)
    return False


def validate_clean_world(world: str | Path) -> dict[str, Any]:
    """Inspect paths and level metadata without installing, sanitizing or deleting."""
    world = _resolve(world)
    issues = []
    try:
        snapshot = snapshot_tree(world)
    except (GateError, OSError) as exc:
        return {"ok": False, "issues": [str(exc)]}
    for parent, dirs, files in os.walk(world, followlinks=False):
        for name in dirs + files:
            relative = (Path(parent) / name).relative_to(world).as_posix()
            lowered = name.casefold()
            if (lowered in FORBIDDEN_PARTS or lowered in FORBIDDEN_FILES
                    or lowered == ".env" or lowered.startswith(".env.")
                    or lowered.startswith("credentials.")
                    or lowered.endswith((".pem", ".key", ".pfx", ".p12"))):
                issues.append("Forbidden runtime/credential path: " + relative)
    if "level.dat" not in snapshot["files"]:
        issues.append("Missing level.dat")
    for name in ("level.dat", "level.dat_old"):
        if name in snapshot["files"]:
            try:
                nbt = importlib.import_module("anvil").read_level_dat(world / name)
                if _has_player_tag(nbt.root):
                    issues.append("Embedded Player tag: " + name)
            except Exception as exc:
                issues.append("Cannot validate level NBT: " + name + " (" + type(exc).__name__ + ")")
    if not any(name.startswith("region/") and name.endswith(".mca") for name in snapshot["files"]):
        issues.append("Missing overworld region files")
    return {"ok": not issues, "issues": issues, "file_count": len(snapshot["files"]),
            "scope": "Path and level metadata gate; driver separately validates chunk/entity/map semantics"}


def seal_manifest(manifest_path: str | Path) -> dict[str, Any]:
    receipt = verify_manifest(manifest_path)
    gate = validate_clean_world(receipt["output_world"])
    if not gate["ok"]:
        raise GateError("Clean world gate failed: " + "; ".join(gate["issues"]))
    receipt["clean_world_gate"] = gate
    receipt["state"] = "SEALED"
    receipt["package_ready"] = not receipt["synthetic"]
    _atomic_write(Path(manifest_path), receipt)
    return _read(manifest_path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("initialize", "checkpoint", "verify", "seal", "clean"))
    parser.add_argument("path", help="Manifest path, or world path for clean")
    parser.add_argument("--spec", help="JSON with output_world, tile_inputs, coordinate_contract, seam_gate")
    args = parser.parse_args()
    try:
        if args.action == "initialize":
            if not args.spec:
                parser.error("initialize requires --spec")
            result = initialize_manifest(args.path, **json.loads(Path(args.spec).read_text(encoding="utf-8-sig")))
        else:
            result = {"checkpoint": checkpoint_manifest, "verify": verify_manifest,
                      "seal": seal_manifest, "clean": validate_clean_world}[args.action](args.path)
        print(json.dumps(result, indent=2))
        if result.get("ok") is False:
            raise SystemExit(2)
    except (GateError, OSError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
