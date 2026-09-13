"""Isolated one-chunk AIR_ONLY generator probe; never edits an accepted world."""
from __future__ import annotations
import argparse
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time

import anvil
import package
from grow_receipt import AIR, SOLID_NAMES, SOLID_SUFFIXES
from grow_runtime import StdinTranscript
from grow_runtime_run import peak_working_set_bytes
import runtime_receipt

BASE = Path(r"C:\Users\User\AppData\Local\FORK-Tools")
FULL = BASE / "fork-singapore-full"
CANDIDATE = FULL / "merged/scoped-air-only-probe-v1/world"
ATTEMPT = FULL / "runtime-check/scoped-air-only-probe-v1"
REGIONS = "dimensions/minecraft/overworld/region"
SETTINGS = "data/minecraft/world_gen_settings.dat"
HEIGHTMAPS = {"WORLD_SURFACE", "OCEAN_FLOOR", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES"}
JAR = BASE / "minecraft-server-26.1.2/server.jar"
JAR_SHA = "cd47e7c38328f64768fd17af8fcd8b22496b40b63d4ffee81e71ae059fedcb42"
JAVA = BASE / "java/jdk-25.0.4.1+1/bin/java.exe"


def need(condition, message):
    if not condition:
        raise ValueError(message)


def write(path, value):
    with Path(path).open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write("\n")


def record(path):
    return package._file_record(Path(path))


def pinned_json(path, expected):
    need(record(path)["sha256"] == expected, "Pinned JSON hash changed: " + str(path))
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def named(parent, key, kind):
    tag = parent.get(key)
    need(isinstance(tag, anvil.Tag) and tag.type_id == kind, "Missing/wrong NBT type: " + key)
    return tag.value


def section_states(section):
    states = named(section, "block_states", anvil.COMPOUND)
    palette = named(states, "palette", anvil.LIST)
    need(palette.element_type == anvil.COMPOUND and bool(palette.items), "Invalid block palette")
    names = [named(item.value, "Name", anvil.STRING) for item in palette.items]
    data = states.get("data")
    if len(names) == 1:
        need(data is None or (data.type_id == anvil.LONG_ARRAY and not data.value),
             "Singleton palette carries unsupported index data")
        return names, [0] * 4096
    bits = max(4, (len(names) - 1).bit_length())
    per = 64 // bits
    words = named(states, "data", anvil.LONG_ARRAY)
    need(len(words) == (4096 + per - 1) // per, "Wrong packed block data length")
    indices = [((words[index // per] & ((1 << 64) - 1)) >> ((index % per) * bits)) & ((1 << bits) - 1)
               for index in range(4096)]
    need(all(index < len(names) for index in indices), "Packed palette index out of bounds")
    return names, indices


def section_map(document):
    root = anvil.chunk_payload(document)
    sections = named(root, "sections", anvil.LIST)
    need(sections.element_type == anvil.COMPOUND, "Invalid section list type")
    result = {}
    seen = set()
    for item in sections.items:
        sy = named(item.value, "Y", anvil.BYTE)
        need(sy not in seen, "Duplicate section Y")
        seen.add(sy)
        if sy in (-5, 20):
            # Minecraft may serialize boundary lighting outside build height.
            need(set(item.value) <= {"Y", "BlockLight", "SkyLight"}, "Non-lighting data outside block height")
            for name, tag in item.value.items():
                if name != "Y":
                    need(tag.type_id == anvil.BYTE_ARRAY and len(tag.value) == 2048, "Malformed boundary lighting")
            continue
        need(-4 <= sy < 20, "Out-of-range section Y")
        result[sy] = item.value
    need(set(result) == set(range(-4, 20)), "All 24 saved sections are required")
    return result


def verify_air_chunk(document, target):
    need("Level" not in document.root.value, "Legacy nested chunk root is not accepted")
    root = anvil.chunk_payload(document)
    need(anvil.chunk_coords(document) == tuple(target), "Wrong target chunk coordinates")
    need(named(root, "DataVersion", anvil.INT) == 4790 and named(root, "yPos", anvil.INT) == -4,
         "Wrong Minecraft version or vertical origin")
    need(named(root, "Status", anvil.STRING) == "minecraft:full", "Target is a protochunk, not a full loaded chunk")
    sections = section_map(document)
    checked = 0
    for sy in range(-4, 20):
        names, indices = section_states(sections[sy])
        palette = sections[sy]["block_states"].value["palette"].value.items
        for index, selected in enumerate(indices):
            properties = palette[selected].value.get("Properties")
            need(names[selected] == "minecraft:air" and (properties is None or
                 (properties.type_id == anvil.COMPOUND and not properties.value)),
                 f"Noncanonical air target block at section {sy}, index {index}: {names[selected]}")
            checked += 1
    maps = named(root, "Heightmaps", anvil.COMPOUND)
    need(HEIGHTMAPS <= set(maps), "Required saved heightmaps are missing")
    for name, tag in maps.items():
        need(tag.type_id == anvil.LONG_ARRAY and len(tag.value) == 37 and all(word == 0 for word in tag.value),
             "Generated AIR_ONLY heightmap is not empty: " + name)
    need(not named(root, "block_entities", anvil.LIST).items, "Air chunk contains block entities")
    return {"targetChunk": list(target), "sectionsChecked": 24, "cellsChecked": checked,
            "allAir": True, "heightmapsChecked": sorted(maps), "heightmapsEmpty": True,
            "savedStatus": "minecraft:full", "chunkNbtSha256": hashlib.sha256(anvil.write_nbt(document)).hexdigest()}


def read_chunk(world, coords):
    cx, cz = coords
    path = Path(world) / REGIONS / f"r.{cx // 32}.{cz // 32}.mca"
    if not path.exists():
        return None
    iterator = anvil.iter_region(path)
    try:
        for found, document in iterator:
            if found == tuple(coords):
                return document
    finally:
        iterator.close()
    return None


def block_state(document, x, y, z):
    section = section_map(document)[y // 16]
    names, indices = section_states(section)
    selected = indices[((y % 16) * 16 + z % 16) * 16 + x % 16]
    palette = section["block_states"].value["palette"].value.items[selected].value
    properties = palette.get("Properties")
    suffix = "" if properties is None else "[" + ",".join(key + "=" + value.value for key, value in sorted(properties.value.items())) + "]"
    return names[selected] + suffix


def verify_profile(profile, world, writer):
    need(profile.get("kind") == "scoped-unmapped-worldgen-profile" and profile.get("schemaVersion") == 1
         and profile.get("profileId") == "flat-air-only-v1", "Wrong explicit AIR_ONLY profile receipt")
    need(writer.get("unmappedGenerator") == profile, "Exported profile differs from pinned writer receipt")
    actual = Path(world) / SETTINGS
    output_pin = profile["outputSettings"]
    need(record(actual) == {key: output_pin[key] for key in ("bytes", "sha256")}, "Profile output settings bytes changed")
    output = anvil.read_level_dat(actual)
    need(hashlib.sha256(anvil.write_nbt(output)).hexdigest() == profile["outputNbtSha256"], "Profile typed output NBT hash changed")
    source_pin = profile["sourceSettings"]
    need(record(source_pin["path"]) == {key: source_pin[key] for key in ("bytes", "sha256")}, "Pristine settings source changed")
    source = anvil.read_level_dat(source_pin["path"])
    need(hashlib.sha256(anvil.write_nbt(source)).hexdigest() == profile["sourceNbtSha256"], "Profile typed source NBT hash changed")
    need(named(output.root.value, "DataVersion", anvil.INT) == 4790, "Wrong profile settings version")
    data = named(output.root.value, "data", anvil.COMPOUND)
    dims = named(data, "dimensions", anvil.COMPOUND)
    overworld = named(dims, "minecraft:overworld", anvil.COMPOUND)
    need(named(overworld, "type", anvil.STRING) == "minecraft:overworld", "Wrong overworld type")
    generator = named(overworld, "generator", anvil.COMPOUND)
    need(named(generator, "type", anvil.STRING) == "minecraft:flat", "Profile is not a flat generator")
    settings = named(generator, "settings", anvil.COMPOUND)
    layers = named(settings, "layers", anvil.LIST)
    need(layers.element_type == anvil.COMPOUND and len(layers.items) == 1
         and named(layers.items[0].value, "block", anvil.STRING) == "minecraft:air"
         and named(layers.items[0].value, "height", anvil.INT) == 1, "Generator has a non-air flat layer")
    need(named(settings, "biome", anvil.STRING) == "minecraft:plains"
         and named(settings, "features", anvil.BYTE) == 0 and named(settings, "lakes", anvil.BYTE) == 0
         and named(data, "generate_structures", anvil.BYTE) == 0
         and not named(settings, "structure_overrides", anvil.LIST).items, "Generator decoration/structures are enabled")
    need("structures" not in settings or named(settings, "structures", anvil.COMPOUND) == {}, "Legacy structures are enabled")
    restored = deepcopy(output)
    restored_data = restored.root.value["data"].value
    original_data = source.root.value["data"].value
    restored_settings = restored_data["dimensions"].value["minecraft:overworld"].value["generator"].value["settings"].value
    original_settings = original_data["dimensions"].value["minecraft:overworld"].value["generator"].value["settings"].value
    for key in ("layers", "features", "lakes", "structure_overrides"):
        restored_settings[key] = deepcopy(original_settings[key])
    restored_data["generate_structures"] = deepcopy(original_data["generate_structures"])
    need(anvil.write_nbt(restored) == anvil.write_nbt(source), "Profile altered metadata outside allowed generator fields")
    need(any(pin.get("sha256") == source_pin["sha256"] for pin in writer.get("templateDependencies", [])),
         "Writer did not verify the pristine settings dependency")
    return {"profileId": profile["profileId"], "settings": record(actual), "independentMetadataCheck": True}


def prepare(spec):
    world = Path(spec["candidate"]).resolve()
    need(world == CANDIDATE.resolve(), "Only the new scoped fixture path is allowed")
    writer = pinned_json(spec["writerManifestPath"], spec["writerManifestSha256"])
    profile = pinned_json(spec["profileReceiptPath"], spec["profileReceiptSha256"])
    need(writer.get("kind") == "global-block-run-world" and writer.get("chunkCount") == 1
         and writer.get("bounds") == [30208, 30208, 30224, 30224] and writer.get("dataVersion") == 4790
         and writer.get("regionDirectory") == REGIONS, "Not the approved one-chunk scoped fixture")
    snapshot = package.snapshot_tree(world)
    pins = {item["path"]: {key: item[key] for key in ("bytes", "sha256")} for item in writer["outputs"]}
    need(len(pins) == len(writer["outputs"]) and snapshot["files"] == pins, "Writer output pins do not match the complete fixture")
    profile_check = verify_profile(profile, world, writer)
    target = spec["targetChunk"]
    need(target == [1889, 1888], "Only the adjacent +X previously-unwritten chunk is in scope")
    need(read_chunk(world, target) is None, "Target chunk already exists in immutable input")
    all_chunks = [coords for path in (world / REGIONS).glob("r.*.mca") for coords, _ in anvil.iter_region(path)]
    need(all_chunks == [(1888, 1888)], "Immutable input is not exactly one mapped chunk")
    data = anvil.read_level_dat(world / "level.dat").root.value["Data"].value
    spawn = named(data, "spawn", anvil.COMPOUND)
    need(named(spawn, "dimension", anvil.STRING) == "minecraft:overworld", "Wrong spawn dimension")
    x, y, z = named(spawn, "pos", anvil.INT_ARRAY)
    need([x, y, z] == writer["spawn"] and (x // 16, z // 16) == (1888, 1888) and -63 <= y <= 318, "Unsafe/out-of-scope modern spawn")
    mapped = read_chunk(world, [1888, 1888])
    states = [block_state(mapped, x, level, z) for level in (y - 1, y, y + 1)]
    floor = states[0].split("[", 1)[0].removeprefix("minecraft:")
    need(floor in SOLID_NAMES or floor.endswith(SOLID_SUFFIXES), "Mapped spawn has no full solid floor")
    need(states[1] in AIR and states[2] in AIR, "Mapped spawn feet/head obstructed")
    commands = ["forceload add 30216 30216", "forceload add 30232 30216",
                "execute if loaded 30216 1 30216 run say FORK_SCOPED_MAPPED_LOADED",
                "execute if loaded 30232 1 30216 run say FORK_SCOPED_TARGET_LOADED"]
    for label, level, block in zip(("FLOOR", "FEET", "HEAD"), (y - 1, y, y + 1), states):
        commands.append(f"execute if block {x} {level} {z} {block} run say FORK_SCOPED_SPAWN_{label}_OK")
    commands += ["save-all flush", "stop"]
    return {"candidateBefore": snapshot, "targetAbsentBefore": True, "profile": profile_check,
            "profileSourceSettings": profile["sourceSettings"], "spawn": [x, y, z], "spawnStates": states,
            "targetChunk": target, "commands": commands, "mappedChunk": [1888, 1888]}


def verify_log(log, commands, transcript):
    import re
    done = re.search(r"Done \([0-9.]+s\)!", log)
    stop = re.search(r"\bStopping server\b", log)
    need(done and stop and done.end() < stop.start() and 'Preparing level "world"' in log
         and "Starting minecraft server version 26.1.2" in log, "Missing actual target startup/shutdown")
    saving = log.find("Saving chunks", stop.end())
    saved = log.find("All dimensions are saved", saving) if saving >= 0 else -1
    need(saving >= stop.end() and saved > saving
         and not runtime_receipt.ERROR_PATTERN.search(log), "Unclean or failed Minecraft save/load")
    markers = [command.split("run say ", 1)[1] for command in commands if "run say " in command]
    for marker in markers:
        need(re.search(r"\[Server\]\s+" + re.escape(marker) + r"\s*$", log[done.end():stop.start()], re.M), "Missing real command marker: " + marker)
    need(transcript.endswith(b"\n") and b"\r" not in transcript, "Transcript encoding changed")
    recorded = transcript.decode("utf-8").splitlines()
    need(set(recorded) == set(commands), "Transcript commands do not match scoped plan")
    order = {command: index for index, command in enumerate(commands)}
    # The two loaded checks can repeat together while chunks finish loading.
    phase = lambda command: 2 if order[command] in (2, 3) else order[command]
    need(all(phase(left) <= phase(right) for left, right in zip(recorded, recorded[1:])), "Runtime command phases changed")
    return markers


def run(spec):
    plan = prepare(spec)
    need(not ATTEMPT.exists(), "Runtime attempt already exists; never overwrite it")
    admission = pinned_json(spec["admissionPath"], spec["admissionSha256"])
    need(admission.get("acceptedByCoordinator") is True and admission.get("processorOffset") == 8
         and admission.get("cpuThreads") == 1 and admission.get("memoryGiB") == 3,
         "Exact shared queue admission for CPU8 / 3GiB is required")
    need(record(JAR)["sha256"] == JAR_SHA, "Prepared Minecraft server JAR changed")
    eula = BASE / "fork-build-20260913/world-validation-1216/eula.txt"
    need("eula=true" in eula.read_text(encoding="utf-8-sig").lower(), "Prepared accepted EULA file missing")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 25579))
    queue_path = BASE / "fork-minecraft-worktrees/full-singapore-queue/tools/singapore-full/queue/queue.py"
    module = importlib.util.spec_from_file_location("scoped_probe_queue", queue_path)
    queue = importlib.util.module_from_spec(module)
    sys.modules[module.name] = queue
    module.loader.exec_module(queue)
    def resources():
        value = queue.resource_snapshot(FULL)
        need(value["freeRam"] >= 8 * 1024**3 and value["freeDisk"] >= 100 * 1024**3, "Runtime RAM/disk floor crossed")
        return value
    before = resources()
    ATTEMPT.mkdir(parents=True)
    write(ATTEMPT / "probe-spec.json", spec)
    write(ATTEMPT / "probe-plan.json", plan)
    shutil.copytree(CANDIDATE, ATTEMPT / "world")
    need(package.snapshot_tree(ATTEMPT / "world") == plan["candidateBefore"], "Runtime copy differs from pinned source")
    shutil.copyfile(JAR, ATTEMPT / "server.jar")
    shutil.copyfile(eula, ATTEMPT / "eula.txt")
    (ATTEMPT / "server.properties").write_text("\n".join([
        "server-ip=127.0.0.1", "server-port=25579", "level-name=world", "online-mode=true", "enable-rcon=false",
        "enable-query=false", "enable-status=false", "max-players=1", "view-distance=2", "simulation-distance=2",
        "spawn-protection=0", "sync-chunk-writes=true", "max-tick-time=60000", ""]), encoding="ascii")
    transcript = StdinTranscript(ATTEMPT / "stdin-transcript.txt", plan["commands"])
    command = [str(JAVA), "-Xms256M", "-Xmx3072M", "-XX:ActiveProcessorCount=1", "-jar", "server.jar", "nogui"]
    state = {"status": "STARTING", "startedUtc": datetime.now(timezone.utc).isoformat(), "command": command,
             "resourceBefore": before, "minimumFreeRamBytes": before["freeRam"], "minimumFreeDiskBytes": before["freeDisk"],
             "javaPeakWorkingSetBytes": None, "javaPeakWorkingSetSamples": 0, "javaPeakWorkingSetErrors": [],
             "cpuAffinityMask": 256, "wholeTreeMemoryLimitBytes": 3 * 1024**3, "priority": "BelowNormal"}
    process = None
    guard = queue.ChildGuard(3, processor_offset=8, cpu_threads=1)
    started = time.monotonic()
    console_path = ATTEMPT / "console.log"
    def sample():
        if process is not None:
            try:
                observed = peak_working_set_bytes(int(process._handle))
                state["javaPeakWorkingSetBytes"] = max(state["javaPeakWorkingSetBytes"] or 0, observed)
                state["javaPeakWorkingSetSamples"] += 1
            except OSError as error:
                state["javaPeakWorkingSetErrors"].append(str(error))
    def log():
        return console_path.read_text(encoding="utf-8", errors="replace") if console_path.exists() else ""
    def wait_for(predicate, seconds):
        # Reserve 20 seconds of the 180-second cap for owned cleanup.
        until = min(started + 160, time.monotonic() + seconds)
        while True:
            sample()
            if predicate(log()):
                return
            need(process.poll() is None, "Java exited before runtime marker")
            current = resources()
            state["minimumFreeRamBytes"] = min(state["minimumFreeRamBytes"], current["freeRam"])
            state["minimumFreeDiskBytes"] = min(state["minimumFreeDiskBytes"], current["freeDisk"])
            need(time.monotonic() < until, "Runtime marker exceeded deadline")
            time.sleep(.2)
    try:
        with console_path.open("xb") as console:
            process = subprocess.Popen(command, cwd=ATTEMPT, stdin=subprocess.PIPE, stdout=console, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW | subprocess.BELOW_NORMAL_PRIORITY_CLASS)
            guard.assign(process)
            state["owner"] = queue.identity(process.pid)
            state["status"] = "RUNNING"
            write(ATTEMPT / "launch.json", state)
            print(json.dumps(state), flush=True)
            wait_for(lambda text: "Done (" in text and ")! For help, type" in text, 60)
            for text in plan["commands"][:2]:
                transcript.send(process.stdin, text)
            for attempt in range(20):
                for text in plan["commands"][2:4]:
                    transcript.send(process.stdin, text)
                time.sleep(.25)
                sample()
                resources()
                if all(marker in log() for marker in ("FORK_SCOPED_MAPPED_LOADED", "FORK_SCOPED_TARGET_LOADED")):
                    break
            wait_for(lambda text: "FORK_SCOPED_MAPPED_LOADED" in text and "FORK_SCOPED_TARGET_LOADED" in text, 10)
            for text in plan["commands"][4:7]:
                transcript.send(process.stdin, text)
            wait_for(lambda text: all("FORK_SCOPED_SPAWN_" + name + "_OK" in text for name in ("FLOOR", "FEET", "HEAD")), 10)
            transcript.send(process.stdin, "save-all flush")
            wait_for(lambda text: "Saved the game" in text, 20)
            transcript.send(process.stdin, "stop")
            wait_for(lambda _: process.poll() is not None, 60)
    except BaseException as error:
        state["error"] = str(error)
        if process is not None and process.poll() is None:
            try:
                transcript.send(process.stdin, "stop")
                process.wait(timeout=10)
            except Exception:
                guard.terminate(process)
                process.wait(timeout=10)
    finally:
        if process is not None and process.poll() is None:
            guard.terminate(process)
            process.wait(timeout=10)
        sample()
        guard.close()
        transcript.close()
        state.update({"exitCode": process.returncode if process else None, "elapsedSeconds": time.monotonic() - started,
                      "finishedUtc": datetime.now(timezone.utc).isoformat(),
                      "verifiedDead": process is not None and process.poll() is not None
                          and bool(state.get("owner")) and not queue.alive(state["owner"])})
        state["status"] = "PROCESS_EXITED" if state["exitCode"] == 0 and state["verifiedDead"] else "PROCESS_FAILED"
        write(ATTEMPT / "runtime-state.json", state)
    receipt = {"kind": "scoped-air-only-runtime-probe", "schemaVersion": 1, "status": "FAIL", "issues": [],
               "runtimeLoadAccepted": False, "generatedNewChunksTested": False, "fullWorldAccepted": False,
               "fullFidelity": False, "scope": "One previously-unwritten neighbor and mapped spawn only",
               "javaPeakWorkingSetBytes": state["javaPeakWorkingSetBytes"], "runtimeState": record(ATTEMPT / "runtime-state.json"),
               "inputSpec": record(ATTEMPT / "probe-spec.json"), "transcript": record(ATTEMPT / "stdin-transcript.txt")}
    try:
        need(not state.get("error") and state["exitCode"] == 0 and state["verifiedDead"], "Java did not exit cleanly")
        need(state["javaPeakWorkingSetBytes"] is not None, "Java peak memory was not measured")
        markers = verify_log(log(), plan["commands"], (ATTEMPT / "stdin-transcript.txt").read_bytes())
        need(package.snapshot_tree(CANDIDATE) == plan["candidateBefore"], "Immutable source changed during probe")
        need(read_chunk(CANDIDATE, plan["targetChunk"]) is None, "Target appeared in immutable source")
        need(record(plan["profileSourceSettings"]["path"]) == {key: plan["profileSourceSettings"][key] for key in ("bytes", "sha256")}, "Pristine template settings changed")
        target = read_chunk(ATTEMPT / "world", plan["targetChunk"])
        need(target is not None, "Previously-unwritten target missing after save")
        verified = verify_air_chunk(target, plan["targetChunk"])
        receipt.update({"status": "PASS", "runtimeLoadAccepted": True, "generatedNewChunksTested": True,
                        "targetAbsentBefore": True, "sourceUnchanged": True, "markers": markers, "target": verified,
                        "mappedSpawn": plan["spawn"], "console": record(console_path)})
    except (ValueError, KeyError, TypeError, OSError) as error:
        receipt["issues"].append(str(error))
    write(ATTEMPT / "runtime-receipt.json", receipt)
    print(json.dumps(receipt), flush=True)
    need(receipt["status"] == "PASS", "Scoped runtime probe failed; retained evidence only")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True)
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    spec = json.loads(Path(args.spec).read_text(encoding="utf-8-sig"))
    if args.prepare_only:
        print(json.dumps(prepare(spec), indent=2))
    else:
        run(spec)


if __name__ == "__main__":
    main()
