"""Write clean, global-coordinate Minecraft chunks from deterministic block runs.

No existing world is edited. Run generation does not constitute a fidelity gate.
"""
from __future__ import annotations

import argparse
from array import array
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re

from anvil import Tag, ListPayload, NbtFile, read_level_dat, write_level_dat, write_region, read_region

MIN_Y, MAX_Y = -64, 320
LAYERS = {"terrain": 10, "landcover": 20, "water": 30, "road": 40, "building": 50, "bridge": 60}
ALLOWED_ROOT = Path(r"C:\Users\User\AppData\Local\FORK-Tools\fork-singapore-full\merged")
PROJECT_ROOT = ALLOWED_ROOT.parent
AIR_NAMES = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
FLUID_NAMES = {"minecraft:water", "minecraft:lava"}
NONBLOCKING = {"short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "vine", "glow_lichen", "torch", "wall_torch", "redstone_torch", "redstone_wall_torch", "snow", "lily_pad", "rail", "powered_rail", "detector_rail", "activator_rail", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower", "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony", "pink_petals"}
SOLIDS = {"stone", "granite", "diorite", "andesite", "deepslate", "cobblestone", "mossy_cobblestone", "dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "bedrock", "sand", "red_sand", "gravel", "clay", "mud", "packed_mud", "glass", "tinted_glass", "bricks", "stone_bricks", "smooth_stone", "sandstone", "red_sandstone", "cut_sandstone", "smooth_sandstone", "prismarine", "dark_prismarine", "prismarine_bricks", "sea_lantern", "glowstone", "obsidian", "ice", "packed_ice", "blue_ice", "netherrack", "end_stone", "quartz_block", "smooth_quartz", "quartz_pillar", "quartz_bricks", "terracotta", "iron_bars"}
SOLID_SUFFIXES = ("_concrete", "_concrete_powder", "_terracotta", "_wool", "_planks", "_log", "_wood", "_leaves", "_ore", "_glass", "_glass_pane", "_slab", "_stairs", "_fence", "_wall")
NAME = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(part)
    return h.hexdigest()


def compound(values):
    return Tag(10, values)


def tag_list(values, kind=10):
    return Tag(9, ListPayload(kind, values))


def state_key(block, properties=None):
    if not isinstance(block, str) or not NAME.fullmatch(block):
        raise ValueError(f"invalid namespaced block: {block!r}")
    local = block.removeprefix("minecraft:")
    if not block.startswith("minecraft:") or not (block in AIR_NAMES | FLUID_NAMES or local in SOLIDS | NONBLOCKING or local.endswith(SOLID_SUFFIXES + ("_sapling", "_tulip"))):
        raise ValueError(f"unsupported block heightmap/entity semantics: {block}")
    properties = properties or {}
    if not isinstance(properties, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in properties.items()):
        raise ValueError("block properties must be string pairs")
    return (block, tuple(sorted(properties.items())))


AIR = state_key("minecraft:air")
GROUND = [(-4, -3, state_key("minecraft:bedrock")), (-3, 0, state_key("minecraft:dirt")), (0, 1, state_key("minecraft:grass_block"))]


@dataclass(frozen=True)
class Run:
    x: int
    z: int
    low: int
    high: int
    state: tuple
    layer: int
    feature: str
    geometry: str
    source: str


def strict_int(value, field):
    if type(value) is not int:
        raise ValueError(f"{field} must be an integer")
    return value


def parse_run(value, bounds):
    if not isinstance(value, dict):
        raise ValueError("run must be an object")
    x, z = (strict_int(value[k], k) for k in ("x", "z"))
    low, high = (strict_int(value[k], k) for k in ("yMin", "yMax"))
    if not MIN_Y <= low < high <= MAX_Y:
        raise ValueError(f"run outside vertical range [{MIN_Y},{MAX_Y}): {low},{high}")
    if not (bounds[0] <= x < bounds[2] and bounds[1] <= z < bounds[3]):
        raise ValueError(f"run outside half-open output bounds: {x},{z}")
    layer = value.get("layer")
    if isinstance(layer, str):
        layer = LAYERS.get(layer)
    if type(layer) is not int or layer not in LAYERS.values():
        raise ValueError("layer must be terrain10, landcover20, water30, road40, building50 or bridge60")
    for field in ("featureId", "geometryKind", "sourceClass"):
        if not isinstance(value.get(field), str) or not value[field]:
            raise ValueError(f"{field} must be a nonempty string")
    return Run(x, z, low, high, state_key(value["block"], value.get("properties")), layer,
               value["featureId"], value["geometryKind"], value["sourceClass"])


def resolve_column(runs, report):
    """Half-open interval sweep: ordering of input JSONL cannot change blocks."""
    boundaries = sorted({y for run in runs for y in (run.low, run.high)})
    result = []
    for low, high in zip(boundaries, boundaries[1:]):
        active = [run for run in runs if run.low <= low and run.high >= high]
        if not active:
            continue
        highest = max(run.layer for run in active)
        winners = [run for run in active if run.layer == highest]
        materials = {run.state for run in winners}
        if len(materials) != 1:
            names = sorted({run.feature for run in winners})
            raise ValueError(f"unresolved same-layer material conflict at {runs[0].x},{low}:{high},{runs[0].z}: {names}")
        chosen = next(iter(materials))
        for lower in {run.layer for run in active if run.layer < highest and run.state != chosen}:
            report["crossLayerOverwrittenBlocks"][f"{lower}->{highest}"] += high - low
        if result and result[-1][1] == low and result[-1][2] == chosen:
            result[-1] = (result[-1][0], high, chosen)
        else:
            result.append((low, high, chosen))
    return result


def pack_indices(indices, bits):
    """Modern Anvil packing: values never straddle signed 64-bit long boundaries."""
    per_long = 64 // bits
    longs = []
    for start in range(0, len(indices), per_long):
        number = 0
        for offset, value in enumerate(indices[start:start + per_long]):
            if value < 0 or value >= 1 << bits:
                raise ValueError("palette/heightmap index exceeds bit width")
            number |= int(value) << (offset * bits)
        longs.append(number if number < 1 << 63 else number - (1 << 64))
    return longs


def state_tag(state):
    block, properties = state
    result = {"Name": Tag(8, block)}
    if properties:
        result["Properties"] = compound({key: Tag(8, value) for key, value in properties})
    return compound(result)


def make_chunk(cx, cz, columns, data_version, report):
    states = [AIR]
    state_ids = {AIR: 0}
    sections = {}
    heights = {key: [0] * 256 for key in ("WORLD_SURFACE", "OCEAN_FLOOR", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES")}

    def place(x, z, low, high, state):
        state_id = state_ids.get(state)
        if state_id is None:
            state_id = len(states)
            if state_id >= 65536:
                raise ValueError("too many states in one chunk")
            state_ids[state] = state_id
            states.append(state)
        for y in range(low, high):
            sy = y // 16
            section = sections.get(sy)
            if section is None:
                section = array("H", [0]) * 4096
                sections[sy] = section
            section[((y % 16) * 16 + z) * 16 + x] = state_id

    for z in range(16):
        for x in range(16):
            gx, gz = cx * 16 + x, cz * 16 + z
            column = [Run(gx, gz, low, high, state, 10, "provisional-flat-ground", "ground", "provisional") for low, high, state in GROUND]
            column.extend(columns.get((gx, gz), []))
            # Explicit terrain runs replace automatic provisional ground at matching heights.
            explicit_terrain = [run for run in column if run.layer == 10 and run.feature != "provisional-flat-ground"]
            if explicit_terrain:
                retained = [run for run in column if run.feature != "provisional-flat-ground"]
                for automatic in (run for run in column if run.feature == "provisional-flat-ground"):
                    intervals = [(automatic.low, automatic.high)]
                    for explicit in explicit_terrain:
                        pieces = []
                        for low, high in intervals:
                            if explicit.high <= low or explicit.low >= high:
                                pieces.append((low, high))
                            else:
                                if low < explicit.low:
                                    pieces.append((low, explicit.low))
                                if explicit.high < high:
                                    pieces.append((explicit.high, high))
                        intervals = pieces
                    retained.extend(Run(gx, gz, low, high, automatic.state, 10, automatic.feature, automatic.geometry, automatic.source) for low, high in intervals)
                column = retained
            resolved = resolve_column(column, report)
            for low, high, state in resolved:
                place(x, z, low, high, state)
            index = z * 16 + x
            for low, high, state in resolved:
                name = state[0]
                if name in AIR_NAMES:
                    continue
                height = high - MIN_Y
                heights["WORLD_SURFACE"][index] = max(heights["WORLD_SURFACE"][index], height)
                local = name.removeprefix("minecraft:")
                blocking = local not in NONBLOCKING and not local.endswith(("_sapling", "_tulip"))
                if blocking:
                    heights["MOTION_BLOCKING"][index] = max(heights["MOTION_BLOCKING"][index], height)
                if blocking and name not in FLUID_NAMES:
                    heights["OCEAN_FLOOR"][index] = max(heights["OCEAN_FLOOR"][index], height)
                if blocking and not name.endswith("_leaves"):
                    heights["MOTION_BLOCKING_NO_LEAVES"][index] = max(heights["MOTION_BLOCKING_NO_LEAVES"][index], height)

    output_sections = []
    for sy in range(MIN_Y // 16, MAX_Y // 16):
        values = sections.get(sy, array("H", [0]) * 4096)
        used = sorted(set(values), key=lambda item: states[item])
        palette = [states[item] for item in used]
        local_ids = {item: index for index, item in enumerate(used)}
        block_states = {"palette": tag_list([state_tag(state) for state in palette])}
        if len(palette) > 1:
            bits = max(4, (len(palette) - 1).bit_length())
            block_states["data"] = Tag(12, pack_indices([local_ids[value] for value in values], bits))
        output_sections.append(compound({"Y": Tag(1, sy), "block_states": compound(block_states),
                                        "biomes": compound({"palette": tag_list([Tag(8, "minecraft:plains")], 8)})}))
    root = compound({"DataVersion": Tag(3, data_version), "xPos": Tag(3, cx), "yPos": Tag(3, MIN_Y // 16),
                     "zPos": Tag(3, cz), "Status": Tag(8, "minecraft:full"), "LastUpdate": Tag(4, 0),
                     "InhabitedTime": Tag(4, 0), "isLightOn": Tag(1, 0), "sections": tag_list(output_sections),
                     "block_entities": tag_list([]), "block_ticks": tag_list([]), "fluid_ticks": tag_list([]),
                     "PostProcessing": Tag(9, ListPayload(9, [tag_list([], 2) for _ in range(24)])),
                     "Heightmaps": compound({key: Tag(12, pack_indices(value, 9)) for key, value in heights.items()}),
                     "structures": compound({"starts": compound({}), "References": compound({})})})
    return NbtFile("", root)


def validate_bounds(bounds):
    if len(bounds) != 4 or any(type(value) is not int or value % 16 for value in bounds):
        raise ValueError("bounds must contain four 16-block-aligned integers")
    if bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
        raise ValueError("bounds must have positive half-open extent")


def validate_job_lease(lease_path, world, project_root=PROJECT_ROOT):
    lease_path, world, project_root = Path(lease_path).resolve(), Path(world).resolve(), Path(project_root).resolve()
    lease = json.loads(lease_path.read_text(encoding="utf-8-sig"))
    root = Path(lease["outputRoot"]).resolve()
    if project_root not in root.parents or root not in world.parents:
        raise ValueError("job lease output root must contain the new world and stay within private full-Singapore project")
    parts = root.relative_to(project_root).parts
    queue_shape = len(parts) == 6 and parts[:2] == ("queue", "jobs") and parts[3] == "attempts" and parts[5] == "output"
    run_shape = len(parts) == 5 and parts[0] == "runs" and parts[2] == "attempts" and parts[4] == "output"
    if not (queue_shape or run_shape):
        raise ValueError("lease output root must identify one immutable queue/run attempt")
    if lease.get("machine") != "Desktop" or lease.get("approvedBy") != "/root/singapore_full_coordinator" or lease.get("heavyJobSlot") not in ("A", "B") or lease.get("cpuThreads") != 1:
        raise ValueError("job lease does not carry the approved Desktop single-process coordinator contract")
    starts = datetime.fromisoformat(lease["startsUtc"].replace("Z", "+00:00"))
    expires = datetime.fromisoformat(lease["expiresUtc"].replace("Z", "+00:00"))
    if starts.tzinfo is None or expires.tzinfo is None or not starts <= datetime.now(timezone.utc) < expires:
        raise ValueError("job lease is not currently valid")
    return {"id": lease.get("id"), "sha256": digest(lease_path), "heavyJobSlot": lease["heavyJobSlot"], "expiresUtc": lease["expiresUtc"]}


def write_overlay(runs_paths, world, bounds, level_template, *, max_chunks=512, allowed_root=ALLOWED_ROOT, job_lease=None):
    validate_bounds(bounds)
    world, allowed_root = Path(world).resolve(), Path(allowed_root).resolve()
    lease_receipt = validate_job_lease(job_lease, world) if job_lease else None
    if not lease_receipt and (world == allowed_root or allowed_root not in world.parents):
        raise ValueError("new output world must be below the private merged root")
    if world.exists():
        raise FileExistsError("output world already exists; immutable attempts are never replaced")
    count = ((bounds[2] - bounds[0]) // 16) * ((bounds[3] - bounds[1]) // 16)
    if count > max_chunks:
        raise ValueError(f"{count} chunks exceed explicit bounded run limit {max_chunks}")
    if count > 4 and lease_receipt is None:
        raise ValueError("more than four synthetic fixture chunks requires a coordinator job lease")
    template = read_level_dat(level_template)
    data = template.root.value.get("Data")
    if data is None or data.type_id != 10 or "DataVersion" not in data.value:
        raise ValueError("level template needs Data/DataVersion")
    data_version = data.value["DataVersion"].value
    if data_version < 2844:
        raise ValueError("level template must use modern 1.18+ chunk height and palette format")
    columns = defaultdict(list)
    sources = []
    source_classes = set()
    input_count = 0
    for source in sorted(map(Path, runs_paths), key=lambda item: str(item.resolve())):
        source = source.resolve()
        sources.append({"name": source.name, "sha256": digest(source), "bytes": source.stat().st_size})
        with source.open(encoding="utf-8-sig") as stream:
            for number, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                try:
                    run = parse_run(json.loads(line), bounds)
                except (ValueError, KeyError, TypeError) as exc:
                    raise ValueError(f"{source.name}:{number}: {exc}") from exc
                columns[(run.x, run.z)].append(run)
                source_classes.add(run.source)
                input_count += 1
    report = {"schemaVersion": 1, "kind": "global-block-run-world", "status": "WRITING", "assemblyAccepted": False,
              "coordinateFrame": {"crs": "EPSG:3414", "x": "easting", "z": "60000-northing", "blocksPerMeter": 1},
              "bounds": list(bounds), "verticalRange": [MIN_Y, MAX_Y], "terrain": "flat-provisional",
              "groundProfileId": "flat-provisional-y0-v1", "actualGroundAccepted": False,
              "lighting": "isLightOn=false; Minecraft must recalculate; client acceptance still required",
              "heightmapPolicy": "restricted vanilla palette; explicit nonblocking vegetation/decorations; ocean floor excludes fluid; no-leaves excludes leaves",
              "entities": "none", "mapData": "none", "sourceClasses": sorted(source_classes), "inputs": sources,
              "inputRuns": input_count, "chunkCount": count, "dataVersion": data_version,
              "minecraftTarget": "26.1.2", "runtimeLoadAccepted": False, "jobLease": lease_receipt,
              "levelTemplateSha256": digest(level_template), "crossLayerOverwrittenBlocks": defaultdict(int), "outputs": []}
    # Resolve all conflicts before creating an output. Generation remains a bounded in-memory strip operation.
    chunks = {}
    for cz in range(bounds[1] // 16, bounds[3] // 16):
        for cx in range(bounds[0] // 16, bounds[2] // 16):
            chunks[(cx, cz)] = make_chunk(cx, cz, columns, data_version, report)
    if job_lease:
        validate_job_lease(job_lease, world)
    world.mkdir(parents=True, exist_ok=False)
    (world / "region").mkdir()
    grouped = defaultdict(dict)
    for (cx, cz), chunk in chunks.items():
        grouped[(cx // 32, cz // 32)][(cx, cz)] = chunk
    for (rx, rz), region_chunks in sorted(grouped.items()):
        path = world / "region" / f"r.{rx}.{rz}.mca"
        write_region(path, region_chunks)
        reopened = read_region(path)
        if set(reopened) != set(region_chunks):
            raise RuntimeError("reopened region coordinates do not match requested global chunks")
        report["outputs"].append({"path": path.relative_to(world).as_posix(), "bytes": path.stat().st_size, "sha256": digest(path)})
    # Keep only world configuration; never carry source player/server state or per-tile map IDs.
    data.value.pop("Player", None)
    data.value.pop("DragonFight", None)
    worldgen = data.value.get("WorldGenSettings")
    if worldgen is not None and worldgen.type_id == 10:
        worldgen.value["generate_features"] = Tag(1, 0)
        dimensions = worldgen.value.get("dimensions")
        if dimensions is not None and dimensions.type_id == 10:
            for dimension in dimensions.value.values():
                if dimension.type_id != 10:
                    continue
                generator = dimension.value.get("generator")
                if generator is None or generator.type_id != 10:
                    continue
                settings = generator.value.get("settings")
                if settings is not None and settings.type_id == 10 and "structure_overrides" in settings.value:
                    settings.value["structure_overrides"] = tag_list([], 8)
    if "MapFeatures" in data.value:
        data.value["MapFeatures"] = Tag(1, 0)
    report["surroundingVanillaStructureGeneration"] = "disabled"
    data.value["LevelName"] = Tag(8, "FORK - Singapore Assembly Preview")
    center_x, center_z = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
    candidates = []
    for (cx, cz), chunk in chunks.items():
        surface = chunk.root.value["Heightmaps"].value["WORLD_SURFACE"].value
        for z in range(16):
            for x in range(16):
                index = z * 16 + x
                height = ((surface[index // 7] & ((1 << 64) - 1)) >> ((index % 7) * 9)) & 511
                feet = height + MIN_Y
                if 1 <= feet < MAX_Y - 2:
                    gx, gz = cx * 16 + x, cz * 16 + z
                    candidates.append((feet != 1, (gx - center_x) ** 2 + (gz - center_z) ** 2, gx, feet, gz))
    if not candidates:
        raise ValueError("no spawn column with two clear blocks below world ceiling")
    _, _, spawn_x, spawn_y, spawn_z = min(candidates)
    data.value["SpawnX"] = Tag(3, spawn_x)
    data.value["SpawnY"] = Tag(3, spawn_y)
    data.value["SpawnZ"] = Tag(3, spawn_z)
    report["spawn"] = [spawn_x, spawn_y, spawn_z]
    level_path = world / "level.dat"
    write_level_dat(level_path, template)
    report["outputs"].append({"path": "level.dat", "bytes": level_path.stat().st_size, "sha256": digest(level_path)})
    report["crossLayerOverwrittenBlocks"] = dict(sorted(report["crossLayerOverwrittenBlocks"].items()))
    report["status"] = "WRITTEN_UNACCEPTED"
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", action="append", required=True)
    parser.add_argument("--world", required=True)
    parser.add_argument("--bounds", required=True, help="minX,minZ,maxXExclusive,maxZExclusive")
    parser.add_argument("--level-template", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--max-chunks", type=int, default=512)
    parser.add_argument("--job-lease", help="Coordinator-approved immutable Desktop queue/run attempt lease")
    args = parser.parse_args()
    manifest = Path(args.manifest).resolve()
    world = Path(args.world).resolve()
    if manifest.exists() or world == manifest or world in manifest.parents:
        parser.error("manifest must be a new path outside the output world")
    if PROJECT_ROOT.resolve() not in manifest.parents:
        parser.error("manifest must remain within the private full-Singapore project")
    report = write_overlay(args.runs, world, tuple(int(value) for value in args.bounds.split(",")), args.level_template, max_chunks=args.max_chunks, job_lease=args.job_lease)
    manifest.parent.mkdir(parents=True, exist_ok=True)
    with manifest.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2, sort_keys=True)
        stream.write("\n")
    print(json.dumps({"world": str(world), "manifest": str(manifest), "status": report["status"], "chunks": report["chunkCount"]}))


if __name__ == "__main__":
    main()
