"""Choose bounded runtime witnesses from frozen runs and the actual final NBT."""
from __future__ import annotations

from collections import defaultdict
from contextlib import closing
import json
from pathlib import Path

from anvil import COMPOUND, INT, LIST, LONG_ARRAY, STRING, NbtError, _fs_path, chunk_coords, chunk_payload, iter_region

REGION_DIRECTORY = "dimensions/minecraft/overworld/region"
AIR = ("minecraft:air", {})
POOL_PER_QUADRANT = 8


def block_at(document, x, y, z):
    """Decode one modern palette entry, including non-straddling signed longs."""
    if chunk_coords(document) != (x // 16, z // 16):
        raise NbtError("Probe does not belong to its chunk")
    root = chunk_payload(document)
    sections = root.get("sections")
    if not sections or sections.type_id != LIST:
        raise NbtError("Expected modern chunk sections")
    for section in sections.value.items:
        values = section.value
        if values["Y"].value != y // 16:
            continue
        states = values.get("block_states")
        if not states or states.type_id != COMPOUND:
            raise NbtError("Expected modern block_states")
        palette_tag = states.value.get("palette")
        if not palette_tag or palette_tag.type_id != LIST or not palette_tag.value.items:
            raise NbtError("Missing block palette")
        palette = palette_tag.value.items
        if len(palette) == 1:
            palette_index = 0
        else:
            packed = states.value.get("data")
            if not packed or packed.type_id != LONG_ARRAY:
                raise NbtError("Missing packed block state data")
            bits = max(4, (len(palette) - 1).bit_length())
            per_long = 64 // bits
            index = ((y % 16) * 16 + z % 16) * 16 + x % 16
            word_index, bit_offset = index // per_long, (index % per_long) * bits
            if word_index >= len(packed.value):
                raise NbtError("Truncated packed block states")
            palette_index = ((packed.value[word_index] & ((1 << 64) - 1)) >> bit_offset) & ((1 << bits) - 1)
        if palette_index >= len(palette):
            raise NbtError("Block state index outside palette")
        state = palette[palette_index].value
        name = state.get("Name")
        if not name or name.type_id != STRING:
            raise NbtError("Invalid block state name")
        properties = state.get("Properties")
        if properties is None:
            return name.value, {}
        if properties.type_id != COMPOUND or any(value.type_id != STRING for value in properties.value.values()):
            raise NbtError("Invalid block state properties")
        return name.value, {key: value.value for key, value in properties.value.items()}
    return AIR


def _quadrant(x, z, bounds):
    return (int(x >= (bounds[0] + bounds[2]) // 2)
            + 2 * int(z >= (bounds[1] + bounds[3]) // 2))


def _bounds(bounds):
    if (len(bounds) != 4 or any(type(value) is not int or value % 16 for value in bounds)
            or bounds[0] >= bounds[2] or bounds[1] >= bounds[3]):
        raise ValueError("Bounds must be positive half-open 16-aligned global blocks")
    return tuple(bounds)


def _states(world, candidates):
    """Read each relevant region once, retaining no decoded chunk cache."""
    grouped = defaultdict(lambda: defaultdict(list))
    for key, candidate in candidates.items():
        x, y, z = key
        grouped[(x // 512, z // 512)][(x // 16, z // 16)].append((key, candidate))
    found = {}
    for (rx, rz), wanted in sorted(grouped.items()):
        path = world / REGION_DIRECTORY / f"r.{rx}.{rz}.mca"
        with closing(iter_region(path)) as chunks:
            for coords, document in chunks:
                if coords in wanted:
                    for key, _ in wanted.pop(coords):
                        found[key] = block_at(document, *key)
                del document
                if not wanted:
                    break
        if wanted:
            raise ValueError("Candidate world has missing probe chunks")
    return found


def select_sentinels(world, bounds, runs_paths):
    """Return at most 16 actual-state witnesses; this is not a fidelity pass.

    Pools retain at most eight candidate coordinates per kind/quadrant. A pool
    exhausted by overwritten runs may supply fewer than four witnesses. The
    runtime plan gate must reject a missing required building/road category.
    """
    world, bounds = Path(world), _bounds(bounds)
    x0, z0, x1, z1 = bounds
    pools = defaultdict(dict)
    targets = [((x0 + x1) // 2 + (-1 if q % 2 == 0 else 1) * (x1 - x0) // 4,
                (z0 + z1) // 2 + (-1 if q < 2 else 1) * (z1 - z0) // 4)
               for q in range(4)]
    for source in runs_paths:
        with open(_fs_path(source), encoding="utf-8-sig") as stream:
            for number, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                run = json.loads(line)
                kind = {50: "building", 40: "road", 30: "water",
                        "building": "building", "road": "road", "water": "water"}.get(run.get("layer"))
                if kind is None:
                    continue
                x, z, low, high = (run[key] for key in ("x", "z", "yMin", "yMax"))
                if any(type(value) is not int for value in (x, z, low, high)) or not -64 <= low < high <= 320:
                    raise ValueError(f"Invalid run coordinates at {source}:{number}")
                if not (x0 <= x < x1 and z0 <= z < z1):
                    continue
                if kind == "building":
                    low = max(low, 3)
                    if low >= high:
                        continue
                    y = (low + high - 1) // 2
                else:
                    y = high - 1
                name, properties = run["block"], run.get("properties", {})
                if not isinstance(name, str) or not isinstance(properties, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in properties.items()):
                    raise ValueError("Invalid run block/properties")
                if name in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air"):
                    continue
                quadrant = _quadrant(x, z, bounds)
                tx, tz = targets[quadrant]
                rank = ((x - tx) ** 2 + (z - tz) ** 2, x, z, y, name, tuple(sorted(properties.items())))
                pool = pools[(kind, quadrant)]
                key = (x, y, z)
                candidate = {"key": key, "block": name, "properties": properties, "rank": rank, "quadrant": quadrant}
                if key not in pool or rank < pool[key]["rank"]:
                    pool[key] = candidate
                if len(pool) > POOL_PER_QUADRANT:
                    del pool[max(pool, key=lambda item: pool[item]["rank"])]

    candidates = {}
    for pool in pools.values():
        for key, candidate in pool.items():
            candidates[key] = candidate
    # Four actual Y0 samples near the corners, one in each owned quadrant.
    margin_x, margin_z = min(8, (x1 - x0) // 4), min(8, (z1 - z0) // 4)
    corners = [(x0 + margin_x, 0, z0 + margin_z),
               (x1 - 1 - margin_x, 0, z0 + margin_z),
               (x0 + margin_x, 0, z1 - 1 - margin_z),
               (x1 - 1 - margin_x, 0, z1 - 1 - margin_z)]
    for key in corners:
        candidates.setdefault(key, {"key": key})
    actual = _states(world, candidates)
    selected, used = [], set()
    for kind in ("building", "road", "water"):
        choices = []
        for quadrant in range(4):
            valid = [candidate for candidate in pools[(kind, quadrant)].values()
                     if actual[candidate["key"]] == (candidate["block"], candidate["properties"])]
            valid.sort(key=lambda candidate: candidate["rank"])
            if valid:
                choices.append(valid.pop(0))
            pools[(kind, quadrant)] = valid
        extras = sorted((candidate for q in range(4) for candidate in pools[(kind, q)]),
                        key=lambda candidate: candidate["rank"])
        choices.extend(extras)
        accepted = 0
        for candidate in choices:
            key = candidate["key"]
            if key in used:
                continue
            used.add(key)
            accepted += 1
            name, properties = actual[key]
            item = {"id": f"{kind}_{accepted:02d}", "x": key[0], "y": key[1], "z": key[2],
                    "block": name, "kind": "terrain" if kind == "water" else kind}
            if properties:
                item["properties"] = dict(properties)
            selected.append(item)
            if accepted == 4:
                break
    # Preserve all four quadrant witnesses, even when a source sample shares a
    # coordinate. Their distinct IDs state their separate coverage purpose.
    for quadrant, key in enumerate(corners):
        name, properties = actual[key]
        item = {"id": f"terrain_corner_{quadrant + 1}", "x": key[0], "y": 0, "z": key[2],
                "block": name, "kind": "terrain"}
        if properties:
            item["properties"] = dict(properties)
        selected.append(item)
    return selected
