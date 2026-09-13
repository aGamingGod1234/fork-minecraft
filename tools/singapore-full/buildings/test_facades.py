"""Focused palette fidelity and deterministic tiling checks (stdlib unittest)."""
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

from facades import choose_palette, wall_material


class FacadeTests(unittest.TestCase):
    def test_explicit_materials_are_independent(self):
        palette = choose_palette("way/12", {
            "building:material": " Brick ", "roof:material": "copper",
        })
        self.assertEqual(palette.wall_block, "minecraft:bricks")
        self.assertEqual(palette.roof_block, "minecraft:copper_block")
        self.assertEqual(palette.wall_material_provenance, "mapped")
        self.assertEqual(palette.roof_material_provenance, "mapped")
        self.assertEqual(palette.wall_colour_provenance, "estimated")

    def test_explicit_glass_colour(self):
        palette = choose_palette(12, {
            "building:material": "glass", "building:colour": "red",
            "roof:colour": "blue",
        })
        self.assertEqual(palette.wall_block, "minecraft:red_stained_glass")
        self.assertEqual(palette.roof_block, "minecraft:blue_concrete")
        self.assertEqual(palette.wall_colour_provenance, "mapped")
        self.assertEqual(palette.roof_material_provenance, "estimated")

    def test_colour_only_is_partial_mapping_not_exact_material(self):
        palette = choose_palette("way/3", {"building:colour": "white"})
        self.assertEqual(palette.wall_block, "minecraft:white_concrete")
        self.assertEqual(palette.wall_provenance, "mapped")
        self.assertEqual(palette.wall_material_provenance, "estimated")
        self.assertEqual(palette.wall_colour_provenance, "mapped")
        self.assertEqual(palette.detail_provenance, "estimated")

    def test_hex_alias_and_fallback_labels(self):
        for value in ("#fff", "#FFFFFF", "white"):
            self.assertEqual(choose_palette(9, {"building:colour": value}).wall_block,
                             "minecraft:white_concrete")
        self.assertEqual(choose_palette(9, {"building:color": "grey"}).wall_block,
                         "minecraft:gray_concrete")
        palette = choose_palette(9, {"building:colour": "#nothex",
                                     "building:material": "unobtainium"})
        self.assertEqual(palette.wall_provenance, "estimated")
        self.assertTrue(any("unsupported source tag" in value for value in palette.estimates))

    def test_british_spelling_wins_if_both_supplied(self):
        self.assertEqual(choose_palette(9, {"building:colour": "red",
                                           "building:color": "blue"}).wall_block,
                         "minecraft:red_concrete")
        self.assertEqual(choose_palette(9, {"building:colour": "",
                                           "building:color": "blue"}).wall_block,
                         "minecraft:blue_concrete")

    def test_multi_material_is_explained(self):
        palette = choose_palette("relation/44", {"building:material": "unknown; brick; glass"})
        self.assertEqual(palette.wall_block, "minecraft:bricks")
        self.assertTrue(any("mixture simplified" in value for value in palette.estimates))

    def test_source_values_are_preserved_and_json_ready(self):
        tags = {"roof:material": "Slate", "building:material": "Brick", "name": "Private Name"}
        palette = choose_palette("way/100", tags)
        result = json.loads(json.dumps(palette.to_dict(), sort_keys=True))
        self.assertEqual(result["source_tags"]["roof:material"], "Slate")
        self.assertNotIn("name", result["source_tags"])
        self.assertEqual(palette, choose_palette("way/100", dict(reversed(list(tags.items())))))
        self.assertEqual(choose_palette(100, {}), choose_palette("100", {}))

    def test_fallback_is_stable_in_separate_processes(self):
        snippet = ("import json; from facades import choose_palette,wall_material; "
                   "p=choose_palette('way/123456',{'building':'apartments'}); "
                   "print(json.dumps([p.to_dict(),[wall_material(p,x,21,-x) "
                   "for x in range(-20,21)]],sort_keys=True))")
        outputs = []
        for seed in ("1", "98765"):
            env = dict(os.environ, PYTHONHASHSEED=seed)
            outputs.append(subprocess.check_output(
                [sys.executable, "-c", snippet], cwd=Path(__file__).parent, env=env, text=True))
        self.assertEqual(outputs[0], outputs[1])

    def test_adjacent_tiles_share_global_cells(self):
        palette = choose_palette("way/crosses-tiles", {"building": "office"})
        for boundary in (-1024, -512, 0, 512, 1024):
            # Independent tile traversal must agree on a one-block overlap.
            left = {x: wall_material(palette, x, -3, -701, face_axis="z")
                    for x in range(boundary - 3, boundary + 1)}
            right = {x: wall_material(palette, x, -3, -701, face_axis="z")
                     for x in range(boundary, boundary + 4)}
            self.assertEqual(left[boundary], right[boundary])

    def test_perpendicular_normals_have_same_cadence(self):
        palette = choose_palette("way/edge", {"building": "apartments"})
        y = next(y for y in range(10) if (y + palette.seed % palette.floor_period)
                 % palette.floor_period != 0)
        along_x = [wall_material(palette, x, y, 33, face_axis="z") for x in range(-20, 20)]
        along_z = [wall_material(palette, 33, y, z, face_axis="x") for z in range(-20, 20)]
        self.assertEqual(along_x, along_z)
        self.assertIn(palette.wall_block, along_x)
        self.assertIn(palette.window_block, along_x)

    def test_coordinates_are_not_silently_truncated(self):
        palette = choose_palette(1, {})
        for coordinates in ((1.5, 2, 3), (1, True, 3), (1, 2, "3")):
            with self.assertRaises(TypeError):
                wall_material(palette, *coordinates)
        with self.assertRaises(ValueError):
            wall_material(palette, 1, 2, 3, face_axis="y")

    def test_tag_input_contract(self):
        for feature_id in ("", "  ", True, 1.5, None):
            with self.assertRaises((ValueError, TypeError)):
                choose_palette(feature_id, {})
        for tags in (None, {"building:levels": 3}, {2: "three"}):
            with self.assertRaises(TypeError):
                choose_palette(1, tags)

    def test_style_taxonomy_is_only_an_estimate(self):
        cases = [
            ({"building": "apartments", "building:levels": "12",
              "building:architecture": "slab"}, "housing_slab_inspired"),
            ({"building": "residential", "building:levels": "24",
              "building:architecture": "point_block"}, "housing_point_inspired"),
            ({"building": "apartments"}, "housing_flats_inspired"),
            ({"building": "shophouse"}, "shophouse_inspired"),
            ({"building": "commercial", "building:levels": "3"}, "lowrise_shopfront_inspired"),
            ({"building": "office", "building:levels": "30"}, "office_glazing_estimate"),
            ({"building": "house"}, "landed_home_inspired"),
            ({"building": "warehouse"}, "industrial_estimate"),
        ]
        for tags, expected in cases:
            with self.subTest(expected=expected):
                palette = choose_palette("way/23", tags)
                self.assertEqual(palette.style_class, expected)
                self.assertEqual(palette.style_provenance, "estimated")
                self.assertNotIn("cobblestone", palette.wall_block)

    def test_styles_produce_distinct_density_without_changing_geometry(self):
        office = choose_palette(23, {"building": "office"})
        warehouse = choose_palette(23, {"building": "warehouse"})
        # The module only returns material IDs; callers retain all coordinates.
        office_glass = sum(wall_material(office, x, y, 1, face_axis="z") ==
                           office.window_block for x in range(60) for y in range(60))
        warehouse_glass = sum(wall_material(warehouse, x, y, 1, face_axis="z") ==
                              warehouse.window_block for x in range(60) for y in range(60))
        self.assertGreater(office_glass, warehouse_glass)

    def test_malformed_levels_do_not_create_false_typology(self):
        for value in ("NaN", "inf", "-2", "two", "2;3"):
            palette = choose_palette(3, {"building": "commercial", "building:levels": value})
            self.assertEqual(palette.style_class, "commercial_estimate")

    def test_estimate_disclosures_always_present(self):
        palette = choose_palette(4, {"building:material": "concrete",
                                     "building:colour": "white",
                                     "roof:material": "glass", "roof:colour": "blue"})
        self.assertEqual(palette.wall_provenance, "mapped")
        self.assertEqual(palette.roof_provenance, "mapped")
        self.assertTrue(any("window positions" in value for value in palette.estimates))
        self.assertTrue(any("no photo-exact claim" in value for value in palette.estimates))


if __name__ == "__main__":
    unittest.main()
