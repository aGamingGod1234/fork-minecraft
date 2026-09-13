"""Prepare future scoped air-only generation without editing any world."""
from __future__ import annotations

from copy import deepcopy
import gzip
import hashlib
import json
from pathlib import Path

from anvil import (BYTE, INT, LONG, STRING, LIST, COMPOUND, Tag, ListPayload, NbtFile,
                   read_nbt, write_nbt, _fs_path)

PROFILE = "flat-air-only-v1"
DATA_VERSION = 4790
GROUND_PROFILE = "country-coast-provisional-y0-v1"
FRAME = {"crs": "EPSG:3414", "x": "easting", "z": "60000-northing", "blocksPerMeter": 1}


class WorldgenProfileError(ValueError):
    pass


def _require(condition, message):
    if not condition:
        raise WorldgenProfileError(message)


def _sha(payload):
    return hashlib.sha256(payload).hexdigest()


def _tag(parent, name, kind):
    value = parent.get(name)
    _require(isinstance(value, Tag) and value.type_id == kind,
             f"Required NBT tag {name} has the wrong type")
    return value


def _compound(parent, name):
    tag = _tag(parent, name, COMPOUND)
    _require(isinstance(tag.value, dict), f"{name} must be a compound")
    return tag.value


def _string(parent, name, expected=None):
    value = _tag(parent, name, STRING).value
    _require(isinstance(value, str) and (expected is None or value == expected),
             f"Unexpected {name}: {value}")
    return value


def _boolean(parent, name):
    value = _tag(parent, name, BYTE).value
    _require(type(value) is int and value in (0, 1), f"{name} must be a typed byte boolean")
    return value


def _list(parent, name, element_type):
    payload = _tag(parent, name, LIST).value
    _require(isinstance(payload, ListPayload) and payload.element_type == element_type
             and isinstance(payload.items, list)
             and all(isinstance(tag, Tag) and tag.type_id == element_type for tag in payload.items),
             f"{name} has the wrong NBT list element type")
    return payload


def _record(record, label, payload=None):
    _require(isinstance(record, dict) and isinstance(record.get("path"), str)
             and bool(record["path"]) and type(record.get("bytes")) is int and record["bytes"] >= 0
             and isinstance(record.get("sha256"), str) and len(record["sha256"]) == 64,
             f"{label} requires path, bytes and SHA256")
    if payload is not None:
        _require(record["bytes"] == len(payload) and record["sha256"] == _sha(payload),
                 f"{label} does not bind the actual bytes")
    return deepcopy(record)


def _metadata_sha(tags):
    return _sha(write_nbt(NbtFile("", Tag(COMPOUND, deepcopy(tags)))))


def prepare_worldgen_profile(source_bytes, *, profile, base_scope, context):
    """Return (cloned NbtFile, preparation receipt); write nothing.

    context requires sourceSettings {path,bytes,sha256}, outputSettingsPath,
    and baseScopeDescriptor {path,bytes,sha256}. The BaseScope constructor must
    already have verified the descriptor and masks used by this writer.
    """
    _require(profile == PROFILE, "Only explicit flat-air-only-v1 is supported")
    _require(isinstance(source_bytes, bytes), "Exact compressed source settings bytes required")
    # The default writer path never needs this optional scoped dependency.
    from base_scope import BaseScope
    _require(isinstance(base_scope, BaseScope), "A constructor-verified BaseScope object is required")
    _require(isinstance(context, dict), "Profile provenance context is required")
    context_copy = deepcopy(context)
    source_record = _record(context_copy.get("sourceSettings"), "source settings", source_bytes)
    output_path = context_copy.get("outputSettingsPath")
    _require(isinstance(output_path, str) and bool(output_path), "Output settings path is required")
    descriptor = _record(context_copy.get("baseScopeDescriptor"), "base scope descriptor")
    try:
        descriptor_bytes = Path(_fs_path(descriptor["path"])).read_bytes()
    except OSError as exc:
        raise WorldgenProfileError(f"Cannot read scope descriptor: {exc}") from exc
    _record(descriptor, "base scope descriptor", descriptor_bytes)
    scope = base_scope.receipt()
    _require(scope.get("profileId") == GROUND_PROFILE and scope.get("coordinateFrame") == FRAME
             and scope.get("descriptorSha256") == descriptor["sha256"],
             "Verified scope object does not match the pinned descriptor and frame")
    for name in ("country", "foreignExclusions", "coast"):
        pin = scope.get("masks", {}).get(name, {})
        _require(isinstance(pin.get("sha256"), str) and len(pin["sha256"]) == 64
                 and type(pin.get("bytes")) is int and pin["bytes"] > 0,
                 "Verified scope is missing a mask pin: " + name)
    try:
        source = read_nbt(gzip.decompress(source_bytes))
    except (OSError, EOFError, ValueError) as exc:
        raise WorldgenProfileError(f"Invalid compressed external NBT settings: {exc}") from exc
    _require(source.root.type_id == COMPOUND and isinstance(source.root.value, dict),
             "External settings root must be a compound")
    version = _tag(source.root.value, "DataVersion", INT)
    _require(type(version.value) is int and version.value == DATA_VERSION,
             "Profile supports only Minecraft 26.1.2 / DataVersion 4790")
    data = _compound(source.root.value, "data")
    _tag(data, "seed", LONG)
    dimensions = _compound(data, "dimensions")
    overworld = _compound(dimensions, "minecraft:overworld")
    _string(overworld, "type", "minecraft:overworld")
    generator = _compound(overworld, "generator")
    _string(generator, "type", "minecraft:flat")
    settings = _compound(generator, "settings")
    _string(settings, "biome", "minecraft:plains")
    _boolean(settings, "features")
    _boolean(settings, "lakes")
    _boolean(data, "generate_structures")
    layers = _list(settings, "layers", COMPOUND)
    _require(bool(layers.items), "Source flat layers must be explicit")
    for layer in layers.items:
        _require(isinstance(layer.value, dict), "Malformed flat layer compound")
        _string(layer.value, "block")
        height = _tag(layer.value, "height", INT).value
        _require(type(height) is int and 0 < height <= 384, "Invalid typed flat-layer height")
    overrides = _list(settings, "structure_overrides", STRING)
    _require(all(isinstance(tag.value, str) for tag in overrides.items), "Invalid structure override")
    if "structures" in settings:
        _require(_compound(settings, "structures") == {}, "Legacy structures must be absent or empty")

    output = deepcopy(source)
    out_data = output.root.value["data"].value
    out_settings = out_data["dimensions"].value["minecraft:overworld"].value["generator"].value["settings"].value
    prefix = "/data/dimensions/minecraft:overworld/generator/settings/"
    replacements = [
        (out_settings, settings, "layers", Tag(LIST, ListPayload(COMPOUND, [
            Tag(COMPOUND, {"block": Tag(STRING, "minecraft:air"), "height": Tag(INT, 1)})])), prefix + "layers"),
        (out_settings, settings, "features", Tag(BYTE, 0), prefix + "features"),
        (out_settings, settings, "lakes", Tag(BYTE, 0), prefix + "lakes"),
        (out_settings, settings, "structure_overrides", Tag(LIST, ListPayload(STRING, [])),
         prefix + "structure_overrides"),
        (out_data, data, "generate_structures", Tag(BYTE, 0), "/data/generate_structures"),
    ]
    changed = []
    for parent, original_parent, key, value, path in replacements:
        if original_parent[key] != value:
            changed.append(path)
            parent[key] = value
    # Undo exactly the allowed changes and verify the entire typed tree, including
    # ordering, unknown fields, root name, seed and other dimensions.
    restored = deepcopy(output)
    restored_data = restored.root.value["data"].value
    restored_settings = restored_data["dimensions"].value["minecraft:overworld"].value["generator"].value["settings"].value
    for key in ("layers", "features", "lakes", "structure_overrides"):
        restored_settings[key] = deepcopy(settings[key])
    restored_data["generate_structures"] = deepcopy(data["generate_structures"])
    source_nbt, output_nbt = write_nbt(source), write_nbt(output)
    _require(write_nbt(restored) == source_nbt, "Unexpected mutation outside the profile allowlist")
    output_gzip = gzip.compress(output_nbt, mtime=0)
    other_dimensions = {key: value for key, value in dimensions.items() if key != "minecraft:overworld"}
    seed = {"seed": data["seed"]} if "seed" in data else {}
    receipt = {
        "schemaVersion": 1, "kind": "scoped-unmapped-worldgen-profile", "profileId": PROFILE,
        "sourceSettings": {**source_record, "DataVersion": DATA_VERSION},
        "outputSettings": {"path": output_path, "bytes": len(output_gzip), "sha256": _sha(output_gzip),
                           "DataVersion": DATA_VERSION, "materialized": False},
        "sourceNbtSha256": _sha(source_nbt), "outputNbtSha256": _sha(output_nbt),
        "outputEncoding": "gzip.compress(write_nbt(settings), mtime=0)",
        "verifiedScopeContext": {"groundProfileId": GROUND_PROFILE, "baseScopeDescriptor": descriptor,
            "coordinateFrame": deepcopy(scope["coordinateFrame"]), "masks": deepcopy(scope["masks"]),
            "scopeReceipt": deepcopy(scope)},
        "context": context_copy,
        "contextSha256": _sha(json.dumps(context_copy, sort_keys=True, separators=(",", ":"),
                                        allow_nan=False).encode("utf-8")),
        "mutationPaths": changed, "allowedMutationPaths": [row[4] for row in replacements],
        "unchangedOutsideMutationPaths": True,
        "unchangedSeedNbtSha256": _metadata_sha(seed),
        "unchangedOtherDimensionsNbtSha256": _metadata_sha(other_dimensions),
        "overworld": {"type": "minecraft:overworld", "generatorType": "minecraft:flat",
            "biome": "minecraft:plains", "layers": [{"block": "minecraft:air", "height": 1}],
            "features": False, "lakes": False, "structureOverrides": [], "generateStructures": False},
        "mappedChunksModified": False, "platformGenerated": False, "seaGenerated": False,
        "generatedNewChunksTested": False, "runtimeLoadAccepted": False, "fullWorldAccepted": False,
        "fullFidelity": False,
    }
    return output, receipt
