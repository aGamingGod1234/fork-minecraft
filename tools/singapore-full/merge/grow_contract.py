"""Read-only admission contract for immutable, edge-connected world sources."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path, PurePosixPath
import re

FRAME = {"crs": "EPSG:3414", "x": "easting", "z": "60000-northing", "blocksPerMeter": 1}
SHA = re.compile(r"^[0-9a-fA-F]{64}$")
REGION = re.compile(r"^region/r\.-?\d+\.-?\d+\.mca$")


class GrowContractError(ValueError):
    pass


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(data)
    return h.hexdigest()


def load(path):
    value = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise GrowContractError(f"expected JSON object: {path}")
    return value


def _sha(value, label):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise GrowContractError(f"invalid SHA-256: {label}")
    return value.lower()


def _bounds(value, label):
    if (not isinstance(value, (list, tuple)) or len(value) != 4
            or any(type(n) is not int or n % 16 for n in value)
            or value[0] >= value[2] or value[1] >= value[3]):
        raise GrowContractError(f"{label} must be positive half-open, 16-aligned bounds")
    return tuple(value)


def _contains(outer, inner):
    return outer[0] <= inner[0] and outer[1] <= inner[1] and outer[2] >= inner[2] and outer[3] >= inner[3]


def _chunk_count(bounds):
    return ((bounds[2] - bounds[0]) // 16) * ((bounds[3] - bounds[1]) // 16)


def _safe_file(world, relative):
    if not isinstance(relative, str) or "\\" in relative:
        raise GrowContractError("output paths must be relative POSIX paths")
    path = PurePosixPath(relative)
    if path.is_absolute() or path.as_posix() != relative or any(p in ("..", ".") or ":" in p for p in path.parts):
        raise GrowContractError(f"unsafe output path: {relative}")
    result = world.joinpath(*path.parts).resolve(strict=True)
    if world not in result.parents or not result.is_file():
        raise GrowContractError("world output escapes source directory or is not a file")
    return result


def _outputs(writer, world):
    if not isinstance(writer.get("outputs"), list) or not writer["outputs"]:
        raise GrowContractError("writer must list its immutable world outputs")
    verified = []
    names = set()
    for item in writer["outputs"]:
        name = item["path"]
        if name in names:
            raise GrowContractError("duplicate writer output")
        names.add(name)
        # Source worlds are clean block worlds; player/entity/map state is not inherited.
        if name != "level.dat" and not REGION.fullmatch(name):
            raise GrowContractError(f"unsupported source-world artifact: {name}")
        path = _safe_file(world, name)
        sha = _sha(item["sha256"], name)
        size = item["bytes"]
        if type(size) is not int or size < 0 or path.stat().st_size != size or digest(path) != sha:
            raise GrowContractError(f"writer output changed: {name}")
        verified.append({"path": name, "bytes": size, "sha256": sha})
    actual = {p.relative_to(world).as_posix() for p in world.rglob("*") if p.is_file()}
    if actual != names or "level.dat" not in names or not any(REGION.fullmatch(n) for n in names):
        raise GrowContractError("world has missing/unlisted files or lacks modern region/ and level.dat")
    return sorted(verified, key=lambda item: item["path"])


def _structural(gate, writer_hash, outputs, core):
    if gate.get("status") != "PASS" or gate.get("synthetic") is True:
        raise GrowContractError("actual structural gate PASS required")
    if _sha(gate.get("writerManifestSha256"), "structural writer binding") != writer_hash:
        raise GrowContractError("structural gate binds a different writer manifest")
    kind = gate.get("kind")
    if kind == "actual-world-structural-validation":
        if gate.get("schemaVersion") != 1 or gate.get("synthetic") is not False:
            raise GrowContractError("generic structural gate must explicitly identify actual schema-1 evidence")
        if type(gate.get("comparedBlocks")) is not int or gate["comparedBlocks"] <= 0 or type(gate.get("mismatches")) is not int or gate["mismatches"] != 0:
            raise GrowContractError("structural block comparison is missing or failed")
        bound_outputs = sorted(({"path": o["path"], "bytes": o["bytes"], "sha256": _sha(o["sha256"], "gate output")}
                                for o in gate.get("worldOutputs", [])), key=lambda o: o["path"])
        if bound_outputs != outputs:
            raise GrowContractError("structural world-output hashes do not match writer outputs")
    elif kind == "independent-joined-strip-structural-gate":
        bounds = _bounds(gate.get("bounds"), "structural gate bounds")
        if not _contains(bounds, core) or type(gate.get("chunkCount")) is not int or gate["chunkCount"] < _chunk_count(core):
            raise GrowContractError("existing structural gate does not cover the owned core")
        if gate.get("fileHashErrors") != []:
            raise GrowContractError("existing structural gate has absent or failed output hash checks")
        # This actual existing gate binds the writer manifest, which binds every
        # output rehashed above. It needs no duplicate oracle/schema rewrite.
    else:
        raise GrowContractError(f"unrecognized actual structural gate kind: {kind}")
    for name in ("errors", "blockErrors", "coordinateErrors"):
        if name in gate and gate[name] != []:
            raise GrowContractError(f"structural gate reports {name}")
    for name in ("mismatches", "blockMismatches", "occupancyMismatches"):
        if name in gate and (type(gate[name]) is not int or gate[name] != 0):
            raise GrowContractError(f"structural gate reports {name}")


def _coverage(component, entry, core, writer_hash):
    if not isinstance(entry, dict) or entry.get("status") not in ("included", "pass", "no_features"):
        raise GrowContractError(f"{component} coverage must be included/pass/no_features with evidence")
    path = Path(entry["evidence_path"]).resolve(strict=True)
    evidence_sha = _sha(entry["evidence_sha256"], component)
    if digest(path) != evidence_sha:
        raise GrowContractError(f"{component} evidence changed")
    evidence = load(path)
    expected_status = "NO_FEATURES" if entry["status"] == "no_features" else "PASS"
    if evidence.get("component") != component or evidence.get("status") != expected_status or evidence.get("synthetic") is True:
        raise GrowContractError(f"{component} typed evidence does not pass")
    if _bounds(evidence.get("coreBounds"), f"{component} evidence core") != core:
        raise GrowContractError(f"{component} evidence covers a different core")
    if _sha(evidence.get("writerManifestSha256"), component) != writer_hash:
        raise GrowContractError(f"{component} evidence binds a different writer")
    _sha(evidence.get("sourceSha256"), f"{component} source")
    count = evidence.get("featureCount")
    if type(count) is not int or count < 0 or (entry["status"] == "no_features" and count != 0) or (entry["status"] != "no_features" and count == 0):
        raise GrowContractError(f"{component} feature count contradicts coverage status")
    if entry["status"] == "no_features" and (evidence.get("sourceCoverageComplete") is not True
            or type(evidence.get("blockedDiagnostics")) is not int or evidence["blockedDiagnostics"] != 0
            or type(evidence.get("unmappedSourceCount")) is not int or evidence["unmappedSourceCount"] != 0):
        raise GrowContractError(f"{component} absence requires complete, unblocked source coverage")
    # A wrapper may bind an existing actual report instead of rerunning an oracle.
    if "reportPath" in evidence or "reportSha256" in evidence:
        report = Path(evidence["reportPath"]).resolve(strict=True)
        if digest(report) != _sha(evidence["reportSha256"], f"{component} wrapped report"):
            raise GrowContractError(f"{component} wrapped report changed")
        report_data = load(report)
        if report_data.get("synthetic") is True:
            raise GrowContractError(f"{component} wrapped actual report failed")
        if report_data.get("schema") == "fork.roads-runs.v1":
            if (type(report_data.get("blockedDiagnostics")) is not int or report_data["blockedDiagnostics"] != 0
                    or _sha(report_data.get("sourceSha256"), "wrapped source") != evidence["sourceSha256"].lower()
                    or type(report_data.get("runCount")) is not int or report_data["runCount"] < 0):
                raise GrowContractError(f"{component} actual road/water raster source is blocked or mismatched")
            runs = Path(evidence["runsPath"]).resolve(strict=True)
            runs_hash = _sha(evidence["runsSha256"], "wrapped runs")
            # outputSha256 binds emitted file bytes; runSha256 is a semantic
            # emitter digest and is deliberately not a filesystem checksum.
            if digest(runs) != runs_hash or runs_hash != _sha(report_data.get("outputSha256"), "report output"):
                raise GrowContractError(f"{component} actual raster run bytes changed")
        elif report_data.get("status") not in ("PASS", "NO_FEATURES"):
            raise GrowContractError(f"{component} wrapped actual report needs a recognized typed source schema")
    return {"status": entry["status"], "evidence_path": str(path), "evidence_sha256": evidence_sha,
            "source_sha256": evidence["sourceSha256"].lower(), "feature_count": count}


def _connected(sources):
    neighbors = {s["id"]: set() for s in sources}
    for i, a in enumerate(sources):
        ax0, az0, ax1, az1 = a["core_bounds"]
        for b in sources[i + 1:]:
            bx0, bz0, bx1, bz1 = b["core_bounds"]
            x_overlap = min(ax1, bx1) > max(ax0, bx0)
            z_overlap = min(az1, bz1) > max(az0, bz0)
            if x_overlap and z_overlap:
                raise GrowContractError(f"overlapping owned cores: {a['id']} and {b['id']}")
            if ((ax1 == bx0 or bx1 == ax0) and z_overlap) or ((az1 == bz0 or bz1 == az0) and x_overlap):
                neighbors[a["id"]].add(b["id"])
                neighbors[b["id"]].add(a["id"])
    seen, pending = set(), [sources[0]["id"]]
    while pending:
        name = pending.pop()
        if name not in seen:
            seen.add(name)
            pending.extend(neighbors[name] - seen)
    if len(seen) != len(sources):
        raise GrowContractError("owned cores must connect by edges; detached/corner-only islands rejected")


def iter_owned_chunks(sources):
    """Yield exact owned chunk keys without constructing an island-sized set."""
    for source in sorted(sources, key=lambda source: source["id"]):
        x0, z0, x1, z1 = source["core_bounds"]
        for cz in range(z0 // 16, z1 // 16):
            for cx in range(x0 // 16, x1 // 16):
                yield cx, cz


def validate_plan(plan):
    """Verify immutable source files, gates, coverage and connected core ownership."""
    try:
        if not isinstance(plan, dict) or plan.get("schemaVersion") != 1 or plan.get("allowDetached", False) is not False:
            raise GrowContractError("schema-1 connected-world plan required; detached worlds are a future contract")
        if not isinstance(plan.get("sources"), list) or not plan["sources"]:
            raise GrowContractError("at least one source is required")
        sources, ids, versions = [], set(), set()
        for raw in plan["sources"]:
            name = raw["id"]
            if not isinstance(name, str) or not name or name in ids:
                raise GrowContractError("source ids must be nonempty and unique")
            ids.add(name)
            core = _bounds(raw["core_bounds"], name + " core")
            world = Path(raw["world_path"]).resolve(strict=True)
            if not world.is_dir() or not (world / "region").is_dir():
                raise GrowContractError("source needs a modern root region/ directory")
            writer_path = Path(raw["writer_manifest_path"]).resolve(strict=True)
            writer_hash, writer = digest(writer_path), load(writer_path)
            if writer.get("coordinateFrame") != FRAME:
                raise GrowContractError("source must preserve exact EPSG:3414 x=E,z=60000-N,1m grid")
            bounds = _bounds(writer["bounds"], name + " writer bounds")
            if not _contains(bounds, core):
                raise GrowContractError("owned core extends outside writer bounds")
            version = writer["dataVersion"]
            if type(version) is not int or version < 2844:
                raise GrowContractError("modern DataVersion >=2844 required")
            versions.add(version)
            outputs = _outputs(writer, world)
            gate_path = Path(raw["structural_gate_path"]).resolve(strict=True)
            _structural(load(gate_path), writer_hash, outputs, core)
            coverage = {component: _coverage(component, raw["coverage"].get(component), core, writer_hash)
                        for component in ("roads", "water")}
            sources.append({"id": name, "world_path": str(world), "core_bounds": list(core),
                            "writer_manifest_path": str(writer_path), "writer_manifest_sha256": writer_hash,
                            "structural_gate_path": str(gate_path), "structural_gate_sha256": digest(gate_path),
                            "outputs": outputs, "coverage": coverage})
        if len(versions) != 1:
            raise GrowContractError("all source DataVersions must match")
        sources.sort(key=lambda source: source["id"])
        _connected(sources)
        extent = [min(s["core_bounds"][0] for s in sources), min(s["core_bounds"][1] for s in sources),
                  max(s["core_bounds"][2] for s in sources), max(s["core_bounds"][3] for s in sources)]
        return {"sources": sources, "extent": extent, "expected_chunks": sum(_chunk_count(s["core_bounds"]) for s in sources),
                "data_version": next(iter(versions)), "coordinate_frame": dict(FRAME)}
    except (KeyError, TypeError, OSError, json.JSONDecodeError) as exc:
        raise GrowContractError(f"missing or unreadable grow evidence: {exc}") from exc
