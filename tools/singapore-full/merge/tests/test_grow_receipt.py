"""Tiny synthetic closed-world fixtures, retained without deletion."""
import copy
from collections import defaultdict
import json
from pathlib import Path
import sys
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import anvil
import package
import grow_receipt
import overlay


def compound(values):
    return anvil.Tag(10, values)


def nbt_list(values, kind=10):
    return anvil.Tag(9, anvil.ListPayload(kind, values))


class GrownWorldReceiptTests(unittest.TestCase):
    def setUp(self):
        self.root = package.OUTPUT_ROOT / ("synthetic-grow-" + uuid.uuid4().hex[:12])
        self.root.mkdir(parents=True)
        self.source = self.root / "source"
        self.world = self.root / "grown"
        self.make_world(self.source)
        self.make_world(self.world)
        self.writer = self.root / "writer.json"
        self.writer.write_text('{"status":"WRITTEN_UNACCEPTED","synthetic":true}')
        self.structural = self.root / "structural.json"
        self.structural.write_text('{"status":"PASS","synthetic":true}')
        writer_sha = package._file_record(self.writer)["sha256"]
        coverage = {}
        for component in ("roads", "water"):
            path = self.root / (component + ".json")
            path.write_text(json.dumps({"status": "NO_FEATURES", "component": component,
                "coreBounds": [0, 0, 16, 16], "writerManifestSha256": writer_sha,
                "sourceSha256": "a" * 64, "featureCount": 0, "synthetic": True}))
            coverage[component] = {"status": "no_features", "evidence_path": str(path),
                                   "evidence_sha256": package._file_record(path)["sha256"]}
        source_files = package.snapshot_tree(self.source)["files"]
        self.sources = [{"id": "synthetic-source", "world_path": str(self.source), "core_bounds": [0, 0, 16, 16],
                         "outputs": [{"path": name, **row} for name, row in source_files.items()],
                         "writer_manifest_path": str(self.writer), "writer_manifest_sha256": writer_sha,
                         "structural_gate_path": str(self.structural),
                         "structural_gate_sha256": package._file_record(self.structural)["sha256"], "coverage": coverage}]
        self.regions = {"chunkCount": 1, "sourceChunkCounts": {"synthetic-source": 1},
                        "ownership": {"exactCoreCoverage": True, "coordinatesTranslated": False,
                                      "duplicateOwnership": False, "canonicalNbtVerified": True},
                        "outputs": [{"path": name, **row} for name, row in source_files.items()
                                    if name.endswith(".mca")]}

    def make_world(self, world):
        (world / grow_receipt.REGION_PREFIX).mkdir(parents=True)
        report = {"crossLayerOverwrittenBlocks": defaultdict(int)}
        chunk = overlay.make_chunk(0, 0, {}, 4790, report)
        anvil.write_region(world / grow_receipt.REGION_PREFIX / "r.0.0.mca", {(0, 0): chunk})
        data = compound({"DataVersion": anvil.Tag(3, 4790),
                         "DataPacks": compound({"Enabled": nbt_list([anvil.Tag(8, "vanilla")], 8), "Disabled": nbt_list([], 8)}),
                         "spawn": compound({"pos": anvil.Tag(11, [1, 1, 1]), "dimension": anvil.Tag(8, "minecraft:overworld")})})
        anvil.write_level_dat(world / "level.dat", anvil.NbtFile("", compound({"Data": data})))
        settings = compound({"data": compound({"generate_structures": anvil.Tag(1, 0)})})
        (world / "data/minecraft").mkdir(parents=True)
        anvil.write_level_dat(world / "data/minecraft/world_gen_settings.dat", anvil.NbtFile("", settings))

    def check(self):
        return grow_receipt.verify_grown_world(self.world, self.sources, self.regions, "synthetic-source")

    def test_positive_exact_copy_safe_spawn_pending_runtime(self):
        result = self.check()
        self.assertTrue(result["assemblyAccepted"], result["issues"])
        self.assertFalse(result["runtimeLoadAccepted"])
        self.assertFalse(result["visualAccepted"])
        self.assertFalse(result["aiAccepted"])
        self.assertEqual([1, 1, 1], result["spawn"]["position"])
        self.assertEqual("minecraft:grass_block", result["spawn"]["floor"])

    def test_changed_source_rejected(self):
        (self.source / "unexpected.dat").write_bytes(b"synthetic mutation")
        self.assertFalse(self.check()["assemblyAccepted"])

    def test_changed_region_rejected(self):
        with (self.world / grow_receipt.REGION_PREFIX / "r.0.0.mca").open("ab") as stream:
            stream.write(b"changed")
        self.assertFalse(self.check()["assemblyAccepted"])

    def test_private_files_and_empty_directories_rejected(self):
        (self.world / "players").mkdir()
        self.assertFalse(self.check()["assemblyAccepted"])
        (self.world / "data/map_0.dat").write_bytes(b"map")
        self.assertFalse(self.check()["assemblyAccepted"])

    def test_missing_coverage_or_proof_rejected(self):
        self.sources[0]["coverage"].pop("roads")
        self.assertFalse(self.check()["assemblyAccepted"])

    def test_wrong_chunk_count_or_copy_proof_rejected(self):
        self.regions["chunkCount"] = 2
        self.assertFalse(self.check()["assemblyAccepted"])
        self.regions["chunkCount"] = 1
        self.regions["ownership"]["canonicalNbtVerified"] = False
        self.assertFalse(self.check()["assemblyAccepted"])

    def test_outside_spawn_rejected(self):
        level = anvil.read_level_dat(self.world / "level.dat")
        level.root.value["Data"].value["spawn"].value["pos"] = anvil.Tag(11, [16, 1, 1])
        anvil.write_level_dat(self.world / "level.dat", level)
        self.assertFalse(self.check()["assemblyAccepted"])

    def test_safe_but_changed_spawn_is_not_the_verified_source_spawn(self):
        level = anvil.read_level_dat(self.world / "level.dat")
        level.root.value["Data"].value["spawn"].value["pos"] = anvil.Tag(11, [2, 1, 1])
        anvil.write_level_dat(self.world / "level.dat", level)
        result = self.check()
        self.assertFalse(result["assemblyAccepted"])
        self.assertIn("verified modern spawn", result["issues"][0])

    def test_actual_spawn_head_obstruction_rejected(self):
        run = overlay.parse_run({"x": 1, "z": 1, "yMin": 2, "yMax": 3, "block": "minecraft:stone",
                                 "layer": "building", "featureId": "synthetic-obstruction",
                                 "geometryKind": "synthetic", "sourceClass": "SYNTHETIC"}, [0, 0, 16, 16])
        chunk = overlay.make_chunk(0, 0, {(1, 1): [run]}, 4790, {"crossLayerOverwrittenBlocks": defaultdict(int)})
        region_name = grow_receipt.REGION_PREFIX + "r.0.0.mca"
        anvil.write_region(self.world / region_name, {(0, 0): chunk})
        self.regions["outputs"] = [{"path": region_name, **package._file_record(self.world / region_name)}]
        result = self.check()
        self.assertFalse(result["assemblyAccepted"])
        self.assertIn("obstructed", result["issues"][0])

    def test_embedded_player_and_world_structures_rejected(self):
        level = anvil.read_level_dat(self.world / "level.dat")
        level.root.value["Data"].value["Player"] = compound({})
        anvil.write_level_dat(self.world / "level.dat", level)
        self.assertFalse(self.check()["assemblyAccepted"])
        level.root.value["Data"].value.pop("Player")
        anvil.write_level_dat(self.world / "level.dat", level)
        settings_path = self.world / "data/minecraft/world_gen_settings.dat"
        settings = anvil.read_level_dat(settings_path)
        settings.root.value["data"].value["generate_structures"].value = 1
        anvil.write_level_dat(settings_path, settings)
        self.assertFalse(self.check()["assemblyAccepted"])

    def test_assembly_component_cannot_supply_final_spawn(self):
        source = self.sources[0]
        source.update(role="assembly-component", standalone_status="NOT_STANDALONE",
                      component_spawn_accepted=False, final_assembled_safe_spawn_required=True)
        self.structural.write_text(json.dumps({"status": "PASS", "role": "assembly-component", "standaloneStatus": "NOT_STANDALONE",
                                   "componentSpawnAccepted": False, "finalAssembledSafeSpawnRequired": True,
                                   "runtimeAccepted": False, "fullWorldAccepted": False}))
        source["structural_gate_sha256"] = package._file_record(self.structural)["sha256"]
        result = self.check()
        self.assertFalse(result["assemblyAccepted"])
        self.assertIn("cannot supply final spawn", result["issues"][0])

    def test_component_role_stays_nonstandalone_when_not_selected(self):
        source = {"id": "east", "role": "assembly-component", "standalone_status": "NOT_STANDALONE",
                  "component_spawn_accepted": False, "final_assembled_safe_spawn_required": True}
        gate = {"status": "PASS", "role": "assembly-component", "standaloneStatus": "NOT_STANDALONE",
                "componentSpawnAccepted": False, "finalAssembledSafeSpawnRequired": True,
                "runtimeAccepted": False, "fullWorldAccepted": False}
        self.assertTrue(grow_receipt._component_status(source, gate, "safe-cbd"))
        gate["standaloneStatus"] = "PASS"
        with self.assertRaises(package.GateError):
            grow_receipt._component_status(source, gate, "safe-cbd")

    def test_final_configuration_must_come_from_selected_safe_source(self):
        level = anvil.read_level_dat(self.world / "level.dat")
        level.root.value["Data"].value["GameType"] = anvil.Tag(3, 3)
        anvil.write_level_dat(self.world / "level.dat", level)
        result = self.check()
        self.assertFalse(result["assemblyAccepted"])
        self.assertIn("Final configs", result["issues"][0])


if __name__ == "__main__":
    unittest.main(verbosity=2)
