"""Small SYNTHETIC fixtures only. Leaves evidence directories; never deletes worlds."""
import gzip
import importlib.util
import json
from pathlib import Path
import struct
import sys
import unittest
import uuid
from unittest import mock

MODULE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MODULE_DIR))
import package


def minimal_level(player=False):
    def name(value):
        encoded = value.encode()
        return struct.pack(">H", len(encoded)) + encoded
    content = b"\x0a\x00\x00\x0a" + name("Data")
    content += b"\x03" + name("SpawnX") + struct.pack(">i", 0)
    if player:
        content += b"\x0a" + name("Player") + b"\x00"
    return gzip.compress(content + b"\x00\x00")


class PackageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.evidence = package.OUTPUT_ROOT / ("synthetic-package-tests-" + uuid.uuid4().hex)
        cls.evidence.mkdir(parents=True, exist_ok=False)
        print("SYNTHETIC TEST EVIDENCE ONLY: " + str(cls.evidence))

    def setUp(self):
        self.root = self.evidence / self._testMethodName
        self.root.mkdir()
        self.tile = self.root / "tile"
        self.tile.mkdir()
        (self.tile / "input.mca").write_bytes(b"SYNTHETIC INPUT")
        self.seam = self.root / "seam.json"
        self.seam.write_text(json.dumps({"status": "PASS", "synthetic": True}))
        self.manifest = self.root / "manifest.json"
        self.world = self.root / "new-world"

    def initialize(self, **overrides):
        args = dict(manifest_path=self.manifest, output_world=self.world,
                    tile_inputs=[{"id": "t0", "path": str(self.tile), "core_bounds": [0, 0, 16, 16],
                                  "transform": {"deltaChunks": [0, 0]}}],
                    coordinate_contract={"x": "SVY21 E", "z": "60000-N", "metresPerBlock": 1},
                    seam_gate={"status": "PASS", "evidence_path": str(self.seam), "synthetic": True},
                    synthetic=True)
        args.update(overrides)
        return package.initialize_manifest(**args)

    def write_world(self, player=False):
        (self.world / "region").mkdir()
        (self.world / "region" / "r.0.0.mca").write_bytes(b"SYNTHETIC REGION: NOT A RUNTIME WORLD")
        (self.world / "level.dat").write_bytes(minimal_level(player))

    def test_checkpoint_resume_seal_synthetic_stays_non_deliverable(self):
        initial = self.initialize()
        self.assertEqual("IN_PROGRESS", initial["state"])
        self.write_world()
        checkpoint = package.checkpoint_manifest(self.manifest)
        self.assertEqual(2, len(checkpoint["outputs"]))
        verified = package.verify_manifest(self.manifest)
        self.assertEqual(checkpoint, verified)
        sealed = package.seal_manifest(self.manifest)
        self.assertEqual("SEALED", sealed["state"])
        self.assertFalse(sealed["package_ready"])
        self.assertFalse(sealed["install_ready"])
        self.assertEqual("PENDING", sealed["runtime_load_gate"]["status"])
        self.assertIn("SYNTHETIC", sealed["evidence_scope"])
        self.assertTrue(sealed["clean_world_gate"]["ok"])
        with self.assertRaises(package.GateError):
            package.checkpoint_manifest(self.manifest)

    def test_changed_input_cannot_resume(self):
        self.initialize()
        (self.tile / "input.mca").write_bytes(b"changed")
        with self.assertRaisesRegex(package.GateError, "Input tile changed"):
            package.verify_manifest(self.manifest)

    def test_added_input_cannot_resume(self):
        self.initialize()
        (self.tile / "extra.dat").write_bytes(b"new")
        with self.assertRaisesRegex(package.GateError, "Input tile changed"):
            package.checkpoint_manifest(self.manifest)

    def test_changed_checkpointed_output_cannot_resume_or_checkpoint(self):
        self.initialize()
        self.write_world()
        package.checkpoint_manifest(self.manifest)
        (self.world / "region" / "r.0.0.mca").write_bytes(b"changed output")
        for action in (package.verify_manifest, package.checkpoint_manifest, package.seal_manifest):
            with self.assertRaisesRegex(package.GateError, "Checkpointed output"):
                action(self.manifest)

    def test_uncheckpointed_output_never_reused(self):
        self.initialize()
        self.write_world()
        with self.assertRaisesRegex(package.GateError, "Uncheckpointed output"):
            package.verify_manifest(self.manifest)
        package.checkpoint_manifest(self.manifest, ["level.dat"])
        with self.assertRaises(package.GateError):
            package.verify_manifest(self.manifest)
        package.checkpoint_manifest(self.manifest, ["region/r.0.0.mca"])
        package.verify_manifest(self.manifest)

    def test_missing_checkpointed_output_rejected(self):
        self.initialize()
        self.write_world()
        package.checkpoint_manifest(self.manifest)
        # No deletion: move the file within the brand-new synthetic fixture only.
        (self.world / "level.dat").rename(self.world / "level.dat.moved")
        with self.assertRaisesRegex(package.GateError, "Checkpointed output"):
            package.verify_manifest(self.manifest)

    def test_no_existing_world_adoption(self):
        self.world.mkdir()
        marker = self.world / "preserve.txt"
        marker.write_text("existing")
        with self.assertRaisesRegex(package.GateError, "Output already exists"):
            self.initialize()
        self.assertEqual("existing", marker.read_text())

    def test_seam_fail_and_unknown_prevent_creation(self):
        for status in ("FAIL", "UNKNOWN", "pass", None):
            with self.assertRaisesRegex(package.GateError, "seam gate must be PASS"):
                self.initialize(seam_gate={"status": status, "evidence_path": str(self.seam)})
        self.assertFalse(self.world.exists())

    def test_changed_seam_evidence_prevents_resume(self):
        self.initialize()
        self.seam.write_text('{"status":"FAIL"}')
        with self.assertRaisesRegex(package.GateError, "Seam evidence hash mismatch"):
            package.verify_manifest(self.manifest)

    def test_synthetic_evidence_cannot_certify_real_world(self):
        with self.assertRaisesRegex(package.GateError, "Synthetic seam"):
            self.initialize(synthetic=False)

    def test_claimed_pass_cannot_override_failed_seam_report(self):
        self.seam.write_text(json.dumps({"status": "FAIL", "synthetic": True}))
        with self.assertRaisesRegex(package.GateError, "Synthetic receipt"):
            self.initialize()

    def test_manifest_tamper_rejected(self):
        self.initialize()
        doc = json.loads(self.manifest.read_text())
        doc["coordinate_contract"]["metresPerBlock"] = 4
        self.manifest.write_text(json.dumps(doc))
        with self.assertRaisesRegex(package.GateError, "integrity hash"):
            package.verify_manifest(self.manifest)

    def test_failed_atomic_replace_preserves_previous_receipt(self):
        self.initialize()
        before = self.manifest.read_bytes()
        self.write_world()
        with mock.patch.object(package.os, "replace", side_effect=OSError("interrupted")):
            with self.assertRaises(OSError):
                package.checkpoint_manifest(self.manifest)
        self.assertEqual(before, self.manifest.read_bytes())
        self.assertEqual(1, len(list(self.root.glob("manifest.json.*.tmp"))))
        with self.assertRaises(package.GateError):
            package.verify_manifest(self.manifest)

    def test_forbidden_empty_dirs_and_files_fail_closed(self):
        self.initialize()
        self.write_world()
        for name in ("players", "playerdata", "entities", "fork", "credentials"):
            (self.world / name).mkdir()
        for name in ("session.lock", ".env", "auth.json", "private.pem"):
            (self.world / name).write_bytes(b"synthetic")
        report = package.validate_clean_world(self.world)
        self.assertFalse(report["ok"])
        self.assertEqual(9, len(report["issues"]))
        package.checkpoint_manifest(self.manifest)
        with self.assertRaisesRegex(package.GateError, "Clean world gate failed"):
            package.seal_manifest(self.manifest)

    def test_embedded_player_rejected_in_level_and_old(self):
        self.initialize()
        self.write_world(player=True)
        (self.world / "level.dat_old").write_bytes(minimal_level(True))
        report = package.validate_clean_world(self.world)
        self.assertFalse(report["ok"])
        self.assertEqual(["Embedded Player tag: level.dat", "Embedded Player tag: level.dat_old"], report["issues"])

    def test_malformed_level_fails_closed(self):
        self.initialize()
        self.write_world()
        (self.world / "level.dat").write_bytes(b"bad NBT")
        report = package.validate_clean_world(self.world)
        self.assertFalse(report["ok"])
        self.assertTrue(report["issues"][0].startswith("Cannot validate level NBT"))

    def test_outside_fixed_output_root_rejected(self):
        with self.assertRaisesRegex(package.GateError, "New output must be below"):
            self.initialize(output_world=package.OUTPUT_ROOT.parent / ("outside-" + uuid.uuid4().hex))

    def test_dotdot_cannot_escape_output_root(self):
        with self.assertRaisesRegex(package.GateError, "New output must be below"):
            self.initialize(output_world=package.OUTPUT_ROOT / ".." / ("outside-" + uuid.uuid4().hex))

    def test_real_failed_report_cannot_be_promoted_by_pass_wrapper(self):
        self.seam.write_text(json.dumps({"kind": "actual-shared-halo-block-comparison",
                                        "status": "FAIL", "left": "t0", "right": "t1",
                                        "compared": 10, "mismatches": 1, "assemblyAccepted": False}))
        with self.assertRaisesRegex(package.GateError, "Structural seam evidence did not pass"):
            self.initialize(seam_gate={"status": "PASS", "evidence_path": str(self.seam)}, synthetic=False)
        self.assertFalse(self.world.exists())

    def test_invalid_and_duplicate_core_inputs_rejected(self):
        item = {"id": "t0", "path": str(self.tile), "core_bounds": [0, 0, 16, 16]}
        with self.assertRaisesRegex(package.GateError, "unique"):
            self.initialize(tile_inputs=[item, item])
        for bounds in ([0, 0, 0, 16], [0, 0, 16.0, 16], [0, 1, 2], [False, 0, 16, 16]):
            with self.assertRaisesRegex(package.GateError, "core_bounds"):
                self.initialize(tile_inputs=[{**item, "core_bounds": bounds}])

    def test_sealed_world_does_not_accept_new_files(self):
        self.initialize()
        self.write_world()
        package.checkpoint_manifest(self.manifest)
        package.seal_manifest(self.manifest)
        (self.world / "session.lock").write_bytes(b"synthetic")
        with self.assertRaisesRegex(package.GateError, "Uncheckpointed output"):
            package.verify_manifest(self.manifest, allow_new_outputs=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
