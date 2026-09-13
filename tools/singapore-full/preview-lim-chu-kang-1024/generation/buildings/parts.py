"""Whole-feature building ownership, roofs and part precedence (Python stdlib).

Inputs are already projected EPSG:3414: x=E, z=60000-N, metres/blocks.
Keep these footprints intact when visiting tiles; clip emitted columns only.
This module does not claim that OSM tags determine an exact surveyed roof.
Tag semantics: https://wiki.openstreetmap.org/wiki/Simple_3D_Buildings
"""
from dataclasses import dataclass
import math
import re
from typing import Iterable, Mapping, Optional, Tuple

Point = Tuple[float, float]
Ring = Tuple[Point, ...]
Tile = Tuple[int, int]
EPS = 1e-9


def _ring(points: Iterable[Point]) -> Ring:
    result = tuple((float(x), float(z)) for x, z in points)
    if len(result) > 1 and result[0] == result[-1]:
        result = result[:-1]
    if len(result) < 3 or any(not math.isfinite(v) for p in result for v in p):
        raise ValueError("Ring needs at least three finite points")
    if abs(_area(result)) <= EPS:
        raise ValueError("Ring has zero area")
    return result


def _area(ring: Ring) -> float:
    if len(ring) < 3:
        return 0.0
    # Local origin avoids cancellation at Singapore's large projected coordinates.
    ox, oz = ring[0]
    return sum((a[0]-ox)*(b[1]-oz) - (b[0]-ox)*(a[1]-oz)
               for a, b in zip(ring, ring[1:] + ring[:1])) / 2.0


@dataclass(frozen=True)
class Footprint:
    outer: Ring
    holes: Tuple[Ring, ...] = ()

    def __post_init__(self):
        object.__setattr__(self, "outer", _ring(self.outer))
        object.__setattr__(self, "holes", tuple(_ring(h) for h in self.holes))
        if self.area <= EPS:
            raise ValueError("Footprint must have positive area after holes")

    @property
    def bounds(self):
        xs, zs = zip(*self.outer)
        return min(xs), min(zs), max(xs), max(zs)

    @property
    def area(self) -> float:
        return abs(_area(self.outer)) - sum(abs(_area(h)) for h in self.holes)

    def contains(self, x: float, z: float) -> bool:
        return _inside(self.outer, x, z) and not any(_inside(h, x, z) for h in self.holes)


def _inside(ring: Ring, x: float, z: float) -> bool:
    inside = False
    for (ax, az), (bx, bz) in zip(ring, ring[1:] + ring[:1]):
        cross = (x-ax)*(bz-az) - (z-az)*(bx-ax)
        if abs(cross) <= EPS and min(ax, bx)-EPS <= x <= max(ax, bx)+EPS and min(az, bz)-EPS <= z <= max(az, bz)+EPS:
            return True
        if (az > z) != (bz > z) and x < ax + (z-az)*(bx-ax)/(bz-az):
            inside = not inside
    return inside


def feature_identity(osm_type: str, osm_id) -> str:
    """Retain OSM source type: relation/42 and way/42 are distinct features."""
    if osm_type not in {"node", "way", "relation"}:
        raise ValueError("Unknown OSM element type")
    text = str(osm_id)
    if not re.fullmatch(r"[0-9]+", text):
        raise ValueError("OSM identity must be a non-negative integer")
    return "%s/%d" % (osm_type, int(text))


def contains_point(footprint: Footprint, x: float, z: float) -> bool:
    return footprint.contains(x, z)


def _tile_size(size: float) -> float:
    size = float(size)
    if not math.isfinite(size) or size <= 0:
        raise ValueError("tile_size must be positive and finite")
    return size


def owner_tile(footprint: Footprint, tile_size: float = 512) -> Tile:
    """Stable metadata owner at full bounding-box minimum; not a render filter."""
    size = _tile_size(tile_size)
    x, z, _, _ = footprint.bounds
    return math.floor(x / size), math.floor(z / size)


def _clip(ring: Ring, axis: int, boundary: float, keep_greater: bool) -> Ring:
    if not ring:
        return ()
    out = []
    previous = ring[-1]
    prev_in = previous[axis] >= boundary if keep_greater else previous[axis] <= boundary
    for current in ring:
        cur_in = current[axis] >= boundary if keep_greater else current[axis] <= boundary
        if prev_in != cur_in:
            t = (boundary-previous[axis])/(current[axis]-previous[axis])
            other = 1-axis
            point = [0.0, 0.0]
            point[axis] = boundary
            point[other] = previous[other] + t*(current[other]-previous[other])
            out.append(tuple(point))
        if cur_in:
            out.append(current)
        previous, prev_in = current, cur_in
    return tuple(out)


def _clipped_area(ring: Ring, bounds) -> float:
    x0, z0, x1, z1 = bounds
    for axis, edge, greater in ((0,x0,True),(0,x1,False),(1,z0,True),(1,z1,False)):
        ring = _clip(ring, axis, edge, greater)
    return abs(_area(ring))


def intersecting_tiles(footprint: Footprint, tile_size: float = 512) -> Tuple[Tile, ...]:
    """Tiles with positive area overlap, excluding pure edge/corner contact."""
    size = _tile_size(tile_size)
    x0, z0, x1, z1 = footprint.bounds
    result = []
    for tx in range(math.floor(x0/size), math.ceil(x1/size)):
        for tz in range(math.floor(z0/size), math.ceil(z1/size)):
            bounds = tx*size, tz*size, (tx+1)*size, (tz+1)*size
            overlap = _clipped_area(footprint.outer, bounds) - sum(_clipped_area(h, bounds) for h in footprint.holes)
            if overlap > EPS:
                result.append((tx, tz))
    return tuple(result)


@dataclass(frozen=True)
class RoofSpec:
    shape: str = "flat"
    height_m: float = 0.0
    ridge_axis: str = "auto"
    estimated: bool = False
    provenance: str = "explicit flat roof"
    source_shape: str = "flat"
    orientation: str = "along"

    def __post_init__(self):
        if self.shape not in {"flat", "gabled", "hipped"}:
            raise ValueError("Use parse_roof for labeled unsupported-shape fallback")
        if not math.isfinite(self.height_m) or self.height_m < 0:
            raise ValueError("Roof height must be finite and non-negative")
        if self.ridge_axis not in {"auto", "x", "z"}:
            raise ValueError("ridge_axis must be auto, x, or z")
        if self.orientation not in {"along", "across"}:
            raise ValueError("orientation must be along or across")


def _metres(value) -> Optional[float]:
    if value is None:
        return None
    match = re.fullmatch(r"\s*([+]?(?:\d+(?:\.\d*)?|\.\d+))\s*(m|metres?|meters?)?\s*", str(value), re.I)
    if not match:
        return None
    number = float(match.group(1))
    return number if math.isfinite(number) else None


def parse_roof(tags: Mapping[str, str]) -> RoofSpec:
    """Never silently invent roof height/direction or emulate an unknown shape.

    A non-flat roof with missing height becomes a labeled estimated flat roof.
    roof:direction is the downslope bearing; only cardinal bearings are mapped
    to bbox axes. Non-cardinal directions explicitly fall back to estimated flat.
    OSM height is TOTAL height; caller subtracts height_m for the wall top.
    """
    source = str(tags.get("roof:shape", "")).strip().lower()
    shape = source or "flat"
    height = _metres(tags.get("roof:height"))
    if shape not in {"flat", "gabled", "hipped"}:
        return RoofSpec(estimated=True, provenance="unsupported roof:shape; estimated flat", source_shape=source)
    if shape == "flat":
        return RoofSpec(estimated=not bool(source), provenance="explicit flat roof" if source else "missing roof shape; estimated flat", source_shape=source)
    if height is None or height <= 0:
        return RoofSpec(estimated=True, provenance="missing/invalid pitched roof height; estimated flat", source_shape=source)
    axis = "auto"
    orientation = tags.get("roof:orientation", "along")
    if orientation not in {"along", "across"}:
        return RoofSpec(estimated=True, provenance="invalid roof orientation; estimated flat", source_shape=source)
    reason = "tagged height; estimated roof from whole-footprint bounding box"
    direction = tags.get("roof:direction")
    if direction is not None:
        try:
            bearing = float(direction) % 360
        except (ValueError, TypeError):
            bearing = float("nan")
        if not math.isfinite(bearing) or min(abs(bearing-v) for v in (0,90,180,270,360)) > 1e-7:
            return RoofSpec(estimated=True, provenance="unsupported non-cardinal/invalid roof direction; estimated flat", source_shape=source)
        axis = "x" if bearing in (0,180) else "z"
    return RoofSpec(shape, height, axis, True, reason, source, orientation)


def roof_height_at(footprint: Footprint, x: float, z: float, roof: RoofSpec) -> float:
    """Rise at a sample, calculated exclusively against the full footprint."""
    if not footprint.contains(x, z) or roof.shape == "flat" or roof.height_m == 0:
        return 0.0
    x0, z0, x1, z1 = footprint.bounds
    width, depth = x1-x0, z1-z0
    if roof.shape == "hipped":
        fraction = min(x-x0, x1-x, z-z0, z1-z) / (min(width, depth)/2)
    else:
        axis = roof.ridge_axis if roof.ridge_axis != "auto" else ("x" if width >= depth else "z")
        if roof.ridge_axis == "auto" and roof.orientation == "across":
            axis = "z" if axis == "x" else "x"
        fraction = 1-abs((z-(z0+z1)/2)/(depth/2)) if axis == "x" else 1-abs((x-(x0+x1)/2)/(width/2))
    return roof.height_m * min(1.0, max(0.0, fraction))


@dataclass(frozen=True)
class BuildingPart:
    identity: str
    footprint: Footprint
    min_height: float
    wall_height: float
    roof: RoofSpec = RoofSpec()
    priority: int = 0
    parent_identity: Optional[str] = None

    def __post_init__(self):
        if not self.identity or any(not math.isfinite(v) for v in (self.min_height, self.wall_height)):
            raise ValueError("Part requires identity and finite elevations")
        if self.min_height < 0 or self.wall_height < self.min_height:
            raise ValueError("Wall top must be at or above non-negative base")

    def top_at(self, x: float, z: float) -> float:
        return self.wall_height + roof_height_at(self.footprint, x, z, self.roof)


@dataclass(frozen=True)
class ColumnSegment:
    min_height: float
    max_height: float
    identity: str
    is_part: bool


def _related(shell: BuildingPart, parts: Iterable[BuildingPart]):
    result = [part for part in parts if part.parent_identity is None or part.parent_identity == shell.identity]
    if len({part.identity for part in result}) != len(result):
        raise ValueError("Duplicate part identity; normalize relation members once upstream")
    return sorted(result, key=lambda p: (-p.priority, p.footprint.area, p.identity))


def select_part_at(shell: BuildingPart, parts: Iterable[BuildingPart], x: float, z: float) -> Optional[BuildingPart]:
    """Deterministic plan-view winner; use column_segments for stacked parts."""
    related = _related(shell, parts)
    covering = [part for part in related if part.footprint.contains(x,z)]
    return covering[0] if covering else (shell if not related and shell.footprint.contains(x,z) else None)


def column_segments(shell: BuildingPart, parts: Iterable[BuildingPart], x: float, z: float) -> Tuple[ColumnSegment, ...]:
    """Disjoint solids. Any related parts replace the entire outline shell.

    A shell is coarse metadata, so it must not refill an elevated part's air gap.
    Overlapping part volumes choose priority, smaller full footprint, then ID.
    Multipolygon relations should call this per normalized polygon, retaining ID.
    """
    related = _related(shell, parts)
    covering = [part for part in related if part.footprint.contains(x,z)]
    is_part = bool(related)
    if not related:
        covering = [shell] if shell.footprint.contains(x,z) else []
    intervals = [(p.min_height, p.top_at(x,z), p) for p in covering]
    levels = sorted({v for lo,hi,_ in intervals for v in (lo,hi)})
    result = []
    for lo, hi in zip(levels, levels[1:]):
        if hi-lo <= EPS:
            continue
        midpoint = (lo+hi)/2
        winner = next((p for start,end,p in intervals if start <= midpoint < end), None)
        if winner is None:
            continue
        if result and result[-1].identity == winner.identity and abs(result[-1].max_height-lo) <= EPS:
            previous = result.pop()
            result.append(ColumnSegment(previous.min_height, hi, winner.identity, is_part))
        else:
            result.append(ColumnSegment(lo, hi, winner.identity, is_part))
    return tuple(result)
