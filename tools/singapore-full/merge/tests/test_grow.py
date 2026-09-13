"""Tiny synthetic integration fixtures; fabricated receipts are test inputs only."""
from collections import defaultdict
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import anvil
import grow
import grow_contract
import grow_receipt
import overlay
import package


def compound(value):
    return anvil.Tag(10, value)


def strings(values):
    return anvil.Tag(9, anvil.ListPayload(8, [anvil.Tag(8, value) for value in values]))


class GrowIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="synthetic-grow-integration-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.merged = self.root / "merged"
        self.merged.mkdir()
        self.world = self.merged / "snapshot-test" / "world"
        self.manifest = self.world.parent / "grow-manifest.json"
        self.source = self.root / "source"
        region = self.source / "dimensions/minecraft/overworld/region/r.0.0.mca"
        region.parent.mkdir(parents=True)
        chunk = overlay.make_chunk(0, 0, {}, 4790, {"crossLayerOverwrittenBlocks": defaultdict(int)})
        anvil.write_region(region, {(0, 0): chunk})
        data = compound({"DataVersion": anvil.Tag(3, 4790),
            "DataPacks": compound({"Enabled": strings(["vanilla"]), "Disabled": strings([])}),
            "spawn": compound({"pos": anvil.Tag(11, [1, 1, 1]), "dimension": anvil.Tag(8, "minecraft:overworld")})})
        anvil.write_level_dat(self.source / "level.dat", anvil.NbtFile("", compound({"Data": data})))
        settings = self.source / "data/minecraft/world_gen_settings.dat"
        settings.parent.mkdir(parents=True)
        anvil.write_level_dat(settings, anvil.NbtFile("", compound({"DataVersion": anvil.Tag(3, 4790),
            "data": compound({"generate_structures": anvil.Tag(1, 0)})})))
        self.before = package.snapshot_tree(self.source)
        outputs = [{"path": name, **row} for name, row in self.before["files"].items()]
        writer = self.root / "synthetic-writer.json"
        self.write(writer, {"schemaVersion": 1, "kind": "global-block-run-world", "dataVersion": 4790,
            "minecraftTarget": "26.1.2", "regionDirectory": "dimensions/minecraft/overworld/region",
            "bounds": [0, 0, 16, 16], "coordinateFrame": grow_contract.FRAME, "outputs": outputs})
        gate = self.root / "synthetic-structural-contract-input.json"
        self.write(gate, {"schemaVersion": 1, "kind": "actual-world-structural-validation", "status": "PASS",
            "synthetic": False, "comparedBlocks": 1, "mismatches": 0,
            "writerManifestSha256": grow.digest(writer), "worldOutputs": outputs})
        coverage = {}
        for component in ("roads", "water"):
            evidence = self.root / ("synthetic-" + component + ".json")
            self.write(evidence, {"component": component, "status": "NO_FEATURES", "coreBounds": [0, 0, 16, 16],
                "writerManifestSha256": grow.digest(writer), "sourceSha256": "a" * 64, "featureCount": 0,
                "sourceCoverageComplete": True, "blockedDiagnostics": 0, "unmappedSourceCount": 0})
            coverage[component] = {"status": "no_features", "evidence_path": str(evidence), "evidence_sha256": grow.digest(evidence)}
        self.plan = self.root / "synthetic-plan.json"
        self.write(self.plan, {"schemaVersion": 1, "spawn_source_id": "test", "sources": [{"id": "test",
            "world_path": str(self.source), "core_bounds": [0, 0, 16, 16], "writer_manifest_path": str(writer),
            "structural_gate_path": str(gate), "coverage": coverage}]})
        self.lease = self.root / "synthetic-lease.json"
        now = datetime.now(timezone.utc)
        self.lease_data = {"id": "TEST-NOT-A-REAL-LEASE", "outputRoot": str(self.world.parent), "machine": "Desktop",
            "approvedBy": "/root/singapore_full_coordinator", "heavyJobSlot": "A", "cpuThreads": 1,
            "startsUtc": (now - timedelta(minutes=1)).isoformat(), "expiresUtc": (now + timedelta(minutes=2)).isoformat()}
        self.write(self.lease, self.lease_data)

    def write(self, path, value):
        path.write_text(json.dumps(value), encoding="utf-8")

    def assemble(self):
        with patch.object(grow_receipt.package, "OUTPUT_ROOT", self.merged):
            return grow.assemble(self.plan, self.world, self.manifest, self.lease, merged_root=self.merged)

    def test_copy_promotes_complete_snapshot_and_preserves_source(self):
        result = self.assemble()
        self.assertEqual("STRUCTURAL_PASS_RUNTIME_PENDING", result["status"])
        self.assertTrue(result["verification"]["assemblyAccepted"])
        self.assertEqual(self.before, package.snapshot_tree(self.source))
        self.assertEqual(self.before, package.snapshot_tree(self.world))
        self.assertFalse(result["runtimeLoadAccepted"])
        self.assertTrue(self.manifest.is_file())
        self.assertFalse(any(self.world.parent.glob(".grow-*")))

    def test_existing_world_is_never_replaced(self):
        self.world.mkdir(parents=True)
        sentinel = self.world / "keep.txt"
        sentinel.write_text("keep")
        with self.assertRaises(FileExistsError):
            self.assemble()
        self.assertEqual("keep", sentinel.read_text())
        self.assertFalse(self.manifest.exists())

    def test_location_label_preserves_verified_spawn_and_source(self):
        plan = json.loads(self.plan.read_text())
        plan["world_name"] = "FORK - Lim Chu Kang"
        self.write(self.plan, plan)
        result = self.assemble()
        data = anvil.read_level_dat(self.world / "level.dat").root.value["Data"].value
        self.assertEqual("FORK - Lim Chu Kang", data["LevelName"].value)
        self.assertEqual([1, 1, 1], data["spawn"].value["pos"].value)
        self.assertEqual(self.before, package.snapshot_tree(self.source))
        self.assertTrue(result["verification"]["spawn"]["safe"])

    def test_changed_source_fails_without_published_world(self):
        actual = grow.merge_regions
        def mutate_after_copy(*args, **kwargs):
            result = actual(*args, **kwargs)
            (self.source / "unexpected.dat").write_bytes(b"mutation")
            return result
        with patch.object(grow, "merge_regions", side_effect=mutate_after_copy):
            with self.assertRaisesRegex(ValueError, "verification failed"):
                self.assemble()
        self.assertFalse(self.world.exists())
        self.assertFalse(self.manifest.exists())

    def test_expired_or_wrong_scope_lease_fails_before_writes(self):
        self.lease_data["expiresUtc"] = "2020-01-01T00:00:00Z"
        self.write(self.lease, self.lease_data)
        with self.assertRaisesRegex(ValueError, "not currently valid"):
            self.assemble()
        self.assertFalse(self.world.parent.exists())
        self.lease_data["outputRoot"] = str(self.merged)
        self.write(self.lease, self.lease_data)
        with self.assertRaisesRegex(ValueError, "outputRoot"):
            self.assemble()

    def test_destination_scope_rejects_parent_and_nested_worlds(self):
        for target in (self.merged / "world", self.root / "outside" / "world", self.world / "nested" / "world"):
            with self.assertRaises(ValueError):
                grow.validate_destination(target, target.parent / "manifest.json", merged_root=self.merged)


if __name__ == "__main__":
    unittest.main(verbosity=2)
