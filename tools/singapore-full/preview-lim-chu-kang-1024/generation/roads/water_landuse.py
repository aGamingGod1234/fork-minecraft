"""Sparse, deterministic water/landuse surfaces from complete projected GeoJSON.
One block = one SVY21 metre; input coordinates are [easting, 60000-northing].
No projection, coastline stitching, national land extraction, elevation or block I/O.
"""
from dataclasses import asdict, dataclass
import argparse
import json
import math
PALETTE = {
    "forest": "minecraft:grass_block", "grass": "minecraft:grass_block",
    "meadow": "minecraft:grass_block", "village_green": "minecraft:grass_block",
    "recreation_ground": "minecraft:grass_block", "orchard": "minecraft:grass_block",
    "farmland": "minecraft:dirt", "farmyard": "minecraft:dirt",
    "residential": "minecraft:grass_block", "commercial": "minecraft:stone",
    "retail": "minecraft:stone", "industrial": "minecraft:stone",
    "construction": "minecraft:dirt", "brownfield": "minecraft:dirt",
    "cemetery": "minecraft:grass_block", "allotments": "minecraft:dirt",
}
EPS = 1e-9
@dataclass(frozen=True)
class SurfaceCell:
    material: str
    kind: str
    source_id: str
    mapped_value: str
    material_basis: str = "stylized_palette_not_surveyed_surface"
@dataclass
class RasterResult:
    cells: dict
    diagnostics: list
    candidate_count: int = 0
    def to_dict(self):
        return {
            "coordinate_contract": "x=EPSG3414 easting,z=60000-EPSG3414 northing",
            "sampling": "block_centres_half_open_core",
            "candidate_count": self.candidate_count,
            "cells": [dict(x=x, z=z, **asdict(cell))
                      for (x, z), cell in sorted(self.cells.items())],
            "diagnostics": self.diagnostics,
        }
def _point(point):
    if not isinstance(point, (list, tuple)) or len(point) < 2:
        raise ValueError("coordinate must have two finite numbers")
    x, z = point[:2]
    if isinstance(x, bool) or isinstance(z, bool):
        raise ValueError("boolean coordinate")
    if not isinstance(x, (int, float)) or not isinstance(z, (int, float)):
        raise ValueError("non-numeric coordinate")
    if not math.isfinite(x) or not math.isfinite(z):
        raise ValueError("non-finite coordinate")
    return float(x), float(z)
def _ring(raw):
    if not isinstance(raw, (list, tuple)) or len(raw) < 4:
        raise ValueError("ring requires at least four positions")
    ring = tuple(_point(p) for p in raw)
    if ring[0] != ring[-1]:
        raise ValueError("unclosed ring; adapter will not invent closing geometry")
    if len(set(ring[:-1])) < 3:
        raise ValueError("ring has fewer than three distinct vertices")
    area2 = sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ring, ring[1:]))
    if abs(area2) < EPS:
        raise ValueError("degenerate ring")
    return ring
def _polygons(geometry):
    if not isinstance(geometry, dict):
        raise ValueError("missing geometry")
    kind = geometry.get("type")
    coordinates = geometry.get("coordinates")
    if kind == "Polygon":
        coordinates = [coordinates]
    elif kind != "MultiPolygon":
        raise ValueError("only complete Polygon/MultiPolygon geometries are rasterized")
    if not isinstance(coordinates, (list, tuple)) or not coordinates:
        raise ValueError("empty polygon geometry")
    polygons = []
    for polygon in coordinates:
        if not isinstance(polygon, (list, tuple)) or not polygon:
            raise ValueError("polygon requires an exterior ring")
        polygons.append(tuple(_ring(ring) for ring in polygon))
    return tuple(polygons)
def _on_segment(x, z, a, b):
    cross = (x-a[0]) * (b[1]-a[1]) - (z-a[1]) * (b[0]-a[0])
    scale = max(1.0, abs(b[0]-a[0]), abs(b[1]-a[1]))
    return (abs(cross) <= EPS * scale
            and min(a[0], b[0])-EPS <= x <= max(a[0], b[0])+EPS
            and min(a[1], b[1])-EPS <= z <= max(a[1], b[1])+EPS)
def _inside_ring(x, z, ring):
    inside = False
    for a, b in zip(ring, ring[1:]):
        if _on_segment(x, z, a, b):
            return True
        if (a[1] > z) != (b[1] > z):
            crossing = a[0] + (z-a[1]) * (b[0]-a[0]) / (b[1]-a[1])
            if x < crossing:
                inside = not inside
    return inside
def _contains(x, z, polygons):
    return any(_inside_ring(x, z, polygon[0])
               and not any(_inside_ring(x, z, hole) for hole in polygon[1:])
               for polygon in polygons)
def _classification(properties, source_id):
    tags = properties.get("tags", properties)
    if not isinstance(tags, dict):
        return None
    if tags.get("natural") == "coastline":
        return None  # A coastline is not evidence that either side is filled land.
    landuse = tags.get("landuse")
    if (tags.get("natural") == "water" or tags.get("waterway") == "riverbank"
            or landuse in ("reservoir", "basin")):
        value = tags.get("water") or tags.get("waterway") or landuse or "water"
        return 30, SurfaceCell("minecraft:water", "mapped_water", source_id, str(value))
    if isinstance(landuse, str) and landuse in PALETTE:
        return 10, SurfaceCell(PALETTE[landuse], "mapped_landuse", source_id, landuse)
    return None
def _feature_id(feature):
    props = feature.get("properties") or {}
    if not isinstance(props, dict):
        props = {}
    return str(feature.get("id", props.get("@id", props.get("osm_id", "unidentified"))))
def rasterize(features, core, clip_mask=None, *, max_candidates=1_000_000):
    """Rasterize complete mapped areas into a half-open integer core.
    features: FeatureCollection or iterable of GeoJSON Features; projected x/z.
    clip_mask: optional already-projected Polygon/MultiPolygon geometry, or
      callable(x,z)->bool. It only limits writes. An administrative mask may
      include territorial sea and is NEVER interpreted as a true land mask.
    Unknown surfaces produce no cells. Caller owns terrain, Y and application.
    Water wins over landuse; same-kind overlaps use canonical source order.
    """
    if isinstance(max_candidates, bool) or not isinstance(max_candidates, int) or max_candidates < 1:
        raise ValueError("max_candidates must be a positive integer")
    if (len(core) != 4 or any(isinstance(v, bool) or not isinstance(v, int) for v in core)
            or core[0] >= core[2] or core[1] >= core[3]):
        raise ValueError("core must be (min_x,min_z,max_x,max_z) integer, half-open")
    if isinstance(features, dict):
        if features.get("type") != "FeatureCollection":
            raise ValueError("expected FeatureCollection")
        features = features.get("features", [])
    if clip_mask is None:
        mask = lambda x, z: True
    elif callable(clip_mask):
        mask = clip_mask
    else:
        mask_geometry = _polygons(clip_mask)
        mask = lambda x, z: _contains(x, z, mask_geometry)
    diagnostics, prepared = [], []
    for feature in features:
        if not isinstance(feature, dict) or feature.get("type") != "Feature":
            diagnostics.append({"source_id": "unidentified", "code": "invalid_feature"})
            continue
        source_id = _feature_id(feature)
        props = feature.get("properties")
        if props is None:
            props = {}
        tags = props.get("tags", props) if isinstance(props, dict) else None
        if (not isinstance(props, dict) or not isinstance(tags, dict)):
            diagnostics.append({"source_id": source_id, "code": "invalid_properties"})
            continue
        if tags.get("natural") == "coastline":
            diagnostics.append({"source_id": source_id, "code": "coastline_not_land_mask"})
            continue
        if any(obj.get(flag) is False for obj in (feature, props)
               for flag in ("geometry_complete", "references_complete")):
            diagnostics.append({"source_id": source_id, "code": "incomplete_geometry"})
            continue
        classification = _classification(props, source_id)
        if classification is None:
            diagnostics.append({"source_id": source_id, "code": "unsupported_surface"})
            continue
        try:
            polygons = _polygons(feature.get("geometry"))
        except (ValueError, TypeError) as error:
            diagnostics.append({"source_id": source_id, "code": "invalid_geometry",
                                "detail": str(error)})
            continue
        priority, cell = classification
        key = (priority, source_id, json.dumps(feature, sort_keys=True, separators=(",", ":")))
        prepared.append((key, polygons, cell))
    windows = []
    candidates = 0
    for _, polygons, cell in sorted(prepared, key=lambda item: item[0]):
        points = [p for polygon in polygons for p in polygon[0]]
        min_x = max(core[0], math.ceil(min(p[0] for p in points)-0.5))
        max_x = min(core[2]-1, math.floor(max(p[0] for p in points)-0.5))
        min_z = max(core[1], math.ceil(min(p[1] for p in points)-0.5))
        max_z = min(core[3]-1, math.floor(max(p[1] for p in points)-0.5))
        candidates += max(0, max_x-min_x+1) * max(0, max_z-min_z+1)
        if candidates > max_candidates:
            raise ValueError("candidate budget exceeded; use a smaller core or explicit larger budget")
        windows.append((polygons, cell, min_x, max_x, min_z, max_z))
    cells = {}
    for polygons, cell, min_x, max_x, min_z, max_z in windows:
        for x in range(min_x, max_x+1):
            for z in range(min_z, max_z+1):
                if mask(x+0.5, z+0.5) and _contains(x+0.5, z+0.5, polygons):
                    cells[x, z] = cell
    diagnostics.sort(key=lambda d: json.dumps(d, sort_keys=True))
    return RasterResult(cells, diagnostics, candidate_count=candidates)
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("features")
    parser.add_argument("--core", type=int, nargs=4, required=True)
    parser.add_argument("--clip-mask", help="already projected geometry JSON; no reprojection")
    parser.add_argument("--max-candidates", type=int, default=1_000_000)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    with open(args.features, encoding="utf-8-sig") as source:
        features = json.load(source)
    mask = None
    if args.clip_mask:
        with open(args.clip_mask, encoding="utf-8-sig") as source:
            mask = json.load(source)
    result = rasterize(features, tuple(args.core), clip_mask=mask,
                       max_candidates=args.max_candidates)
    with open(args.out, "w", encoding="utf-8", newline="\n") as target:
        json.dump(result.to_dict(), target, indent=2, sort_keys=True)
        target.write("\n")
if __name__ == "__main__":
    main()