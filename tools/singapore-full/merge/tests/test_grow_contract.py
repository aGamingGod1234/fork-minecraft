import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from grow_contract import FRAME, REGION_DIRECTORY, WORLD_SETTINGS, GrowContractError, digest, iter_owned_chunks, validate_plan


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


if __name__ == "__main__":
    unittest.main()
