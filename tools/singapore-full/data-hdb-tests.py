"""Focused fixtures for exact address matching; no source downloads or writes."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("hdb_matching", Path(__file__).with_name("data-hdb-matching.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class MatchingTests(unittest.TestCase):
    def setUp(self):
        self.tags = {"building": "apartments", "addr:housenumber": "101A", "addr:street": "Bukit Batok West Avenue 6"}
        self.row = {"blk_no": "101a", "street": "BT BATOK WEST AVE 6", "max_floor_lvl": "16", "residential": "Y", "multistorey_carpark": "N"}

    def decide(self, tags=None, row=None, count=1):
        return m.enrichment_decision(self.tags if tags is None else tags, self.row if row is None else row, count, "hdb-exact-v1")

    def test_exact_expansion_and_proxy(self):
        result = self.decide()
        self.assertEqual(result["status"], "enrich-missing")
        self.assertEqual(result["enrichment"]["heightMetres"], 44.8)
        self.assertFalse(result["enrichment"]["isMeasuredHeight"])
        self.assertFalse(result["enrichment"]["roofIncluded"])

    def test_raw_and_input_preserved(self):
        before = copy.deepcopy((self.tags, self.row))
        self.decide()
        self.assertEqual(before, (self.tags, self.row))
        result = m.normalize_block("  blk 101a  ")
        self.assertEqual(result["raw"], "  blk 101a  ")
        self.assertEqual(result["normalized"], "101A")

    def test_block_rejections_and_no_guessed_zero(self):
        for block in ("101-103", "101/103", "101;103", "101 AND 103", "#01-01", "101 A", "10I"):
            if block == "10I":
                self.assertNotEqual(m.normalize_block(block)["normalized"], "101")
            else:
                self.assertNotEqual(m.normalize_block(block)["status"], "valid", block)
        self.assertNotEqual(m.make_key("001", "BEACH RD")["key"], m.make_key("1", "BEACH RD")["key"])

    def test_street_context_and_ranges(self):
        self.assertEqual(m.normalize_street("TAMPINES ST 21")["normalized"], "TAMPINES STREET 21")
        self.assertEqual(m.normalize_street("ST MICHAEL'S RD")["normalized"], "ST MICHAEL'S ROAD")
        self.assertNotEqual(m.make_key("1", "ST MICHAEL'S RD")["key"], m.make_key("1", "STREET MICHAEL'S RD")["key"])
        for street in ("A RD / B RD", "TAMPINES ST 21-23", "A RD & B RD", ""):
            self.assertNotEqual(m.normalize_street(street)["status"], "valid")

    def test_no_fuzzy_match(self):
        self.assertEqual(self.decide(tags={**self.tags, "addr:street": "Bukit Batok West Avenue 5"})["status"], "no-match")
        self.assertEqual(self.decide(tags={**self.tags, "addr:housenumber": "101B"})["status"], "no-match")

    def test_multiplicity(self):
        for count in (0, 2, True, "1", None):
            self.assertEqual(self.decide(count=count)["status"], "ambiguous")
        self.assertEqual(self.decide(row={**self.row, "_match_count": 2})["status"], "ambiguous")

    def test_preserve_existing_evidence(self):
        for field in ({"height": "100 ft"}, {"building:levels": "12"}, {"height": "45", "building:levels": "12"}):
            result = self.decide(tags={**self.tags, **field})
            self.assertEqual(result["status"], "preserved")
            self.assertIsNone(result["enrichment"])

    def test_invalid_never_overwritten(self):
        for field in ({"height": "0"}, {"height": ""}, {"building:levels": ""}, {"building:levels": "2.5"}, {"height": "45", "building:levels": "bad"}, {"height": "45", "building:levels": "0"}, {"height": "45", "building:levels": None}, {"height": "10", "min_height": "12"}):
            self.assertEqual(self.decide(tags={**self.tags, **field})["status"], "manual-review", field)

    def test_valid_underground_count_preserved(self):
        self.assertEqual(self.decide(tags={**self.tags, "building:levels": "0", "building:levels:underground": "2"})["status"], "preserved")

    def test_default_policy_and_configurable_proxy(self):
        result = m.enrichment_decision(self.tags, self.row, 1)
        self.assertEqual(result["policyVersion"], "hdb-max-floor-proxy-v1")
        result = m.enrichment_decision(self.tags, self.row, 1, metres_per_floor=3.1)
        self.assertEqual(result["enrichment"]["heightMetres"], 49.6)
        self.assertEqual(result["enrichment"]["metresPerFloor"], 3.1)
        self.assertEqual(result["maxFloorObservation"]["raw"], "16")
        self.assertEqual(result["maxFloorObservation"]["value"], 16)
        for value in (0, -1, float("nan"), float("inf"), float("-inf"), True, "2.8", None):
            with self.assertRaises(ValueError):
                m.enrichment_decision(self.tags, self.row, 1, metres_per_floor=value)

    def test_new_proxy_requires_confirmed_residential_non_carpark(self):
        for flags in ({"residential": "N"}, {"residential": None}, {"multistorey_carpark": "Y"}, {"multistorey_carpark": None}):
            row = {**self.row, **flags}
            self.assertEqual(self.decide(row=row)["status"], "manual-review")
            for known in ({"height": "45"}, {"building:levels": "12"}):
                self.assertEqual(self.decide(tags={**self.tags, **known}, row=row)["status"], "preserved")

    def test_bad_hdb_floor(self):
        for raw in (None, "", "0", "16.5", "-1", "NaN", "101", "1;2"):
            self.assertEqual(self.decide(row={**self.row, "max_floor_lvl": raw})["status"], "manual-review")

    def test_parts_and_vertical_geometry_not_guessed(self):
        for extra in ({"building:part": "yes"}, {"min_height": "3"}, {"building:min_level": "1"}, {"building:levels:underground": "1"}):
            self.assertEqual(self.decide(tags={**self.tags, **extra})["status"], "manual-review")


if __name__ == "__main__":
    unittest.main()
