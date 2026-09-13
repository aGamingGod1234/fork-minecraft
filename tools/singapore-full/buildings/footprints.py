"""Deterministic building footprints in already-projected global Minecraft metres.

Input coordinates MUST already be x=EPSG:3414 easting, z=60000-northing.
This module does not project longitude/latitude, simplify geometry, or clip rings.
Rasterization uses cell centres, a half-open ray crossing rule, and row-major
(z, then x) order. Tile bounds are integer [min_x,min_z,max_x,max_z), so independently
generated adjacent tiles have neither duplicate cells nor seams.

OSM heights describe the total building including its roof. Missing measurements
are explicitly marked as estimates. Vertical bounds are bottom-inclusive and
top-exclusive, conservatively covering fractional heights; overflow is an error.

Tag semantics verified 2026-09-13 against the primary OSM specification:
https://wiki.openstreetmap.org/wiki/Key:height
https://wiki.openstreetmap.org/wiki/Key:building:levels
https://wiki.openstreetmap.org/wiki/Key:min_height
"""

from dataclasses import dataclass
from math import ceil, floor, isfinite
from numbers import Real
import re
from typing import Iterator, Mapping, Sequence

Point = tuple[float, float]
Ring = tuple[Point, ...]
Polygon = tuple[Ring, ...]  # exterior, followed by holes; rings omit duplicate closure
Bounds = tuple[float, float, float, float]


class FootprintError(ValueError):
    """Invalid or unsupported source geometry/height."""


class HeightOverflow(FootprintError):
    """A building cannot fit in the requested world's vertical range."""


@dataclass(frozen=True)
class HeightInfo:
    value_m: float
    source: str
    estimated: bool
    warnings: tuple[str, ...] = ()


@dataclass(frozen=True)
class BuildingFootprint:
    feature_id: str
    polygons: tuple[Polygon, ...]
    height: HeightInfo
    min_height: HeightInfo
    bounds: Bounds
    tags: tuple[tuple[str, str], ...]

    @property
    def height_m(self) -> float:
        return self.height.value_m

    @property
    def min_height_m(self) -> float:
        return self.min_height.value_m

    @property
    def warnings(self) -> tuple[str, ...]:
        return self.height.warnings + self.min_height.warnings


_METRES = re.compile(r"^([+-]?(?:\d+(?:\.\d*)?|\.\d+))\s*(m|metres?|meters?|ft|feet|foot|')?$", re.I)
_FEET_INCHES = re.compile(r'''^([+-]?(?:\d+(?:\.\d*)?|\.\d+))\s*'\s*(\d+(?:\.\d*)?)\s*(?:"|in)?$''', re.I)


def _number(value, name: str) -> float:
    if isinstance(value, bool):
        raise FootprintError(f"{name} must be a finite number, not boolean")
    try:
        result = float(value)
    except (ValueError, TypeError, OverflowError) as exc:
        raise FootprintError(f"{name} must be a finite number") from exc
    if not isfinite(result):
        raise FootprintError(f"{name} must be finite")
    return result


def parse_length_m(value) -> float:
    """Read a single OSM metre or foot/inch measurement; reject ranges/lists."""
    if isinstance(value, Real) and not isinstance(value, bool):
        return _number(value, "height")
    if not isinstance(value, str):
        raise FootprintError("height must be a number or a length string")
    text = value.strip()
    feet = _FEET_INCHES.fullmatch(text)
    if feet:
        ft, inches = map(float, feet.groups())
        if ft < 0 or inches >= 12:
            raise FootprintError("invalid feet/inches height")
        return _number(ft * 0.3048 + inches * 0.0254, "height")
    match = _METRES.fullmatch(text)
    if not match:
        raise FootprintError(f"unsupported height measurement: {value!r}")
    result = float(match.group(1))
    unit = (match.group(2) or "m").lower()
    if unit in ("ft", "feet", "foot", "'"):
        result *= 0.3048
    return _number(result, "height")


def _source_tags(feature: Mapping) -> dict:
    properties = feature.get("properties") or {}
    if not isinstance(properties, Mapping):
        raise FootprintError("feature.properties must be an object")
    # Flat GeoJSON properties override nested OSM tags when both are supplied.
    nested = properties.get("tags") or {}
    if not isinstance(nested, Mapping):
        raise FootprintError("properties.tags must be an object")
    tags = dict(nested)
    tags.update({key: value for key, value in properties.items() if key != "tags"})
    return tags


def normalize_height(tags: Mapping, *, floor_height: float = 3.0,
                     default_height: float = 6.0, minimum: bool = False) -> HeightInfo:
    """Resolve explicit height, levels estimate, then a labelled default.

    Inferred total height is building:levels * floor_height plus roof:height if
    available. Without roof:height no unmeasured roof is silently added. Minimum
    height uses min_height or building:min_level; absent minimum means ground.
    roof:levels is not added as if it were measured metres; absent roof:height
    leaves that extra roof extent unresolved and the result remains estimated.
    Malformed tags are recorded in warnings before trying a documented fallback.
    """
    floor_height = _number(floor_height, "floor_height")
    default_height = _number(default_height, "default_height")
    if floor_height <= 0 or default_height <= 0:
        raise FootprintError("floor_height and default_height must be positive")
    explicit = "min_height" if minimum else "height"
    levels_key = "building:min_level" if minimum else "building:levels"
    warnings = []
    if explicit in tags:
        try:
            value = parse_length_m(tags[explicit])
            if value < 0 or (not minimum and value == 0):
                raise FootprintError("must be positive" if not minimum else "must be nonnegative")
            return HeightInfo(value, explicit, False)
        except FootprintError as exc:
            warnings.append(f"invalid {explicit}: {exc}")
    if levels_key in tags:
        try:
            levels = _number(tags[levels_key], levels_key)
            if levels < 0 or (not minimum and levels == 0):
                raise FootprintError("must be positive" if not minimum else "must be nonnegative")
            value = levels * floor_height
            source = levels_key
            if not minimum and "roof:height" in tags:
                try:
                    roof = parse_length_m(tags["roof:height"])
                    if roof < 0:
                        raise FootprintError("must be nonnegative")
                    value += roof
                    source += "+roof:height"
                except FootprintError as exc:
                    warnings.append(f"invalid roof:height: {exc}")
            return HeightInfo(_number(value, "inferred height"), source, True, tuple(warnings))
        except FootprintError as exc:
            warnings.append(f"invalid {levels_key}: {exc}")
    return HeightInfo(0.0 if minimum else default_height,
                      "ground_default" if minimum else "default_height", True, tuple(warnings))


def _ring(coordinates: Sequence, *, exterior: bool) -> Ring:
    if not isinstance(coordinates, (list, tuple)):
        raise FootprintError("ring coordinates must be an array")
    points = []
    for coordinate in coordinates:
        if not isinstance(coordinate, (list, tuple)) or len(coordinate) < 2:
            raise FootprintError("each coordinate requires projected x and z")
        point = (_number(coordinate[0], "x"), _number(coordinate[1], "z"))
        if not points or point != points[-1]:
            points.append(point)
    if len(points) > 1 and points[0] == points[-1]:
        points.pop()
    if len(set(points)) < 3:
        raise FootprintError("ring needs at least three distinct points")
    # Translate before the shoelace sum to retain precision at large eastings.
    ox, oz = points[0]
    area = sum((a[0] - ox) * (b[1] - oz) - (b[0] - ox) * (a[1] - oz)
               for a, b in zip(points, points[1:] + points[:1]))
    if not isfinite(area) or area == 0:
        raise FootprintError("ring has zero or non-finite signed area")
    if (area > 0) != exterior:
        points.reverse()
    start = min(range(len(points)), key=points.__getitem__)
    return tuple(points[start:] + points[:start])


def normalize_feature(feature: Mapping, *, floor_height: float = 3.0,
                      default_height: float = 6.0) -> BuildingFootprint:
    """Normalize one projected GeoJSON Polygon/MultiPolygon without clipping.

    Source polygons must be simple valid GeoJSON rings (the module checks finite
    coordinates and nonzero area but is not a full topology repair library).
    Feature IDs and source height provenance are retained for audit and rejection.
    Geometry Z/altitude, if present, is ignored; ground_y is a separate input.
    """
    if not isinstance(feature, Mapping) or feature.get("type") != "Feature":
        raise FootprintError("expected a GeoJSON Feature")
    geometry = feature.get("geometry")
    if not isinstance(geometry, Mapping):
        raise FootprintError("feature needs geometry")
    kind, coordinates = geometry.get("type"), geometry.get("coordinates")
    if kind == "Polygon":
        components = [coordinates]
    elif kind == "MultiPolygon":
        components = coordinates
    else:
        raise FootprintError(f"unsupported geometry type: {kind!r}")
    if not isinstance(components, (list, tuple)) or not components:
        raise FootprintError("geometry has no polygons")
    polygons = []
    for component in components:
        if not isinstance(component, (list, tuple)) or not component:
            raise FootprintError("polygon has no exterior ring")
        outer = _ring(component[0], exterior=True)
        holes = tuple(sorted(_ring(r, exterior=False) for r in component[1:]))
        polygons.append((outer,) + holes)
    polygons = tuple(sorted(polygons))
    points = [point for polygon in polygons for point in polygon[0]]
    bounds = (min(p[0] for p in points), min(p[1] for p in points),
              max(p[0] for p in points), max(p[1] for p in points))
    tags = _source_tags(feature)
    height = normalize_height(tags, floor_height=floor_height, default_height=default_height)
    minimum = normalize_height(tags, floor_height=floor_height,
                               default_height=default_height, minimum=True)
    # With no total-height claim, the default is a span above a known minimum,
    # not an absolute roof elevation below an elevated building part. Explicit
    # or malformed total-height/levels tags retain their existing strict policy.
    if (height.source == "default_height" and "height" not in tags
            and "building:levels" not in tags
            and minimum.source in ("min_height", "building:min_level")
            and minimum.value_m > 0):
        height = HeightInfo(
            _number(minimum.value_m + height.value_m, "estimated total height"),
            minimum.source + "+default_span", True,
            height.warnings + (
                "height and building:levels absent; estimated total as resolved "
                "minimum plus default span; not a measured total",))
    if minimum.value_m >= height.value_m:
        raise FootprintError(f"min_height {minimum.value_m} must be below total height {height.value_m}")
    identifier = feature.get("id", tags.get("@id", tags.get("id", "")))
    return BuildingFootprint(str(identifier), polygons, height, minimum, bounds,
                             tuple(sorted((str(k), str(v)) for k, v in tags.items())))


def _inside_ring(x: float, z: float, ring: Ring) -> bool:
    inside = False
    previous = ring[-1]
    for current in ring:
        ax, az = previous
        bx, bz = current
        if (az > z) != (bz > z):
            crossing_x = ax + (z - az) * (bx - ax) / (bz - az)
            if x < crossing_x:
                inside = not inside
        previous = current
    return inside


def rasterize(building: BuildingFootprint, tile_box: Sequence[int]) -> Iterator[tuple[int, int]]:
    """Yield global (x,z) cells in a bounded integer tile, z then x order.

    Geometry is tested in its original global coordinates, including all holes.
    Multipolygon overlaps are unioned, never emitted twice. Bounds exactly on a
    cell centre use the same half-open crossing convention for every tile.
    Runtime is proportional to requested overlapping cells and source vertices;
    it never iterates the rest of the country or allocates a whole-world mask.
    """
    if len(tile_box) != 4 or any(isinstance(n, bool) or not isinstance(n, int) for n in tile_box):
        raise FootprintError("tile_box requires four integer global bounds")
    tx0, tz0, tx1, tz1 = tile_box
    if tx0 > tx1 or tz0 > tz1:
        raise FootprintError("tile_box minimum must not exceed maximum")
    bx0, bz0, bx1, bz1 = building.bounds
    x0, z0 = max(tx0, ceil(bx0 - 0.5)), max(tz0, ceil(bz0 - 0.5))
    x1, z1 = min(tx1, floor(bx1 - 0.5) + 1), min(tz1, floor(bz1 - 0.5) + 1)
    for z in range(z0, z1):
        for x in range(x0, x1):
            cx, cz = x + 0.5, z + 0.5
            if any(_inside_ring(cx, cz, polygon[0]) and
                   not any(_inside_ring(cx, cz, hole) for hole in polygon[1:])
                   for polygon in building.polygons):
                yield x, z


def vertical_bounds(building: BuildingFootprint, ground_y: float, *,
                    min_y: int = -64, max_y: int = 319) -> tuple[int, int]:
    """Return (bottom_inclusive, top_exclusive) for one ground/elevation column.

    The world's min_y and max_y are BOTH inclusive Minecraft block coordinates.
    ground_y is the building's ground datum (OSM uses the lowest ground contact),
    not a varying per-column terrain height: one datum preserves a level roof.
    Can be called per material column with that same datum. Raises HeightOverflow whenever
    even one occupied voxel would leave that range; callers must report/reject
    or choose a suitable world vertical profile, never silently shorten it.
    """
    ground_y = _number(ground_y, "ground_y")
    if any(isinstance(n, bool) or not isinstance(n, int) for n in (min_y, max_y)) or min_y > max_y:
        raise FootprintError("world Y bounds must be ordered integers")
    bottom = floor(ground_y + building.min_height_m)
    top = ceil(ground_y + building.height_m)
    if bottom < min_y or top - 1 > max_y:
        raise HeightOverflow(f"building {building.feature_id!r} needs occupied Y {bottom}..{top-1}; "
                             f"world permits {min_y}..{max_y}; source height={building.height_m:g}m "
                             f"({building.height.source})")
    return bottom, top
