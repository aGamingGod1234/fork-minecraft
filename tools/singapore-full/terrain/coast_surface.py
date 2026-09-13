"""Stream provisional sea surface columns from an explicit global-XZ coast mask."""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path
import time

import numpy as np
import shapely
from shapely.geometry import shape

MAX_CELLS = 2_000_000
BITS = {"land": 1, "sea": 2, "unknown": 4}


def compile_mask(document):
    if document.get("type") != "FeatureCollection" or document.get("coordinate_space") != "minecraft_xz":
        raise ValueError("Expected an explicit minecraft_xz FeatureCollection; no coordinate conversion is inferred")
    features = []
    for index, feature in enumerate(document.get("features", [])):
        label = feature.get("properties", {}).get("class")
        if label not in BITS:
            raise ValueError(f"Unsupported coast class: {label!r}")
        geometry = shape(feature["geometry"])
        if geometry.geom_type not in ("Polygon", "MultiPolygon") or geometry.is_empty or not geometry.is_valid:
            raise ValueError("Coast features must be valid nonempty polygons or multipolygons")
        if not all(math.isfinite(v) for v in geometry.bounds):
            raise ValueError("Nonfinite coast geometry")
        identifier = str(feature.get("id", f"coast-face/{index}"))
        features.append((identifier, BITS[label], geometry))
    if not features:
        raise ValueError("Empty coast mask")
    return sorted(features, key=lambda item: item[0])


def iter_water_runs(document, bounds, statistics=None):
    """Half-open global block bounds; multiple distinct covering labels mean unknown."""
    if len(bounds) != 4 or any(type(v) is not int for v in bounds):
        raise ValueError("bounds must be four integer global coordinates")
    x0, z0, x1, z1 = bounds
    width, height = x1 - x0, z1 - z0
    if width <= 0 or height <= 0 or width * height > MAX_CELLS:
        raise ValueError(f"Positive bounded extent limited to {MAX_CELLS} cells")
    features = compile_mask(document)
    stats = statistics if statistics is not None else {}
    stats.update(totalCells=width * height, landCells=0, seaCells=0, unknownCells=0)
    centers = np.arange(x0, x1, dtype=np.float64) + 0.5
    for z in range(z0, z1):
        labels = np.zeros(width, dtype=np.uint8)
        owners = np.full(width, -1, dtype=np.int32)
        for index, (_, bit, geometry) in enumerate(features):
            covered = shapely.intersects_xy(geometry, centers, z + 0.5)
            labels[covered] |= bit
            if bit == BITS["sea"]:
                owners[covered & (owners == -1)] = index
        sea = labels == BITS["sea"]
        land_count, sea_count = int(np.count_nonzero(labels == BITS["land"])), int(np.count_nonzero(sea))
        stats["landCells"] += land_count
        stats["seaCells"] += sea_count
        stats["unknownCells"] += width - land_count - sea_count
        for column in np.flatnonzero(sea):
            yield {"x": x0 + int(column), "z": z, "yMin": 0, "yMax": 1,
                   "block": "minecraft:water", "properties": {"level": "0"},
                   "featureId": "coastline/" + features[int(owners[column])][0],
                   "geometryKind": "coastline-sea", "sourceClass": "osm-coastline-sea-level-provisional",
                   "shorelineSourceClass": "mapped", "elevationSourceClass": "provisional-flat",
                   "layer": 30}


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def peak_working_set_bytes():
    if os.name != "nt":
        return None
    class Counters(ctypes.Structure):
        _fields_ = [("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong)] + [
            (name, ctypes.c_size_t) for name in ("PeakWorkingSetSize", "WorkingSetSize",
            "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
            "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]
    counters = Counters()
    counters.cb = ctypes.sizeof(counters)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.GetCurrentProcess.restype = ctypes.c_void_p
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    psapi.GetProcessMemoryInfo.argtypes = (ctypes.c_void_p, ctypes.c_void_p, ctypes.c_ulong)
    if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), ctypes.byref(counters), counters.cb):
        return None
    return counters.PeakWorkingSetSize


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mask", type=Path, required=True)
    parser.add_argument("--bounds", type=int, nargs=4, required=True, metavar=("MIN_X", "MIN_Z", "MAX_X", "MAX_Z"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--expected-sha256")
    args = parser.parse_args()
    started = time.perf_counter()
    mask_hash = file_hash(args.mask)
    if args.expected_sha256 and args.expected_sha256.lower() != mask_hash:
        parser.error("Frozen coast mask hash mismatch")
    if args.output.exists() or args.manifest.exists():
        parser.error("Output and manifest must be new paths")
    document = json.loads(args.mask.read_text(encoding="utf-8-sig"))
    compile_mask(document)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    statistics = {}
    with args.output.open("x", encoding="utf-8", newline="\n") as stream:
        for run in iter_water_runs(document, args.bounds, statistics):
            stream.write(json.dumps(run, separators=(",", ":"), allow_nan=False) + "\n")
    manifest = {"schema": "fork.coast-surface.v1", "status": "emitted-unclipped",
        "maskSha256": mask_hash, "outputSha256": file_hash(args.output),
        "outputBytes": args.output.stat().st_size, "bounds": args.bounds,
        "statistics": statistics, "elapsedSeconds": time.perf_counter() - started,
        "processId": os.getpid(), "peakWorkingSetBytes": peak_working_set_bytes(),
        "sampling": "global block centers (x+0.5,z+0.5); half-open requested bounds",
        "boundaryRule": "one distinct covering class; conflicts and uncovered are unknown",
        "shorelineSourceClass": "mapped", "waterHeightSourceClass": "estimated",
        "groundProfileId": "flat-provisional-y0-v1",
        "vertical": {"yMin": 0, "yMaxExclusive": 1, "surfaceY": 0,
            "datum": None, "waterHeightAccepted": False, "bathymetryAccepted": False,
            "policy": "One-block provisional surface replaces base at Y=0; no measured water height or depth"},
        "countryClipApplied": False, "foreignExclusionsApplied": False,
        "requiresPipelineCountryAndExclusionClip": True,
        "actualTerrainAccepted": False, "landUnmodified": True, "unknownFilled": False}
    with args.manifest.open("x", encoding="utf-8") as stream:
        json.dump(manifest, stream, indent=2, allow_nan=False)
        stream.write("\n")
    print(json.dumps(manifest))


if __name__ == "__main__":
    main()
