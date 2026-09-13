"""Approved policy bytes are embedded for portable, exact-hash regression tests."""
import base64
import copy
import hashlib
from pathlib import Path
import sys
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import test_grow_preview as preview_fixtures
from grow_preview import PROPOSED_POLICY_SHA256, PreviewEvidenceError, validate_preview

POLICY_ZLIB_B64 = "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"


class ProposedRoadTests(unittest.TestCase):
    def setUp(self):
        self.f = preview_fixtures.PreviewTests()
        self.f.setUp()
        self.addCleanup(self.f.doCleanups)
        policy = self.f.root / "approved-policy-v2.json"
        data = zlib.decompress(base64.b64decode(POLICY_ZLIB_B64))
        self.assertEqual(PROPOSED_POLICY_SHA256, hashlib.sha256(data).hexdigest())
        policy.write_bytes(data)
        self.f.bindings["nationalPolicy"] = {"path": str(policy), "sha256": PROPOSED_POLICY_SHA256}
        self.f.audit["omissions"] = []
        self.f.report["diagnostics"] = []
        for index, source_id in enumerate((1287188677, 1287188678, 1287188680, 1287188681)):
            diagnostic = {"code": "road_raster_blocked", "featureId": "way/" + str(source_id),
                          "detail": "unsupported highway type: 'proposed'"}
            self.f.report["diagnostics"].append(diagnostic)
            self.f.audit["omissions"].append({"featureId": diagnostic["featureId"],
                "diagnosticCode": diagnostic["code"], "originalDiagnostic": diagnostic,
                "sourceSha256": self.f.bindings["source"]["sha256"], "sourceReferencesComplete": True,
                "reason": "unsupported_highway_proposed", "classification": "valid_source_capability_omission",
                "scope": "core" if index < 2 else "lateral-impact-unresolved",
                "scopeMethod": "source_geometry_intersects_core" if index < 2 else "unknown lateral impact",
                "blocked": True, "layerDomain": "road", "previewExclusionEligible": True,
                "tags": {"highway": "proposed", "proposed": "tertiary"},
                "sourceStatus": "proposed_not_existing", "existingRoad": False, "emitGeometry": False,
                "geometryValidity": {"valid": True, "reason": "Valid Geometry"},
                "geometryBoundsXZ": [1, 2, 4, 5] if index < 2 else [18, 2, 24, 5]})
        self.f.report["blockedDiagnostics"] = 4
        self.f.audit["counts"]["blocked"] = 4
        self.f.evidence["blockedDiagnostics"] = 4

    def validate(self):
        return validate_preview("roads", self.f.freeze(), self.f.core,
                                self.f.writer_hash, self.f.inputs)

    def test_four_planned_ways_preserve_two_core_two_unknown(self):
        result = self.validate()
        self.assertEqual(4, len(result["omissions"]))
        self.assertEqual(2, len(result["core_road_omissions"]))
        self.assertEqual(2, len(result["potential_core_road_omissions"]))
        for omission in result["omissions"]:
            self.assertIs(omission["existingRoad"], False)
            self.assertIs(omission["emitGeometry"], False)
        self.assertIs(result["sourceComplete"], False)

    def test_v1_or_unapproved_policy_hash_rejected(self):
        self.f.bindings["nationalPolicy"]["sha256"] = "b2de7df7459b7eb855eca520065e272dc1d7021f19be98c273a41f642b6e5846"
        with self.assertRaisesRegex(PreviewEvidenceError, "exact approved national policy v2"):
            self.validate()

    def test_changed_policy_bytes_rejected(self):
        Path(self.f.bindings["nationalPolicy"]["path"]).write_text("{}")
        with self.assertRaisesRegex(PreviewEvidenceError, "SHA256 mismatch"):
            self.validate()

    def test_tag_or_original_error_cannot_claim_an_existing_street(self):
        self.f.audit["omissions"][0]["tags"]["highway"] = "tertiary"
        with self.assertRaisesRegex(PreviewEvidenceError, "planned status"):
            self.validate()
        self.f.audit["omissions"][0]["tags"]["highway"] = "proposed"
        self.f.report["diagnostics"][0]["detail"] = "unsupported highway type: 'construction'"
        with self.assertRaisesRegex(PreviewEvidenceError, "planned status"):
            self.validate()

    def test_planned_and_emission_flags_are_literal(self):
        first = self.f.audit["omissions"][0]
        for key in ("existingRoad", "emitGeometry"):
            first[key] = 0
            with self.assertRaisesRegex(PreviewEvidenceError, "planned status"):
                self.validate()
            first[key] = False
        first["sourceStatus"] = "existing"
        with self.assertRaisesRegex(PreviewEvidenceError, "planned status"):
            self.validate()

    def test_missing_references_invalid_or_nonfinite_geometry_rejected(self):
        first = self.f.audit["omissions"][0]
        first["sourceReferencesComplete"] = False
        with self.assertRaises(PreviewEvidenceError):
            self.validate()
        first["sourceReferencesComplete"] = True
        first["geometryValidity"]["valid"] = False
        with self.assertRaisesRegex(PreviewEvidenceError, "complete valid finite geometry"):
            self.validate()
        first["geometryValidity"]["valid"] = True
        first["geometryBoundsXZ"][0] = float("nan")
        with self.assertRaisesRegex(PreviewEvidenceError, "complete valid finite geometry"):
            self.validate()

    def test_other_unsupported_reasons_remain_rejected(self):
        self.f.audit["omissions"][0]["reason"] = "unsupported_highway_planned"
        with self.assertRaisesRegex(PreviewEvidenceError, "Unknown omission reason"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
