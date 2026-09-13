"""Tiny fixtures test composition; the injected strict gate has its own oracle tests."""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import test_grow_preview as preview_fixtures
from grow_preview import MULTI_KIND, PreviewEvidenceError, validate_multi_water_preview


class MultiWaterPreviewTests(unittest.TestCase):
    def setUp(self):
        self.f = preview_fixtures.PreviewTests()
        self.f.setUp()
        self.addCleanup(self.f.doCleanups)
        self.f.evidence.update(component="water", runSource="inland-water",
                               emittedFeatureIds=["water/1"], unmappedSourceCount=None,
                               sourceCoverageComplete=False)
        self.f.freeze()
        self.f.evidence.update(sourceReportPath=self.f.evidence["reportPath"],
                               sourceReportSha256=self.f.evidence["reportSha256"])
        inland_entry = self.f.freeze()
        self.inland = copy.deepcopy(self.f.evidence)
        self.inland_pin = {"path": inland_entry["evidence_path"],
                           "sha256": inland_entry["evidence_sha256"]}
        coast_source = self.f.write("coast-source.json", {"coast": "fixture"})
        coast_runs = self.f.write("coast.runs.jsonl", {"layer": 30, "block": "minecraft:water"})
        coast_report = self.f.write("coast-report.json", {"schema": "fork.coast-surface.v1"})
        self.coast = {"component": "water", "status": "PASS", "synthetic": False,
            "coreBounds": self.f.core, "writerManifestSha256": self.f.writer_hash,
            "sourceSha256": coast_source["sha256"], "runsPath": coast_runs["path"],
            "runsSha256": coast_runs["sha256"], "sourceReportPath": coast_report["path"],
            "sourceReportSha256": coast_report["sha256"], "featureCount": 1,
            "emittedFeatureIds": ["coast/1"], "blockedDiagnostics": 0,
            "unmappedSourceCount": 0, "maskEvidence": {"fixture": True}}
        self.coast_pin = self.f.write("coast-evidence.json", self.coast)
        self.f.inputs.append({"name": Path(coast_runs["path"]).name, "sha256": coast_runs["sha256"],
                              "bytes": Path(coast_runs["path"]).stat().st_size})
        self.aggregate = {"schemaVersion": 1, "kind": MULTI_KIND, "component": "water",
            "synthetic": False, "status": "RENDERED_SUBSET", "coreBounds": self.f.core,
            "writerManifestSha256": self.f.writer_hash,
            "requiredContributors": ["coast-water", "inland-water"],
            "sourceComplete": False, "routeComplete": False, "fullFidelity": False,
            "fullWorldAccepted": False, "sourceCoverageComplete": False,
            "featureCount": 2, "emittedFeatureCount": 2,
            "blockedDiagnostics": 1, "unmappedSourceCount": None,
            "omissions": copy.deepcopy(self.inland["omissions"])}
        self.calls = []

    def freeze(self):
        records = []
        for name, child, pin in (("coast-water", self.coast, self.coast_pin),
                                 ("inland-water", self.inland, self.inland_pin)):
            records.append({"id": name, "evidencePath": pin["path"], "evidenceSha256": pin["sha256"],
                **{key: child[key] for key in ("sourceSha256", "runsSha256", "sourceReportSha256",
                                                "status", "featureCount")}})
        source_set = self.f.write("source-set.json", {"schemaVersion": 1,
            "kind": "fork-component-source-set", "component": "water", "coreBounds": self.f.core,
            "writerManifestSha256": self.f.writer_hash, "contributors": records})
        self.aggregate.update(contributors=records, sourceSetPath=source_set["path"],
                              sourceSha256=source_set["sha256"],
            emittedFeatureIds=sorted([self.coast["sourceSha256"] + ":coast/1",
                                      self.inland["sourceSha256"] + ":water/1"]))
        pin = self.f.write("aggregate.json", self.aggregate)
        return {"status": "rendered_subset_preview", "evidence_path": pin["path"],
                "evidence_sha256": pin["sha256"]}

    def strict_coast(self, component, entry, core, writer, inputs):
        self.calls.append((component, entry, core, writer, inputs))
        self.assertEqual("water", component)
        self.assertEqual("included", entry["status"])
        self.assertEqual(self.coast_pin["sha256"], entry["evidence_sha256"])
        return {"status": "included", "source_sha256": self.coast["sourceSha256"],
                "feature_count": 1}

    def validate(self, entry=None, callback=None):
        return validate_multi_water_preview(entry or self.freeze(), self.f.core,
            self.f.writer_hash, self.f.inputs,
            coast_validator=self.strict_coast if callback is None else callback)

    def test_strict_coast_and_qualified_inland_compose(self):
        result = self.validate()
        self.assertEqual(1, len(self.calls))
        self.assertEqual("rendered_subset_preview", result["status"])
        self.assertEqual(2, result["feature_count"])
        self.assertEqual(self.inland["omissions"], result["omissions"])
        self.assertEqual(["included", "rendered_subset_preview"],
                         [child["status"] for child in result["contributors"]])
        self.assertIs(result["fullFidelity"], False)

    def test_failing_coast_oracle_propagates(self):
        def fail(*args):
            raise ValueError("actual independent coastline oracle rejected")
        with self.assertRaisesRegex(ValueError, "coastline oracle rejected"):
            self.validate(callback=fail)

    def test_no_coast_validator_rejected(self):
        entry = self.freeze()
        with self.assertRaisesRegex(PreviewEvidenceError, "strict coast"):
            validate_multi_water_preview(entry, self.f.core, self.f.writer_hash,
                                         self.f.inputs, coast_validator=None)

    def test_aggregate_cannot_be_promoted_to_pass(self):
        self.aggregate["status"] = "PASS"
        with self.assertRaises(PreviewEvidenceError):
            self.validate()

    def test_dropped_inland_warning_rejected(self):
        self.aggregate["omissions"].pop()
        with self.assertRaisesRegex(PreviewEvidenceError, "every inland"):
            self.validate()

    def test_duplicate_contributor_rejected(self):
        entry = self.freeze()
        self.aggregate["contributors"][1] = self.aggregate["contributors"][0]
        pin = self.f.write("aggregate.json", self.aggregate)
        entry["evidence_sha256"] = pin["sha256"]
        with self.assertRaisesRegex(PreviewEvidenceError, "duplicate water"):
            self.validate(entry)

    def test_changed_child_and_source_set_rejected(self):
        entry = self.freeze()
        Path(self.coast_pin["path"]).write_text("{}")
        with self.assertRaisesRegex(PreviewEvidenceError, "SHA256 mismatch"):
            self.validate(entry)

    def test_coast_source_cannot_be_replaced_with_roads_report(self):
        report = self.f.write("coast-report.json", {"schema": "fork.roads-runs.v1"})
        self.coast["sourceReportSha256"] = report["sha256"]
        self.coast_pin = self.f.write("coast-evidence.json", self.coast)
        with self.assertRaisesRegex(PreviewEvidenceError, "Wrong actual source type"):
            self.validate()

    def test_inland_cannot_hide_as_pass(self):
        self.inland["status"] = "PASS"
        self.inland_pin = self.f.write("evidence.json", self.inland)
        with self.assertRaisesRegex(PreviewEvidenceError, "explicit rendered subset"):
            self.validate()

    def test_false_completeness_and_actual_writer_input_required(self):
        self.aggregate["sourceCoverageComplete"] = True
        with self.assertRaisesRegex(PreviewEvidenceError, "complete coverage"):
            self.validate()
        self.aggregate["sourceCoverageComplete"] = False
        self.f.inputs.pop()
        with self.assertRaisesRegex(PreviewEvidenceError, "not consumed"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
