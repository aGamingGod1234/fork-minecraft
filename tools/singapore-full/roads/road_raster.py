"""Deterministic one-metre OSM road raster, with explicit width provenance.

Input coordinates are Minecraft X/Z in metres, already projected from EPSG:3414
as X=easting, Z=60000-northing. This module does not project WGS84 coordinates.
Roads are round-cap unions of complete segments. A block belongs when its centre
is inside that union, including the boundary. Integer core bounds are half-open.
Raster quantisation is not evidence of surveyed road width. Bridges/tunnels and
terrain heights are the caller's responsibility; y is supplied explicitly.

OSM width semantics: https://wiki.openstreetmap.org/wiki/Key:width
"""
from __future__ import annotations

import hashlib
import json
import math
import re
from collections.abc import Mapping

POLICY_VERSION = "fork-road-width-1"
LANE_WIDTH_M = 3.2  # Rendering assumption, never represented as measured data.
DEFAULT_WIDTHS_M = {
    "motorway": 10.5, "motorway_link": 5.0, "trunk": 9.6, "trunk_link": 5.0,
    "primary": 9.6, "primary_link": 5.0, "secondary": 6.4, "secondary_link": 5.0,
    "tertiary": 6.4, "tertiary_link": 5.0, "unclassified": 6.0,
    "residential": 6.0, "living_street": 4.0, "service": 3.2, "track": 3.0,
    "footway": 2.0, "path": 1.5, "steps": 2.0, "cycleway": 2.5,
    "pedestrian": 5.0, "bridleway": 2.0, "road": 6.0,
}
_NUMBER = r"(?:\d+(?:\.\d*)?|\.\d+)"


def parse_width_m(value):
    """Parse a single positive OSM width in metres, feet, or feet/inches.

    Ambiguous lists/ranges, maxwidth restrictions and unrecognised units are not
    guessed. None means the caller must record a fallback, not claim precision.
    """
    if isinstance(value, bool) or value is None:
        return None
    text = str(value).strip().lower()
    match = re.fullmatch(rf"({_NUMBER})\s*(m|metres?|meters?|ft|feet|foot)?", text)
    if match:
        width = float(match[1]) * (0.3048 if match[2] in ("ft", "feet", "foot") else 1)
    else:
        match = re.fullmatch(rf"({_NUMBER})\s*'\s*(?:({_NUMBER})\s*\")?", text)
        if not match or (match[2] is not None and float(match[2]) >= 12):
            return None
        width = float(match[1]) * 0.3048 + float(match[2] or 0) * 0.0254
    return width if math.isfinite(width) and width > 0 else None


def _lanes(value):
    text = str(value)
    return int(text) if re.fullmatch(r"[1-9]\d?", text) and int(text) <= 24 else None


def normalize_width(tags):
    """Return width_m, source, inferred, inputs, assumptions and rejected tags.

    Only width/width:carriageway are explicit physical widths. est_width,
    lane-based widths and highway defaults always retain inferred=True.
    """
    if not isinstance(tags, Mapping):
        raise ValueError("road tags must be an object")
    highway = tags.get("highway")
    if highway not in DEFAULT_WIDTHS_M:
        raise ValueError(f"unsupported highway type: {highway!r}")
    rejected = {}
    def result(width, source, inferred, inputs, assumptions=()):
        return {"width_m": width, "source": source, "inferred": inferred,
                "inputs": inputs, "assumptions": list(assumptions),
                "rejected": rejected.copy(), "policy_version": POLICY_VERSION}
    for key in ("width", "width:carriageway", "est_width"):
        if key in tags:
            width = parse_width_m(tags[key])
            if width is not None:
                inputs = {key: tags[key]}
                if "source:width" in tags:
                    inputs["source:width"] = tags["source:width"]
                return result(width, "osm:" + key, key == "est_width", inputs)
            rejected[key] = tags[key]
    count = _lanes(tags.get("lanes"))
    lane_inputs = {"lanes": tags["lanes"]} if count is not None else {}
    if "lanes" in tags and count is None:
        rejected["lanes"] = tags["lanes"]
    if count is None:
        # A single directional count does not describe the complete carriageway.
        forward, backward = _lanes(tags.get("lanes:forward")), _lanes(tags.get("lanes:backward"))
        if forward is not None and backward is not None:
            both = _lanes(tags.get("lanes:both_ways")) if "lanes:both_ways" in tags else 0
            if both is not None and forward + backward + both <= 24:
                count = forward + backward + both
                lane_inputs = {k: tags[k] for k in ("lanes:forward", "lanes:backward", "lanes:both_ways") if k in tags}
    if count is not None and highway not in ("footway", "path", "steps", "cycleway", "pedestrian", "bridleway"):
        return result(count * LANE_WIDTH_M, "inferred:lanes", True, lane_inputs,
                      [f"{LANE_WIDTH_M} metres per lane; shoulders and parking not inferred"])
    return result(DEFAULT_WIDTHS_M[highway], "inferred:highway", True,
                  {"highway": highway}, ["project rendering default, not a measured width"])


def _digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                    ensure_ascii=False, allow_nan=False).encode("utf-8")).hexdigest()


def rasterize_road(feature, core, y=0, max_blocks=1_000_000):
    """Return whole feature/digest, width provenance, clipped sorted [x,y,z] blocks.

    core=(min_x,min_z,max_x,max_z), all integer block boundaries. max_blocks
    bounds candidate centre tests as well as output size; raises before partial
    output. The digest is independent of tile/core/y and retains IDs and tags.
    Never pre-clip the supplied centreline: doing so introduces tile-edge caps.
    """
    if not isinstance(feature, Mapping) or feature.get("type") != "Feature":
        raise ValueError("expected GeoJSON Feature")
    # Copy and validate JSON values without mutating the caller's source record.
    whole = json.loads(json.dumps(feature, allow_nan=False))
    geometry = whole.get("geometry") or {}
    if geometry.get("type") != "LineString":
        raise ValueError("road geometry must be a whole LineString")
    coordinates = geometry.get("coordinates")
    if not isinstance(coordinates, list) or not 2 <= len(coordinates) <= 100_000:
        raise ValueError("LineString requires 2..100000 points")
    for point in coordinates:
        if (not isinstance(point, list) or len(point) != 2 or
                any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in point)):
            raise ValueError("coordinates must be finite projected [X,Z] metre pairs")
    if (len(core) != 4 or any(type(v) is not int for v in core) or
            core[0] >= core[2] or core[1] >= core[3]):
        raise ValueError("core must have increasing integer half-open bounds")
    if type(y) is not int or type(max_blocks) is not int or max_blocks < 1:
        raise ValueError("y must be integer and max_blocks must be positive integer")
    properties = whole.get("properties") or {}
    tags = properties.get("tags", properties)
    width = normalize_width(tags)
    radius = width["width_m"] / 2
    segments, candidates = [], 0
    for (ax, az), (bx, bz) in zip(coordinates, coordinates[1:]):
        # The bbox is clipped only for iteration. Distance still uses whole endpoints.
        x0 = max(core[0], math.ceil(min(ax, bx) - radius - 0.5))
        z0 = max(core[1], math.ceil(min(az, bz) - radius - 0.5))
        x1 = min(core[2] - 1, math.floor(max(ax, bx) + radius - 0.5))
        z1 = min(core[3] - 1, math.floor(max(az, bz) + radius - 0.5))
        candidates += max(0, x1 - x0 + 1) * max(0, z1 - z0 + 1)
        if candidates > max_blocks:
            raise ValueError(f"candidate budget exceeded ({candidates} > {max_blocks}); use smaller cores")
        segments.append((ax, az, bx, bz, x0, z0, x1, z1))
    blocks = set()
    for ax, az, bx, bz, x0, z0, x1, z1 in segments:
        dx, dz = bx - ax, bz - az
        length2 = dx * dx + dz * dz
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                px, pz = x + 0.5, z + 0.5
                t = max(0, min(1, ((px - ax) * dx + (pz - az) * dz) / length2)) if length2 else 0
                if (px - (ax + t * dx)) ** 2 + (pz - (az + t * dz)) ** 2 <= radius * radius:
                    blocks.add((x, y, z))
    ordered = [list(block) for block in sorted(blocks)]
    return {"feature": whole, "feature_digest": _digest({"feature": whole, "width": width}),
            "width": width, "core": list(core), "candidate_count": candidates,
            "blocks": ordered, "blocks_digest": _digest(ordered)}