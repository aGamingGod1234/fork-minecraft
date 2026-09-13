"""Deterministic, source-attributed Singapore roads and mapped surfaces."""
from .osm_adapter import adapt_osm, AdaptedFeatures
from .road_raster import normalize_width, rasterize_road
from .topology import analyze_topology, vertical_policy
from .water_landuse import rasterize as rasterize_surfaces
from .runs import emit_surface_runs

__all__ = ["adapt_osm", "AdaptedFeatures", "normalize_width", "rasterize_road",
           "analyze_topology", "vertical_policy", "rasterize_surfaces", "emit_surface_runs"]
