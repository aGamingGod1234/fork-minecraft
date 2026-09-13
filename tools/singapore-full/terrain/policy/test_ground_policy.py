import json
import unittest
from ground_policy import (ElevationEvidence as E, BuildingHeight as H, DatumTransform,
                           VerticalMapping as M, resolve_building, resolve_ground,
                           interpolate_ground)


class GroundPolicyTest(unittest.TestCase):
    def setUp(self):
        self.mapping = M("EGM2008", 64, -64, 319, "nearest_ties_up")
        self.ground = E(12.25, "bare_earth", "EGM2008", "survey:fixture", 0.2)
        self.dsm = E(48, "surface_dsm", "EGM2008", "glo30:fixture", 4)
        self.height = H(30, "above_ground", "building:fixture", uncertainty_m=0.5)

    def test_measured_ground_relative_height(self):
        d = resolve_building(self.dsm, self.height, self.mapping, ground_evidence=self.ground)
        self.assertEqual(d["status"], "resolved")
        self.assertEqual(d["ground"]["value_m"], 12.25)
        self.assertEqual(d["roof"]["value_m"], 42.25)
        self.assertEqual(d["roof"]["block_y"], 106)
        self.assertAlmostEqual(d["roof"]["uncertainty_m"], 0.7)
        self.assertEqual(d["roof"]["quantization_error_m"], -0.25)
        self.assertNotIn("glo30:fixture", d["roof"]["source_ids"])
        json.dumps(d, allow_nan=False)

    def test_dsm_only_is_blocked_not_78_metres(self):
        d = resolve_building(self.dsm, self.height, self.mapping)
        self.assertEqual(d["status"], "blocked")
        self.assertIsNone(d["ground"]["value_m"])
        self.assertIsNone(d["roof"]["value_m"])

    def test_bare_earth_source_needs_no_override(self):
        d = resolve_building(self.ground, self.height, self.mapping)
        self.assertEqual(d["roof"]["value_m"], 42.25)

    def test_explicit_absolute_roof_not_added_to_ground(self):
        d = resolve_building(self.ground, H(80, "absolute_roof", "roof:survey", "EGM2008", 0.3), self.mapping)
        self.assertEqual(d["roof"]["value_m"], 80)
        self.assertEqual(d["roof"]["block_y"], 144)
        self.assertEqual(d["roof"]["source_ids"], ["roof:survey"])

    def test_absolute_roof_preserved_while_ground_blocked(self):
        d = resolve_building(self.dsm, H(80, "absolute_roof", "roof:survey", "EGM2008"), self.mapping)
        self.assertEqual(d["status"], "blocked")
        self.assertEqual(d["roof"]["status"], "resolved")
        self.assertEqual(d["roof"]["value_m"], 80)

    def test_dsm_cannot_be_supplied_as_ground_override(self):
        d = resolve_ground(self.ground, self.mapping, ground_evidence=self.dsm)
        self.assertEqual(d["status"], "blocked")

    def test_estimated_ground_requires_opt_in(self):
        estimate = E(10, "estimated_ground", "EGM2008", "ground:heuristic", 3, ("glo30:fixture",))
        self.assertEqual(resolve_building(self.dsm, self.height, self.mapping, ground_evidence=estimate)["status"], "blocked")
        d = resolve_building(self.dsm, self.height, self.mapping, ground_evidence=estimate, allow_estimated_ground=True)
        self.assertEqual(d["status"], "estimated")
        self.assertEqual(d["roof"]["value_m"], 40)
        self.assertEqual(d["roof"]["uncertainty_m"], 3.5)
        self.assertIn("glo30:fixture", d["ground"]["source_ids"])

    def test_mixed_datums_block_without_transform(self):
        other = E(10, "bare_earth", "LOCAL_DATUM", "fixture", 0.1)
        self.assertEqual(resolve_ground(other, self.mapping)["reasons"], ["vertical_datum_transform_required"])

    def test_explicit_datum_transform_and_uncertainty(self):
        # Synthetic fixture only. This is NOT a real Singapore datum conversion.
        mapping = M("EGM2008", 64, -64, 319, "nearest_ties_up",
                    (DatumTransform("LOCAL_DATUM", "EGM2008", -1.25, "synthetic:offset", 0.4),))
        d = resolve_ground(E(10, "bare_earth", "LOCAL_DATUM", "fixture", 0.1), mapping)
        self.assertEqual(d["value_m"], 8.75)
        self.assertAlmostEqual(d["uncertainty_m"], 0.5)
        self.assertIn("synthetic:offset", d["source_ids"])

    def test_unknown_uncertainty_stays_unknown(self):
        d = resolve_building(self.ground, H(30, "above_ground", "height:unknown"), self.mapping)
        self.assertIsNone(d["roof"]["uncertainty_m"])

    def test_roof_below_ground_is_blocked(self):
        d = resolve_building(self.ground, H(5, "absolute_roof", "roof:bad", "EGM2008"), self.mapping)
        self.assertEqual(d["roof"]["reasons"], ["roof_below_ground"])

    def test_y_out_of_bounds_preserves_height_without_clipping(self):
        d = resolve_building(self.ground, H(300, "above_ground", "height:tower"), self.mapping)
        self.assertEqual(d["roof"]["value_m"], 312.25)
        self.assertIsNone(d["roof"]["block_y"])
        self.assertEqual(d["roof"]["reasons"], ["minecraft_y_out_of_bounds"])

    def test_explicit_offset_and_negative_rounding(self):
        mapping = M("EGM2008", -40, -64, 319, "nearest_ties_up")
        self.assertEqual(resolve_ground(E(0.5, "bare_earth", "EGM2008", "fixture"), mapping)["block_y"], -39)
        mapping = M("EGM2008", -40, -64, 319, "floor")
        self.assertEqual(resolve_ground(E(0.5, "bare_earth", "EGM2008", "fixture"), mapping)["block_y"], -40)

    def test_interpolation_value_bound_and_provenance(self):
        samples = [E(v, "bare_earth", "EGM2008", f"survey:{i}", 0.2) for i, v in enumerate([0, 10, 20, 30])]
        estimate = interpolate_ground(samples, [0.25] * 4, method="bilinear", source_id="cell:fixture",
                                      interpolation_uncertainty_m=1.0)
        self.assertEqual(estimate.elevation_m, 15)
        self.assertAlmostEqual(estimate.uncertainty_m, 1.2)
        self.assertEqual(estimate.semantic, "estimated_ground")
        self.assertEqual(len(estimate.provenance), 5)
        self.assertEqual(resolve_ground(estimate, self.mapping)["status"], "blocked")
        self.assertEqual(resolve_ground(estimate, self.mapping, allow_estimated_ground=True)["block_y"], 79)

    def test_unknown_interpolation_error_stays_unknown(self):
        estimate = interpolate_ground([self.ground], [1], method="copy-estimate", source_id="fixture")
        self.assertIsNone(estimate.uncertainty_m)

    def test_interpolation_rejects_dsm_nodata_and_mixed_datums(self):
        for samples in ([self.dsm], [None], [self.ground, E(12, "bare_earth", "OTHER", "other")]):
            with self.subTest(samples=samples), self.assertRaises(ValueError):
                interpolate_ground(samples, [1 / len(samples)] * len(samples), method="bilinear", source_id="fixture")

    def test_interpolation_rejects_extrapolation_or_missing_weight(self):
        for weights in ([1.2, -0.2], [0.2, 0.2], [float("nan"), 1], [1]):
            with self.subTest(weights=weights), self.assertRaises(ValueError):
                interpolate_ground([self.ground, self.ground], weights, method="bilinear", source_id="fixture")

    def test_nearest_half_away_from_zero_signed_halves(self):
        mapping = M("EGM2008", 0, -10, 10, "nearest_half_away_from_zero")
        for value, expected in [(0.5, 1), (-0.5, -1), (1.5, 2), (-1.5, -2), (0, 0),
                                (0.49, 0), (-0.49, 0), (1.49, 1), (-1.49, -1)]:
            with self.subTest(value=value):
                d = resolve_ground(E(value, "bare_earth", "EGM2008", "fixture"), mapping)
                self.assertEqual(d["block_y"], expected)
                self.assertAlmostEqual(d["quantization_error_m"], expected - value)

    def test_half_away_rounds_world_y_and_respects_negative_bounds(self):
        for sea_y, elevation, expected, status in [(-64, 0.5, -64, "resolved"),
                                                  (-64, -0.5, None, "blocked"),
                                                  (319, 0.5, None, "blocked")]:
            with self.subTest(sea_y=sea_y, elevation=elevation):
                mapping = M("EGM2008", sea_y, -64, 319, "nearest_half_away_from_zero")
                d = resolve_ground(E(elevation, "bare_earth", "EGM2008", "fixture"), mapping)
                self.assertEqual(d["status"], status)
                self.assertEqual(d["block_y"], expected)
                if status == "blocked":
                    self.assertEqual(d["reasons"], ["minecraft_y_out_of_bounds"])

    def test_invalid_input_is_rejected(self):
        for value in (float("nan"), float("inf"), True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                E(value, "bare_earth", "EGM2008", "fixture")
        with self.assertRaises(ValueError):
            H(-1, "above_ground", "fixture")
        with self.assertRaises(ValueError):
            H(20, "absolute_roof", "fixture")
        with self.assertRaises(ValueError):
            E(10, "bare_earth", "EGM2008", "fixture", -1)
        with self.assertRaises(ValueError):
            M("EGM2008", 64, -64, 319, "implicit")


if __name__ == "__main__":
    unittest.main()
