import copy
import gzip
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from anvil import (BYTE, SHORT, INT, LONG, STRING, LIST, COMPOUND, LONG_ARRAY,
                   Tag, ListPayload, NbtFile, write_nbt, read_nbt, write_level_dat)
from base_scope import BaseScope
from worldgen_profile import PROFILE, FRAME, WorldgenProfileError, prepare_worldgen_profile


def compound(value):
    return Tag(COMPOUND, value)


def string(value):
    return Tag(STRING, value)


def rectangle(kind=None):
    return {"type": "Feature", "properties": {} if kind is None else {"class": kind},
            "geometry": {"type": "Polygon", "coordinates": [[[0,0],[4,0],[4,4],[0,4],[0,0]]]}}


def fixture_document():
    settings = {"features": Tag(BYTE, 1), "biome": string("minecraft:plains"),
        "layers": Tag(LIST, ListPayload(COMPOUND, [
            compound({"block": string("minecraft:air"), "height": Tag(INT, 62)}),
            compound({"block": string("minecraft:dirt"), "height": Tag(INT, 2)}),
            compound({"block": string("minecraft:grass_block"), "height": Tag(INT, 1)})])),
        "structure_overrides": Tag(LIST, ListPayload(STRING, [string("minecraft:villages")])),
        "lakes": Tag(BYTE, 1), "unknown_setting": Tag(SHORT, -123)}
    dimensions = {
        "minecraft:overworld": compound({"type": string("minecraft:overworld"),
            "generator": compound({"type": string("minecraft:flat"), "settings": compound(settings)})}),
        "minecraft:the_nether": compound({"type": string("minecraft:the_nether"),
            "generator": compound({"type": string("minecraft:noise"), "settings": string("minecraft:nether")}),
            "unknown_dimension": Tag(LONG_ARRAY, [-9223372036854775808, 9223372036854775807])})}
    return NbtFile("", compound({"DataVersion": Tag(INT, 4790),
        "data": compound({"seed": Tag(LONG, -9223372036854775808), "bonus_chest": Tag(BYTE, 0),
                         "generate_structures": Tag(BYTE, 1), "dimensions": compound(dimensions)}),
        "unknown_root": Tag(SHORT, 42)}))


class WorldgenProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        descriptor = {"schemaVersion": 1, "kind": "country-coast-base-scope",
                      "coordinateFrame": FRAME, "sampling": "block-center"}
        for name in ("country", "foreignExclusions", "coast"):
            doc = {"type": "FeatureCollection", "features": [] if name == "foreignExclusions"
                   else [rectangle("land" if name == "coast" else None)]}
            if name == "coast":
                doc.update(coordinate_space="minecraft_xz",
                           crs={"type": "name", "properties": {"name": "EPSG:3414 derived X=E,Z=60000-N"}})
            else:
                doc.update(coordinateSystem="EPSG:3414",
                           axisMapping={"x": "easting", "z": "60000-northing"})
            payload = json.dumps(doc).encode()
            path = self.root / (name + ".json")
            path.write_bytes(payload)
            descriptor[name] = {"path": path.name, "sha256": hashlib.sha256(payload).hexdigest()}
        scope_path = self.root / "scope.json"
        scope_bytes = json.dumps(descriptor).encode()
        scope_path.write_bytes(scope_bytes)
        self.scope = BaseScope(scope_path)
        self.context = {"baseScopeDescriptor": {"path": str(scope_path), "bytes": len(scope_bytes),
                        "sha256": hashlib.sha256(scope_bytes).hexdigest()},
                        "outputSettingsPath": str(self.root / "future.dat"), "jobId": "tiny-profile-fixture"}
        self.doc = fixture_document()

    def source(self, doc=None):
        payload = gzip.compress(write_nbt(doc or self.doc), mtime=0)
        source_path = self.root / "source.dat"
        source_path.write_bytes(payload)
        self.context["sourceSettings"] = {"path": str(source_path), "bytes": len(payload),
                                         "sha256": hashlib.sha256(payload).hexdigest()}
        return payload

    def prepare(self, doc=None):
        return prepare_worldgen_profile(self.source(doc), profile=PROFILE,
                                        base_scope=self.scope, context=self.context)

    @staticmethod
    def settings(doc):
        return doc.root.value["data"].value["dimensions"].value["minecraft:overworld"].value["generator"].value["settings"].value

    def test_air_profile_is_exact_and_preserves_other_typed_metadata(self):
        before = write_nbt(self.doc)
        output, receipt = self.prepare()
        settings = self.settings(output)
        self.assertEqual(ListPayload(COMPOUND, [compound({"block": string("minecraft:air"),
            "height": Tag(INT, 1)})]), settings["layers"].value)
        self.assertEqual(ListPayload(STRING, []), settings["structure_overrides"].value)
        self.assertEqual(Tag(BYTE, 0), settings["features"])
        self.assertEqual(Tag(BYTE, 0), settings["lakes"])
        self.assertEqual(Tag(SHORT, -123), settings["unknown_setting"])
        self.assertEqual(self.doc.root.value["data"].value["dimensions"].value["minecraft:the_nether"],
                         output.root.value["data"].value["dimensions"].value["minecraft:the_nether"])
        self.assertEqual(Tag(LONG, -9223372036854775808), output.root.value["data"].value["seed"])
        self.assertEqual(before, write_nbt(self.doc))
        self.assertTrue(receipt["unchangedOutsideMutationPaths"])
        self.assertEqual(5, len(receipt["mutationPaths"]))

    def test_prepared_gzip_hash_matches_existing_writer(self):
        output, receipt = self.prepare()
        self.assertFalse(Path(self.context["outputSettingsPath"]).exists())
        write_level_dat(self.context["outputSettingsPath"], output)
        actual = Path(self.context["outputSettingsPath"]).read_bytes()
        self.assertEqual(receipt["outputSettings"]["sha256"], hashlib.sha256(actual).hexdigest())
        self.assertEqual(receipt["outputNbtSha256"], hashlib.sha256(gzip.decompress(actual)).hexdigest())
        self.assertFalse(receipt["outputSettings"]["materialized"])
        for key in ("generatedNewChunksTested", "runtimeLoadAccepted", "fullWorldAccepted", "fullFidelity"):
            self.assertIs(receipt[key], False)

    def test_idempotence_and_determinism(self):
        first, receipt1 = self.prepare()
        _, receipt2 = self.prepare()
        self.assertEqual(receipt1, receipt2)
        second, receipt3 = self.prepare(first)
        self.assertEqual(write_nbt(first), write_nbt(second))
        self.assertEqual([], receipt3["mutationPaths"])

    def test_receipt_does_not_alias_caller_or_scope(self):
        _, receipt = self.prepare()
        self.context["sourceSettings"]["sha256"] = "f" * 64
        self.assertNotEqual("f" * 64, receipt["sourceSettings"]["sha256"])
        receipt["verifiedScopeContext"]["scopeReceipt"]["masks"]["coast"]["sha256"] = "0" * 64
        self.assertNotEqual("0" * 64, self.scope.receipt()["masks"]["coast"]["sha256"])

    def test_explicit_profile_and_real_scope_object_required(self):
        payload = self.source()
        with self.assertRaises(WorldgenProfileError):
            prepare_worldgen_profile(payload, profile="legacy", base_scope=self.scope, context=self.context)
        with self.assertRaises(WorldgenProfileError):
            prepare_worldgen_profile(payload, profile=PROFILE, base_scope=self.scope.receipt(), context=self.context)

    def test_source_or_scope_pin_mismatch_rejected(self):
        payload = self.source()
        self.context["sourceSettings"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(WorldgenProfileError, "actual bytes"):
            prepare_worldgen_profile(payload, profile=PROFILE, base_scope=self.scope, context=self.context)
        self.source()
        Path(self.context["baseScopeDescriptor"]["path"]).write_text("{}")
        with self.assertRaisesRegex(WorldgenProfileError, "actual bytes"):
            self.prepare()

    def test_nonmodern_settings_and_wrong_generator_rejected(self):
        self.doc.root.value["DataVersion"] = Tag(INT, 4189)
        with self.assertRaises(WorldgenProfileError):
            self.prepare()
        self.doc = fixture_document()
        gen = self.doc.root.value["data"].value["dimensions"].value["minecraft:overworld"].value["generator"].value
        gen["type"] = string("minecraft:noise")
        with self.assertRaises(WorldgenProfileError):
            self.prepare()

    def test_wrong_tag_types_and_biome_rejected(self):
        for key, bad in (("features", Tag(INT, 0)), ("structure_overrides", Tag(LIST, ListPayload(0, []))),
                         ("biome", string("minecraft:forest"))):
            self.doc = fixture_document()
            self.settings(self.doc)[key] = bad
            with self.assertRaises(WorldgenProfileError):
                self.prepare()

    def test_nonempty_legacy_structures_rejected(self):
        self.settings(self.doc)["structures"] = compound({"minecraft:village": compound({})})
        with self.assertRaisesRegex(WorldgenProfileError, "Legacy structures"):
            self.prepare()

    def test_named_root_and_empty_legacy_structures_preserved(self):
        self.doc.name = "custom-preserved-name"
        self.settings(self.doc)["structures"] = compound({})
        result, _ = self.prepare()
        self.assertEqual("custom-preserved-name", result.name)
        self.assertEqual(compound({}), self.settings(result)["structures"])

    def test_corrupt_gzip_rejected(self):
        payload = b"not gzip"
        self.context["sourceSettings"] = {"path": "memory-source.dat", "bytes": len(payload),
                                         "sha256": hashlib.sha256(payload).hexdigest()}
        with self.assertRaises(WorldgenProfileError):
            prepare_worldgen_profile(payload, profile=PROFILE, base_scope=self.scope, context=self.context)


if __name__ == "__main__":
    unittest.main()
