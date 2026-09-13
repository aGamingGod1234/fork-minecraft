"""Independent <=4-chunk synthetic comparison against a frozen Git writer.

CLI: python test_streaming_equivalence.py --repository REPO --candidate-module
     PATH/overlay.py --level-template PATH/level.dat [--baseline-ref 04dacfb]

No real-area generation or slot-sized benchmark is performed. Each writer runs
in a fresh process. Evidence and tiny synthetic worlds are retained, never deleted.
"""
from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import tracemalloc
import uuid

PROJECT = Path(r"C:\Users\User\AppData\Local\FORK-Tools\fork-singapore-full")
EVIDENCE_ROOT = PROJECT / "merged"
BOUNDS = (-16, -16, 16, 16)
RELATIVE_MERGE = "tools/singapore-full/merge/"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def file_sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def nbt_value(tag):
    if tag.type_id == 10:
        value = {name: nbt_value(child) for name, child in sorted(tag.value.items())}
    elif tag.type_id == 9:
        value = [tag.value.element_type, [nbt_value(child) for child in tag.value.items]]
    elif isinstance(tag.value, (bytes, bytearray)):
        value = list(tag.value)
    else:
        value = tag.value
    return [tag.type_id, value]


def windows_peak_working_set():
    if os.name != "nt":
        return {"bytes": None, "source": "Windows API unavailable"}

    class Counters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in ("PeakWorkingSetSize", "WorkingSetSize",
             "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
             "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    psapi.GetProcessMemoryInfo.restype = wintypes.BOOL
    counters = Counters()
    counters.cb = ctypes.sizeof(counters)
    if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), ctypes.byref(counters), counters.cb):
        raise ctypes.WinError(ctypes.get_last_error())
    return {"bytes": counters.PeakWorkingSetSize, "source": "GetProcessMemoryInfo.PeakWorkingSetSize"}


def load_writer(path):
    sys.path.insert(0, str(Path(path).parent))
    spec = importlib.util.spec_from_file_location("isolated_overlay_writer", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def state_palette(section):
    block_states = section.value["block_states"].value
    palette = []
    for entry in block_states["palette"].value.items:
        name = entry.value["Name"].value
        props = entry.value.get("Properties")
        palette.append([name, {} if props is None else {k: v.value for k, v in sorted(props.value.items())}])
    return block_states, palette


def index_at(states, palette, index):
    if len(palette) == 1:
        return 0
    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    word = states["data"].value[index // per_long] & ((1 << 64) - 1)
    return (word >> ((index % per_long) * bits)) & ((1 << bits) - 1)


def inspect_world(world, writer):
    """Reopen bytes independently; digest decoded states, not palette numbering."""
    anvil = sys.modules["anvil"]
    semantic = hashlib.sha256()
    region_hashes, chunk_positions, observed = {}, [], {}
    sentinels = {(-2, 2, -2), (-2, 4, -2), (2, 2, 2), (2, 6, 2)}
    for path in sorted(Path(world).rglob("r.*.*.mca")):
        region_hashes[path.relative_to(world).as_posix()] = file_sha(path)
        for (cx, cz), doc in sorted(anvil.read_region(path).items()):
            chunk_positions.append([cx, cz])
            root = doc.root.value
            semantic.update(canonical([cx, cz, {key: nbt_value(value) for key, value in sorted(root.items()) if key != "sections"}]))
            for section in sorted(root["sections"].value.items, key=lambda value: value.value["Y"].value):
                sy = section.value["Y"].value
                semantic.update(canonical([sy, nbt_value(section.value["biomes"])]))
                states, palette = state_palette(section)
                hashes = [hashlib.sha256(canonical(state)).digest() for state in palette]
                for index in range(4096):
                    semantic.update(hashes[index_at(states, palette, index)])
                for gx, gy, gz in sentinels:
                    if (gx // 16, gz // 16, gy // 16) == (cx, cz, sy):
                        index = ((gy % 16) * 16 + gz % 16) * 16 + gx % 16
                        observed[f"{gx},{gy},{gz}"] = palette[index_at(states, palette, index)]
    level = anvil.read_level_dat(Path(world) / "level.dat")
    settings_path = Path(world) / "data/minecraft/world_gen_settings.dat"
    settings = anvil.read_level_dat(settings_path)
    data = level.root.value["Data"].value
    enabled = [tag.value for tag in data["DataPacks"].value["Enabled"].value.items]
    modern = {"external_settings": settings_path.is_file(), "modern_region_paths": all(
              name.startswith("dimensions/minecraft/overworld/region/") for name in region_hashes),
              "modern_spawn": "spawn" in data, "embedded_player_absent": "Player" not in data,
              "vanilla_datapack_only": enabled == ["vanilla"]}
    return {"semantic_sha256": semantic.hexdigest(), "region_sha256": region_hashes,
            "level_semantic_sha256": hashlib.sha256(canonical(nbt_value(level.root))).hexdigest(),
            "settings_semantic_sha256": hashlib.sha256(canonical(nbt_value(settings.root))).hexdigest(),
            "level_bytes_sha256": file_sha(Path(world) / "level.dat"),
            "settings_bytes_sha256": file_sha(settings_path), "modern_gates": modern,
            "chunk_positions": sorted(chunk_positions), "sentinels": observed}


def worker(spec_path):
    spec = json.loads(Path(spec_path).read_text())
    writer = load_writer(spec["module"])
    tracemalloc.start()
    started = time.perf_counter()
    try:
        report = writer.write_overlay([spec["runs"]], Path(spec["world"]), BOUNDS,
                                      Path(spec["level_template"]), max_chunks=4)
        outcome = {"accepted": True, "writer_report": report}
    except ValueError as exc:
        outcome = {"accepted": False, "error_type": type(exc).__name__, "error": str(exc),
                   "world_exists": Path(spec["world"]).exists(),
                   "level_exists": (Path(spec["world"]) / "level.dat").exists()}
    elapsed = time.perf_counter() - started
    _, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    outcome["memory"] = {"tracemalloc_peak_bytes": peak,
                         "windows_peak_working_set": windows_peak_working_set(),
                         "elapsed_seconds_with_tracemalloc": elapsed,
                         "measurement_scope": "isolated synthetic 4-chunk writer; includes interpreter/imports in Windows peak, not a capacity benchmark"}
    if outcome["accepted"]:
        outcome["reopened"] = inspect_world(spec["world"], writer)
        outcome["reopened_twice"] = inspect_world(spec["world"], writer)
    Path(spec["result"]).write_text(json.dumps(outcome, indent=2, sort_keys=True))


def run_value(x, z, low, high, block, layer="building", feature="synthetic-building"):
    return {"x": x, "z": z, "yMin": low, "yMax": high, "block": block, "layer": layer,
            "featureId": feature, "geometryKind": "synthetic-regression", "sourceClass": "SYNTHETIC"}


def run_suite(args):
    root = EVIDENCE_ROOT / ("synthetic-streaming-equivalence-" + uuid.uuid4().hex)
    root.mkdir(parents=True, exist_ok=False)
    repository = Path(args.repository).resolve()
    candidate = Path(args.candidate_module).resolve()
    template = Path(args.level_template).resolve()
    template_settings = template.parent / "data/minecraft/world_gen_settings.dat"
    protected = {str(path): file_sha(path) for path in (candidate, candidate.parent / "anvil.py", template, template_settings)}
    baseline_ref = subprocess.check_output(["git", "-C", str(repository), "rev-parse", args.baseline_ref], text=True).strip()
    snapshots = {}
    for label in ("baseline", "candidate"):
        module_dir = root / label
        module_dir.mkdir()
        snapshots[label] = {}
        for name in ("overlay.py", "anvil.py"):
            content = subprocess.check_output(["git", "-C", str(repository), "show", f"{baseline_ref}:{RELATIVE_MERGE}{name}"]) if label == "baseline" else (candidate if name == "overlay.py" else candidate.parent / name).read_bytes()
            path = module_dir / name
            path.write_bytes(content)
            snapshots[label][name] = file_sha(path)
    rows = [run_value(-2, -2, 1, 8, "minecraft:stone"),
            run_value(-2, -2, 3, 5, "minecraft:air", "bridge", "synthetic-air-window"),
            run_value(2, 2, 1, 7, "minecraft:glass"),
            run_value(2, 2, 5, 7, "minecraft:dark_prismarine", "bridge", "synthetic-roof"),
            run_value(-1, 3, 1, 3, "minecraft:water", "water", "synthetic-water"),
            run_value(-15, -15, -3, 0, "minecraft:stone", "terrain", "synthetic-ground"),
            run_value(0, 0, 1, 5, "minecraft:oak_leaves"),
            run_value(0, 0, 5, 6, "minecraft:torch", "bridge", "synthetic-decoration")]
    fixture_paths = {}
    for name, values in (("positive", rows), ("reordered", list(reversed(rows))),
                         ("conflict", rows + [run_value(-2, -2, 1, 2, "minecraft:glass", feature="synthetic-conflict")])):
        path = root / (name + ".jsonl")
        path.write_text("".join(json.dumps(value, sort_keys=True) + "\n" for value in values))
        fixture_paths[name] = path
    results = {}
    for label in ("baseline", "candidate"):
        for case in ("positive", "reordered", "conflict"):
            name = label + "-" + case
            spec = {"module": str(root / label / "overlay.py"), "runs": str(fixture_paths[case]),
                    "world": str(root / (name + "-world")), "level_template": str(template),
                    "result": str(root / (name + "-result.json"))}
            spec_path = root / (name + "-spec.json")
            spec_path.write_text(json.dumps(spec))
            env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
            completed = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--worker-spec", str(spec_path)],
                                       capture_output=True, text=True, timeout=180, env=env)
            if completed.returncode:
                raise RuntimeError(name + ": " + completed.stderr[-5000:])
            results[name] = json.loads(Path(spec["result"]).read_text())
    failures = []
    reference = results["baseline-positive"]["reopened"]
    expected_sentinels = {"-2,2,-2": ["minecraft:stone", {}], "-2,4,-2": ["minecraft:air", {}],
                          "2,2,2": ["minecraft:glass", {}], "2,6,2": ["minecraft:dark_prismarine", {}]}
    for name, result in results.items():
        if name.endswith("conflict"):
            if result["accepted"] or "same-layer material conflict" not in result.get("error", "") or result["level_exists"]:
                failures.append(name + " must reject material conflict without a complete level")
            continue
        if not result["accepted"]:
            failures.append(name + " did not produce synthetic world")
            continue
        reopened = result["reopened"]
        if reopened != result["reopened_twice"]:
            failures.append(name + " changed across deterministic reopen")
        if reopened != reference:
            failures.append(name + " differs in semantic, region-byte, level or settings evidence")
        if reopened["sentinels"] != expected_sentinels:
            failures.append(name + " independent wall/air/glass/roof block check failed")
        if reopened["chunk_positions"] != [[-1, -1], [-1, 0], [0, -1], [0, 0]]:
            failures.append(name + " negative-origin chunk ownership mismatch")
        if not all(reopened["modern_gates"].values()):
            failures.append(name + " modern level settings gate failed")
    for path, before in protected.items():
        if file_sha(path) != before:
            failures.append("Source/template changed during harness: " + path)
    evidence = {"kind": "SYNTHETIC_STREAMING_EQUIVALENCE_ONLY", "status": "PASS" if not failures else "FAIL",
                "baseline_commit": baseline_ref, "snapshots": snapshots, "bounds": BOUNDS, "max_chunks": 4,
                "failures": failures, "results": results, "source_hashes": protected,
                "runtimeLoadAccepted": False, "visualAccepted": False, "capacityBenchmarkAccepted": False,
                "real_256m_generation_performed": False}
    receipt = root / "equivalence-receipt.json"
    receipt.write_text(json.dumps(evidence, indent=2, sort_keys=True))
    print(json.dumps({"status": evidence["status"], "receipt": str(receipt), "failures": failures,
                      "memory": {key: value["memory"] for key, value in results.items() if key.endswith("positive")}}, indent=2))
    return 0 if not failures else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worker-spec")
    parser.add_argument("--repository")
    parser.add_argument("--candidate-module")
    parser.add_argument("--level-template")
    parser.add_argument("--baseline-ref", choices=("04dacfb", "a0192b5"), default="04dacfb")
    args = parser.parse_args()
    if args.worker_spec:
        worker(args.worker_spec)
        return
    if not all((args.repository, args.candidate_module, args.level_template)):
        parser.error("--repository, --candidate-module and --level-template are required")
    raise SystemExit(run_suite(args))


if __name__ == "__main__":
    main()
