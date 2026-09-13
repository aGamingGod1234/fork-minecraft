"""Plan and verify a representative copied-world runtime check; no server launch."""
from __future__ import annotations
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import anvil
import grow_receipt
import package
import runtime_receipt

ATTEMPT_ROOT = package.OUTPUT_ROOT.parent / "runtime-check" / "lim-chu-kang-v1"
RUNTIME_BINDINGS = {
    district: (package.OUTPUT_ROOT / ("grow-" + district) / "world",
               package.OUTPUT_ROOT.parent / "runtime-check" / district)
    for district in ("lim-chu-kang-v1", "changi-v1", "cbd-east-v1", "cbd-east-v2")
}


def _need(value, message):
    if not value:
        raise ValueError(message)


def _district_binding(candidate, attempt):
    candidate, attempt = package._resolve(candidate), package._resolve(attempt)
    for district, (approved_candidate, approved_attempt) in RUNTIME_BINDINGS.items():
        if candidate == package._resolve(approved_candidate) and attempt == package._resolve(approved_attempt):
            return district
    raise ValueError("Candidate and runtime attempt must match one approved district binding")


def _state(name, properties):
    _need(isinstance(name, str) and re.fullmatch(r"minecraft:[a-z0-9_]+", name), "Invalid vanilla block name")
    _need(isinstance(properties, dict) and all(isinstance(k, str) and isinstance(v, str)
          and re.fullmatch(r"[a-z0-9_]+", k) and re.fullmatch(r"[a-z0-9_-]+", v) for k, v in properties.items()),
          "Invalid block state properties")
    return name + ("[" + ",".join(k + "=" + v for k, v in sorted(properties.items())) + "]" if properties else "")


def _read_states(world, positions):
    wanted = set(map(tuple, positions))
    grouped = {}
    for x, y, z in wanted:
        grouped.setdefault((x // 512, z // 512), {}).setdefault((x // 16, z // 16), []).append((x, y, z))
    found = {}
    for (rx, rz), chunks in grouped.items():
        iterator = anvil.iter_region(world / grow_receipt.REGION_PREFIX / f"r.{rx}.{rz}.mca")
        try:
            for coords, document in iterator:
                if coords not in chunks:
                    continue
                sections = {item.value["Y"].value: item.value for item in document.root.value["sections"].value.items}
                for x, y, z in chunks.pop(coords):
                    section = sections.get(y // 16)
                    if section is None:
                        found[(x, y, z)] = "minecraft:air"
                        continue
                    states = section["block_states"].value
                    palette = states["palette"].value.items
                    index = ((y % 16) * 16 + z % 16) * 16 + x % 16
                    selected = 0
                    if len(palette) > 1:
                        bits = max(4, (len(palette) - 1).bit_length())
                        per = 64 // bits
                        word = states["data"].value[index // per] & ((1 << 64) - 1)
                        selected = (word >> ((index % per) * bits)) & ((1 << bits) - 1)
                    item = palette[selected].value
                    properties = item.get("Properties")
                    found[(x, y, z)] = _state(item["Name"].value, {} if properties is None else {k: v.value for k, v in properties.value.items()})
                if not chunks:
                    break
        finally:
            iterator.close()
    _need(set(found) == wanted, "A sentinel chunk is absent from the candidate")
    return found


def prepare_runtime_plan(candidate, bounds, sentinels, *, attempt_root=ATTEMPT_ROOT, max_selected_chunks=256):
    candidate, attempt = package._resolve(candidate), package._resolve(attempt_root)
    district = _district_binding(candidate, attempt)
    _need(len(bounds) == 4 and all(type(v) is int and v % 16 == 0 for v in bounds)
          and bounds[0] < bounds[2] and bounds[1] < bounds[3], "Invalid chunk-aligned crop bounds")
    _need(type(max_selected_chunks) is int and 1 <= max_selected_chunks <= 256, "Selected chunk cap must be 1..256")
    before = package.snapshot_tree(candidate)
    data = anvil.read_level_dat(candidate / "level.dat").root.value["Data"].value
    _need(data["DataVersion"].value == 4790, "Runtime source must target Minecraft 26.1.2")
    spawn = data["spawn"].value
    _need(spawn["dimension"].value == "minecraft:overworld", "Spawn must be in overworld")
    sx, sy, sz = spawn["pos"].value
    definitions, identifiers, kinds, quadrants = [], set(), set(), set()
    for item in sentinels:
        identity, kind = item["id"], item["kind"]
        _need(isinstance(identity, str) and re.fullmatch(r"[A-Za-z0-9_-]+", identity), "Invalid sentinel ID")
        marker = "FORK_RUNTIME_BLOCK_" + identity.upper() + "_OK"
        _need(marker not in identifiers and not identity.upper().startswith("SPAWN_"), "Duplicate/reserved sentinel ID")
        identifiers.add(marker)
        _need(kind in {"building", "road", "terrain"}, "Sentinel kind must be building, road or terrain")
        x, y, z = (item[key] for key in ("x", "y", "z"))
        _need(all(type(v) is int for v in (x, y, z)) and bounds[0] <= x < bounds[2]
              and bounds[1] <= z < bounds[3] and -64 <= y < 320, "Sentinel is outside crop/height bounds")
        definitions.append({"id": identity, "pos": [x, y, z], "state": _state(item["block"], item.get("properties", {})),
                            "kind": kind, "marker": marker})
        kinds.add(kind)
        quadrants.add((x >= (bounds[0] + bounds[2]) // 2, z >= (bounds[1] + bounds[3]) // 2))
    _need(kinds == {"building", "road", "terrain"} and len(quadrants) == 4,
          "Building/road/terrain and all four crop quadrants require representative sentinels")
    _need(all(type(v) is int for v in (sx, sy, sz)) and bounds[0] <= sx < bounds[2]
          and bounds[1] <= sz < bounds[3] and -63 <= sy <= 318, "Modern spawn is outside the owned crop")
    positions = [item["pos"] for item in definitions] + [[sx, y, sz] for y in (sy - 1, sy, sy + 1)]
    actual = _read_states(candidate, positions)
    for item in definitions:
        _need(actual[tuple(item["pos"])] == item["state"], "Frozen source block differs from supplied sentinel: " + item["id"])
    floor, feet, head = [actual[(sx, y, sz)] for y in (sy - 1, sy, sy + 1)]
    floor_name = floor.split("[", 1)[0].removeprefix("minecraft:")
    _need(floor_name in grow_receipt.SOLID_NAMES or floor_name.endswith(grow_receipt.SOLID_SUFFIXES), "Spawn floor is not a full solid block")
    _need(feet in grow_receipt.AIR and head in grow_receipt.AIR, "Spawn feet/head are obstructed")
    for label, y in (("FLOOR", sy - 1), ("FEET", sy), ("HEAD", sy + 1)):
        definitions.append({"id": "SPAWN_" + label, "kind": "spawn", "pos": [sx, y, sz],
                            "state": actual[(sx, y, sz)], "marker": "FORK_RUNTIME_BLOCK_SPAWN_" + label + "_OK"})
    chunks = sorted({(item["pos"][0] // 16, item["pos"][2] // 16) for item in definitions})
    _need(len(chunks) <= max_selected_chunks, "Sentinel chunks exceed the resident chunk cap")
    _need(package.snapshot_tree(candidate) == before, "Candidate changed while preparing runtime sentinels")
    chunk_markers = [f"FORK_RUNTIME_CHUNK_{cx}_{cz}_OK" for cx, cz in chunks]
    total_chunks = ((bounds[2] - bounds[0]) // 16) * ((bounds[3] - bounds[1]) // 16)
    plan = {"schema": "fork.grown-runtime-plan.v1", "district": district,
            "candidate_path": str(candidate), "candidate_before": before, "crop_chunk_count": total_chunks,
            "bounds": list(bounds), "attempt_root": str(attempt), "selected_chunks": [list(c) for c in chunks],
            "expected_chunk_markers": chunk_markers, "sentinels": definitions,
            "forceload_commands": [f"forceload add {cx * 16 + 8} {cz * 16 + 8}" for cx, cz in chunks],
            "chunk_commands": [f"execute if loaded {cx * 16 + 8} 1 {cz * 16 + 8} run say {marker}"
                               for (cx, cz), marker in zip(chunks, chunk_markers)],
            "block_commands": [f"execute if block {' '.join(map(str, item['pos']))} {item['state']} run say {item['marker']}" for item in definitions],
            "finish_commands": ["save-all flush", "stop"], "max_selected_chunks": max_selected_chunks,
            "runtime_scope": f"Representative {len(chunks)} selected chunks and source-proven blocks within a {total_chunks}-chunk crop; no whole-crop runtime acceptance is claimed"}
    plan["plan_sha256"] = hashlib.sha256(package._canonical(plan)).hexdigest()
    return plan


class StdinTranscript:
    """Write identical UTF-8 LF bytes to server stdin and an exclusive retained file."""
    def __init__(self, path, allowed_commands):
        self.path = Path(path)
        self.allowed = set(allowed_commands)
        self.stream = self.path.open("xb")

    def send(self, stdin, command):
        _need(command in self.allowed and "\n" not in command and "\r" not in command, "Command is outside the frozen runtime plan")
        payload = (command + "\n").encode("utf-8")
        channel = getattr(stdin, "buffer", stdin)
        written = channel.write(payload)
        _need(written == len(payload), "Partial server stdin write")
        channel.flush()
        self.stream.write(payload)
        self.stream.flush()
        os.fsync(self.stream.fileno())

    def close(self):
        if not self.stream.closed:
            self.stream.flush()
            os.fsync(self.stream.fileno())
            self.stream.close()
        return package._file_record(self.path)


def verify_grow_runtime(spec, plan, transcript_path):
    result = {"schema": "fork.grown-runtime-receipt.v1", "runtimeLoadAccepted": False, "visualAccepted": False,
              "aiAccepted": False, "allOwnedChunksRuntimeTested": False, "issues": [], "scope": plan.get("runtime_scope")}
    try:
        unsigned = dict(plan)
        expected = unsigned.pop("plan_sha256")
        _need(hashlib.sha256(package._canonical(unsigned)).hexdigest() == expected, "Runtime plan hash changed")
        attempt = package._resolve(plan["attempt_root"])
        district = _district_binding(plan["candidate_path"], attempt)
        _need(plan.get("district", district) == district, "Plan district differs from candidate/runtime binding")
        _need(package._resolve(spec["copied_world_path"]) == attempt / "world", "Wrong isolated runtime copy")
        _need(package.snapshot_tree(plan["candidate_path"]) == plan["candidate_before"], "Candidate changed during runtime")
        definitions_path = Path(spec["sentinel_definitions_path"])
        _need(json.loads(definitions_path.read_text(encoding="utf-8-sig")) == plan["sentinels"], "Retained sentinel definitions differ from plan")
        result["sentinel_definitions"] = {"path": str(definitions_path), **package._file_record(definitions_path)}
        transcript_path = Path(transcript_path)
        raw = transcript_path.read_bytes()
        _need(raw.endswith(b"\n") and b"\r" not in raw, "Transcript must retain exact UTF-8 LF commands")
        commands = raw.decode("utf-8").splitlines()
        phases = [plan["forceload_commands"], plan["chunk_commands"], plan["block_commands"], ["save-all flush"], ["stop"]]
        ordering = {command: phase for phase, group in enumerate(phases) for command in group}
        _need(set(commands) == set(ordering), "Transcript omits or adds commands outside the frozen plan")
        _need(all(ordering[left] <= ordering[right] for left, right in zip(commands, commands[1:])), "Runtime command phases are out of order")
        result["stdin_transcript"] = {"path": str(transcript_path), **package._file_record(transcript_path), "command_count": len(commands)}
        log_path = Path(spec["log_path"])
        log = log_path.read_text(encoding="utf-8-sig")
        done = re.search(r'Done \([0-9.]+s\)!', log)
        stop = re.search(r'\bStopping server\b', log)
        saving = re.compile(r'\bSaving chunks\b').search(log, stop.end()) if stop else None
        saved = re.compile(r'\bAll dimensions are saved\b').search(log, saving.end()) if saving else None
        _need(done and stop and saving and saved and done.end() < stop.start()
              and 'Preparing level "world"' in log and re.search(r'Starting minecraft server version 26\.1\.2\b', log, re.I),
              "Missing actual target startup or clean shutdown evidence")
        _need(not runtime_receipt.ERROR_PATTERN.search(log), "Runtime log contains errors/failed-load evidence")
        expected_markers = plan["expected_chunk_markers"] + [item["marker"] for item in plan["sentinels"]]
        found = []
        for marker in expected_markers:
            match = re.search(r'\[Server\]\s+' + re.escape(marker) + r'\s*$', log, re.M)
            if match and done.end() < match.start() < stop.start():
                found.append(marker)
        result["missing_markers"] = sorted(set(expected_markers) - set(found))
        _need(not result["missing_markers"], "Selected chunk or block sentinel did not confirm in game")
        process = spec["process"]
        _need(type(process["pid"]) is int and process["pid"] > 0 and process["exit_code"] == 0
              and process["observed_alive"] is False and process.get("observed_at_utc"), "Server did not exit zero with observed dead PID")
        _need(spec["max_heap_mib"] == 3072 and spec["active_processor_count"] == 1 and spec["cpu_affinity_mask"] == 256,
              "Runtime resource limits differ from the approved 3 GiB/one CPU offset8")
        _need(spec["free_memory_before_bytes"] >= 8 * 1024 ** 3
              and spec["minimum_free_memory_bytes"] >= 8 * 1024 ** 3, "Runtime free-memory floor was not maintained")
        jar = package._file_record(Path(spec["jar_path"]))
        _need(jar["sha256"] == spec["expected_jar_sha256"].lower() and spec["minecraft_version"] == "26.1.2"
              and isinstance(spec["java_identity"], str) and spec["java_identity"].strip(), "Runtime JAR/version/Java identity mismatch")
        bounds = plan["bounds"]
        crop_chunks = ((bounds[2] - bounds[0]) // 16) * ((bounds[3] - bounds[1]) // 16)
        result.update({"runtimeLoadAccepted": True, "plan_sha256": expected, "district": district,
                       "crop_chunk_count": crop_chunks, "selected_chunk_count": len(plan["selected_chunks"]),
                       "block_sentinel_count": len(plan["sentinels"]), "candidateUnchanged": True,
                       "candidate_path": plan["candidate_path"], "copied_world_path": spec["copied_world_path"],
                       "minecraft_version": spec["minecraft_version"], "java_identity": spec["java_identity"],
                       "port": spec.get("port"), "max_heap_mib": spec["max_heap_mib"],
                       "active_processor_count": spec["active_processor_count"], "cpu_affinity_mask": spec["cpu_affinity_mask"],
                       "free_memory_before_bytes": spec["free_memory_before_bytes"],
                       "copied_world_after": package.snapshot_tree(spec["copied_world_path"]), "jar": jar,
                       "log": package._file_record(log_path), "process": process,
                       "minimum_free_memory_bytes": spec["minimum_free_memory_bytes"]})
    except (KeyError, TypeError, ValueError, OSError) as exc:
        result["issues"].append(str(exc))
    return result
