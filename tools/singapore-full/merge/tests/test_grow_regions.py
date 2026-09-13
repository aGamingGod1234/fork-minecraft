"""Small synthetic ownership/copy fixtures; no real-world jobs."""
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import anvil as a
import grow_regions as g


def document(cx, cz, label):
    return a.NbtFile("", a.Tag(a.COMPOUND, {
        "DataVersion": a.Tag(a.INT, 4790), "xPos": a.Tag(a.INT, cx),
        "zPos": a.Tag(a.INT, cz), "yPos": a.Tag(a.INT, -4),
        "label": a.Tag(a.STRING, label),
        "Heightmaps": a.Tag(a.COMPOUND, {"WORLD_SURFACE": a.Tag(a.LONG_ARRAY, [-1, -(1 << 63), 123])}),
        "sections": a.Tag(a.LIST, a.ListPayload(a.COMPOUND, [
            a.Tag(a.COMPOUND, {"Y": a.Tag(a.BYTE, -4),
                              "block_states": a.Tag(a.COMPOUND, {
                                  "palette": a.Tag(a.LIST, a.ListPayload(a.COMPOUND, [
                                      a.Tag(a.COMPOUND, {"Name": a.Tag(a.STRING, "minecraft:stone")})
                                  ]))
                              })})
        ])),
    }))


def source(root, source_id, bounds, chunks):
    world = root / source_id
    region = world / g.MODERN_REGION_DIRECTORY
    region.mkdir(parents=True)
    groups = {}
    for coords, doc in chunks.items():
        groups.setdefault((coords[0] // 32, coords[1] // 32), {})[coords] = doc
    for (rx, rz), group in groups.items():
        a.write_region(region / f"r.{rx}.{rz}.mca", group)
    return {"id": source_id, "world_path": str(world), "core_bounds": bounds,
            "region_directory": g.MODERN_REGION_DIRECTORY}


def snapshot(world):
    return {p.relative_to(world).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in Path(world).rglob("*") if p.is_file()}


class GrowTests(unittest.TestCase):
    def test_adjacent_cores_with_overlapping_halos_choose_owners(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            owned_a, owned_b = document(0, 0, "owned-a"), document(1, 0, "owned-b")
            first = source(root, "a", [0, 0, 16, 16], {
                (-1, 0): document(-1, 0, "halo-a-west"), (0, 0): owned_a,
                (1, 0): document(1, 0, "halo-a-east")})
            second = source(root, "b", [16, 0, 32, 16], {
                (0, 0): document(0, 0, "halo-b-west"), (1, 0): owned_b,
                (2, 0): document(2, 0, "halo-b-east")})
            before = [snapshot(root / "a"), snapshot(root / "b")]
            output = root / "output"
            report = g.merge_regions([second, first], output)
            region = output / g.MODERN_REGION_DIRECTORY / "r.0.0.mca"
            self.assertEqual(a.read_region(region), {(0, 0): owned_a, (1, 0): owned_b})
            self.assertEqual(report["chunkCount"], 2)
            self.assertEqual(report["sourceChunkCounts"], {"b": 1, "a": 1})
            self.assertEqual(report["ownership"], {"exactCoreCoverage": True, "coordinatesTranslated": False,
                                                   "duplicateOwnership": False, "canonicalNbtVerified": True})
            self.assertEqual(report["outputs"][0]["sha256"], hashlib.sha256(region.read_bytes()).hexdigest())
            self.assertEqual([snapshot(root / "a"), snapshot(root / "b")], before)
            # Reversing source order must not change any output region bytes.
            other = root / "output-other"
            again = g.merge_regions([first, second], other)
            self.assertEqual(again["outputs"], report["outputs"])

    def test_negative_coordinates_and_region_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            chunks = {(-1, -1): document(-1, -1, "west"), (0, -1): document(0, -1, "east")}
            descriptor = source(root, "negative", [-16, -16, 16, 0], chunks)
            report = g.merge_regions([descriptor], root / "output")
            self.assertEqual(report["chunkCount"], 2)
            found = {}
            for item in report["outputs"]:
                found.update(a.read_region(root / "output" / item["path"]))
            self.assertEqual(found, chunks)
            self.assertEqual(len(report["outputs"]), 2)

    def test_holes_fail_before_output_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            descriptor = source(root, "incomplete", [0, 0, 32, 16], {(0, 0): document(0, 0, "only")})
            output = root / "output"
            with self.assertRaisesRegex(ValueError, "holes"):
                g.merge_regions([descriptor], output)
            self.assertFalse(output.exists())

    def test_duplicate_core_and_id_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = source(root, "a", [0, 0, 16, 16], {(0, 0): document(0, 0, "a")})
            second = source(root, "b", [0, 0, 16, 16], {(0, 0): document(0, 0, "b")})
            with self.assertRaisesRegex(ValueError, "ownership"):
                g.merge_regions([first, second], root / "output")
            with self.assertRaisesRegex(ValueError, "unique"):
                g.merge_regions([first, first], root / "output")

    def test_existing_staging_world_keeps_level_and_existing_regions_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            descriptor = source(root, "a", [0, 0, 16, 16], {(0, 0): document(0, 0, "a")})
            output = root / "stage"
            output.mkdir()
            level = output / "level.dat"
            level.write_bytes(b"Main owns this file")
            g.merge_regions([descriptor], output)
            before = snapshot(output)
            with self.assertRaises(FileExistsError):
                g.merge_regions([descriptor], output)
            self.assertEqual(snapshot(output), before)
            self.assertEqual(level.read_bytes(), b"Main owns this file")

    def test_region_filename_coordinate_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            descriptor = source(root, "a", [512, 0, 528, 16], {(0, 0): document(0, 0, "wrong")})
            region = root / "a" / g.MODERN_REGION_DIRECTORY
            (region / "r.0.0.mca").rename(region / "r.1.0.mca")
            with self.assertRaisesRegex(a.NbtError, "disagree"):
                g.merge_regions([descriptor], root / "output")

    def test_failure_never_publishes_partial_region_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            descriptor = source(root, "a", [0, 0, 16, 16], {(0, 0): document(0, 0, "a")})
            output = root / "output"
            with patch.object(g, "write_region_stream", side_effect=RuntimeError("injected write failure")):
                with self.assertRaisesRegex(RuntimeError, "injected"):
                    g.merge_regions([descriptor], output)
            parent = output / "dimensions/minecraft/overworld"
            self.assertFalse((parent / "region").exists())
            self.assertEqual(list(parent.iterdir()), [])

    def test_invalid_core_path_and_source_destination_overlap(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            descriptor = source(root, "a", [0, 0, 16, 16], {(0, 0): document(0, 0, "a")})
            for bounds in ([0, 0, 15, 16], [False, 0, 16, 16], [16, 0, 0, 16]):
                with self.subTest(bounds=bounds), self.assertRaises(ValueError):
                    g.merge_regions([dict(descriptor, core_bounds=bounds)], root / "out")
            with self.assertRaises(ValueError):
                g.merge_regions([descriptor], root / "a" / "nested")
            with self.assertRaises(ValueError):
                g.merge_regions([descriptor], root / "out", region_directory="../escape")


if __name__ == "__main__":
    unittest.main()
