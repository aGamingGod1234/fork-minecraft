"""Empty output is a qualified renderer fact, never source-water absence."""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import test_grow_preview as preview_fixtures
import test_grow_preview_multi_water as multi_fixtures
from grow_preview import PreviewEvidenceError, validate_preview


def empty_fixture(f):
    runs = f.write("roads.runs.jsonl", {"x": 1, "z": 1, "layer": 40})
    f.report["outputSha256"] = runs["sha256"]
    f.evidence.update(component="water", status="EMPTY_RENDERED_SUBSET", runSource="inland-water",
        featureCount=0, emittedFeatureCount=0, emittedFeatureIds=[], runsSha256=runs["sha256"],
        emptyInlandSubset=True, sourceWaterAbsenceProven=False, sourceCoverageComplete=False)
    f.inputs[0].update(sha256=runs["sha256"], bytes=Path(runs["path"]).stat().st_size)
    f.freeze()
    f.evidence.update(sourceReportPath=f.evidence["reportPath"],
                      sourceReportSha256=f.evidence["reportSha256"])
    return {"schemaVersion": 1, "kind": "fork-consumed-component-run-scan", "status": "PASS",
        "synthetic": False, "coreBounds": f.core, "writerManifestSha256": f.writer_hash,
        "runsPath": runs["path"], "runsSha256": runs["sha256"],
        "runsBytes": f.inputs[0]["bytes"], "sourceSha256": f.evidence["sourceSha256"],
        "sourceReportSha256": f.evidence["reportSha256"],
        "allRuns": 1, "coreRuns": 1, "layers": {"40": 1},
        "inlandWater": {"emittedFeatureIds": [], "componentRuns": 0, "componentVoxels": 0},
        "sourceComplete": False, "fullWorldAccepted": False}


def bind_scan(f, scan):
    pin = f.write("empty-scan.json", scan)
    f.evidence.update(emptyScanPath=pin["path"], emptyScanSha256=pin["sha256"])
    return f.freeze()


class EmptyInlandTests(unittest.TestCase):
    def setUp(self):
        self.f = preview_fixtures.PreviewTests()
        self.f.setUp()
        self.addCleanup(self.f.doCleanups)
        self.scan = empty_fixture(self.f)

    def validate(self):
        return validate_preview("water", bind_scan(self.f, self.scan),
                                self.f.core, self.f.writer_hash, self.f.inputs)

    def replace_run(self, run):
        pin = self.f.write("roads.runs.jsonl", run)
        size = Path(pin["path"]).stat().st_size
        self.f.report["outputSha256"] = pin["sha256"]
        self.f.evidence["runsSha256"] = pin["sha256"]
        self.f.inputs[0].update(sha256=pin["sha256"], bytes=size)
        self.f.freeze()
        self.f.evidence["sourceReportSha256"] = self.f.evidence["reportSha256"]
        self.scan.update(runsSha256=pin["sha256"], runsBytes=size,
                         sourceReportSha256=self.f.evidence["reportSha256"])

    def test_full_empty_scan_is_qualified_not_absence(self):
        result = self.validate()
        self.assertEqual("rendered_subset_preview", result["status"])
        self.assertIs(result["emptyInlandSubset"], True)
        self.assertIs(result["sourceWaterAbsenceProven"], False)
        self.assertIs(result["waterAccepted"], False)
        self.assertEqual(0, result["feature_count"])
        self.assertEqual(self.f.audit["omissions"], result["omissions"])

    def test_missing_scan_is_rejected(self):
        entry = self.f.freeze()
        with self.assertRaises(PreviewEvidenceError):
            validate_preview("water", entry, self.f.core, self.f.writer_hash, self.f.inputs)

    def test_partial_scan_is_rejected(self):
        self.scan["coreRuns"] = 0
        with self.assertRaisesRegex(PreviewEvidenceError, "complete core-clipped"):
            self.validate()

    def test_writer_source_and_byte_mismatch_rejected(self):
        for field in ("writerManifestSha256", "sourceSha256", "runsSha256"):
            original = self.scan[field]
            self.scan[field] = "f" * 64
            with self.assertRaisesRegex(PreviewEvidenceError, "binding mismatch"):
                self.validate()
            self.scan[field] = original
        self.scan["runsBytes"] -= 1
        with self.assertRaisesRegex(PreviewEvidenceError, "all consumed bytes"):
            self.validate()

    def test_actual_layer30_rejected_even_if_scan_denies_it(self):
        self.replace_run({"x": 1, "z": 1, "layer": 30})
        with self.assertRaisesRegex(PreviewEvidenceError, "actually emits"):
            self.validate()

    def test_actual_halo_run_rejected_even_if_scan_claims_core(self):
        self.replace_run({"x": 17, "z": 1, "layer": 40})
        with self.assertRaisesRegex(PreviewEvidenceError, "not entirely core-clipped"):
            self.validate()

    def test_actual_layer_recount_must_equal_scan(self):
        self.scan["layers"] = {"20": 1}
        with self.assertRaisesRegex(PreviewEvidenceError, "recount contradicts"):
            self.validate()

    def test_absence_or_completeness_promotion_rejected(self):
        self.f.evidence["sourceWaterAbsenceProven"] = True
        with self.assertRaises(PreviewEvidenceError):
            self.validate()
        self.f.evidence["sourceWaterAbsenceProven"] = False
        self.f.evidence["sourceCoverageComplete"] = True
        with self.assertRaises(PreviewEvidenceError):
            self.validate()

    def test_fatal_reference_failure_stays_fatal(self):
        self.f.audit["integrity"]["referenceComplete"] = False
        with self.assertRaisesRegex(PreviewEvidenceError, "integrity is unproven"):
            self.validate()

    def test_multiwater_exposes_empty_inland_and_keeps_strict_coast(self):
        m = multi_fixtures.MultiWaterPreviewTests()
        m.setUp()
        self.addCleanup(m.doCleanups)
        scan = empty_fixture(m.f)
        entry = bind_scan(m.f, scan)
        m.inland = copy.deepcopy(m.f.evidence)
        m.inland_pin = {"path": entry["evidence_path"], "sha256": entry["evidence_sha256"]}
        m.aggregate.update(featureCount=1, emittedFeatureCount=1,
                           emptyInlandSubset=True, sourceWaterAbsenceProven=False)
        aggregate_entry = m.freeze()
        m.aggregate["emittedFeatureIds"] = [m.coast["sourceSha256"] + ":coast/1"]
        pin = m.f.write("aggregate.json", m.aggregate)
        aggregate_entry["evidence_sha256"] = pin["sha256"]
        result = m.validate(aggregate_entry)
        self.assertEqual(1, len(m.calls))
        self.assertIs(result["emptyInlandSubset"], True)
        self.assertIs(result["sourceWaterAbsenceProven"], False)
        self.assertEqual(1, result["feature_count"])
        self.assertEqual("included", result["contributors"][0]["status"])


if __name__ == "__main__":
    unittest.main()
