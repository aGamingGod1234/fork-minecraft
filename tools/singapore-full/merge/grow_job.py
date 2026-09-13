"""Bound one approved Desktop growth subprocess; retain actual process evidence."""
import argparse
import ctypes
from ctypes import wintypes
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import time

BASE = Path(r"C:\Users\User\AppData\Local\FORK-Tools")
FULL = BASE / "fork-singapore-full"
QUEUE_CODE = BASE / "fork-minecraft-worktrees/full-singapore-queue/tools/singapore-full/queue/queue.py"


def write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", required=True)
    args = parser.parse_args()
    request_path = Path(args.request).resolve()
    request = json.loads(request_path.read_text(encoding="utf-8-sig"))
    root = Path(request["evidenceRoot"]).resolve()
    if FULL.resolve() not in root.parents or root.name != "process-evidence" or root.exists():
        raise ValueError("new private process-evidence root required")
    if request["memoryGiB"] not in (1, 3) or request["cpuOffset"] != 8 or not 0 < request["timeoutSeconds"] <= 300:
        raise ValueError("request exceeds approved growth job resource limits")
    spec = importlib.util.spec_from_file_location("fork_queue_guard", QUEUE_CODE)
    queue = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = queue
    spec.loader.exec_module(queue)
    before = queue.resource_snapshot(FULL)
    if before["freeRam"] < 8 * 1024 ** 3 or before["freeDisk"] < 100 * 1024 ** 3:
        raise ValueError("resource floor not available")
    root.mkdir(parents=True)
    guard = queue.ChildGuard(request["memoryGiB"])
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.SetProcessAffinityMask.argtypes = [wintypes.HANDLE, ctypes.c_size_t]
    kernel.SetProcessAffinityMask.restype = wintypes.BOOL
    class Counters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("pageFaults", wintypes.DWORD)] + [(name, ctypes.c_size_t) for name in
            ("peakWorking", "working", "peakPaged", "paged", "peakNonpaged", "nonpaged", "pagefile", "peakPagefile")]
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    state = {"requestPath": str(request_path), "approvedBy": request["approvedBy"],
             "resourceBefore": before, "memoryGiB": request["memoryGiB"], "cpuAffinityMask": 256,
             "command": request["command"], "startedUtc": datetime.now(timezone.utc).isoformat(),
             "status": "STARTING", "peakRssBytes": 0}
    process = None
    started = time.monotonic()
    try:
        with (root / "stdout.log").open("wb") as stdout, (root / "stderr.log").open("wb") as stderr:
            process = subprocess.Popen(request["command"], stdout=stdout, stderr=stderr,
                creationflags=subprocess.CREATE_NO_WINDOW | subprocess.BELOW_NORMAL_PRIORITY_CLASS, cwd=request.get("cwd"))
            guard.assign(process)
            if not kernel.SetProcessAffinityMask(int(process._handle), 256):
                raise OSError(ctypes.get_last_error(), "Cannot assign approved CPU offset8")
            state["owner"] = queue.identity(process.pid)
            state["status"] = "RUNNING"
            write(root / "state.json", state)
            print(json.dumps(state), flush=True)
            while process.poll() is None:
                counters = Counters()
                counters.cb = ctypes.sizeof(counters)
                if psapi.GetProcessMemoryInfo(int(process._handle), ctypes.byref(counters), counters.cb):
                    state["peakRssBytes"] = max(state["peakRssBytes"], counters.peakWorking)
                available = queue.resource_snapshot(FULL)
                if (available["freeRam"] < 8 * 1024 ** 3 or available["freeDisk"] < 100 * 1024 ** 3
                        or time.monotonic() - started > request["timeoutSeconds"]):
                    raise TimeoutError("owned growth job crossed time/resource floor")
                time.sleep(.2)
            state["exitCode"] = process.returncode
            state["status"] = "PASS" if process.returncode == 0 else "FAIL"
    except BaseException as error:
        state["status"] = "FAIL"
        state["error"] = str(error)
        if process is not None and process.poll() is None:
            guard.terminate(process)
            process.wait(timeout=15)
        raise
    finally:
        if process is not None and process.poll() is None:
            guard.terminate(process)
            process.wait(timeout=15)
        guard.close()
        state["elapsedSeconds"] = time.monotonic() - started
        state["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        state["processAlive"] = process is not None and process.poll() is None
        state["resourceAfter"] = queue.resource_snapshot(FULL)
        write(root / "state.json", state)
        print(json.dumps(state), flush=True)
    if state["status"] != "PASS":
        raise SystemExit(state.get("exitCode") or 1)


if __name__ == "__main__":
    main()
