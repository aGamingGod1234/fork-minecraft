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
from grow_runtime import prepare_runtime_plan, StdinTranscript, verify_grow_runtime
from grow_sentinels import select_sentinels

BASE = Path(r"C:\Users\User\AppData\Local\FORK-Tools")
FULL = BASE / "fork-singapore-full"
JAVA = BASE / "java/jdk-25.0.4.1+1/bin/java.exe"
JAR = BASE / "minecraft-server-26.1.2/server.jar"
JAR_SHA = "cd47e7c38328f64768fd17af8fcd8b22496b40b63d4ffee81e71ae059fedcb42"
DISTRICTS = {
    "national-preview-v1": ("grow-national-preview-v1", "FORK-Singapore-National-Preview-v1", "Singapore reconstruction preview"),
    "lim-chu-kang-v1": ("grow-lim-chu-kang-v1", "FORK-Lim-Chu-Kang-1024", "Lim Chu Kang"),
    "changi-v1": ("grow-changi-v1", "FORK-Changi-1024", "Changi"),
    "cbd-east-v1": ("grow-cbd-east-v1", "FORK-CBD-East-v1", "Singapore CBD"),
    "cbd-east-v2": ("grow-cbd-east-v2", "FORK-CBD-East-v2", "Singapore CBD and adjoining east district"),
    "cbd-east-ring-v1": ("grow-cbd-east-ring-v1", "FORK-CBD-East-Ring-v1", "Singapore CBD and surrounding district"),
    "cbd-south-v1": ("grow-cbd-south-v1", "FORK-Singapore-CBD-South-1792x2048", "Singapore CBD and adjoining south district"),
}


def write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def peak_working_set_bytes(process_handle):
    """Read the actual process lifetime RSS peak, including after process exit."""
    class Counters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("pageFaults", wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in
            ("peakWorking", "working", "peakPaged", "paged", "peakNonpaged", "nonpaged", "pagefile", "peakPagefile")]
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    psapi.GetProcessMemoryInfo.restype = wintypes.BOOL
    counters = Counters()
    counters.cb = ctypes.sizeof(counters)
    if not psapi.GetProcessMemoryInfo(process_handle, ctypes.byref(counters), counters.cb):
        raise ctypes.WinError(ctypes.get_last_error())
    return counters.peakWorking


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--runs", action="append", required=True)
    parser.add_argument("--district", choices=sorted(DISTRICTS), default="lim-chu-kang-v1")
    args = parser.parse_args()
    candidate = Path(args.candidate).resolve()
    snapshot_name, package_name, location = DISTRICTS[args.district]
    expected = FULL / "merged" / snapshot_name / "world"
    attempt_root = FULL / "runtime-check" / args.district
    if candidate != expected.resolve() or attempt_root.exists():
        raise ValueError("requires exact approved district candidate and brand-new runtime attempt")
    manifest = json.loads((candidate.parent / "grow-manifest.json").read_text(encoding="utf-8-sig"))
    if (manifest.get("kind") != "exact-core-grown-world" or manifest.get("assemblyAccepted") is not True
            or manifest.get("verification", {}).get("status") != "PASS"
            or package.snapshot_tree(candidate) != manifest["verification"]["hash_manifest"]):
        raise ValueError("runtime candidate lacks its unchanged accepted crop receipt")
    if package._file_record(JAR)["sha256"] != JAR_SHA:
        raise ValueError("Minecraft server JAR changed")
    bounds = manifest["extent"]
    sentinels = select_sentinels(candidate, bounds, args.runs)
    plan = prepare_runtime_plan(candidate, bounds, sentinels, attempt_root=attempt_root)
    attempt_root.mkdir(parents=True)
    write(attempt_root / "runtime-plan.json", plan)
    definitions = attempt_root / "sentinel-definitions.json"
    write(definitions, plan["sentinels"])
    shutil.copytree(candidate, attempt_root / "world")
    if package.snapshot_tree(attempt_root / "world") != plan["candidate_before"]:
        raise ValueError("runtime copy differs from immutable candidate")
    shutil.copyfile(JAR, attempt_root / "server.jar")
    shutil.copyfile(BASE / "fork-build-20260913/world-validation-1216/eula.txt", attempt_root / "eula.txt")
    (attempt_root / "server.properties").write_text("\n".join([
        "server-ip=127.0.0.1", "server-port=25579", "level-name=world", "online-mode=true",
        "enable-rcon=false", "enable-query=false", "enable-status=false", "max-players=1",
        "view-distance=2", "simulation-distance=2", "spawn-protection=0", "sync-chunk-writes=true",
        "max-tick-time=60000", "motd=FORK district isolated runtime check", ""]), encoding="ascii")
    spec_module = importlib.util.spec_from_file_location("fork_growth_queue", BASE / "fork-minecraft-worktrees/full-singapore-queue/tools/singapore-full/queue/queue.py")
    queue = importlib.util.module_from_spec(spec_module)
    sys.modules[spec_module.name] = queue
    spec_module.loader.exec_module(queue)
    before = queue.resource_snapshot(FULL)
    if before["freeRam"] < 8 * 1024 ** 3:
        raise ValueError("runtime requires 8GiB free RAM floor")
    java_identity = subprocess.run([str(JAVA), "-version"], capture_output=True, text=True, timeout=15).stderr.strip()
    commands = plan["forceload_commands"] + plan["chunk_commands"] + plan["block_commands"] + plan["finish_commands"]
    transcript_path = attempt_root / "stdin-transcript.txt"
    transcript = StdinTranscript(transcript_path, commands)
    console_path = attempt_root / "console.log"
    command = [str(JAVA), "-Xms256M", "-Xmx3072M", "-XX:ActiveProcessorCount=1", "-jar", "server.jar", "nogui"]
    state = {"status": "STARTING", "command": command, "startedUtc": datetime.now(timezone.utc).isoformat(),
             "minimumFreeMemoryBytes": before["freeRam"], "sourceHash": plan["candidate_before"]["sha256"],
             "javaPeakWorkingSetBytes": None, "javaPeakWorkingSetSamples": 0, "javaPeakWorkingSetErrors": 0}
    process = None
    guard = queue.ChildGuard(3)
    started = time.monotonic()
    def sample_java_peak():
        if process is None:
            return
        try:
            observed = peak_working_set_bytes(int(process._handle))
            state["javaPeakWorkingSetBytes"] = max(state["javaPeakWorkingSetBytes"] or 0, observed)
            state["javaPeakWorkingSetSamples"] += 1
        except OSError as error:
            # Telemetry failure stays explicit; it must not invent a zero peak.
            state["javaPeakWorkingSetErrors"] += 1
            state["javaPeakWorkingSetLastError"] = str(error)
    def log():
        return console_path.read_text(encoding="utf-8", errors="replace") if console_path.exists() else ""
    def wait_for(predicate, timeout):
        until = min(started + 180, time.monotonic() + timeout)
        sample_java_peak()
        while not predicate(log()):
            sample_java_peak()
            if process.poll() is not None:
                raise RuntimeError("Minecraft exited before expected runtime marker")
            resources = queue.resource_snapshot(FULL)
            state["minimumFreeMemoryBytes"] = min(state["minimumFreeMemoryBytes"], resources["freeRam"])
            if resources["freeRam"] < 8 * 1024 ** 3 or time.monotonic() >= until:
                raise TimeoutError("runtime marker deadline or memory floor")
            time.sleep(.2)
        sample_java_peak()
    try:
        with console_path.open("wb") as console:
            process = subprocess.Popen(command, cwd=attempt_root, stdin=subprocess.PIPE,
                stdout=console, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW | subprocess.BELOW_NORMAL_PRIORITY_CLASS)
            guard.assign(process)
            kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel.SetProcessAffinityMask.argtypes = [wintypes.HANDLE, ctypes.c_size_t]
            if not kernel.SetProcessAffinityMask(int(process._handle), 256):
                raise OSError("Could not set runtime CPU offset8")
            state["owner"] = queue.identity(process.pid)
            state["status"] = "RUNNING"
            sample_java_peak()
            write(attempt_root / "runtime-state.json", state)
            print(json.dumps(state), flush=True)
            wait_for(lambda text: ")! For help, type" in text and "Done (" in text, 60)
            for command_text in plan["forceload_commands"]:
                transcript.send(process.stdin, command_text)
            time.sleep(1)
            for attempt in range(20):
                for command_text in plan["chunk_commands"]:
                    transcript.send(process.stdin, command_text)
                time.sleep(.25)
                sample_java_peak()
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
        sample_java_peak()
        guard.close()
        transcript.close()
        state["elapsedSeconds"] = time.monotonic() - started
        state["exitCode"] = process.returncode if process else None
        state["status"] = "PROCESS_EXITED" if process is not None and process.returncode == 0 else "PROCESS_FAILED"
        state["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        state["processAlive"] = process is not None and process.poll() is None
        write(attempt_root / "runtime-state.json", state)
    runtime_spec = {"copied_world_path": str(attempt_root / "world"), "sentinel_definitions_path": str(definitions),
        "log_path": str(console_path), "process": {"pid": process.pid, "exit_code": process.returncode,
            "observed_alive": process.poll() is None, "observed_at_utc": state["finishedUtc"]},
        "max_heap_mib": 3072, "active_processor_count": 1, "cpu_affinity_mask": 256,
        "free_memory_before_bytes": before["freeRam"], "minimum_free_memory_bytes": state["minimumFreeMemoryBytes"],
        "java_peak_working_set_bytes": state["javaPeakWorkingSetBytes"],
        "jar_path": str(JAR), "expected_jar_sha256": JAR_SHA, "minecraft_version": "26.1.2", "java_identity": java_identity, "port": 25579}
    write(attempt_root / "runtime-spec.json", runtime_spec)
    receipt = verify_grow_runtime(runtime_spec, plan, transcript_path)
    receipt["javaPeakWorkingSetBytes"] = state["javaPeakWorkingSetBytes"]
    receipt["javaMemoryTelemetry"] = {"metric": "GetProcessMemoryInfo.PeakWorkingSetSize",
        "samples": state["javaPeakWorkingSetSamples"], "errors": state["javaPeakWorkingSetErrors"]}
    receipt["runtimeState"] = {"path": str(attempt_root / "runtime-state.json"),
        **package._file_record(attempt_root / "runtime-state.json")}
    write(attempt_root / "runtime-receipt.json", receipt)
    if not receipt["runtimeLoadAccepted"]:
        raise RuntimeError("Runtime gate failed: " + json.dumps(receipt["issues"]))
    target = candidate.parent / (package_name + ".zip")
    width, depth = bounds[2] - bounds[0], bounds[3] - bounds[1]
    readme = (f"FORK - {location}\n\nMinecraft Java 26.1.2 save. Extract the {package_name} folder into your saves folder.\n\n"
        f"This mapped district spans {width}m x {depth}m at one block per horizontal metre, with mapped building and road geometry. "
        "Ground is flat provisional Y0; building heights include declared estimates and quarantined omissions. "
        "It is not the whole of Singapore, an exact visual replica, or an AI-agent gameplay build.\n\n"
        f"All {manifest['expectedChunks']} owned chunks passed independent structural/source validation. "
        "The isolated Minecraft runtime checked representative building, road, terrain and safe-spawn blocks; "
        "it did not load every chunk. Client visual review is pending.\n")
    with zipfile.ZipFile(target, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(candidate.rglob("*")):
            if path.is_file():
                archive.write(path, package_name + "/" + path.relative_to(candidate).as_posix())
        archive.writestr("README.txt", readme)
    output = {"package": str(target), **package._file_record(target), "runtimeReceipt": str(attempt_root / "runtime-receipt.json"),
        "runtimeReceiptSha256": package._file_record(attempt_root / "runtime-receipt.json")["sha256"],
        "world": str(candidate), "worldHash": package.snapshot_tree(candidate)["sha256"], "runtimeLoadAccepted": True}
    write(candidate.parent / "delivery.json", output)
    print(json.dumps(output), flush=True)


if __name__ == "__main__":
    main()
