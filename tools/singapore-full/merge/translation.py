"""Strict metric tile ownership; no world writes or implicit coordinate rounding."""
from dataclasses import dataclass
from typing import Iterable, Mapping

CHUNK_SIZE = 16
REGION_CHUNKS = 32
GRID_DEFINITION = "+proj=tmerc +lat_0=1.366666666666667 +lon_0=103.83333333333333 +k=1 +x_0=28001.642 +y_0=38744.572 +ellps=WGS84 +units=m +no_defs"
GRID = {
    "kind": "EPSG:3414", "originEasting": 0, "originNorthing": 60000,
    "blocksPerMeter": 1, "xDirection": "east", "zDirection": "south",
    "definition": GRID_DEFINITION,
}
COORDINATE_CONTRACT = {
    "schemaVersion": 1, "grid": GRID,
    "transform": "x=SVY21_easting; z=60000-SVY21_northing; y=source_y",
    "ownership": "half-open core; halo and region padding excluded",
    "translation": "global_block=Arnis_local_block+tile.renderOrigin",
    "chunkAlignment": 16, "rounding": "none during merge",
}


class CoordinateError(ValueError):
    """Input cannot be translated without an unproven assumption."""


def _integer(value, label):
    if type(value) is not int:
        raise CoordinateError(f"{label} must be an integer, not {value!r}")
    return value


def _pair(value, label):
    if not isinstance(value, (list, tuple)) or len(value) != 2:
        raise CoordinateError(f"{label} must contain exactly two integers")
    return tuple(_integer(n, f"{label}[{i}]") for i, n in enumerate(value))


def svy21_to_block(easting: int, northing: int) -> tuple[int, int]:
    """Transform already globally quantized SVY21 metres, never re-quantize."""
    return (_integer(easting, "easting"), 60000 - _integer(northing, "northing"))


@dataclass(frozen=True)
class ChunkAssignment:
    tile_id: str
    source: tuple[int, int]
    destination: tuple[int, int]

    @property
    def region(self):
        return tuple(c // REGION_CHUNKS for c in self.destination)

    @property
    def region_slot(self):
        return tuple(c % REGION_CHUNKS for c in self.destination)


@dataclass(frozen=True)
class TileTransform:
    tile_id: str
    core_origin: tuple[int, int]
    core_size: int
    halo: int
    render_origin: tuple[int, int]
    render_size: int

    def __post_init__(self):
        if not isinstance(self.tile_id, str) or not self.tile_id:
            raise CoordinateError("tile id must be a nonempty string")
        object.__setattr__(self, "core_origin", _pair(self.core_origin, "coreOrigin"))
        object.__setattr__(self, "render_origin", _pair(self.render_origin, "renderOrigin"))
        for field in ("core_size", "halo", "render_size"):
            _integer(getattr(self, field), field)
        if self.core_size <= 0 or self.halo < 0:
            raise CoordinateError("coreSize must be positive and halo nonnegative")
        if self.render_size != self.core_size + 2 * self.halo:
            raise CoordinateError("renderSize must equal coreSize + 2*halo")
        if self.render_origin != tuple(c - self.halo for c in self.core_origin):
            raise CoordinateError("renderOrigin must equal coreOrigin - halo")
        if any(v % CHUNK_SIZE for v in (*self.core_origin, *self.render_origin,
                                       self.core_size, self.halo, self.render_size)):
            raise CoordinateError("all origins, core size and halo must align to 16 blocks; refusing to round")

    @classmethod
    def from_manifest(cls, manifest: Mapping, metadata: Mapping):
        """Require both generator provenance and actual renderer coordinate metadata."""
        try:
            if manifest["schemaVersion"] != 1 or manifest["status"] != "generated":
                raise CoordinateError("expected generated tile manifest schema 1")
            grid = manifest["grid"]
            for key, value in GRID.items():
                if grid.get(key) != value or isinstance(grid.get(key), bool):
                    raise CoordinateError(f"fixed SVY21 grid mismatch: {key}")
            mapping = manifest["mapping"]
            if mapping["adapter"] != "global-grid-to-Arnis-local":
                raise CoordinateError("unrecognized local coordinate adapter")
            if manifest["qualityGates"]["coordinateAgreement"] is not True:
                raise CoordinateError("source coordinate agreement not proven")
            tile = manifest["tile"]
            result = cls(manifest["id"], tile["coreOrigin"], tile["coreSize"],
                         tile["halo"], tile["renderOrigin"], tile["renderSize"])
            if metadata["projection"] != "local" or metadata["scale"] != 1 or isinstance(metadata["scale"], bool):
                raise CoordinateError("renderer metadata must use local projection at scale 1")
            for key, expected in (("minMcX", 0), ("minMcZ", 0),
                                  ("maxMcX", result.render_size), ("maxMcZ", result.render_size)):
                if _integer(metadata[key], key) != expected:
                    raise CoordinateError(f"renderer {key} does not agree with local render bounds")
            points = mapping["controlPoints"]
            if not isinstance(points, list) or len(points) < 4:
                raise CoordinateError("at least four mapping control points required")
            corners = set()
            for point in points:
                local = (_integer(point["renderX"], "renderX"), _integer(point["renderZ"], "renderZ"))
                global_point = (_integer(point["globalX"], "globalX"), _integer(point["globalZ"], "globalZ"))
                if result.local_to_global(*local) != global_point:
                    raise CoordinateError("mapping control point contradicts render origin")
                corners.add(local)
            required = {(0, 0), (result.render_size, 0), (0, result.render_size),
                        (result.render_size, result.render_size)}
            if not required <= corners:
                raise CoordinateError("mapping does not establish all four local render corners")
            return result
        except (KeyError, TypeError) as exc:
            raise CoordinateError(f"missing or malformed coordinate evidence: {exc}") from exc

    @property
    def delta_chunks(self):
        return tuple(c // CHUNK_SIZE for c in self.render_origin)

    @property
    def source_core_chunks(self):
        low = self.halo // CHUNK_SIZE
        high = (self.halo + self.core_size) // CHUNK_SIZE
        return low, low, high, high

    def core_source_chunks(self):
        x0, z0, x1, z1 = self.source_core_chunks
        return ((x, z) for z in range(z0, z1) for x in range(x0, x1))

    def local_to_global(self, x: int, z: int):
        return (_integer(x, "local x") + self.render_origin[0],
                _integer(z, "local z") + self.render_origin[1])

    def owns_block(self, x: int, z: int):
        x, z = _integer(x, "global x"), _integer(z, "global z")
        return (self.core_origin[0] <= x < self.core_origin[0] + self.core_size and
                self.core_origin[1] <= z < self.core_origin[1] + self.core_size)

    def translate_chunk(self, cx: int, cz: int):
        cx, cz = _integer(cx, "source cx"), _integer(cz, "source cz")
        x0, z0, x1, z1 = self.source_core_chunks
        if not (x0 <= cx < x1 and z0 <= cz < z1):
            raise CoordinateError("source chunk is outside owned core (halo/padding excluded)")
        dx, dz = self.delta_chunks
        return cx + dx, cz + dz

    def to_descriptor(self):
        x0, z0 = (c // CHUNK_SIZE for c in self.core_origin)
        size = self.core_size // CHUNK_SIZE
        return {
            "id": self.tile_id, "coreOrigin": list(self.core_origin), "coreSize": self.core_size,
            "halo": self.halo, "renderOrigin": list(self.render_origin), "renderSize": self.render_size,
            "sourceCoreChunks": list(self.source_core_chunks), "deltaChunks": list(self.delta_chunks),
            "destinationCoreChunks": [x0, z0, x0 + size, z0 + size],
        }


def plan_ownership(transforms: Iterable[TileTransform]) -> tuple[ChunkAssignment, ...]:
    """Deterministic diagnostic plan. It is not an assertion that tile seams pass."""
    tiles = sorted(transforms, key=lambda t: t.tile_id)
    if len({t.tile_id for t in tiles}) != len(tiles):
        raise CoordinateError("duplicate tile id")
    owned = {}
    for tile in tiles:
        for source in tile.core_source_chunks():
            destination = tile.translate_chunk(*source)
            if destination in owned:
                raise CoordinateError(f"ambiguous overlapping core ownership at {destination}: "
                                      f"{owned[destination].tile_id} and {tile.tile_id}")
            owned[destination] = ChunkAssignment(tile.tile_id, source, destination)
    return tuple(sorted(owned.values(), key=lambda a: (a.destination[1], a.destination[0], a.tile_id)))


def require_seam_gate(receipt: Mapping, left_id: str, right_id: str):
    """Fail closed on structural/content disagreement before writing an assembly."""
    if not isinstance(receipt, Mapping):
        raise CoordinateError("actual shared halo seam receipt required")
    if (receipt.get("kind") != "actual-shared-halo-block-comparison"
            or receipt.get("status") != "PASS" or receipt.get("assemblyAccepted") is not True
            or type(receipt.get("mismatches")) is not int or receipt["mismatches"] != 0
            or type(receipt.get("compared")) is not int or receipt["compared"] <= 0
            or {receipt.get("left"), receipt.get("right")} != {left_id, right_id}
            or left_id == right_id):
        raise CoordinateError("shared halo block gate has not passed for this tile pair")
    for field in ("coreBoundaryMismatches", "occupancyMismatches"):
        if field in receipt and (type(receipt[field]) is not int or receipt[field] != 0):
            raise CoordinateError(f"structural seam gate failed: {field}")
