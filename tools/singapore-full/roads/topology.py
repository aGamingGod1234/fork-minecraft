"""Conservative topology for complete OSM ways in the FORK metre grid.

No raster or world mutation occurs here. Input positions are [easting, 60000-
northing] in EPSG:3414, already projected by the source pipeline. See the paired
document for evidence requirements; layer is an ordering, never a height.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from itertools import combinations
import math
import re
from typing import Any, Callable, Iterable, Mapping

Point = tuple[float, float]
TerrainSampler = Callable[[float, float], Mapping[str, Any] | None]
NO = {"no", "false", "0", ""}
PATHS = {"footway", "cycleway", "path", "steps", "pedestrian"}


@dataclass(frozen=True)
class VerticalPolicy:
    way_id: str
    mode: str
    layer: int | None
    y: int | None
    allow_surface: bool
    accepted_geometry: bool
    provenance: dict[str, Any]
    uncertainties: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class Intersection:
    id: str
    x: float
    z: float
    block: tuple[int, int]
    way_ids: tuple[str, ...]
    relation: str
    evidence: str
    node_refs: tuple[str, ...]
    uncertainties: tuple[str, ...] = ()


@dataclass(frozen=True)
class MappedCrossing:
    id: str
    geometry: dict[str, Any]
    road_ids: tuple[str, ...]
    markings: str
    paint_geometry_available: bool
    provenance: dict[str, Any]
    uncertainties: tuple[str, ...]


@dataclass(frozen=True)
class TopologyResult:
    vertical_policies: tuple[VerticalPolicy, ...]
    intersections: tuple[Intersection, ...]
    crossings: tuple[MappedCrossing, ...]
    diagnostics: tuple[dict[str, Any], ...]
    coordinate_system: str = "EPSG:3414; x=easting; z=60000-northing; 1 block/metre"

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def _tags(feature: Mapping[str, Any]) -> dict[str, Any]:
    props = feature.get("properties") or {}
    return {**props, **(props.get("tags") or {})}


def _id(feature: Mapping[str, Any]) -> str:
    props = feature.get("properties") or {}
    value = feature.get("id", props.get("osm_id", props.get("id")))
    if value is None:
        raise ValueError("Every input feature requires a stable source id")
    return str(value)


def _point(value: Any) -> Point:
    if not isinstance(value, (list, tuple)) or len(value) < 2:
        raise ValueError("Expected projected [x,z] coordinate")
    point = (float(value[0]), float(value[1]))
    if not all(math.isfinite(v) for v in point):
        raise ValueError("Non-finite projected coordinate")
    return point


def _on(value: Any) -> bool:
    return str(value or "").strip().lower() not in NO


def _layer(tags: Mapping[str, Any]) -> int | None:
    text = str(tags.get("layer", "0")).strip()
    return int(text) if re.fullmatch(r"[+-]?\d+", text) else None


def _mode(tags: Mapping[str, Any]) -> str:
    bridge = _on(tags.get("bridge"))
    tunnel = _on(tags.get("tunnel"))
    if bridge and tunnel:
        return "conflicting_structure"
    if str(tags.get("tunnel", "")).lower() == "building_passage":
        return "covered_passage"
    if tunnel or tags.get("location") == "underground":
        return "tunnel"
    if bridge or tags.get("location") == "overground":
        return "bridge"
    if _layer(tags) not in (0, None):
        return "unresolved_layer"
    return "surface"


def _round_y(value: float) -> int:
    # Explicit half-away-from-zero quantization; Python round uses bankers' ties.
    return math.floor(value + 0.5) if value >= 0 else math.ceil(value - 0.5)


def vertical_policy(feature: Mapping[str, Any], *, ground_y: float | None = None,
                    terrain_sampler: TerrainSampler | None = None,
                    y_offset: float = 0.0, x: float | None = None,
                    z: float | None = None) -> VerticalPolicy:
    """Evaluate a road point. Call per raster sample, not once for a whole way.

    A terrain sample supplies elevation_m, datum, semantics, surveyed, source_id.
    y_offset explicitly maps that source datum to Minecraft zero. DSM samples
    are withheld. An optional feature.properties.vertical_evidence mapping may
    supply the same fields with semantics road_surface/bridge_deck/tunnel_floor;
    for measured structures it MUST include position [x,z], sample_radius_m and
    surveyed=True. A single source height is never extended across a full way.
    """
    fid, tags = _id(feature), _tags(feature)
    mode, layer = _mode(tags), _layer(tags)
    uncertainty: list[str] = []
    provenance: dict[str, Any] = {"mode_tags": {k: tags[k] for k in
        ("bridge", "tunnel", "layer", "location", "covered", "ele", "height") if k in tags}}
    if layer is None:
        uncertainty.append("Invalid layer: vertical ordering unresolved")
    if mode in {"bridge", "tunnel"} and "layer" not in tags:
        uncertainty.append("Structure has no explicit layer; zero is not a height")
    if "height" in tags or "ele" in tags:
        uncertainty.append("OSM height/ele is retained as evidence, not a surveyed road profile")
    if x is None or z is None:
        geometry = feature.get("geometry") or {}
        coords = geometry.get("coordinates", [])
        x, z = _point(coords if geometry.get("type") == "Point" else coords[0])
    sample = None
    measured = (feature.get("properties") or {}).get("vertical_evidence")
    if isinstance(measured, Mapping) and measured.get("position") is not None:
        position = _point(measured["position"])
        radius = float(measured.get("sample_radius_m", 0))
        if radius >= 0 and math.dist(position, (x, z)) <= radius:
            sample = measured
    if sample is None and mode in {"surface", "covered_passage"} and terrain_sampler:
        sample = terrain_sampler(x, z)
    y, accepted = None, False
    if sample is not None:
        provenance["vertical_sample"] = dict(sample)
        semantic = sample.get("semantics")
        numeric = sample.get("elevation_m")
        valid = (isinstance(numeric, (int, float)) and not isinstance(numeric, bool)
                 and math.isfinite(numeric) and bool(sample.get("datum"))
                 and bool(sample.get("source_id")))
        suitable = semantic in ({"road_surface", "bare_earth", "estimated_ground"}
                    if mode in {"surface", "covered_passage"} else
                    {"bridge_deck"} if mode == "bridge" else
                    {"tunnel_floor"} if mode == "tunnel" else set())
        if valid and suitable and (mode in {"surface", "covered_passage"} or sample.get("surveyed") is True):
            y = _round_y(numeric + y_offset)
            accepted = sample.get("surveyed") is True and semantic != "estimated_ground"
            provenance["y_offset"] = y_offset
            if not accepted:
                uncertainty.append("Ground is estimated or not surveyed; diagnostic geometry only")
        else:
            uncertainty.append("Vertical sample is missing provenance or has unsuitable semantics")
    if y is None and ground_y is not None and mode in {"surface", "covered_passage"}:
        if not math.isfinite(ground_y):
            raise ValueError("ground_y must be finite")
        y = _round_y(ground_y)
        provenance["ground_y_fallback"] = ground_y
        uncertainty.append("Caller-configured flat ground is inferred, not surveyed")
    if y is None:
        uncertainty.append("Road surface elevation unresolved; no blocks may be emitted")
    if layer is None or mode in {"conflicting_structure", "unresolved_layer"}:
        accepted = False
    # Tunnel excavation/deck generation is deliberately outside the surface pass.
    allow_surface = y is not None and mode in {"surface", "covered_passage", "bridge"}
    return VerticalPolicy(fid, mode, layer, y, allow_surface, accepted,
                          provenance, tuple(uncertainty))


@dataclass(frozen=True)
class _Way:
    id: str
    feature: Mapping[str, Any]
    points: tuple[Point, ...]
    refs: tuple[str | None, ...]


def _ways(features: Iterable[Mapping[str, Any]], diagnostics: list) -> list[_Way]:
    result = []
    for f in features:
        geometry = f.get("geometry") or {}
        if not _tags(f).get("highway") or geometry.get("type") != "LineString":
            continue
        points = tuple(_point(p) for p in geometry["coordinates"])
        if len(points) < 2:
            diagnostics.append({"id": _id(f), "reason": "Way has fewer than two positions"})
            continue
        refs = (f.get("properties") or {}).get("nodes", [])
        if refs and len(refs) != len(points):
            raise ValueError(f"{_id(f)}: node refs must align with complete way coordinates")
        result.append(_Way(_id(f), f, points, tuple(str(n) if n is not None else None
                            for n in refs) if refs else (None,) * len(points)))
    return sorted(result, key=lambda w: w.id)


def _cross(a: Point, b: Point) -> float:
    return a[0] * b[1] - a[1] * b[0]


def _subtract(a: Point, b: Point) -> Point:
    return a[0] - b[0], a[1] - b[1]


def _segment_hits(a: Point, b: Point, c: Point, d: Point, eps: float) -> list[Point]:
    r, s = _subtract(b, a), _subtract(d, c)
    lengths = math.hypot(*r), math.hypot(*s)
    if min(lengths) <= eps:
        return []
    denominator = _cross(r, s)
    relative = _subtract(c, a)
    if abs(denominator) <= eps * max(lengths):
        if abs(_cross(relative, r)) > eps * lengths[0]:
            return []
        # A collinear overlap is not a new intersection. Only actual coincident
        # ends connect; overlaps are reported separately by the caller.
        return sorted({p for p in (a, b) for q in (c, d) if math.dist(p, q) <= eps})
    t, u = _cross(relative, s) / denominator, _cross(relative, r) / denominator
    if -eps / lengths[0] <= t <= 1 + eps / lengths[0] and -eps / lengths[1] <= u <= 1 + eps / lengths[1]:
        t = max(0.0, min(1.0, t))
        return [(a[0] + t * r[0], a[1] + t * r[1])]
    return []


def _relation(a: _Way, b: _Way, shared: bool, endpoint: bool) -> tuple[str, tuple[str, ...]]:
    ta, tb = _tags(a.feature), _tags(b.feature)
    ma, mb, la, lb = _mode(ta), _mode(tb), _layer(ta), _layer(tb)
    if la is None or lb is None or "conflicting_structure" in (ma, mb):
        return "unresolved", ("Invalid or conflicting vertical tags",)
    surface = {"surface", "covered_passage"}
    if la != lb or (ma != mb and not {ma, mb} <= surface):
        if shared and endpoint:
            return "transition", ("Mapped shared endpoint joins differing vertical sections; profile needs evidence",)
        if shared:
            return "unresolved", ("Shared node conflicts with grade-separation tags",)
        return "grade_separated", ()
    if ma in surface and mb in surface:
        return ("at_grade", ()) if shared else ("geometric_candidate", ("OSM shared-node connectivity absent; do not automatically join roads",))
    if shared:
        return "at_grade", ("Mapped connectivity is known; structure elevation still requires evidence",)
    return "unresolved", ("Equal structure layers do not establish shared connectivity or elevation",)


def _distance(p: Point, a: Point, b: Point) -> float:
    delta = _subtract(b, a)
    length2 = delta[0] ** 2 + delta[1] ** 2
    if not length2:
        return math.dist(p, a)
    t = max(0.0, min(1.0, ((p[0] - a[0]) * delta[0] + (p[1] - a[1]) * delta[1]) / length2))
    return math.dist(p, (a[0] + t * delta[0], a[1] + t * delta[1]))


def _mapped_crossings(features, ways, tolerance, buckets, spatial_cell_m):
    result = []
    by_ref: dict[str, set[int]] = {}
    for wi, way in enumerate(ways):
        for ref in way.refs:
            if ref is not None:
                by_ref.setdefault(ref, set()).add(wi)
    for feature in sorted(features, key=_id):
        tags, geo = _tags(feature), feature.get("geometry") or {}
        kind, highway = geo.get("type"), tags.get("highway")
        mapped = (kind == "Point" and highway == "crossing") or (
            kind == "LineString" and highway in PATHS and tags.get(highway) == "crossing")
        if not mapped or tags.get("crossing") == "no":
            continue
        points = [_point(geo["coordinates"])] if kind == "Point" else [_point(p) for p in geo["coordinates"]]
        refs = {str(n) for n in (feature.get("properties") or {}).get("nodes", [])}
        if kind == "Point":
            refs.add(_id(feature).removeprefix("node/"))
        matched = set()
        candidates = set()
        for ref in refs:
            candidates.update(by_ref.get(ref, ()))
        for gx in range(math.floor((min(p[0] for p in points)-tolerance)/spatial_cell_m), math.floor((max(p[0] for p in points)+tolerance)/spatial_cell_m)+1):
            for gz in range(math.floor((min(p[1] for p in points)-tolerance)/spatial_cell_m), math.floor((max(p[1] for p in points)+tolerance)/spatial_cell_m)+1):
                candidates.update(wi for wi, _ in buckets.get((gx, gz), ()))
        for wi in sorted(candidates):
            way = ways[wi]
            if _tags(way.feature).get("highway") in PATHS or _id(feature) == way.id:
                continue
            if refs.intersection(n for n in way.refs if n is not None):
                matched.add(way.id)
                continue
            road_tags = _tags(way.feature)
            if (_mode(road_tags) not in {"surface", "covered_passage"}
                    or _mode(tags) not in {"surface", "covered_passage"}
                    or _layer(road_tags) != _layer(tags)):
                continue
            if kind == "Point" and any(_distance(points[0], a, b) <= tolerance for a, b in zip(way.points, way.points[1:])):
                matched.add(way.id)
            elif kind == "LineString" and any(_segment_hits(a, b, c, d, tolerance)
                    for a, b in zip(points, points[1:]) for c, d in zip(way.points, way.points[1:])):
                matched.add(way.id)
        raw = str(tags.get("crossing:markings", "")).lower()
        if raw in {"no", "none"} or tags.get("crossing") == "unmarked":
            markings = "none"
        elif raw == "zebra" or tags.get("crossing") == "zebra":
            markings = "zebra"
        elif raw:
            markings = "marked_unspecified" if raw == "yes" else raw
        else:
            markings = "unknown"
        uncertainty = []
        if not matched:
            uncertainty.append("No exact road association; marker retained without invented road snapping")
        if markings == "unknown":
            uncertainty.append("Mapped crossing does not specify painted markings")
        if kind == "Point":
            uncertainty.append("Point location supplies no crosswalk extent or orientation")
        width = str(tags.get("width", ""))
        has_width = bool(re.fullmatch(r"\d+(\.\d+)?", width)) and float(width) > 0
        if kind == "LineString" and not has_width:
            uncertainty.append("Mapped crossing axis has no explicit width; no complete paint footprint")
        result.append(MappedCrossing(_id(feature), dict(geo), tuple(sorted(matched)), markings,
            kind == "LineString" and has_width and markings not in {"none", "unknown"},
            {"tags": {k: v for k, v in tags.items() if k in {"highway", "width"} or k.startswith("crossing") or k in PATHS},
             "source_feature_id": _id(feature)}, tuple(uncertainty)))
    return result


def analyze_topology(features: Iterable[Mapping[str, Any]] | Mapping[str, Any], *,
                     ground_y: float | None = None, terrain_sampler: TerrainSampler | None = None,
                     y_offset: float = 0.0, tolerance: float = 1e-7,
                     spatial_cell_m: float = 128.0) -> TopologyResult:
    """Find deterministic junctions without connecting grade-separated roads.

    Accept a FeatureCollection or feature iterable. Geometry uses metre units;
    tolerance is numerical, not a road-snapping radius. Complete ways must be
    supplied before half-open tile ownership is applied by the raster caller.
    """
    if not math.isfinite(tolerance) or tolerance <= 0 or spatial_cell_m <= 0 or not math.isfinite(spatial_cell_m):
        raise ValueError("Tolerance and spatial cell size must be positive and finite")
    if not math.isfinite(y_offset):
        raise ValueError("y_offset must be finite")
    features = list(features.get("features", []) if isinstance(features, Mapping) else features)
    ids = [_id(f) for f in features]
    if len(set(ids)) != len(ids):
        raise ValueError("Duplicate source feature IDs")
    diagnostics: list[dict[str, Any]] = []
    ways = _ways(features, diagnostics)
    policies = [vertical_policy(w.feature, ground_y=ground_y, terrain_sampler=terrain_sampler,
                y_offset=y_offset) for w in ways]
    nodes: dict[str, list[tuple[int, int]]] = {}
    for wi, way in enumerate(ways):
        for pi, ref in enumerate(way.refs):
            if ref is not None:
                nodes.setdefault(ref, []).append((wi, pi))
    events = {}

    def add(ai, bi, point, ref=None, endpoint=False):
        if ai == bi:
            return
        a, b = ways[ai], ways[bi]
        relation, uncertainty = _relation(a, b, ref is not None, endpoint)
        key = (tuple(sorted((a.id, b.id))), round(point[0] / tolerance), round(point[1] / tolerance))
        evidence = "shared_osm_node" if ref is not None else "geometric_crossing"
        existing = events.get(key)
        if existing and existing.evidence == "shared_osm_node":
            return
        x, z = round(point[0], 8), round(point[1], 8)
        events[key] = Intersection(f"{key[0][0]}|{key[0][1]}@{x:.8f},{z:.8f}", x, z,
            (math.floor(x), math.floor(z)), key[0], relation, evidence, () if ref is None else (ref,), uncertainty)

    for ref, positions in sorted(nodes.items()):
        for (ai, ap), (bi, bp) in combinations(positions, 2):
            if ai == bi:
                continue
            pa, pb = ways[ai].points[ap], ways[bi].points[bp]
            if math.dist(pa, pb) > tolerance:
                diagnostics.append({"node_ref": ref, "reason": "Shared node has inconsistent projected coordinates",
                                    "way_ids": [ways[ai].id, ways[bi].id]})
                continue
            ends = ap in {0, len(ways[ai].points)-1} and bp in {0, len(ways[bi].points)-1}
            add(ai, bi, pa, ref, ends)
    # Grid broad phase keeps full-island candidate work local, instead of testing
    # every road against every other road. Pairs are visited once across cells.
    buckets: dict[tuple[int, int], list[tuple[int, int]]] = {}
    for wi, way in enumerate(ways):
        for si, (a, b) in enumerate(zip(way.points, way.points[1:])):
            for gx in range(math.floor((min(a[0], b[0])-tolerance)/spatial_cell_m), math.floor((max(a[0], b[0])+tolerance)/spatial_cell_m)+1):
                for gz in range(math.floor((min(a[1], b[1])-tolerance)/spatial_cell_m), math.floor((max(a[1], b[1])+tolerance)/spatial_cell_m)+1):
                    buckets.setdefault((gx, gz), []).append((wi, si))
    seen = set()
    for cell in sorted(buckets):
        for left, right in combinations(buckets[cell], 2):
            if left[0] == right[0]:
                continue
            pair = tuple(sorted((left, right)))
            if pair in seen:
                continue
            seen.add(pair)
            ai, asi = left
            bi, bsi = right
            for point in _segment_hits(*ways[ai].points[asi:asi+2], *ways[bi].points[bsi:bsi+2], tolerance):
                add(ai, bi, point)
    return TopologyResult(tuple(policies), tuple(sorted(events.values(), key=lambda e: e.id)),
                          tuple(_mapped_crossings(features, ways, tolerance, buckets, spatial_cell_m)), tuple(diagnostics))
