"""Run bounded real-raster, shared-grid and synthetic policy integration checks."""
from __future__ import annotations

import argparse
from contextlib import ExitStack
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import time


ROOT = Path(__file__).resolve().parent


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(data_root):
    started = time.perf_counter()
    raster = load("fork_raster_milestone", "raster/copernicus.py")
    resample = load("fork_resample_milestone", "resample/resample.py")
    profile = load("fork_profile_milestone", "ground_profile.py")
    policy = load("ground_policy", "policy/ground_policy.py")
    fixtures = load("fork_policy_examples", "policy/fixture_examples.py").cases()
    assert fixtures["measured_ground_relative_height"]["roof"]["value_m"] == 42.25
    assert fixtures["dsm_only_relative_height"]["status"] == "blocked"
    assert fixtures["absolute_roof_elevation"]["roof"]["value_m"] == 80
    flat = json.loads((ROOT / "flat-provisional-y0.json").read_text(encoding="utf-8"))
    profile_validation = profile.validate(flat)
    assert profile_validation["valid"] and not profile_validation["actualGroundAccepted"]
    assert profile.flat_surface(flat, 29712, 30496)["surfaceY"] == 0

    paths = [data_root / f"Copernicus_DSM_COG_10_N01_00_E{east}_00_DEM.tif" for east in (103, 104)]
    mask_path = data_root / "mask-260912/singapore-admin-mask.geojson"
    country = resample.PolygonMask.from_geojson(mask_path)
    with ExitStack() as stack:
        sources = [stack.enter_context(raster.CopernicusTile(path)) for path in paths]
        point_samples = [sources[0].sample_lonlat(103.85, 1.28, "bilinear"),
                         sources[1].sample_lonlat(104.02, 1.33, "nearest")]
        assert all(p["status"] == "valid" and p["semantic"] == "surface_dsm" for p in point_samples)
        left = resample.sample_tile(29712, 30496, 3, 2, sources, country, method="bilinear")
        right = resample.sample_tile(29714, 30496, 3, 2, sources, country, method="bilinear")
        assert left["counts"].get("valid") == 6 and right["counts"].get("valid") == 6
        # Both tiles independently visit the same absolute cells along their overlap.
        shared_checks = []
        for row in range(2):
            li, ri = row * 3 + 2, row * 3
            for key in ("elevation_m", "country_mask", "land_mask", "sample_status", "source_id"):
                assert left["arrays"][key][li] == right["arrays"][key][ri], key
            shared_checks.append({"x": 29714, "z": 30496 + row,
                "elevation_m": left["arrays"]["elevation_m"][li],
                "land_mask": left["arrays"]["land_mask"][li],
                "sample_status": left["arrays"]["sample_status"][li], "equal": True})
        assert all(v is None for v in left["arrays"]["land_mask"])
        assert not left["sampling"]["one_metre_measurement"]
        p = point_samples[0]
        actual_dsm = policy.ElevationEvidence(p["elevation_m"], p["semantic"], p["vertical_datum"], p["source_id"])
        blocked = policy.resolve_building(actual_dsm,
            policy.BuildingHeight(30, "above_ground", "synthetic:height-30m"),
            policy.VerticalMapping("EGM2008", 0, -64, 319, flat["vertical"]["rounding"]))
        assert blocked["status"] == "blocked" and blocked["roof"]["value_m"] is None
        io = [{"source_id": s.metadata["source_id"], "blocks_decoded": s.blocks_decoded,
               "compressed_bytes_read": s.compressed_bytes_read} for s in sources]
    return {"schema": "fork.terrain.integration-milestone.v1", "status": "PASS",
            "created_utc": datetime.now(timezone.utc).isoformat(),
            "elapsed_seconds": time.perf_counter() - started,
            "scope": "Two real source point samples, twelve resampled cell visits and synthetic policy fixtures; no world generation",
            "sources": [{"file": p.name, "bytes": p.stat().st_size, "sha256": sha256(p)} for p in paths],
            "administrative_mask_sha256": sha256(mask_path),
            "point_samples": point_samples, "bounded_io": io,
            "shared_coordinate_checks": shared_checks, "left_tile": left,
            "actual_dsm_relative_height_rejected": blocked,
            "synthetic_policy_fixtures": fixtures, "first_strip_profile": profile_validation,
            "acceptance": {"raster_reader_operational": True, "global_grid_resampling_operational": True,
                "synthetic_policy_checks": True, "actual_ground_accepted": False,
                "land_mask_available": False, "whole_country_generated": False,
                "one_metre_source_measurement": False}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = run(args.data_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "output": str(args.output),
                      "sha256": sha256(args.output), "elapsed_seconds": result["elapsed_seconds"]}))
