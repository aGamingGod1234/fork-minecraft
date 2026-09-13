import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from anvil import Tag, NbtFile, read_region, write_level_dat, read_level_dat
from overlay import write_overlay, compound, parse_run, pack_indices, MIN_Y, validate_job_lease


def block_at(chunks, x, y, z):
    root = chunks[x // 16, z // 16].root.value
    sections = root["sections"].value.items
    section = next(item.value for item in sections if item.value["Y"].value == y // 16)
    states = section["block_states"].value
    palette = states["palette"].value.items
    if len(palette) == 1:
        index = 0
    else:
        bits = max(4, (len(palette) - 1).bit_length())
        offset = ((y % 16) * 16 + z % 16) * 16 + x % 16
        per = 64 // bits
        data = states["data"].value
        index = ((data[offset // per] & ((1 << 64) - 1)) >> ((offset % per) * bits)) & ((1 << bits) - 1)
    return palette[index].value["Name"].value


class OverlayTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.template = self.base / "template.dat"
        write_level_dat(self.template, NbtFile("", compound({"Data": compound({"DataVersion": Tag(3, 3955), "Player": compound({"secret": Tag(8, "fixture")})})})))

    def render(self, rows, name="world", bounds=(-16, -16, 16, 16)):
        source = self.base / (name + ".jsonl")
        source.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
        target = self.base / name
        receipt = write_overlay([source], target, bounds, self.template, allowed_root=self.base)
        chunks = {}
        for path in (target / "region").glob("*.mca"):
            chunks.update(read_region(path))
        return receipt, chunks, target

    @staticmethod
    def row(x, z, low, high, block, layer="building", feature="A"):
        return {"x": x, "z": z, "yMin": low, "yMax": high, "block": block, "layer": layer,
                "featureId": feature, "geometryKind": "wall", "sourceClass": "synthetic"}

    def test_global_negative_boundaries_palette_and_clean_level(self):
        receipt, chunks, world = self.render([self.row(-1, -1, 1, 20, "minecraft:glass"), self.row(0, 0, 1, 3, "minecraft:stone")])
        self.assertEqual(set(chunks), {(-1, -1), (-1, 0), (0, -1), (0, 0)})
        self.assertEqual(block_at(chunks, -1, 19, -1), "minecraft:glass")
        self.assertEqual(block_at(chunks, -1, 20, -1), "minecraft:air")
        self.assertEqual(block_at(chunks, 0, 2, 0), "minecraft:stone")
        self.assertEqual(block_at(chunks, 0, 0, 0), "minecraft:grass_block")
        self.assertEqual(block_at(chunks, 0, -4, 0), "minecraft:bedrock")
        self.assertNotIn("Player", read_level_dat(world / "level.dat").root.value["Data"].value)
        self.assertFalse(receipt["assemblyAccepted"])
        self.assertEqual(receipt["groundProfileId"], "flat-provisional-y0-v1")
        sx, sy, sz = receipt["spawn"]
        self.assertEqual(block_at(chunks, sx, sy, sz), "minecraft:air")
        self.assertEqual(block_at(chunks, sx, sy + 1, sz), "minecraft:air")

    def test_layer_order_and_input_order_invariant(self):
        low = self.row(0, 0, 1, 3, "minecraft:stone", "road")
        high = self.row(0, 0, 2, 4, "minecraft:glass", "building")
        a, ac, _ = self.render([low, high], "a")
        b, bc, _ = self.render([high, low], "b")
        self.assertEqual(a["crossLayerOverwrittenBlocks"], {"40->50": 1})
        self.assertEqual(block_at(ac, 0, 2, 0), "minecraft:glass")
        self.assertEqual(ac, bc)

    def test_same_layer_conflict_rejects_before_world_write(self):
        with self.assertRaisesRegex(ValueError, "same-layer"):
            self.render([self.row(0, 0, 1, 3, "minecraft:stone"), self.row(0, 0, 2, 4, "minecraft:glass", feature="B")])
        self.assertFalse((self.base / "world").exists())

    def test_bounds_y_and_unknown_layers_rejected(self):
        for row in (self.row(16, 0, 1, 2, "minecraft:stone"), self.row(0, 0, 319, 321, "minecraft:stone"), self.row(0, 0, 1, 2, "minecraft:stone", "unknown")):
            with self.assertRaises(ValueError):
                parse_run(row, (-16, -16, 16, 16))

    def test_refuses_existing_world_and_outside_scope(self):
        source = self.base / "empty.jsonl"
        source.write_text("")
        with self.assertRaises(ValueError):
            write_overlay([source], self.base.parent / "outside", (0, 0, 16, 16), self.template, allowed_root=self.base)
        world = self.base / "existing"
        world.mkdir()
        with self.assertRaises(FileExistsError):
            write_overlay([source], world, (0, 0, 16, 16), self.template, allowed_root=self.base)

    def test_packing_signed_non_straddling_heightmaps(self):
        values = [511] * 256
        packed = pack_indices(values, 9)
        self.assertEqual(len(packed), 37)
        self.assertEqual(packed[0], (1 << 63) - 1)
        self.assertEqual(pack_indices([15] * 16, 4), [-1])

    def test_partial_terrain_preserves_underlying_ground(self):
        _, chunks, _ = self.render([self.row(0, 0, 0, 1, "minecraft:sand", "terrain")])
        self.assertEqual(block_at(chunks, 0, 0, 0), "minecraft:sand")
        self.assertEqual(block_at(chunks, 0, -1, 0), "minecraft:dirt")
        self.assertEqual(block_at(chunks, 0, -4, 0), "minecraft:bedrock")

    def test_unknown_block_semantics_rejected(self):
        with self.assertRaisesRegex(ValueError, "unsupported block"):
            self.render([self.row(0, 0, 1, 2, "unregistered:fake_material")])

    def test_job_lease_scope_and_expiry(self):
        now = datetime.now(timezone.utc)
        root = self.base / "queue" / "jobs" / "fixture" / "attempts" / "one" / "output"
        lease_path = self.base / "lease.json"
        lease = {"id": "test", "machine": "Desktop", "approvedBy": "/root/singapore_full_coordinator", "heavyJobSlot": "B",
                 "cpuThreads": 1, "outputRoot": str(root), "startsUtc": (now - timedelta(minutes=1)).isoformat(),
                 "expiresUtc": (now + timedelta(minutes=1)).isoformat()}
        lease_path.write_text(json.dumps(lease))
        self.assertEqual(validate_job_lease(lease_path, root / "tile" / "world", self.base)["id"], "test")
        with self.assertRaisesRegex(ValueError, "contain"):
            validate_job_lease(lease_path, self.base / "other", self.base)
        lease["expiresUtc"] = (now - timedelta(seconds=1)).isoformat()
        lease_path.write_text(json.dumps(lease))
        with self.assertRaisesRegex(ValueError, "not currently valid"):
            validate_job_lease(lease_path, root / "world", self.base)


if __name__ == "__main__":
    unittest.main()
