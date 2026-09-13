"""Focused codec, translation and corruption tests. Run with unittest."""
import copy
import gzip
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import anvil as a


def compound(value):
    return a.Tag(a.COMPOUND, value)


def list_tag(items, subtype=a.COMPOUND):
    return a.Tag(a.LIST, a.ListPayload(subtype, items))


def chunk(cx=-1, cz=31, legacy=False):
    root = {
        "xPos": a.Tag(a.INT, cx),
        "zPos": a.Tag(a.INT, cz),
        "yPos": a.Tag(a.INT, -4),
        "DataVersion": a.Tag(a.INT, 3955),
        "sections": list_tag([compound({
            "Y": a.Tag(a.BYTE, -4),
            "block_states": compound({
                "palette": list_tag([compound({"Name": a.Tag(a.STRING, "minecraft:stone")})]),
                "data": a.Tag(a.LONG_ARRAY, [-(1 << 63), -1, 0, (1 << 63) - 1]),
            }),
            "biomes": compound({"palette": list_tag([a.Tag(a.STRING, "minecraft:plains")], a.STRING)}),
        })]),
        "Heightmaps": compound({"WORLD_SURFACE": a.Tag(a.LONG_ARRAY, [-1, -(1 << 63), 123])}),
        "PostProcessing": list_tag([list_tag([a.Tag(a.SHORT, 4095)], a.SHORT)], a.LIST),
        "structures": compound({"References": compound({}), "starts": compound({})}),
        "block_entities": list_tag([compound({
            "id": a.Tag(a.STRING, "minecraft:banner"),
            "x": a.Tag(a.INT, cx * 16 + 15), "y": a.Tag(a.INT, 12),
            "z": a.Tag(a.INT, cz * 16 + 1), "custom": a.Tag(a.STRING, "retained"),
        })]),
        "block_ticks": list_tag([compound({
            "i": a.Tag(a.STRING, "minecraft:stone"),
            "x": a.Tag(a.INT, cx * 16), "y": a.Tag(a.INT, 10),
            "z": a.Tag(a.INT, cz * 16 + 15), "t": a.Tag(a.INT, 1),
        })]),
        "fluid_ticks": list_tag([]),
        "entities": list_tag([]),
        "UnknownFutureField": compound({"number": a.Tag(a.LONG, -123456789123456789)}),
    }
    return a.NbtFile("", compound({"Level": compound(root)}) if legacy else compound(root))


def map_frame():
    return compound({
        "id": a.Tag(a.STRING, "minecraft:item_frame"),
        "Pos": list_tag([a.Tag(a.DOUBLE, 0.5), a.Tag(a.DOUBLE, 2), a.Tag(a.DOUBLE, 2.5)], a.DOUBLE),
        "TileX": a.Tag(a.INT, 0), "TileY": a.Tag(a.INT, 2), "TileZ": a.Tag(a.INT, 2),
        "block_pos": list_tag([a.Tag(a.INT, n) for n in (0, 2, 2)], a.INT),
        "UUID": a.Tag(a.INT_ARRAY, [1, -2, 3, -4]),
        "Item": compound({
            "id": a.Tag(a.STRING, "minecraft:filled_map"),
            "count": a.Tag(a.INT, 1),
            "components": compound({"minecraft:map_id": a.Tag(a.INT, 0)}),
        }),
    })


class CodecTests(unittest.TestCase):
    def test_every_type_roundtrip_and_signed_long(self):
        doc = a.NbtFile("root", compound({
            "b": a.Tag(a.BYTE, -128), "s": a.Tag(a.SHORT, -32768),
            "i": a.Tag(a.INT, -(1 << 31)), "l": a.Tag(a.LONG, -(1 << 63)),
            "f": a.Tag(a.FLOAT, 1.5), "d": a.Tag(a.DOUBLE, -3.25),
            "ba": a.Tag(a.BYTE_ARRAY, b"\x00\x80\xff"),
            "text": a.Tag(a.STRING, "Singapore\x00\U0001f1f8\U0001f1ec"),
            "ia": a.Tag(a.INT_ARRAY, [-(1 << 31), (1 << 31) - 1]),
            "la": a.Tag(a.LONG_ARRAY, [-(1 << 63), -1, (1 << 63) - 1]),
            "empty": list_tag([], a.END),
            "nested": list_tag([list_tag([a.Tag(a.BYTE, -1)], a.BYTE)], a.LIST),
        }))
        encoded = a.write_nbt(doc)
        self.assertEqual(a.read_nbt(encoded), doc)
        self.assertEqual(a.write_nbt(a.read_nbt(encoded)), encoded)

    def test_truncated_trailing_invalid_negative_lengths(self):
        encoded = a.write_nbt(chunk())
        for invalid in (encoded[:-1], encoded + b"\x00", b"\x00\x00\x00",
                        b"\x07\x00\x00" + struct.pack(">i", -1),
                        b"\x09\x00\x00\x00" + struct.pack(">i", 1),
                        b"\x09\x00\x00\x01" + struct.pack(">i", 20000000)):
            with self.subTest(invalid=invalid[:12]), self.assertRaises(a.NbtError):
                a.read_nbt(invalid)

    def test_invalid_numeric_and_mixed_list_rejected(self):
        with self.assertRaises(a.NbtError):
            a.write_nbt(a.NbtFile("", a.Tag(a.LONG, 1 << 63)))
        with self.assertRaises(a.NbtError):
            a.write_nbt(a.NbtFile("", list_tag([a.Tag(a.BYTE, 1)], a.INT)))

    def test_compression_truncation_concat_and_limit(self):
        compressed = zlib.compress(b"a" * 100)
        self.assertEqual(a._inflate(compressed, 15), b"a" * 100)
        for invalid in (compressed[:-1], compressed + compressed):
            with self.assertRaises(a.NbtError):
                a._inflate(invalid, 15)
        original = a.MAX_NBT_BYTES
        try:
            a.MAX_NBT_BYTES = 10
            with self.assertRaises(a.NbtError):
                a._inflate(compressed, 15)
        finally:
            a.MAX_NBT_BYTES = original

    def test_level_dat_gzip_roundtrip(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "level.dat"
            doc = a.NbtFile("", compound({"Data": compound({"Player": compound({"Score": a.Tag(a.INT, 42)})})}))
            a.write_level_dat(path, doc)
            first = path.read_bytes()
            self.assertEqual(a.read_level_dat(path), doc)
            a.write_level_dat(path, doc)
            self.assertEqual(path.read_bytes(), first)


class TranslationTests(unittest.TestCase):
    def test_negative_and_region_boundary_preserves_relative_data(self):
        original = chunk()
        before = a.write_nbt(original)
        moved = a.translate_chunk(original, 33, -64)
        self.assertEqual(a.chunk_coords(moved), (32, -33))
        old_root, root = a.chunk_payload(original), a.chunk_payload(moved)
        for name in ("sections", "Heightmaps", "PostProcessing", "UnknownFutureField"):
            self.assertEqual(root[name], old_root[name])
        be = root["block_entities"].value.items[0].value
        self.assertEqual((be["x"].value, be["y"].value, be["z"].value), (527, 12, -527))
        tick = root["block_ticks"].value.items[0].value
        self.assertEqual((tick["x"].value, tick["y"].value, tick["z"].value), (512, 10, -513))
        self.assertEqual(a.write_nbt(original), before)
        self.assertEqual(a.translate_chunk(moved, -33, 64), original)

    def test_legacy_level_roundtrip(self):
        doc = chunk(legacy=True)
        self.assertEqual(a.chunk_coords(a.translate_chunk(doc, -32, 32)), (-33, 63))

    def test_entity_policy_explicit_drop_only_proven_maps(self):
        doc = chunk(0, 0)
        a.chunk_payload(doc)["entities"] = list_tag([map_frame()])
        with self.assertRaises(a.NbtError):
            a.translate_chunk(doc, 1, 1)
        stats = a.inspect_chunk(doc, entity_policy="drop_generated_map_entities")
        self.assertEqual(stats["dropped_map_entities"], 1)
        self.assertEqual(stats["block_entities"], 1)
        self.assertEqual(len(a.chunk_payload(doc)["entities"].value.items), 1)
        modified = copy.deepcopy(doc)
        a.chunk_payload(modified)["entities"].value.items[0].value["Item"].value["id"].value = "minecraft:diamond"
        with self.assertRaises(a.NbtError):
            a.translate_chunk(modified, 1, 1, entity_policy="drop_generated_map_entities")
        modified = copy.deepcopy(doc)
        a.chunk_payload(modified)["entities"].value.items[0].value["Passengers"] = list_tag([compound({"id": a.Tag(a.STRING, "minecraft:pig")})])
        with self.assertRaises(a.NbtError):
            a.translate_chunk(modified, 1, 1, entity_policy="drop_generated_map_entities")

    def test_structures_rejected(self):
        for name in ("starts", "References"):
            doc = chunk()
            a.chunk_payload(doc)["structures"].value[name] = compound({"fortress": a.Tag(a.LONG_ARRAY, [123])})
            with self.subTest(name=name), self.assertRaises(a.NbtError):
                a.translate_chunk(doc, 1, 1)

    def test_block_entity_and_tick_ownership_rejected(self):
        for name in ("block_entities", "block_ticks"):
            doc = chunk()
            a.chunk_payload(doc)[name].value.items[0].value["x"].value = 0
            with self.subTest(name=name), self.assertRaises(a.NbtError):
                a.translate_chunk(doc, 1, 1)

    def test_special_nested_coordinate_schemas_rejected(self):
        for name, value in (("ExitPortal", compound({"X": a.Tag(a.INT, 99)})),
                            ("Bees", list_tag([compound({"EntityData": compound({})})])),
                            ("custom", compound({"x": a.Tag(a.INT, 42)}))):
            doc = chunk()
            a.chunk_payload(doc)["block_entities"].value.items[0].value[name] = value
            with self.subTest(name=name), self.assertRaises(a.NbtError):
                a.translate_chunk(doc, 1, 1)

    def test_offset_type_and_overflow(self):
        for dx in (True, 1.5, 1 << 32):
            with self.subTest(dx=dx), self.assertRaises(a.NbtError):
                a.translate_chunk(chunk(), dx, 0)


class RegionTests(unittest.TestCase):
    def test_negative_regions_and_cross_boundary_roundtrip(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "r.-1.0.mca"
            original = {(-1, 31): chunk()}
            a.write_region(source, original)
            self.assertEqual(a.read_region(source), original)
            moved = a.translate_chunk(original[(-1, 31)], 33, -64)
            target = Path(directory) / "r.1.-2.mca"
            a.write_region(target, {(32, -33): moved})
            self.assertEqual(a.read_region(target), {(32, -33): moved})
            self.assertEqual(a.read_region(source), original)

    def test_deterministic_multiple_chunks(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r.0.0.mca"
            items = {(0, 0): chunk(0, 0), (31, 31): chunk(31, 31), (15, 17): chunk(15, 17)}
            a.write_region(path, items)
            first = path.read_bytes()
            self.assertEqual(a.read_region(path), items)
            a.write_region(path, dict(reversed(list(items.items()))))
            self.assertEqual(path.read_bytes(), first)

    def test_reject_wrong_target_and_coordinate_slot(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r.0.0.mca"
            with self.assertRaises(a.NbtError):
                a.write_region(path, {(-1, 31): chunk()})
            a.write_region(path, {(0, 0): chunk(0, 0)})
            data = bytearray(path.read_bytes())
            data[4:8], data[:4] = data[:4], bytes(4)
            path.write_bytes(data)
            with self.assertRaisesRegex(a.NbtError, "disagree"):
                a.read_region(path)

    def test_sector_overlap_external_and_bad_length(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r.0.0.mca"
            a.write_region(path, {(0, 0): chunk(0, 0)})
            original = path.read_bytes()
            cases = []
            overlap = bytearray(original)
            overlap[4:8] = overlap[:4]
            cases.append(overlap)
            external = bytearray(original)
            external[8196] = 130
            cases.append(external)
            bad_length = bytearray(original)
            bad_length[8192:8196] = struct.pack(">I", 8192)
            cases.append(bad_length)
            cases.extend((original[:100], original[:-1]))
            for invalid in cases:
                path.write_bytes(invalid)
                with self.assertRaises(a.NbtError):
                    a.read_region(path)



class StreamingTests(unittest.TestCase):
    @staticmethod
    def frozen_writer_bytes(chunks):
        # Exact pre-stream writer algorithm retained as an independent byte oracle.
        locations, body, offset = bytearray(a.SECTOR), bytearray(), 2
        for (cx, cz), document in sorted(chunks.items(), key=lambda item: (item[0][1], item[0][0])):
            encoded = zlib.compress(a.write_nbt(document), 6)
            record = struct.pack(">I", len(encoded) + 1) + b"\x02" + encoded
            count = (len(record) + a.SECTOR - 1) // a.SECTOR
            index = (cx % 32) + (cz % 32) * 32
            locations[index * 4:index * 4 + 4] = ((offset << 8) | count).to_bytes(4, "big")
            body.extend(record)
            body.extend(bytes(count * a.SECTOR - len(record)))
            offset += count
        return bytes(locations) + bytes(a.SECTOR) + body

    def test_stream_matches_frozen_writer_bytes_arbitrary_order(self):
        import hashlib
        items = {(-32, -32): chunk(-32, -32), (-1, -1): chunk(-1, -1), (-17, -20): chunk(-17, -20)}
        expected = self.frozen_writer_bytes(items)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r.-1.-1.mca"
            seen = []
            def documents():
                for entry in reversed(list(items.items())):
                    seen.append(entry[0])
                    yield entry
            receipt = a.write_region_stream(path, documents())
            self.assertEqual(len(seen), 3)
            self.assertEqual(path.read_bytes(), expected)
            self.assertEqual(receipt, {"bytes": len(expected), "sha256": hashlib.sha256(expected).hexdigest(), "chunks": 3})
            self.assertEqual(dict(a.iter_region(path)), items)
            self.assertEqual(list(Path(directory).iterdir()), [path])

    def test_failure_preserves_existing_destination_and_removes_temps(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r.0.0.mca"
            path.write_bytes(b"existing destination")
            def interrupted():
                yield (0, 0), chunk(0, 0)
                raise RuntimeError("producer failed")
            with self.assertRaisesRegex(RuntimeError, "producer failed"):
                a.write_region_stream(path, interrupted())
            self.assertEqual(path.read_bytes(), b"existing destination")
            self.assertEqual(list(Path(directory).iterdir()), [path])

    def test_duplicate_foreign_and_bad_coordinate_fail_without_artifact(self):
        cases = [
            [((0, 0), chunk(0, 0)), ((0, 0), chunk(0, 0))],
            [((0, 0), chunk(0, 0)), ((32, 0), chunk(32, 0))],
            [((True, 0), chunk(1, 0))],
            [((0, 0), chunk(1, 0))],
        ]
        for entries in cases:
            with self.subTest(entries=entries[:1]), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "r.0.0.mca"
                with self.assertRaises(a.NbtError):
                    a.write_region_stream(path, iter(entries))
                self.assertEqual(list(Path(directory).iterdir()), [])

    def test_complete_header_validated_before_first_yield(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r.0.0.mca"
            a.write_region(path, {(0, 0): chunk(0, 0)})
            data = bytearray(path.read_bytes())
            data[4092:4096] = data[:4]  # Very last header entry overlaps the first.
            path.write_bytes(data)
            iterator = a.iter_region(path)
            with self.assertRaisesRegex(a.NbtError, "Overlapping"):
                next(iterator)

    def test_invalid_later_header_offset_rejected_before_first_yield(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r.0.0.mca"
            a.write_region(path, {(0, 0): chunk(0, 0)})
            data = bytearray(path.read_bytes())
            data[4092:4096] = ((0xFFFFFF << 8) | 1).to_bytes(4, "big")
            path.write_bytes(data)
            with self.assertRaisesRegex(a.NbtError, "sector location"):
                next(a.iter_region(path))

    def test_payloads_are_decoded_lazily(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r.0.0.mca"
            a.write_region(path, {(0, 0): chunk(0, 0), (1, 0): chunk(1, 0)})
            data = bytearray(path.read_bytes())
            second_offset = int.from_bytes(data[4:8], "big") >> 8
            data[second_offset * a.SECTOR + 4] = 99
            path.write_bytes(data)
            iterator = a.iter_region(path)
            self.assertEqual(next(iterator)[0], (0, 0))
            with self.assertRaisesRegex(a.NbtError, "compression"):
                next(iterator)

    def test_reopen_validation_failure_does_not_publish(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r.0.0.mca"
            with patch.object(a, "_iter_region_handle", side_effect=a.NbtError("injected verification failure")):
                with self.assertRaisesRegex(a.NbtError, "verification failure"):
                    a.write_region_stream(path, iter([((0, 0), chunk(0, 0))]))
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_empty_stream_matches_legacy_empty_region(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "r.0.0.mca"
            receipt = a.write_region_stream(path, iter(()))
            self.assertEqual(path.read_bytes(), bytes(8192))
            self.assertEqual(receipt["chunks"], 0)
            self.assertEqual(list(a.iter_region(path)), [])



class LongPathTests(unittest.TestCase):
    @unittest.skipUnless(sys.platform == "win32", "Windows extended-length path fixture")
    def test_windows_region_and_level_dat_over_300_characters(self):
        import os
        import shutil
        directory = tempfile.mkdtemp(prefix="fork-anvil-long-")
        try:
            parent = Path(directory).joinpath(*(["segment_" + "x" * 40] * 7))
            path = parent / "r.0.0.mca"
            self.assertGreater(len(str(path)), 300)
            entries = {(0, 0): chunk(0, 0), (1, 0): chunk(1, 0)}
            expected = StreamingTests.frozen_writer_bytes(entries)
            a.write_region_stream(path, reversed(list(entries.items())))
            with open(a._fs_path(path), "rb") as handle:
                self.assertEqual(handle.read(), expected)
            self.assertEqual(dict(a.iter_region(path)), entries)
            self.assertEqual(a.read_region(path), entries)
            a.write_region(path, entries)
            with open(a._fs_path(path), "rb") as handle:
                self.assertEqual(handle.read(), expected)
            level = parent / "level.dat"
            doc = a.NbtFile("", compound({"Data": compound({"DataVersion": a.Tag(a.INT, 4790)})}))
            a.write_level_dat(level, doc)
            self.assertEqual(a.read_level_dat(level), doc)
            self.assertEqual(sorted(os.listdir(a._fs_path(parent))), ["level.dat", "r.0.0.mca"])
        finally:
            self.assertTrue(Path(directory).name.startswith("fork-anvil-long-"))
            shutil.rmtree(a._fs_path(directory))

    @unittest.skipUnless(sys.platform == "win32", "Windows path transformation")
    def test_unc_and_already_extended_paths(self):
        source = "\\\\server\\share\\folder"
        expected = "\\\\?\\UNC\\server\\share\\folder"
        self.assertEqual(a._fs_path(source), expected)
        self.assertEqual(a._fs_path(expected), expected)
        drive = "\\\\?\\C:\\already\\extended"
        self.assertEqual(a._fs_path(drive), drive)

    def test_non_windows_path_is_unchanged(self):
        from unittest.mock import patch
        with patch.object(a.os, "name", "posix"):
            self.assertEqual(a._fs_path("relative/path"), "relative/path")


if __name__ == "__main__":
    unittest.main()
