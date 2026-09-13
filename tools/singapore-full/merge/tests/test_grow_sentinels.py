import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import anvil as a
import grow_sentinels as g


def fixture_chunk(cx, cz, blocks):
    palette = [("minecraft:air", ())]
    indices = [0] * 4096
    for (x, y, z), (name, properties) in blocks.items():
        if (x // 16, z // 16) != (cx, cz):
            continue
        state = (name, tuple(sorted(properties.items())))
        if state not in palette:
            palette.append(state)
        indices[((y % 16) * 16 + z % 16) * 16 + x % 16] = palette.index(state)
    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    data = []
    for start in range(0, 4096, per_long):
        word = sum(value << (offset * bits) for offset, value in enumerate(indices[start:start + per_long]))
        data.append(word if word < 1 << 63 else word - (1 << 64))
    states = []
    for name, properties in palette:
        value = {"Name": a.Tag(a.STRING, name)}
        if properties:
            value["Properties"] = a.Tag(a.COMPOUND, {key: a.Tag(a.STRING, item) for key, item in properties})
        states.append(a.Tag(a.COMPOUND, value))
    section = a.Tag(a.COMPOUND, {"Y": a.Tag(a.BYTE, 0), "block_states": a.Tag(a.COMPOUND, {
        "palette": a.Tag(a.LIST, a.ListPayload(a.COMPOUND, states)), "data": a.Tag(a.LONG_ARRAY, data)})})
    return a.NbtFile("", a.Tag(a.COMPOUND, {"xPos": a.Tag(a.INT, cx), "zPos": a.Tag(a.INT, cz),
        "sections": a.Tag(a.LIST, a.ListPayload(a.COMPOUND, [section]))}))


def fixture(directory):
    world = Path(directory) / "world"
    blocks = {(x, 0, z): ("minecraft:stone", {}) for x in range(32) for z in range(32)}
    runs = []
    for q in range(4):
        x, z = (8 if q % 2 == 0 else 24), (8 if q < 2 else 24)
        for kind, px, py, pz, name, props in [
            ("building", x + 1, 3, z, "minecraft:red_concrete", {}),
            ("road", x, 0, z + 1, "minecraft:gray_concrete", {}),
            ("water", x - 1, 0, z, "minecraft:water", {"level": "0"}),
        ]:
            blocks[(px, py, pz)] = (name, props)
            runs.append({"x": px, "z": pz, "yMin": py, "yMax": py + 1, "block": name, "properties": props,
                         "layer": kind, "featureId": kind, "geometryKind": kind, "sourceClass": "test"})
        # This closer building run was overwritten; it must never become proof.
        runs.append({"x": x, "z": z, "yMin": 3, "yMax": 4, "block": "minecraft:glass",
                     "layer": "building", "featureId": "overwritten", "geometryKind": "wall", "sourceClass": "test"})
    chunks = {(cx, cz): fixture_chunk(cx, cz, blocks) for cx in range(2) for cz in range(2)}
    region = world / g.REGION_DIRECTORY / "r.0.0.mca"
    a.write_region(region, chunks)
    path = Path(directory) / "runs.jsonl"
    path.write_text("".join(json.dumps(run) + "\n" for run in runs))
    return world, region, path, runs


class SentinelTests(unittest.TestCase):
    def test_distributed_verified_categories_and_actual_corners(self):
        with tempfile.TemporaryDirectory() as directory:
            world, region, path, runs = fixture(directory)
            before = hashlib.sha256(region.read_bytes()).hexdigest()
            with patch.object(g, "iter_region", wraps=g.iter_region) as reader:
                result = g.select_sentinels(world, [0, 0, 32, 32], [path])
            self.assertEqual(reader.call_count, 1)
            self.assertEqual(len(result), 16)
            self.assertEqual(sum(item["kind"] == "building" for item in result), 4)
            self.assertEqual(sum(item["kind"] == "road" for item in result), 4)
            self.assertFalse(any(item["block"] == "minecraft:glass" for item in result))
            corners = [item for item in result if item["id"].startswith("terrain_corner")]
            self.assertEqual(len(corners), 4)
            self.assertEqual({g._quadrant(item["x"], item["z"], [0, 0, 32, 32]) for item in corners}, {0, 1, 2, 3})
            self.assertEqual([item["block"] for item in corners], ["minecraft:stone", "minecraft:water", "minecraft:stone", "minecraft:stone"])
            self.assertTrue(all(item.get("properties") == {"level": "0"} for item in result if item["id"].startswith("water")))
            self.assertEqual(hashlib.sha256(region.read_bytes()).hexdigest(), before)
            path.write_text("".join(json.dumps(run) + "\n" for run in reversed(runs)))
            self.assertEqual(g.select_sentinels(world, [0, 0, 32, 32], [path]), result)

    def test_missing_categories_are_not_invented(self):
        with tempfile.TemporaryDirectory() as directory:
            world, _, path, _ = fixture(directory)
            path.write_text("")
            result = g.select_sentinels(world, [0, 0, 32, 32], [path])
            self.assertEqual(len(result), 4)
            self.assertTrue(all(item["kind"] == "terrain" for item in result))

    def test_negative_coordinates_and_packed_signed_long(self):
        blocks = {}
        for index in range(15):
            blocks[(-16 + index if index < 14 else -1, 0, -16)] = ("minecraft:color_" + str(index), {})
        doc = fixture_chunk(-1, -1, blocks)
        for coords, expected in blocks.items():
            self.assertEqual(g.block_at(doc, *coords), expected)
        self.assertEqual(g.block_at(doc, -1, -1, -1), ("minecraft:air", {}))
        with self.assertRaises(a.NbtError):
            g.block_at(doc, 0, 0, 0)

    def test_invalid_bounds_and_missing_world_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                g.select_sentinels(directory, [0, 0, 15, 16], [])
            with self.assertRaises(FileNotFoundError):
                g.select_sentinels(directory, [0, 0, 16, 16], [])


if __name__ == "__main__":
    unittest.main()
