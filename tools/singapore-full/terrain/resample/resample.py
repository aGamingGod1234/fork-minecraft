"""Bounded, auditable SVY21 DSM sampling. This is not bare-earth reconstruction."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
from typing import Any, Protocol

import pyproj
import shapely
from pyproj import Transformer
from shapely.geometry import Point, shape
from shapely.ops import transform, unary_union

MAX_CELLS = 65536
SCHEMA = "fork.terrain.sample-tile.v1"
TO_LONLAT = Transformer.from_crs(3414, 4326, always_xy=True)
TO_SVY21 = Transformer.from_crs(4326, 3414, always_xy=True)


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def block_center(x: int, z: int) -> tuple[float, float]:
    """East/north at Minecraft column center; block edges are integer metres."""
    return x + 0.5, 60000.0 - (z + 0.5)


class PolygonMask:
    """Polygon-only mask. No repairing, filling holes, or dropping small islands."""

    def __init__(self, geometry: Any, provenance: dict[str, Any]):
        if geometry.is_empty or geometry.geom_type not in ("Polygon", "MultiPolygon"):
            raise ValueError("Mask must contain non-empty Polygon/MultiPolygon geometry")
        if not geometry.is_valid:
            raise ValueError("Invalid mask topology; repair upstream with an explicit receipt")
        self.geometry = geometry
        self.provenance = provenance

    @classmethod
    def from_geojson(cls, path: Path, crs: str = "EPSG:4326") -> "PolygonMask":
        document = json.loads(path.read_text(encoding="utf-8-sig"))
        if document.get("crs"):
            raise ValueError("Legacy embedded GeoJSON CRS unsupported; specify --mask-crs explicitly")
        kind = document.get("type")
        items = document.get("features", []) if kind == "FeatureCollection" else [document]
        geometries = []
        for item in items:
            geometry = item.get("geometry") if item.get("type") == "Feature" else item
            if not geometry or geometry.get("type") not in ("Polygon", "MultiPolygon"):
                raise ValueError("Every mask feature must contain Polygon/MultiPolygon geometry")
            polygon = shape(geometry)
            if not polygon.is_valid or polygon.is_empty:
                raise ValueError("Invalid or empty mask feature")
            geometries.append(polygon)
        if not geometries:
            raise ValueError("Empty mask feature collection")
        merged = unary_union(geometries)
        if crs != "EPSG:3414":
            converter = Transformer.from_crs(crs, 3414, always_xy=True)
            merged = transform(converter.transform, merged)
        return cls(merged, {"name": path.name, "sha256": sha256(path), "input_crs": crs,
                            "classification": "explicit_polygon_center_test"})

    def covers(self, easting: float, northing: float) -> bool:
        # Boundary centers count inside; subcell coast coverage is not inferred.
        return bool(self.geometry.covers(Point(easting, northing)))

    def metadata(self) -> dict[str, Any]:
        parts = list(self.geometry.geoms) if self.geometry.geom_type == "MultiPolygon" else [self.geometry]
        return {**self.provenance, "crs": "EPSG:3414", "components": len(parts),
                "holes": sum(len(part.interiors) for part in parts),
                "area_m2": self.geometry.area, "boundary_rule": "center_covered",
                "topology_repair": False}


class RasterSource(Protocol):
    metadata: dict[str, Any]

    def sample_lonlat(self, lon: float, lat: float, method: str = "nearest") -> dict[str, Any]: ...


def source_sample(sources: list[RasterSource], lon: float, lat: float, method: str) -> dict[str, Any]:
    for source in sources:
        result = source.sample_lonlat(lon, lat, method=method)
        if result["status"] != "outside":
            return result
    return {"status": "outside", "elevation_m": None}


def sample_tile(x0: int, z0: int, width: int, height: int, sources: list[RasterSource],
                country: PolygonMask, land: PolygonMask | None = None,
                method: str = "nearest") -> dict[str, Any]:
    if any(type(value) is not int for value in (x0, z0, width, height)):
        raise ValueError("Grid origins/dimensions must be integers")
    if width < 1 or height < 1 or width * height > MAX_CELLS:
        raise ValueError(f"Tile must contain 1..{MAX_CELLS} cells; no country-size jobs")
    if method not in ("nearest", "bilinear") or not sources:
        raise ValueError("Use nearest/bilinear and at least one raster source")
    arrays: dict[str, list[Any]] = {name: [] for name in
        ("elevation_m", "country_mask", "land_mask", "sample_status", "source_id")}
    counts: dict[str, int] = {}
    datums: set[str] = set()
    semantics: set[str] = set()
    for row in range(height):
        for column in range(width):
            east, north = block_center(x0 + column, z0 + row)
            inside = country.covers(east, north)
            land_value = land.covers(east, north) if inside and land is not None else None
            status, elevation, source_id = "outside_country", None, None
            if inside and land_value is False:
                status = "water"
            elif inside:
                lon, lat = TO_LONLAT.transform(east, north)
                result = source_sample(sources, lon, lat, method)
                status = {"valid": "valid", "nodata": "nodata", "outside": "outside_raster"}.get(result["status"])
                if status is None:
                    raise ValueError(f"Unknown raster sample status {result['status']!r}")
                source_id = result.get("source_id")
                elevation = result.get("elevation_m")
                if status == "valid":
                    if elevation is None or not math.isfinite(elevation):
                        raise ValueError("Raster returned a nonfinite/missing valid elevation")
                    if not result.get("vertical_datum") or not result.get("semantic"):
                        raise ValueError("Valid source sample needs vertical datum and surface semantic")
                    if result.get("vertical_unit") != "m" or not source_id:
                        raise ValueError("Valid source sample needs metre units and source ID")
                    datums.add(result["vertical_datum"])
                    semantics.add(result["semantic"])
                else:
                    elevation = None
            for name, value in (("elevation_m", elevation), ("country_mask", inside),
                                ("land_mask", land_value), ("sample_status", status), ("source_id", source_id)):
                arrays[name].append(value)
            counts[status] = counts.get(status, 0) + 1
    if len(datums) > 1 or len(semantics) > 1:
        raise ValueError("Mixed vertical datums/semantics require an explicit upstream conversion")
    return {"schema": SCHEMA, "grid": {"crs": "EPSG:3414", "axis_order": "easting,northing",
                "minecraft_x0": x0, "minecraft_z0": z0, "width": width, "height": height,
                "metres_per_block": 1, "array_order": "row-major: row=z, column=x",
                "center_formula": "E=x+0.5; N=60000-(z+0.5)",
                "first_center_en": list(block_center(x0, z0)), "y_offset": None},
            "sampling": {"method": method, "resampled": True,
                "interpolated": method == "bilinear", "native_spacing_approx_m": 30,
                "one_metre_measurement": False, "surveyed": False,
                "vertical_datum": next(iter(datums), None), "semantic": next(iter(semantics), "surface_dsm"),
                "vertical_unit": "m", "vertical_conversion": None,
                "nodata_rule": "preserve_null_no_fallback", "uncertainty_m": None,
                "warning": "One-metre output cells resample an approximately 30 m surface DSM; buildings and vegetation remain."},
            "masks": {"country": country.metadata(), "land": land.metadata() if land else None,
                "land_status": "explicit_land_polygon" if land else "unknown_no_coastline_mask",
                "country_is_land": False},
            "sources": [source.metadata for source in sources],
            "software": {"pyproj": pyproj.__version__, "shapely": shapely.__version__},
            "counts": counts, "arrays": arrays}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--country-mask", required=True, type=Path)
    parser.add_argument("--land-mask", type=Path, help="Actual coastal land polygons, NOT an administrative boundary")
    parser.add_argument("--mask-crs", choices=("EPSG:4326", "EPSG:3414"), default="EPSG:4326")
    parser.add_argument("--raster-reader", type=Path, default=Path(__file__).resolve().parents[1] / "raster" / "copernicus.py")
    parser.add_argument("--raster", action="append", required=True, type=Path)
    for name in ("x", "z", "width", "height"):
        parser.add_argument("--" + name, type=int, required=True)
    parser.add_argument("--method", choices=("nearest", "bilinear"), default="nearest")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.width < 1 or args.height < 1 or args.width * args.height > MAX_CELLS:
        parser.error(f"Tile limited to 1..{MAX_CELLS} cells")
    spec = importlib.util.spec_from_file_location("fork_copernicus_reader", args.raster_reader)
    if spec is None or spec.loader is None:
        parser.error("Cannot load raster reader")
    reader = importlib.util.module_from_spec(spec)
    import sys
    sys.modules[spec.name] = reader
    spec.loader.exec_module(reader)
    sources = [reader.CopernicusTile(path) for path in args.raster]
    try:
        document = sample_tile(args.x, args.z, args.width, args.height, sources,
            PolygonMask.from_geojson(args.country_mask, args.mask_crs),
            PolygonMask.from_geojson(args.land_mask, args.mask_crs) if args.land_mask else None, args.method)
        for metadata, path in zip(document["sources"], args.raster):
            metadata["sha256"] = sha256(path)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(document, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        print(json.dumps({"output": str(args.output), "sha256": sha256(args.output), "counts": document["counts"]}))
    finally:
        for source in sources:
            if hasattr(source, "close"):
                source.close()


if __name__ == "__main__":
    main()
