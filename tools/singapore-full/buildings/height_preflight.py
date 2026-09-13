"""Stream a height-only OSM inventory through FORK's frozen height normalizer.

This is an admission preflight, not a world builder. Synthetic unit geometry is
used only to invoke shared height semantics; no source footprint, references,
projection, building type, terrain, or Minecraft vertical range is validated.
"""
from dataclasses import asdict
from collections import Counter
import argparse
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path
import sys
import time

from footprints import FootprintError, normalize_feature, parse_length_m


class SourceError(ValueError):
    """Corrupt inventory/schema; abort the entire stream, not just one object."""


def _schema(record, line_number):
    if not isinstance(record, dict):
        raise SourceError(f"line {line_number}: record must be an object")
    kind, identity = record.get("osmType"), record.get("osmId")
    if kind not in ("node", "way", "relation") or isinstance(identity, bool) or not isinstance(identity, int) or identity <= 0:
        raise SourceError(f"line {line_number}: missing/invalid typed OSM identity")
    for key in ("objectKind", "classification"):
        if not isinstance(record.get(key), str) or not record[key]:
            raise SourceError(f"line {line_number}: missing/invalid {key}")
    if not isinstance(record.get("tags"), dict):
        raise SourceError(f"line {line_number}: tags must be an object")
    for key in ("warnings", "errors"):
        value = record.get(key, [])
        if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
            raise SourceError(f"line {line_number}: {key} must be an array of strings")


def _tag_issues(tags):
    """Inspect supplied tags even when precedence would otherwise skip them."""
    issues = []
    for key in ("height", "min_height", "roof:height", "building:levels",
                "building:min_level", "roof:levels", "building:levels:underground"):
        if key not in tags:
            continue
        try:
            value = tags[key]
            if isinstance(value, bool):
                raise ValueError("boolean is not a measurement")
            number = parse_length_m(value) if key in ("height", "min_height", "roof:height") else float(value)
            if not math.isfinite(number) or number < 0 or (key in ("height", "building:levels") and number == 0):
                raise ValueError("nonfinite or invalid sign/zero")
        except (ValueError, TypeError, OverflowError) as exc:
            issues.append(f"invalid-tag:{key}:{exc}")
    return issues


def assess_record(record, line_number=1):
    _schema(record, line_number)
    tags = record["tags"]
    identity = f"{record['osmType']}/{record['osmId']}"
    reasons = _tag_issues(tags)
    source_errors = record.get("errors", [])
    if record["classification"] == "invalid":
        reasons.append("source-classification:invalid")
    reasons.extend("source-error:" + error for error in source_errors)
    result = {
        "featureId": identity, "osmType": record["osmType"], "osmId": record["osmId"],
        "objectKind": record["objectKind"], "originalClassification": record["classification"],
        "sourceIssueFlags": {"warnings": record.get("warnings", []), "errors": source_errors},
        "sourceTags": tags, "heightOnly": True, "syntheticUnitGeometry": True,
        "geometryAssessment": "not-assessed; source geometry absent from inventory",
        "missingTotalHeightTags": "height" not in tags and "building:levels" not in tags,
        "normalizationStatus": "error", "heightClass": "unresolved",
        "minimumPlusDefaultSpan": False, "conflictingHeight": False,
        "height": None, "minHeight": None, "normalizationWarnings": [],
    }
    unit_feature = {"type": "Feature", "id": identity, "properties": tags,
                    "geometry": {"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]}}
    try:
        building = normalize_feature(unit_feature)
        result["height"] = asdict(building.height)
        result["minHeight"] = asdict(building.min_height)
        result["normalizationWarnings"] = list(building.warnings)
        result["normalizationStatus"] = "estimated" if building.height.estimated else "mapped"
        source = building.height.source
        result["heightClass"] = "missing" if source == "default_height" else ("estimated" if building.height.estimated else "tagged")
        result["minimumPlusDefaultSpan"] = source.endswith("+default_span")
        reasons.extend("normalizer-warning:" + warning for warning in building.warnings if warning.startswith("invalid "))
    except FootprintError as exc:
        message = str(exc)
        reasons.append("normalization-error:" + message)
        result["conflictingHeight"] = "must be below total height" in message
    # Do not change renderer policy: this flags cases for quarantine/review even
    # when the normalizer was able to produce a labelled estimated fallback.
    result["policyAction"] = "quarantine-needed" if reasons else "height-only-admissible"
    result["quarantineReasons"] = sorted(set(reasons))
    return result


def peak_memory_mib():
    if os.name == "nt":
        from ctypes import wintypes
        class Counters(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
                (name, ctypes.c_size_t) for name in ("PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                    "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]
        counters = Counters()
        counters.cb = ctypes.sizeof(counters)
        get_process = ctypes.windll.kernel32.GetCurrentProcess
        get_process.restype = wintypes.HANDLE
        get_info = ctypes.windll.psapi.GetProcessMemoryInfo
        get_info.argtypes = [wintypes.HANDLE, ctypes.c_void_p, wintypes.DWORD]
        if not get_info(get_process(), ctypes.byref(counters), counters.cb):
            raise RuntimeError("Cannot enforce process memory guard")
        return max(counters.PeakWorkingSetSize, counters.PeakPagefileUsage) / (1024 * 1024)
    import resource
    value = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return value / (1024 * 1024 if sys.platform == "darwin" else 1024)


def run_preflight(input_path, output_path, summary_path, *, memory_limit_mib=128,
                  max_line_bytes=1024 * 1024, code_revision="unknown"):
    input_path, output_path, summary_path = map(Path, (input_path, output_path, summary_path))
    if len({p.resolve() for p in (input_path, output_path, summary_path)}) != 3:
        raise ValueError("Input, per-feature output and summary paths must differ")
    if not math.isfinite(memory_limit_mib) or memory_limit_mib <= 0:
        raise ValueError("Memory limit must be positive and finite")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    partial = output_path.with_name(output_path.name + ".partial")
    if any(p.exists() for p in (output_path, summary_path, partial)):
        raise FileExistsError("Use fresh receipt paths; existing artifacts are never overwritten")
    started, digest = time.monotonic(), hashlib.sha256()
    counters = {key: Counter() for key in ("policyAction", "normalizationStatus", "heightClass", "originalClassification", "heightSource", "sourceWarnings", "sourceErrors", "quarantineReasonKinds")}
    totals = Counter()
    seen = set()
    read_bytes = 0
    peak = peak_memory_mib()
    if peak > memory_limit_mib:
        raise MemoryError("Process exceeds memory guard before scan")
    print(json.dumps({"status": "running", "pid": os.getpid(), "memoryLimitMiB": memory_limit_mib}), flush=True)
    with input_path.open("rb") as source, partial.open("x", encoding="utf-8", newline="\n") as output:
        while True:
            raw = source.readline(max_line_bytes + 1)
            if not raw:
                break
            totals["rows"] += 1
            line = totals["rows"]
            if len(raw) > max_line_bytes:
                raise SourceError(f"line {line}: exceeds bounded JSONL record size")
            digest.update(raw)
            read_bytes += len(raw)
            try:
                text = raw.decode("utf-8-sig" if line == 1 else "utf-8")
                record = json.loads(text, parse_constant=lambda value: (_ for _ in ()).throw(ValueError("nonfinite JSON constant: " + value)))
            except (ValueError, UnicodeError) as exc:
                raise SourceError(f"line {line}: malformed JSON/encoding: {exc}") from exc
            result = assess_record(record, line)
            if result["featureId"] in seen:
                raise SourceError(f"line {line}: duplicate typed source identity {result['featureId']}")
            seen.add(result["featureId"])
            for key in ("policyAction", "normalizationStatus", "heightClass", "originalClassification"):
                counters[key][result[key]] += 1
            if result["height"]:
                counters["heightSource"][result["height"]["source"]] += 1
            counters["sourceWarnings"].update(result["sourceIssueFlags"]["warnings"])
            counters["sourceErrors"].update(result["sourceIssueFlags"]["errors"])
            counters["quarantineReasonKinds"].update(reason.split(":", 1)[0] for reason in result["quarantineReasons"])
            for key in ("missingTotalHeightTags", "minimumPlusDefaultSpan", "conflictingHeight"):
                totals[key] += bool(result[key])
            output.write(json.dumps(result, sort_keys=True, separators=(",", ":")) + "\n")
            if line % 512 == 0:
                peak = max(peak, peak_memory_mib())
                if peak > memory_limit_mib:
                    raise MemoryError(f"Process peak {peak:.2f} MiB exceeds {memory_limit_mib:g} MiB")
    peak = max(peak, peak_memory_mib())
    if peak > memory_limit_mib:
        raise MemoryError("Process exceeded memory guard")
    if input_path.stat().st_size != read_bytes:
        raise SourceError("Input size changed during scan")
    summary = {"schema": "fork-height-preflight-v1", "complete": True, "heightOnly": True,
               "pid": os.getpid(), "codeRevision": code_revision, "inputBytes": read_bytes,
               "inputSha256": digest.hexdigest(), "elapsedSeconds": round(time.monotonic() - started, 3),
               "memoryGuardMiB": memory_limit_mib, "peakProcessMemoryMiB": round(peak, 3),
               "memoryGuard": "sampled every512 records; bounded1MiB record reads; single process",
               "totals": dict(totals), "counts": {key: dict(sorted(value.items())) for key, value in counters.items()},
               "notValidated": ["source footprints", "node/way/relation geometry references", "CRS/projection",
                                "building type unavailable in height-only tags", "terrain datum", "world Y range", "exact surveyed height"],
               "policy": "Quarantine-needed is a preflight flag; renderer admission policy unchanged"}
    partial.rename(output_path)
    with summary_path.open("x", encoding="utf-8") as output:
        json.dump(summary, output, indent=2)
        output.write("\n")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--summary", required=True)
    parser.add_argument("--memory-limit-mib", type=float, default=128)
    parser.add_argument("--code-revision", default="unknown")
    args = parser.parse_args()
    result = run_preflight(args.input, args.output, args.summary,
                           memory_limit_mib=args.memory_limit_mib, code_revision=args.code_revision)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
