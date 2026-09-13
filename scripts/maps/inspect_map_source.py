"""Inspect one bounded vanilla structure-template NBT without converting it."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

try:
    from scripts.maps.convert_map_module import _canonical_state, _compound, _compound_list_allow_empty_end, _list, _tag
    from scripts.maps.nbt_reader import read_bounded
except ModuleNotFoundError:  # Direct execution adds scripts/maps, not the repository root.
    from convert_map_module import _canonical_state, _compound, _compound_list_allow_empty_end, _list, _tag
    from nbt_reader import read_bounded


def inspect_structure(path: str | Path) -> dict[str, object]:
    source = Path(path)
    if source.is_dir():
        raise ValueError("world directories are rejected; inspect one structure-template NBT")
    if source.suffix.casefold() == ".mca":
        raise ValueError("Anvil .mca inspection is deferred")
    payload = source.read_bytes()
    root = read_bounded(__import__("io").BytesIO(payload))
    structure = _compound(root.value, "structure root")
    if "palettes" in structure:
        raise ValueError("randomized multi-palette structures are rejected")
    palette = _list(_tag(structure, "palette", 9), 10, "palette")
    blocks = _list(_tag(structure, "blocks", 9), 10, "blocks")
    entities = _compound_list_allow_empty_end(_tag(structure, "entities", 9), "entities")
    states = sorted(_canonical_state(_compound(entry, "palette entry"))[2] for entry in palette)
    return {
        "sha256": hashlib.sha256(payload).hexdigest(),
        "dataVersion": _tag(structure, "DataVersion", 3),
        "size": list(_list(_tag(structure, "size", 9), 3, "size")),
        "paletteStates": states,
        "blockCount": len(blocks),
        "blockEntityCount": sum("nbt" in _compound(block, "block entry") for block in blocks),
        "entityCount": len(entities),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    print(json.dumps(inspect_structure(args.source), sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
