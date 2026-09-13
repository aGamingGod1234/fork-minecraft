"""Bounded reader for the tiled float32 Copernicus GLO-30 GeoTIFF profile.

This reads surface elevations, not bare-earth terrain or building heights.
Only requested compressed blocks are decoded. No network or world writes.
"""
from __future__ import annotations

import argparse
from collections import OrderedDict
import json
import math
from pathlib import Path
import struct
import zlib

import numpy as np


class RasterFormatError(ValueError):
    """The file does not match the deliberately limited supported profile."""


def _read_exact(stream, count):
    value = stream.read(count)
    if len(value) != count:
        raise RasterFormatError("Truncated TIFF")
    return value


class CopernicusTile:
    """One local north-up EPSG:4326 Copernicus DSM tile; not thread-safe.

    ``sample_lonlat(lon, lat, method='nearest')`` returns a JSON-ready evidence
    dictionary. ``elevation_m=None`` means unavailable, never a sea-level fill.
    Use a context manager or call close(). A bounded LRU retains two blocks.
    """

    def __init__(self, path, cache_blocks=2):
        if not isinstance(cache_blocks, int) or not 1 <= cache_blocks <= 8:
            raise ValueError("cache_blocks must be an integer from 1 to 8")
        self.path = Path(path)
        self._file = self.path.open("rb")
        self._cache = OrderedDict()
        self._cache_limit = cache_blocks
        self.blocks_decoded = 0
        self.compressed_bytes_read = 0
        try:
            self._load_metadata()
        except Exception:
            self.close()
            raise

    def _load_metadata(self):
        header = _read_exact(self._file, 8)
        if header[:2] not in (b"II", b"MM"):
            raise RasterFormatError("Not a TIFF")
        self._endian = "<" if header[:2] == b"II" else ">"
        marker, offset = struct.unpack(self._endian + "HI", header[2:])
        if marker != 42:
            raise RasterFormatError("Only classic TIFF is supported; BigTIFF is not")
        self._file.seek(offset)
        count = struct.unpack(self._endian + "H", _read_exact(self._file, 2))[0]
        if count > 256:
            raise RasterFormatError("Unexpectedly large TIFF directory")
        entries = _read_exact(self._file, count * 12)
        formats = {1: "B", 2: "s", 3: "H", 4: "I", 5: "II", 6: "b",
                   7: "B", 8: "h", 9: "i", 10: "ii", 11: "f", 12: "d"}
        tags = {}
        for index in range(count):
            raw = entries[index * 12:index * 12 + 12]
            tag, kind, length = struct.unpack(self._endian + "HHI", raw[:8])
            if kind not in formats:
                continue
            fmt = formats[kind]
            size = struct.calcsize(self._endian + fmt) * length
            if size > 1024 * 1024:
                raise RasterFormatError("TIFF metadata entry exceeds 1 MiB limit")
            if size <= 4:
                data = raw[8:8 + size]
            else:
                self._file.seek(struct.unpack(self._endian + "I", raw[8:])[0])
                data = _read_exact(self._file, size)
            tags[tag] = (data.decode("ascii").rstrip("\0") if kind == 2 else
                         struct.unpack(self._endian + fmt * length, data))
        def one(tag, default=None):
            value = tags.get(tag)
            return value[0] if value else default

        self.width, self.height = one(256), one(257)
        self.tile_width, self.tile_height = one(322), one(323)
        if not all(isinstance(v, int) and 0 < v <= 16384 for v in
                   (self.width, self.height, self.tile_width, self.tile_height)):
            raise RasterFormatError("Expected bounded tiled image dimensions")
        if self.tile_width * self.tile_height * 4 > 16 * 1024 * 1024:
            raise RasterFormatError("Decoded block exceeds 16 MiB limit")
        if (one(258), one(277, 1), one(339), one(284, 1), one(274, 1)) != (32, 1, 3, 1, 1):
            raise RasterFormatError("Expected top-left single-band float32 TIFF")
        self._compression = one(259, 1)
        self._predictor = one(317, 1)
        if self._compression not in (1, 8, 32946) or self._predictor not in (1, 3):
            raise RasterFormatError("Only uncompressed/Deflate and predictor 1/3 supported")
        self._offsets, self._counts = tags.get(324, ()), tags.get(325, ())
        self._across = math.ceil(self.width / self.tile_width)
        tile_count = self._across * math.ceil(self.height / self.tile_height)
        if len(self._offsets) != tile_count or len(self._counts) != tile_count:
            raise RasterFormatError("Invalid tile offset/count arrays")
        geo = tags.get(34735, ())
        if len(geo) < 4 or len(geo) != 4 + geo[3] * 4:
            raise RasterFormatError("Missing or invalid GeoKeyDirectory")
        keys = {geo[i]: geo[i + 3] for i in range(4, len(geo), 4)
                if geo[i + 1] == 0 and geo[i + 2] == 1}
        if keys.get(2048) != 4326 or keys.get(1024) != 2:
            raise RasterFormatError("Expected geographic EPSG:4326")
        raster_type = keys.get(1025)
        if raster_type not in (1, 2):
            raise RasterFormatError("Explicit PixelIsArea/PixelIsPoint required")
        if 34264 in tags:
            raise RasterFormatError("Rotated/transformation-matrix rasters unsupported")
        scale, tie = tags.get(33550, ()), tags.get(33922, ())
        if len(scale) != 3 or len(tie) != 6 or scale[0] <= 0 or scale[1] <= 0:
            raise RasterFormatError("Expected north-up scale and one tiepoint")
        self.dx, self.dy = scale[:2]
        shift = 0.5 if raster_type == 1 else 0.0
        self.lon0 = tie[3] + (shift - tie[0]) * self.dx
        self.lat0 = tie[4] - (shift - tie[1]) * self.dy
        if not all(math.isfinite(v) for v in (self.dx, self.dy, self.lon0, self.lat0)):
            raise RasterFormatError("Non-finite georeferencing")
        self.nodata = float(tags[42113]) if 42113 in tags else None
        self.metadata = {
            "source_id": self.path.name,
            "product": "Copernicus DEM GLO-30",
            "semantic": "surface_dsm",
            "horizontal_crs": "EPSG:4326",
            "vertical_datum": "EGM2008", "vertical_crs": "EPSG:3855",
            "vertical_datum_evidence": "Copernicus product documentation; not embedded in these tiles",
            "vertical_unit": "m", "width": self.width, "height": self.height,
            "pixel_type": "PixelIsPoint" if raster_type == 2 else "PixelIsArea",
            "pixel_center_origin_lonlat": [self.lon0, self.lat0],
            "pixel_step_lonlat": [self.dx, -self.dy],
            "pixel_outer_bounds_wsen": [self.lon0 - self.dx / 2,
                self.lat0 - (self.height - 0.5) * self.dy,
                self.lon0 + (self.width - 0.5) * self.dx, self.lat0 + self.dy / 2],
            "nodata": ("NaN" if self.nodata is not None and math.isnan(self.nodata) else self.nodata),
            "nodata_tag_present": 42113 in tags,
            "block_shape_rows_columns": [self.tile_height, self.tile_width],
            "compression": self._compression, "predictor": self._predictor,
            "cache_blocks": self._cache_limit,
            "limitations": ["DSM includes roofs, infrastructure and vegetation",
                "30 m-class source sampling is not 1 m measurement accuracy",
                "Zero is a valid sample; no land/water classification is inferred",
                "No vertical datum conversion or bare-earth correction performed"],
        }

    def _block(self, index):
        if index in self._cache:
            self._cache.move_to_end(index)
            return self._cache[index]
        count = self._counts[index]
        expected = self.tile_width * self.tile_height * 4
        if not 0 < count <= expected + 65536:
            raise RasterFormatError("Invalid or oversized compressed tile")
        self._file.seek(self._offsets[index])
        encoded = _read_exact(self._file, count)
        self.compressed_bytes_read += count
        if self._compression == 1:
            raw = encoded
        else:
            decoder = zlib.decompressobj()
            raw = decoder.decompress(encoded, expected + 1)
            if not decoder.eof or decoder.unconsumed_tail or decoder.unused_data:
                raise RasterFormatError("Incomplete, oversized or trailing Deflate data")
        if len(raw) != expected:
            raise RasterFormatError("Decoded tile length mismatch")
        if self._predictor == 3:
            # TIFF floating predictor: row-wise byte differences, then MSB-first
            # byte planes. The cumulative sum intentionally spans plane boundaries.
            rows = np.frombuffer(raw, dtype=np.uint8).reshape(self.tile_height, self.tile_width * 4)
            accumulated = np.cumsum(rows, axis=1, dtype=np.uint8)
            packed = accumulated.reshape(self.tile_height, 4, self.tile_width).transpose(0, 2, 1).copy()
            values = packed.view(">f4").reshape(self.tile_height, self.tile_width)
        else:
            values = np.frombuffer(raw, dtype=self._endian + "f4").reshape(self.tile_height, self.tile_width)
        self.blocks_decoded += 1
        self._cache[index] = values
        while len(self._cache) > self._cache_limit:
            self._cache.popitem(last=False)
        return values

    def _value(self, col, row):
        tile = self._block((row // self.tile_height) * self._across + col // self.tile_width)
        value = float(tile[row % self.tile_height, col % self.tile_width])
        if not math.isfinite(value) or (self.nodata is not None and value == self.nodata):
            return None
        return value

    def sample_lonlat(self, lon, lat, method="nearest"):
        if method not in ("nearest", "bilinear"):
            raise ValueError("method must be nearest or bilinear")
        if not math.isfinite(lon) or not math.isfinite(lat):
            raise ValueError("Coordinates must be finite")
        col, row = (lon - self.lon0) / self.dx, (self.lat0 - lat) / self.dy
        # Stabilize exact centers and half-pixel boundaries after degree arithmetic.
        if abs(col * 2 - round(col * 2)) < 1e-9:
            col = round(col * 2) / 2
        if abs(row * 2 - round(row * 2)) < 1e-9:
            row = round(row * 2) / 2
        result = {"elevation_m": None, "status": "outside", "semantic": "surface_dsm",
                  "vertical_datum": "EGM2008", "vertical_unit": "m", "method": method,
                  "source_id": self.path.name, "pixel_col_row": [col, row]}
        if not (-0.5 <= col < self.width - 0.5 and -0.5 <= row < self.height - 0.5):
            return result
        if method == "nearest":
            neighbours = [(math.floor(col + 0.5), math.floor(row + 0.5), 1.0)]
        else:
            left, top = math.floor(col), math.floor(row)
            fx, fy = col - left, row - top
            neighbours = [(left, top, (1 - fx) * (1 - fy)),
                          (left + 1, top, fx * (1 - fy)),
                          (left, top + 1, (1 - fx) * fy), (left + 1, top + 1, fx * fy)]
            neighbours = [item for item in neighbours if item[2] > 0]
        if any(not (0 <= c < self.width and 0 <= r < self.height) for c, r, _ in neighbours):
            result["reason"] = "interpolation_neighbour_outside_tile"
            return result
        weighted = [(self._value(c, r), weight) for c, r, weight in neighbours]
        result["status"] = "nodata" if any(value is None for value, _ in weighted) else "valid"
        if result["status"] == "valid":
            result["elevation_m"] = sum(value * weight for value, weight in weighted)
        result["contributing_pixels"] = [[c, r] for c, r, _ in neighbours]
        return result

    def close(self):
        self._file.close()
        self._cache.clear()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tile", type=Path)
    parser.add_argument("--lonlat", nargs=2, type=float, metavar=("LON", "LAT"), action="append")
    parser.add_argument("--method", choices=("nearest", "bilinear"), default="nearest")
    args = parser.parse_args()
    with CopernicusTile(args.tile) as tile:
        samples = [tile.sample_lonlat(*point, args.method) for point in (args.lonlat or [])]
        print(json.dumps({"metadata": tile.metadata, "samples": samples,
                          "blocks_decoded": tile.blocks_decoded,
                          "compressed_bytes_read": tile.compressed_bytes_read}, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
