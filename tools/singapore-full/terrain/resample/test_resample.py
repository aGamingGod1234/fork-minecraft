import json
from pathlib import Path
import tempfile
import unittest

from shapely.geometry import MultiPolygon, Polygon, box
from resample import MAX_CELLS, PolygonMask, TO_SVY21, block_center, sample_tile


class ConstantRaster:
    metadata = {"source_id": "synthetic-test", "native_spacing_approx_m": 30}

    def __init__(self, status="valid"):
        self.status = status
        self.calls = []

    def sample_lonlat(self, lon, lat, method="nearest"):
        self.calls.append((lon, lat, method))
        return {"status": self.status, "elevation_m": 12.5 if self.status == "valid" else None,
                "source_id": "synthetic-test", "semantic": "surface_dsm", "vertical_datum": "EGM2008", "vertical_unit": "m"}


class ResampleTests(unittest.TestCase):
    def setUp(self):
        self.country = PolygonMask(box(0, 0, 100000, 100000), {"source_id": "test-country"})

    def test_svy21_origin_axis_order(self):
        east, north = TO_SVY21.transform(103 + 50 / 60, 1 + 22 / 60)
        self.assertAlmostEqual(east, 28001.642, places=5)
        self.assertAlmostEqual(north, 38744.572, places=5)

    def test_block_centers_direction_and_row_order(self):
        self.assertEqual(block_center(10, 20), (10.5, 59979.5))
        source = ConstantRaster()
        result = sample_tile(28000, 21255, 2, 2, [source], self.country)
        self.assertEqual(result["grid"]["first_center_en"], [28000.5, 38744.5])
        centers = [TO_SVY21.transform(lon, lat) for lon, lat, _ in source.calls]
        for actual, expected in zip(centers, [(28000.5, 38744.5), (28001.5, 38744.5),
                                               (28000.5, 38743.5), (28001.5, 38743.5)]):
            for a, e in zip(actual, expected):
                self.assertAlmostEqual(a, e, places=5)

    def test_components_holes_and_boundary(self):
        polygon = Polygon([(0, 0), (10, 0), (10, 10), (0, 10)],
                          [[(2, 2), (8, 2), (8, 8), (2, 8)]])
        mask = PolygonMask(MultiPolygon([polygon, box(20, 20, 21, 21)]), {})
        self.assertTrue(mask.covers(1, 1))
        self.assertFalse(mask.covers(5, 5))
        self.assertTrue(mask.covers(20.5, 20.5))
        self.assertTrue(mask.covers(0, 5))
        self.assertEqual(mask.metadata()["components"], 2)
        self.assertEqual(mask.metadata()["holes"], 1)

    def test_geojson_masks_preserve_hole_and_island(self):
        payload = {"type": "MultiPolygon", "coordinates": [
            [[[0, 0], [10, 0], [10, 10], [0, 10], [0, 0]],
             [[2, 2], [8, 2], [8, 8], [2, 8], [2, 2]]],
            [[[20, 20], [21, 20], [21, 21], [20, 21], [20, 20]]]]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mask.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            mask = PolygonMask.from_geojson(path, "EPSG:3414")
        self.assertEqual(mask.metadata()["components"], 2)
        self.assertEqual(mask.metadata()["holes"], 1)
        self.assertFalse(mask.covers(5, 5))

    def test_admin_mask_does_not_assert_land(self):
        result = sample_tile(28000, 21255, 1, 1, [ConstantRaster()], self.country)
        self.assertEqual(result["arrays"]["land_mask"], [None])
        self.assertEqual(result["arrays"]["elevation_m"], [12.5])
        self.assertFalse(result["masks"]["country_is_land"])
        self.assertFalse(result["sampling"]["one_metre_measurement"])
        self.assertIsNone(result["grid"]["y_offset"])

    def test_water_and_country_exclusion_never_sample(self):
        source = ConstantRaster()
        country = PolygonMask(box(28000, 38744, 28002, 38745), {})
        land = PolygonMask(box(28001, 38744, 28002, 38745), {})
        result = sample_tile(28000, 21255, 3, 1, [source], country, land)
        self.assertEqual(result["arrays"]["sample_status"], ["water", "valid", "outside_country"])
        self.assertEqual(result["arrays"]["land_mask"], [False, True, None])
        self.assertEqual(len(source.calls), 1)

    def test_nodata_is_not_zero_or_fallback(self):
        second = ConstantRaster()
        result = sample_tile(28000, 21255, 1, 1, [ConstantRaster("nodata"), second], self.country)
        self.assertEqual(result["arrays"]["sample_status"], ["nodata"])
        self.assertEqual(result["arrays"]["elevation_m"], [None])
        self.assertEqual(second.calls, [])

    def test_outside_first_tile_tries_next_without_filling_nodata(self):
        result = sample_tile(28000, 21255, 1, 1, [ConstantRaster("outside"), ConstantRaster()], self.country)
        self.assertEqual(result["arrays"]["sample_status"], ["valid"])

    def test_bilinear_flag_and_budget(self):
        result = sample_tile(28000, 21255, 1, 1, [ConstantRaster()], self.country, method="bilinear")
        self.assertTrue(result["sampling"]["interpolated"])
        with self.assertRaises(ValueError):
            sample_tile(0, 0, MAX_CELLS + 1, 1, [ConstantRaster()], self.country)
        with self.assertRaises(ValueError):
            sample_tile(0, 0, 0, 1, [ConstantRaster()], self.country)
        with self.assertRaises(ValueError):
            sample_tile(0.5, 0, 1, 1, [ConstantRaster()], self.country)

    def test_invalid_polygon_rejected(self):
        with self.assertRaises(ValueError):
            PolygonMask(Polygon([(0, 0), (2, 2), (2, 0), (0, 2)]), {})


if __name__ == "__main__":
    unittest.main()
