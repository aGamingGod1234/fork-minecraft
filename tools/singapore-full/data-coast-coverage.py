#!/usr/bin/env python3
"""Classify planned cores from a pinned continuous coastline mask, never a world."""
import argparse
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path
import time
import unittest
from collections import Counter
from shapely.geometry import box, shape, mapping
from shapely.ops import unary_union
from shapely.strtree import STRtree

MASK_SHA = "e8c3ea80ae5044edadaeb239249b83b26b4b585b10cdd9a134112b53d4c51c38"
JOBS_SHA = "68b2b048c7420c03494c27188f7783435e3382e2562c7299e73c9dcf0376fbe1"
EXPECTED_AREA = 1639066248.9807005
EPS = 0.01  # Square metres: tolerates floating-point polygon arithmetic only.
OUTSIDE_REASON = "outside-approved-administrative-scope"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def single_cpu():
    if os.name == "nt":
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.GetCurrentProcess.restype = ctypes.c_void_p
        kernel.GetProcessAffinityMask.argtypes = [ctypes.c_void_p,
                                                 ctypes.POINTER(ctypes.c_size_t),
                                                 ctypes.POINTER(ctypes.c_size_t)]
        kernel.SetProcessAffinityMask.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
        process = kernel.GetCurrentProcess()
        available, system = ctypes.c_size_t(), ctypes.c_size_t()
        if not kernel.GetProcessAffinityMask(process, ctypes.byref(available),
                                            ctypes.byref(system)):
            raise ctypes.WinError(ctypes.get_last_error())
        chosen = available.value & -available.value
        if not kernel.SetProcessAffinityMask(process, chosen):
            raise ctypes.WinError(ctypes.get_last_error())
    elif hasattr(os, "sched_getaffinity"):
        os.sched_setaffinity(0, {min(os.sched_getaffinity(0))})


def peak_working_set():
    if os.name != "nt":
        return None
    class Counters(ctypes.Structure):
        _fields_ = [("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong)] + [
            (name, ctypes.c_size_t) for name in
            ("PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
             "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
             "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]
    result = Counters()
    result.cb = ctypes.sizeof(result)
    kernel = ctypes.WinDLL("kernel32")
    kernel.GetCurrentProcess.restype = ctypes.c_void_p
    psapi = ctypes.WinDLL("psapi")
    psapi.GetProcessMemoryInfo.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                         ctypes.c_ulong]
    if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(),
                                     ctypes.byref(result), result.cb):
        raise RuntimeError("Cannot measure peak working set")
    return result.PeakWorkingSetSize


class Coverage:
    def __init__(self, features):
        self.geometries, self.classes = [], []
        self.outside = []
        for feature in features:
            properties = feature["properties"]
            geom = shape(feature["geometry"])
            if not geom.is_valid:
                raise ValueError("Invalid source geometry")
            # Administrative clipping can leave line-only boundary remnants.
            if geom.area == 0:
                continue
            if properties.get("reason") == OUTSIDE_REASON:
                self.outside.append(geom)
                continue
            kind = properties.get("class")
            if kind not in ("land", "sea", "unknown"):
                raise ValueError("Unsupported in-scope class: " + str(kind))
            parts = list(geom.geoms) if geom.geom_type == "MultiPolygon" else [geom]
            for part in parts:
                if part.geom_type != "Polygon":
                    raise ValueError("Source must contain polygons")
                self.geometries.append(part)
                self.classes.append(kind)
        self.tree = STRtree(self.geometries)

    def classify(self, core):
        x, z = core["coreOrigin"]
        size = core["coreSize"]
        expected = core["maskAreaSquareMetres"]
        if not all(math.isfinite(v) for v in (x, z, size, expected)):
            raise ValueError("Non-finite core bounds/area")
        if size <= 0 or expected <= 0 or expected > size * size + EPS:
            raise ValueError("Invalid expected core area")
        bounds = box(x, z, x + size, z + size)
        clips = {"land": [], "sea": [], "unknown": []}
        for index in self.tree.query(bounds, predicate="intersects"):
            piece = self.geometries[index].intersection(bounds)
            if piece.area > 0:
                clips[self.classes[index]].append(piece)
        areas = {kind: math.fsum(g.area for g in pieces)
                 for kind, pieces in clips.items()}
        all_pieces = [g for pieces in clips.values() for g in pieces]
        covered = unary_union(all_pieces).area if all_pieces else 0.0
        overlap = max(0.0, math.fsum(areas.values()) - covered)
        if overlap > EPS:
            raise ValueError("Overlapping classes in core " + core["id"])
        if covered > expected + EPS:
            raise ValueError("Classified area exceeds administrative core scope")
        gap = max(0.0, expected - covered)
        # Outside-scope unknown polygons are intentionally absent from this total.
        areas["unknown"] += gap
        if areas["unknown"] > EPS:
            classification = "unknown"
        elif areas["land"] > EPS and areas["sea"] > EPS:
            classification = "mixed_coast"
        elif areas["land"] > EPS:
            classification = "coastline_side_land"
        elif areas["sea"] > EPS:
            classification = "pure_sea"
        else:
            classification = "unknown"
        return {
            "id": core["id"], "coreOrigin": [x, z], "coreSize": size,
            "boundsXZ": [x, z, x + size, z + size],
            "classification": classification,
            "scopeAreaSquareMetres": expected,
            "areaSquareMetres": areas,
            "inScopeFraction": {kind: area / expected for kind, area in areas.items()},
            "unclassifiedGapSquareMetres": gap,
            "overlapSquareMetres": overlap,
            "areaResidualSquareMetres": expected - math.fsum(areas.values()),
            "outsideScopeAreaSquareMetres": size * size - expected,
        }


def feature(kind, geometry, reason=None):
    return {"properties": {"class": kind, "reason": reason},
            "geometry": mapping(geometry)}


class AreaTests(unittest.TestCase):
    core = {"id": "test", "coreOrigin": [0, 0], "coreSize": 10,
            "maskAreaSquareMetres": 100}
    def test_mixed_and_boundary_clipping(self):
        result = Coverage([feature("land", box(-10, -10, 4, 20)),
                           feature("sea", box(4, -10, 20, 20))]).classify(self.core)
        self.assertEqual(result["classification"], "mixed_coast")
        self.assertEqual(result["areaSquareMetres"], {"land": 40, "sea": 60, "unknown": 0})
    def test_gap_becomes_unknown(self):
        result = Coverage([feature("land", box(0, 0, 4, 10))]).classify(self.core)
        self.assertEqual(result["classification"], "unknown")
        self.assertEqual(result["unclassifiedGapSquareMetres"], 60)
        self.assertEqual(sum(result["areaSquareMetres"].values()), 100)
    def test_outside_scope_is_not_unknown(self):
        core = dict(self.core, maskAreaSquareMetres=40)
        result = Coverage([feature("sea", box(0, 0, 4, 10)),
                           feature("unknown", box(4, 0, 10, 10), OUTSIDE_REASON)]).classify(core)
        self.assertEqual(result["classification"], "pure_sea")
        self.assertEqual(result["areaSquareMetres"]["unknown"], 0)
        self.assertEqual(result["outsideScopeAreaSquareMetres"], 60)
    def test_explicit_unknown(self):
        result = Coverage([feature("unknown", box(0, 0, 10, 10))]).classify(self.core)
        self.assertEqual(result["areaSquareMetres"]["unknown"], 100)
    def test_overlap_rejected(self):
        with self.assertRaisesRegex(ValueError, "Overlapping"):
            Coverage([feature("land", box(0, 0, 10, 10)),
                      feature("sea", box(0, 0, 1, 10))]).classify(self.core)
    def test_scope_overflow_rejected(self):
        with self.assertRaisesRegex(ValueError, "exceeds"):
            Coverage([feature("land", box(0, 0, 10, 10))]).classify(
                dict(self.core, maskAreaSquareMetres=40))
    def test_boundary_remnant_has_no_area(self):
        from shapely.geometry import LineString
        result = Coverage([feature("land", box(0, 0, 10, 10)),
                           feature("sea", LineString([(0, 0), (0, 10)]))]).classify(self.core)
        self.assertEqual(result["areaSquareMetres"]["land"], 100)
        self.assertEqual(result["areaSquareMetres"]["sea"], 0)
    def test_land_class(self):
        self.assertEqual(Coverage([feature("land", box(0, 0, 10, 10))])
                         .classify(self.core)["classification"], "coastline_side_land")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mask", type=Path)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--expected-mask-sha256", default=MASK_SHA,
                        help="Explicit immutable mask hash; defaults to national-v1")
    parser.add_argument("--prior-geometry-audit", type=Path,
                        help="Prior v1 audit provenance only; does not approve this mask")
    parser.add_argument("--jobs", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    single_cpu()
    if args.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(AreaTests)
        if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
            raise SystemExit(1)
        return
    if not all((args.mask, args.receipt, args.jobs, args.output)):
        parser.error("--mask, --receipt, --jobs and --output are required")
    if args.output.name != "core-coast-classification.json":
        parser.error("output must be named core-coast-classification.json")
    if args.output.exists():
        parser.error("refusing to overwrite an existing artifact")
    start = time.monotonic()
    raw = {key: path.read_bytes() for key, path in
           (("mask", args.mask), ("receipt", args.receipt), ("jobs", args.jobs))}
    hashes = {key: sha(value) for key, value in raw.items()}
    if hashes["mask"] != args.expected_mask_sha256 or hashes["jobs"] != JOBS_SHA:
        raise ValueError("Input differs from pinned national candidate")
    mask, receipt, jobs = (json.loads(raw[key]) for key in ("mask", "receipt", "jobs"))
    if mask.get("coordinate_space") != "minecraft_xz":
        raise ValueError("Mask is not in Minecraft XZ coordinates")
    if receipt.get("status") != "PASS":
        raise ValueError("Source receipt did not pass")
    if receipt["scopeSha256"] != jobs["maskSha256"]:
        raise ValueError("Administrative scope hashes disagree")
    if not any(f["name"] == args.mask.name and f["sha256"] == hashes["mask"]
               for f in receipt["files"]):
        raise ValueError("Receipt does not bind supplied mask")
    cores = jobs["expectedCores"]
    if len(cores) != 1716 or jobs["jobCount"] != len(cores):
        raise ValueError("Unexpected core count")
    ids, tiles = set(), set()
    for core in cores:
        origin = tuple(core["coreOrigin"])
        if core["id"] in ids or origin in tiles or core["coreSize"] != 1024:
            raise ValueError("Duplicate core or unexpected core size")
        if any(value % 1024 for value in origin):
            raise ValueError("Core origin is not on the shared 1024 grid")
        ids.add(core["id"])
        tiles.add(origin)
    expected_area = math.fsum(c["maskAreaSquareMetres"] for c in cores)
    if abs(expected_area - EXPECTED_AREA) > EPS:
        raise ValueError("Expected core areas do not match national scope")
    if abs(receipt["scopeAreaSquareMetres"] - expected_area) > EPS:
        raise ValueError("Receipt scope area does not match core scope")
    prior_audit = None
    if args.prior_geometry_audit:
        audit_raw = args.prior_geometry_audit.read_bytes()
        audit = json.loads(audit_raw)
        national_audit = audit["national_face_audit"]
        source_file = next(f for f in receipt["files"] if f["name"] == "coast-source.json")
        if national_audit.get("status") != "PASS":
            raise ValueError("Prior national source-geometry audit did not pass")
        if national_audit["source_json_sha256"] != source_file["sha256"]:
            raise ValueError("Prior audit and current mask have different source geometry")
        if audit["source"]["sha256"] != receipt["source"]["sha256"]:
            raise ValueError("Prior audit and current receipt have different source PBFs")
        prior_audit = {
            "status": "PASS", "auditSha256": sha(audit_raw),
            "sourceJsonSha256": source_file["sha256"],
            "appliesToMaskSha256": MASK_SHA,
            "qualification": "Prior national-v1 source-geometry audit only; source receipt assertions and raster/world coverage are not independently approved",
        }
    classifier = Coverage(mask["features"])
    results = [classifier.classify(core) for core in cores]
    totals = {kind: math.fsum(c["areaSquareMetres"][kind] for c in results)
              for kind in ("land", "sea", "unknown")}
    gap = math.fsum(c["unclassifiedGapSquareMetres"] for c in results)
    residual = expected_area - math.fsum(totals.values())
    if abs(residual) > EPS:
        raise ValueError("National area accounting residual exceeds tolerance")
    for kind in ("land", "sea"):
        if abs(totals[kind] - receipt["areaSquareMetres"][kind]) > EPS:
            raise ValueError("Core classification does not reproduce receipt " + kind)
    peak = peak_working_set()
    if peak is not None and peak > 256 * 1024 * 1024:
        raise ValueError("Peak working set exceeded 256 MiB")
    artifact = {
        "schemaVersion": 1, "status": "CANDIDATE",
        "independentNationalAudit": "pending",
        "geometryAudit": {
            "priorSourceGeometry": prior_audit,
            "currentMaskNormalization": {
                "status": "pending", "maskSha256": hashes["mask"],
                "discardedZeroAreaScopeRemnantsFromReceipt": receipt.get("discardedZeroAreaScopeRemnants"),
            },
            "currentMaskIndependentAudit": "pending",
        },
        "purpose": "acquisition/classification coverage; not generated-world coverage",
        "landMeaning": "coastline land side, including reservoirs and inland water until a separate inland-water adapter; not official dry-land area",
        "method": "continuous polygon intersections with 1024m core bounds; outside-approved-administrative-scope features excluded; gaps reconciled to hash-bound administrative core areas",
        "coordinateSpace": "minecraft_xz",
        "worldTransform": jobs["worldTransform"],
        "scopeSha256": jobs["maskSha256"],
        "sources": {key: {"name": getattr(args, key).name, "sha256": hashes[key],
                          "bytes": len(raw[key])} for key in raw},
        "sourcePbfSha256": receipt["source"]["sha256"],
        "toolSha256": sha(Path(__file__).read_bytes()),
        "bounds": {"sourceBoundsEN": receipt["boundsEN"],
                   "approvedScopeBoundsXZ": jobs["worldBounds"]},
        "coreCount": len(results),
        "classificationCounts": dict(sorted(Counter(c["classification"] for c in results).items())),
        "scopeAreaSquareMetres": expected_area,
        "areaSquareMetres": totals,
        "unclassifiedGapSquareMetres": gap,
        "areaResidualSquareMetres": residual,
        "maximumCoreResidualSquareMetres": max(abs(c["areaResidualSquareMetres"]) for c in results),
        "outsideScopeAreaSquareMetres": math.fsum(c["outsideScopeAreaSquareMetres"] for c in results),
        "toleranceSquareMetres": EPS,
        "checks": {"status": "PASS", "expectedCoreCount": 1716,
                   "sourceHashesBound": True, "administrativeScopeHashBound": True,
                   "coreAreaConservation": True, "nationalAreaConservation": True,
                   "receiptClassAreasReproduced": True},
        "runtime": {"elapsedSeconds": time.monotonic() - start, "cpuLimit": 1,
                    "peakWorkingSetBytes": peak},
        "cores": results,
    }
    payload = (json.dumps(artifact, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("xb") as handle:
        handle.write(payload)
    print(json.dumps({"artifactSha256": sha(payload), "bytes": len(payload),
                      "classificationCounts": artifact["classificationCounts"],
                      "scopeAreaSquareMetres": expected_area,
                      "areaSquareMetres": totals,
                      "unclassifiedGapSquareMetres": gap,
                      "areaResidualSquareMetres": residual,
                      "runtime": artifact["runtime"]}, indent=2))


if __name__ == "__main__":
    main()
