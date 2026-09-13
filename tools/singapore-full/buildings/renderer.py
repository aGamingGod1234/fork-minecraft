"""Bounded, seam-invariant complete-building to sparse Minecraft run compiler.

Input geometry is already projected to global x=SVY21 E, z=60000-SVY21 N.
This writer requires a clean terrain/world layer. It does not erase arbitrary
older Arnis geometry. Source outlines and every facade decision precede clipping.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict
import hashlib
import json
import math
from pathlib import Path

from footprints import normalize_feature, vertical_bounds
from facades import choose_palette, wall_material
from parts import BuildingPart, Footprint, column_segments, parse_roof, owner_tile
from source_validation import FeatureTagError, validate_document, validate_features


def _properties(feature):
    properties = feature.get("properties") or {}
    tags = dict(properties.get("tags") or {})
    tags.update({key: value for key, value in properties.items() if key != "tags"})
    return properties, tags


def _identity(feature):
    properties = feature.get("properties") or {}
    return str(feature.get("id") or properties.get("id") or
               properties.get("featureid") or properties.get("@id") or "")


def _intersects(feature, box):
    points = []
    def visit(value):
        if isinstance(value, (list, tuple)):
            if len(value) >= 2 and all(isinstance(v, (int, float)) for v in value[:2]):
                points.append(value[:2])
            else:
                for child in value:
                    visit(child)
    visit((feature.get("geometry") or {}).get("coordinates", []))
    if not points:
        raise ValueError(_identity(feature) + ": missing polygon coordinates")
    return (min(p[0] for p in points) < box[2] and max(p[0] for p in points) > box[0]
            and min(p[1] for p in points) < box[3] and max(p[1] for p in points) > box[1])


def _prepare(feature, ground_y, min_y, max_y):
    properties, tags = _properties(feature)
    identity = str(feature.get("id") or properties.get("id") or
                   properties.get("featureid") or properties.get("@id") or "")
    if not identity:
        raise ValueError("Every building needs a stable, typed source feature id")
    normalized_input = dict(feature)
    normalized_input["id"] = identity
    normalized_input["properties"] = tags
    building = normalize_feature(normalized_input)
    # Validate the complete envelope before any tile clipping, including tall
    # buildings that happen not to occupy a cell in this particular tile.
    vertical_bounds(building, ground_y, min_y=min_y, max_y=max_y)
    palette = choose_palette(identity, dict(building.tags))
    roof = parse_roof(dict(building.tags))
    if roof.height_m > building.height_m - building.min_height_m:
        raise ValueError(identity + ": roof exceeds the complete building height")
    components = [BuildingPart(
        identity + "/component/" + str(index), Footprint(polygon[0], polygon[1:]),
        building.min_height_m, building.height_m - roof.height_m, roof,
        # Group membership is resolved by the original typed feature identity
        # below. Component suffixes must not invalidate that relationship.
        parent_identity=None,
    ) for index, polygon in enumerate(building.polygons)]
    return {"id": identity, "normalized": building, "components": components,
            "palette": palette, "roof": roof,
            "parent": properties.get("parent_identity"),
            "is_part": tags.get("building:part", "no") not in (None, "", "no")}


def _append_run(runs, x, z, y_min, y_max, block, source, kind, source_class):
    if y_max <= y_min:
        return
    run = {"x": x, "z": z, "yMin": y_min, "yMax": y_max,
           "block": block, "featureId": source, "geometryKind": kind,
           "sourceClass": source_class, "layer": "building"}
    if runs and all(runs[-1].get(k) == v for k, v in run.items()
                    if k not in ("yMin", "yMax")) and runs[-1]["yMax"] == y_min:
        runs[-1]["yMax"] = y_max
    else:
        runs.append(run)


def _resolve_overlaps(runs, prepared, policy):
    """Global feature precedence, never source iteration order or tile position."""
    priority = {item["id"]: (0 if item["is_part"] else 1,
                             1 if item["normalized"].height.estimated else 0,
                             sum(p.footprint.area for p in item["components"]), item["id"])
                for item in prepared}
    columns = {}
    for run in runs:
        columns.setdefault((run["x"],run["z"]),[]).append(run)
    resolved, overlapping_voxels, samples = [], 0, []
    for (x,z), column in sorted(columns.items(), key=lambda pair:(pair[0][1],pair[0][0])):
        levels = sorted({y for run in column for y in (run["yMin"],run["yMax"])})
        for lo,hi in zip(levels,levels[1:]):
            active = [run for run in column if run["yMin"] <= lo and run["yMax"] >= hi]
            if not active:
                continue
            if len(active) > 1:
                identities = sorted({r["featureId"] for r in active})
                if policy == "error":
                    raise ValueError(f"Ambiguous source building overlap at ({x},{lo},{z}): {identities}")
                overlapping_voxels += hi-lo
                if len(samples) < 10:
                    samples.append({"x":x,"z":z,"yMin":lo,"yMax":hi,"featureIds":identities})
            winner = min(active,key=lambda r:(priority[r["featureId"]],r["geometryKind"],r["block"]))
            _append_run(resolved,x,z,lo,hi,winner["block"],winner["featureId"],
                        winner["geometryKind"],"estimated" if len(active)>1 else winner["sourceClass"])
    return resolved, overlapping_voxels, samples


def render_features(features, tile_box, *, ground_y, ground_source_class,
                    tile_size=512, min_y=-64, max_y=319,
                    max_candidate_columns=2_000_000, invalid_feature_policy="error"):
    """Return (runs, manifest); integer tile box and run yMax are half-open.

    Ground is one explicitly declared global datum for this bounded invocation.
    Production terrain policy must resolve the same building-wide datum before
    calling this function; raw DSM roof elevations are never accepted as ground.
    """
    if len(tile_box) != 4 or any(isinstance(v, bool) or not isinstance(v, int)
                                for v in tile_box):
        raise ValueError("tile_box must contain four integer global coordinates")
    x0, z0, x1, z1 = tile_box
    if x0 >= x1 or z0 >= z1:
        raise ValueError("tile_box must have positive area")
    if not isinstance(ground_y, (int, float)) or not math.isfinite(ground_y):
        raise ValueError("ground_y must be finite")
    if not ground_source_class:
        raise ValueError("ground_source_class is required")
    source_features = list(features)
    tag_errors = validate_features(source_features)
    source_by_id = {_identity(f): f for f in source_features}
    if len(source_by_id) != len(source_features):
        raise ValueError("Duplicate source feature identity")
    selected = {_identity(f) for f in source_features if _intersects(f, tile_box)}
    # Expand relation membership before clipping. A part crossing the tile must
    # bring its complete outline and all sibling parts into the compiler.
    changed = True
    while changed:
        before = len(selected)
        for f in source_features:
            parent = (f.get("properties") or {}).get("parent_identity")
            identity = _identity(f)
            if parent in selected:
                selected.add(identity)
            if identity in selected and parent in source_by_id:
                selected.add(parent)
        changed = len(selected) != before
    if invalid_feature_policy not in ("error", "report"):
        raise ValueError("invalid_feature_policy must be error or report")
    prepared, exclusions = [], []
    for identity in sorted(selected):
        try:
            if identity in tag_errors:
                raise FeatureTagError("tag_validation: " + tag_errors[identity])
            prepared.append(_prepare(source_by_id[identity], ground_y, min_y, max_y))
        except ValueError as exc:
            if invalid_feature_policy == "error":
                raise ValueError(identity + ": " + str(exc)) from exc
            exclusions.append({"featureId": identity, "reason": str(exc)})
    by_id = {item["id"]: item for item in prepared}
    if len(by_id) != len(prepared):
        raise ValueError("Duplicate source feature identity")
    children = {}
    for item in prepared:
        if item["is_part"] and item["parent"] in by_id:
            children.setdefault(item["parent"], []).append(item)
    roots = [item for item in prepared if not
             (item["is_part"] and item["parent"] in by_id)]
    runs, metadata, warnings = [], [], []
    candidate_columns = 0
    for root in roots:
        related = children.get(root["id"], [])
        # Official Simple 3D Buildings: parts replace the entire outline.
        active = related if related else [root]
        if root["is_part"]:
            warnings.append(root["id"] + ": part has no resolved outline; rendered independently")
        geometries = [part for item in active for part in item["components"]]
        component_lookup = {part.identity: (part, item)
                            for item in active for part in item["components"]}
        bounds = (min(p.footprint.bounds[0] for p in geometries),
                  min(p.footprint.bounds[1] for p in geometries),
                  max(p.footprint.bounds[2] for p in geometries),
                  max(p.footprint.bounds[3] for p in geometries))
        left, top = max(x0, math.floor(bounds[0])), max(z0, math.floor(bounds[1]))
        right, bottom = min(x1, math.ceil(bounds[2])), min(z1, math.ceil(bounds[3]))
        candidate_columns += max(0, right-left) * max(0, bottom-top)
        if candidate_columns > max_candidate_columns:
            raise ValueError("Building raster budget exceeded; partition into bounded tiles")
        for z in range(top, bottom):
            for x in range(left, right):
                cx, cz = x + 0.5, z + 0.5
                covering = [part for part in geometries if part.footprint.contains(cx, cz)]
                if not covering:
                    continue
                if related:
                    segments = column_segments(root["components"][0], geometries, cx, cz)
                else:
                    # Multipart polygon interiors are disjoint by valid GeoJSON.
                    segments = column_segments(covering[0], [], cx, cz)
                column_voxels = {}
                for segment in segments:
                    part, item = component_lookup[segment.identity]
                    floor_y = math.floor(ground_y + segment.min_height)
                    ceiling_y = math.ceil(ground_y + segment.max_height)
                    if floor_y < min_y or ceiling_y > max_y + 1:
                        raise ValueError(item["id"] + ": column exceeds world Y range")
                    if ceiling_y <= floor_y:
                        continue
                    palette = item["palette"]
                    x_edge = not (part.footprint.contains(cx-1, cz) and
                                  part.footprint.contains(cx+1, cz))
                    z_edge = not (part.footprint.contains(cx, cz-1) and
                                  part.footprint.contains(cx, cz+1))
                    exterior = x_edge or z_edge
                    ys = range(floor_y, ceiling_y) if exterior else sorted({floor_y, ceiling_y-1})
                    for y in ys:
                        if y == ceiling_y - 1:
                            block, kind = palette.roof_block, "roof"
                            classification = "mapped" if (not item["roof"].estimated and
                                                           palette.roof_provenance == "mapped") else "estimated"
                        elif y == floor_y:
                            block, kind, classification = palette.trim_block, "floor", "estimated"
                        elif exterior:
                            block = wall_material(palette, x, y, z,
                                                  face_axis="x" if x_edge else "z")
                            kind, classification = "facade", "estimated"
                        else:
                            continue
                        # Fractional adjacent parts may cover the same integer
                        # voxel after outward rounding. Decide ownership once,
                        # using its centre's distance to the continuous solid.
                        sample = y + 0.5 - ground_y
                        distance = max(segment.min_height-sample, sample-segment.max_height, 0.0)
                        inside = segment.min_height <= sample < segment.max_height
                        score = (0 if inside else 1, distance, item["id"])
                        previous = column_voxels.get(y)
                        if previous is None or score < previous[0]:
                            column_voxels[y] = (score, block, item["id"], kind, classification)
                for y, (_, block, identity, kind, classification) in sorted(column_voxels.items()):
                    _append_run(runs, x, z, y, y+1, block, identity, kind, classification)
        metadata.append({"featureId": root["id"], "bounds": bounds,
                         "ownerTile": [math.floor(bounds[0]/tile_size),
                                       math.floor(bounds[1]/tile_size)],
                         "relatedParts": [item["id"] for item in related],
                         "outlineSuppressed": bool(related)})
    runs, overlapping_voxels, overlap_samples = _resolve_overlaps(runs, prepared, invalid_feature_policy)
    encoded = "\n".join(json.dumps(r, sort_keys=True, separators=(",", ":")) for r in runs)
    manifest = {"schema": "fork-building-runs-v1", "coordinateSystem": "EPSG:3414",
                "axisMapping": "x=E,z=60000-N", "horizontalBlocksPerMetre": 1,
                "tileCore": list(tile_box), "groundY": ground_y,
                "groundSourceClass": ground_source_class,
                "verticalRangeInclusive": [min_y, max_y], "requiresCleanTerrainLayer": True,
                "candidateColumns": candidate_columns, "runCount": len(runs),
                "inputFeatureCount": len(source_features), "selectedFeatureCount": len(prepared),
                "runsSha256": hashlib.sha256(encoded.encode()).hexdigest(),
                "features": metadata, "warnings": warnings,
                "exclusions": exclusions,
                "resolvedOverlappingVoxels": overlapping_voxels, "overlapSamples": overlap_samples,
                "overlapPolicy": "parts, explicit height, smaller complete footprint, stable source id",
                "completeSourceGeometryAccepted": not bool(exclusions or overlapping_voxels),
                "evidence": [{"featureId": item["id"],
                              "height": asdict(item["normalized"].height),
                              "minHeight": asdict(item["normalized"].min_height),
                              "roof": asdict(item["roof"]),
                              "palette": item["palette"].to_dict()}
                             for item in prepared]}
    return runs, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--tile", required=True, type=int, nargs=4)
    parser.add_argument("--ground-y", required=True, type=float)
    parser.add_argument("--ground-source-class", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--invalid-feature-policy", choices=("error", "report"), default="error")
    args = parser.parse_args()
    source = json.loads(args.input.read_text(encoding="utf-8-sig"))
    features = validate_document(source)
    runs, manifest = render_features(features, tuple(args.tile),
                                    ground_y=args.ground_y,
                                    ground_source_class=args.ground_source_class,
                                    invalid_feature_policy=args.invalid_feature_policy)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(json.dumps(r, sort_keys=True) + "\n" for r in runs), encoding="utf-8")
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"runCount": len(runs), "manifest": str(args.manifest)}))


if __name__ == "__main__":
    main()
