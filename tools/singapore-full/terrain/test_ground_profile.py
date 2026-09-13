import copy
import json
from pathlib import Path
import unittest

from ground_profile import flat_surface, validate


class GroundProfileTests(unittest.TestCase):
    def setUp(self):
        self.profile = json.loads(Path(__file__).with_name("flat-provisional-y0.json").read_text())

    def test_frozen_plane_has_surface_zero_standing_one(self):
        self.assertTrue(validate(self.profile)["valid"])
        self.assertEqual(flat_surface(self.profile, 29712, 30496)["standingY"], 1)
        self.assertFalse(flat_surface(self.profile, -1, -1)["actualGroundAccepted"])

    def test_cannot_promote_flat_plane_to_ground(self):
        for key, value in (("actualGroundAccepted", True), ("surveyed", True), ("uncertaintyMeters", 0)):
            p = copy.deepcopy(self.profile)
            p["evidence"][key] = value
            self.assertFalse(validate(p)["valid"])

    def test_no_fake_datum(self):
        self.profile["vertical"]["datum"] = "EGM2008"
        self.assertFalse(validate(self.profile)["valid"])

    def test_bad_shapes_and_rounding_are_rejected(self):
        self.assertFalse(validate(None)["valid"])
        self.assertFalse(validate({"grid": []})["valid"])
        self.profile["vertical"]["rounding"] = "bankers"
        self.assertFalse(validate(self.profile)["valid"])

    def test_axis_and_range_rejected(self):
        self.profile["grid"]["zDirection"] = "north"
        self.assertFalse(validate(self.profile)["valid"])
        self.setUp()
        self.profile["vertical"]["surfaceY"] = 319
        self.assertFalse(validate(self.profile)["valid"])

    def test_elevation_never_falls_back_to_flat(self):
        p = self.profile
        p["mode"] = "elevation-evidence"
        p["samplesPath"] = "terrain/samples.json"
        p["vertical"].pop("surfaceY")
        p["vertical"].update(datum="EGM2008", seaLevelY=0)
        p["evidence"]["classification"] = "surface-dsm"
        self.assertTrue(validate(p)["valid"])
        with self.assertRaises(ValueError):
            flat_surface(p, 0, 0)
        p["evidence"]["actualGroundAccepted"] = True
        self.assertFalse(validate(p)["valid"])


if __name__ == "__main__":
    unittest.main()
