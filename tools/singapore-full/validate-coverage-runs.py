#!/usr/bin/env python3
"""Independent bounded run-mask oracle. Samples global block centres, never tile origins."""
import argparse
import hashlib
import json
import sys
from collections import Counter
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import Point, shape
from shapely.ops import transform, unary_union
from shapely.prepared import prep

SAMPLE_CONVENTION = "global block centre (x+0.5,z+0.5)"
MAX_LINE_BYTES = 1024 * 1024


class AuditFailure(ValueError):
    pass


class LimitReached(AuditFailure):
    pass


def digest(path):
    value = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


class Budget:
    def __init__(self, records, byte_limit):
        if type(records) is not int or records <= 0 or type(byte_limit) is not int or byte_limit <= 0:
            raise AuditFailure("record and byte limits must be positive integers")
        self.max_records, self.max_bytes = records, byte_limit
        self.records = self.bytes = 0

    def add_bytes(self, count):
        if self.bytes + count > self.max_bytes:
            raise LimitReached("combined input/output byte limit reached before EOF")
        self.bytes += count

    def add_record(self):
        if self.records >= self.max_records:
            raise LimitReached("combined input/output record limit reached before EOF")
        self.records += 1


def load_geometry(path, expected_sha256, crs):
    if Path(path).stat().st_size > 32 * 1024 * 1024:
        raise AuditFailure("mask exceeds bounded 32 MiB geometry limit")
    actual = digest(path)
    if not expected_sha256 or actual.lower() != expected_sha256.lower():
        raise AuditFailure("mask/exclusion SHA256 absent or mismatched: " + str(path))
    document = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    geometries = []
    def visit(item):
        if item.get("type") == "FeatureCollection":
            for feature in item["features"]:
                visit(feature)
        elif item.get("type") == "Feature":
            visit(item["geometry"])
        else:
            geometry = shape(item)
            if geometry.geom_type not in ("Polygon", "MultiPolygon") or not geometry.is_valid or geometry.is_empty:
                raise AuditFailure("mask/exclusion requires valid nonempty polygons")
            geometries.append(geometry)
    visit(document)
    if not geometries:
        raise AuditFailure("empty polygon collection")
    geometry = unary_union(geometries)
    if crs == "wgs84":
        project = Transformer.from_crs("EPSG:4326", "EPSG:3414", always_xy=True).transform
        geometry = transform(project, geometry)
        geometry = transform(lambda e, n, z=None: (e, 60000 - n), geometry)
    elif crs == "world-xz":
        x0, z0, x1, z1 = geometry.bounds
        if (-180 <= x0 <= x1 <= 180 and -90 <= z0 <= z1 <= 90) or "4326" in json.dumps(document.get("crs", {})):
            raise AuditFailure("world-xz mask appears to contain unprojected WGS84 coordinates")
    else:
        raise AuditFailure("coordinate convention must be explicit: wgs84 or world-xz")
    return geometry, actual


def membership(country, exclusions):
    country, exclusions = prep(country), prep(exclusions)
    @lru_cache(maxsize=65536)
    def classify(x, z):
        point = Point(x + 0.5, z + 0.5)
        # Exclusion precedence includes its boundary, even when derived.covers
        # also returns true on that shared boundary.
        if exclusions.covers(point):
            return "foreignExclusion"
        if not country.covers(point):
            return "outsideCountry"
        return None
    return classify


def run_records(path, expected_sha256, budget, receipt):
    path = Path(path).resolve()
    receipt.update(path=str(path), expectedSha256=expected_sha256, sha256Verified=False,
                   complete=False, records=0)
    before = path.stat()
    value = hashlib.sha256()
    with path.open("rb") as stream:
        line_number = 0
        while True:
            raw = stream.readline(MAX_LINE_BYTES + 1)
            if not raw:
                break
            line_number += 1
            if len(raw) > MAX_LINE_BYTES:
                raise LimitReached("run line exceeds 1 MiB bound")
            budget.add_bytes(len(raw))
            value.update(raw)
            if not raw.strip():
                continue
            budget.add_record()
            run = json.loads(raw.decode("utf-8-sig"), parse_constant=lambda value: (_ for _ in ()).throw(AuditFailure("nonfinite JSON constant")))
            if not isinstance(run, dict) or any(type(run.get(k)) is not int for k in ("x", "z", "yMin", "yMax")):
                raise AuditFailure("run requires integer x,z,yMin,yMax: %s:%d" % (path, line_number))
            if run["yMin"] >= run["yMax"]:
                raise AuditFailure("run yMin must be below exclusive yMax")
            receipt["records"] += 1
            # Compare unmodified JSON payloads, allowing newline normalization and
            # legal record reordering. Multiplicity is preserved by Counter.
            payload_sha = hashlib.sha256(raw.rstrip(b"\r\n")).digest()
            yield run, payload_sha, line_number
    after = path.stat()
    receipt.update(complete=True, actualSha256=value.hexdigest(), bytes=before.st_size)
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise AuditFailure("run file changed during audit")
    receipt["sha256Verified"] = bool(expected_sha256) and value.hexdigest().lower() == expected_sha256.lower()
    if not receipt["sha256Verified"]:
        raise AuditFailure("run SHA256 absent or mismatched: " + str(path))


def audit(args):
    report = dict(schemaVersion=1, auditKind="independent-run-mask-oracle",
                  auditedUtc=datetime.now(timezone.utc).isoformat(), mode=args.mode,
                  sampling=SAMPLE_CONVENTION,
                  grid=dict(crs="EPSG:3414", x="easting", z="60000-northing", blocksPerMeter=1),
                  boundaryPolicy="derived.covers(center) and not exclusions.covers(center); exclusion boundary takes precedence",
                  recordComparison="SHA256 multiset of unchanged JSON payload bytes, ignoring CRLF/LF terminators; duplicate counts retained; order not checked",
                  inputs=[], output={}, layers={}, blockedInputReasons={}, violationSamples=[],
                  inputRuns=0, expectedRetainedRuns=0, outputRuns=0, outsideOutputRuns=0,
                  containmentAccepted=False, exactFilterOnProvidedInputsAccepted=False,
                  countrySemanticsAccepted=False, upstreamSourceCompletenessAccepted=False,
                  rendererAccepted=False, fullWorldAccepted=False, errors=[])
    differences = Counter()
    incomplete = False
    try:
        if args.mode not in ("exact-filter", "containment"):
            raise AuditFailure("unsupported mode")
        if args.mode == "exact-filter" and not args.inputs:
            raise AuditFailure("exact-filter requires explicitly hashed predecessor inputs")
        if args.mode == "containment" and args.inputs:
            raise AuditFailure("containment mode accepts only consumed output runs; no predecessor-completeness claim")
        budget = Budget(args.max_records, args.max_bytes)
        country, country_sha = load_geometry(args.country_mask, args.country_sha256, "world-xz")
        exclusions, foreign_sha = load_geometry(args.foreign_exclusions, args.foreign_sha256, args.foreign_crs)
        report["geometry"] = dict(countryMaskSha256=country_sha, foreignExclusionsSha256=foreign_sha,
                                  foreignInputCrs=args.foreign_crs)
        classify = membership(country, exclusions)
        paths = [Path(p).resolve() for p, sha in args.inputs]
        if len(set(paths)) != len(paths) or Path(args.output_runs).resolve() in paths:
            raise AuditFailure("predecessors must be unique and distinct from consumed output")
        for filename, expected_sha in args.inputs:
            receipt = {}
            report["inputs"].append(receipt)
            for run, payload_sha, line in run_records(filename, expected_sha, budget, receipt):
                report["inputRuns"] += 1
                layer = report["layers"].setdefault(str(run.get("layer", "unspecified")),
                                                   dict(inputRuns=0, expectedRetainedRuns=0, outputRuns=0, outsideOutputRuns=0))
                layer["inputRuns"] += 1
                reason = classify(run["x"], run["z"])
                if reason:
                    reasons = report["blockedInputReasons"]
                    reasons[reason] = reasons.get(reason, 0) + 1
                else:
                    report["expectedRetainedRuns"] += 1
                    layer["expectedRetainedRuns"] += 1
                    differences[payload_sha] += 1
        for run, payload_sha, line in run_records(args.output_runs, args.output_sha256, budget, report["output"]):
            report["outputRuns"] += 1
            layer = report["layers"].setdefault(str(run.get("layer", "unspecified")),
                                               dict(inputRuns=0, expectedRetainedRuns=0, outputRuns=0, outsideOutputRuns=0))
            layer["outputRuns"] += 1
            reason = classify(run["x"], run["z"])
            if reason:
                report["outsideOutputRuns"] += 1
                layer["outsideOutputRuns"] += 1
                if len(report["violationSamples"]) < 32:
                    report["violationSamples"].append(dict(line=line, x=run["x"], z=run["z"], reason=reason))
            if args.mode == "exact-filter":
                differences[payload_sha] -= 1
        if report["outsideOutputRuns"]:
            report["errors"].append("consumed output contains outside-country or excluded columns")
        if args.mode == "exact-filter":
            report["missingExpectedRuns"] = sum(n for n in differences.values() if n > 0)
            report["unexpectedOutputRuns"] = -sum(n for n in differences.values() if n < 0)
            if any(differences.values()):
                report["errors"].append("output payload multiset differs from independently filtered predecessors")
            if not report["inputRuns"]:
                report["errors"].append("empty predecessors provide no positive filter evidence")
        elif not report["outputRuns"]:
            report["errors"].append("empty output provides no positive containment evidence")
        report["containmentAccepted"] = not report["errors"]
        report["exactFilterOnProvidedInputsAccepted"] = args.mode == "exact-filter" and not report["errors"]
    except LimitReached as error:
        incomplete = True
        report["errors"].append(str(error))
    except (OSError, ValueError, KeyError, TypeError) as error:
        report["errors"].append(str(error))
    report["status"] = "INCOMPLETE" if incomplete else ("FAIL" if report["errors"] else "PASS")
    report["complete"] = not incomplete and all(r.get("complete") and r.get("sha256Verified")
                                               for r in report["inputs"] + [report["output"]])
    report["limits"] = dict(maxRecords=args.max_records, maxBytes=args.max_bytes,
                           scope="combined predecessor and output reads; hashes are verified only at EOF")
    report["scope"] = ("Exact membership and unchanged payload multiplicity for the explicitly supplied predecessor files only; no upstream source completeness proof."
                       if args.mode == "exact-filter" else
                       "Containment of consumed runs only; no unmasked predecessor exists or was supplied, so filter completeness is unproven.")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("exact-filter", "containment"), default="exact-filter")
    parser.add_argument("--input", dest="inputs", action="append", nargs=2, default=[], metavar=("PATH", "SHA256"))
    parser.add_argument("--output-runs", required=True)
    parser.add_argument("--output-sha256", required=True)
    parser.add_argument("--country-mask", required=True)
    parser.add_argument("--country-sha256", required=True)
    parser.add_argument("--foreign-exclusions", required=True)
    parser.add_argument("--foreign-sha256", required=True)
    parser.add_argument("--foreign-crs", choices=("world-xz", "wgs84"), required=True)
    parser.add_argument("--max-records", type=int, default=10000)
    parser.add_argument("--max-bytes", type=int, default=32 * 1024 * 1024)
    parser.add_argument("--out", required=True, help="new immutable report file")
    args = parser.parse_args()
    protected = [args.output_runs, args.country_mask, args.foreign_exclusions] + [p for p, sha in args.inputs]
    if Path(args.out).resolve() in {Path(p).resolve() for p in protected}:
        parser.error("report must not overwrite an input artifact")
    result = audit(args)
    with open(args.out, "x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, indent=2, allow_nan=False)
        stream.write("\n")
    print(json.dumps({k: result[k] for k in ("status", "inputRuns", "outputRuns", "outsideOutputRuns")}))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
