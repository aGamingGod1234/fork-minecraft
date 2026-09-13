"""Lossless typed NBT and bounded Anvil I/O for offline Singapore tile assembly.

Coordinates are translated in chunk units, never vertical units. Entity and
structure handling is deliberately conservative: unsupported coordinate-bearing
payloads fail closed. Source worlds must be offline; this module never modifies
a source document. Separate entities/ and poi/ region files are caller-rejected.
"""
from __future__ import annotations

import copy
import gzip
import hashlib
import os
import re
import struct
import tempfile
import zlib
from dataclasses import dataclass
from pathlib import Path

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)
MAX_NBT_BYTES = 32 * 1024 * 1024
MAX_TOTAL_NBT_BYTES = 256 * 1024 * 1024
MAX_DEPTH = 64
MAX_ITEMS = 4 * 1024 * 1024
SECTOR = 4096


class NbtError(ValueError):
    pass


@dataclass
class Tag:
    type_id: int
    value: object


@dataclass
class ListPayload:
    element_type: int
    items: list[Tag]


@dataclass
class NbtFile:
    name: str
    root: Tag


class _Reader:
    def __init__(self, data):
        if len(data) > MAX_NBT_BYTES:
            raise NbtError("NBT exceeds per-document limit")
        self.data = memoryview(data)
        self.pos = 0

    def take(self, count):
        if count < 0 or self.pos + count > len(self.data):
            raise NbtError("Truncated NBT")
        result = self.data[self.pos:self.pos + count]
        self.pos += count
        return result

    def number(self, fmt):
        return struct.unpack(">" + fmt, self.take(struct.calcsize(">" + fmt)))[0]

    def string(self):
        raw = bytes(self.take(self.number("H")))
        # Java modified UTF-8 encodes NUL as C0 80 and supplementary characters
        # as surrogate pairs. Accept ordinary UTF-8 too for third-party writers.
        try:
            value = raw.replace(b"\xc0\x80", b"\x00").decode("utf-8", "surrogatepass")
            return value.encode("utf-16", "surrogatepass").decode("utf-16", "surrogatepass")
        except UnicodeError as exc:
            raise NbtError("Invalid NBT string") from exc

    def count(self, minimum_size=1):
        count = self.number("i")
        if count < 0 or count > MAX_ITEMS or count * minimum_size > len(self.data) - self.pos:
            raise NbtError("Invalid or oversized NBT collection")
        return count

    def payload(self, type_id, depth=0):
        if depth > MAX_DEPTH:
            raise NbtError("NBT nesting exceeds limit")
        scalar = {BYTE: "b", SHORT: "h", INT: "i", LONG: "q", FLOAT: "f", DOUBLE: "d"}
        if type_id in scalar:
            return Tag(type_id, self.number(scalar[type_id]))
        if type_id == STRING:
            return Tag(STRING, self.string())
        if type_id == BYTE_ARRAY:
            return Tag(BYTE_ARRAY, bytes(self.take(self.count())))
        if type_id in (INT_ARRAY, LONG_ARRAY):
            width, fmt = (4, "i") if type_id == INT_ARRAY else (8, "q")
            count = self.count(width)
            return Tag(type_id, list(struct.unpack(">" + str(count) + fmt, self.take(count * width))))
        if type_id == LIST:
            subtype = self.number("B")
            count = self.count(0)
            if subtype not in range(13) or (subtype == END and count):
                raise NbtError("Invalid list element tag")
            # Every non-END tag requires at least one byte, limiting CPU use.
            if count > len(self.data) - self.pos:
                raise NbtError("List length exceeds remaining payload")
            return Tag(LIST, ListPayload(subtype, [self.payload(subtype, depth + 1) for _ in range(count)]))
        if type_id == COMPOUND:
            value = {}
            while True:
                subtype = self.number("B")
                if subtype == END:
                    return Tag(COMPOUND, value)
                name = self.string()
                if name in value:
                    raise NbtError("Duplicate compound key: " + name)
                value[name] = self.payload(subtype, depth + 1)
        raise NbtError("Unsupported tag type: " + str(type_id))


def read_nbt(data: bytes) -> NbtFile:
    reader = _Reader(data)
    type_id = reader.number("B")
    if type_id == END:
        raise NbtError("Root cannot be TAG_End")
    name = reader.string()
    root = reader.payload(type_id)
    if reader.pos != len(reader.data):
        raise NbtError("Trailing bytes after NBT document")
    return NbtFile(name, root)


def _string(value):
    # DataOutput.writeUTF compatible representation, including supplementary
    # Unicode characters represented by two UTF-16 surrogate code units.
    units = value.encode("utf-16-be", "surrogatepass")
    out = bytearray()
    for i in range(0, len(units), 2):
        c = (units[i] << 8) | units[i + 1]
        if 0 < c < 0x80:
            out.append(c)
        elif c < 0x800:
            out.extend((0xC0 | (c >> 6), 0x80 | (c & 0x3F)))
        else:
            out.extend((0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F)))
    if len(out) > 65535:
        raise NbtError("NBT string exceeds unsigned-short length")
    return struct.pack(">H", len(out)) + out


def write_nbt(document: NbtFile) -> bytes:
    out = bytearray()
    def emit(data):
        out.extend(data)
        if len(out) > MAX_NBT_BYTES:
            raise NbtError("NBT exceeds per-document limit")

    def payload(tag, depth=0):
        if not isinstance(tag, Tag) or depth > MAX_DEPTH:
            raise NbtError("Invalid tag or excessive depth")
        type_id, value = tag.type_id, tag.value
        scalar = {BYTE: "b", SHORT: "h", INT: "i", LONG: "q", FLOAT: "f", DOUBLE: "d"}
        if type_id in scalar:
            try:
                emit(struct.pack(">" + scalar[type_id], value))
            except (struct.error, OverflowError) as exc:
                raise NbtError("Numeric tag out of range") from exc
        elif type_id == STRING:
            emit(_string(value))
        elif type_id == BYTE_ARRAY:
            emit(struct.pack(">i", len(value)))
            emit(bytes(value))
        elif type_id in (INT_ARRAY, LONG_ARRAY):
            if len(value) > MAX_ITEMS:
                raise NbtError("Array exceeds item limit")
            emit(struct.pack(">i", len(value)))
            fmt = "i" if type_id == INT_ARRAY else "q"
            try:
                emit(struct.pack(">" + str(len(value)) + fmt, *value))
            except (struct.error, OverflowError) as exc:
                raise NbtError("Array number out of range") from exc
        elif type_id == LIST:
            if not isinstance(value, ListPayload) or len(value.items) > MAX_ITEMS:
                raise NbtError("Invalid list payload")
            if value.element_type not in range(13) or (value.element_type == END and value.items):
                raise NbtError("Invalid list element type")
            emit(struct.pack(">Bi", value.element_type, len(value.items)))
            for item in value.items:
                if item.type_id != value.element_type:
                    raise NbtError("List has mixed tag types")
                payload(item, depth + 1)
        elif type_id == COMPOUND:
            for name, child in value.items():
                if child.type_id not in range(1, 13):
                    raise NbtError("Invalid compound child type")
                emit(bytes([child.type_id]) + _string(name))
                payload(child, depth + 1)
            emit(b"\x00")
        else:
            raise NbtError("Invalid payload tag type")
    if document.root.type_id not in range(1, 13):
        raise NbtError("Invalid root tag type")
    emit(bytes([document.root.type_id]) + _string(document.name))
    payload(document.root)
    return bytes(out)


def _inflate(data, wbits):
    try:
        decoder = zlib.decompressobj(wbits)
        raw = decoder.decompress(data, MAX_NBT_BYTES + 1)
        if len(raw) > MAX_NBT_BYTES or decoder.unconsumed_tail:
            raise NbtError("Decompressed NBT exceeds limit")
        if not decoder.eof or decoder.unused_data:
            raise NbtError("Truncated or concatenated compressed payload")
        return raw
    except zlib.error as exc:
        raise NbtError("Invalid compressed payload") from exc



def _fs_path(path):
    """Native absolute path with Windows long-path support; leave other OSes alone."""
    value = os.fspath(path)
    if os.name != "nt":
        return value
    value = os.path.abspath(value)
    if value.startswith("\\\\?\\"):
        return value
    if value.startswith("\\\\"):
        return "\\\\?\\UNC\\" + value[2:]
    return "\\\\?\\" + value


def read_level_dat(path) -> NbtFile:
    with open(_fs_path(path), "rb") as handle:
        data = handle.read(MAX_NBT_BYTES + 1)
    if len(data) > MAX_NBT_BYTES:
        raise NbtError("Compressed level.dat exceeds limit")
    return read_nbt(_inflate(data, 31))


def _atomic_write(path, data):
    path = Path(path)
    os.makedirs(_fs_path(path.parent), exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".nb-", suffix=".tmp", dir=_fs_path(path.parent))
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, _fs_path(path))
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def write_level_dat(path, document: NbtFile):
    _atomic_write(path, gzip.compress(write_nbt(document), mtime=0))


def _region_coords(path):
    match = re.fullmatch(r"r\.(-?\d+)\.(-?\d+)\.mca", Path(path).name)
    if not match:
        raise NbtError("Region filename must be r.<x>.<z>.mca")
    return tuple(map(int, match.groups()))


def chunk_payload(document: NbtFile) -> dict[str, Tag]:
    if document.root.type_id != COMPOUND:
        raise NbtError("Chunk root must be compound")
    root = document.root.value
    if "Level" in root:
        if root["Level"].type_id != COMPOUND:
            raise NbtError("Legacy Level must be compound")
        if "xPos" in root or "zPos" in root:
            raise NbtError("Ambiguous modern and legacy coordinates")
        root = root["Level"].value
    return root


def chunk_coords(document: NbtFile) -> tuple[int, int]:
    root = chunk_payload(document)
    for name in ("xPos", "zPos"):
        if name not in root or root[name].type_id != INT:
            raise NbtError("Chunk requires int " + name)
    return root["xPos"].value, root["zPos"].value


def _region_locations(handle, path):
    """Validate the complete location table before exposing any chunk."""
    rx, rz = _region_coords(path)
    size = os.fstat(handle.fileno()).st_size
    if size < 8192 or size % SECTOR:
        raise NbtError("Invalid region size/alignment")
    handle.seek(0)
    locations = handle.read(SECTOR)
    occupied = {0, 1}
    entries = []
    for index in range(1024):
        entry = int.from_bytes(locations[index * 4:index * 4 + 4], "big")
        offset, count = entry >> 8, entry & 255
        if not entry:
            continue
        if offset < 2 or not count or (offset + count) * SECTOR > size:
            raise NbtError("Invalid region sector location")
        sectors = set(range(offset, offset + count))
        if occupied.intersection(sectors):
            raise NbtError("Overlapping region sectors")
        occupied.update(sectors)
        entries.append(((rx * 32 + index % 32, rz * 32 + index // 32), offset, count))
    return entries


def _iter_region_handle(handle, path):
    total = 0
    entries = _region_locations(handle, path)
    for coords, offset, count in entries:
        handle.seek(offset * SECTOR)
        length = int.from_bytes(handle.read(4), "big")
        if length < 2 or length > count * SECTOR - 4:
            raise NbtError("Invalid compressed chunk length")
        compression_data = handle.read(1)
        if len(compression_data) != 1:
            raise NbtError("Truncated chunk compression marker")
        compression = compression_data[0]
        encoded = handle.read(length - 1)
        if len(encoded) != length - 1:
            raise NbtError("Truncated compressed chunk")
        if compression & 128:
            raise NbtError("External .mcc chunks are not supported")
        if compression == 1:
            raw = _inflate(encoded, 31)
        elif compression == 2:
            raw = _inflate(encoded, 15)
        elif compression == 3:
            raw = encoded
        else:
            raise NbtError("Unsupported Anvil compression: " + str(compression))
        total += len(raw)
        if total > MAX_TOTAL_NBT_BYTES:
            raise NbtError("Region exceeds decoded-data limit")
        document = read_nbt(raw)
        if chunk_coords(document) != coords:
            raise NbtError("NBT chunk coordinates disagree with region slot")
        del raw, encoded
        yield coords, document
        del document




def iter_region(path):
    """Yield (global chunk coordinates, NbtFile), decoding one chunk at a time.

    Every location-table offset and overlap is checked before the first yield.
    Payload errors are detected as that individual chunk is reached. Consumers
    must not mutate the source and must exhaust or close the iterator.
    """
    with open(_fs_path(path), "rb") as handle:
        yield from _iter_region_handle(handle, path)


def read_region(path) -> dict[tuple[int, int], NbtFile]:
    return dict(iter_region(path))


def write_region_stream(path, chunks):
    """Write an iterable of (coordinates, NbtFile) with bounded chunk memory.

    Input order is arbitrary. Compressed records are spooled to one temporary
    file, then copied in canonical region-slot order to a second temporary file.
    Only the 1024-entry slot table and one chunk payload are retained in memory.
    The destination is atomically replaced only after the input and output are
    complete. Existing destinations survive all pre-replacement failures.
    Returns the final byte length, SHA-256 and chunk count for provenance.
    """
    path = Path(path)
    rx, rz = _region_coords(path)
    os.makedirs(_fs_path(path.parent), exist_ok=True)
    spool_path = output_path = None
    metadata = {}
    total = 0
    try:
        spool_fd, spool_path = tempfile.mkstemp(prefix=".sp-", suffix=".tmp", dir=_fs_path(path.parent))
        with os.fdopen(spool_fd, "w+b") as spool:
            for coords, document in chunks:
                if (not isinstance(coords, (tuple, list)) or len(coords) != 2
                        or any(type(value) is not int for value in coords)):
                    raise NbtError("Chunk coordinates must be a pair of integers")
                cx, cz = coords
                coords = (cx, cz)
                if cx // 32 != rx or cz // 32 != rz or chunk_coords(document) != coords:
                    raise NbtError("Chunk coordinates do not belong in target region")
                index = (cx % 32) + (cz % 32) * 32
                if index in metadata:
                    raise NbtError("Duplicate chunk coordinates")
                raw = write_nbt(document)
                total += len(raw)
                if total > MAX_TOTAL_NBT_BYTES:
                    raise NbtError("Region exceeds decoded-data limit")
                nbt_digest = hashlib.sha256(raw).digest()
                encoded = zlib.compress(raw, 6)
                del raw
                record_length = len(encoded) + 5
                count = (record_length + SECTOR - 1) // SECTOR
                if count > 255:
                    raise NbtError("Chunk requires external storage")
                metadata[index] = (spool.tell(), count, nbt_digest)
                spool.write(struct.pack(">I", len(encoded) + 1))
                spool.write(b"\x02")
                spool.write(encoded)
                del encoded, document
                spool.write(bytes(count * SECTOR - record_length))
            spool.flush()
            locations = bytearray(SECTOR)
            offset = 2
            for index in sorted(metadata):
                count = metadata[index][1]
                if offset > 0xFFFFFF:
                    raise NbtError("Region sector offset exceeds format limit")
                locations[index * 4:index * 4 + 4] = ((offset << 8) | count).to_bytes(4, "big")
                offset += count
            output_fd, output_path = tempfile.mkstemp(prefix=".out-", suffix=".tmp", dir=_fs_path(path.parent))
            digest = hashlib.sha256()
            with os.fdopen(output_fd, "wb") as output:
                def emit(data):
                    output.write(data)
                    digest.update(data)
                emit(bytes(locations))
                emit(bytes(SECTOR))  # Deliberately zero timestamps for reproducibility.
                for index in sorted(metadata):
                    position, count, _ = metadata[index]
                    spool.seek(position)
                    remaining = count * SECTOR
                    while remaining:
                        part = spool.read(min(65536, remaining))
                        if not part:
                            raise NbtError("Truncated temporary chunk record")
                        emit(part)
                        remaining -= len(part)
                output.flush()
                os.fsync(output.fileno())
        # Validate the completed temporary file before publication, one decoded
        # tree at a time. Hashing its canonical NBT detects writer corruption.
        checked = 0
        with open(output_path, "rb") as verify:
            for (cx, cz), document in _iter_region_handle(verify, path):
                index = (cx % 32) + (cz % 32) * 32
                if index not in metadata or hashlib.sha256(write_nbt(document)).digest() != metadata[index][2]:
                    raise NbtError("Reopened output chunk differs from input")
                checked += 1
                del document
        if checked != len(metadata):
            raise NbtError("Reopened output is missing chunks")
        os.replace(output_path, _fs_path(path))
        output_path = None
        return {"bytes": offset * SECTOR, "sha256": digest.hexdigest(), "chunks": len(metadata)}
    finally:
        for temporary in (spool_path, output_path):
            if temporary is not None and os.path.exists(temporary):
                os.unlink(temporary)


def write_region(path, chunks: dict[tuple[int, int], NbtFile]):
    write_region_stream(path, chunks.items())


def _empty(tag):
    if tag.type_id == COMPOUND:
        return all(_empty(child) for child in tag.value.values())
    if tag.type_id == LIST:
        return not tag.value.items
    if tag.type_id in (BYTE_ARRAY, INT_ARRAY, LONG_ARRAY):
        return not tag.value
    return False


def _compound(tag, context):
    if tag.type_id != COMPOUND:
        raise NbtError(context + " must be compound")
    return tag.value


def _shift_xz(values, dx, dz, context):
    for name, delta in (("x", dx), ("z", dz)):
        if name not in values or values[name].type_id != INT:
            raise NbtError(context + " requires int " + name)
        new = values[name].value + delta
        if not -(1 << 31) <= new < (1 << 31):
            raise NbtError(context + " coordinate overflow")
        values[name].value = new


def _generated_map_frame(tag):
    entity = _compound(tag, "Entity")
    if entity.get("id") != Tag(STRING, "minecraft:item_frame"):
        return False
    item_tag = entity.get("Item") or entity.get("item")
    if not item_tag or item_tag.type_id != COMPOUND:
        return False
    item = item_tag.value
    if item.get("id") != Tag(STRING, "minecraft:filled_map"):
        return False
    if "Passengers" in entity and not _empty(entity["Passengers"]):
        return False
    components = item.get("components")
    if components and components.type_id == COMPOUND:
        map_id = components.value.get("minecraft:map_id")
        if map_id and map_id.type_id == INT and map_id.value >= 0:
            return True
    old_tag = item.get("tag")
    if old_tag and old_tag.type_id == COMPOUND:
        map_id = old_tag.value.get("map")
        return bool(map_id and map_id.type_id == INT and map_id.value >= 0)
    return False


def _reject_nested_coordinates(tag, context, root=True):
    # Plain banner/sign/chest block entities are retained. Embedded entities,
    # packed target positions and special coordinate schemas need a dedicated
    # adapter; silently retaining them would point into a different tile.
    forbidden = {"Pos", "pos", "position", "block_pos", "BlockPos", "ExitPortal",
                 "FlowerPos", "FlowerPosX", "FlowerPosZ", "SpawnData",
                 "SpawnPotentials", "Bees", "bees", "entity_data",
                 "minecraft:entity_data", "minecraft:lodestone_tracker"}
    if tag.type_id == COMPOUND:
        for key, child in tag.value.items():
            if key in forbidden or (not root and key in {"x", "z", "TileX", "TileZ"}):
                if not _empty(child):
                    raise NbtError(context + " has unsupported coordinate/entity field " + key)
            _reject_nested_coordinates(child, context, False)
    elif tag.type_id == LIST:
        for child in tag.value.items:
            _reject_nested_coordinates(child, context, False)


def translate_chunk(document: NbtFile, dx: int, dz: int, *, entity_policy="reject") -> NbtFile:
    """Copy a chunk with integer chunk-coordinate offsets; Y/palettes stay exact.

    Nonempty structures are rejected. Inline entities are rejected by default.
    The opt-in drop_generated_map_entities policy removes only item frames
    proven to contain a filled map with a nonnegative map ID, with no passengers.
    This intentionally omits generated map decoration and needs caller provenance.
    Separate entities/ and poi/ regions are not covered and must be rejected.
    """
    if type(dx) is not int or type(dz) is not int:
        raise NbtError("Offsets must be integers in chunk units")
    if entity_policy not in ("reject", "drop_generated_map_entities"):
        raise NbtError("Unknown entity policy")
    result = copy.deepcopy(document)
    root = chunk_payload(result)
    cx, cz = chunk_coords(result)
    for name, value in (("xPos", cx + dx), ("zPos", cz + dz)):
        if not -(1 << 31) <= value < (1 << 31):
            raise NbtError("Chunk coordinate overflow")
        root[name].value = value
    block_dx, block_dz = dx * 16, dz * 16
    def validate_owner(values, context):
        for name in ("x", "z"):
            if name not in values or values[name].type_id != INT:
                raise NbtError(context + " requires int " + name)
        if (values["x"].value // 16, values["z"].value // 16) != (cx, cz):
            raise NbtError(context + " lies outside its owning source chunk")
    for name in ("structures", "Structures"):
        if name in root and not _empty(root[name]):
            raise NbtError("Nonempty structures/references require a dedicated translator")
    for name in ("entities", "Entities"):
        if name in root:
            tag = root[name]
            if tag.type_id != LIST or (tag.value.items and tag.value.element_type != COMPOUND):
                raise NbtError("Invalid inline entity list")
            if tag.value.items:
                if entity_policy != "drop_generated_map_entities" or not all(_generated_map_frame(e) for e in tag.value.items):
                    raise NbtError("Unsupported inline entities; refusing stale positions or UUIDs")
                tag.value.items.clear()
    for name in ("block_entities", "TileEntities"):
        if name in root:
            tag = root[name]
            if tag.type_id != LIST or (tag.value.items and tag.value.element_type != COMPOUND):
                raise NbtError("Invalid block entity list")
            for child in tag.value.items:
                validate_owner(_compound(child, "Block entity"), "Block entity")
                _reject_nested_coordinates(child, "Block entity")
                _shift_xz(_compound(child, "Block entity"), block_dx, block_dz, "Block entity")
    for name in ("block_ticks", "fluid_ticks", "TileTicks", "LiquidTicks"):
        if name in root:
            tag = root[name]
            if tag.type_id != LIST or (tag.value.items and tag.value.element_type != COMPOUND):
                raise NbtError("Invalid tick list")
            for child in tag.value.items:
                validate_owner(_compound(child, "Tick"), "Tick")
                _shift_xz(_compound(child, "Tick"), block_dx, block_dz, "Tick")
    # sections/Sections, Heightmaps, PostProcessing and carving masks encode
    # coordinates relative to this chunk or only Y and are preserved verbatim.
    return result


def inspect_chunk(document: NbtFile, *, entity_policy="reject") -> dict[str, int]:
    """Validate translatability and return counts for the caller's audit log."""
    root = chunk_payload(document)
    checked = translate_chunk(document, 0, 0, entity_policy=entity_policy)
    translated_root = chunk_payload(checked)
    def count(names, values):
        return sum(len(values[name].value.items) for name in names if name in values)
    before = count(("entities", "Entities"), root)
    after = count(("entities", "Entities"), translated_root)
    return {
        "block_entities": count(("block_entities", "TileEntities"), root),
        "scheduled_ticks": count(("block_ticks", "fluid_ticks", "TileTicks", "LiquidTicks"), root),
        "inline_entities": before,
        "dropped_map_entities": before - after,
    }
