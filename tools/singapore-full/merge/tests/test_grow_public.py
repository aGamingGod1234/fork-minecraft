"""Tiny synthetic ZIP fixtures only. No actual world package or runtime launch."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import unittest
import uuid
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import grow_public
import package


class PublicPackageTests(unittest.TestCase):
    def setUp(self):
        self.root = package.OUTPUT_ROOT / ("synthetic-public-" + uuid.uuid4().hex[:10])
        self.root.mkdir()
        self.world = self.root / "synthetic-world"
        self.payload = {"level.dat": b"SYNTHETIC level", "data/minecraft/world_gen_settings.dat": b"SYNTHETIC settings",
                        "dimensions/minecraft/overworld/region/r.-1.0.mca": b"SYNTHETIC region"}
        self.readme = b"SYNTHETIC ORIGINAL README\r\nKeep these exact bytes.\r\n"
        self.files = {p: {"bytes": len(b), "sha256": self.sha(b)} for p, b in self.payload.items()}
        manifest = {"files": self.files, "sha256": self.sha(grow_public._canonical(self.files))}
        self.plan = {"schema": "fork.grown-runtime-plan.v1", "candidate_path": str(self.world), "candidate_before": manifest}
        self.plan["plan_sha256"] = self.sha(grow_public._canonical(self.plan))
        self.grow = {"kind": "exact-core-grown-world", "assemblyAccepted": True, "sourceSubsetPreviewAccepted": True,
                     "sourceComplete": False, "routeComplete": False, "fullFidelity": False, "world": str(self.world),
                     "expectedChunks": 1, "extent": [-16, 0, 0, 16], "verification": {"status": "PASS", "hash_manifest": manifest}}
        self.runtime = {"schema": "fork.grown-runtime-receipt.v1", "runtimeLoadAccepted": True, "candidateUnchanged": True,
                        "minecraft_version": "26.1.2", "candidate_path": str(self.world), "plan_sha256": self.plan["plan_sha256"],
                        "selected_chunk_count": 1, "block_sentinel_count": 3}
        self.meta = {"packageName": "SYNTHETIC-public-v1", "saveFolder": "FORK-SYNTHETIC-16", "worldName": "SYNTHETIC ONLY",
                     "sourceComplete": False, "routeComplete": False, "fullFidelity": False,
                     "sources": [{"filename": "SYNTHETIC.osm.pbf", "snapshotDate": "2026-09-13", "sha256": "a" * 64,
                                  "url": "https://download.geofabrik.de/asia/SYNTHETIC.osm.pbf", "licenseUrl": grow_public.ODBL}],
                     "omissions": [{"sourceId": "SYNTHETIC-way-1", "reason": "Synthetic missing source geometry", "count": 2,
                                    "affectsCore": True, "affectsHalo": False}],
                     "limitations": {"terrain": "Synthetic flat Y0", "heights": "Synthetic estimate", "facades": "Synthetic generic materials",
                                     "coverage": "Synthetic fixture; never a real preview"}, "gateEvidence": {}}
        self.save_gates()
        self.zip = self.root / "synthetic-input.zip"
        self.write_zip(self.zip)

    @staticmethod
    def sha(value):
        return hashlib.sha256(value).hexdigest()

    def save_gates(self):
        for name in ("grow", "runtime", "plan"):
            target = self.root / (name + "-" + uuid.uuid4().hex[:5] + ".json")
            data = json.dumps(getattr(self, name), sort_keys=True).encode("utf-8")
            target.write_bytes(data)
            self.meta["gateEvidence"][name] = {"path": str(target), "sha256": self.sha(data)}

    def write_zip(self, target, extras=None, payload=None):
        with zipfile.ZipFile(target, "x") as archive:
            archive.writestr("README.txt", self.readme)
            for path, data in (payload or self.payload).items():
                archive.writestr("old-world/" + path, data)
            for path, data in (extras or {}).items():
                archive.writestr(path, data)

    def test_preserves_payload_readme_provenance_and_immutable_output(self):
        before = self.zip.read_bytes()
        output = self.root / "public-v1"
        receipt = grow_public.package_public(self.zip, self.meta, output)
        self.assertEqual("PASS", receipt["status"])
        self.assertEqual(before, self.zip.read_bytes())
        final = output / receipt["zip"]["path"]
        with zipfile.ZipFile(final) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(6, len(archive.namelist()))
            self.assertEqual(self.readme, archive.read("README.txt"))
            for path, data in self.payload.items():
                self.assertEqual(data, archive.read(self.meta["saveFolder"] + "/" + path))
            sources = archive.read("WORLD-SOURCES.txt").decode()
            install = archive.read("INSTALL-WORLD.txt").decode()
            for expected in (grow_public.COPYRIGHT, grow_public.ODBL, "a" * 64, "count=2; affectsCore=true; affectsHalo=false",
                             "sourceComplete=false", "1 selected chunks and 3 block sentinels"):
                self.assertIn(expected, sources)
            self.assertIn("FORK-SYNTHETIC-16", install)
            self.assertIn("Never merge or overwrite", install)
            self.assertNotIn(str(self.root), sources + install)
            self.assertNotIn("private-package-receipt.json", archive.namelist())
        frozen = final.read_bytes()
        with self.assertRaises(ValueError):
            grow_public.package_public(self.zip, self.meta, output)
        self.assertEqual(frozen, final.read_bytes())

    def test_rejects_missing_changed_or_mismatched_gates_before_output(self):
        cases = [lambda: self.grow.update(sourceSubsetPreviewAccepted=False),
                 lambda: self.runtime.update(candidateUnchanged=False),
                 lambda: self.runtime.update(candidate_path=str(self.root / "another-world")),
                 lambda: self.runtime.update(plan_sha256="f" * 64),
                 lambda: self.grow.update(fullFidelity=True)]
        for index, mutate in enumerate(cases):
            with self.subTest(case=index):
                originals = copy.deepcopy((self.grow, self.runtime, self.plan))
                mutate()
                self.save_gates()
                output = self.root / ("bad-gate-" + str(index))
                with self.assertRaises(ValueError):
                    grow_public.package_public(self.zip, self.meta, output)
                self.assertFalse(output.exists())
                self.grow, self.runtime, self.plan = originals
        self.save_gates()
        self.meta["gateEvidence"]["grow"]["sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            grow_public.package_public(self.zip, self.meta, self.root / "changed-gate")

    def test_rejects_payload_change_unlisted_member_and_private_metadata(self):
        bad_payload = dict(self.payload, **{"level.dat": b"CHANGED"})
        variants = [(bad_payload, {}), (None, {"old-world/playerdata/private.dat": b"private"}),
                    (None, {"../escape": b"bad"})]
        for index, (payload, extras) in enumerate(variants):
            with self.subTest(archive=index):
                source = self.root / ("bad-archive-" + str(index) + ".zip")
                self.write_zip(source, extras, payload)
                output = self.root / ("bad-payload-" + str(index))
                with self.assertRaises(ValueError):
                    grow_public.package_public(source, self.meta, output)
                self.assertFalse(output.exists())
        for index, alter in enumerate((lambda m: m["limitations"].update(terrain=r"C:\Users\Private\terrain.json"),
                                       lambda m: m["sources"][0].update(url="https://download.geofabrik.de/a?token=secret"),
                                       lambda m: m.update(sourceComplete=True))):
            with self.subTest(metadata=index):
                meta = copy.deepcopy(self.meta)
                alter(meta)
                with self.assertRaises(ValueError):
                    grow_public.package_public(self.zip, meta, self.root / ("bad-meta-" + str(index)))

    def test_preserves_individual_omission_records_and_unknown_impact(self):
        records = [dict(featureId="way/1", sourceSha256="b" * 64, reason="synthetic_outside", scope="outside_render",
                        classification="synthetic", geometryBoundsXZ=[-50, -50, -40, -40], tags={"name": "Synthetic Road"}),
                   dict(featureId="way/2", sourceSha256="b" * 64, reason="synthetic_unknown", scope="lateral-impact-unresolved",
                        classification="synthetic", geometryBoundsXZ=None)]
        self.meta["omissions"] = [dict(sourceId=r["featureId"], reason=r["reason"], count=1, scope=r["scope"],
                                       affectsCore=False if i == 0 else None, affectsHalo=False if i == 0 else None)
                                   for i, r in enumerate(records)]
        self.meta["omissionEvidence"] = {"records": records, "redactions": [{"featureId": "way/1", "field": "tags.email",
                                                                              "reason": "Public contact tag omitted"}]}
        output = self.root / "with-omissions"
        receipt = grow_public.package_public(self.zip, self.meta, output)
        with zipfile.ZipFile(output / receipt["zip"]["path"]) as archive:
            evidence = json.loads(archive.read("SOURCE-OMISSIONS.json"))
            self.assertEqual(records, evidence["records"])
            self.assertEqual(self.meta["omissionEvidence"]["redactions"], evidence["redactions"])
            sources = archive.read("WORLD-SOURCES.txt").decode()
            self.assertIn("affectsCore=false; affectsHalo=false; scope=outside_render", sources)
            self.assertIn("affectsCore=unknown; affectsHalo=unknown; scope=lateral-impact-unresolved", sources)
        self.meta["omissionEvidence"]["records"][0]["tags"]["email"] = "private@example.com"
        with self.assertRaises(ValueError):
            grow_public.package_public(self.zip, self.meta, self.root / "contact-leak")

    def test_preserves_duplicate_feature_occurrences_per_source_and_reason(self):
        self.meta["sources"][0]["coreBounds"] = [-16, 0, 0, 16]
        source_hash = self.meta["sources"][0]["sha256"]
        records = [dict(featureId="way/1", sourceSha256=source_hash, reason=reason, scope="core", classification="synthetic")
                   for reason in ("first_reason", "second_reason")]
        self.meta["omissions"] = [dict(sourceId="way/1", sourceSha256=source_hash, occurrenceIndex=i, reason=r["reason"],
                                       count=1, scope="core", affectsCore=True, affectsHalo=None) for i, r in enumerate(records)]
        self.meta["omissionEvidence"] = {"records": records, "redactions": [dict(featureId="way/1", sourceSha256=source_hash,
            occurrenceIndex=1, field="tags.email", reason="Public contact tag omitted")]}
        output = self.root / "occurrences"
        receipt = grow_public.package_public(self.zip, self.meta, output)
        with zipfile.ZipFile(output / receipt["zip"]["path"]) as archive:
            evidence = json.loads(archive.read("SOURCE-OMISSIONS.json"))
            self.assertEqual(records, evidence["records"])
            self.assertEqual(self.meta["omissions"], evidence["occurrences"])
            self.assertEqual([{ "sourceSha256": source_hash, "coreBounds": [-16, 0, 0, 16]}], evidence["sourceCores"])
            self.assertIn("2 source occurrences cover 1 unique feature IDs", archive.read("WORLD-SOURCES.txt").decode())
        self.meta["omissionEvidence"]["redactions"][0]["occurrenceIndex"] = 9
        with self.assertRaises(ValueError):
            grow_public.package_public(self.zip, self.meta, self.root / "wrong-occurrence")
        self.meta["omissionEvidence"]["redactions"] = []
        self.meta["omissions"][1]["occurrenceIndex"] = 0
        with self.assertRaises(ValueError):
            grow_public.package_public(self.zip, self.meta, self.root / "duplicate-occurrence")


if __name__ == "__main__":
    unittest.main()
