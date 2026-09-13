"""Run a bounded representative MC26 check on a new private copy, then package."""
import argparse
import ctypes
from ctypes import wintypes
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import zipfile

import package
from grow_runtime import ATTEMPT_ROOT, prepare_runtime_plan, StdinTranscript, verify_grow_runtime
from grow_sentinels import select_sentinels

BASE = Path(r"C:\Users\User\AppData\Local\FORK-Tools")
FULL = BASE / "fork-singapore-full"
JAVA = BASE / "java/jdk-25.0.4.1+1/bin/java.exe"
JAR = BASE / "minecraft-server-26.1.2/server.jar"
JAR_SHA = "cd47e7c38328f64768fd17af8fcd8b22496b40b63d4ffee81e71ae059fedcb42"


def write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--runs", action="append", required=True)
    args = parser.parse_args()
    candidate = Path(args.candidate).resolve()
    expected = FULL / "merged/grow-lim-chu-kang-v1/world"
    if candidate != expected.resolve() or ATTEMPT_ROOT.exists():
        raise ValueError("requires exact immutable rural candidate and brand-new runtime attempt")
    if package._file_record(JAR)["sha256"] != JAR_SHA:
        raise ValueError("Minecraft server JAR changed")
    bounds = [13312, 13312, 14336, 14336]
    sentinels = select_sentinels(candidate, bounds, args.runs)
    plan = prepare_runtime_plan(candidate, bounds, sentinels)
    ATTEMPT_ROOT.mkdir(parents=True)
    write(ATTEMPT_ROOT / "runtime-plan.json", plan)
    definitions = ATTEMPT_ROOT / "sentinel-definitions.json"
    write(definitions, plan["sentinels"])
    shutil.copytree(candidate, ATTEMPT_ROOT / "world")
    if package.snapshot_tree(ATTEMPT_ROOT / "world") != plan["candidate_before"]:
        raise ValueError("runtime copy differs from immutable candidate")
    shutil.copyfile(JAR, ATTEMPT_ROOT / "server.jar")
    shutil.copyfile(BASE / "fork-build-20260913/world-validation-1216/eula.txt", ATTEMPT_ROOT / "eula.txt")
    (ATTEMPT_ROOT / "server.properties").write_text("\n".join([
        "server-ip=127.0.0.1", "server-port=25579", "level-name=world", "online-mode=true",
        "enable-rcon=false", "enable-query=false", "enable-status=false", "max-players=1",
        "view-distance=2", "simulation-distance=2", "spawn-protection=0", "sync-chunk-writes=true",
        "max-tick-time=60000", "motd=FORK Lim Chu Kang isolated runtime check", ""]), encoding="ascii")
    spec_module = importlib.util.spec_from_file_location("fork_growth_queue", BASE / "fork-minecraft-worktrees/full-singapore-queue/tools/singapore-full/queue/queue.py")
    queue = importlib.util.module_from_spec(spec_module)
    sys.modules[spec_module.name] = queue
    spec_module.loader.exec_module(queue)
    before = queue.resource_snapshot(FULL)
    if before["freeRam"] < 8 * 1024 ** 3:
        raise ValueError("runtime requires 8GiB free RAM floor")
    java_identity = subprocess.run([str(JAVA), "-version"], capture_output=True, text=True, timeout=15).stderr.strip()
    commands = plan["forceload_commands"] + plan["chunk_commands"] + plan["block_commands"] + plan["finish_commands"]
    transcript_path = ATTEMPT_ROOT / "stdin-transcript.txt"
    transcript = StdinTranscript(transcript_path, commands)
    console_path = ATTEMPT_ROOT / "console.log"
    command = [str(JAVA), "-Xms256M", "-Xmx3072M", "-XX:ActiveProcessorCount=1", "-jar", "server.jar", "nogui"]
    state = {"status": "STARTING", "command": command, "startedUtc": datetime.now(timezone.utc).isoformat(),
             "minimumFreeMemoryBytes": before["freeRam"], "sourceHash": plan["candidate_before"]["sha256"]}
    process = None
    guard = queue.ChildGuard(3)
    started = time.monotonic()
    def log():
        return console_path.read_text(encoding="utf-8", errors="replace") if console_path.exists() else ""
    def wait_for(predicate, timeout):
        until = min(started + 180, time.monotonic() + timeout)
        while not predicate(log()):
            if process.poll() is not None:
                raise RuntimeError("Minecraft exited before expected runtime marker")
            resources = queue.resource_snapshot(FULL)
            state["minimumFreeMemoryBytes"] = min(state["minimumFreeMemoryBytes"], resources["freeRam"])
            if resources["freeRam"] < 8 * 1024 ** 3 or time.monotonic() >= until:
                raise TimeoutError("runtime marker deadline or memory floor")
            time.sleep(.2)
    try:
        with console_path.open("wb") as console:
            process = subprocess.Popen(command, cwd=ATTEMPT_ROOT, stdin=subprocess.PIPE,
                stdout=console, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
            guard.assign(process)
            kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel.SetProcessAffinityMask.argtypes = [wintypes.HANDLE, ctypes.c_size_t]
            if not kernel.SetProcessAffinityMask(int(process._handle), 256):
                raise OSError("Could not set runtime CPU offset8")
            state["owner"] = queue.identity(process.pid)
            state["status"] = "RUNNING"
            write(ATTEMPT_ROOT / "runtime-state.json", state)
            print(json.dumps(state), flush=True)
            wait_for(lambda text: ")! For help, type" in text and "Done (" in text, 60)
            for command_text in plan["forceload_commands"]:
                transcript.send(process.stdin, command_text)
            time.sleep(1)
            for attempt in range(20):
                for command_text in plan["chunk_commands"]:
                    transcript.send(process.stdin, command_text)
                time.sleep(.25)
                if all(marker in log() for marker in plan["expected_chunk_markers"]):
                    break
            wait_for(lambda text: all(marker in text for marker in plan["expected_chunk_markers"]), 5)
            for command_text in plan["block_commands"]:
                transcript.send(process.stdin, command_text)
            wait_for(lambda text: all(row["marker"] in text for row in plan["sentinels"]), 10)
            transcript.send(process.stdin, "save-all flush")
            wait_for(lambda text: "Saved the game" in text, 20)
            transcript.send(process.stdin, "stop")
            wait_for(lambda _: process.poll() is not None, max(1, 180 - (time.monotonic() - started)))
    except BaseException as error:
        state["error"] = str(error)
        if process is not None and process.poll() is None:
            try:
                transcript.send(process.stdin, "stop")
                process.wait(timeout=15)
            except Exception:
                guard.terminate(process)
                process.wait(timeout=10)
    finally:
        if process is not None and process.poll() is None:
            guard.terminate(process)
            process.wait(timeout=10)
        guard.close()
        transcript.close()
        state["elapsedSeconds"] = time.monotonic() - started
        state["exitCode"] = process.returncode if process else None
        state["status"] = "PROCESS_EXITED" if process is not None and process.returncode == 0 else "PROCESS_FAILED"
        state["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        state["processAlive"] = process is not None and process.poll() is None
        write(ATTEMPT_ROOT / "runtime-state.json", state)
    runtime_spec = {"copied_world_path": str(ATTEMPT_ROOT / "world"), "sentinel_definitions_path": str(definitions),
        "log_path": str(console_path), "process": {"pid": process.pid, "exit_code": process.returncode,
            "observed_alive": process.poll() is None, "observed_at_utc": state["finishedUtc"]},
        "max_heap_mib": 3072, "active_processor_count": 1, "cpu_affinity_mask": 256,
        "free_memory_before_bytes": before["freeRam"], "minimum_free_memory_bytes": state["minimumFreeMemoryBytes"],
        "jar_path": str(JAR), "expected_jar_sha256": JAR_SHA, "minecraft_version": "26.1.2", "java_identity": java_identity, "port": 25579}
    write(ATTEMPT_ROOT / "runtime-spec.json", runtime_spec)
    receipt = verify_grow_runtime(runtime_spec, plan, transcript_path)
    write(ATTEMPT_ROOT / "runtime-receipt.json", receipt)
    if not receipt["runtimeLoadAccepted"]:
        raise RuntimeError("Runtime gate failed: " + json.dumps(receipt["issues"]))
    target = candidate.parent / "FORK-Lim-Chu-Kang-1024.zip"
    readme = "FORK - Lim Chu Kang\n\nMinecraft Java 26.1.2 save. Extract the FORK-Lim-Chu-Kang folder into your saves folder.\n\nThis is a 1024m x 1024m Lim Chu Kang map core at one block per horizontal metre, with mapped building and road geometry. Ground is flat provisional Y0; building heights include declared estimates and quarantined omissions. It is not the whole of Singapore, an exact visual replica, or an AI-agent gameplay build.\n\nAll 4096 owned chunks passed independent structural/source validation. The isolated Minecraft runtime checked representative building, road, terrain and safe-spawn blocks; it did not load every chunk. Client visual review is pending.\n"
    with zipfile.ZipFile(target, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(candidate.rglob("*")):
            if path.is_file():
                archive.write(path, "FORK-Lim-Chu-Kang/" + path.relative_to(candidate).as_posix())
        archive.writestr("README.txt", readme)
    output = {"package": str(target), **package._file_record(target), "runtimeReceipt": str(ATTEMPT_ROOT / "runtime-receipt.json"),
        "runtimeReceiptSha256": package._file_record(ATTEMPT_ROOT / "runtime-receipt.json")["sha256"],
        "world": str(candidate), "worldHash": package.snapshot_tree(candidate)["sha256"], "runtimeLoadAccepted": True}
    write(candidate.parent / "delivery.json", output)
    print(json.dumps(output), flush=True)


if __name__ == "__main__":
    main()
