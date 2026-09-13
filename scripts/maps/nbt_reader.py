"""Strict, allocation-bounded reader for Java Edition NBT tags 0 through 12."""

from __future__ import annotations

import io
import math
import struct
import zlib
from dataclasses import dataclass
from typing import BinaryIO


class NbtError(ValueError):
    """Raised when NBT is malformed, unsupported, or exceeds a safety budget."""


@dataclass(frozen=True)
class NbtLimits:
    max_depth: int = 64
    max_compressed_bytes: int = 64 * 1024 * 1024
    max_decompressed_bytes: int = 64 * 1024 * 1024
    max_string_bytes: int = 65_535
    max_array_payload_bytes: int = 16 * 1024 * 1024
    max_array_elements: int = 4_194_304
    max_list_elements: int = 1_000_000
    max_compound_entries: int = 65_536
    max_nodes: int = 2_000_000

    def __post_init__(self) -> None:
        for name, value in vars(self).items():
            if value < 1:
                raise ValueError(f"{name} must be positive")


@dataclass(frozen=True)
class NbtList:
    element_tag_id: int
    values: tuple[object, ...]


@dataclass(frozen=True)
class NbtTag:
    tag_id: int
    value: object
    name: str | None = None


_SCALAR_FORMATS = {
    1: ">b",
    2: ">h",
    3: ">i",
    4: ">q",
    5: ">f",
    6: ">d",
}


def _read_input(stream: BinaryIO, limits: NbtLimits) -> bytes:
    compressed = stream.read(limits.max_compressed_bytes + 1)
    if len(compressed) > limits.max_compressed_bytes:
        raise NbtError("compressed input exceeds byte limit")
    if not compressed:
        raise NbtError("NBT input is empty")
    if compressed.startswith(b"\x1f\x8b"):
        try:
            decompressor = zlib.decompressobj(16 + zlib.MAX_WBITS)
            payload = decompressor.decompress(compressed, limits.max_decompressed_bytes + 1)
            if len(payload) > limits.max_decompressed_bytes or decompressor.unconsumed_tail:
                raise NbtError("decompressed input exceeds byte limit")
            payload += decompressor.flush(limits.max_decompressed_bytes + 1 - len(payload))
        except (zlib.error, ValueError) as error:
            raise NbtError(f"corrupt gzip NBT: {error}") from error
        if len(payload) > limits.max_decompressed_bytes:
            raise NbtError("decompressed input exceeds byte limit")
        if not decompressor.eof:
            raise NbtError("truncated gzip NBT")
        if decompressor.unused_data:
            raise NbtError("concatenated or trailing gzip data is not allowed")
        return payload
    if len(compressed) > limits.max_decompressed_bytes:
        raise NbtError("raw NBT exceeds decompressed byte limit")
    return compressed


def _decode_modified_utf8(payload: bytes) -> str:
    units: list[int] = []
    cursor = 0
    while cursor < len(payload):
        first = payload[cursor]
        if 0x01 <= first <= 0x7F:
            units.append(first)
            cursor += 1
            continue
        if 0xC0 <= first <= 0xDF:
            if cursor + 1 >= len(payload):
                raise NbtError("truncated modified UTF-8 sequence")
            second = payload[cursor + 1]
            if second & 0xC0 != 0x80:
                raise NbtError("invalid modified UTF-8 continuation")
            unit = (first & 0x1F) << 6 | second & 0x3F
            if unit == 0:
                if first != 0xC0 or second != 0x80:
                    raise NbtError("invalid modified UTF-8 null encoding")
            elif unit < 0x80:
                raise NbtError("overlong modified UTF-8 sequence")
            units.append(unit)
            cursor += 2
            continue
        if 0xE0 <= first <= 0xEF:
            if cursor + 2 >= len(payload):
                raise NbtError("truncated modified UTF-8 sequence")
            second, third = payload[cursor + 1], payload[cursor + 2]
            if second & 0xC0 != 0x80 or third & 0xC0 != 0x80:
                raise NbtError("invalid modified UTF-8 continuation")
            unit = (first & 0x0F) << 12 | (second & 0x3F) << 6 | third & 0x3F
            if unit < 0x800:
                raise NbtError("overlong modified UTF-8 sequence")
            units.append(unit)
            cursor += 3
            continue
        raise NbtError("invalid modified UTF-8 leading byte")
    encoded = b"".join(unit.to_bytes(2, "big") for unit in units)
    try:
        return encoded.decode("utf-16-be", errors="strict")
    except UnicodeDecodeError as error:
        raise NbtError("unpaired modified UTF-8 surrogate") from error


class _Reader:
    def __init__(self, payload: bytes, limits: NbtLimits) -> None:
        self._stream = io.BytesIO(payload)
        self._length = len(payload)
        self._limits = limits
        self._nodes = 0

    def read_root(self) -> NbtTag:
        tag_id = self._read_u8("root tag")
        if tag_id == 0:
            raise NbtError("named End root is invalid")
        if tag_id != 10:
            raise NbtError("root must be one named compound")
        name = self._read_string()
        self._count_node()
        value = self._read_payload(tag_id, 1)
        if self._stream.tell() != self._length:
            raise NbtError("trailing NBT roots or bytes are not allowed")
        return NbtTag(tag_id, value, name)

    def _read_exact(self, length: int, description: str) -> bytes:
        if length < 0:
            raise NbtError(f"negative {description} length")
        value = self._stream.read(length)
        if len(value) != length:
            raise NbtError(f"truncated {description}")
        return value

    def _read_u8(self, description: str) -> int:
        return self._read_exact(1, description)[0]

    def _read_i32(self, description: str) -> int:
        return struct.unpack(">i", self._read_exact(4, description))[0]

    def _read_string(self) -> str:
        byte_length = struct.unpack(">H", self._read_exact(2, "string length"))[0]
        if byte_length > self._limits.max_string_bytes:
            raise NbtError("modified UTF-8 string exceeds byte limit")
        return _decode_modified_utf8(self._read_exact(byte_length, "modified UTF-8 string"))

    def _count_node(self, count: int = 1) -> None:
        self._nodes += count
        if self._nodes > self._limits.max_nodes:
            raise NbtError("NBT node count exceeds limit")

    def _check_depth(self, depth: int) -> None:
        if depth > self._limits.max_depth:
            raise NbtError("NBT depth exceeds limit")

    def _read_payload(self, tag_id: int, depth: int) -> object:
        self._check_depth(depth)
        if tag_id in _SCALAR_FORMATS:
            value = struct.unpack(_SCALAR_FORMATS[tag_id], self._read_exact(struct.calcsize(_SCALAR_FORMATS[tag_id]), "scalar payload"))[0]
            if tag_id in (5, 6) and not math.isfinite(value):
                raise NbtError("non-finite NBT floating-point value")
            return value
        if tag_id == 7:
            return self._read_array(1, lambda chunk: chunk)
        if tag_id == 8:
            return self._read_string()
        if tag_id == 9:
            return self._read_list(depth)
        if tag_id == 10:
            return self._read_compound(depth)
        if tag_id == 11:
            return self._read_array(4, lambda chunk: struct.unpack(f">{len(chunk) // 4}i", chunk))
        if tag_id == 12:
            return self._read_array(8, lambda chunk: struct.unpack(f">{len(chunk) // 8}q", chunk))
        raise NbtError(f"unsupported NBT tag id {tag_id}")

    def _read_array(self, element_size: int, decoder) -> object:
        count = self._read_i32("array")
        if count < 0:
            raise NbtError("negative array length")
        if count > self._limits.max_array_elements:
            raise NbtError("array element count exceeds limit")
        payload_size = count * element_size
        if payload_size > self._limits.max_array_payload_bytes:
            raise NbtError("array payload exceeds byte limit")
        return decoder(self._read_exact(payload_size, "array payload"))

    def _read_list(self, depth: int) -> NbtList:
        element_tag_id = self._read_u8("list element tag")
        if element_tag_id > 12:
            raise NbtError(f"invalid list element tag id {element_tag_id}")
        count = self._read_i32("list")
        if count < 0:
            raise NbtError("negative list length")
        if count > self._limits.max_list_elements:
            raise NbtError("list element count exceeds limit")
        if element_tag_id == 0 and count:
            raise NbtError("End-tag lists must be empty")
        self._count_node(count)
        values = tuple(self._read_payload(element_tag_id, depth + 1) for _ in range(count))
        return NbtList(element_tag_id, values)

    def _read_compound(self, depth: int) -> dict[str, NbtTag]:
        values: dict[str, NbtTag] = {}
        entries = 0
        while True:
            tag_id = self._read_u8("compound tag")
            if tag_id == 0:
                return values
            if tag_id > 12:
                raise NbtError(f"invalid compound tag id {tag_id}")
            entries += 1
            if entries > self._limits.max_compound_entries:
                raise NbtError("compound entry count exceeds limit")
            name = self._read_string()
            if name in values:
                raise NbtError(f"duplicate compound name: {name!r}")
            self._count_node()
            values[name] = NbtTag(tag_id, self._read_payload(tag_id, depth + 1), name)


def read_bounded(stream: BinaryIO, limits: NbtLimits = NbtLimits()) -> NbtTag:
    """Read one raw or single-member gzip named-compound NBT root."""

    return _Reader(_read_input(stream, limits), limits).read_root()
