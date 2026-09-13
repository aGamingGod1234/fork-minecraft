import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from grow_preview import FRAME, KIND, PreviewEvidenceError, validate_preview


class PreviewTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.core = [0, 0, 16, 16]
        self.writer_hash = "a" * 64
        self.bindings = {}
        for name in ("source", "projectedNodes", "countryMask", "foreignExclusions"):
            self.bindings[name] = self.write(name + ".json", {"name": name})
        self.runs = self.write("roads.runs.jsonl", {"x": 1, "z": 1, "yMin": 0,
                              "yMax": 1, "block": "minecraft:water", "layer": 30})
        self.report = {"schema": "fork.roads-runs.v1", "grid": FRAME,
            "tile": [-16, -16, 32, 32], "countryMaskCreatesLand": False,
            "wholeSourceGeometryPreserved": True, "outputSha256": self.runs["sha256"],
            "runCount": 1, "blockedDiagnostics": 1,
            "diagnostics": [{"code": "road_raster_blocked", "featureId": "way/1",
                             "detail": "unsupported highway type: corridor"},
                            {"code": "unsupported_surface", "source_id": "way/2"},
                            {"code": "road_overlap_resolved", "featureId": "way/3"}]}
        for key, reportkey in (("source", "sourceSha256"), ("projectedNodes", "projectedNodesSha256"),
                              ("countryMask", "countryMaskSha256"), ("foreignExclusions", "foreignExclusionsSha256")):
            self.report[reportkey] = self.bindings[key]["sha256"]
        self.audit = {"schema": "fork.road-omission-audit.v1", "core": self.core,
            "render": [-16, -16, 32, 32], "bindings": self.bindings,
            "integrity": {key: True for key in ("sourceHashMatches", "projectionHashMatches",
                "referenceComplete", "projectedReferenceCoverageComplete", "coordinateContractValid",
                "countryMaskValid", "foreignExclusionsValid")},
            "gates": {"sourceComplete": False, "routeComplete": False, "fullFidelity": False},
            "counts": {"blocked": 1}, "omissions": []}
        self.audit["integrity"].update(fatalErrors=[], missingReferences=[], missingProjectedNodes=[])
        for index, diagnostic in enumerate(self.report["diagnostics"][:2]):
            self.audit["omissions"].append({"featureId": diagnostic.get("featureId", diagnostic.get("source_id")),
                "diagnosticCode": diagnostic["code"], "originalDiagnostic": diagnostic,
                "sourceSha256": self.bindings["source"]["sha256"], "sourceReferencesComplete": True,
                "reason": "unsupported_highway_corridor" if not index else "unsupported_mapped_surface",
                "classification": "source_omission" if not index else "surface_warning",
                "scope": "lateral-impact-unresolved" if not index else "core",
                "scopeMethod": "explicit unknown width" if not index else "geometry intersection",
                "blocked": not bool(index), "layerDomain": "road" if not index else "landcover",
                "previewExclusionEligible": not bool(index)})
        self.evidence = {"kind": KIND, "status": "RENDERED_SUBSET", "component": "roads",
            "synthetic": False, "coreBounds": self.core, "writerManifestSha256": self.writer_hash,
            "sourceSha256": self.bindings["source"]["sha256"], "featureCount": 1,
            "runsPath": self.runs["path"], "runsSha256": self.runs["sha256"],
            "sourceComplete": False, "routeComplete": False, "fullFidelity": False,
            "fullWorldAccepted": False, "blockedDiagnostics": 1}
        self.inputs = [{"name": Path(self.runs["path"]).name, "sha256": self.runs["sha256"],
                        "bytes": Path(self.runs["path"]).stat().st_size}]

    def write(self, name, obj):
        path = self.root / name
        data = (json.dumps(obj, sort_keys=True) + "\n").encode()
        path.write_bytes(data)
        return {"path": str(path), "sha256": hashlib.sha256(data).hexdigest()}

    def freeze(self):
        report = self.write("report.json", self.report)
        self.bindings["roadsManifest"] = report
        classification = self.write("classification.json", self.audit)
        self.evidence.update(reportPath=report["path"], reportSha256=report["sha256"],
            classificationPath=classification["path"], classificationSha256=classification["sha256"],
            omissions=copy.deepcopy(self.audit["omissions"]))
        evidence = self.write("evidence.json", self.evidence)
        return {"status": "rendered_subset_preview", "evidence_path": evidence["path"],
                "evidence_sha256": evidence["sha256"]}

    def validate(self, entry=None, component=None):
        return validate_preview(component or self.evidence["component"], entry or self.freeze(),
                                self.core, self.writer_hash, self.inputs)

    def test_preserves_unresolved_and_ancillary_warnings(self):
        result = self.validate()
        self.assertEqual(2, len(result["omissions"]))
        self.assertEqual(1, len(result["potential_core_road_omissions"]))
        self.assertEqual([], result["core_road_omissions"])
        for key in ("sourceComplete", "routeComplete", "fullFidelity", "waterAccepted"):
            self.assertIs(result[key], False)

    def test_inland_water_explicit_type_and_actual_layer(self):
        self.evidence.update(component="water", runSource="inland-water")
        self.assertEqual("water", self.validate()["component"])
        self.evidence["runSource"] = "coast-water"
        with self.assertRaises(PreviewEvidenceError):
            self.validate()

    def test_no_layer30_water_rejected(self):
        changed = self.write("roads.runs.jsonl", {"layer": 40})
        self.evidence["runsSha256"] = self.report["outputSha256"] = changed["sha256"]
        self.evidence.update(component="water", runSource="inland-water")
        with self.assertRaisesRegex(PreviewEvidenceError, "layer-30"):
            self.validate()

    def test_dropped_duplicate_and_substituted_diagnostics(self):
        original = copy.deepcopy(self.audit["omissions"])
        for values in (original[:1], original + original[:1]):
            self.audit["omissions"] = values
            with self.assertRaisesRegex(PreviewEvidenceError, "dropped, duplicated"):
                self.validate()
        self.audit["omissions"] = original
        self.audit["omissions"][0]["originalDiagnostic"] = dict(original[0]["originalDiagnostic"], detail="changed")
        with self.assertRaisesRegex(PreviewEvidenceError, "dropped, duplicated"):
            self.validate()

    def test_wrapper_cannot_omit_warning(self):
        entry = self.freeze()
        self.evidence["omissions"].pop()
        pin = self.write("evidence.json", self.evidence)
        entry["evidence_sha256"] = pin["sha256"]
        with self.assertRaisesRegex(PreviewEvidenceError, "complete exact"):
            self.validate(entry)

    def test_unknown_diagnostic_cannot_be_waived(self):
        self.report["diagnostics"].append({"code": "missing_references", "featureId": "way/4"})
        with self.assertRaisesRegex(PreviewEvidenceError, "Unclassified"):
            self.validate()

    def test_integrity_and_literal_flags(self):
        for key in ("referenceComplete", "coordinateContractValid", "countryMaskValid"):
            self.audit["integrity"][key] = 1
            with self.assertRaises(PreviewEvidenceError):
                self.validate()
            self.audit["integrity"][key] = True
        self.evidence["sourceComplete"] = 0
        with self.assertRaises(PreviewEvidenceError):
            self.validate()

    def test_fatal_errors_are_not_preview_omissions(self):
        self.audit["integrity"]["fatalErrors"] = ["corrupt source"]
        with self.assertRaisesRegex(PreviewEvidenceError, "Fatal source"):
            self.validate()

    def test_hash_core_and_writer_consumption_binding(self):
        entry = self.freeze()
        Path(self.bindings["source"]["path"]).write_text("changed")
        with self.assertRaisesRegex(PreviewEvidenceError, "SHA256 mismatch"):
            self.validate(entry)
        self.setUp()
        self.audit["core"] = [16, 0, 32, 16]
        with self.assertRaisesRegex(PreviewEvidenceError, "core mismatch"):
            self.validate()
        self.audit["core"] = self.core
        self.inputs = []
        with self.assertRaisesRegex(PreviewEvidenceError, "not consumed"):
            self.validate()

    def test_explicit_outside_topology_quarantine_only(self):
        quarantine = {"featureId": "relation/1", "sourceSha256": self.bindings["source"]["sha256"],
            "bounds": [100, 100, 120, 120],
            "reason": "malformed_individual_geometry_proved_outside_render_and_unsupported",
            "sourceGraphCorruptionWaived": False, "referenceComplete": True,
            "finiteProjection": True, "neverRendered": True}
        self.audit["quarantinedSourceFeatures"] = [quarantine]
        self.evidence["quarantinedSourceFeatures"] = [quarantine]
        self.audit["globalSourceGeometryComplete"] = False
        self.evidence["globalSourceGeometryComplete"] = False
        self.assertEqual([quarantine], self.validate()["quarantinedSourceFeatures"])
        quarantine["bounds"] = [0, 0, 1, 1]
        with self.assertRaisesRegex(PreviewEvidenceError, "intersect"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
