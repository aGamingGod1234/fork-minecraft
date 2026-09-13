"""Validate the shared ground profile; never turn DSM into bare-earth evidence."""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


def validate(profile: dict) -> dict:
    errors = []
    if not isinstance(profile, dict) or any(not isinstance(profile.get(key), dict) for key in ("grid", "vertical", "evidence")):
        return {"valid": False, "errors": ["profile, grid, vertical and evidence must be objects"],
                "actualGroundAccepted": False, "mode": None, "profileId": None}
    if not isinstance(profile.get("id"), str) or not profile["id"].strip():
        errors.append("profile id is required")
    if profile.get("schemaVersion") != 1:
        errors.append("schemaVersion must be 1")
    expected = {"kind": "EPSG:3414", "blocksPerMeter": 1,
                "originEasting": 0, "originNorthing": 60000,
                "xDirection": "east", "zDirection": "south"}
    grid = profile.get("grid", {})
    for key, value in expected.items():
        if grid.get(key) != value:
            errors.append(f"grid.{key} must be {value!r}")
    mode = profile.get("mode")
    if mode not in ("flat-provisional", "elevation-evidence"):
        errors.append("mode must explicitly distinguish provisional flat from elevation evidence")
    vertical = profile.get("vertical", {})
    evidence = profile.get("evidence", {})
    for key in ("minY", "maxY", "standingOffset"):
        if type(vertical.get(key)) is not int:
            errors.append(f"vertical.{key} must be an integer")
    if isinstance(vertical.get("minY"), int) and isinstance(vertical.get("maxY"), int):
        if vertical["minY"] >= vertical["maxY"]:
            errors.append("vertical range must increase")
    if vertical.get("standingOffset") != 1:
        errors.append("standingOffset must be 1 block above the occupied surface block")
    if vertical.get("rounding") != "nearest_half_away_from_zero":
        errors.append("shared production Y rounding must be nearest_half_away_from_zero")
    if not evidence.get("sourceId"):
        errors.append("evidence.sourceId is required")
    if type(evidence.get("surveyed")) is not bool:
        errors.append("evidence.surveyed must be boolean")
    if type(evidence.get("actualGroundAccepted")) is not bool:
        errors.append("evidence.actualGroundAccepted must be boolean")
    uncertainty = evidence.get("uncertaintyMeters")
    if uncertainty is not None and (isinstance(uncertainty, bool) or not isinstance(uncertainty, (float, int)) or not math.isfinite(uncertainty) or uncertainty < 0):
        errors.append("uncertaintyMeters must be null or finite nonnegative metres")
    if mode == "flat-provisional":
        if type(vertical.get("surfaceY")) is not int:
            errors.append("flat mode requires integer surfaceY")
        if vertical.get("datum") is not None or vertical.get("seaLevelY") is not None:
            errors.append("provisional flat Y is not an elevation datum or sea level")
        if evidence.get("classification") != "provisional-flat":
            errors.append("flat mode classification must be provisional-flat")
        if evidence.get("surveyed") is not False or evidence.get("actualGroundAccepted") is not False:
            errors.append("flat mode cannot claim surveyed or accepted actual ground")
        if uncertainty is not None:
            errors.append("flat mode ground error is unknown, not a numeric accuracy claim")
        if type(vertical.get("surfaceY")) is int and type(vertical.get("minY")) is int and type(vertical.get("maxY")) is int:
            if not vertical["minY"] <= vertical["surfaceY"] < vertical["maxY"]:
                errors.append("surface and standing block must fit vertical range")
    elif mode == "elevation-evidence":
        if not vertical.get("datum") or not isinstance(vertical.get("seaLevelY"), (int, float)) or isinstance(vertical.get("seaLevelY"), bool) or not math.isfinite(vertical["seaLevelY"]):
            errors.append("elevation mode requires explicit datum and finite seaLevelY offset")
        if "surfaceY" in vertical:
            errors.append("elevation mode cannot silently apply one constant surfaceY")
        if not profile.get("samplesPath"):
            errors.append("elevation mode requires explicit samplesPath")
        if evidence.get("classification") not in ("bare-earth", "estimated-ground", "surface-dsm"):
            errors.append("unsupported elevation classification")
        if evidence.get("classification") != "bare-earth" and evidence.get("actualGroundAccepted") is not False:
            errors.append("DSM and estimated ground do not establish actual ground acceptance")
        if evidence.get("classification") in ("estimated-ground", "surface-dsm") and evidence.get("surveyed") is not False:
            errors.append("estimated ground and DSM are not surveyed bare-earth ground")
    if evidence.get("actualGroundAccepted") is True and not evidence.get("acceptanceEvidence"):
        errors.append("actual ground acceptance requires independent acceptanceEvidence")
    return {"valid": not errors, "errors": errors,
            "actualGroundAccepted": evidence.get("actualGroundAccepted") is True and not errors,
            "mode": mode, "profileId": profile.get("id")}


def flat_surface(profile: dict, x: int, z: int) -> dict:
    result = validate(profile)
    if not result["valid"]:
        raise ValueError("; ".join(result["errors"]))
    if profile["mode"] != "flat-provisional":
        raise ValueError("elevation profiles require their explicit samples; no flat fallback")
    if type(x) is not int or type(z) is not int:
        raise ValueError("x,z must be integer global block coordinates")
    y = profile["vertical"]["surfaceY"]
    return {"x": x, "z": z, "surfaceY": y, "standingY": y + 1,
            "elevationMeters": None, "datum": None,
            "classification": "provisional-flat", "sourceId": profile["evidence"]["sourceId"],
            "uncertaintyMeters": None, "actualGroundAccepted": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path)
    args = parser.parse_args()
    report = validate(json.loads(args.profile.read_text(encoding="utf-8-sig")))
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report["valid"] else 1)
