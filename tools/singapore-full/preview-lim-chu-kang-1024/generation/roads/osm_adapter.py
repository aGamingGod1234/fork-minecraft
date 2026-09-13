"""Complete OSM objects to road/water GeoJSON in the shared Singapore grid.

Projection is injected: ``project(lon, lat) -> (SVY21 easting, northing)``.
The caller owns national extraction/masking and must pass complete object refs.
No tile clipping or coordinate rounding is done here; both cause seam defects.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
import hashlib
import json
import math
from typing import Callable


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


@dataclass
class AdaptedFeatures:
    features: list[dict]
    diagnostics: list[dict]
    source_sha256: str | None

    def to_dict(self):
        return {
            "type": "FeatureCollection", "features": self.features,
            "metadata": {
                "grid": "EPSG:3414", "x": "easting", "z": "60000-northing",
                "blocksPerMeter": 1, "coordinatesRounded": False,
                "wholeFeaturesPreserved": True, "sourceSha256": self.source_sha256,
                "featureSha256": hashlib.sha256(_canonical(self.features).encode()).hexdigest(),
                "license": "OpenStreetMap contributors, ODbL 1.0",
            },
            "diagnostics": self.diagnostics,
        }


def _surface(tags):
    return tags.get("natural") in {"water", "wood", "scrub", "wetland", "beach", "sand", "grassland"} or "landuse" in tags or tags.get("waterway") == "riverbank" or tags.get("leisure") in {"park", "garden", "pitch"}


def _surface_key(tags):
    if tags.get("natural") == "water" or tags.get("waterway") == "riverbank" or tags.get("landuse") in {"reservoir", "basin"}:
        return ("water",)
    return next(((key, tags[key]) for key in ("landuse", "natural", "leisure") if key in tags), ())


def _point_in_ring(point, ring):
    x, z = point
    inside = False
    for a, b in zip(ring, ring[1:]):
        if (a[1] > z) != (b[1] > z) and x < (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]) + a[0]:
            inside = not inside
    return inside


def _join_rings(parts):
    """Join endpoint-connected OSM member ways; reject branches/open chains."""
    remaining = [list(p) for p in parts]
    rings = []
    while remaining:
        remaining.sort(key=lambda p: tuple(p))
        chain = remaining.pop(0)
        if len(chain) < 2:
            raise ValueError("member has fewer than two nodes")
        while chain[0] != chain[-1]:
            matches = [(i, p) for i, p in enumerate(remaining) if p[0] == chain[-1] or p[-1] == chain[-1]]
            if len(matches) != 1:
                raise ValueError("open or branching multipolygon ring")
            index, part = matches[0]
            remaining.pop(index)
            if part[-1] == chain[-1]:
                part = list(reversed(part))
            chain.extend(part[1:])
        if len(set(chain[:-1])) < 3:
            raise ValueError("ring has fewer than three unique nodes")
        # Canonical ring start/direction makes member order immaterial.
        body = chain[:-1]
        candidates = []
        for sequence in (body, list(reversed(body))):
            first = min(range(len(sequence)), key=sequence.__getitem__)
            candidates.append(sequence[first:] + sequence[:first])
        body = min(candidates)
        rings.append(body + body[:1])
    return sorted(rings)


def adapt_osm(document: dict, project: Callable[[float, float], tuple[float, float]], *, source_sha256=None, northing_origin=60000.0) -> AdaptedFeatures:
    """Convert complete Overpass-style JSON into deterministic whole features.

    All way references must resolve. Multipolygon components are assembled from
    shared OSM node IDs, not proximity; any incomplete relation is diagnostic.
    An administrative mask is deliberately not interpreted as a land boundary.
    """
    elements = document.get("elements")
    if not isinstance(elements, list):
        raise ValueError("document.elements must be a list")
    by_type = defaultdict(dict)
    for element in elements:
        kind, identity = element.get("type"), element.get("id")
        if kind not in {"node", "way", "relation"} or not isinstance(identity, int):
            raise ValueError("each element needs an OSM type and integer id")
        old = by_type[kind].get(identity)
        if old is not None and old != element:
            raise ValueError(f"conflicting duplicate {kind}/{identity}")
        by_type[kind][identity] = element
    diagnostics, features = [], []
    projected = {}

    def diagnostic(source_id, code, detail):
        diagnostics.append({"source_id": source_id, "code": code, "detail": str(detail)})

    def coordinate(node_id):
        if node_id not in projected:
            node = by_type["node"].get(node_id)
            if node is None or "lon" not in node or "lat" not in node:
                raise ValueError(f"missing coordinate node/{node_id}")
            east, north = project(float(node["lon"]), float(node["lat"]))
            result = [float(east), float(northing_origin - north)]
            if not all(math.isfinite(v) for v in result):
                raise ValueError(f"non-finite projection node/{node_id}")
            projected[node_id] = result
        return projected[node_id]

    def emit(kind, obj, geometry, nodes=None, members=None):
        identity = f'{kind}/{obj["id"]}'
        properties = {"osm_type": kind, "osm_id": obj["id"], "source_id": identity, "tags": dict(sorted(obj.get("tags", {}).items()))}
        if nodes is not None:
            properties["nodes"] = nodes
        if members is not None:
            properties["members"] = members
        features.append({"type": "Feature", "id": identity, "properties": properties, "geometry": geometry})

    relation_surface_members = set()
    for identity, relation in sorted(by_type["relation"].items()):
        tags = relation.get("tags", {})
        if tags.get("type") != "multipolygon" or not _surface(tags):
            continue
        source = f"relation/{identity}"
        try:
            parts = {"outer": [], "inner": []}
            used = []
            duplicate_outer_members = []
            for member in relation.get("members", []):
                role = member.get("role", "") or "outer"
                if member.get("type") != "way" or role not in parts:
                    raise ValueError("unsupported multipolygon member type/role")
                way = by_type["way"].get(member.get("ref"))
                if way is None:
                    raise ValueError(f'missing member way/{member.get("ref")}')
                refs = way.get("nodes", [])
                for node_id in refs:
                    coordinate(node_id)
                parts[role].append(refs)
                used.append(way["id"])
                # A tagged inner island is an independent mapped surface.
                # Suppress only an outer member repeating its relation's area:
                # emitting that outline separately would fill relation holes.
                if role == "outer" and _surface_key(way.get("tags", {})) == _surface_key(tags):
                    duplicate_outer_members.append(way["id"])
            outer = _join_rings(parts["outer"])
            inner = _join_rings(parts["inner"])
            if not outer:
                raise ValueError("multipolygon has no outer rings")
            polygons = [[[coordinate(ref) for ref in ring]] for ring in outer]
            for ring in inner:
                coords = [coordinate(ref) for ref in ring]
                owners = [i for i, polygon in enumerate(polygons) if _point_in_ring(coords[0], polygon[0])]
                if len(owners) != 1:
                    raise ValueError("hole has no unique containing outer ring")
                polygons[owners[0]].append(coords)
            emit("relation", relation, {"type": "MultiPolygon", "coordinates": polygons}, members=sorted(used))
            relation_surface_members.update(duplicate_outer_members)
        except (ValueError, TypeError) as error:
            diagnostic(source, "incomplete_or_unsupported_multipolygon", error)

    for identity, way in sorted(by_type["way"].items()):
        tags, source = way.get("tags", {}), f"way/{identity}"
        road = "highway" in tags and tags.get("area") != "yes"
        surface = _surface(tags) and identity not in relation_surface_members
        coastline = tags.get("natural") == "coastline"
        if not (road or surface or coastline):
            if "highway" in tags and tags.get("area") == "yes":
                diagnostic(source, "area_highway_not_rasterized", "road area needs polygon policy")
            continue
        refs = way.get("nodes", [])
        try:
            coords = [coordinate(ref) for ref in refs]
            if len(coords) < 2:
                raise ValueError("way has fewer than two nodes")
            if road or coastline:
                emit("way", way, {"type": "LineString", "coordinates": coords}, nodes=list(refs))
            elif surface:
                if refs[0] != refs[-1] or len(set(refs[:-1])) < 3:
                    raise ValueError("surface way is not a closed polygon")
                emit("way", way, {"type": "Polygon", "coordinates": [coords]}, nodes=list(refs))
        except (ValueError, TypeError) as error:
            diagnostic(source, "incomplete_way", error)

    for identity, node in sorted(by_type["node"].items()):
        tags = node.get("tags", {})
        if tags.get("highway") == "crossing" or "crossing" in tags or tags.get("railway") == "level_crossing":
            try:
                emit("node", node, {"type": "Point", "coordinates": coordinate(identity)}, nodes=[identity])
            except (ValueError, TypeError) as error:
                diagnostic(f"node/{identity}", "incomplete_crossing", error)
    features.sort(key=lambda f: f["id"])
    diagnostics.sort(key=lambda d: (d["source_id"], d["code"], d["detail"]))
    return AdaptedFeatures(features, diagnostics, source_sha256)
