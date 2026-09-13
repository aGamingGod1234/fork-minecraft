"""Read-only admission contract for immutable, edge-connected world sources."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess

FRAME = {"crs": "EPSG:3414", "x": "easting", "z": "60000-northing", "blocksPerMeter": 1}
SHA = re.compile(r"^[0-9a-fA-F]{64}$")
REGION_DIRECTORY = "dimensions/minecraft/overworld/region"
WORLD_SETTINGS = "data/minecraft/world_gen_settings.dat"
REGION = re.compile(r"^dimensions/minecraft/overworld/region/r\.-?\d+\.-?\d+\.mca$")
COMPONENT_LOADERS = frozenset(("validate-east-world-gate.mjs", "validate-ring-world-gate.mjs",
                              "validate-transfer-world-gate.mjs"))


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
        if name not in ("level.dat", WORLD_SETTINGS) and not REGION.fullmatch(name):
            raise GrowContractError(f"unsupported source-world artifact: {name}")
        path = _safe_file(world, name)
        sha = _sha(item["sha256"], name)
        size = item["bytes"]
        if type(size) is not int or size < 0 or path.stat().st_size != size or digest(path) != sha:
            raise GrowContractError(f"writer output changed: {name}")
        verified.append({"path": name, "bytes": size, "sha256": sha})
    actual = {p.relative_to(world).as_posix() for p in world.rglob("*") if p.is_file()}
    if actual != names or not {"level.dat", WORLD_SETTINGS} <= names or not any(REGION.fullmatch(n) for n in names):
        raise GrowContractError("world has missing/unlisted files or lacks MC26 regions, level.dat and external world settings")
    return sorted(verified, key=lambda item: item["path"])


def _benchmark_structural(gate, source, writer_hash, outputs, core, world, writer_path):
    """Reuse the actual hash-pinned read-only validator; never relabel its gate."""
    primary = Path(source["structural_gate_path"]).resolve(strict=True)
    summary_path = Path(source.get("benchmark_summary_path", primary.parent / "summary.json")).resolve(strict=True)
    summary = load(summary_path)
    if gate.get("kind") == "measured-independent-benchmark-validation":
        if primary != summary_path:
            raise GrowContractError("measured summary must be the supplied summary evidence")
        gate_path = Path(source.get("benchmark_gate_path", primary.parent / "gate.json")).resolve(strict=True)
        gate = load(gate_path)
    else:
        gate_path = primary
    if (summary.get("schemaVersion") != 1 or summary.get("kind") != "measured-independent-benchmark-validation"
            or summary.get("status") != "PASS" or summary.get("metrics", {}).get("exitCode") != 0
            or summary.get("metrics", {}).get("timedOut") is not False
            or summary.get("metrics", {}).get("parentExited") is not False):
        raise GrowContractError("actual measured independent validation summary must PASS")
    if digest(gate_path) != _sha(summary.get("gateSha256"), "measured gate"):
        raise GrowContractError("measured summary binds a different gate")
    if (gate.get("schemaVersion") != 1 or gate.get("kind") != "independent-benchmark-result-gate"
            or gate.get("status") != "PASS" or gate.get("comparisonScope") != "full-volume"
            or _bounds(gate.get("comparisonBounds"), "benchmark core") != core
            or core[2] - core[0] != 1024 or core[3] - core[1] != 1024
            or type(gate.get("comparedCells")) is not int or gate["comparedCells"] != 402653184
            or type(gate.get("mismatchedCells")) is not int or gate["mismatchedCells"] != 0
            or gate.get("fileHashErrors") != []
            or any(gate.get("checks", {}).get(k) is not True for k in ("globalChunkCoordinates", "metadata", "heightmaps"))):
        raise GrowContractError("actual benchmark gate must cover every 1024-core cell without mismatches")
    receipt_path = Path(source.get("benchmark_receipt_path", summary["receiptPath"])).resolve(strict=True)
    if receipt_path != Path(summary["receiptPath"]).resolve(strict=True):
        raise GrowContractError("benchmark receipt path differs from measured summary")
    receipt_hash = digest(receipt_path)
    if receipt_hash != _sha(summary.get("benchmarkReceiptSha256"), "summary receipt") or receipt_hash != _sha(gate.get("benchmarkReceiptSha256"), "gate receipt"):
        raise GrowContractError("benchmark receipt bytes do not match measured gate")
    evidence = gate["evidence"]
    if (Path(evidence["writerManifest"]["path"]).resolve(strict=True) != writer_path
            or _sha(evidence["writerManifest"]["sha256"], "benchmark writer") != writer_hash):
        raise GrowContractError("benchmark gate binds a different writer")
    if _sha(summary.get("oracleSha256"), "measured oracle") != _sha(evidence["oracleProof"]["sha256"], "gate oracle"):
        raise GrowContractError("summary and gate oracle bindings differ")
    gate_outputs = sorted(({"path": o["path"], "bytes": o["bytes"], "sha256": _sha(o["sha256"], "benchmark output")}
                           for o in gate["writerOutputHashes"]), key=lambda o: o["path"])
    if gate_outputs != outputs:
        raise GrowContractError("benchmark gate world output map differs from writer")
    validator = Path(source.get("benchmark_validator_path", Path(__file__).resolve().parent.parent / "validate-benchmark.mjs")).resolve(strict=True)
    if validator.name != "validate-benchmark.mjs":
        raise GrowContractError("benchmark validator must be the existing typed validation module")
    code_hashes = summary["validatorCodeHashes"]
    validator_hashes = []
    for name in ("validate-benchmark.mjs", "validate-benchmark-bindings.mjs"):
        path = validator.parent / name
        sha = digest(path)
        if sha != _sha(code_hashes[name], "measured validator code"):
            raise GrowContractError("read-only validator code differs from measured validation")
        validator_hashes.append({"path": str(path), "sha256": sha})
    node = shutil.which("node")
    if not node:
        raise GrowContractError("existing Node runtime required for actual binding validation")
    script = ("import {pathToFileURL} from 'node:url';"
              "const m=await import(pathToFileURL(process.argv[2]).href);"
              "const v=m.loadIndependentlyValidatedReceipt(process.argv[3],process.argv[4]);"
              "console.log(JSON.stringify({status:v.status,bindings:v.resultBindings}));")
    # Keep argv[1] different from the imported module so its CLI entry-point
    # guard cannot mistake this read-only call for a benchmark execution.
    completed = subprocess.run([node, "--max-old-space-size=384", "--input-type=module", "-e", script,
                                "fork-grow-readonly-bindings", str(validator), str(receipt_path), str(gate_path)],
                               capture_output=True, text=True, encoding="utf-8", timeout=120)
    if completed.returncode != 0:
        raise GrowContractError("actual validateResultBindings rejected source: " + completed.stderr[-2000:])
    result = json.loads(completed.stdout)
    bindings = result["bindings"]
    if (result.get("status") != "PASS" or Path(bindings["worldRoot"]).resolve(strict=True) != world
            or Path(bindings["writerManifestPath"]).resolve(strict=True) != writer_path
            or _bounds(bindings["coreBounds"], "verified benchmark core") != core):
        raise GrowContractError("verified benchmark identity differs from requested grow source")
    rebound = sorted(({"path": Path(o["path"]).resolve(strict=True).relative_to(world).as_posix(),
                       "bytes": o["bytes"], "sha256": _sha(o["sha256"], "verified output")}
                      for o in bindings["outputs"]), key=lambda o: o["path"])
    if rebound != outputs:
        raise GrowContractError("verified benchmark output bindings differ from grow outputs")
    return {"benchmark_summary_path": str(summary_path), "benchmark_summary_sha256": digest(summary_path),
            "benchmark_receipt_path": str(receipt_path), "benchmark_receipt_sha256": receipt_hash,
            "benchmark_gate_path": str(gate_path), "benchmark_gate_sha256": digest(gate_path),
            "benchmark_validator_code": validator_hashes}


def _component_role(gate, source, core, world, writer_path):
    if (gate.get("role") != "assembly-component" or gate.get("standaloneStatus") != "NOT_STANDALONE"
            or type(gate.get("componentSpawnAccepted")) is not bool
            or gate.get("finalAssembledSafeSpawnRequired") is not True
            or gate.get("runtimeAccepted") is not False or gate.get("fullWorldAccepted") is not False
            or gate.get("coreBounds") != list(core)
            or gate.get("comparedBlocks") != _chunk_count(core) * 256 * 384):
        raise GrowContractError("assembly component requires exact geometry-only role and final safe-spawn obligation")
    record = gate["evidence"]["gateModule"]
    validator = Path(record["path"]).resolve(strict=True)
    validator_hash = _sha(record["sha256"], "assembly component loader")
    if validator.name not in COMPONENT_LOADERS or digest(validator) != validator_hash or validator.stat().st_size != record["bytes"]:
        raise GrowContractError("actual assembly component loader code changed")
    node = shutil.which("node")
    if not node:
        raise GrowContractError("existing Node runtime required for exact component evidence reload")
    gate_path = Path(source["structural_gate_path"]).resolve(strict=True)
    gate_hash = digest(gate_path)
    script = ("import {pathToFileURL} from 'node:url';"
              "const m=await import(pathToFileURL(process.argv[2]).href);"
              "const g=await m.loadEastWorldGate(process.argv[3],process.argv[4]);"
              "console.log(JSON.stringify({role:g.role,coreBounds:g.coreBounds,worldRoot:g.worldRoot,"
              "writerPath:g.evidence.writerManifest.path,componentSpawnAccepted:g.componentSpawnAccepted}));")
    completed = subprocess.run([node, "--max-old-space-size=384", "--input-type=module", "-e", script,
                                "fork-component-readonly-bindings", str(validator), str(gate_path), gate_hash],
                               capture_output=True, text=True, encoding="utf-8", timeout=120)
    if completed.returncode != 0:
        raise GrowContractError("actual component geometry proof reload failed: " + completed.stderr[-2000:])
    fresh = json.loads(completed.stdout)
    if (fresh.get("role") != "assembly-component" or fresh.get("coreBounds") != list(core)
            or Path(fresh["worldRoot"]).resolve(strict=True) != world
            or Path(fresh["writerPath"]).resolve(strict=True) != writer_path
            or fresh.get("componentSpawnAccepted") is not gate["componentSpawnAccepted"]):
        raise GrowContractError("reloaded component role/identity differs from requested source")
    return {"role": "assembly-component", "standalone_status": "NOT_STANDALONE",
            "component_spawn_accepted": gate["componentSpawnAccepted"], "final_assembled_safe_spawn_required": True,
            "component_validator_path": str(validator), "component_validator_sha256": validator_hash}


def _validate_spawn_selection(plan, sources):
    components = [s for s in sources if s.get("role") == "assembly-component"]
    if not components:
        return None
    selected_id = plan.get("spawn_source_id")
    selected = next((s for s in sources if s["id"] == selected_id), None)
    if selected is None or selected.get("role") == "assembly-component":
        raise GrowContractError("assembly components require plan.spawn_source_id naming a separate standalone safe source")
    # Read only the selected source's actual spawn region before allowing copy.
    import anvil
    from grow_receipt import _spawn_check
    data = anvil.read_level_dat(Path(selected["world_path"]) / "level.dat").root.value["Data"].value
    return _spawn_check(Path(selected["world_path"]), data, selected)


def _structural(gate, writer_hash, outputs, core, source=None, world=None, writer_path=None):
    if gate.get("kind") in ("independent-benchmark-result-gate", "measured-independent-benchmark-validation"):
        return _benchmark_structural(gate, source, writer_hash, outputs, core, world, writer_path)
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
        if "fileHashErrors" in gate:
            if gate["fileHashErrors"] != []:
                raise GrowContractError("existing structural gate has failed output hash checks")
        else:
            # The accepted MC26 repair rebinds the earlier full block proof by
            # region identity and separately rechecks external settings/spawn.
            if gate.get("regionDirectory") != REGION_DIRECTORY or gate.get("errors") != []:
                raise GrowContractError("rebound structural gate lacks modern layout/error proof")
            if type(gate.get("comparedCells")) is not int or gate["comparedCells"] <= 0:
                raise GrowContractError("rebound structural gate lacks full block comparison proof")
            _sha(gate.get("priorGeometryOracleSha256"), "prior full block proof")
            _sha(gate.get("metadataProofSha256"), "MC26 metadata proof")
            identity = sorted((o["newPath"], _sha(o["sha256"], "rebound region")) for o in gate.get("regionIdentity", []))
            expected = sorted((o["path"], o["sha256"]) for o in outputs if REGION.fullmatch(o["path"]))
            if identity != expected:
                raise GrowContractError("rebound region identities differ from immutable source outputs")
        # This actual existing gate binds the writer manifest, which binds every
        # output rehashed above. It needs no duplicate oracle/schema rewrite.
    else:
        raise GrowContractError(f"unrecognized actual structural gate kind: {kind}")
    for name in ("errors", "blockErrors", "coordinateErrors"):
        if name in gate and gate[name] != []:
            raise GrowContractError(f"structural gate reports {name}")
    for name in ("mismatches", "blockMismatches", "occupancyMismatches", "mismatchedCells", "seamMismatchedCells", "heightmapMismatches"):
        if name in gate and (type(gate[name]) is not int or gate[name] != 0):
            raise GrowContractError(f"structural gate reports {name}")
    gate_module = gate.get("evidence", {}).get("gateModule", {})
    component_module = Path(gate_module.get("path", "")).name in COMPONENT_LOADERS
    if (gate.get("role") == "assembly-component" or gate.get("standaloneStatus") == "NOT_STANDALONE"
            or gate.get("finalAssembledSafeSpawnRequired") is True
            or gate.get("componentSpawnAccepted") is False
            or component_module):
        # Typed component proof cannot downgrade into the legacy generic path
        # by removing/changing only its role discriminator.
        return _component_role(gate, source, core, world, writer_path)


def _coast_chain(evidence, report):
    """Verify the typed masked-coast chain carried by an existing child wrapper."""
    if (report.get("status") != "PASS" or report.get("synthetic") is not False
            or report.get("component") != "water" or report.get("coreBounds") != evidence["coreBounds"]
            or type(report.get("comparedBlocks")) is not int or report["comparedBlocks"] <= 0
            or type(report.get("mismatches")) is not int or report["mismatches"] != 0):
        raise GrowContractError("actual masked-coast component oracle did not pass")
    for field in ("writerManifestSha256", "sourceSha256", "runsSha256"):
        if _sha(report.get(field), "coast oracle " + field) != _sha(evidence.get(field), "coast child " + field):
            raise GrowContractError("coast oracle differs from child " + field)
    mask = evidence["maskEvidence"]
    mask_path = Path(mask["maskManifestPath"]).resolve(strict=True)
    if digest(mask_path) != _sha(mask["maskManifestSha256"], "coast mask manifest"):
        raise GrowContractError("coast mask manifest changed")
    masked = load(mask_path)
    if (masked.get("schema") != "fork.masked-runs.v1" or masked.get("allLayersFiltered") is not True
            or masked.get("sourcesUnchanged") is not True
            or _sha(masked.get("outputSha256"), "coast masked output") != _sha(evidence["runsSha256"], "coast child runs")):
        raise GrowContractError("coast mask does not bind actual filtered child runs")
    raw = Path(mask["rawRunsPath"]).resolve(strict=True)
    raw_hash = _sha(mask["rawRunsSha256"], "raw coast runs")
    if digest(raw) != raw_hash or not any(_sha(item.get("sha256"), "coast mask input") == raw_hash for item in masked.get("inputs", [])):
        raise GrowContractError("masked coast does not bind unchanged raw runs")
    for field in ("countryMaskSha256", "foreignExclusionsSha256"):
        if not (_sha(mask.get(field), field) == _sha(masked.get(field), field) == _sha(report.get(field), field)):
            raise GrowContractError("coast country/foreign mask bindings disagree")
        mask_input = Path(mask[field.replace("Sha256", "Path")]).resolve(strict=True)
        if digest(mask_input) != _sha(mask[field], field):
            raise GrowContractError("coast country/foreign mask file changed")
    source_report = Path(evidence["sourceReportPath"]).resolve(strict=True)
    if digest(source_report) != _sha(evidence["sourceReportSha256"], "coast source report"):
        raise GrowContractError("coast source report changed")
    source = load(source_report)
    if (source.get("schema") != "fork.coast-surface.v1" or source.get("status") != "emitted-unclipped"
            or _sha(source.get("maskSha256"), "coast classification mask") != _sha(evidence["sourceSha256"], "coast source")
            or _sha(source.get("outputSha256"), "coast raw output") != raw_hash
            or source.get("statistics", {}).get("unknownCells") != 0):
        raise GrowContractError("coast source classification or raw output is unverified")


def _multi_coverage(component, entry, evidence, path, evidence_sha, core, writer_hash, writer_inputs):
    if (component != "water" or evidence.get("schemaVersion") != 1 or evidence.get("synthetic") is not False
            or evidence.get("component") != "water" or _bounds(evidence.get("coreBounds"), "multi-source core") != core
            or _sha(evidence.get("writerManifestSha256"), "multi-source writer") != writer_hash):
        raise GrowContractError("multi-source water identity does not match the grow core/writer")
    required = {"coast-water", "inland-water"}
    if not isinstance(evidence.get("requiredContributors"), list) or len(evidence["requiredContributors"]) != 2 or set(evidence["requiredContributors"]) != required:
        raise GrowContractError("multi-source water requires exactly coast-water and inland-water")
    source_set_path = Path(evidence["sourceSetPath"]).resolve(strict=True)
    source_hash = _sha(evidence["sourceSha256"], "component source set")
    if digest(source_set_path) != source_hash:
        raise GrowContractError("immutable component source set changed")
    source_set = load(source_set_path)
    if (source_set.get("schemaVersion") != 1 or source_set.get("kind") != "fork-component-source-set"
            or source_set.get("component") != component or source_set.get("coreBounds") != list(core)
            or _sha(source_set.get("writerManifestSha256"), "source-set writer") != writer_hash):
        raise GrowContractError("component source-set identity mismatch")
    def keyed(records, label):
        if not isinstance(records, list) or len(records) != 2 or any(not isinstance(r, dict) or not isinstance(r.get("id"), str) for r in records):
            raise GrowContractError(label + " must contain exactly two typed contributors")
        result = {r["id"]: r for r in records}
        if len(result) != len(records) or set(result) != required:
            raise GrowContractError(label + " contributor set is incomplete or duplicated")
        return result
    declared = keyed(evidence.get("contributors"), "aggregate")
    expected = keyed(source_set.get("contributors"), "source-set")
    if not isinstance(writer_inputs, list) or not writer_inputs:
        raise GrowContractError("multi-source water needs the actual writer consumed-input map")
    children, child_proofs, files, feature_ids = [], [], set(), set()
    for name in sorted(required):
        record, pinned = declared[name], expected[name]
        child_path = Path(record["evidencePath"]).resolve(strict=True)
        if child_path in files or child_path == path or child_path == source_set_path:
            raise GrowContractError("duplicate or cyclic component evidence")
        files.add(child_path)
        child_sha = _sha(record["evidenceSha256"], "child evidence")
        if child_sha != _sha(pinned["evidenceSha256"], "source-set child") or digest(child_path) != child_sha:
            raise GrowContractError("child evidence differs from immutable source set")
        child = load(child_path)
        if child.get("kind") == "fork-multi-source-component-evidence":
            raise GrowContractError("nested aggregate is not a typed coast/inland child")
        if child.get("status") not in ("PASS", "NO_FEATURES") or record.get("status") != child["status"] or record.get("featureCount") != child.get("featureCount"):
            raise GrowContractError("aggregate child status/count differs from actual wrapper")
        for field in ("sourceSha256", "runsSha256"):
            if not (_sha(record.get(field), field) == _sha(pinned.get(field), field) == _sha(child.get(field), field)):
                raise GrowContractError("component child hash differs from source set: " + field)
        source_report = Path(child["sourceReportPath"]).resolve(strict=True)
        source_report_sha = _sha(child["sourceReportSha256"], "child source report")
        if source_report_sha != _sha(pinned["sourceReportSha256"], "source-set report") or digest(source_report) != source_report_sha:
            raise GrowContractError("component source report changed")
        if "sourceReportSha256" in record and _sha(record["sourceReportSha256"], "aggregate source report") != source_report_sha:
            raise GrowContractError("aggregate source report hash contradicts child")
        schema = load(source_report).get("schema")
        if schema != ("fork.coast-surface.v1" if name == "coast-water" else "fork.roads-runs.v1"):
            raise GrowContractError("contributor does not use its required actual coast/inland source")
        runs = Path(child["runsPath"]).resolve(strict=True)
        runs_hash, size = _sha(child["runsSha256"], "child run bytes"), runs.stat().st_size
        if digest(runs) != runs_hash or not any(i.get("name", "").casefold() == runs.name.casefold()
                and i.get("bytes") == size and _sha(i.get("sha256"), "writer input") == runs_hash for i in writer_inputs):
            raise GrowContractError("component run bytes were not consumed by this writer")
        normalized = _coverage(component, {"status": "no_features" if child["status"] == "NO_FEATURES" else "included",
                                           "evidence_path": str(child_path), "evidence_sha256": child_sha}, core, writer_hash, writer_inputs)
        if name == "coast-water" and not child.get("maskEvidence"):
            raise GrowContractError("coast contributor lacks its actual mask chain")
        ids = child.get("emittedFeatureIds")
        if not isinstance(ids, list) or any(not isinstance(i, str) or not i for i in ids) or len(set(ids)) != len(ids) or len(ids) != normalized["feature_count"]:
            raise GrowContractError("child feature IDs do not establish its count")
        feature_ids.update(normalized["source_sha256"] + ":" + feature for feature in ids)
        children.append({"id": name, **normalized, "runs_path": str(runs), "runs_sha256": runs_hash,
                         "source_report_path": str(source_report), "source_report_sha256": source_report_sha})
        child_proofs.append(child)
    count = len(feature_ids)
    status = "NO_FEATURES" if count == 0 else "PASS"
    if (evidence.get("status") != status or entry["status"] != ("no_features" if count == 0 else "included")
            and not (count > 0 and entry["status"] == "pass")
            or type(evidence.get("featureCount")) is not int or evidence["featureCount"] != count
            or type(evidence.get("emittedFeatureCount")) is not int or evidence["emittedFeatureCount"] != count
            or evidence.get("emittedFeatureIds") != sorted(feature_ids)):
        raise GrowContractError("aggregate feature IDs/count/status do not equal verified contributors")
    if count == 0 and (evidence.get("sourceCoverageComplete") is not True or evidence.get("blockedDiagnostics") != 0 or evidence.get("unmappedSourceCount") != 0):
        raise GrowContractError("aggregate absence lacks complete source coverage")
    if evidence.get("sourceCoverageComplete") is not all(c.get("sourceCoverageComplete") is True for c in child_proofs):
        raise GrowContractError("aggregate source completeness exceeds its child proofs")
    for field in ("blockedDiagnostics", "unmappedSourceCount"):
        values = [c.get(field) for c in child_proofs]
        combined = sum(values) if all(type(v) is int and v >= 0 for v in values) else None
        if evidence.get(field) != combined or (combined is not None and type(evidence.get(field)) is not int):
            raise GrowContractError("aggregate diagnostics do not equal child proofs")
    return {"status": entry["status"], "evidence_path": str(path), "evidence_sha256": evidence_sha,
            "source_sha256": source_hash, "feature_count": count, "source_set_path": str(source_set_path),
            "source_set_sha256": source_hash, "contributors": children}


def _coverage(component, entry, core, writer_hash, writer_inputs=None):
    core = _bounds(core, component + " coverage core")
    if isinstance(entry, dict) and entry.get("status") == "rendered_subset_preview":
        evidence = load(Path(entry["evidence_path"]).resolve(strict=True))
        if evidence.get("kind") == "fork-multi-source-component-preview":
            if component != "water":
                raise GrowContractError("multi-source component preview applies only to water")
            from grow_preview import validate_multi_water_preview
            return validate_multi_water_preview(entry, list(core), writer_hash, writer_inputs,
                                                coast_validator=_coverage)
        from grow_preview import validate_preview
        return validate_preview(component, entry, list(core), writer_hash, writer_inputs)
    if not isinstance(entry, dict) or entry.get("status") not in ("included", "pass", "no_features"):
        raise GrowContractError(f"{component} coverage must be included/pass/no_features with evidence")
    path = Path(entry["evidence_path"]).resolve(strict=True)
    evidence_sha = _sha(entry["evidence_sha256"], component)
    if digest(path) != evidence_sha:
        raise GrowContractError(f"{component} evidence changed")
    evidence = load(path)
    if evidence.get("kind") == "fork-multi-source-component-evidence":
        return _multi_coverage(component, entry, evidence, path, evidence_sha, core, writer_hash, writer_inputs)
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
        elif evidence.get("maskEvidence") is not None:
            _coast_chain(evidence, report_data)
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
            if not world.is_dir() or not (world / REGION_DIRECTORY).is_dir():
                raise GrowContractError("source needs MC26 dimensions/minecraft/overworld/region directory")
            writer_path = Path(raw["writer_manifest_path"]).resolve(strict=True)
            writer_hash, writer = digest(writer_path), load(writer_path)
            if writer.get("coordinateFrame") != FRAME:
                raise GrowContractError("source must preserve exact EPSG:3414 x=E,z=60000-N,1m grid")
            bounds = _bounds(writer["bounds"], name + " writer bounds")
            if not _contains(bounds, core):
                raise GrowContractError("owned core extends outside writer bounds")
            version = writer["dataVersion"]
            if type(version) is not int or version != 4790:
                raise GrowContractError("MC26.1.2 DataVersion 4790 required")
            if writer.get("minecraftTarget") != "26.1.2" or writer.get("regionDirectory") != REGION_DIRECTORY:
                raise GrowContractError("writer must bind MC26.1.2 and its actual dimension directory layout")
            versions.add(version)
            outputs = _outputs(writer, world)
            gate_path = Path(raw["structural_gate_path"]).resolve(strict=True)
            extra_bindings = _structural(load(gate_path), writer_hash, outputs, core, raw, world, writer_path) or {}
            coverage = {component: _coverage(component, raw["coverage"].get(component), core, writer_hash, writer.get("inputs"))
                        for component in ("roads", "water")}
            sources.append({"id": name, "world_path": str(world), "core_bounds": list(core),
                            "writer_manifest_path": str(writer_path), "writer_manifest_sha256": writer_hash,
                            "structural_gate_path": str(gate_path), "structural_gate_sha256": digest(gate_path),
                            "outputs": outputs, "region_directory": REGION_DIRECTORY, "coverage": coverage, **extra_bindings})
        if len(versions) != 1:
            raise GrowContractError("all source DataVersions must match")
        sources.sort(key=lambda source: source["id"])
        _connected(sources)
        selected_spawn = _validate_spawn_selection(plan, sources)
        extent = [min(s["core_bounds"][0] for s in sources), min(s["core_bounds"][1] for s in sources),
                  max(s["core_bounds"][2] for s in sources), max(s["core_bounds"][3] for s in sources)]
        return {"sources": sources, "extent": extent, "expected_chunks": sum(_chunk_count(s["core_bounds"]) for s in sources),
                "data_version": next(iter(versions)), "coordinate_frame": dict(FRAME), "selected_spawn": selected_spawn}
    except (KeyError, TypeError, OSError, json.JSONDecodeError, subprocess.SubprocessError) as exc:
        raise GrowContractError(f"missing or unreadable grow evidence: {exc}") from exc
