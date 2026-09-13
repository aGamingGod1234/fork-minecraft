"""Tiny hand-built TIFF fixtures, numeric georeferencing and decoder checks."""
import math
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from copernicus import CopernicusTile, RasterFormatError


def fixture(path, values, *, point=True, nodata=None, predictor=3, endian="<", crs=4326, compression=8):
    height, width = len(values), len(values[0])
    # Two-by-two blocks ensure interpolation crosses actual compressed blocks.
    blocks = []
    for top in range(0, height, 2):
        for left in range(0, width, 2):
            raw = bytearray()
            for row in values[top:top + 2]:
                if predictor == 3:
                    packed = [struct.pack(">f", v) for v in row[left:left + 2]]
                    planes = bytes(packed[col][byte] for byte in range(4) for col in range(2))
                    raw.extend([planes[0]] + [(planes[i] - planes[i - 1]) % 256 for i in range(1, 8)])
                else:
                    raw.extend(struct.pack(endian + "ff", *row[left:left + 2]))
            blocks.append(zlib.compress(bytes(raw)))
    keys = [1, 1, 0, 3, 1024, 0, 1, 2, 1025, 0, 1, 2 if point else 1, 2048, 0, 1, crs]
    tags = {256: (4, [width]), 257: (4, [height]), 258: (3, [32]),
            259: (3, [compression]), 277: (3, [1]), 284: (3, [1]), 317: (3, [predictor]),
            322: (4, [2]), 323: (4, [2]), 324: (4, [0] * len(blocks)),
            325: (4, [len(b) for b in blocks]), 339: (3, [3]),
            33550: (12, [0.01, 0.01, 0]), 33922: (12, [0, 0, 0, 103, 2, 0]),
            34735: (3, keys)}
    if nodata is not None:
        tags[42113] = (2, str(nodata) + "\0")
    formats = {2: "s", 3: "H", 4: "I", 12: "d"}
    def encode(kind, value):
        return value.encode("ascii") if kind == 2 else struct.pack(endian + formats[kind] * len(value), *value)
    payload_start = 8 + 2 + 12 * len(tags) + 4
    block_start = payload_start + sum(len(encode(k, v)) for k, v in tags.values() if len(encode(k, v)) > 4)
    offsets, cursor = [], block_start
    for block in blocks:
        offsets.append(cursor)
        cursor += len(block)
    tags[324] = (4, offsets)
    entries, extra = bytearray(), bytearray()
    for tag, (kind, value) in sorted(tags.items()):
        data = encode(kind, value)
        entries.extend(struct.pack(endian + "HHI", tag, kind, len(value)))
        if len(data) <= 4:
            entries.extend(data.ljust(4, b"\0"))
        else:
            entries.extend(struct.pack(endian + "I", payload_start + len(extra)))
            extra.extend(data)
    path.write_bytes((b"II" if endian == "<" else b"MM") + struct.pack(endian + "HIH", 42, 8, len(tags))
                     + entries + b"\0" * 4 + extra + b"".join(blocks))


class ReaderTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "tiny.tif"
        self.values = [[-10.5, 0, 10, 20], [30, 40, 50, 60], [70, 80, 90, 100], [110, 120, 130, 140]]

    def tearDown(self):
        self.directory.cleanup()

    def test_point_origin_negative_zero_and_block_cache(self):
        fixture(self.path, self.values)
        with CopernicusTile(self.path) as tile:
            self.assertEqual(tile.blocks_decoded, 0)
            self.assertEqual(tile.sample_lonlat(103, 2)["elevation_m"], -10.5)
            self.assertEqual(tile.sample_lonlat(103.01, 2)["elevation_m"], 0)
            self.assertEqual(tile.blocks_decoded, 1)
            self.assertFalse(tile.metadata["nodata_tag_present"])
            self.assertEqual(tile.metadata["pixel_center_origin_lonlat"], [103, 2])
            self.assertEqual(tile.sample_lonlat(102, 2)["status"], "outside")
            self.assertEqual(tile.blocks_decoded, 1)

    def test_bilinear_crosses_four_blocks(self):
        fixture(self.path, self.values)
        with CopernicusTile(self.path) as tile:
            answer = tile.sample_lonlat(103.015, 1.985, "bilinear")
            self.assertAlmostEqual(answer["elevation_m"], 65, places=8)
            self.assertEqual(len(answer["contributing_pixels"]), 4)
            self.assertEqual(tile.blocks_decoded, 4)
            self.assertEqual(len(tile._cache), 2)

    def test_no_interpolation_across_nodata(self):
        self.values[0][1] = -9999
        fixture(self.path, self.values, nodata=-9999)
        with CopernicusTile(self.path) as tile:
            self.assertEqual(tile.sample_lonlat(103.005, 1.995, "bilinear")["status"], "nodata")
            self.assertIsNone(tile.sample_lonlat(103.01, 2)["elevation_m"])
            self.assertEqual(tile.sample_lonlat(103, 2, "bilinear")["elevation_m"], -10.5)

    def test_nan_and_infinity_are_missing(self):
        self.values[0][0], self.values[0][1] = math.nan, math.inf
        fixture(self.path, self.values, nodata="nan")
        with CopernicusTile(self.path) as tile:
            self.assertEqual(tile.metadata["nodata"], "NaN")
            self.assertEqual(tile.sample_lonlat(103, 2)["status"], "nodata")
            self.assertEqual(tile.sample_lonlat(103.01, 2)["status"], "nodata")

    def test_area_half_pixel_conversion(self):
        fixture(self.path, self.values, point=False)
        with CopernicusTile(self.path) as tile:
            self.assertEqual(tile.metadata["pixel_center_origin_lonlat"], [103.005, 1.995])
            self.assertEqual(tile.sample_lonlat(103.005, 1.995)["elevation_m"], -10.5)

    def test_big_endian_and_no_predictor(self):
        for predictor in (1, 3):
            fixture(self.path, self.values, endian=">", predictor=predictor)
            with CopernicusTile(self.path) as tile:
                self.assertEqual(tile.sample_lonlat(103.03, 1.97)["elevation_m"], 140)

    def test_edges_no_clamping_or_extrapolation(self):
        fixture(self.path, self.values)
        with CopernicusTile(self.path) as tile:
            self.assertEqual(tile.sample_lonlat(103.03, 1.97, "bilinear")["elevation_m"], 140)
            result = tile.sample_lonlat(103.032, 1.97, "bilinear")
            self.assertEqual(result["reason"], "interpolation_neighbour_outside_tile")
            self.assertEqual(tile.sample_lonlat(103.036, 1.97)["status"], "outside")

    def test_half_open_bounds_and_nearest_ties(self):
        fixture(self.path, self.values)
        with CopernicusTile(self.path) as tile:
            self.assertEqual(tile.sample_lonlat(102.995, 2)["elevation_m"], -10.5)
            self.assertEqual(tile.sample_lonlat(103.035, 2)["status"], "outside")
            self.assertEqual(tile.sample_lonlat(103.005, 2)["elevation_m"], 0)
            self.assertEqual(tile.sample_lonlat(103, 1.995)["elevation_m"], 30)

    def test_unsupported_compression_and_predictor_rejected(self):
        for args in ({"compression": 5}, {"predictor": 2}, {"predictor": 7}):
            fixture(self.path, self.values, **args)
            with self.assertRaises(RasterFormatError):
                CopernicusTile(self.path)

    def test_reject_wrong_crs_bad_args_and_truncated_file(self):
        fixture(self.path, self.values, crs=3414)
        with self.assertRaises(RasterFormatError):
            CopernicusTile(self.path)
        fixture(self.path, self.values)
        with CopernicusTile(self.path) as tile:
            with self.assertRaises(ValueError):
                tile.sample_lonlat(math.nan, 1)
            with self.assertRaises(ValueError):
                tile.sample_lonlat(103, 2, "cubic")
        self.path.write_bytes(b"II")
        with self.assertRaises(RasterFormatError):
            CopernicusTile(self.path)


if __name__ == "__main__":
    unittest.main()
