import importlib.util
import json
import math
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("road_raster", ROOT / "road_raster.py")
road = importlib.util.module_from_spec(spec)
spec.loader.exec_module(road)


def feature(points, tags=None):
    return {"type": "Feature", "id": "test/synthetic", "properties": {
        "tags": tags or {"highway": "residential", "width": "4"}, "node_ids": [11, 12]},
        "geometry": {"type": "LineString", "coordinates": points}}


class WidthTests(unittest.TestCase):
    def test_explicit_metres_and_feet(self):
        for raw, expected in [("3", 3), ("3 m", 3), ("10 ft", 3.048), ('16\'3"', 4.953), ("6'", 1.8288)]:
            with self.subTest(raw=raw):
                self.assertAlmostEqual(road.parse_width_m(raw), expected)
        got = road.normalize_width({"highway": "residential", "width": "5 m", "source:width": "survey", "lanes": "3"})
        self.assertFalse(got["inferred"])
        self.assertEqual(got["width_m"], 5)
        self.assertEqual(got["inputs"]["source:width"], "survey")

    def test_invalid_widths_not_silently_measured(self):
        for raw in ["3;4", "3-5", "narrow", "nan", "inf", -3, 0, True, '6\'14"']:
            self.assertIsNone(road.parse_width_m(raw), raw)
        got = road.normalize_width({"highway": "service", "width": "narrow", "maxwidth": "1"})
        self.assertTrue(got["inferred"])
        self.assertEqual(got["rejected"], {"width": "narrow"})
        self.assertEqual(got["width_m"], 3.2)

    def test_lanes_and_estimates(self):
        for tags in [{"lanes": "3"}, {"lanes:forward": "2", "lanes:backward": "1"}]:
            got = road.normalize_width({"highway": "primary", **tags})
            self.assertEqual(got["source"], "inferred:lanes")
            self.assertAlmostEqual(got["width_m"], 9.6)
        self.assertEqual(road.normalize_width({"highway": "residential", "lanes:forward": "4"})["source"], "inferred:highway")
        self.assertTrue(road.normalize_width({"highway": "footway", "est_width": "2.2"})["inferred"])

    def test_footpaths_do_not_become_car_lanes(self):
        self.assertEqual(road.normalize_width({"highway": "footway", "lanes": "2"})["width_m"], 2)
        self.assertEqual(road.normalize_width({"highway": "path"})["width_m"], 1.5)
        with self.assertRaises(ValueError):
            road.normalize_width({"highway": "construction"})


class RasterTests(unittest.TestCase):
    def test_core_seam_union_matches_whole(self):
        source = feature([[-20, -5], [0, 7], [20, -2]])
        before = json.dumps(source)
        whole = road.rasterize_road(source, (-24, -12, 24, 12), y=6)
        parts = [road.rasterize_road(source, c, y=6) for c in [(-24, -12, 0, 12), (0, -12, 24, 12)]]
        actual = [tuple(p) for part in parts for p in part["blocks"]]
        self.assertEqual(len(actual), len(set(actual)))
        self.assertEqual(sorted(actual), [tuple(p) for p in whole["blocks"]])
        self.assertTrue(all(p["feature_digest"] == whole["feature_digest"] for p in parts))
        self.assertEqual(json.dumps(source), before)
        self.assertEqual(whole["feature"]["properties"]["node_ids"], [11, 12])

    def test_centre_semantics_and_sorted_stability(self):
        source = feature([[0, 0], [3, 0]], {"highway": "footway", "width": "2"})
        got = road.rasterize_road(source, (-2, -2, 5, 2))
        expected = [[x, 0, z] for x in range(-1, 4) for z in (-1, 0)]
        self.assertEqual(got["blocks"], expected)
        self.assertEqual(got, road.rasterize_road(source, (-2, -2, 5, 2)))
        distant = road.rasterize_road(source, (100, 100, 110, 110))
        self.assertEqual(distant["blocks"], [])
        self.assertEqual(distant["candidate_count"], 0)

    def test_bound_budget_prevents_large_scan(self):
        with self.assertRaisesRegex(ValueError, "candidate budget"):
            road.rasterize_road(feature([[0, 0], [100000, 100000]]), (0, 0, 100000, 100000), max_blocks=10)
        # A long complete way can be evaluated in a small tile without full-way scan.
        got = road.rasterize_road(feature([[-100000, 0], [100000, 0]]), (0, 0, 3, 3), max_blocks=9)
        self.assertLessEqual(got["candidate_count"], 9)

    def test_degenerate_segment_and_invalid_input(self):
        self.assertTrue(road.rasterize_road(feature([[0, 0], [0, 0]]), (-3, -3, 3, 3))["blocks"])
        with self.assertRaises(ValueError):
            road.rasterize_road(feature([[0, 0], [math.nan, 0]]), (0, 0, 3, 3))
        with self.assertRaises(ValueError):
            road.rasterize_road(feature([[0, 0], [1, 0]]), (0, 0, 3.5, 3))

    def test_actual_osm_fixture(self):
        fixture = json.loads((ROOT / "fixtures" / "roads_osm.json").read_text(encoding="utf-8"))
        self.assertEqual(fixture["provenance"]["licence"], "ODbL-1.0")
        self.assertEqual(len(fixture["provenance"]["source_sha256"]), 64)
        self.assertTrue(fixture["features"])
        for actual in fixture["features"]:
            self.assertTrue(str(actual["id"]).startswith("way/"))
            points = actual["geometry"]["coordinates"]
            x, z = points[0]
            bounds = (math.floor(x) - 8, math.floor(z) - 8, math.floor(x) + 8, math.floor(z) + 8)
            result = road.rasterize_road(actual, bounds)
            self.assertTrue(result["blocks"])
            self.assertEqual(actual, result["feature"])


if __name__ == "__main__":
    unittest.main()