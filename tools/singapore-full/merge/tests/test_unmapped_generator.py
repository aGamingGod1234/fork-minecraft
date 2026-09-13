import hashlib
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from anvil import Tag, NbtFile, read_level_dat, write_level_dat, write_nbt
from overlay import compound, write_overlay
import test_worldgen_profile as profile_fixture


class UnmappedGeneratorIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.fixture = profile_fixture.WorldgenProfileTests("test_explicit_profile_and_real_scope_object_required")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.base = self.fixture.root
        self.scope = Path(self.fixture.context["baseScopeDescriptor"]["path"])
        self.template = self.base / "template" / "level.dat"
        self.template.parent.mkdir()
        write_level_dat(self.template, NbtFile("", compound({"Data": compound({"DataVersion": Tag(3,4790)})})))
        self.settings = self.template.parent / "data" / "minecraft" / "world_gen_settings.dat"
        self.settings.parent.mkdir(parents=True)
        write_level_dat(self.settings, profile_fixture.fixture_document())
        self.runs = self.base / "empty.jsonl"
        self.runs.write_bytes(b"")

    def render(self, name, **kw):
        return write_overlay([self.runs], self.base/name, (0,0,16,16), self.template,
                             max_chunks=1, allowed_root=self.base, **kw)

    def test_opt_in_keeps_written_chunk_bytes_and_binds_actual_settings(self):
        source_before = self.settings.read_bytes()
        legacy = self.render("legacy", base_scope=str(self.scope))
        scoped = self.render("scoped", base_scope=str(self.scope), unmapped_generator="AIR_ONLY")
        self.assertNotIn("unmappedGenerator", legacy)
        self.assertEqual(legacy["spawn"], scoped["spawn"])
        for old in legacy["outputs"]:
            if old["path"].endswith(".mca"):
                new = next(v for v in scoped["outputs"] if v["path"] == old["path"])
                self.assertEqual(old, new)
        receipt = scoped["unmappedGenerator"]
        actual = (self.base/"scoped"/receipt["outputSettings"]["path"]).read_bytes()
        self.assertEqual(receipt["outputSettings"]["sha256"], hashlib.sha256(actual).hexdigest())
        document = read_level_dat(self.base/"scoped"/receipt["outputSettings"]["path"])
        self.assertEqual(receipt["outputNbtSha256"], hashlib.sha256(write_nbt(document)).hexdigest())
        self.assertEqual(source_before, self.settings.read_bytes())
        self.assertFalse(scoped["runtimeLoadAccepted"])

    def test_missing_explicit_scope_rejects_before_any_world_write(self):
        for value in (None, self.fixture.scope):
            with self.assertRaisesRegex(ValueError, "explicit pinned"):
                self.render("rejected", base_scope=value, unmapped_generator="AIR_ONLY")
            self.assertFalse((self.base/"rejected").exists())

    def test_air_only_rejects_legacy_embedded_settings(self):
        self.settings.unlink()
        data = compound({"DataVersion":Tag(3,4790),"WorldGenSettings":compound({})})
        write_level_dat(self.template, NbtFile("",compound({"Data":data})))
        with self.assertRaisesRegex(ValueError,"MC26 external"):
            self.render("embedded",base_scope=str(self.scope),unmapped_generator="AIR_ONLY")
        self.assertFalse((self.base/"embedded").exists())

    def test_air_only_rejects_mismatched_level_version(self):
        for version in (Tag(3,3955),Tag(4,4790),Tag(5,4790.0)):
            write_level_dat(self.template, NbtFile("",compound({"Data":compound({"DataVersion":version})})))
            with self.assertRaisesRegex(ValueError,"DataVersion 4790"):
                self.render("wrong-version",base_scope=str(self.scope),unmapped_generator="AIR_ONLY")
            self.assertFalse((self.base/"wrong-version").exists())


if __name__ == "__main__":
    unittest.main()
