"""Grow a new world from exact, nonoverlapping global chunk ownership cores.

Sources stay read-only. Caller owns source hashing, immutable input leases and
runtime acceptance. Only chunk coordinate/file metadata is retained in memory;
NBT trees flow one at a time through the verified streaming region writer.
"""
from __future__ import annotations

from collections import defaultdict
from contextlib import closing
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile

from anvil import _fs_path, iter_region, write_region_stream

MODERN_REGION_DIRECTORY = "dimensions/minecraft/overworld/region"
_REGION_NAME = re.compile(r"r\.(-?\d+)\.(-?\d+)\.mca")


def _relative_directory(value):
    normalized = str(value).replace("\\", "/")
    path = PurePosixPath(normalized)
    if (path.is_absolute() or not path.parts
            or any(part in (".", "..") or not re.fullmatch(r"[A-Za-z0-9_.-]+", part) for part in path.parts)):
        raise ValueError("region_directory must be a safe relative directory")
    return path.as_posix()


def _core(value):
    if (not isinstance(value, (list, tuple)) or len(value) != 4
            or any(type(number) is not int or number % 16 for number in value)):
        raise ValueError("core_bounds must contain four 16-aligned integers")
    x0, z0, x1, z1 = value
    if x0 >= x1 or z0 >= z1:
        raise ValueError("core_bounds must have positive half-open extent")
    return (x0 // 16, z0 // 16, x1 // 16, z1 // 16)


def _overlaps(first, second):
    return (first[0] < second[2] and second[0] < first[2]
            and first[1] < second[3] and second[1] < first[3])


def _contains(bounds, coords):
    return bounds[0] <= coords[0] < bounds[2] and bounds[1] <= coords[1] < bounds[3]


def merge_regions(sources, output_world, region_directory=MODERN_REGION_DIRECTORY):
    """Copy complete owned cores into a brand-new output region directory.

    Existing output_world is allowed for Main's staged level.dat/configuration;
    its target region directory must not exist. Existing source/destination
    worlds are never overwritten. Every core must provide every owned chunk.
    Halos are excluded, and source chunk coordinates remain unchanged.
    """
    sources = list(sources)
    if not sources:
        raise ValueError("At least one source core is required")
    output_world = Path(output_world).resolve()
    relative_output = _relative_directory(region_directory)
    destination = output_world.joinpath(*PurePosixPath(relative_output).parts)
    if os.path.lexists(_fs_path(destination)):
        raise FileExistsError("Output region directory already exists")
    if os.path.exists(_fs_path(output_world)) and not os.path.isdir(_fs_path(output_world)):
        raise ValueError("output_world must be a new or staged directory")

    normalized, ids = [], set()
    for descriptor in sources:
        if not isinstance(descriptor, dict):
            raise ValueError("Source descriptor must be a dictionary")
        source_id = descriptor.get("id")
        if not isinstance(source_id, str) or not source_id or source_id in ids:
            raise ValueError("Source IDs must be nonempty and unique")
        ids.add(source_id)
        bounds = _core(descriptor.get("core_bounds"))
        world = Path(descriptor["world_path"]).resolve()
        if (world == output_world or world in output_world.parents
                or output_world in world.parents):
            raise ValueError("Source and output world trees must be separate")
        relative_source = _relative_directory(descriptor.get("region_directory", MODERN_REGION_DIRECTORY))
        region = world.joinpath(*PurePosixPath(relative_source).parts)
        if not os.path.isdir(_fs_path(region)):
            raise FileNotFoundError("Source region directory does not exist: " + str(region))
        for previous in normalized:
            if _overlaps(bounds, previous["bounds"]):
                raise ValueError("Duplicate chunk ownership between " + source_id + " and " + previous["id"])
        normalized.append({"id": source_id, "world": world, "region": region, "bounds": bounds,
                           "relative": relative_source, "core": list(descriptor["core_bounds"])})

    grouped = defaultdict(list)
    source_counts, source_reports, source_regions = {}, [], []
    # Preflight all cores and headers before creating an output tree. Coordinates
    # are metadata only; no decoded chunk survives the next iterator step.
    for source in normalized:
        selected_count = 0
        candidates = []
        with os.scandir(_fs_path(source["region"])) as entries:
            for entry in entries:
                if not entry.name.endswith(".mca"):
                    continue
                match = _REGION_NAME.fullmatch(entry.name)
                if not match or not entry.is_file(follow_symlinks=False):
                    raise ValueError("Invalid source region entry: " + entry.name)
                rx, rz = map(int, match.groups())
                if _overlaps((rx * 32, rz * 32, (rx + 1) * 32, (rz + 1) * 32), source["bounds"]):
                    candidates.append((rx, rz, source["region"] / entry.name))
        for rx, rz, path in sorted(candidates, key=lambda item: (item[1], item[0])):
            selected = set()
            with closing(iter_region(path)) as chunks:
                for coords, document in chunks:
                    if _contains(source["bounds"], coords):
                        if coords in selected:
                            raise ValueError("Duplicate source chunk coordinates")
                        selected.add(coords)
                    del document
            if selected:
                selected_count += len(selected)
                grouped[(rx, rz)].append((source["id"], path, selected))
                source_regions.append({"sourceId": source["id"],
                                       "path": path.relative_to(source["world"]).as_posix(),
                                       "selectedChunks": len(selected)})
        bounds = source["bounds"]
        expected = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1])
        if selected_count != expected:
            raise ValueError(f"Source core {source['id']} has holes: expected {expected}, found {selected_count}")
        source_counts[source["id"]] = selected_count
        source_reports.append({"id": source["id"], "world_path": str(source["world"]),
                               "core_bounds": source["core"], "expectedChunks": expected,
                               "selectedChunks": selected_count})

    os.makedirs(_fs_path(destination.parent), exist_ok=True)
    staging = tempfile.mkdtemp(prefix=".grow-", dir=_fs_path(destination.parent))
    outputs, written_counts = [], defaultdict(int)
    try:
        for (rx, rz), records in sorted(grouped.items(), key=lambda item: (item[0][1], item[0][0])):
            def owned_chunks():
                for source_id, path, selected in sorted(records, key=lambda item: item[0]):
                    remaining = set(selected)
                    with closing(iter_region(path)) as chunks:
                        for coords, document in chunks:
                            if coords in selected:
                                if coords not in remaining:
                                    raise ValueError("Duplicate chunk during source reread")
                                remaining.remove(coords)
                                written_counts[source_id] += 1
                                yield coords, document
                            del document
                    if remaining:
                        raise ValueError("Source changed during merge; selected chunks disappeared")
            name = f"r.{rx}.{rz}.mca"
            receipt = write_region_stream(Path(staging) / name, owned_chunks())
            outputs.append({"path": relative_output + "/" + name,
                            "sha256": receipt["sha256"], "bytes": receipt["bytes"]})
        if dict(written_counts) != source_counts:
            raise ValueError("Written source counts do not match exact core ownership")
        if os.path.lexists(_fs_path(destination)):
            raise FileExistsError("Output region directory appeared during merge")
        os.rename(staging, _fs_path(destination))
        staging = None
    finally:
        if staging is not None:
            # This path was allocated by mkdtemp above, never a caller path.
            shutil.rmtree(staging)

    return {"outputs": outputs, "chunkCount": sum(source_counts.values()),
            "sourceChunkCounts": source_counts, "sources": source_reports,
            "sourceRegions": source_regions,
            "ownership": {"exactCoreCoverage": True, "coordinatesTranslated": False,
                          "duplicateOwnership": False, "canonicalNbtVerified": True}}
