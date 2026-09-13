"""Focused geometry/provenance/vertical acceptance tests; standard library only."""
import unittest

from footprints import (FootprintError, HeightOverflow, normalize_feature,
                        normalize_height, parse_length_m, rasterize, vertical_bounds)


def feature(outer, holes=(), tags=None, identifier="way/123"):
    return {"type": "Feature", "id": identifier,
            "properties": tags or {},
            "geometry": {"type": "Polygon", "coordinates": [outer, *holes]}}


def rect(x0, z0, x1, z1):
    return [[x0, z0], [x1, z0], [x1, z1], [x0, z1], [x0, z0]]


class GeometryTests(unittest.TestCase):
    def test_fractional_projected_geometry_is_not_rounded(self):
        building = normalize_feature(feature(rect(30000.6, 28000.6, 30002.6, 28002.6)))
        self.assertEqual(building.bounds, (30000.6, 28000.6, 30002.6, 28002.6))
        self.assertEqual(list(rasterize(building, (30000, 28000, 30004, 28004))),
                         [(30001, 28001), (30002, 28001), (30001, 28002), (30002, 28002)])

    def test_hole_survives_tile_crossing(self):
        building = normalize_feature(feature(rect(-2.2, -2.2, 3.8, 3.8),
                                             [rect(-0.2, -0.2, 1.8, 1.8)]))
        whole = set(rasterize(building, (-4, -4, 5, 5)))
        left = set(rasterize(building, (-4, -4, 1, 5)))
        right = set(rasterize(building, (1, -4, 5, 5)))
        self.assertFalse(left & right)
        self.assertEqual(whole, left | right)
        self.assertEqual(len(whole), 32)
        self.assertTrue({(0, 0), (1, 0), (0, 1), (1, 1)}.isdisjoint(whole))

    def test_canonical_ring_orientation_start_and_components(self):
        original = rect(0, 0, 3, 3)[:-1]
        rotated = original[2:] + original[:2]
        a = normalize_feature(feature(original, [rect(1, 1, 2, 2)]))
        b = normalize_feature(feature(list(reversed(rotated)),
                                      [list(reversed(rect(1, 1, 2, 2)))]))
        self.assertEqual(a, b)

    def test_multipolygon_union_does_not_duplicate(self):
        source = feature(rect(0, 0, 2, 2))
        source["geometry"] = {"type": "MultiPolygon", "coordinates":
                              [[rect(0, 0, 2, 2)], [rect(1, 0, 3, 2)]]}
        building = normalize_feature(source)
        cells = list(rasterize(building, (0, 0, 3, 2)))
        self.assertEqual(cells, [(0, 0), (1, 0), (2, 0), (0, 1), (1, 1), (2, 1)])

    def test_center_on_edge_is_deterministic_across_tiles(self):
        building = normalize_feature(feature(rect(-0.5, -0.5, 2.5, 2.5)))
        self.assertEqual(list(rasterize(building, (-2, -2, 4, 4))),
                         [(x, z) for z in range(-1, 2) for x in range(-1, 2)])

    def test_triangle_uses_actual_shape_not_bbox(self):
        building = normalize_feature(feature([[0, 0], [4, 0], [0, 4], [0, 0]]))
        self.assertEqual(set(rasterize(building, (0, 0, 4, 4))),
                         {(0, 0), (1, 0), (2, 0), (0, 1), (1, 1), (0, 2)})

    def test_empty_and_distant_tiles_are_empty(self):
        building = normalize_feature(feature(rect(-100000, -100000, 100000, 100000)))
        self.assertEqual(list(rasterize(building, (0, 0, 1, 1))), [(0, 0)])
        self.assertEqual(list(rasterize(building, (1, 1, 1, 10))), [])
        self.assertEqual(list(rasterize(building, (100001, 100001, 100002, 100002))), [])

    def test_invalid_geometry_and_tiles_rejected(self):
        for ring in ([[0, 0], [1, 1], [2, 2]], [[0, 0], [1, 0], [float("nan"), 1]]):
            with self.assertRaises(FootprintError):
                normalize_feature(feature(ring))
        building = normalize_feature(feature(rect(0, 0, 1, 1)))
        for box in ((0.1, 0, 1, 1), (2, 0, 1, 1), (False, 0, 1, 1)):
            with self.assertRaises(FootprintError):
                list(rasterize(building, box))


class HeightTests(unittest.TestCase):
    def test_explicit_metres_win_over_levels(self):
        result = normalize_height({"height": "27.4 m", "building:levels": "12"})
        self.assertEqual(result.value_m, 27.4)
        self.assertEqual(result.source, "height")
        self.assertFalse(result.estimated)

    def test_units_and_invalid_measurements(self):
        self.assertAlmostEqual(parse_length_m("10 ft"), 3.048)
        self.assertAlmostEqual(parse_length_m('10\' 6"'), 3.2004)
        for invalid in ("10;20", "10-20", "NaN", float("inf"), True, '10\' 13"', "9" * 400 + "'1\""):
            with self.assertRaises(FootprintError):
                parse_length_m(invalid)

    def test_levels_and_default_are_explicitly_estimated(self):
        result = normalize_height({"building:levels": "3.5", "roof:height": "2"})
        self.assertEqual(result.value_m, 12.5)
        self.assertEqual(result.source, "building:levels+roof:height")
        self.assertTrue(result.estimated)
        fallback = normalize_height({"height": "unknown", "building:levels": "no"})
        self.assertEqual(fallback.value_m, 6)
        self.assertEqual(fallback.source, "default_height")
        self.assertEqual(len(fallback.warnings), 2)

    def test_properties_override_nested_tags(self):
        building = normalize_feature(feature(rect(0, 0, 2, 2), tags=
                                             {"height": 25, "tags": {"height": 20}}))
        self.assertEqual(building.height_m, 25)

    def test_vertical_integer_and_fractional_extent(self):
        building = normalize_feature(feature(rect(0, 0, 2, 2), tags={"height": "10.2", "min_height": "2.5"}))
        self.assertEqual(vertical_bounds(building, 5), (7, 16))
        self.assertEqual(vertical_bounds(building, -5.25), (-3, 5))
        self.assertFalse(building.min_height.estimated)

    def test_min_level_and_invalid_empty_height(self):
        building = normalize_feature(feature(rect(0, 0, 2, 2), tags={"height": 12, "building:min_level": 1}))
        self.assertEqual(vertical_bounds(building, 0), (3, 12))
        self.assertTrue(building.min_height.estimated)
        with self.assertRaises(FootprintError):
            normalize_feature(feature(rect(0, 0, 2, 2), tags={"height": 10, "min_height": 10}))

    def test_overflow_is_never_clipped(self):
        tall = normalize_feature(feature(rect(0, 0, 2, 2), tags={"height": 290}))
        self.assertEqual(vertical_bounds(tall, -64), (-64, 226))
        self.assertEqual(vertical_bounds(tall, 30), (30, 320))
        with self.assertRaisesRegex(HeightOverflow, "needs occupied Y 31..320"):
            vertical_bounds(tall, 31)
        with self.assertRaises(HeightOverflow):
            vertical_bounds(tall, -65)
        fractional = normalize_feature(feature(rect(0, 0, 2, 2), tags={"height": 290.01}))
        with self.assertRaises(HeightOverflow):
            vertical_bounds(fractional, 30)


if __name__ == "__main__":
    unittest.main()
