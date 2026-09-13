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
import uuid

from anvil import Tag, ListPayload, NbtFile, read_level_dat, write_level_dat, write_region_stream
from run_spool import RunSpool

MIN_Y, MAX_Y = -64, 320
LAYERS = {"terrain": 10, "landcover": 20, "water": 30, "road": 40, "building": 50, "bridge": 60}
ALLOWED_ROOT = Path(r"C:\Users\User\AppData\Local\FORK-Tools\fork-singapore-full\merged")
PROJECT_ROOT = ALLOWED_ROOT.parent
AIR_NAMES = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
FLUID_NAMES = {"minecraft:water", "minecraft:lava"}
NONBLOCKING = {"short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "vine", "glow_lichen", "torch", "wall_torch", "redstone_torch", "redstone_wall_torch", "snow", "lily_pad", "rail", "powered_rail", "detector_rail", "activator_rail", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower", "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony", "pink_petals"}
SOLIDS = {"stone", "granite", "diorite", "andesite", "deepslate", "cobblestone", "mossy_cobblestone", "dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "bedrock", "sand", "red_sand", "gravel", "clay", "mud", "packed_mud", "glass", "tinted_glass", "bricks", "stone_bricks", "smooth_stone", "sandstone", "red_sandstone", "cut_sandstone", "smooth_sandstone", "prismarine", "dark_prismarine", "prismarine_bricks", "sea_lantern", "glowstone", "obsidian", "ice", "packed_ice", "blue_ice", "netherrack", "end_stone", "quartz_block", "smooth_quartz", "quartz_pillar", "quartz_bricks", "terracotta", "iron_block", "calcite", "copper_block", "polished_deepslate", "polished_granite", "iron_bars"}
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
    queue_runtime_shape = len(parts) == 5 and parts[:2] == ("queue", "jobs") and re.fullmatch(r"attempt-\d+", parts[3]) is not None and parts[4] == "output"
    run_shape = len(parts) == 5 and parts[0] == "runs" and parts[2] == "attempts" and parts[4] == "output"
    if not (queue_shape or queue_runtime_shape or run_shape):
        raise ValueError("lease output root must identify one immutable queue/run attempt")
    if lease.get("machine") != "Desktop" or lease.get("approvedBy") != "/root/singapore_full_coordinator" or lease.get("heavyJobSlot") not in ("A", "B") or lease.get("cpuThreads") != 1:
        raise ValueError("job lease does not carry the approved Desktop single-process coordinator contract")
    starts = datetime.fromisoformat(lease["startsUtc"].replace("Z", "+00:00"))
    expires = datetime.fromisoformat(lease["expiresUtc"].replace("Z", "+00:00"))
    if starts.tzinfo is None or expires.tzinfo is None or not starts <= datetime.now(timezone.utc) < expires:
        raise ValueError("job lease is not currently valid")
    return {"id": lease.get("id"), "sha256": digest(lease_path), "heavyJobSlot": lease["heavyJobSlot"], "expiresUtc": lease["expiresUtc"]}


def disable_structures(config):
    """Handle both embedded legacy and external 26.1 world-generation records."""
    for key in ("generate_features", "generate_structures"):
        if key in config.value:
            config.value[key] = Tag(1, 0)
    dimensions = config.value.get("dimensions")
    if dimensions is None or dimensions.type_id != 10:
        raise ValueError("World generation template has no dimensions")
    for dimension in dimensions.value.values():
        if dimension.type_id != 10:
            continue
        generator = dimension.value.get("generator")
        if generator is None or generator.type_id != 10:
            continue
        settings = generator.value.get("settings")
        if settings is not None and settings.type_id == 10 and "structure_overrides" in settings.value:
            settings.value["structure_overrides"] = tag_list([], 8)


def spool_inputs(spool, runs_paths, bounds):
    sources, source_classes, input_count = [], set(), 0
    for source in sorted(map(Path, runs_paths), key=lambda item: str(item.resolve())):
        source = source.resolve()
        before = source.stat()
        sources.append({"name": source.name, "sha256": digest(source), "bytes": before.st_size})
        with source.open(encoding="utf-8-sig") as stream:
            for number, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                try:
                    run = parse_run(json.loads(line), bounds)
                except (ValueError, KeyError, TypeError) as exc:
                    raise ValueError(f"{source.name}:{number}: {exc}") from exc
                payload = {"x": run.x, "z": run.z, "yMin": run.low, "yMax": run.high,
                           "block": run.state[0], "properties": dict(run.state[1]), "layer": run.layer,
                           "featureId": run.feature, "geometryKind": run.geometry, "sourceClass": run.source}
                spool.add(run.x // 16, run.z // 16, payload)
                source_classes.add(run.source)
                input_count += 1
        after = source.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise ValueError(f"Input source changed while spooling: {source.name}")
    spool.finish()
    return sources, source_classes, input_count


def write_streamed_regions(spool, stage_world, bounds, data_version, report):
    region_directory = stage_world / report["regionDirectory"]
    region_directory.mkdir(parents=True)
    cx0, cz0, cx1, cz1 = bounds[0] // 16, bounds[1] // 16, bounds[2] // 16, bounds[3] // 16
    center_x, center_z = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
    best_spawn, max_buffered_runs, verified_chunks = None, 0, 0
    for rx in range(cx0 // 32, (cx1 - 1) // 32 + 1):
        for rz in range(cz0 // 32, (cz1 - 1) // 32 + 1):
            def chunks_for_region():
                nonlocal best_spawn, max_buffered_runs
                for cz in range(max(cz0, rz * 32), min(cz1, (rz + 1) * 32)):
                    for cx in range(max(cx0, rx * 32), min(cx1, (rx + 1) * 32)):
                        columns, buffered = defaultdict(list), 0
                        for payload in spool.iter_chunk(cx, cz):
                            buffered += 1
                            if buffered > 131072:
                                raise ValueError(f"Chunk {cx},{cz} exceeds 131072 buffered runs; input must be simplified before assembly")
                            run = parse_run(payload, bounds)
                            columns[(run.x, run.z)].append(run)
                        max_buffered_runs = max(max_buffered_runs, buffered)
                        chunk = make_chunk(cx, cz, columns, data_version, report)
                        del columns
                        surface = chunk.root.value["Heightmaps"].value["WORLD_SURFACE"].value
                        for z in range(16):
                            for x in range(16):
                                index = z * 16 + x
                                height = ((surface[index // 7] & ((1 << 64) - 1)) >> ((index % 7) * 9)) & 511
                                feet = height + MIN_Y
                                if 1 <= feet < MAX_Y - 2:
                                    gx, gz = cx * 16 + x, cz * 16 + z
                                    candidate = (feet != 1, (gx - center_x) ** 2 + (gz - center_z) ** 2, gx, feet, gz)
                                    if best_spawn is None or candidate < best_spawn:
                                        best_spawn = candidate
                        yield (cx, cz), chunk
            path = region_directory / f"r.{rx}.{rz}.mca"
            # The streaming writer itself reopens and verifies every chunk's typed NBT hash.
            written = write_region_stream(path, chunks_for_region())
            planned = (min(cx1, (rx + 1) * 32) - max(cx0, rx * 32)) * (min(cz1, (rz + 1) * 32) - max(cz0, rz * 32))
            if written["chunks"] != planned:
                raise RuntimeError("Reopened region is missing generated chunks")
            verified_chunks += written["chunks"]
            report["outputs"].append({"path": path.relative_to(stage_world).as_posix(), "bytes": path.stat().st_size, "sha256": digest(path)})
    if best_spawn is None:
        raise ValueError("no spawn column with two clear blocks below world ceiling")
    report["streaming"] = {"spool": "sqlite-disk", "maxBufferedRunsPerChunk": max_buffered_runs,
                           "maxAllowedRunsPerChunk": 131072, "verifiedChunks": verified_chunks,
                           "maxRetainedChunkTrees": 2, "spawnSelection": "incremental-minimum"}
    return best_spawn


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
    settings_path = Path(level_template).resolve().parent / "data" / "minecraft" / "world_gen_settings.dat"
    external_settings = None
    if settings_path.is_file():
        external_settings = read_level_dat(settings_path)
        config = external_settings.root.value.get("data")
        if config is None or config.type_id != 10:
            raise ValueError("External world generation settings require a data compound")
        disable_structures(config)
    elif "WorldGenSettings" not in data.value:
        raise ValueError("Template requires missing external data/minecraft/world_gen_settings.dat")
    scratch = world.parent / (".s-" + uuid.uuid4().hex[:12])
    scratch.mkdir(parents=True, exist_ok=False)
    stage_world = scratch / "world"
    spool_path = scratch / "runs.sqlite"
    with RunSpool(spool_path) as spool:
        sources, source_classes, input_count = spool_inputs(spool, runs_paths, bounds)
    report = {"schemaVersion": 1, "kind": "global-block-run-world", "status": "WRITING", "assemblyAccepted": False,
              "coordinateFrame": {"crs": "EPSG:3414", "x": "easting", "z": "60000-northing", "blocksPerMeter": 1},
              "bounds": list(bounds), "verticalRange": [MIN_Y, MAX_Y], "terrain": "flat-provisional",
              "groundProfileId": "flat-provisional-y0-v1", "actualGroundAccepted": False,
              "lighting": "isLightOn=false; Minecraft must recalculate; client acceptance still required",
              "heightmapPolicy": "restricted vanilla palette; explicit nonblocking vegetation/decorations; ocean floor excludes fluid; no-leaves excludes leaves",
              "entities": "none", "mapData": "none", "sourceClasses": sorted(source_classes), "inputs": sources,
              "inputRuns": input_count, "chunkCount": count, "dataVersion": data_version,
              "minecraftTarget": "26.1.2", "runtimeLoadAccepted": False, "jobLease": lease_receipt,
              "templateDependencies": ([{"path": "data/minecraft/world_gen_settings.dat", "sha256": digest(settings_path)}] if external_settings else []),
              "regionDirectory": "dimensions/minecraft/overworld/region" if external_settings else "region",
              "levelTemplateSha256": digest(level_template), "crossLayerOverwrittenBlocks": defaultdict(int), "outputs": []}
    # One source chunk and one output chunk at a time; final world remains absent on failure.
    with RunSpool(spool_path, create=False) as spool:
        best_spawn = write_streamed_regions(spool, stage_world, bounds, data_version, report)
    # Keep only world configuration; never carry source player/server state or per-tile map IDs.
    data.value.pop("Player", None)
    data.value.pop("DragonFight", None)
    worldgen = data.value.get("WorldGenSettings")
    if worldgen is not None and worldgen.type_id == 10:
        disable_structures(worldgen)
    data.value["DataPacks"] = compound({"Enabled": tag_list([Tag(8, "vanilla")], 8), "Disabled": tag_list([], 8)})
    report["dataPacks"] = ["vanilla"]
    if "MapFeatures" in data.value:
        data.value["MapFeatures"] = Tag(1, 0)
    report["surroundingVanillaStructureGeneration"] = "disabled"
    data.value["LevelName"] = Tag(8, "FORK - Singapore Assembly Preview")
    _, _, spawn_x, spawn_y, spawn_z = best_spawn
    data.value["SpawnX"] = Tag(3, spawn_x)
    data.value["SpawnY"] = Tag(3, spawn_y)
    data.value["SpawnZ"] = Tag(3, spawn_z)
    if "spawn" in data.value or external_settings is not None:
        data.value["spawn"] = compound({"pos": Tag(11, [spawn_x, spawn_y, spawn_z]), "pitch": Tag(5, 0.0),
                                        "yaw": Tag(5, 0.0), "dimension": Tag(8, "minecraft:overworld")})
    report["spawn"] = [spawn_x, spawn_y, spawn_z]
    level_path = stage_world / "level.dat"
    write_level_dat(level_path, template)
    report["outputs"].append({"path": "level.dat", "bytes": level_path.stat().st_size, "sha256": digest(level_path)})
    if external_settings is not None:
        output_settings = stage_world / "data" / "minecraft" / "world_gen_settings.dat"
        output_settings.parent.mkdir(parents=True, exist_ok=True)
        write_level_dat(output_settings, external_settings)
        report["outputs"].append({"path": output_settings.relative_to(stage_world).as_posix(), "bytes": output_settings.stat().st_size, "sha256": digest(output_settings)})
    if job_lease:
        validate_job_lease(job_lease, world)
    if world.exists():
        raise FileExistsError("Final world appeared during generation; refusing replacement")
    stage_world.rename(world)
    # Delete only this attempt's named spool files; no recursive filesystem cleanup.
    for name in ("runs.sqlite", "runs.sqlite-journal", "runs.sqlite-wal", "runs.sqlite-shm"):
        temporary = scratch / name
        if temporary.exists():
            temporary.unlink()
    scratch.rmdir()
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
