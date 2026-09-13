"""Bounded, isolated Desktop Minecraft load check of a copied candidate world."""
from __future__ import annotations
import argparse
import ctypes
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import queue
import re
import shutil
import socket
import subprocess
import threading
import time

ROOT = Path(r"C:\Users\User\AppData\Local\FORK-Tools\fork-singapore-full\runtime-check")


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(part)
    return digest.hexdigest()


def snapshot(root):
    root = Path(root)
    return {file.relative_to(root).as_posix(): sha(file) for file in sorted(root.rglob("*")) if file.is_file()}


def atomic_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def free_memory():
    class MemoryStatus(ctypes.Structure):
        _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [(field, ctypes.c_ulonglong) for field in ("totalPhysical", "freePhysical", "totalPage", "freePage", "totalVirtual", "freeVirtual", "extended")]
    value = MemoryStatus()
    value.length = ctypes.sizeof(value)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(value)):
        raise OSError("Cannot verify free memory before runtime lease")
    return value.freePhysical


def peak_rss(process):
    class MemoryCounters(ctypes.Structure):
        _fields_ = [("cb", ctypes.c_ulong), ("faults", ctypes.c_ulong)] + [(field, ctypes.c_size_t) for field in ("peak", "working", "quotaPeakPaged", "quotaPaged", "quotaPeakNonPaged", "quotaNonPaged", "pageUsage", "peakPageUsage")]
    value = MemoryCounters()
    value.cb = ctypes.sizeof(value)
    if ctypes.windll.psapi.GetProcessMemoryInfo(ctypes.c_void_p(int(process._handle)), ctypes.byref(value), value.cb):
        return value.peak
    return None


def run(args):
    attempt, source = Path(args.attempt).resolve(), Path(args.candidate).resolve()
    if ROOT.resolve() not in attempt.parents:
        raise ValueError("Runtime attempt must remain below the isolated runtime-check root")
    world = attempt / "world"
    if world.exists():
        raise FileExistsError("Runtime world copy already exists; create a new attempt")
    if not (attempt / "server.jar").is_file() or not (attempt / "eula.txt").is_file():
        raise ValueError("Prepare copied server.jar and existing accepted eula.txt before runtime")
    lease_path = Path(args.lease).resolve()
    lease = json.loads(lease_path.read_text(encoding="utf-8-sig"))
    if lease.get("approvedBy") != "/root/singapore_full_coordinator" or lease.get("heavyJobSlot") != "A" or Path(lease["attemptRoot"]).resolve() != attempt:
        raise ValueError("Runtime lease does not match coordinator slot A and exact attempt")
    expires = datetime.fromisoformat(lease["expiresUtc"].replace("Z", "+00:00"))
    if expires.tzinfo is None or datetime.now(timezone.utc) >= expires:
        raise ValueError("Runtime lease expired")
    gate_path = Path(args.gate).resolve()
    gate = json.loads(gate_path.read_text(encoding="utf-8-sig"))
    if gate.get("status") != "PASS" or gate.get("kind") != "independent-joined-strip-structural-gate" or gate.get("chunkCount") != 256 or gate.get("bounds") != list(args.bounds):
        raise ValueError("Independent structural NBT gate must pass before runtime")
    writer_path = source.parent / "writer-manifest.json"
    if sha(writer_path).lower() != str(gate.get("writerManifestSha256")).lower():
        raise ValueError("Candidate writer manifest differs from independent gate binding")
    writer = json.loads(writer_path.read_text(encoding="utf-8-sig"))
    free_before = free_memory()
    if free_before < 8 * 1024 ** 3:
        raise RuntimeError("Less than 8 GiB free; runtime lease cannot start")
    before = snapshot(source)
    if before != {record["path"]: record["sha256"].lower() for record in writer["outputs"]}:
        raise ValueError("Candidate world files differ from independently accepted writer manifest")
    if "level.dat" not in before or not any(name.endswith(".mca") and "/region/" in "/" + name for name in before):
        raise ValueError("Candidate has no level.dat or region chunks")
    shutil.copytree(source, world)
    if snapshot(world) != before:
        raise RuntimeError("Runtime copy differs from candidate before Java launch")
    with socket.socket() as test_socket:
        test_socket.bind(("127.0.0.1", args.port))
    properties = {"server-ip": "127.0.0.1", "server-port": args.port, "level-name": "world", "online-mode": "true",
                  "enable-rcon": "false", "enable-query": "false", "enable-status": "false", "max-players": 1,
                  "view-distance": 2, "simulation-distance": 2, "spawn-protection": 0, "max-tick-time": 60000,
                  "sync-chunk-writes": "true", "pause-when-empty-seconds": -1, "motd": "FORK isolated runtime validation"}
    (attempt / "server.properties").write_text("".join(f"{key}={value}\n" for key, value in properties.items()), encoding="ascii")
    java = Path(args.java).resolve()
    identity = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=10).stderr.strip()
    command = [str(java), "-Xms512M", "-Xmx3072M", "-XX:ActiveProcessorCount=1", "-jar", "server.jar", "nogui"]
    min_x, min_z, max_x, max_z = args.bounds
    expected = {f"FORK_RUNTIME_CHUNK_{cx}_{cz}_OK": (cx * 16 + 8, cz * 16 + 8) for cz in range(min_z // 16, max_z // 16) for cx in range(min_x // 16, max_x // 16)}
    if len(expected) != 256:
        raise ValueError("This runtime gate requires exactly 256 chunk-aligned core chunks")
    log_path = attempt / "console.log"
    state = {"minecraft_version": "26.1.2", "candidate_path": str(source), "candidate_before": before, "copied_world_path": str(world),
             "jar_path": str(attempt / "server.jar"), "expected_jar_sha256": sha(attempt / "server.jar"), "java_identity": identity,
             "port": args.port, "max_heap_mib": 3072, "log_path": str(log_path), "expected_level_name": "world",
             "expected_chunk_markers": sorted(expected), "lease_sha256": sha(lease_path), "gate_sha256": sha(gate_path),
             "free_memory_before_bytes": free_before, "command": command, "startedUtc": datetime.now(timezone.utc).isoformat(),
             "runtimeLoadAccepted": False, "visualAccepted": False, "aiAccepted": False}
    process = subprocess.Popen(command, cwd=attempt, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, encoding="utf-8", errors="replace", bufsize=1, creationflags=subprocess.CREATE_NO_WINDOW)
    state["pid"] = process.pid
    atomic_json(attempt / "runtime-state.json", state)
    lines = queue.Queue()
    def pump():
        with log_path.open("w", encoding="utf-8") as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                lines.put(line)
    reader = threading.Thread(target=pump, daemon=True)
    reader.start()
    started = time.monotonic()
    seen, text_log = set(), []
    ready = False
    peak = 0
    def drain():
        while True:
            try:
                line = lines.get_nowait()
            except queue.Empty:
                break
            text_log.append(line)
            seen.update(marker for marker in re.findall(r"FORK_RUNTIME_CHUNK_-?\d+_-?\d+_OK", line) if marker in expected)
    def send(command_text):
        process.stdin.write(command_text + "\n")
        process.stdin.flush()
    try:
        while process.poll() is None and time.monotonic() - started < 100:
            drain()
            peak = max(peak, peak_rss(process) or 0)
            if any(re.search(r'Done \([\d.]+s\)!', line) for line in text_log):
                ready = True
                break
            time.sleep(0.2)
        if not ready:
            raise RuntimeError("Server did not reach Done within bounded startup window")
        send(f"forceload add {min_x} {min_z} {max_x - 1} {max_z - 1}")
        while process.poll() is None and time.monotonic() - started < 150 and len(seen) < len(expected):
            for marker, (x, z) in expected.items():
                if marker not in seen:
                    send(f"execute if loaded {x} 1 {z} run say {marker}")
            until = time.monotonic() + 3
            while time.monotonic() < until and process.poll() is None:
                drain()
                peak = max(peak, peak_rss(process) or 0)
                time.sleep(0.1)
        if len(seen) != len(expected):
            raise RuntimeError(f"Only {len(seen)}/{len(expected)} requested core chunks confirmed loaded")
        send("save-all flush")
        until = min(started + 160, time.monotonic() + 10)
        while time.monotonic() < until and process.poll() is None:
            drain()
            if any("Saved the game" in line for line in text_log):
                break
            time.sleep(0.1)
        send("stop")
        process.wait(timeout=max(1, 175 - (time.monotonic() - started)))
    except Exception as error:
        state["failure"] = str(error)
    finally:
        if process.poll() is None:
            try:
                send("stop")
                process.wait(timeout=max(1, min(8, 178 - (time.monotonic() - started))))
            except (OSError, subprocess.TimeoutExpired):
                process.kill()
                process.wait(timeout=2)
                state["forcedOwnedProcessCleanup"] = True
        reader.join(timeout=2)
        drain()
        state.update({"exit_code": process.returncode, "process_alive": process.poll() is None, "peak_rss_bytes": peak,
                      "loaded_chunk_marker_count": len(seen), "elapsed_java_seconds": round(time.monotonic() - started, 3),
                      "candidate_after": snapshot(source), "copied_world_after": snapshot(world), "finishedUtc": datetime.now(timezone.utc).isoformat()})
        state["process"] = {"pid": process.pid, "exit_code": process.returncode, "observed_alive": process.poll() is None,
                            "observed_at_utc": state["finishedUtc"]}
        state["candidate_unchanged"] = state["candidate_after"] == before
        atomic_json(attempt / "runtime-state.json", state)
    print(json.dumps({"state": str(attempt / "runtime-state.json"), "exitCode": process.returncode, "loadedChunks": len(seen), "failure": state.get("failure")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--attempt", required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--lease", required=True)
    parser.add_argument("--gate", required=True)
    parser.add_argument("--bounds", type=lambda value: tuple(map(int, value.split(","))), required=True)
    parser.add_argument("--port", type=int, default=25579)
    run(parser.parse_args())
