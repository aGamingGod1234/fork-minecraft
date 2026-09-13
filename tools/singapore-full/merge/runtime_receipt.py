"""Check retained evidence from a separately launched server; never launch or copy."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
from typing import Any

import package

RUNTIME_ROOT = package.OUTPUT_ROOT.parent / "runtime-check"
ERROR_PATTERN = re.compile(
    r"\b(?:ERROR|FATAL)\b|(?:NBT|datafix\w*).*(?:error|exception|invalid|fail|corrupt)|"
    r"(?:error|exception|invalid|fail|corrupt).*\bNBT\b|"
    r"(?:failed|unable)\s+to\s+load|(?:fail\w*|exception).*deserializ\w*.*chunk|"
    r"chunk.*(?:deserializ\w*|datafix\w*).*(?:fail|error|exception)", re.IGNORECASE)


def _file_hashes(snapshot: dict[str, Any]) -> dict[str, str]:
    files = snapshot.get("files", snapshot)
    if not isinstance(files, dict) or not files:
        raise ValueError("Nonempty baseline file SHA map required")
    result = {}
    for name, value in files.items():
        sha = value.get("sha256") if isinstance(value, dict) else value
        if not isinstance(name, str) or not isinstance(sha, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", sha):
            raise ValueError("Malformed baseline file SHA map")
        result[name] = sha.lower()
    return result


def check_runtime_receipt(spec: dict[str, Any]) -> dict[str, Any]:
    """Validate actual logs, caller-retained process observation and file hashes.

    Required spec keys: candidate_path, candidate_before (snapshot_tree result),
    copied_world_path, log_path, expected_level_name, jar_path, expected_jar_sha256,
    java_identity (nonempty string), port, max_heap_mib, minecraft_version,
    process={pid, exit_code, observed_alive, observed_at_utc}, expected_chunk_markers.
    Optional input_snapshots=[{path,files:{relative_path:sha256}}] checks source tiles too.
    Process observation is supplied by the launcher; this function runs no process.
    """
    issues: list[str] = []
    report: dict[str, Any] = {
        "schema": "fork.singapore.runtime-load.v1", "checked_at_utc": datetime.now(timezone.utc).isoformat(),
        "runtimeLoadAccepted": False, "visualAccepted": False, "aiAccepted": False,
        "scope": "Headless server startup/shutdown only; appearance and AI remain untested",
        "minecraft_version": spec.get("minecraft_version"), "java_identity": spec.get("java_identity"),
        "port": spec.get("port"), "max_heap_mib": spec.get("max_heap_mib"),
        "process": spec.get("process"), "issues": issues,
    }
    try:
        source = package._resolve(spec["candidate_path"])
        copied = package._resolve(spec["copied_world_path"])
        runtime_root = package._resolve(RUNTIME_ROOT)
        if copied == runtime_root or not package._inside(copied, runtime_root):
            issues.append("Runtime world must be a private copy below runtime-check")
        if package._inside(copied, source) or package._inside(source, copied):
            issues.append("Runtime copy and immutable candidate overlap")
        report["candidate_path"] = str(source)
        report["copied_world_path"] = str(copied)
        baseline = spec["candidate_before"]
        current = package.snapshot_tree(source)
        report["candidate_before"] = baseline
        report["candidate_after"] = current
        report["candidateUnchanged"] = _file_hashes(baseline) == _file_hashes(current)
        if not report["candidateUnchanged"]:
            issues.append("Candidate input changed or baseline does not match")
        report["input_snapshots"] = []
        for item in spec.get("input_snapshots", []):
            tile_path = package._resolve(item["path"])
            tile_after = package.snapshot_tree(tile_path)
            same = _file_hashes(item["files"]) == _file_hashes(tile_after)
            report["input_snapshots"].append({"path": str(tile_path), "unchanged": same,
                                               "before": item["files"], "after": tile_after})
            if not same:
                issues.append("Source tile changed: " + str(tile_path))

        log_path = package._resolve(spec["log_path"])
        log_record = package._file_record(log_path)
        log = log_path.read_text(encoding="utf-8-sig", errors="strict")
        if package._file_record(log_path) != log_record:
            issues.append("Server log changed during inspection")
        report["log"] = {"path": str(log_path), **log_record}
        level = spec["expected_level_name"]
        if not isinstance(level, str) or not level:
            issues.append("Explicit target level name required")
            level = "__INVALID_EMPTY_LEVEL__"
        loaded = re.search(r'Preparing level ["\']' + re.escape(level) + r'["\']', log)
        done = re.search(r'Done \([0-9.]+s\)!', log)
        stopping = re.search(r'\bStopping server\b', log)
        # Explicit save-all can occur before stop. Only the shutdown save proves
        # that the server finished its final writes before the process ended.
        saving = re.compile(r'\bSaving chunks\b').search(log, stopping.end()) if stopping else None
        saved = re.compile(r'\bAll dimensions are saved\b').search(log, saving.end()) if saving else None
        startup_version = bool(re.search(r'\bStarting minecraft server version 26\.1\.2\b', log, re.I))
        markers = {"target_level": bool(loaded), "done": bool(done), "stopping_server": bool(stopping),
                   "saving_chunks": bool(saving), "all_dimensions_saved": bool(saved),
                   "minecraft_26_1_2": startup_version}
        report["log_markers"] = markers
        if not all(markers.values()):
            issues.append("Missing server evidence: " + ", ".join(key for key, found in markers.items() if not found))
        if all((loaded, done, stopping, saving, saved)) and not (
                loaded.start() < done.start() < stopping.start() <= saving.start() < saved.start()):
            issues.append("Startup and clean shutdown markers are out of order")
        expected_chunks = spec.get("expected_chunk_markers", [])
        if (not isinstance(expected_chunks, list) or len(expected_chunks) != 256
                or any(not isinstance(marker, str) or not re.fullmatch(r"FORK_RUNTIME_CHUNK_-?\d+_-?\d+_OK", marker)
                       for marker in expected_chunks) or len(set(expected_chunks)) != 256):
            issues.append("Exactly 256 unique expected chunk-load markers are required")
            expected_chunks = []
        found_chunks = []
        for marker in expected_chunks:
            found = re.search(r'\[Server\]\s+' + re.escape(marker) + r'\s*$', log, re.MULTILINE)
            if found and done and stopping and done.end() < found.start() < stopping.start():
                found_chunks.append(marker)
        report["chunk_load_gate"] = {"expected": expected_chunks, "found": found_chunks,
                                      "missing": sorted(set(expected_chunks) - set(found_chunks))}
        if len(found_chunks) != 256:
            issues.append(f"Only {len(found_chunks)}/256 expected chunks have actual load confirmation")
        errors = [{"line": index + 1, "text": line[:1000]} for index, line in enumerate(log.splitlines())
                  if ERROR_PATTERN.search(line)]
        report["log_errors"] = errors[:30]
        if errors:
            issues.append(f"Server log contains {len(errors)} error/failed-load markers")

        process = spec["process"]
        pid = process.get("pid")
        exit_code = process.get("exit_code")
        alive = process.get("observed_alive")
        observed = process.get("observed_at_utc")
        valid_pid = type(pid) is int and pid > 0
        known_exit_zero = type(exit_code) is int and exit_code == 0
        documented_dead_stop = alive is False and bool(observed) and all(markers.values())
        if not valid_pid or alive is True or not (known_exit_zero or documented_dead_stop):
            issues.append("No proven zero exit or observed dead PID after documented clean stop")
        if exit_code is not None and not known_exit_zero:
            issues.append("Known nonzero process exit")
        if not isinstance(observed, str) or not observed:
            issues.append("Missing timestamped process observation")
        else:
            try:
                parsed = datetime.fromisoformat(observed.replace("Z", "+00:00"))
                if parsed.utcoffset() is None:
                    issues.append("Process observation must specify a timezone")
            except ValueError:
                issues.append("Invalid process observation timestamp")

        jar = package._resolve(spec["jar_path"])
        jar_record = package._file_record(jar)
        report["jar"] = {"path": str(jar), **jar_record}
        if jar_record["sha256"] != str(spec["expected_jar_sha256"]).lower():
            issues.append("Runtime JAR does not match approved hash")
        if spec.get("minecraft_version") != "26.1.2":
            issues.append("Runtime version must be Minecraft 26.1.2")
        if not isinstance(spec.get("java_identity"), str) or not spec["java_identity"].strip():
            issues.append("Missing retained Java identity")
        if type(spec.get("port")) is not int or not 1 <= spec["port"] <= 65535:
            issues.append("Invalid private server port")
        if type(spec.get("max_heap_mib")) is not int or spec["max_heap_mib"] != 3072:
            issues.append("Runtime check must retain the 3 GiB heap cap")
        if alive is not True:
            report["copied_world_after"] = package.snapshot_tree(copied)
            report["copied_world_file_count"] = len(report["copied_world_after"]["files"])
            report["copied_world_bytes"] = sum(v["bytes"] for v in report["copied_world_after"]["files"].values())
            if not report["copied_world_after"]["files"]:
                issues.append("Runtime copy is empty")
        report["runtimeLoadAccepted"] = not issues
    except (KeyError, TypeError, ValueError, OSError) as exc:
        issues.append("Incomplete or invalid runtime evidence: " + str(exc))
    return report


def write_receipt(spec: dict[str, Any], receipt_path: str | Path) -> dict[str, Any]:
    """Write only a receipt, outside both worlds; never change source/runtime data."""
    path = package._resolve(receipt_path)
    for key in ("candidate_path", "copied_world_path"):
        if package._inside(path, package._resolve(spec[key])):
            raise package.GateError("Receipt must be outside source and copied worlds")
    report = check_runtime_receipt(spec)
    package._atomic_write(path, report)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("spec")
    parser.add_argument("--receipt")
    args = parser.parse_args()
    spec = json.loads(Path(args.spec).read_text(encoding="utf-8-sig"))
    result = write_receipt(spec, args.receipt) if args.receipt else check_runtime_receipt(spec)
    print(json.dumps(result, indent=2))
    if not result["runtimeLoadAccepted"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
