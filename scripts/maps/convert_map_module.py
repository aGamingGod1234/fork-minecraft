"""Convert one selection-bound vanilla structure template to block-only JSON."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any

try:
    from scripts.maps.nbt_reader import NbtList, NbtTag, read_bounded
except ModuleNotFoundError:  # Direct execution adds scripts/maps, not the repository root.
    from nbt_reader import NbtList, NbtTag, read_bounded


DATA_VERSION = 4790
MINECRAFT_VERSION = "26.1.2"
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_IDENTIFIER = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
_SELECTION_KEYS = {
    "schemaVersion",
    "id",
    "version",
    "sourceKey",
    "input",
    "archiveEvidence",
    "curation",
    "difficulty",
    "bounds",
    "allowedTransforms",
    "containerPolicy",
    "spectatorPolicy",
    "expectedRemoved",
    "anchors",
}
_MODULE_KEYS = {
    "schemaVersion",
    "id",
    "version",
    "sourceKey",
    "difficulty",
    "bounds",
    "allowedTransforms",
    "containerPolicy",
    "spectatorPolicy",
    "palette",
    "placements",
    "anchors",
    "geometrySha256",
}
_CONTROL_BLOCKS = {
    "minecraft:command_block",
    "minecraft:repeating_command_block",
    "minecraft:chain_command_block",
    "minecraft:structure_block",
    "minecraft:jigsaw",
}
_AIR_BLOCKS = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
_TRANSFORM_ORDER = ("identity", "rotate_90", "rotate_180", "rotate_270", "mirror_x", "mirror_z")
_CONTAINER_BLOCKS = {
    "minecraft:barrel",
    "minecraft:chest",
    "minecraft:trapped_chest",
    "minecraft:dispenser",
    "minecraft:dropper",
    "minecraft:hopper",
    "minecraft:furnace",
    "minecraft:blast_furnace",
    "minecraft:smoker",
    "minecraft:brewing_stand",
    "minecraft:chiseled_bookshelf",
    "minecraft:decorated_pot",
}
_FORBIDDEN_NBT_KEYS = {
    "command",
    "commands",
    "items",
    "item",
    "loottable",
    "loot_table",
    "recipes",
    "functions",
    "function",
    "ticks",
    "scheduledticks",
}
_ANCHOR_ORDER = {name: index for index, name in enumerate(("spawn", "checkpoint", "goal", "loot", "camera", "connection"))}


def strict_json_load(path: Path) -> Any:
    def reject_duplicate(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        output: dict[str, Any] = {}
        for key, value in pairs:
            if key in output:
                raise ValueError(f"duplicate JSON key: {key}")
            output[key] = value
        return output

    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=reject_duplicate,
            parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"non-finite JSON value: {value}")),
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid JSON file {path}: {error}") from error


def _has_reparse(path: Path) -> bool:
    try:
        return bool(path.lstat().st_file_attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT)
    except (AttributeError, FileNotFoundError):
        return False


def _reject_linked_path(path: Path, stop: Path) -> None:
    current = path.absolute()
    stop = stop.absolute()
    while True:
        if current.exists() or current.is_symlink():
            if current.is_symlink() or _has_reparse(current):
                raise ValueError(f"path contains a symlink or reparse point: {current}")
        if current == stop:
            return
        if current == current.parent:
            raise ValueError(f"path is outside declared root: {stop}")
        current = current.parent


def _within(path: Path, root: Path, description: str) -> Path:
    absolute = path.absolute()
    root_absolute = root.absolute()
    try:
        absolute.relative_to(root_absolute)
    except ValueError as error:
        raise ValueError(f"{description} must stay inside declared root {root_absolute}") from error
    _reject_linked_path(absolute, root_absolute)
    return absolute


def _relative_input(value: object) -> PurePosixPath:
    if not isinstance(value, str) or not value or "\\" in value:
        raise ValueError("input path must be a non-empty relative POSIX path")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ("", ".", "..") for part in path.parts) or ":" in path.parts[0]:
        raise ValueError("input path must be a contained relative path")
    return path


def _expect_object(value: object, description: str, keys: set[str], *, required: set[str] | None = None) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{description} must be an object")
    unknown = set(value) - keys
    if unknown:
        raise ValueError(f"{description} contains unknown keys: {sorted(unknown)}")
    missing = (required or keys) - set(value)
    if missing:
        raise ValueError(f"{description} is missing keys: {sorted(missing)}")
    return value


def _expect_int(value: object, description: str, minimum: int | None = None, maximum: int | None = None) -> int:
    if type(value) is not int:
        raise ValueError(f"{description} must be an integer")
    if minimum is not None and value < minimum or maximum is not None and value > maximum:
        raise ValueError(f"{description} is outside the allowed range")
    return value


def _expect_vec3(value: object, description: str) -> list[int]:
    if not isinstance(value, list) or len(value) != 3:
        raise ValueError(f"{description} must contain three integers")
    return [_expect_int(component, description) for component in value]


def _tag(compound: dict[str, NbtTag], name: str, tag_id: int) -> object:
    value = compound.get(name)
    if value is None or value.tag_id != tag_id:
        raise ValueError(f"structure {name} must be NBT tag {tag_id}")
    return value.value


def _compound(value: object, description: str) -> dict[str, NbtTag]:
    if not isinstance(value, dict):
        raise ValueError(f"{description} must be a compound")
    return value


def _list(value: object, element_tag: int, description: str) -> tuple[object, ...]:
    if not isinstance(value, NbtList) or value.element_tag_id != element_tag:
        raise ValueError(f"{description} must be a list of NBT tag {element_tag}")
    return value.values


def _compound_list_allow_empty_end(value: object, description: str) -> tuple[object, ...]:
    if not isinstance(value, NbtList):
        raise ValueError(f"{description} must be an NBT list")
    if value.element_tag_id == 10:
        return value.values
    if value.element_tag_id == 0 and not value.values:
        return value.values
    raise ValueError(f"{description} must be a compound list or an End-typed empty list")


def _canonical_state(entry: dict[str, NbtTag]) -> tuple[str, dict[str, str], str]:
    unknown = set(entry) - {"Name", "Properties"}
    if unknown:
        raise ValueError(f"palette entry contains unknown keys: {sorted(unknown)}")
    block_id = _tag(entry, "Name", 8)
    if not isinstance(block_id, str) or not _IDENTIFIER.fullmatch(block_id):
        raise ValueError(f"invalid palette block identifier: {block_id!r}")
    properties: dict[str, str] = {}
    if "Properties" in entry:
        property_tags = _compound(_tag(entry, "Properties", 10), "palette Properties")
        for name, property_tag in property_tags.items():
            if property_tag.tag_id != 8 or not isinstance(property_tag.value, str):
                raise ValueError("block-state properties must be strings")
            if not re.fullmatch(r"[a-z0-9_]+", name) or not re.fullmatch(r"[a-z0-9_.-]+", property_tag.value):
                raise ValueError("invalid block-state property name or value")
            properties[name] = property_tag.value
    suffix = ""
    if properties:
        suffix = "[" + ",".join(f"{name}={properties[name]}" for name in sorted(properties)) + "]"
    return block_id, dict(sorted(properties.items())), block_id + suffix


def _contains_forbidden_nbt(value: object) -> bool:
    if isinstance(value, dict):
        for key, tag in value.items():
            if key.casefold() in _FORBIDDEN_NBT_KEYS or _contains_forbidden_nbt(tag.value):
                return True
    elif isinstance(value, NbtList):
        return any(_contains_forbidden_nbt(item) for item in value.values)
    return False


def _load_catalog(path: Path) -> set[str]:
    catalog = _expect_object(
        strict_json_load(path),
        "state catalog",
        {"schemaVersion", "minecraftVersion", "dataVersion", "states"},
    )
    if catalog["schemaVersion"] != 1 or catalog["minecraftVersion"] != MINECRAFT_VERSION or catalog["dataVersion"] != DATA_VERSION:
        raise ValueError("state catalog is not pinned to Minecraft 26.1.2 DataVersion 4790")
    states = catalog["states"]
    if not isinstance(states, list) or not states or states != sorted(set(states)) or not all(isinstance(state, str) for state in states):
        raise ValueError("state catalog states must be a non-empty sorted unique string list")
    return set(states)


def _validate_provenance(selection: dict[str, Any], ledger_path: Path, source_root: Path) -> bool:
    ledger = _expect_object(strict_json_load(ledger_path), "source ledger", set(strict_json_load(ledger_path).keys()), required={"sources"})
    sources = ledger["sources"]
    if not isinstance(sources, dict) or selection["sourceKey"] not in sources:
        raise ValueError("selection sourceKey is absent from source ledger")
    source = sources[selection["sourceKey"]]
    if not isinstance(source, dict) or not source.get("bundleEligible"):
        raise ValueError("selection source is not bundle eligible")
    if source.get("origin") == "project-owned":
        if "archiveEvidence" in selection:
            raise ValueError("project-owned selection must not declare archive evidence")
        return False
    evidence_record = selection.get("archiveEvidence")
    if not isinstance(evidence_record, dict):
        raise ValueError("external selection requires locked archive evidence")
    _expect_object(evidence_record, "archiveEvidence", {"path", "sha256"})
    expected = evidence_record["sha256"]
    archive = source.get("archive")
    if not isinstance(expected, str) or not _SHA256.fullmatch(expected) or not isinstance(archive, dict) or archive.get("sha256") != expected:
        raise ValueError("archive evidence SHA-256 does not match the locked ledger digest")
    evidence_relative = _relative_input(evidence_record["path"])
    evidence_path = _within(source_root.joinpath(*evidence_relative.parts), source_root, "archive evidence")
    evidence = strict_json_load(evidence_path)
    if not isinstance(evidence, dict) or evidence.get("sourceKey") != selection["sourceKey"] or evidence.get("sha256") != expected:
        raise ValueError("archive evidence content does not bind the selected source")
    return True


def _validate_curation(selection: dict[str, Any], external: bool) -> None:
    if not external:
        if "curation" in selection:
            raise ValueError("project-owned selection must not declare external curation notes")
        return
    notes = _expect_object(
        selection.get("curation"),
        "curation",
        {"intendedMechanic", "cropNotes", "transformationNotes"},
    )
    for key, value in notes.items():
        if not isinstance(value, str) or value != value.strip() or not 1 <= len(value) <= 512:
            raise ValueError(f"curation {key} must be a trimmed nonblank string of at most 512 characters")


def convert_selection(
    selection_path: str | os.PathLike[str],
    source_root: str | os.PathLike[str],
    output_path: str | os.PathLike[str],
    *,
    repository_root: str | os.PathLike[str],
    output_root: str | os.PathLike[str],
    catalog_path: str | os.PathLike[str],
    ledger_path: str | os.PathLike[str],
) -> dict[str, Any]:
    repository = Path(repository_root).absolute()
    source = _within(Path(source_root), repository, "source root")
    output_base = _within(Path(output_root), repository, "output root")
    selection_file = _within(Path(selection_path), repository, "selection")
    output = _within(Path(output_path), output_base, "output")
    try:
        output.relative_to(source)
    except ValueError:
        pass
    else:
        raise ValueError("output must stay outside the untrusted source tree")
    if not selection_file.is_file():
        raise ValueError("selection must be a regular file")

    selection = _expect_object(
        strict_json_load(selection_file),
        "selection",
        _SELECTION_KEYS,
        required=_SELECTION_KEYS - {"archiveEvidence", "curation"},
    )
    if selection["schemaVersion"] != 1:
        raise ValueError("selection schemaVersion must be 1")
    if not isinstance(selection["id"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", selection["id"]):
        raise ValueError("selection id is invalid")
    _expect_int(selection["version"], "selection version", 1)
    if not isinstance(selection["sourceKey"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", selection["sourceKey"]):
        raise ValueError("selection sourceKey is invalid")
    _expect_int(selection["difficulty"], "difficulty", 1, 5)
    external = _validate_provenance(selection, Path(ledger_path), source)
    _validate_curation(selection, external)

    input_record = _expect_object(selection["input"], "selection input", {"path", "sha256"})
    relative_input = _relative_input(input_record["path"])
    input_path = _within(source.joinpath(*relative_input.parts), source, "input")
    if input_path.suffix.casefold() == ".mca":
        raise ValueError("Anvil .mca inputs are deferred and rejected")
    if input_path.is_dir():
        raise ValueError("world directory inputs are rejected")
    if input_path.suffix.casefold() not in (".nbt", ".gz") or not input_path.is_file():
        raise ValueError("input must be one raw or gzip structure-template NBT file")
    expected_sha256 = input_record["sha256"]
    if not isinstance(expected_sha256, str) or not _SHA256.fullmatch(expected_sha256):
        raise ValueError("input sha256 must be 64 lowercase hexadecimal characters")
    with input_path.open("rb") as input_stream:
        input_bytes = input_stream.read(64 * 1024 * 1024 + 1)
    if len(input_bytes) > 64 * 1024 * 1024:
        raise ValueError("input exceeds the compressed byte limit")
    actual_sha256 = hashlib.sha256(input_bytes).hexdigest()
    if actual_sha256 != expected_sha256:
        raise ValueError(f"input sha256 mismatch: expected {expected_sha256}, got {actual_sha256}")
    root = read_bounded(__import__("io").BytesIO(input_bytes))
    structure = _compound(root.value, "structure root")
    if "palettes" in structure:
        raise ValueError("randomized multi-palette structures are rejected")
    allowed_root = {"DataVersion", "size", "palette", "blocks", "entities"}
    unknown_root = set(structure) - allowed_root
    missing_root = allowed_root - set(structure)
    if unknown_root or missing_root:
        raise ValueError(f"structure root keys are not the supported vanilla form; unknown={sorted(unknown_root)}, missing={sorted(missing_root)}")
    data_version = _tag(structure, "DataVersion", 3)
    if data_version != DATA_VERSION:
        raise ValueError(f"DataVersion must be exactly 4790; upgrade and resave this structure with Minecraft 26.1.2 (got {data_version!r})")
    size_values = list(_list(_tag(structure, "size", 9), 3, "size"))
    if len(size_values) != 3 or any(type(value) is not int or value < 1 for value in size_values):
        raise ValueError("structure size must contain three positive integers")
    if size_values[0] > 192 or size_values[1] > 64 or size_values[2] > 192:
        raise ValueError("structure bounds exceed the arena module limits")

    bounds = _expect_object(selection["bounds"], "bounds", {"min", "max"})
    minimum = _expect_vec3(bounds["min"], "bounds min")
    maximum = _expect_vec3(bounds["max"], "bounds max")
    expected_maximum = [size_values[index] - 1 for index in range(3)]
    if minimum != [0, 0, 0] or maximum != expected_maximum:
        raise ValueError(f"selection bounds must exactly match structure bounds [0,0,0]..{expected_maximum}")

    catalog = _load_catalog(Path(catalog_path))
    palette_entries = _list(_tag(structure, "palette", 9), 10, "palette")
    if not palette_entries:
        raise ValueError("structure palette must not be empty")
    source_palette: list[tuple[str, dict[str, str], str]] = []
    for raw_entry in palette_entries:
        block_id, properties, canonical = _canonical_state(_compound(raw_entry, "palette entry"))
        if block_id in _CONTROL_BLOCKS:
            raise ValueError(f"command/structure/jigsaw control block is rejected: {block_id}")
        if canonical not in catalog:
            block_states = [state for state in catalog if state == block_id or state.startswith(block_id + "[")]
            if block_states and any("[" in state for state in block_states):
                catalog_property_names = {
                    part.split("=", 1)[0]
                    for part in block_states[0].split("[", 1)[1].removesuffix("]").split(",")
                }
                if set(properties) != catalog_property_names:
                    raise ValueError(f"state for {block_id} is not an exact complete state combination")
            raise ValueError(f"state is absent from the exact 26.1.2 catalog: {canonical}")
        source_palette.append((block_id, properties, canonical))

    entity_values = _compound_list_allow_empty_end(_tag(structure, "entities", 9), "entities")
    blocks = _list(_tag(structure, "blocks", 9), 10, "blocks")
    expected_removed = _expect_object(selection["expectedRemoved"], "expectedRemoved", {"entities", "blockEntities"})
    expected_entities = _expect_int(expected_removed["entities"], "expectedRemoved entities", 0)
    expected_block_entities = _expect_int(expected_removed["blockEntities"], "expectedRemoved blockEntities", 0)
    if len(entity_values) != expected_entities:
        raise ValueError(f"entities removal count drift: expected {expected_entities}, found {len(entity_values)}")

    semantic_placements: list[tuple[int, int, int, str, str, dict[str, str]]] = []
    coordinates: set[tuple[int, int, int]] = set()
    block_entity_count = 0
    for raw_block in blocks:
        block = _compound(raw_block, "block entry")
        unknown = set(block) - {"pos", "state", "nbt"}
        if unknown:
            raise ValueError(f"block entry contains unsupported keys: {sorted(unknown)}")
        position = list(_list(_tag(block, "pos", 9), 3, "block pos"))
        if len(position) != 3 or any(type(value) is not int for value in position):
            raise ValueError("block position must contain three integers")
        coordinate = tuple(position)
        if coordinate in coordinates:
            raise ValueError(f"duplicate block coordinate: {coordinate}")
        coordinates.add(coordinate)
        if any(position[index] < 0 or position[index] >= size_values[index] for index in range(3)):
            raise ValueError(f"block coordinate is outside structure bounds: {coordinate}")
        palette_index = _tag(block, "state", 3)
        if type(palette_index) is not int or not 0 <= palette_index < len(source_palette):
            raise ValueError(f"invalid palette index at {coordinate}: {palette_index!r}")
        block_id, properties, canonical = source_palette[palette_index]
        if block_id in _CONTAINER_BLOCKS and selection["containerPolicy"] == "none":
            raise ValueError(f"containerPolicy none rejects {block_id}")
        if "nbt" in block:
            block_entity_count += 1
            block_nbt = _compound(_tag(block, "nbt", 10), "block nbt")
            if _contains_forbidden_nbt(block_nbt):
                raise ValueError("block entity contains command, inventory, loot, tick, or executable content")
        if block_id not in _AIR_BLOCKS:
            semantic_placements.append((position[0], position[1], position[2], canonical, block_id, properties))
    if block_entity_count != expected_block_entities:
        raise ValueError(f"blockEntities removal count drift: expected {expected_block_entities}, found {block_entity_count}")

    if selection["containerPolicy"] not in ("none", "empty"):
        raise ValueError("containerPolicy must be none or empty")
    if selection["spectatorPolicy"] not in ("separated", "none"):
        raise ValueError("spectatorPolicy must be separated or none")
    transforms = selection["allowedTransforms"]
    supported_transforms = set(_TRANSFORM_ORDER)
    if not isinstance(transforms, list) or not transforms or len(set(transforms)) != len(transforms) or any(value not in supported_transforms for value in transforms):
        raise ValueError("allowedTransforms must be a non-empty unique supported transform list")

    anchors = selection["anchors"]
    if not isinstance(anchors, list):
        raise ValueError("anchors must be a list")
    output_anchors: list[dict[str, Any]] = []
    anchor_ids: set[str] = set()
    anchor_positions: set[tuple[int, int, int]] = set()
    for anchor in anchors:
        anchor = _expect_object(anchor, "anchor", {"id", "type", "position"})
        anchor_id = anchor["id"]
        anchor_type = anchor["type"]
        position = _expect_vec3(anchor["position"], "anchor position")
        if not isinstance(anchor_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", anchor_id) or anchor_id in anchor_ids:
            raise ValueError("anchor id is invalid or duplicate")
        if anchor_type not in _ANCHOR_ORDER:
            raise ValueError("anchor type is unsupported")
        if any(position[index] < minimum[index] or position[index] > maximum[index] for index in range(3)):
            raise ValueError(f"anchor {anchor_id} is outside bounds")
        anchor_position = tuple(position)
        if anchor_position in anchor_positions:
            raise ValueError(f"duplicate anchor position: {position}")
        anchor_ids.add(anchor_id)
        anchor_positions.add(anchor_position)
        output_anchors.append({"id": anchor_id, "type": anchor_type, "position": position})
    output_anchors.sort(key=lambda anchor: (_ANCHOR_ORDER[anchor["type"]], anchor["id"]))

    semantic_placements.sort(key=lambda placement: placement[:3])
    used_states = sorted({placement[3] for placement in semantic_placements})
    state_indexes = {state: index for index, state in enumerate(used_states)}
    output_palette = []
    state_details = {canonical: (block_id, properties) for _, _, _, canonical, block_id, properties in semantic_placements}
    for canonical in used_states:
        block_id, properties = state_details[canonical]
        output_palette.append({"id": block_id, "properties": properties})
    output_placements = [
        {"x": x, "y": y, "z": z, "state": state_indexes[canonical]}
        for x, y, z, canonical, _, _ in semantic_placements
    ]
    hash_input = "".join(f"{x},{y},{z}={canonical}\n" for x, y, z, canonical, _, _ in semantic_placements).encode("utf-8")
    module = {
        "schemaVersion": 1,
        "id": selection["id"],
        "version": selection["version"],
        "sourceKey": selection["sourceKey"],
        "difficulty": selection["difficulty"],
        "bounds": {"min": minimum, "max": maximum},
        "allowedTransforms": [transform for transform in _TRANSFORM_ORDER if transform in transforms],
        "containerPolicy": selection["containerPolicy"],
        "spectatorPolicy": selection["spectatorPolicy"],
        "palette": output_palette,
        "placements": output_placements,
        "anchors": output_anchors,
        "geometrySha256": hashlib.sha256(hash_input).hexdigest(),
    }
    if set(module) != _MODULE_KEYS:
        raise AssertionError("internal module schema drift")
    encoded = (json.dumps(module, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False) + "\n").encode("utf-8")
    output.parent.mkdir(parents=True, exist_ok=True)
    _reject_linked_path(output.parent, output_base)
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(mode="xb", prefix=f".{output.name}.", suffix=".tmp", dir=output.parent, delete=False) as temporary:
            temporary_name = temporary.name
            temporary.write(encoded)
            temporary.flush()
            os.fsync(temporary.fileno())
        Path(temporary_name).replace(output)
        temporary_name = None
    finally:
        if temporary_name is not None:
            Path(temporary_name).unlink(missing_ok=True)
    return module


def main() -> None:
    repository = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selection", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--output-root", type=Path)
    parser.add_argument("--catalog", type=Path, default=repository / "maps/registries/minecraft-26.1.2-block-states.json")
    parser.add_argument("--ledger", type=Path, default=repository / "maps/source-ledger.json")
    args = parser.parse_args()
    module = convert_selection(
        args.selection,
        args.source,
        args.output,
        repository_root=repository,
        output_root=args.output_root or args.output.parent,
        catalog_path=args.catalog,
        ledger_path=args.ledger,
    )
    print(module["geometrySha256"])


if __name__ == "__main__":
    main()
