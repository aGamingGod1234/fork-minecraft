"""Road and mapped-surface adapters for the shared vertical-run renderer.

This emits data only. The caller owns JSONL persistence and Anvil block writes.
Whole source features are never clipped before rasterization. A flat preview
requires explicit provisional_surface_y; omission never invents street height.
"""
from dataclasses import asdict, is_dataclass
import hashlib
import json

try:
    from .road_raster import rasterize_road
    from .topology import vertical_policy
    from .water_landuse import rasterize as rasterize_surfaces
except ImportError:
    from road_raster import rasterize_road
    from topology import vertical_policy
    from water_landuse import rasterize as rasterize_surfaces

ROAD_PALETTE = {
    "footway": "minecraft:stone_bricks", "pedestrian": "minecraft:stone_bricks",
    "path": "minecraft:gravel", "track": "minecraft:gravel", "bridleway": "minecraft:gravel",
    "steps": "minecraft:stone_bricks", "cycleway": "minecraft:gray_concrete",
}
OWNERSHIP_POLICY = "road-surface-ownership-v1"
_CARRIAGEWAYS = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential",
    "living_street", "service", "track", "road"}


def _ownership_rank(tags, identity):
    """Global immutable rank, never dependent on tile or feature visit order.

    A footpath buffer at a road crossing must not replace the carriageway.
    This is an explicit rendering choice, not evidence of exact paving design.
    Equal classes use numeric OSM IDs, with the full ID as a final tie-break.
    """
    highway = tags.get("highway")
    category = 0 if highway in _CARRIAGEWAYS else 1 if highway == "cycleway" else 2 if highway == "pedestrian" else 3
    number = identity.rsplit("/", 1)[-1]
    return (category, int(number) if number.isdigit() else 2**64, identity)


def _data(value):
    if hasattr(value, "to_dict"):
        return value.to_dict()
    return asdict(value) if is_dataclass(value) else dict(value)


def emit_surface_runs(features, core, *, provisional_surface_y=None, terrain_sampler=None,
                      y_offset=0.0, clip_mask=None, max_candidates=1_000_000):
    """Return deterministic runs, per-feature evidence and unmet geometry gates.

    clip_mask is a projected administrative membership callable(x,z)->bool or
    Polygon/MultiPolygon for mapped surfaces. Roads require a callable mask;
    this avoids separately implementing country/coastline polygon extraction.
    Terrain samples and bridge/tunnel acceptance follow topology.vertical_policy.
    Water needs explicit provisional Y until an independent water-level input is
    added; DEM land elevation is not evidence of a water surface elevation.
    """
    if isinstance(features, dict):
        features = features["features"]
    features = list(features)
    if provisional_surface_y is not None and type(provisional_surface_y) is not int:
        raise ValueError("provisional_surface_y must be an explicit integer or None")
    if type(max_candidates) is not int or max_candidates < 1:
        raise ValueError("max_candidates must be positive")
    if clip_mask is not None and not callable(clip_mask):
        raise ValueError("shared run adapter requires callable country membership")
    runs, evidence, diagnostics = [], [], []
    road_owners, road_contenders, road_source_evidence = {}, {}, {}
    road_features, area_features = [], []
    for feature in features:
        props = feature.get("properties") or {}
        tags = props.get("tags", props)
        if feature.get("geometry", {}).get("type") == "LineString" and "highway" in tags:
            road_features.append(feature)
        elif feature.get("geometry", {}).get("type") in {"Polygon", "MultiPolygon"} or tags.get("natural") == "coastline":
            area_features.append(feature)
    used_candidates = 0
    for feature in sorted(road_features, key=lambda f: str(f.get("id", ""))):
        identity = str(feature.get("id", "unidentified"))
        tags = feature["properties"].get("tags", feature["properties"])
        try:
            raster = rasterize_road(feature, core, y=0, max_blocks=max_candidates-used_candidates)
        except ValueError as error:
            diagnostics.append({"featureId": identity, "code": "road_raster_blocked", "detail": str(error)})
            continue
        used_candidates += raster["candidate_count"]
        material = ROAD_PALETTE.get(tags.get("highway"), "minecraft:gray_concrete")
        ownership_rank = _ownership_rank(tags, identity)
        road_source_evidence[identity] = {"featureId": identity, "highway": tags.get("highway"),
            "block": material, "priorityClass": ownership_rank[0], "width": raster["width"]}
        record = {"featureId": identity, "sourceSha256": raster["feature_digest"],
                  "width": raster["width"], "materialBasis": "stylized_palette_not_surveyed_surface",
                  "runs": 0, "verticalAccepted": True, "verticalPolicies": []}
        policies = {}
        for x, _, z in raster["blocks"]:
            if clip_mask is not None and not clip_mask(x+0.5, z+0.5):
                continue
            policy = _data(vertical_policy(feature, ground_y=provisional_surface_y,
                terrain_sampler=terrain_sampler, y_offset=y_offset, x=x+0.5, z=z+0.5))
            policy_key = json.dumps(policy, sort_keys=True, separators=(",", ":"))
            policies[policy_key] = policy
            y = policy.get("y")
            record["verticalAccepted"] = record["verticalAccepted"] and bool(policy.get("accepted_geometry"))
            if not policy.get("allow_surface") or y is None:
                continue
            if type(y) is not int or not -64 <= y <= 319:
                diagnostics.append({"featureId": identity, "code": "vertical_outside_runtime", "x": x, "z": z, "y": y})
                continue
            is_bridge = policy.get("mode") == "bridge"
            run = {"x": x, "z": z, "yMin": y, "yMax": y+1,
                "block": material, "featureId": identity, "geometryKind": "road_surface",
                "sourceClass": "mapped_geometry" if policy.get("accepted_geometry") else "mapped_geometry_provisional_vertical",
                "layer": 60 if is_bridge else 40}
            voxel = (run["layer"], x, y, z)
            previous = road_owners.get(voxel)
            if previous is None:
                road_owners[voxel] = (ownership_rank, run)
            else:
                contenders = road_contenders.setdefault(voxel, {previous[1]["featureId"]})
                contenders.add(identity)
                if ownership_rank < previous[0]:
                    road_owners[voxel] = (ownership_rank, run)
            record["runs"] += 1
        record["verticalPolicies"] = [policies[k] for k in sorted(policies)]
        if raster["blocks"] and not record["runs"]:
            diagnostics.append({"featureId": identity, "code": "road_vertical_or_country_mask_blocked"})
        evidence.append(record)
    # Emit one owner per same-layer voxel before the strict writer sees runs.
    # Other layers and elevations stay separate for the global merge policy.
    owned_counts = {}
    for _, run in road_owners.values():
        runs.append(run)
        owned_counts[run["featureId"]] = owned_counts.get(run["featureId"], 0) + 1
    for record in evidence:
        record["candidateRuns"] = record["runs"]
        record["runs"] = owned_counts.get(record["featureId"], 0)
        record["occludedRuns"] = record["candidateRuns"]-record["runs"]
    overlap_groups = {}
    different_material = 0
    for voxel, identities in sorted(road_contenders.items()):
        layer, x, y, z = voxel
        winner = road_owners[voxel][1]["featureId"]
        identities = tuple(sorted(identities))
        different_material += len({road_source_evidence[i]["block"] for i in identities}) > 1
        group = overlap_groups.setdefault((layer, winner, identities), {
            "code": "road_overlap_resolved", "policy": OWNERSHIP_POLICY, "layer": layer,
            "winnerFeatureId": winner, "candidateFeatureIds": list(identities),
            "candidates": [road_source_evidence[i] for i in identities],
            "voxelCount": 0, "firstVoxel": [x, y, z],
            "reason": "Carriageway before cycleway before pedestrian/path; equal classes use stable OSM ID",
            "surveyedJunctionDesign": False})
        group["voxelCount"] += 1
    diagnostics.extend(overlap_groups[k] for k in sorted(overlap_groups))
    remaining = max_candidates-used_candidates
    if area_features and remaining <= 0:
        diagnostics.append({"code": "surface_raster_blocked", "detail": "Shared candidate budget exhausted"})
        area_features = []
    try:
        surfaces = rasterize_surfaces(area_features, core, clip_mask=clip_mask, max_candidates=max(1, remaining))
    except ValueError as error:
        diagnostics.append({"code": "surface_raster_blocked", "detail": str(error)})
        surfaces = rasterize_surfaces([], core, max_candidates=1)
    used_candidates += surfaces.candidate_count
    diagnostics.extend(surfaces.diagnostics)
    surface_evidence = {}
    for (x, z), cell in sorted(surfaces.cells.items()):
        entry = surface_evidence.setdefault(cell.source_id, {"featureId": cell.source_id,
            "classification": cell.kind, "mappedValue": cell.mapped_value,
            "material": cell.material, "materialBasis": cell.material_basis,
            "verticalAccepted": False, "cellCount": 0})
        entry["cellCount"] += 1
        if provisional_surface_y is None:
            continue
        if not -64 <= provisional_surface_y <= 319:
            raise ValueError("provisional surface lies outside Minecraft Y range")
        is_water = cell.kind == "mapped_water"
        run = {"x": x, "z": z, "yMin": provisional_surface_y, "yMax": provisional_surface_y+1,
               "block": cell.material, "featureId": cell.source_id,
               "geometryKind": "water_surface" if is_water else "landcover_surface",
               "sourceClass": "mapped_geometry_provisional_vertical", "layer": 30 if is_water else 20}
        if is_water:
            run["properties"] = {"level": "0"}
        runs.append(run)
    if surfaces.cells and provisional_surface_y is None:
        diagnostics.append({"code": "surface_vertical_unknown", "cells": len(surfaces.cells),
                            "detail": "No water level or accepted landcover ground policy supplied"})
    runs.sort(key=lambda r: (r["layer"], r["featureId"], r["x"], r["z"], r["yMin"], r["block"]))
    return {"runs": runs, "evidence": evidence, "surfaceEvidence": [surface_evidence[k] for k in sorted(surface_evidence)], "diagnostics": diagnostics,
            "runSha256": hashlib.sha256(json.dumps(runs, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
            "candidateCount": used_candidates, "fullFidelityAccepted": False,
            "ownership": {"policy": OWNERSHIP_POLICY, "contestedVoxelCount": len(road_contenders),
                          "differentMaterialVoxelCount": different_material,
                          "sameMaterialVoxelCount": len(road_contenders)-different_material},
            "precedence": {"terrain": 10, "landcover": 20, "water": 30, "road": 40, "building": 50, "bridge": 60}}
