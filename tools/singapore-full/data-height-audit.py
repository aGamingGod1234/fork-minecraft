"""Audit OSM building height provenance without converting missing data to fact.

Standard library only. Reads a completed data-extract.py output, streams JSON,
and emits one compact record per tagged building or building part.
"""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import time
import tempfile

NUMBER = r"(?:\d+(?:\.\d+)?|\.\d+)"
METRIC = re.compile(rf"({NUMBER})(?:\s*(m|ft))?", re.I)
IMPERIAL = re.compile(rf"({NUMBER})'(?:(\d+(?:\.\d+)?)\")?")
FIELDS = ("height", "height:accuracy", "source:height", "building:levels",
          "building:min_level", "building:levels:underground", "min_height",
          "roof:height", "roof:levels", "building:height", "est_height",
          "building:material", "building:colour", "roof:material", "roof:colour")
CATEGORIES = ("mapped-valid-explicit", "inferred-levels", "missing", "invalid")


def measure(raw, allow_zero=False):
    """Return metres/error/warnings; do not guess ranges or decimal commas."""
    if raw is None:
        return None, None, []
    value = str(raw).strip()
    warnings = []
    match = IMPERIAL.fullmatch(value)
    if match:
        feet = float(match[1])
        inches = float(match[2] or 0)
        if inches >= 12:
            return None, "inches-must-be-less-than-12", []
        result = feet * 0.3048 + inches * 0.0254
    else:
        match = METRIC.fullmatch(value)
        if not match:
            return None, "unsupported-height-syntax", []
        result = float(match[1]) * (0.3048 if (match[2] or "").lower() == "ft" else 1)
        if match[2] and not re.search(r"\s+(m|ft)$", value, re.I):
            warnings.append("unit-spacing-noncanonical")
    if not math.isfinite(result):
        return None, "nonfinite-height", warnings
    if not (result >= 0 if allow_zero else result > 0):
        return None, "nonpositive-height", warnings
    return result, None, warnings


def level_count(raw):
    if raw is None:
        return None, None
    text = str(raw).strip()
    # Fractional floors, ranges, lists and signed level references are not counts.
    if not re.fullmatch(r"\d+", text):
        return None, "levels-not-nonnegative-integer"
    try:
        result = int(text)
        if not math.isfinite(float(result)):
            return None, "levels-exceed-numeric-range"
        return result, None
    except (ValueError, OverflowError):
        return None, "levels-exceed-numeric-range"


def classify(tags, metres_per_level=3.0):
    warnings, errors = [], []
    height, height_error, height_warnings = measure(tags.get("height"))
    minimum, minimum_error, minimum_warnings = measure(tags.get("min_height"), True)
    levels, levels_error = level_count(tags.get("building:levels"))
    min_level, min_level_error = level_count(tags.get("building:min_level"))
    underground, underground_error = level_count(tags.get("building:levels:underground"))
    roof, roof_error, roof_warnings = measure(tags.get("roof:height"), True)
    roof_levels, roof_levels_error = level_count(tags.get("roof:levels"))
    warnings.extend(height_warnings + minimum_warnings + roof_warnings)
    for key, error in (("height", height_error), ("min_height", minimum_error),
                       ("building:levels", levels_error), ("building:min_level", min_level_error),
                       ("building:levels:underground", underground_error),
                       ("roof:height", roof_error), ("roof:levels", roof_levels_error)):
        if error:
            errors.append(key + ":" + error)
    if levels is not None and min_level is not None and levels <= min_level:
        errors.append("building:min_level:not-below-levels")
    if height is not None and minimum is not None and minimum >= height:
        errors.append("min_height:not-below-height")
    if height is not None and roof is not None and roof > height:
        errors.append("roof:height:exceeds-total-height")
    if height is not None and roof is not None and minimum is not None and roof > height - minimum:
        errors.append("roof:height:exceeds-object-vertical-extent")
    if tags.get("building:height") is not None:
        warnings.append("nonstandard-building:height-not-used")
    if tags.get("est_height") is not None:
        warnings.append("est_height-not-promoted-to-mapped-height")
    if height and height > 350:
        warnings.append("height-above-350m-review-required-no-clamping")
    if levels is not None and levels > 100:
        warnings.append("levels-above-100-review-required-no-clamping")
    estimate = None
    roof_basis = None
    # A malformed explicit height remains invalid; do not conceal it by fallback.
    if "height" in tags:
        category = "mapped-valid-explicit" if height is not None else "invalid"
    elif levels is not None and levels > 0:
        category = "inferred-levels"
        roof_extra = roof if roof is not None else (roof_levels or 0) * metres_per_level
        roof_basis = "roof:height" if roof is not None else "roof:levels-assumption" if roof_levels else "roof-height-unknown"
        estimate = levels * metres_per_level + roof_extra
        if not math.isfinite(estimate):
            category = "invalid"
            estimate = None
            errors.append("building:levels:estimate-exceeds-numeric-range")
        else:
            estimate = round(estimate, 6)
        warnings.append("storey-height-assumed-not-measured")
        if roof_basis == "roof-height-unknown":
            warnings.append("roof-height-missing-estimate-may-understate-total")
    elif levels == 0 and underground and underground > 0:
        category = "missing"
        warnings.append("underground-only-no-above-ground-height-inferred")
    elif "building:levels" in tags:
        category = "invalid"
        if levels == 0:
            errors.append("building:levels:zero-without-underground-count")
    else:
        category = "missing"
    # Bad auxiliary fields are retained for correction without hiding a valid
    # independently mapped top height. Geometry conflicts block automatic use.
    geometry_errors = [x for x in errors if x.startswith("min_height:") or
                       x.startswith("building:min_level:") or x.endswith("exceeds-total-height") or
                       x.endswith("exceeds-object-vertical-extent")]
    if geometry_errors:
        category = "invalid"
    if category == "inferred-levels" and (roof_error or roof_levels_error):
        category = "invalid"
    if estimate is not None and minimum is not None and minimum >= estimate:
        category = "invalid"
        errors.append("min_height:not-below-estimated-height")
    return {"classification": category, "heightMetres": height,
            "estimatedHeightMetres": estimate, "levels": levels,
            "minHeightMetres": minimum, "minLevel": min_level,
            "roofHeightMetres": roof, "roofLevels": roof_levels,
            "roofEstimateBasis": roof_basis, "undergroundLevels": underground,
            "warnings": sorted(set(warnings)), "errors": errors}


def elements(path):
    """Bounded-memory reader for the known Overpass elements array."""
    decoder = json.JSONDecoder()
    with path.open(encoding="utf-8") as stream:
        buffer = stream.read(65536)
        header = re.search(r'"elements"\s*:\s*\[', buffer)
        if not header:
            raise ValueError("Missing Overpass elements array")
        json.loads(buffer[:header.start()] + '"elements": []}')
        position = header.end()
        state = "first"
        while True:
            while position < len(buffer) and buffer[position] in " \r\n\t":
                position += 1
            if position == len(buffer):
                buffer = stream.read(1048576)
                position = 0
                if not buffer:
                    raise ValueError("Truncated elements array")
                continue
            if position < len(buffer) and buffer[position] == "]":
                if state == "value":
                    raise ValueError("Trailing array comma")
                tail = buffer[position + 1:] + stream.read(65536)
                if stream.read(1) or not re.fullmatch(r"\s*}\s*", tail):
                    raise ValueError("Missing or malformed Overpass object terminator")
                return
            if state == "separator":
                if buffer[position] != ",":
                    raise ValueError("Missing array comma")
                position += 1
                state = "value"
                continue
            try:
                item, position = decoder.raw_decode(buffer, position)
            except json.JSONDecodeError:
                extra = stream.read(1048576)
                if not extra:
                    raise ValueError("Truncated or malformed elements array")
                buffer = buffer[position:] + extra
                position = 0
                continue
            if not isinstance(item, dict):
                raise ValueError("An Overpass element must be an object")
            state = "separator"
            yield item


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def self_test():
    assert measure("300")[0] == 300
    assert measure("12.25 m")[0] == 12.25
    assert measure("100 ft")[0] == 30.48
    assert math.isclose(measure('7\'4"')[0], 2.2352)
    assert measure("4m")[2] == ["unit-spacing-noncanonical"]
    for raw in ("", "-1", "0", "NaN", "inf", "1,5", "10;20", "10-20", "about 20", '7\'12"'):
        assert measure(raw)[1], raw
    assert classify({"height": "10", "roof:height": "2"})["heightMetres"] == 10
    assert classify({"height": "bad", "building:levels": "2"})["classification"] == "invalid"
    assert classify({"building:levels": "2"})["estimatedHeightMetres"] == 6
    assert classify({"building:levels": "2", "roof:height": "1"})["estimatedHeightMetres"] == 7
    assert classify({"building:levels": "2", "roof:levels": "1"})["estimatedHeightMetres"] == 9
    assert classify({"building:levels": "10", "building:min_level": "3"})["estimatedHeightMetres"] == 30
    assert classify({"building:levels": "2.5"})["classification"] == "invalid"
    assert classify({"building:levels": "0", "building:levels:underground": "2"})["classification"] == "missing"
    assert classify({"height": "10", "min_height": "12"})["classification"] == "invalid"
    assert classify({"building:levels": "3", "building:min_level": "3"})["classification"] == "invalid"
    assert classify({"height": "15", "building:levels": "bad"})["classification"] == "mapped-valid-explicit"
    assert classify({"ele": "300", "maxheight": "20", "est_height": "50"})["classification"] == "missing"
    assert measure("9" * 400)[1] == "nonfinite-height"
    assert classify({"building:levels": "3", "min_height": "20"})["classification"] == "invalid"
    assert classify({"height": "10", "min_height": "6", "roof:height": "5"})["classification"] == "invalid"
    assert classify({"building:levels": "9" * 400})["classification"] == "invalid"
    assert classify({"building:levels": "9" * 308})["classification"] == "invalid"
    assert classify({"height": "0.0000001"})["heightMetres"] > 0
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "source.json"
        expected = [{"type": "node", "id": 1, "tags": {"name": "x" * 70000}},
                    {"type": "way", "id": 2, "tags": {"building": "yes", "height": "12"}}]
        path.write_text(json.dumps({"elements": expected}), encoding="utf-8")
        assert list(elements(path)) == expected
        malformed = ('{"elements":[{"type":"way","id":', '{"elements":[{"id":1}]',
                     '{"elements":[{"id":1}{"id":2}]}', '{"elements":[,{"id":1}]}',
                     '{"elements":[{"id":1},]}')
        for text in malformed:
            path.write_text(text, encoding="utf-8")
            try:
                list(elements(path))
            except ValueError:
                pass
            else:
                raise AssertionError("Malformed source accepted: " + text)
    print("Height parser: boundary, precedence, streaming and truncation checks passed.")


def audit(source, report_path, output, metres_per_level):
    if not report_path.is_file():
        raise SystemExit("Extract report absent: source is not committed complete; retry after extraction.")
    report = json.loads(report_path.read_text(encoding="utf-8-sig"))
    if not report.get("referenceComplete") or any(report.get("missingReferences", {}).values()):
        raise SystemExit("Source reference-completeness gate failed.")
    entry = next((x for x in report.get("files", []) if x["name"] == source.name), None)
    if not entry or source.stat().st_size != entry["bytes"] or sha(source).lower() != entry["sha256"].lower():
        raise SystemExit("Source does not match completed extract receipt.")
    if output.exists():
        raise SystemExit("Select a new output directory; audit outputs are immutable.")
    output.mkdir(parents=True)
    counts, kinds, tag_counts, problems = Counter(), Counter(), Counter(), Counter()
    examples, seen, rows = [], set(), 0
    target = output / "building-heights.jsonl"
    started = time.time()
    with target.open("w", encoding="utf-8") as stream:
        for item in elements(source):
            tags = item.get("tags", {})
            building = tags.get("building") not in (None, "no", "")
            part = tags.get("building:part") not in (None, "no", "")
            if not (building or part):
                continue
            identity = (item["type"], item["id"])
            if identity in seen:
                raise ValueError("Duplicate OSM object identity: " + str(identity))
            seen.add(identity)
            kind = "building-and-part" if building and part else "building" if building else "building-part"
            row = {"osmType": item["type"], "osmId": item["id"], "objectKind": kind,
                   **classify(tags, metres_per_level), "tags": {k: tags[k] for k in FIELDS if k in tags}}
            if item["type"] == "node":
                row["warnings"].append("building-point-has-no-footprint")
            rows += 1
            counts[row["classification"]] += 1
            kinds[kind] += 1
            for key in row["tags"]:
                tag_counts[key] += 1
            for error in row["errors"]:
                problems[error] += 1
            if row["classification"] == "invalid" and len(examples) < 30:
                examples.append(row)
            stream.write(json.dumps(row, separators=(",", ":"), ensure_ascii=False) + "\n")
    summary = {"schemaVersion": 1, "sourceSha256": entry["sha256"],
               "extractReportSha256": sha(report_path), "maskSha256": report.get("maskSha256"),
               "sourceObjectCount": rows, "classifications": {k: counts[k] for k in CATEGORIES},
               "objectKinds": dict(kinds), "tagCounts": dict(tag_counts), "errors": dict(problems),
               "invalidExamples": examples, "assumedMetresPerLevel": metres_per_level,
               "elapsedSeconds": round(time.time() - started, 3),
               "files": [{"name": target.name, "bytes": target.stat().st_size, "sha256": sha(target)}],
               "limits": ["Counts are OSM objects, not deduplicated physical buildings; outline and parts may overlap.",
                          "Mapped explicit means a parseable OSM tag, not independently surveyed or exact.",
                          "Selection uses Singapore administrative mask; crossing whole geometries still require clipping.",
                          "Missing heights remain null. Inferred storey heights are estimates, not 1:1 facts.",
                          "Material/colour tags do not establish exact facades.",
                          "URA height controls are not observed heights; DSM elevation is not building height."]}
    (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps({k: summary[k] for k in ("sourceObjectCount", "classifications", "objectKinds", "elapsedSeconds")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--extract-report", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--metres-per-level", type=float, default=3.0)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if not all((args.source, args.extract_report, args.output_dir)) or not 0 < args.metres_per_level < 10:
            parser.error("Require source, extract report, output directory, and sensible explicit storey assumption")
        audit(args.source, args.extract_report, args.output_dir, args.metres_per_level)
