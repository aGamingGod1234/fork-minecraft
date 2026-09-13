"""Verify deterministic block-only arena module JSON without loading Minecraft."""

from __future__ import annotations

import argparse
import hashlib
import re
from pathlib import Path
from typing import Any

try:
    from scripts.maps.convert_map_module import _MODULE_KEYS, _TRANSFORM_ORDER, _load_catalog, strict_json_load
except ModuleNotFoundError:  # Direct execution adds scripts/maps, not the repository root.
    from convert_map_module import _MODULE_KEYS, _TRANSFORM_ORDER, _load_catalog, strict_json_load


def _canonical(entry: object) -> str:
    if not isinstance(entry, dict) or set(entry) != {"id", "properties"}:
        raise ValueError("module palette entries must contain only id and properties")
    block_id, properties = entry["id"], entry["properties"]
    if not isinstance(block_id, str) or not isinstance(properties, dict):
        raise ValueError("module palette id/properties have invalid types")
    if not all(isinstance(key, str) and isinstance(value, str) for key, value in properties.items()):
        raise ValueError("module palette properties must be strings")
    if list(properties) != sorted(properties):
        raise ValueError("module palette properties are not canonically ordered")
    if properties:
        return block_id + "[" + ",".join(f"{key}={value}" for key, value in properties.items()) + "]"
    return block_id


def verify_module(module_path: str | Path, catalog_path: str | Path) -> dict[str, Any]:
    module = strict_json_load(Path(module_path))
    if not isinstance(module, dict) or set(module) != _MODULE_KEYS:
        raise ValueError("module has unknown or missing schema keys")
    if module["schemaVersion"] != 1:
        raise ValueError("module schemaVersion must be 1")
    if not isinstance(module["id"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", module["id"]):
        raise ValueError("module id is invalid")
    if type(module["version"]) is not int or module["version"] < 1:
        raise ValueError("module version must be a positive integer")
    if not isinstance(module["sourceKey"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", module["sourceKey"]):
        raise ValueError("module sourceKey is invalid")
    if type(module["difficulty"]) is not int or not 1 <= module["difficulty"] <= 5:
        raise ValueError("module difficulty must be an integer from 1 to 5")
    transforms = module["allowedTransforms"]
    supported_transforms = set(_TRANSFORM_ORDER)
    if (
        not isinstance(transforms, list)
        or not transforms
        or any(not isinstance(value, str) or value not in supported_transforms for value in transforms)
        or transforms != [value for value in _TRANSFORM_ORDER if value in transforms]
    ):
        raise ValueError("module allowedTransforms must be sorted, unique, and supported")
    if module["containerPolicy"] not in ("none", "empty"):
        raise ValueError("module containerPolicy is invalid")
    if module["spectatorPolicy"] not in ("none", "separated"):
        raise ValueError("module spectatorPolicy is invalid")
    catalog = _load_catalog(Path(catalog_path))
    palette = module["palette"]
    placements = module["placements"]
    if not isinstance(palette, list) or not isinstance(placements, list):
        raise ValueError("module palette and placements must be lists")
    canonical_palette = [_canonical(entry) for entry in palette]
    if canonical_palette != sorted(set(canonical_palette)):
        raise ValueError("module palette is not sorted and unique")
    if any(state not in catalog for state in canonical_palette):
        raise ValueError("module palette contains a state outside the pinned catalog")
    bounds = module["bounds"]
    if not isinstance(bounds, dict) or set(bounds) != {"min", "max"}:
        raise ValueError("module bounds are invalid")
    minimum, maximum = bounds["min"], bounds["max"]
    if not isinstance(minimum, list) or not isinstance(maximum, list) or len(minimum) != 3 or len(maximum) != 3:
        raise ValueError("module bounds must be vec3 values")
    if any(type(value) is not int for value in minimum + maximum):
        raise ValueError("module bounds coordinates must be integers")
    if any(minimum[index] > maximum[index] for index in range(3)):
        raise ValueError("module bounds are inverted")
    spans = [maximum[index] - minimum[index] + 1 for index in range(3)]
    if spans[0] > 192 or spans[1] > 64 or spans[2] > 192:
        raise ValueError("module bounds exceed the arena caps")
    if len(placements) > 400_000:
        raise ValueError("module placement count exceeds the arena cap")
    coordinates: set[tuple[int, int, int]] = set()
    ordered: list[tuple[int, int, int, str]] = []
    for placement in placements:
        if not isinstance(placement, dict) or set(placement) != {"x", "y", "z", "state"}:
            raise ValueError("module placement has unknown or missing fields")
        x, y, z, state_index = (placement[key] for key in ("x", "y", "z", "state"))
        if any(type(value) is not int for value in (x, y, z, state_index)):
            raise ValueError("module placement fields must be integers")
        coordinate = (x, y, z)
        if coordinate in coordinates:
            raise ValueError("module contains duplicate placement coordinates")
        if any(coordinate[index] < minimum[index] or coordinate[index] > maximum[index] for index in range(3)):
            raise ValueError("module placement is outside bounds")
        if not 0 <= state_index < len(canonical_palette):
            raise ValueError("module placement state index is invalid")
        coordinates.add(coordinate)
        ordered.append((x, y, z, canonical_palette[state_index]))
    if [entry[:3] for entry in ordered] != sorted(entry[:3] for entry in ordered):
        raise ValueError("module placements are not numerically ordered")
    hash_input = "".join(f"{x},{y},{z}={state}\n" for x, y, z, state in ordered).encode("utf-8")
    actual_hash = hashlib.sha256(hash_input).hexdigest()
    if module["geometrySha256"] != actual_hash:
        raise ValueError(f"module geometrySha256 mismatch: expected {actual_hash}")
    anchors = module["anchors"]
    if not isinstance(anchors, list):
        raise ValueError("module anchors must be a list")
    anchor_order = {name: index for index, name in enumerate(("spawn", "checkpoint", "goal", "loot", "camera", "connection"))}
    anchor_ids: set[str] = set()
    anchor_positions: set[tuple[int, int, int]] = set()
    ordering: list[tuple[int, str]] = []
    for anchor in anchors:
        if not isinstance(anchor, dict) or set(anchor) != {"id", "type", "position"}:
            raise ValueError("module anchor has unknown or missing fields")
        anchor_id, anchor_type, position = anchor["id"], anchor["type"], anchor["position"]
        if not isinstance(anchor_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", anchor_id) or anchor_id in anchor_ids:
            raise ValueError("module anchor id is invalid or duplicate")
        if anchor_type not in anchor_order:
            raise ValueError("module anchor type is invalid")
        if not isinstance(position, list) or len(position) != 3 or any(type(value) is not int for value in position):
            raise ValueError("module anchor position must contain three integers")
        anchor_position = tuple(position)
        if anchor_position in anchor_positions:
            raise ValueError("module anchor position is duplicated")
        if any(position[index] < minimum[index] or position[index] > maximum[index] for index in range(3)):
            raise ValueError("module anchor is outside bounds")
        anchor_ids.add(anchor_id)
        anchor_positions.add(anchor_position)
        ordering.append((anchor_order[anchor_type], anchor_id))
    if ordering != sorted(ordering):
        raise ValueError("module anchors are not in canonical semantic order")
    return module


def main() -> None:
    repository = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module", type=Path)
    parser.add_argument("--resources", type=Path)
    parser.add_argument("--ledger", type=Path, default=repository / "maps/source-ledger.json")
    parser.add_argument("--catalog", type=Path, default=repository / "maps/registries/minecraft-26.1.2-block-states.json")
    args = parser.parse_args()
    modules = [args.module] if args.module else sorted((args.resources or repository / "src/main/resources/data/arenaagents/arena_modules").rglob("*.json"))
    if not modules:
        raise SystemExit("no module JSON files found")
    for module_path in modules:
        verified = verify_module(module_path, args.catalog)
        print(f"{module_path}: {verified['geometrySha256']}")


if __name__ == "__main__":
    main()
