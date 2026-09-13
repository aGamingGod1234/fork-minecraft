from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import anvil
from overlay import compound, tag_list, pack_indices
import scoped_runtime_probe as probe


def air_chunk():
    sections = [compound({"Y": anvil.Tag(1, sy), "block_states": compound({
        "palette": tag_list([compound({"Name": anvil.Tag(8, "minecraft:air")})])})}) for sy in range(-4, 20)]
    return anvil.NbtFile("", compound({"DataVersion": anvil.Tag(3, 4790), "xPos": anvil.Tag(3, 1889),
        "zPos": anvil.Tag(3, 1888), "yPos": anvil.Tag(3, -4), "Status": anvil.Tag(8, "minecraft:full"),
        "sections": tag_list(sections), "Heightmaps": compound({name: anvil.Tag(12, [0] * 37) for name in probe.HEIGHTMAPS}),
        "block_entities": tag_list([])}))


class AirChunkTests(unittest.TestCase):
    def test_checks_all_cells_and_empty_heightmaps(self):
        result = probe.verify_air_chunk(air_chunk(), [1889, 1888])
        self.assertEqual(result["cellsChecked"], 98304)
        self.assertEqual(result["sectionsChecked"], 24)
        self.assertTrue(result["heightmapsEmpty"])

    def test_protochunk_cannot_pass_even_with_air_sections(self):
        chunk = air_chunk()
        chunk.root.value["Status"] = anvil.Tag(8, "minecraft:structure_starts")
        chunk.root.value["Heightmaps"] = compound({})
        with self.assertRaisesRegex(ValueError, "protochunk"):
            probe.verify_air_chunk(chunk, [1889, 1888])

    def test_boundary_light_sections_are_not_counted_as_block_sections(self):
        chunk = air_chunk()
        chunk.root.value["sections"].value.items.append(compound({
            "Y": anvil.Tag(1, 20), "SkyLight": anvil.Tag(7, b"\xff" * 2048)}))
        self.assertEqual(probe.verify_air_chunk(chunk, [1889, 1888])["sectionsChecked"], 24)
        chunk.root.value["sections"].value.items[-1].value["block_states"] = compound({})
        with self.assertRaisesRegex(ValueError, "Non-lighting"):
            probe.verify_air_chunk(chunk, [1889, 1888])

    def test_final_cell_stone_is_detected_with_signed_packing(self):
        chunk = air_chunk()
        states = chunk.root.value["sections"].value.items[-1].value["block_states"].value
        states["palette"].value.items.append(compound({"Name": anvil.Tag(8, "minecraft:stone")}))
        indices = [0] * 4096
        indices[-1] = 1
        # Choose air index8 to exercise signed high bits in ordinary 4-bit words.
        states["palette"].value.items += [compound({"Name": anvil.Tag(8, "minecraft:air")}) for _ in range(7)]
        indices[-2] = 8
        states["data"] = anvil.Tag(12, pack_indices(indices, 4))
        with self.assertRaisesRegex(ValueError, "section 19, index 4095"):
            probe.verify_air_chunk(chunk, [1889, 1888])

    def test_unused_stone_palette_does_not_make_air_non_air(self):
        chunk = air_chunk()
        states = chunk.root.value["sections"].value.items[0].value["block_states"].value
        states["palette"].value.items.append(compound({"Name": anvil.Tag(8, "minecraft:stone")}))
        states["data"] = anvil.Tag(12, [0] * 256)
        self.assertTrue(probe.verify_air_chunk(chunk, [1889, 1888])["allAir"])

    def test_singleton_nonempty_data_or_noncanonical_air_properties_reject(self):
        for mode in ("data", "properties"):
            chunk = air_chunk()
            states = chunk.root.value["sections"].value.items[0].value["block_states"].value
            if mode == "data":
                states["data"] = anvil.Tag(12, [0] * 10)
            else:
                states["palette"].value.items[0].value["Properties"] = compound({"fake": anvil.Tag(8, "true")})
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                probe.verify_air_chunk(chunk, [1889, 1888])

    def test_five_bit_non_straddling_indices_and_invalid_id(self):
        section = {"block_states": compound({"palette": tag_list([
            compound({"Name": anvil.Tag(8, "minecraft:air")}) for _ in range(17)])})}
        values = [index % 17 for index in range(4096)]
        section["block_states"].value["data"] = anvil.Tag(12, pack_indices(values, 5))
        self.assertEqual(probe.section_states(section)[1], values)
        values[-1] = 31
        section["block_states"].value["data"] = anvil.Tag(12, pack_indices(values, 5))
        with self.assertRaisesRegex(ValueError, "out of bounds"):
            probe.section_states(section)

    def test_wrong_coords_version_missing_or_duplicate_sections_rejected(self):
        variants = []
        chunk = air_chunk(); chunk.root.value["xPos"].value = 1888; variants.append(chunk)
        chunk = air_chunk(); chunk.root.value["DataVersion"].value = 4189; variants.append(chunk)
        chunk = air_chunk(); chunk.root.value["sections"].value.items.pop(); variants.append(chunk)
        chunk = air_chunk(); chunk.root.value["sections"].value.items[-1].value["Y"].value = -4; variants.append(chunk)
        chunk = air_chunk(); chunk.root.value["Level"] = compound({}); variants.append(chunk)
        for chunk in variants:
            with self.subTest(chunk=chunk.root.value.keys()), self.assertRaises(ValueError):
                probe.verify_air_chunk(chunk, [1889, 1888])

    def test_heightmap_missing_truncated_or_nonzero_rejected(self):
        for mode in ("missing", "short", "nonzero"):
            chunk = air_chunk()
            maps = chunk.root.value["Heightmaps"].value
            if mode == "missing": maps.pop("WORLD_SURFACE")
            elif mode == "short": maps["WORLD_SURFACE"].value.pop()
            else: maps["WORLD_SURFACE"].value[-1] = 1
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                probe.verify_air_chunk(chunk, [1889, 1888])

    def test_neighbor_absence_reads_exact_region_slot(self):
        with tempfile.TemporaryDirectory() as folder:
            region = Path(folder) / probe.REGIONS / "r.59.59.mca"
            region.parent.mkdir(parents=True)
            source = air_chunk()
            source.root.value["xPos"].value = 1888
            anvil.write_region(region, {(1888, 1888): source})
            self.assertIsNone(probe.read_chunk(folder, [1889, 1888]))
            self.assertEqual(anvil.chunk_coords(probe.read_chunk(folder, [1888, 1888])), (1888, 1888))


class ProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.source_path = self.base / "source.dat"
        self.world = self.base / "world"
        self.output_path = self.world / probe.SETTINGS
        self.output_path.parent.mkdir(parents=True)
        settings = compound({"biome": anvil.Tag(8, "minecraft:plains"), "features": anvil.Tag(1, 1),
            "lakes": anvil.Tag(1, 1), "structure_overrides": tag_list([anvil.Tag(8, "minecraft:villages")], 8),
            "layers": tag_list([compound({"block": anvil.Tag(8, "minecraft:grass_block"), "height": anvil.Tag(3, 1)})])})
        data = compound({"seed": anvil.Tag(4, 42), "generate_structures": anvil.Tag(1, 1), "dimensions": compound({
            "minecraft:overworld": compound({"type": anvil.Tag(8, "minecraft:overworld"),
                "generator": compound({"type": anvil.Tag(8, "minecraft:flat"), "settings": settings})}),
            "minecraft:the_nether": compound({"unchanged": anvil.Tag(8, "fixture")})})})
        self.source = anvil.NbtFile("", compound({"DataVersion": anvil.Tag(3, 4790), "data": data}))
        anvil.write_level_dat(self.source_path, self.source)
        self.output = deepcopy(self.source)
        out_data = self.output.root.value["data"].value
        out_settings = out_data["dimensions"].value["minecraft:overworld"].value["generator"].value["settings"].value
        out_settings["layers"] = tag_list([compound({"block": anvil.Tag(8, "minecraft:air"), "height": anvil.Tag(3, 1)})])
        out_settings["features"] = anvil.Tag(1, 0)
        out_settings["lakes"] = anvil.Tag(1, 0)
        out_settings["structure_overrides"] = tag_list([], 8)
        out_data["generate_structures"] = anvil.Tag(1, 0)
        self.refresh()

    def refresh(self):
        anvil.write_level_dat(self.output_path, self.output)
        self.profile = {"kind": "scoped-unmapped-worldgen-profile", "schemaVersion": 1, "profileId": "flat-air-only-v1",
            "sourceSettings": {"path": str(self.source_path), **probe.record(self.source_path)},
            "outputSettings": {"path": str(self.output_path), **probe.record(self.output_path)},
            "sourceNbtSha256": hashlib.sha256(anvil.write_nbt(self.source)).hexdigest(),
            "outputNbtSha256": hashlib.sha256(anvil.write_nbt(self.output)).hexdigest()}
        self.writer = {"unmappedGenerator": self.profile, "templateDependencies": [probe.record(self.source_path)]}

    def test_actual_air_profile_and_unchanged_metadata_pass(self):
        self.assertTrue(probe.verify_profile(self.profile, self.world, self.writer)["independentMetadataCheck"])

    def test_seed_mutation_rejected_even_with_recomputed_output_pin(self):
        self.output.root.value["data"].value["seed"].value = 43
        self.refresh()
        with self.assertRaisesRegex(ValueError, "outside allowed"):
            probe.verify_profile(self.profile, self.world, self.writer)

    def test_output_pin_and_exported_receipt_must_match(self):
        changed = deepcopy(self.writer)
        changed["unmappedGenerator"]["profileId"] = "other"
        with self.assertRaisesRegex(ValueError, "differs"):
            probe.verify_profile(self.profile, self.world, changed)
        self.output_path.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "bytes changed"):
            probe.verify_profile(self.profile, self.world, self.writer)


class LogTests(unittest.TestCase):
    def test_actual_markers_require_correct_startup_and_shutdown_order(self):
        commands = ["forceload add 30216 30216", "forceload add 30232 30216",
                    "execute if loaded 30216 1 30216 run say FORK_SCOPED_MAPPED_LOADED",
                    "execute if loaded 30232 1 30216 run say FORK_SCOPED_TARGET_LOADED",
                    "execute if block 30216 0 30216 minecraft:grass_block run say FORK_SCOPED_SPAWN_FLOOR_OK",
                    "execute if block 30216 1 30216 minecraft:air run say FORK_SCOPED_SPAWN_FEET_OK",
                    "execute if block 30216 2 30216 minecraft:air run say FORK_SCOPED_SPAWN_HEAD_OK",
                    "save-all flush", "stop"]
        lines = ["Starting minecraft server version 26.1.2", 'Preparing level "world"', "Done (0.9s)!"]
        lines += ["[Server] " + row.split("run say ")[1] for row in commands if "run say " in row]
        lines += ["Saved the game", "Stopping server", "Saving chunks", "All dimensions are saved"]
        transcript = ("\n".join(commands) + "\n").encode()
        self.assertEqual(len(probe.verify_log("\n".join(lines), commands, transcript)), 5)
        with self.assertRaisesRegex(ValueError, "Missing real command marker"):
            probe.verify_log("\n".join(lines[:3] + lines[4:]), commands, transcript)
        with self.assertRaisesRegex(ValueError, "Unclean"):
            probe.verify_log("\n".join(lines[:-2] + lines[-2:][::-1]), commands, transcript)


if __name__ == "__main__":
    unittest.main()
