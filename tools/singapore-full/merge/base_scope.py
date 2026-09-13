"""Verified point-center domain for automatic provisional terrain.

This is a source-mask contract, not measured terrain or bathymetry acceptance.
Shapely/NumPy are imported only when this optional mode is requested.
"""
from __future__ import annotations

from collections import Counter
import hashlib
import json
from pathlib import Path
import re

FRAME = {"crs": "EPSG:3414", "x": "easting", "z": "60000-northing", "blocksPerMeter": 1}
PROFILE = "country-coast-provisional-y0-v1"
CLASSES = ("outside", "foreign", "unknown", "land", "sea")


def _read_pinned(pin, base, label):
    if not isinstance(pin, dict) or not isinstance(pin.get("path"), str):
        raise ValueError(f"{label} needs an explicit path and SHA256")
    expected = pin.get("sha256")
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
        raise ValueError(f"{label} needs an explicit SHA256")
    path = (base / pin["path"]).resolve()
    payload = path.read_bytes()
    actual = hashlib.sha256(payload).hexdigest()
    if actual != expected.lower():
        raise ValueError(f"{label} SHA256 mismatch")
    doc = json.loads(payload.decode("utf-8-sig"))
    if not isinstance(doc, dict) or doc.get("type") != "FeatureCollection" or not isinstance(doc.get("features"), list):
        raise ValueError(f"{label} must be a FeatureCollection")
    return doc, {"sha256": actual, "bytes": len(payload)}


def _geometry(features, label, shapely):
    geometries = []
    for feature in features:
        if not isinstance(feature, dict) or feature.get("type") != "Feature":
            raise ValueError(f"{label} has an invalid feature")
        geometry = feature.get("geometry")
        if not isinstance(geometry, dict) or geometry.get("type") not in ("Polygon", "MultiPolygon"):
            raise ValueError(f"{label} requires polygon geometry")
        parsed = shapely.from_geojson(json.dumps(geometry, allow_nan=False))
        if parsed.is_empty or not parsed.is_valid:
            raise ValueError(f"{label} has empty or invalid polygon geometry; repair is forbidden")
        geometries.append(parsed)
    combined = shapely.union_all(geometries)
    if not combined.is_valid:
        raise ValueError(f"{label} union is invalid")
    shapely.prepare(combined)
    return combined


class BaseScope:
    """Prepared geometries evaluated in vectorized bounded arrays, never per-block Point calls."""

    def __init__(self, descriptor_path):
        import numpy as np
        import shapely
        self._np, self._shapely = np, shapely
        path = Path(descriptor_path).resolve()
        payload = path.read_bytes()
        descriptor = json.loads(payload.decode("utf-8-sig"))
        if descriptor.get("schemaVersion") != 1 or descriptor.get("kind") != "country-coast-base-scope":
            raise ValueError("unsupported base scope descriptor")
        if descriptor.get("coordinateFrame") != FRAME or descriptor.get("sampling") != "block-center":
            raise ValueError("base scope requires global EPSG:3414 X=E,Z=60000-N, 1 block/m, block-center sampling")
        docs, pins = {}, {}
        for label in ("country", "foreignExclusions", "coast"):
            docs[label], pins[label] = _read_pinned(descriptor.get(label), path.parent, label)
        country = docs["country"]
        country_frame = (country.get("coordinateSystem") == "EPSG:3414"
                         and country.get("axisMapping") == {"x": "easting", "z": "60000-northing"})
        legacy_country_frame = bool(country["features"]) and all(
            f.get("properties", {}).get("crs") == "Minecraft metric X/Z from EPSG3414"
            for f in country["features"])
        if not (country_frame or legacy_country_frame):
            raise ValueError("country mask must declare the expected X/Z frame")
        foreign = docs["foreignExclusions"]
        if foreign.get("coordinateSystem") != "EPSG:3414" or foreign.get("axisMapping") != {"x": "easting", "z": "60000-northing"}:
            raise ValueError("foreign exclusions must declare the expected X/Z frame")
        coast = docs["coast"]
        if coast.get("coordinate_space") != "minecraft_xz" or coast.get("crs") != {
            "type": "name", "properties": {"name": "EPSG:3414 derived X=E,Z=60000-N"}
        }:
            raise ValueError("coast mask must declare the expected X/Z frame")
        self._country = _geometry(country["features"], "country", shapely)
        if self._country.is_empty:
            raise ValueError("country mask must not be empty")
        self._foreign = _geometry(foreign["features"], "foreignExclusions", shapely)
        groups = {key: [] for key in ("land", "sea", "unknown")}
        for feature in coast["features"]:
            # Unrecognized source classes remain explicitly unknown, never inferred land.
            kind = feature.get("properties", {}).get("class")
            groups[kind if kind in ("land", "sea") else "unknown"].append(feature)
        self._coast = {key: _geometry(features, "coast " + key, shapely) for key, features in groups.items()}
        self._receipt = {
            "schemaVersion": 1, "profileId": PROFILE,
            "descriptorSha256": hashlib.sha256(payload).hexdigest(), "masks": pins,
            "coordinateFrame": dict(FRAME), "sampling": "block-center: X=x+0.5,Z=z+0.5",
            "precedence": ["foreign", "outside", "unknown-or-conflicting-coast", "land-or-sea"],
            "defaultLand": "provisional bedrock Y[-4,-3), dirt Y[-3,0), grass Y[0,1)",
            "defaultSea": "provisional bedrock Y[-4,-3), dirt Y[-3,0); no automatic surface water",
            "unmodeledColumns": "AIR/void; no ocean or land inference",
            "waterSurface": "separate mapped water layer required at Y[0,1)",
            "bathymetryAccepted": False, "actualGroundAccepted": False,
            "coastFidelityAccepted": False, "sourceFidelityAccepted": False,
            "runtime": {"shapely": shapely.__version__, "numpy": np.__version__},
        }

    def classify(self, bounds):
        """Return row-major class names for a half-open, globally aligned rectangle."""
        if (len(bounds) != 4 or any(type(v) is not int for v in bounds)
                or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]):
            raise ValueError("base scope bounds must be positive half-open integer bounds")
        if (bounds[2] - bounds[0]) * (bounds[3] - bounds[1]) > 1048576:
            raise ValueError("base scope evaluation is limited to 1048576 columns")
        np, shapely = self._np, self._shapely
        xs = np.arange(bounds[0], bounds[2], dtype="float64") + 0.5
        zs = np.arange(bounds[1], bounds[3], dtype="float64") + 0.5
        xx, zz = np.meshgrid(xs, zs)
        points = shapely.points(xx.ravel(), zz.ravel())
        country = shapely.covers(self._country, points)
        foreign = shapely.covers(self._foreign, points)
        land, sea, unknown = (shapely.covers(self._coast[k], points) for k in ("land", "sea", "unknown"))
        result = np.full(len(points), "unknown", dtype="<U7")
        result[land & ~sea & ~unknown] = "land"
        result[sea & ~land & ~unknown] = "sea"
        result[~country] = "outside"
        result[foreign] = "foreign"
        return tuple(result.tolist())

    def chunk(self, cx, cz):
        return self.classify((cx * 16, cz * 16, (cx + 1) * 16, (cz + 1) * 16))

    def receipt(self):
        return json.loads(json.dumps(self._receipt))


def record_classes(report, classes):
    scope = report.setdefault("baseScope", {})
    counts = scope.setdefault("columnCounts", {key: 0 for key in CLASSES})
    for key, value in Counter(classes).items():
        counts[key] += value
    scope["baseTerrainScopeClipped"] = True
    scope["unknownCoastColumnsAccepted"] = counts["unknown"] == 0
