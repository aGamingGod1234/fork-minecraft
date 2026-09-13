"""Hash-bound admission for a qualified rendered roads subset, never full coverage.

This gate supplements the independent world/structural gate. Mixed roads-run
reports may admit explicitly typed inland water; coastline still needs its oracle.
"""
from __future__ import annotations

from collections import Counter
from copy import deepcopy
import hashlib
import json
import math
from pathlib import Path

from anvil import _fs_path

FRAME = {"crs": "EPSG:3414", "x": "easting",
         "z": "60000-northing", "blocksPerMeter": 1}
KIND = "fork-rendered-source-subset-preview"
ENTRY_STATUS = "rendered_subset_preview"
BLOCKED_CODES = {"road_vertical_or_country_mask_blocked", "road_raster_blocked"}
CONTEXT_CODES = {"area_highway_not_rasterized", "unsupported_surface",
                 "coastline_not_land_mask"}
SCOPES = {"core", "halo_only", "outside_render", "unresolved",
          "lateral-impact-unresolved"}
FALSE_GATES = ("sourceComplete", "routeComplete", "fullFidelity")


class PreviewEvidenceError(ValueError):
    pass


def _require(condition, message):
    if not condition:
        raise PreviewEvidenceError(message)


def _sha(path):
    h = hashlib.sha256()
    try:
        with open(_fs_path(path), "rb") as stream:
            for data in iter(lambda: stream.read(1024 * 1024), b""):
                h.update(data)
    except (OSError, TypeError) as exc:
        raise PreviewEvidenceError(f"Cannot read evidence {path}: {exc}") from exc
    return h.hexdigest()


def _pin(path, digest, label):
    _require(isinstance(path, (str, Path)) and bool(str(path)),
             f"{label}: missing path")
    _require(isinstance(digest, str) and len(digest) == 64
             and all(c in "0123456789abcdef" for c in digest),
             f"{label}: invalid SHA256")
    _require(_sha(path) == digest, f"{label}: SHA256 mismatch")


def _json(path):
    try:
        with open(_fs_path(path), encoding="utf-8-sig") as stream:
            result = json.load(stream)
    except (OSError, ValueError, TypeError) as exc:
        raise PreviewEvidenceError(f"Invalid JSON {path}: {exc}") from exc
    _require(isinstance(result, dict), f"{path}: object required")
    return result


def _bound_json(path, digest, label):
    _pin(path, digest, label)
    return _json(path)


def _bounds(value, label):
    _require(isinstance(value, (list, tuple)) and len(value) == 4
             and all(type(v) is int for v in value),
             f"{label}: four integer bounds required")
    _require(value[0] < value[2] and value[1] < value[3],
             f"{label}: empty or reversed bounds")
    return list(value)


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def _false_gates(obj, label):
    _require(all(obj.get(key) is False for key in FALSE_GATES),
             f"{label}: completeness flags must all be false")


def validate_preview(component, entry, core_bounds, writer_hash, writer_inputs=None):
    """Validate qualified roads/inland-water coverage with every omission retained.

    entry: status=rendered_subset_preview, evidence_path, evidence_sha256.
    Evidence kind/status: fork-rendered-source-subset-preview / RENDERED_SUBSET.
    It pins reportPath/Sha256, runsPath/Sha256, classificationPath/Sha256,
    coreBounds, writerManifestSha256, sourceSha256 and exact omissions.
    writer_inputs must contain the actual consumed runs' name, sha256 and bytes.
    """
    _require(component in ("roads", "water"), "Unsupported preview component")
    _require(isinstance(entry, dict) and entry.get("status") == ENTRY_STATUS,
             "Explicit rendered_subset_preview entry required")
    core = _bounds(core_bounds, "requested core")
    evidence = _bound_json(entry.get("evidence_path"), entry.get("evidence_sha256"),
                           "preview evidence")
    _require(evidence.get("kind") == KIND and evidence.get("status") == "RENDERED_SUBSET"
             and evidence.get("component") == component
             and evidence.get("synthetic") is False,
             "Wrong preview kind/status/component or synthetic evidence")
    _require(evidence.get("coreBounds") == core, "Preview core mismatch")
    _require(evidence.get("writerManifestSha256") == writer_hash,
             "Preview writer mismatch")
    _false_gates(evidence, "preview")
    if component == "water":
        _require(evidence.get("runSource") == "inland-water",
                 "Water preview is inland-only; coastline requires its independent oracle")
    _require(evidence.get("fullWorldAccepted") is False,
             "A preview cannot accept the full world")
    report = _bound_json(evidence.get("reportPath"), evidence.get("reportSha256"), "roads report")
    audit = _bound_json(evidence.get("classificationPath"), evidence.get("classificationSha256"),
                       "classification")
    _require(audit.get("schema") == "fork.road-omission-audit.v1",
             "Unknown omission classification schema")
    _require(audit.get("core") == core, "Classification core mismatch")
    render = _bounds(audit.get("render"), "classification render")
    _require(render[0] <= core[0] < core[2] <= render[2]
             and render[1] <= core[1] < core[3] <= render[3],
             "Core outside classification render")
    _false_gates(audit.get("gates", {}), "classification")
    integrity = audit.get("integrity", {})
    required = ("sourceHashMatches", "projectionHashMatches", "referenceComplete",
                "projectedReferenceCoverageComplete", "coordinateContractValid",
                "countryMaskValid", "foreignExclusionsValid")
    _require(all(integrity.get(key) is True for key in required),
             "Source/reference/coordinate/country integrity is unproven")
    _require(integrity.get("fatalErrors") == []
             and integrity.get("missingReferences") == []
             and integrity.get("missingProjectedNodes") == [],
             "Fatal source integrity errors or missing references")
    bindings = audit.get("bindings", {})
    report_keys = {"roadsManifest": None, "source": "sourceSha256",
                   "projectedNodes": "projectedNodesSha256",
                   "countryMask": "countryMaskSha256",
                   "foreignExclusions": "foreignExclusionsSha256"}
    for key, report_key in report_keys.items():
        binding = bindings.get(key, {})
        _pin(binding.get("path"), binding.get("sha256"), "classification " + key)
        if report_key:
            _require(binding["sha256"] == report.get(report_key),
                     f"Report/classification {key} mismatch")
    _require(bindings["roadsManifest"]["sha256"] == evidence["reportSha256"],
             "Classification binds another roads report")
    source_hash = bindings["source"]["sha256"]
    quarantines = audit.get("quarantinedSourceFeatures", [])
    _require(isinstance(quarantines, list)
             and evidence.get("quarantinedSourceFeatures", []) == quarantines,
             "Exact geometry quarantine records must be preserved")
    if quarantines:
        _require(audit.get("globalSourceGeometryComplete") is False
                 and evidence.get("globalSourceGeometryComplete") is False,
                 "Geometry quarantine cannot claim complete source geometry")
    _require(not integrity.get("globalSourceGeometryInvalid") or quarantines,
             "Unclassified invalid source geometry")
    for quarantine in quarantines:
        _require(isinstance(quarantine, dict), "Malformed geometry quarantine")
        box = quarantine.get("bounds")
        _require(isinstance(box, list) and len(box) == 4
                 and all(type(v) in (int, float) and math.isfinite(v) for v in box)
                 and box[0] <= box[2] and box[1] <= box[3],
                 "Quarantine needs finite ordered source bounds")
        _require(box[2] < render[0] or box[0] > render[2]
                 or box[3] < render[1] or box[1] > render[3],
                 "Quarantined geometry may intersect the rendered world")
        _require(quarantine.get("reason") ==
                 "malformed_individual_geometry_proved_outside_render_and_unsupported"
                 and quarantine.get("sourceSha256") == source_hash
                 and quarantine.get("sourceGraphCorruptionWaived") is False
                 and quarantine.get("referenceComplete") is True
                 and quarantine.get("finiteProjection") is True
                 and quarantine.get("neverRendered") is True
                 and isinstance(quarantine.get("featureId"), str),
                 "Only explicit outside-render, never-rendered topology quarantine is allowed")

    _require(evidence.get("sourceSha256") == source_hash, "Preview source mismatch")
    _require(report.get("schema") == "fork.roads-runs.v1"
             and report.get("grid") == FRAME and report.get("tile") == render
             and report.get("countryMaskCreatesLand") is False
             and report.get("wholeSourceGeometryPreserved") is True,
             "Road report coordinate/render/country contract mismatch")
    runs_path, runs_hash = evidence.get("runsPath"), evidence.get("runsSha256")
    _pin(runs_path, runs_hash, "consumed roads runs")
    _require(report.get("outputSha256") == runs_hash, "Report runs mismatch")
    if component == "water":
        found_inland = False
        with open(_fs_path(runs_path), encoding="utf-8-sig") as stream:
            for line in stream:
                if not line.strip():
                    continue
                try:
                    run = json.loads(line)
                except ValueError as exc:
                    raise PreviewEvidenceError("Malformed inland-water run") from exc
                if isinstance(run, dict) and run.get("layer") == 30:
                    found_inland = True
                    break
        _require(found_inland, "No actual consumed layer-30 inland-water runs")
    _require(isinstance(writer_inputs, list), "Actual writer inputs required")
    size = Path(_fs_path(runs_path)).stat().st_size
    _require(any(isinstance(item, dict) and item.get("sha256") == runs_hash
                 and item.get("bytes") == size and item.get("name") == Path(runs_path).name
                 for item in writer_inputs), "Road runs were not consumed by this writer")
    omissions = audit.get("omissions")
    _require(isinstance(omissions, list) and evidence.get("omissions") == omissions,
             "Preview must preserve the complete exact classification list")
    diagnostics = report.get("diagnostics")
    _require(isinstance(diagnostics, list), "Missing actual report diagnostics")
    retained = []
    for diagnostic in diagnostics:
        _require(isinstance(diagnostic, dict), "Malformed report diagnostic")
        code = diagnostic.get("code")
        _require(diagnostic.get("fatal") is not True
                 and diagnostic.get("severity") not in ("fatal", "error"),
                 "Fatal report diagnostic")
        if code == "road_overlap_resolved":
            continue
        _require(code in BLOCKED_CODES | CONTEXT_CODES,
                 f"Unclassified diagnostic cannot be waived: {code}")
        retained.append(diagnostic)
    originals = []
    for omission in omissions:
        _require(isinstance(omission, dict), "Malformed omission")
        original = omission.get("originalDiagnostic")
        _require(isinstance(original, dict), "Missing original diagnostic")
        feature = original.get("featureId", original.get("source_id"))
        _require(isinstance(feature, str) and omission.get("featureId") == feature
                 and omission.get("diagnosticCode") == original.get("code"),
                 "Omission feature/code substitution")
        _require(omission.get("sourceSha256") == source_hash
                 and omission.get("sourceReferencesComplete") is True,
                 "Omission source/reference mismatch")
        _require(isinstance(omission.get("reason"), str) and bool(omission["reason"])
                 and isinstance(omission.get("classification"), str)
                 and bool(omission["classification"])
                 and omission.get("scope") in SCOPES
                 and isinstance(omission.get("scopeMethod"), str)
                 and bool(omission["scopeMethod"]), "Omission lacks explicit reason/scope")
        is_blocked = original["code"] in BLOCKED_CODES
        _require(omission.get("blocked") is is_blocked, "Omission blocked flag mismatch")
        is_road_omission = is_blocked or original["code"] == "area_highway_not_rasterized"
        if is_road_omission:
            allowed_reasons = {
                "road_vertical_or_country_mask_blocked": {
                    "missing_bridge_elevation", "missing_tunnel_elevation",
                    "missing_unresolved_layer_elevation"},
                "road_raster_blocked": {"unsupported_highway_corridor",
                    "unsupported_highway_construction", "unsupported_highway_raceway"},
                "area_highway_not_rasterized": {"unsupported_pedestrian_area"}}
            _require(omission["reason"] in allowed_reasons[original["code"]],
                     "Unknown omission reason cannot be waived")
            _require(omission.get("layerDomain") == "road"
                     and omission.get("previewExclusionEligible") is True,
                     "Road omission not eligible for qualified preview")
        # Ancillary warnings are retained as warnings, never counted as lost roads.
        originals.append(original)
    _require(Counter(map(_canonical, originals)) == Counter(map(_canonical, retained)),
             "Classification dropped, duplicated or changed report diagnostics")
    blocked = sum(d.get("code") in BLOCKED_CODES for d in retained)
    _require(type(report.get("blockedDiagnostics")) is int
             and report["blockedDiagnostics"] == blocked
             and audit.get("counts", {}).get("blocked") == blocked
             and evidence.get("blockedDiagnostics") == blocked,
             "Blocked diagnostic count mismatch")
    count = evidence.get("featureCount")
    _require(type(count) is int and count >= 0, "Invalid rendered feature count")
    _require(type(report.get("runCount")) is int and report["runCount"] >= 0,
             "Invalid report run count")
    return {"status": ENTRY_STATUS, "evidence_path": str(entry["evidence_path"]),
            "evidence_sha256": entry["evidence_sha256"], "source_sha256": source_hash,
            "feature_count": count, "classification_path": evidence["classificationPath"],
            "classification_sha256": evidence["classificationSha256"],
            "blocked_diagnostics": blocked, "omissions": deepcopy(omissions),
            "core_road_omissions": [deepcopy(o) for o in omissions
                if o.get("layerDomain") == "road" and o["scope"] == "core"],
            "potential_core_road_omissions": [deepcopy(o) for o in omissions
                if o.get("layerDomain") == "road" and o["scope"] in
                   ("unresolved", "lateral-impact-unresolved")],
            "sourceComplete": False, "routeComplete": False, "fullFidelity": False,
            "fullWorldAccepted": False, "waterAccepted": False,
            "component": component, "run_source": evidence.get("runSource", "roads"),
            "quarantinedSourceFeatures": deepcopy(quarantines),
            "globalSourceGeometryComplete": False if quarantines else None}


MULTI_KIND = "fork-multi-source-component-preview"


def validate_multi_water_preview(entry, core_bounds, writer_hash, writer_inputs,
                                 *, coast_validator):
    """Compose a strict coast proof and qualified inland preview, without waiver.

    coast_validator must be the existing strict coverage consumer, called as
    ('water', child_entry, core_bounds, writer_hash, writer_inputs). Keeping that
    callback mandatory leaves the independent coastline oracle in one place.
    """
    _require(callable(coast_validator), "A strict coast proof validator is required")
    _require(isinstance(entry, dict) and entry.get("status") == ENTRY_STATUS,
             "Explicit rendered_subset_preview aggregate entry required")
    core = _bounds(core_bounds, "requested core")
    path, digest = entry.get("evidence_path"), entry.get("evidence_sha256")
    aggregate = _bound_json(path, digest, "multi-water preview")
    _require(aggregate.get("kind") == MULTI_KIND and aggregate.get("schemaVersion") == 1
             and aggregate.get("status") == "RENDERED_SUBSET"
             and aggregate.get("component") == "water" and aggregate.get("synthetic") is False
             and aggregate.get("coreBounds") == core
             and aggregate.get("writerManifestSha256") == writer_hash,
             "Multi-water preview identity/core/writer mismatch")
    _false_gates(aggregate, "multi-water preview")
    _require(aggregate.get("fullWorldAccepted") is False
             and aggregate.get("sourceCoverageComplete") is False,
             "Multi-water preview cannot claim complete coverage")
    required = {"coast-water", "inland-water"}
    declared_required = aggregate.get("requiredContributors")
    _require(isinstance(declared_required, list) and len(declared_required) == 2
             and set(declared_required) == required, "Exactly coast and inland water are required")
    source_path, source_hash = aggregate.get("sourceSetPath"), aggregate.get("sourceSha256")
    source_set = _bound_json(source_path, source_hash, "multi-water source set")
    _require(source_set.get("kind") == "fork-component-source-set"
             and source_set.get("schemaVersion") == 1 and source_set.get("component") == "water"
             and source_set.get("coreBounds") == core
             and source_set.get("writerManifestSha256") == writer_hash,
             "Multi-water source-set identity mismatch")

    def keyed(records):
        _require(isinstance(records, list) and len(records) == 2
                 and all(isinstance(item, dict) and isinstance(item.get("id"), str)
                         for item in records), "Two typed source contributors required")
        result = {item["id"]: item for item in records}
        _require(len(result) == 2 and set(result) == required, "Missing or duplicate water contributor")
        return result

    declared, expected = keyed(aggregate.get("contributors")), keyed(source_set.get("contributors"))
    files = {str(Path(_fs_path(path)).resolve()).casefold(),
             str(Path(_fs_path(source_path)).resolve()).casefold()}
    children, proofs, feature_ids = [], {}, set()
    for name in sorted(required):
        record, pinned = declared[name], expected[name]
        child_path, child_hash = record.get("evidencePath"), record.get("evidenceSha256")
        _require(child_hash == pinned.get("evidenceSha256"), "Source-set child evidence mismatch")
        child = _bound_json(child_path, child_hash, name + " evidence")
        logical = str(Path(_fs_path(child_path)).resolve()).casefold()
        _require(logical not in files, "Duplicate or cyclic water child")
        files.add(logical)
        _require(child.get("kind") not in (MULTI_KIND, "fork-multi-source-component-evidence"),
                 "Nested water aggregates are not source contributors")
        _require(child.get("component") == "water" and child.get("coreBounds") == core
                 and child.get("writerManifestSha256") == writer_hash,
                 "Child component/core/writer mismatch")
        for key in ("sourceSha256", "runsSha256", "sourceReportSha256"):
            _require(isinstance(child.get(key), str)
                     and record.get(key) == pinned.get(key) == child[key],
                     "Child/source-set binding mismatch: " + key)
        _require(record.get("status") == child.get("status")
                 and type(record.get("featureCount")) is int
                 and record["featureCount"] == child.get("featureCount"),
                 "Child status or feature count mismatch")
        source_report = _bound_json(child.get("sourceReportPath"), child["sourceReportSha256"],
                                    name + " source report")
        schema = "fork.coast-surface.v1" if name == "coast-water" else "fork.roads-runs.v1"
        _require(source_report.get("schema") == schema, "Wrong actual source type for water contributor")
        runs_path, runs_hash = child.get("runsPath"), child["runsSha256"]
        _pin(runs_path, runs_hash, name + " consumed runs")
        size = Path(_fs_path(runs_path)).stat().st_size
        _require(isinstance(writer_inputs, list) and any(
            isinstance(item, dict) and item.get("sha256") == runs_hash
            and item.get("bytes") == size
            and str(item.get("name", "")).casefold() == Path(runs_path).name.casefold()
            for item in writer_inputs), "Water source runs were not consumed by this writer")
        if name == "coast-water":
            _require(child.get("status") in ("PASS", "NO_FEATURES")
                     and child.get("maskEvidence"), "Coast needs a strict oracle and mask chain")
            child_entry = {"status": "no_features" if child["status"] == "NO_FEATURES" else "included",
                           "evidence_path": child_path, "evidence_sha256": child_hash}
            normalized = coast_validator("water", child_entry, core, writer_hash, writer_inputs)
            _require(isinstance(normalized, dict)
                     and normalized.get("status") in ("included", "pass", "no_features"),
                     "Strict coast consumer did not accept its actual proof")
        else:
            _require(child.get("status") == "RENDERED_SUBSET"
                     and child.get("kind") == KIND and child.get("runSource") == "inland-water",
                     "Inland child must remain an explicit rendered subset")
            _require(child.get("reportSha256") == child["sourceReportSha256"],
                     "Inland source report differs from classified roads report")
            normalized = validate_preview("water", {"status": ENTRY_STATUS,
                "evidence_path": child_path, "evidence_sha256": child_hash},
                core, writer_hash, writer_inputs)
        _require(normalized.get("source_sha256") == child["sourceSha256"]
                 and normalized.get("feature_count") == child.get("featureCount"),
                 "Child normalization does not match actual wrapper")
        ids = child.get("emittedFeatureIds")
        _require(isinstance(ids, list) and all(isinstance(value, str) and value for value in ids)
                 and len(set(ids)) == len(ids) and len(ids) == normalized["feature_count"],
                 "Child emitted IDs do not establish its feature count")
        feature_ids.update(child["sourceSha256"] + ":" + value for value in ids)
        children.append({"id": name, **normalized, "runs_path": runs_path,
                         "runs_sha256": runs_hash, "source_report_path": child["sourceReportPath"],
                         "source_report_sha256": child["sourceReportSha256"]})
        proofs[name] = child
    count = len(feature_ids)
    _require(type(aggregate.get("featureCount")) is int and aggregate["featureCount"] == count
             and type(aggregate.get("emittedFeatureCount")) is int
             and aggregate["emittedFeatureCount"] == count
             and aggregate.get("emittedFeatureIds") == sorted(feature_ids),
             "Aggregate feature IDs/count do not equal actual children")
    for field in ("blockedDiagnostics", "unmappedSourceCount"):
        values = [proof.get(field) for proof in proofs.values()]
        combined = sum(values) if all(type(value) is int and value >= 0 for value in values) else None
        _require(aggregate.get(field) == combined
                 and (combined is None or type(aggregate.get(field)) is int),
                 "Aggregate diagnostic totals contradict child proofs")
    inland = proofs["inland-water"]
    _require(aggregate.get("omissions") == inland.get("omissions"),
             "Aggregate must preserve every inland source diagnostic")
    quarantines = inland.get("quarantinedSourceFeatures", [])
    _require(aggregate.get("quarantinedSourceFeatures", []) == quarantines,
             "Aggregate dropped source geometry quarantines")
    if quarantines:
        _require(aggregate.get("globalSourceGeometryComplete") is False,
                 "Aggregate geometry quarantine cannot claim complete source geometry")
    return {"status": ENTRY_STATUS, "evidence_path": str(path), "evidence_sha256": digest,
            "source_sha256": source_hash, "feature_count": count,
            "source_set_path": str(source_path), "source_set_sha256": source_hash,
            "contributors": children, "omissions": deepcopy(inland["omissions"]),
            "quarantinedSourceFeatures": deepcopy(quarantines),
            "sourceComplete": False, "routeComplete": False, "fullFidelity": False,
            "fullWorldAccepted": False, "waterAccepted": False}
