import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from grow_contract import FRAME, REGION_DIRECTORY, WORLD_SETTINGS, GrowContractError, digest, iter_owned_chunks, validate_plan
from grow_contract import _benchmark_structural, _coverage, _component_role, _validate_spawn_selection


class GrowContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, path, value):
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def source(self, name, bounds, version=4790, existing=False):
        root = self.root / name
        world = root / "world"
        (world / REGION_DIRECTORY).mkdir(parents=True)
        (world / WORLD_SETTINGS).parent.mkdir(parents=True)
        # Hash fixtures only. This test does not claim these bytes are playable NBT.
        (world / "level.dat").write_bytes(b"fixture level")
        (world / REGION_DIRECTORY / "r.0.0.mca").write_bytes(b"fixture region")
        (world / WORLD_SETTINGS).write_bytes(b"fixture external settings")
        outputs = [{"path": p.relative_to(world).as_posix(), "bytes": p.stat().st_size, "sha256": digest(p)}
                   for p in (world / "level.dat", world / REGION_DIRECTORY / "r.0.0.mca", world / WORLD_SETTINGS)]
        writer = self.write(root / "writer.json", {"coordinateFrame": FRAME, "bounds": bounds,
                            "dataVersion": version, "minecraftTarget": "26.1.2", "regionDirectory": REGION_DIRECTORY, "outputs": outputs})
        if existing:
            gate = {"kind": "independent-joined-strip-structural-gate", "status": "PASS",
                    "chunkCount": ((bounds[2] - bounds[0]) // 16) * ((bounds[3] - bounds[1]) // 16),
                    "bounds": bounds, "writerManifestSha256": digest(writer), "fileHashErrors": []}
        else:
            gate = {"schemaVersion": 1, "kind": "actual-world-structural-validation", "status": "PASS",
                    "synthetic": False, "writerManifestSha256": digest(writer), "worldOutputs": outputs,
                    "comparedBlocks": 4096, "mismatches": 0}
        gate_path = self.write(root / "gate.json", gate)
        coverage = {}
        for component in ("roads", "water"):
            path = self.write(root / f"{component}.json", {"component": component, "status": "NO_FEATURES",
                              "sourceSha256": "a" * 64, "coreBounds": bounds, "writerManifestSha256": digest(writer), "featureCount": 0,
                              "sourceCoverageComplete": True, "blockedDiagnostics": 0, "unmappedSourceCount": 0})
            coverage[component] = {"status": "no_features", "evidence_path": str(path), "evidence_sha256": digest(path)}
        return {"id": name, "world_path": str(world), "core_bounds": bounds,
                "writer_manifest_path": str(writer), "structural_gate_path": str(gate_path), "coverage": coverage}

    def test_cbd_alone_and_connected_twelve_east_cores(self):
        cbd = self.source("cbd", [29696, 29696, 30720, 30720], existing=True)
        alone = validate_plan({"schemaVersion": 1, "sources": [cbd]})
        self.assertEqual(alone["expected_chunks"], 4096)
        east = [self.source(f"east{x}-{z}", [30720 + 256*x, 29696 + 256*z, 30976 + 256*x, 29952 + 256*z])
                for x in range(3) for z in range(4)]
        plan = validate_plan({"schemaVersion": 1, "sources": list(reversed(east + [cbd]))})
        self.assertEqual(plan["extent"], [29696, 29696, 31488, 30720])
        self.assertEqual(plan["expected_chunks"], 7168)
        self.assertEqual(len(set(iter_owned_chunks(plan["sources"]))), 7168)

    def test_overlap_detached_and_corner_only_rejected(self):
        a = self.source("a", [-16, -16, 16, 16])
        for name, bounds in (("overlap", [0, 0, 32, 32]), ("detached", [32, 0, 48, 16]),
                             ("corner", [16, 16, 32, 32])):
            with self.subTest(name=name), self.assertRaises(GrowContractError):
                validate_plan({"schemaVersion": 1, "sources": [a, self.source(name, bounds)]})

    def test_missing_roads_unknown_and_buildings_only_rejected(self):
        a = self.source("a", [0, 0, 16, 16])
        for status in ("UNKNOWN", "buildings_only", None):
            bad = copy.deepcopy(a)
            bad["coverage"]["roads"]["status"] = status
            with self.assertRaises(GrowContractError):
                validate_plan({"schemaVersion": 1, "sources": [bad]})

    def test_modified_output_and_stale_writer_gate_rejected(self):
        a = self.source("a", [0, 0, 16, 16])
        (Path(a["world_path"]) / "level.dat").write_bytes(b"changed")
        with self.assertRaisesRegex(GrowContractError, "output changed"):
            validate_plan({"schemaVersion": 1, "sources": [a]})
        b = self.source("b", [0, 0, 16, 16])
        writer = Path(b["writer_manifest_path"])
        writer.write_text(writer.read_text() + " ")
        with self.assertRaisesRegex(GrowContractError, "different writer"):
            validate_plan({"schemaVersion": 1, "sources": [b]})

    def test_mixed_version_and_non_aligned_core_rejected(self):
        a = self.source("a", [0, 0, 16, 16])
        b = self.source("b", [16, 0, 32, 16], version=4999)
        with self.assertRaisesRegex(GrowContractError, "DataVersion"):
            validate_plan({"schemaVersion": 1, "sources": [a, b]})
        a["core_bounds"] = [1, 0, 16, 16]
        with self.assertRaisesRegex(GrowContractError, "16-aligned"):
            validate_plan({"schemaVersion": 1, "sources": [a]})

    def test_actual_gate_failure_and_unlisted_source_state_rejected(self):
        a = self.source("a", [0, 0, 16, 16], existing=True)
        path = Path(a["structural_gate_path"])
        gate = json.loads(path.read_text())
        gate["fileHashErrors"] = ["bad"]
        self.write(path, gate)
        with self.assertRaises(GrowContractError):
            validate_plan({"schemaVersion": 1, "sources": [a]})
        gate["fileHashErrors"] = []
        self.write(path, gate)
        (Path(a["world_path"]) / "session.lock").write_bytes(b"private state")
        with self.assertRaisesRegex(GrowContractError, "unlisted"):
            validate_plan({"schemaVersion": 1, "sources": [a]})

    def test_coverage_binding_and_no_features_count_enforced(self):
        a = self.source("a", [0, 0, 16, 16])
        entry = a["coverage"]["water"]
        path = Path(entry["evidence_path"])
        data = json.loads(path.read_text())
        data["featureCount"] = 1
        self.write(path, data)
        entry["evidence_sha256"] = digest(path)
        with self.assertRaisesRegex(GrowContractError, "feature count"):
            validate_plan({"schemaVersion": 1, "sources": [a]})

    def test_existing_raster_manifest_wrapper_binds_real_report_and_runs(self):
        a = self.source("a", [0, 0, 16, 16])
        entry = a["coverage"]["roads"]
        path = Path(entry["evidence_path"])
        runs = path.parent / "roads.runs.jsonl"
        runs.write_bytes(b'{"x":0,"z":0,"layer":40}\n')
        report = self.write(path.parent / "roads-manifest.json", {"schema": "fork.roads-runs.v1",
                            "sourceSha256": "a" * 64, "runSha256": "b" * 64, "outputSha256": digest(runs),
                            "runCount": 1, "blockedDiagnostics": 0})
        evidence = json.loads(path.read_text())
        evidence.update(status="PASS", featureCount=1, reportPath=str(report), reportSha256=digest(report),
                        runsPath=str(runs), runsSha256=digest(runs))
        self.write(path, evidence)
        entry.update(status="included", evidence_sha256=digest(path))
        self.assertEqual(validate_plan({"schemaVersion": 1, "sources": [a]})["expected_chunks"], 1)
        runs.write_bytes(b"changed")
        with self.assertRaisesRegex(GrowContractError, "run bytes"):
            validate_plan({"schemaVersion": 1, "sources": [a]})

    def test_mc26_rebound_actual_gate_shape_and_missing_external_settings(self):
        a = self.source("a", [0, 0, 16, 16], existing=True)
        path = Path(a["structural_gate_path"])
        gate = json.loads(path.read_text())
        gate.pop("fileHashErrors")
        world = Path(a["world_path"])
        gate.update(regionDirectory=REGION_DIRECTORY, errors=[], comparedCells=98304,
                    priorGeometryOracleSha256="b" * 64, metadataProofSha256="c" * 64,
                    mismatchedCells=0, seamMismatchedCells=0, heightmapMismatches=0,
                    regionIdentity=[{"newPath": REGION_DIRECTORY + "/r.0.0.mca",
                                     "sha256": digest(world / REGION_DIRECTORY / "r.0.0.mca")}])
        self.write(path, gate)
        self.assertEqual(validate_plan({"schemaVersion": 1, "sources": [a]})["data_version"], 4790)
        (world / WORLD_SETTINGS).unlink()
        with self.assertRaises(GrowContractError):
            validate_plan({"schemaVersion": 1, "sources": [a]})

    def benchmark_fixture(self):
        source = self.source("benchmark", [0, 0, 1024, 1024])
        root = Path(source["writer_manifest_path"]).parent
        writer_path = Path(source["writer_manifest_path"])
        writer = json.loads(writer_path.read_text())
        writer_hash = digest(writer_path)
        receipt = self.write(root / "receipt.json", {"fixture": True})
        validator = root / "validate-benchmark.mjs"
        validator.write_text("// Test fixture only. Actual read-only loader smoke is separate.\n")
        bindings_module = root / "validate-benchmark-bindings.mjs"
        bindings_module.write_text("// Test fixture only.\n")
        gate = {"schemaVersion": 1, "kind": "independent-benchmark-result-gate", "status": "PASS",
                "comparisonScope": "full-volume", "comparisonBounds": [0, 0, 1024, 1024],
                "comparedCells": 402653184, "mismatchedCells": 0, "fileHashErrors": [],
                "checks": {"globalChunkCoordinates": True, "metadata": True, "heightmaps": True},
                "benchmarkReceiptSha256": digest(receipt), "writerOutputHashes": writer["outputs"],
                "evidence": {"writerManifest": {"path": str(writer_path), "sha256": writer_hash},
                             "oracleProof": {"path": str(root / "oracle.json"), "sha256": "a" * 64}}}
        gate_path = self.write(Path(source["structural_gate_path"]), gate)
        summary = {"schemaVersion": 1, "kind": "measured-independent-benchmark-validation", "status": "PASS",
                   "metrics": {"exitCode": 0, "timedOut": False, "parentExited": False},
                   "receiptPath": str(receipt), "benchmarkReceiptSha256": digest(receipt), "gateSha256": digest(gate_path),
                   "oracleSha256": "a" * 64, "validatorCodeHashes": {p.name: digest(p) for p in (validator, bindings_module)}}
        self.write(root / "summary.json", summary)
        source["benchmark_validator_path"] = str(validator)
        world = Path(source["world_path"])
        outputs = sorted(writer["outputs"], key=lambda record: record["path"])
        bound_outputs = [{**record, "path": str(world / record["path"])} for record in outputs]
        loaded = {"status": "PASS", "bindings": {"worldRoot": str(world), "writerManifestPath": str(writer_path),
                                                  "coreBounds": [0, 0, 1024, 1024], "outputs": bound_outputs}}
        return gate, source, writer_hash, outputs, (0, 0, 1024, 1024), world, writer_path, loaded

    def test_benchmark_uses_pinned_loader_and_retains_provenance(self):
        *arguments, loaded = self.benchmark_fixture()
        with patch("grow_contract.shutil.which", return_value="node"), patch("grow_contract.subprocess.run") as run:
            run.return_value = SimpleNamespace(returncode=0, stdout=json.dumps(loaded), stderr="")
            result = _benchmark_structural(*arguments)
        self.assertEqual(len(result["benchmark_validator_code"]), 2)
        self.assertIn("--max-old-space-size=384", run.call_args.args[0])
        self.assertIn("fork-grow-readonly-bindings", run.call_args.args[0])

    def test_benchmark_rejects_partial_volume_before_loader(self):
        *arguments, loaded = self.benchmark_fixture()
        arguments[0]["comparedCells"] -= 1
        path = Path(arguments[1]["structural_gate_path"])
        self.write(path, arguments[0])
        summary_path = path.parent / "summary.json"
        summary = json.loads(summary_path.read_text())
        summary["gateSha256"] = digest(path)
        self.write(summary_path, summary)
        with patch("grow_contract.subprocess.run") as run, self.assertRaisesRegex(GrowContractError, "every 1024-core cell"):
            _benchmark_structural(*arguments)
        run.assert_not_called()

    def test_benchmark_rejects_changed_validator_and_wrong_returned_world(self):
        *arguments, loaded = self.benchmark_fixture()
        bindings_path = Path(arguments[1]["benchmark_validator_path"]).parent / "validate-benchmark-bindings.mjs"
        original = bindings_path.read_text()
        bindings_path.write_text("changed")
        with self.assertRaisesRegex(GrowContractError, "validator code"):
            _benchmark_structural(*arguments)
        bindings_path.write_text(original)
        loaded["bindings"]["worldRoot"] = str(self.root)
        with patch("grow_contract.shutil.which", return_value="node"), patch("grow_contract.subprocess.run") as run:
            run.return_value = SimpleNamespace(returncode=0, stdout=json.dumps(loaded), stderr="")
            with self.assertRaisesRegex(GrowContractError, "identity differs"):
                _benchmark_structural(*arguments)

    def multi_fixture(self):
        root, core, writer_hash = self.root, [0, 0, 16, 16], "d" * 64
        records, pinned, inputs, ids = [], [], [], []
        for name, source_hash, feature in (("coast-water", "c" * 64, "coast-one"), ("inland-water", "e" * 64, "lake-one")):
            runs = root / (name + ".runs.jsonl")
            runs.write_bytes((name + " masked emitted runs\n").encode())
            runs_hash = digest(runs)
            inputs.append({"name": runs.name, "bytes": runs.stat().st_size, "sha256": runs_hash})
            if name == "coast-water":
                country = root / "country.json"
                foreign = root / "foreign.json"
                country.write_bytes(b"country mask")
                foreign.write_bytes(b"foreign exclusions")
                raw = root / "coast.raw.runs.jsonl"
                raw.write_bytes(b"unclipped source runs")
                report = self.write(root / "coast-source.json", {"schema": "fork.coast-surface.v1", "status": "emitted-unclipped",
                                    "maskSha256": source_hash, "outputSha256": digest(raw), "statistics": {"unknownCells": 0}})
                masked = self.write(root / "coast-mask.json", {"schema": "fork.masked-runs.v1", "allLayersFiltered": True,
                                    "sourcesUnchanged": True, "outputSha256": runs_hash, "inputs": [{"sha256": digest(raw)}],
                                    "countryMaskSha256": digest(country), "foreignExclusionsSha256": digest(foreign)})
                oracle = self.write(root / "coast-oracle.json", {"status": "PASS", "synthetic": False, "component": "water",
                                    "coreBounds": core, "writerManifestSha256": writer_hash, "sourceSha256": source_hash,
                                    "runsSha256": runs_hash, "countryMaskSha256": digest(country), "foreignExclusionsSha256": digest(foreign),
                                    "comparedBlocks": 1, "mismatches": 0})
                mask_evidence = {"maskManifestPath": str(masked), "maskManifestSha256": digest(masked),
                                 "rawRunsPath": str(raw), "rawRunsSha256": digest(raw),
                                 "countryMaskPath": str(country), "foreignExclusionsPath": str(foreign),
                                 "countryMaskSha256": digest(country), "foreignExclusionsSha256": digest(foreign)}
            else:
                report = self.write(root / "inland-source.json", {"schema": "fork.roads-runs.v1", "sourceSha256": source_hash,
                                    "outputSha256": runs_hash, "runCount": 1, "blockedDiagnostics": 0})
                oracle, mask_evidence = report, None
            child = self.write(root / (name + ".json"), {"schemaVersion": 1, "component": "water", "status": "PASS",
                               "synthetic": False, "coreBounds": core, "writerManifestSha256": writer_hash,
                               "sourceSha256": source_hash, "featureCount": 1, "emittedFeatureIds": [feature],
                               "runsPath": str(runs), "runsSha256": runs_hash, "sourceReportPath": str(report),
                               "sourceReportSha256": digest(report), "reportPath": str(oracle), "reportSha256": digest(oracle),
                               "maskEvidence": mask_evidence})
            records.append({"id": name, "evidencePath": str(child), "evidenceSha256": digest(child),
                            "sourceSha256": source_hash, "runsSha256": runs_hash, "status": "PASS", "featureCount": 1})
            pinned.append({"id": name, "evidenceSha256": digest(child), "sourceSha256": source_hash,
                           "runsSha256": runs_hash, "sourceReportSha256": digest(report)})
            ids.append(source_hash + ":" + feature)
        source_set = self.write(root / "source-set.json", {"schemaVersion": 1, "kind": "fork-component-source-set", "component": "water",
                                "coreBounds": core, "writerManifestSha256": writer_hash, "contributors": pinned})
        aggregate = self.write(root / "water.json", {"schemaVersion": 1, "kind": "fork-multi-source-component-evidence",
                              "component": "water", "status": "PASS", "synthetic": False, "coreBounds": core,
                              "writerManifestSha256": writer_hash, "sourceSetPath": str(source_set), "sourceSha256": digest(source_set),
                              "featureCount": 2, "emittedFeatureCount": 2, "emittedFeatureIds": sorted(ids),
                              "sourceCoverageComplete": False, "blockedDiagnostics": None, "unmappedSourceCount": None,
                              "requiredContributors": ["coast-water", "inland-water"], "contributors": records})
        entry = {"status": "included", "evidence_path": str(aggregate), "evidence_sha256": digest(aggregate)}
        return entry, tuple(core), writer_hash, inputs

    def test_multi_source_water_verifies_both_typed_children_and_mask_chain(self):
        entry, core, writer_hash, inputs = self.multi_fixture()
        result = _coverage("water", entry, core, writer_hash, inputs)
        self.assertEqual(result["feature_count"], 2)
        self.assertEqual([c["id"] for c in result["contributors"]], ["coast-water", "inland-water"])
        self.assertEqual(result["source_sha256"], result["source_set_sha256"])

    def test_multi_source_water_rejects_missing_child_and_aggregate_only_count(self):
        entry, core, writer_hash, inputs = self.multi_fixture()
        path = Path(entry["evidence_path"])
        original = json.loads(path.read_text())
        for changes in ({"contributors": original["contributors"][:1]}, {"featureCount": 1}, {"requiredContributors": ["inland-water"]},
                        {"sourceCoverageComplete": True}, {"blockedDiagnostics": 0}):
            self.write(path, {**original, **changes})
            entry["evidence_sha256"] = digest(path)
            with self.assertRaises(GrowContractError):
                _coverage("water", entry, core, writer_hash, inputs)

    def test_multi_source_water_rejects_unconsumed_runs_and_changed_oracle(self):
        entry, core, writer_hash, inputs = self.multi_fixture()
        with self.assertRaisesRegex(GrowContractError, "not consumed"):
            _coverage("water", entry, core, writer_hash, inputs[1:])
        (self.root / "coast-oracle.json").write_text('{"status":"FAIL"}')
        with self.assertRaisesRegex(GrowContractError, "report changed"):
            _coverage("water", entry, core, writer_hash, inputs)

    def test_component_cannot_be_selected_or_admitted_without_safe_source(self):
        components = [{"id": "east", "role": "assembly-component"}]
        for plan in ({}, {"spawn_source_id": "east"}, {"spawn_source_id": "missing"}):
            with self.assertRaisesRegex(GrowContractError, "separate standalone safe source"):
                _validate_spawn_selection(plan, components)

    def test_component_role_reloads_original_typed_proof(self):
        source = self.source("component", [0, 0, 256, 256])
        writer = Path(source["writer_manifest_path"])
        validator = writer.parent / "validate-east-world-gate.mjs"
        validator.write_text("// bounded test fixture; actual loader tested separately\n")
        gate = {"role": "assembly-component", "standaloneStatus": "NOT_STANDALONE", "componentSpawnAccepted": False,
                "finalAssembledSafeSpawnRequired": True, "runtimeAccepted": False, "fullWorldAccepted": False,
                "coreBounds": [0, 0, 256, 256], "comparedBlocks": 25165824,
                "evidence": {"gateModule": {"path": str(validator), "bytes": validator.stat().st_size, "sha256": digest(validator)}}}
        loaded = {"role": "assembly-component", "coreBounds": [0, 0, 256, 256], "componentSpawnAccepted": False,
                  "worldRoot": source["world_path"], "writerPath": str(writer)}
        with patch("grow_contract.shutil.which", return_value="node"), patch("grow_contract.subprocess.run") as run:
            run.return_value = SimpleNamespace(returncode=0, stdout=json.dumps(loaded), stderr="")
            result = _component_role(gate, source, (0, 0, 256, 256), Path(source["world_path"]), writer)
        self.assertEqual(result["standalone_status"], "NOT_STANDALONE")
        self.assertFalse(result["component_spawn_accepted"])
        for name, accepted in (("validate-ring-world-gate.mjs", True), ("unapproved-world-gate.mjs", False)):
            validator = writer.parent / name
            validator.write_text("// bounded pinned loader fixture\n")
            gate["evidence"]["gateModule"] = {"path": str(validator), "bytes": validator.stat().st_size, "sha256": digest(validator)}
            with self.subTest(loader=name), patch("grow_contract.shutil.which", return_value="node"), patch("grow_contract.subprocess.run") as run:
                run.return_value = SimpleNamespace(returncode=0, stdout=json.dumps(loaded), stderr="")
                if accepted:
                    result = _component_role(gate, source, (0, 0, 256, 256), Path(source["world_path"]), writer)
                    self.assertEqual(result["component_validator_path"], str(validator))
                    self.assertIn("loadEastWorldGate", run.call_args.args[0][4])
                else:
                    with self.assertRaisesRegex(GrowContractError, "loader code changed"):
                        _component_role(gate, source, (0, 0, 256, 256), Path(source["world_path"]), writer)
                    run.assert_not_called()
        gate["finalAssembledSafeSpawnRequired"] = False
        with self.assertRaisesRegex(GrowContractError, "geometry-only role"):
            _component_role(gate, source, (0, 0, 256, 256), Path(source["world_path"]), writer)

    def test_component_role_cannot_downgrade_to_generic_gate(self):
        source = self.source("component-downgrade", [0, 0, 256, 256])
        path = Path(source["structural_gate_path"])
        original = json.loads(path.read_text())
        for marker in ({"standaloneStatus": "NOT_STANDALONE"},
                       {"finalAssembledSafeSpawnRequired": True},
                       {"componentSpawnAccepted": False},
                       {"evidence": {"gateModule": {"path": "validate-east-world-gate.mjs"}}},
                       {"evidence": {"gateModule": {"path": "validate-ring-world-gate.mjs"}}}):
            for role in (None, "standalone"):
                gate = {**original, **marker}
                if role is not None:
                    gate["role"] = role
                self.write(path, gate)
                with self.subTest(marker=marker, role=role), self.assertRaisesRegex(GrowContractError, "geometry-only role"):
                    validate_plan({"schemaVersion": 1, "sources": [source], "spawn_source_id": source["id"]})

    def test_direct_cbd_validator_metadata_is_not_component_role(self):
        source = self.source("direct-cbd", [29696, 29696, 30720, 30720])
        path = Path(source["structural_gate_path"])
        gate = json.loads(path.read_text())
        gate.update(comparedBlocks=402653184, coreBounds=source["core_bounds"],
                    chunkCount=4096, allocatedChunkCount=6400,
                    evidence={"gateModule": {"path": "validate-direct-world-gate.mjs", "bytes": 20149,
                              "sha256": "f21e86106fad75ad892085d9d9213eb3b0dfc2e9833299d049fd430e6bd9ab8c"}})
        self.write(path, gate)
        with patch("grow_contract.subprocess.run") as run:
            result = validate_plan({"schemaVersion": 1, "sources": [source], "spawn_source_id": source["id"]})
        run.assert_not_called()
        self.assertEqual(result["expected_chunks"], 4096)
        self.assertNotIn("role", result["sources"][0])


if __name__ == "__main__":
    unittest.main()
