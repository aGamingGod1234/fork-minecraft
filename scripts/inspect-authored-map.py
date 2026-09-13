from __future__ import annotations

import argparse
import collections
import json
import math
import pathlib
import sys


AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def palette_name(entry) -> str:
    return entry["Name"].value


def section_block(section, x: int, y: int, z: int) -> str:
    block_states = section["block_states"]
    palette = block_states["palette"]
    if len(palette) == 1 or "data" not in block_states:
        return palette_name(palette[0])
    bits = max(4, math.ceil(math.log2(len(palette))))
    values_per_long = 64 // bits
    index = y * 256 + z * 16 + x
    packed = block_states["data"].value[index // values_per_long]
    if packed < 0:
        packed += 1 << 64
    palette_index = (packed >> ((index % values_per_long) * bits)) & ((1 << bits) - 1)
    return palette_name(palette[palette_index])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("world", type=pathlib.Path)
    parser.add_argument("--minimum-x", type=int, default=-24)
    parser.add_argument("--maximum-x", type=int, default=24)
    parser.add_argument("--minimum-y", type=int, default=60)
    parser.add_argument("--maximum-y", type=int, default=110)
    parser.add_argument("--minimum-z", type=int, default=0)
    parser.add_argument("--maximum-z", type=int, default=320)
    args = parser.parse_args()

    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "runtime" / "pydeps"))
    import anvil  # type: ignore

    region_dir = args.world / "region"
    regions = {}
    chunks = {}
    counts: collections.Counter[str] = collections.Counter()
    bounds = [None, None, None, None, None, None]
    total = 0
    for x in range(args.minimum_x, args.maximum_x + 1):
        for z in range(args.minimum_z, args.maximum_z + 1):
            cx, cz = x // 16, z // 16
            key = (cx, cz)
            if key not in chunks:
                region_key = (cx // 32, cz // 32)
                if region_key not in regions:
                    region_path = region_dir / f"r.{region_key[0]}.{region_key[1]}.mca"
                    regions[region_key] = anvil.Region.from_file(str(region_path)) if region_path.is_file() else None
                region = regions[region_key]
                chunks[key] = None if region is None else region.chunk_data(cx % 32, cz % 32)
            chunk = chunks[key]
            if chunk is None:
                continue
            sections = {section["Y"].value: section for section in chunk["sections"]}
            for y in range(args.minimum_y, args.maximum_y + 1):
                section = sections.get(y // 16)
                if section is None or "block_states" not in section:
                    continue
                name = section_block(section, x % 16, y % 16, z % 16)
                if name in AIR:
                    continue
                total += 1
                counts[name] += 1
                for index, value in enumerate((x, y, z)):
                    low, high = index * 2, index * 2 + 1
                    bounds[low] = value if bounds[low] is None else min(bounds[low], value)
                    bounds[high] = value if bounds[high] is None else max(bounds[high], value)
    print(json.dumps({"blocks": total, "bounds": bounds, "palette": counts.most_common()}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
