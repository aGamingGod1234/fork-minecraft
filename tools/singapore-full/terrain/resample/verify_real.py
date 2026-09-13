"""Bounded integration receipt: 4x4 district tile and its two 2x4 halves only."""
import argparse
import importlib.util
import json
from pathlib import Path
import sys

from resample import PolygonMask, sample_tile, sha256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--raster-reader", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("fork_raster_verify", args.raster_reader)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    raster_path = args.data_root / "Copernicus_DSM_COG_10_N01_00_E103_00_DEM.tif"
    country = PolygonMask.from_geojson(args.data_root / "mask-260912" / "singapore-admin-mask.geojson")
    receipt = {"schema": "fork.terrain.resample-verification.v1", "raster_sha256": sha256(raster_path),
               "raster_reader_sha256": sha256(args.raster_reader), "country": country.metadata(),
               "grid_origin": [29856, 30506], "grid_shape": [4, 4], "method_checks": {}}
    with module.CopernicusTile(raster_path) as source:
        for method in ("nearest", "bilinear"):
            whole = sample_tile(29856, 30506, 4, 4, [source], country, method=method)
            left = sample_tile(29856, 30506, 2, 4, [source], country, method=method)
            right = sample_tile(29858, 30506, 2, 4, [source], country, method=method)
            for key in whole["arrays"]:
                joined = []
                for row in range(4):
                    joined.extend(left["arrays"][key][row * 2:row * 2 + 2])
                    joined.extend(right["arrays"][key][row * 2:row * 2 + 2])
                assert joined == whole["arrays"][key], (method, key, "tile partition changed values")
            assert whole["counts"] == {"valid": 16}
            assert whole["arrays"]["land_mask"] == [None] * 16
            assert whole["masks"]["country"]["components"] == 3
            assert whole["sampling"]["vertical_datum"] == "EGM2008"
            assert whole["sampling"]["semantic"] == "surface_dsm"
            whole["sources"][0]["sha256"] = receipt["raster_sha256"]
            sample_path = args.output.parent / f"market-street-4x4-{method}.json"
            sample_path.parent.mkdir(parents=True, exist_ok=True)
            sample_path.write_text(json.dumps(whole, indent=2, allow_nan=False) + "\n", encoding="utf-8")
            receipt["method_checks"][method] = {"tile_partition_invariant": True,
                "sample_count_with_partitions": 32, "counts": whole["counts"],
                "minimum_elevation_m": min(whole["arrays"]["elevation_m"]),
                "maximum_elevation_m": max(whole["arrays"]["elevation_m"]),
                "sample_file": sample_path.name, "sample_sha256": sha256(sample_path)}
        receipt["blocks_decoded"] = source.blocks_decoded
        receipt["compressed_bytes_read"] = source.compressed_bytes_read
    receipt["passed"] = True
    args.output.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
