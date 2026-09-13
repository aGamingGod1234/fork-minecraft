"""Regressions from the admitted national CBD projected source."""
import unittest

from footprints import FootprintError, normalize_feature, vertical_bounds


def feature(identity="way/test", **tags):
    return {"type":"Feature","id":identity,
            "geometry":{"type":"Polygon","coordinates":[[[0,0],[2,0],[2,2],[0,2]]]},
            "properties":{"building":"yes",**tags}}


class ElevatedDefaultTests(unittest.TestCase):
    def test_actual_hitachi_missing_total_rises_above_minimum(self):
        building=normalize_feature(feature("way/407926393",min_height="16"))
        self.assertEqual((building.min_height_m,building.height_m),(16,22))
        self.assertEqual(building.height.source,"min_height+default_span")
        self.assertTrue(building.height.estimated)
        self.assertIn("not a measured total",building.height.warnings[-1])

    def test_actual_parts_missing_total_rise_above_minimum_levels(self):
        for identity in ("way/1385793323","way/325903781"):
            with self.subTest(identity=identity):
                building=normalize_feature(feature(identity,**{"building:part":"yes","building:min_level":"5"}))
                self.assertEqual((building.min_height_m,building.height_m),(15,21))
                self.assertEqual(building.height.source,"building:min_level+default_span")
                self.assertTrue(building.height.estimated)

    def test_default_span_is_preserved_even_for_low_minimum(self):
        self.assertEqual(normalize_feature(feature(min_height="2")).height_m,8)
        self.assertEqual(normalize_feature(feature(min_height="2"),default_height=4).height_m,6)
        self.assertEqual(normalize_feature(feature()).height_m,6)

    def test_explicit_or_invalid_total_claims_do_not_receive_offset(self):
        cases=[{"height":"16","min_height":"16"},
               {"height":"6","min_height":"16"},
               {"building":"roof","building:levels":"1","building:min_level":"1"},
               {"height":"unknown","min_height":"16"},
               {"building:levels":"unknown","min_height":"16"}]
        for tags in cases:
            with self.subTest(tags=tags),self.assertRaises(FootprintError):
                normalize_feature(feature(**tags))

    def test_estimated_offset_does_not_clip_world_height(self):
        building=normalize_feature(feature(min_height="318"))
        self.assertEqual(building.height_m,324)
        with self.assertRaises(FootprintError):
            vertical_bounds(building,0)


if __name__ == "__main__":
    unittest.main()
