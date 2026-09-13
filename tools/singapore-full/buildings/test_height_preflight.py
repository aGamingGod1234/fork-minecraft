import json
from pathlib import Path
import tempfile
import unittest

from height_preflight import SourceError, assess_record, run_preflight


def row(identity=1, **tags):
    return {"osmType": "way", "osmId": identity, "objectKind": "building", "classification": "missing",
            "tags": tags, "warnings": [], "errors": []}


class HeightPreflightTests(unittest.TestCase):
    def test_numeric_height_and_minimum_provenance(self):
        result = assess_record(row(height=290))
        self.assertEqual(result["height"]["value_m"], 290)
        self.assertEqual(result["normalizationStatus"], "mapped")
        self.assertEqual(result["heightClass"], "tagged")
        self.assertTrue(result["minHeight"]["estimated"])

    def test_missing_default_and_levels_are_distinct(self):
        self.assertEqual(assess_record(row())["heightClass"], "missing")
        self.assertEqual(assess_record(row(**{"building:levels": 4}))["heightClass"], "estimated")

    def test_new_missing_total_minimum_plus_span(self):
        for tags, expected in (({"min_height": 16}, 22), ({"building:min_level": 5}, 21)):
            result = assess_record(row(**tags))
            self.assertEqual(result["height"]["value_m"], expected)
            self.assertTrue(result["minimumPlusDefaultSpan"])
            self.assertEqual(result["policyAction"], "height-only-admissible")

    def test_explicit_contradiction_and_invalid_fallback_quarantine(self):
        result = assess_record(row(height=16, min_height=16))
        self.assertTrue(result["conflictingHeight"])
        self.assertEqual(result["policyAction"], "quarantine-needed")
        result = assess_record(row(height="bad", **{"building:levels": 11}))
        self.assertEqual(result["normalizationStatus"], "estimated")
        self.assertEqual(result["policyAction"], "quarantine-needed")
        self.assertTrue(result["normalizationWarnings"])

    def test_roof_and_zero_level_contradiction_not_reinterpreted(self):
        result = assess_record(row(**{"building:levels": 1, "building:min_level": 1}))
        self.assertTrue(result["conflictingHeight"])
        result = assess_record(row(height=12, **{"roof:height": "bad"}))
        self.assertEqual(result["policyAction"], "quarantine-needed")

    def test_node_flags_and_original_invalid_preserved(self):
        record = row(height=20)
        record.update(osmType="node", classification="invalid", warnings=["building-point-has-no-footprint"], errors=["source-error"])
        result = assess_record(record)
        self.assertEqual(result["originalClassification"], "invalid")
        self.assertEqual(result["sourceIssueFlags"]["warnings"], record["warnings"])
        self.assertEqual(result["policyAction"], "quarantine-needed")
        self.assertIn("not-assessed", result["geometryAssessment"])

    def test_corrupt_stream_missing_identity_abort_no_success_receipt(self):
        for bad in ('{broken\n', json.dumps({"tags": {}}) + '\n', '{"osmId":NaN}\n'):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source = root / "input.jsonl"
                source.write_text(json.dumps(row()) + "\n" + bad, encoding="utf-8")
                with self.assertRaises(SourceError):
                    run_preflight(source, root / "out.jsonl", root / "summary.json")
                self.assertFalse((root / "summary.json").exists())
                self.assertFalse((root / "out.jsonl").exists())
                self.assertTrue((root / "out.jsonl.partial").exists())

    def test_complete_stream_counts_and_no_geometry_claim(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = [row(1, height=22), row(2), row(3, height="bad")]
            source = root / "input.jsonl"
            source.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
            summary = run_preflight(source, root / "out.jsonl", root / "summary.json")
            self.assertTrue(summary["complete"])
            self.assertEqual(summary["totals"]["rows"], 3)
            self.assertEqual(summary["counts"]["policyAction"], {"height-only-admissible": 2, "quarantine-needed": 1})
            self.assertEqual(len((root / "out.jsonl").read_text().splitlines()), 3)
            self.assertIn("CRS/projection", summary["notValidated"])

    def test_duplicate_identity_aborts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "input.jsonl"
            source.write_text((json.dumps(row()) + "\n") * 2, encoding="utf-8")
            with self.assertRaisesRegex(SourceError, "duplicate typed"):
                run_preflight(source, root / "out.jsonl", root / "summary.json")


if __name__ == "__main__":
    unittest.main()
