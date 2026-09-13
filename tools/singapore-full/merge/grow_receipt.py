"""Read-only final receipt for a new save copied from accepted owned cores."""
from __future__ import annotations

import hashlib
import copy
import json
from pathlib import Path
import re
import struct
from typing import Any

import anvil
import package

REGION_PREFIX = "dimensions/minecraft/overworld/region/"
REGION = re.compile(r"dimensions/minecraft/overworld/region/r\.-?\d+\.-?\d+\.mca$")
CONFIG_FILES = {"level.dat", "data/minecraft/world_gen_settings.dat"}
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
SOLID_NAMES = {"stone", "granite", "diorite", "andesite", "deepslate", "cobblestone", "mossy_cobblestone",
               "dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "bedrock",
               "sand", "red_sand", "gravel", "clay", "mud", "packed_mud", "glass", "tinted_glass",
               "bricks", "stone_bricks", "smooth_stone", "sandstone", "red_sandstone", "cut_sandstone",
               "smooth_sandstone", "prismarine", "dark_prismarine", "prismarine_bricks", "sea_lantern",
               "glowstone", "obsidian", "ice", "packed_ice", "blue_ice", "netherrack", "end_stone",
               "quartz_block", "smooth_quartz", "quartz_pillar", "quartz_bricks", "terracotta"}
SOLID_SUFFIXES = ("_concrete", "_terracotta", "_wool", "_planks", "_log", "_wood", "_ore", "_leaves")


def _need(condition, message):
    if not condition:
        raise package.GateError(message)


def _records(rows):
    result = {}
    for row in rows:
        name = row["path"]
        _need(name not in result and isinstance(name, str), "Duplicate/invalid manifest path")
        result[name] = {"bytes": row["bytes"], "sha256": row["sha256"].lower()}
    return result


def _evidence(path, expected_sha):
    record = package._file_record(Path(path))
    _need(record["sha256"] == expected_sha.lower(), "Source gate evidence changed: " + str(path))
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def _private_nbt(tag):
    if tag.type_id == 10:
        return any(name.casefold() in {"player", "fork", "arena"} or name.casefold().startswith("arena_")
                   or _private_nbt(child) for name, child in tag.value.items())
    if tag.type_id == 9:
        return any(_private_nbt(child) for child in tag.value.items)
    return tag.type_id == 8 and tag.value.casefold().startswith(("arena:", "arena_agents:", "fork:"))


def _state_at(chunk, x, y, z):
    root = chunk.root.value
    section = next((item for item in root["sections"].value.items if item.value["Y"].value == y // 16), None)
    if section is None:
        return "minecraft:air"
    states = section.value["block_states"].value
    palette = states["palette"].value.items
    index = ((y % 16) * 16 + z % 16) * 16 + x % 16
    if len(palette) == 1:
        selected = 0
    else:
        bits = max(4, (len(palette) - 1).bit_length())
        per_long = 64 // bits
        word = states["data"].value[index // per_long] & ((1 << 64) - 1)
        selected = (word >> ((index % per_long) * bits)) & ((1 << bits) - 1)
    return palette[selected].value["Name"].value


def _spawn_check(world, data, source):
    spawn = data["spawn"].value
    _need(spawn["dimension"].value == "minecraft:overworld", "Spawn dimension is not overworld")
    pos = spawn["pos"].value
    _need(len(pos) == 3 and all(type(value) is int for value in pos), "Modern spawn must have three integer coordinates")
    x, y, z = pos
    low_x, low_z, high_x, high_z = source["core_bounds"]
    _need(low_x <= x < high_x and low_z <= z < high_z and -63 <= y <= 318,
          "Modern spawn is outside its selected owned core or height range")
    for name, coordinate in zip(("SpawnX", "SpawnY", "SpawnZ"), pos):
        _need(name not in data or data[name].value == coordinate, "Legacy and modern spawn disagree")
    coords = x // 16, z // 16
    path = world / REGION_PREFIX / f"r.{coords[0] // 32}.{coords[1] // 32}.mca"
    chunk = None
    iterator = anvil.iter_region(path)
    try:
        for key, document in iterator:
            if key == coords:
                chunk = document
                break
    finally:
        iterator.close()
    _need(chunk is not None, "Spawn chunk is missing")
    floor, feet, head = (_state_at(chunk, x, at_y, z) for at_y in (y - 1, y, y + 1))
    local_floor = floor.removeprefix("minecraft:")
    _need(floor.startswith("minecraft:") and (local_floor in SOLID_NAMES or local_floor.endswith(SOLID_SUFFIXES)),
          "Spawn floor is not a supported full solid block")
    _need(feet in AIR and head in AIR, "Spawn feet/head are obstructed")
    return {"sourceId": source["id"], "position": list(pos), "floor": floor, "feet": feet, "head": head,
            "withinOwnedCore": True, "safe": True}


def _component_status(source, structural, spawn_source_id):
    if (structural.get("kind") == "national-pipeline-structural-result" or "validationRole" in structural
            or "componentDisposition" in structural or "assemblyComponentAccepted" in structural):
        from grow_contract import _national_role, _national_metadata
        role = _national_role(structural)
        _need(source["id"] != spawn_source_id, "A National assembly component cannot supply final spawn or configuration")
        _need(all(source.get(field) == value for field, value in role.items())
              and source.get("national_verification") == "STRICT_LOADER_PASS",
              "National normalized component role differs from strict verified evidence")
        provenance, _ = _national_metadata(structural, source)
        _need(all(source.get(field) == value for field, value in provenance.items()),
              "National component binding/request/execution/validator provenance changed")
        return True
    component = structural.get("role") == "assembly-component"
    _need(component == (source.get("role") == "assembly-component"), "Normalized source role differs from actual gate")
    if component:
        _need(source["id"] != spawn_source_id, "An assembly component cannot supply final spawn or configuration")
        _need(structural.get("status") == "PASS" and structural.get("standaloneStatus") == "NOT_STANDALONE"
              and structural.get("finalAssembledSafeSpawnRequired") is True
              and type(structural.get("componentSpawnAccepted")) is bool
              and structural.get("componentSpawnAccepted") is source.get("component_spawn_accepted")
              and source.get("standalone_status") == "NOT_STANDALONE"
              and source.get("final_assembled_safe_spawn_required") is True
              and structural.get("runtimeAccepted") is False and structural.get("fullWorldAccepted") is False,
              "Assembly component's geometry-only role or spawn obligation changed")
    return component


def verify_grown_world(world, sources, region_report, spawn_source_id) -> dict[str, Any]:
    """Consume normalized grow_contract sources and grow_regions exact-copy report.

    Hashes all files, but decodes only the region containing the selected spawn.
    The chunk-copy writer's canonical-NBT proof replaces another whole-world oracle.
    No world/config/receipt file is written; callers may retain the returned JSON.
    """
    result = {"schema": "fork.singapore.grown-world.v1", "status": "FAIL", "assemblyAccepted": False,
              "runtimeLoadAccepted": False, "visualAccepted": False, "aiAccepted": False,
              "scope": "Exact owned-core copy, source and coverage evidence, clean modern settings and selected safe spawn",
              "issues": []}
    try:
        world = package._resolve(world)
        allowed = package._resolve(package.OUTPUT_ROOT)
        _need(world != allowed and package._inside(world, allowed), "Grown save must be a new private merged world")
        # Main verifies a staged world, then renames its snapshot directory.
        # Retain a relative basename rather than a stale absolute staging path.
        result["world"] = world.name
        result["pathsRelativeTo"] = "final snapshot root"
        _need(isinstance(sources, list) and sources, "Normalized source list required")
        ids, rectangles, expected_counts, source_versions, source_spawns = set(), [], {}, set(), {}
        result["sources"] = []
        for source in sources:
            identity = source["id"]
            _need(isinstance(identity, str) and identity and identity not in ids, "Invalid/duplicate source ID")
            ids.add(identity)
            bounds = source["core_bounds"]
            _need(len(bounds) == 4 and all(type(v) is int and v % 16 == 0 for v in bounds)
                  and bounds[0] < bounds[2] and bounds[1] < bounds[3], "Invalid owned core bounds")
            for old in rectangles:
                _need(not (bounds[0] < old[2] and old[0] < bounds[2] and bounds[1] < old[3] and old[1] < bounds[3]),
                      "Source core ownership overlaps")
            rectangles.append(bounds)
            expected_counts[identity] = ((bounds[2] - bounds[0]) // 16) * ((bounds[3] - bounds[1]) // 16)
            source_path = package._resolve(source["world_path"])
            _need(not package._inside(world, source_path) and not package._inside(source_path, world), "Source/output worlds overlap")
            current = package.snapshot_tree(source_path)
            _need(current["files"] == _records(source["outputs"]), "Source world changed: " + identity)
            source_data = anvil.read_level_dat(source_path / "level.dat").root.value["Data"].value
            source_versions.add(source_data["DataVersion"].value)
            if identity == spawn_source_id:
                source_spawns[identity] = source_data["spawn"].value
            writer = _evidence(source["writer_manifest_path"], source["writer_manifest_sha256"])
            structural = _evidence(source["structural_gate_path"], source["structural_gate_sha256"])
            _need(structural.get("status") == "PASS", "Source structural gate is not PASS: " + identity)
            component_only = _component_status(source, structural, spawn_source_id)
            coverage = {}
            for component in ("roads", "water"):
                record = source["coverage"][component]
                if record.get("status") == "rendered_subset_preview":
                    from grow_contract import _coverage
                    subset = _coverage(component, record, bounds,
                        source["writer_manifest_sha256"], writer.get("inputs"))
                    _need(subset.get("status") == "rendered_subset_preview", "Qualified coverage cannot become complete")
                    coverage[component] = {"status": "RENDERED_SUBSET", "featureCount": subset["feature_count"],
                        "evidence_sha256": subset["evidence_sha256"],
                        "sourceComplete": False, "routeComplete": False, "fullFidelity": False}
                    for field in ("classification_sha256", "source_set_sha256", "source_sha256", "contributors",
                                  "omissions", "quarantinedSourceFeatures"):
                        if field in subset:
                            coverage[component][field] = copy.deepcopy(subset[field])
                    continue
                proof = _evidence(record["evidence_path"], record["evidence_sha256"])
                _need(record["status"] in ("included", "pass", "no_features"), "Coverage metadata not accepted")
                _need(proof.get("component") == component and proof.get("status") in ("PASS", "NO_FEATURES")
                      and proof.get("coreBounds") == bounds
                      and proof.get("writerManifestSha256", "").lower() == source["writer_manifest_sha256"].lower()
                      and bool(re.fullmatch(r"[0-9a-fA-F]{64}", proof.get("sourceSha256", ""))),
                      "Coverage evidence does not identify accepted component/core/writer")
                count = proof.get("featureCount")
                _need(type(count) is int and (count == 0 if proof["status"] == "NO_FEATURES" else count > 0),
                      "Coverage feature count disagrees with status")
                _need((record["status"] == "no_features") == (proof["status"] == "NO_FEATURES"), "Coverage no-features status disagrees")
                coverage[component] = {"status": proof["status"], "featureCount": count, "evidence_sha256": record["evidence_sha256"]}
            result["sources"].append({"id": identity, "core_bounds": bounds, "unchanged": True,
                                      "sha256": current["sha256"], "coverage": coverage,
                                      "writer_manifest_sha256": source["writer_manifest_sha256"],
                                      "structural_gate_sha256": source["structural_gate_sha256"],
                                      "role": "assembly-component" if component_only else "standalone",
                                      "standaloneStatus": "NOT_STANDALONE" if component_only else "SOURCE_GATE_PASSED"})
        _need(spawn_source_id in ids, "Spawn source ID is not an accepted source")
        ownership = region_report["ownership"]
        _need(ownership.get("exactCoreCoverage") is True and ownership.get("coordinatesTranslated") is False
              and ownership.get("duplicateOwnership") is False
              and (ownership.get("canonicalNbtVerified") is True or region_report.get("canonicalNbtVerified") is True),
              "Region writer lacks exact unshifted canonical-NBT copy proof")
        expected_chunks = sum(expected_counts.values())
        _need(region_report["chunkCount"] == expected_chunks and region_report["sourceChunkCounts"] == expected_counts,
              "Output chunk/source counts do not match exact owned cores")
        current = package.snapshot_tree(world)
        result["hash_manifest"] = current
        actual_regions = {name: row for name, row in current["files"].items() if REGION.fullmatch(name)}
        _need(actual_regions == _records(region_report["outputs"]), "Output regions differ from verified copy report")
        _need(set(current["files"]) == set(actual_regions) | CONFIG_FILES, "World contains missing config or forbidden player/runtime/POI/map/credential files")
        allowed_dirs = {str(parent).replace("\\", "/") for name in current["files"] for parent in Path(name).parents if str(parent) != "."}
        for entry in world.rglob("*"):
            if entry.is_dir():
                _need(entry.relative_to(world).as_posix() in allowed_dirs, "Unexpected empty/runtime directory: " + entry.name)
        header_chunks = 0
        for name in actual_regions:
            with (world / name).open("rb") as stream:
                header = stream.read(4096)
            _need(len(header) == 4096, "Truncated region header")
            header_chunks += sum(bool(value[0]) for value in struct.iter_unpack(">I", header))
        _need(header_chunks == expected_chunks, "Actual region location count differs from owned count")
        level = anvil.read_level_dat(world / "level.dat")
        settings = anvil.read_level_dat(world / "data/minecraft/world_gen_settings.dat")
        _need(not _private_nbt(level.root) and not _private_nbt(settings.root), "Player/FORK/Arena NBT leaked into world configuration")
        data = level.root.value["Data"].value
        packs = data["DataPacks"].value
        _need([tag.value for tag in packs["Enabled"].value.items] == ["vanilla"]
              and not packs["Disabled"].value.items, "World must use only vanilla datapacks")
        config = settings.root.value["data"].value
        _need(config["generate_structures"].value == 0, "External world settings still generate structures")
        _need(source_versions == {data["DataVersion"].value}, "Source and output data versions disagree")
        original_spawn = source_spawns[spawn_source_id]
        _need(data["spawn"].value["pos"].value == original_spawn["pos"].value
              and data["spawn"].value["dimension"].value == original_spawn["dimension"].value,
              "Selected source's verified modern spawn was not preserved")
        selected = next(source for source in sources if source["id"] == spawn_source_id)
        _need(selected.get("role") != "assembly-component", "Final configuration source cannot be an assembly component")
        # Both configs must derive solely from the selected safe source. The
        # assembly CLI may change only its display name after copying them.
        original_level = anvil.read_level_dat(Path(selected["world_path"]) / "level.dat")
        original_settings = anvil.read_level_dat(Path(selected["world_path"]) / "data/minecraft/world_gen_settings.dat")
        left, right = copy.deepcopy(original_level), copy.deepcopy(level)
        left.root.value["Data"].value.pop("LevelName", None)
        right.root.value["Data"].value.pop("LevelName", None)
        _need(left == right and original_settings == settings,
              "Final configs must preserve the selected safe source; component configs cannot be copied")
        result["spawn"] = _spawn_check(world, data, selected)
        result["configurationSourceId"] = spawn_source_id
        result["finalAssembledSafeSpawnRequired"] = any(source.get("role") == "assembly-component" for source in sources)
        result["data_version"] = data["DataVersion"].value
        result["chunkCount"] = expected_chunks
        result["sourceChunkCounts"] = expected_counts
        result["region_report_sha256"] = hashlib.sha256(package._canonical(region_report)).hexdigest()
        result["assemblyAccepted"] = True
        result["status"] = "PASS"
    except (KeyError, TypeError, ValueError, OSError, StopIteration, IndexError) as exc:
        result["issues"].append(str(exc))
    return result
