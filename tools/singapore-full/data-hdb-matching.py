"""Conservative exact-address matching for an explicitly inferred HDB floor proxy.

This module never changes source tags. Callers must count both HDB-key and OSM
outline multiplicity before accepting a candidate; proximity is not evidence.
"""
import importlib.util
import math
from pathlib import Path
import re

_spec = importlib.util.spec_from_file_location("fork_height_audit", Path(__file__).with_name("data-height-audit.py"))
_height = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_height)

# An explicit policy allowlist, not fuzzy expansion or punctuation erasure.
_TOKENS = {"RD": "ROAD", "AVE": "AVENUE", "DR": "DRIVE", "CRES": "CRESCENT",
           "CL": "CLOSE", "PL": "PLACE", "TER": "TERRACE", "LK": "LINK",
           "HTS": "HEIGHTS", "CTRL": "CENTRAL", "PK": "PARK",
           "JLN": "JALAN", "LOR": "LORONG", "UPP": "UPPER",
           "BT": "BUKIT", "STH": "SOUTH", "NTH": "NORTH", "TG": "TANJONG",
           "KG": "KAMPONG"}


def _result(raw, normalized=None, status="valid", reason=None):
    return {"raw": raw, "normalized": normalized, "status": status, "reason": reason}


def _text(raw):
    if not isinstance(raw, str):
        return None
    return " ".join(raw.strip().upper().split())


def normalize_block(raw):
    text = _text(raw)
    if not text:
        return _result(raw, status="missing" if raw is None or raw == "" else "malformed", reason="block-not-a-nonempty-string")
    if re.search(r"[;/,&]|\b(?:TO|AND)\b", text):
        return _result(raw, status="ambiguous", reason="multiple-block-addresses")
    if "-" in text:
        return _result(raw, status="range", reason="block-range-or-compound-number")
    # Permit conventional explicit labels; do not strip leading zeros or guess
    # letter/number confusions, unit numbers, or punctuation.
    text = re.sub(r"^(?:BLK|BLOCK)\s+", "", text)
    if not re.fullmatch(r"[0-9]+[A-Z]?", text):
        return _result(raw, status="malformed", reason="block-must-be-digits-with-optional-one-letter")
    return _result(raw, text)


def normalize_street(raw):
    text = _text(raw)
    if not text:
        return _result(raw, status="missing" if raw is None or raw == "" else "malformed", reason="street-not-a-nonempty-string")
    if re.search(r"[;/,&]|\b(?:AND|OR)\b", text):
        return _result(raw, status="ambiguous", reason="multiple-streets-or-intersection")
    if re.search(r"\d\s*-\s*\d", text):
        return _result(raw, status="range", reason="street-number-range")
    if not re.fullmatch(r"[A-Z0-9' -]+", text) or not re.search(r"[A-Z]", text):
        return _result(raw, status="malformed", reason="unsupported-street-characters")
    tokens = text.split()
    for i, token in enumerate(tokens):
        # Leading ST may mean Saint (e.g. ST MICHAEL'S ROAD). Leave it intact.
        if token == "ST" and i > 0 and (i == len(tokens) - 1 or (i == len(tokens) - 2 and tokens[-1].isdigit())):
            tokens[i] = "STREET"
        else:
            tokens[i] = _TOKENS.get(token, token)
    return _result(raw, " ".join(tokens))


def make_key(block, street):
    b, s = normalize_block(block), normalize_street(street)
    issue = next((item for item in (b, s) if item["status"] != "valid"), None)
    return {"key": None if issue else b["normalized"] + "|" + s["normalized"],
            "status": issue["status"] if issue else "valid",
            "reason": issue["reason"] if issue else None, "block": b, "street": s}


def enrichment_decision(tags, hdb_row, osm_match_count, policyversion="hdb-max-floor-proxy-v1", metres_per_floor=2.8):
    """Return a JSON-compatible proposal; input dictionaries remain untouched.

    osm_match_count is the count of matching candidate OSM outlines. More than
    one always fails closed here; proving part linkage is the caller's job and
    must not be inferred by passing coordinates or choosing the nearest object.
    hdb_row['_match_count'] may communicate duplicate source address rows.
    """
    if isinstance(metres_per_floor, bool) or not isinstance(metres_per_floor, (int, float)) or not math.isfinite(metres_per_floor) or metres_per_floor <= 0:
        raise ValueError("metres_per_floor must be a finite positive number")
    osm_key = make_key(tags.get("addr:housenumber"), tags.get("addr:street"))
    hdb_key = make_key(hdb_row.get("blk_no"), hdb_row.get("street"))
    out = {"policyVersion": policyversion, "status": None, "reason": None,
           "osmKey": osm_key, "hdbKey": hdb_key, "maxFloorObservation": None,
           "enrichment": None, "sourceTagsModified": False}

    def finish(status, reason):
        return {**out, "status": status, "reason": reason}

    if osm_key["status"] != "valid" or hdb_key["status"] != "valid":
        return finish("manual-review", "address-missing-malformed-range-or-ambiguous")
    if osm_key["key"] != hdb_key["key"]:
        return finish("no-match", "normalized-block-and-street-differ")
    if type(osm_match_count) is not int or osm_match_count != 1:
        return finish("ambiguous", "osm-address-does-not-identify-one-outline")
    hdb_count = hdb_row.get("_match_count", 1)
    if type(hdb_count) is not int or hdb_count != 1:
        return finish("ambiguous", "hdb-address-does-not-identify-one-row")

    floor, floor_error = _height.level_count(hdb_row.get("max_floor_lvl"))
    if floor_error or floor is None or floor <= 0 or floor > 100:
        return finish("manual-review", "hdb-maximum-floor-invalid-or-outside-review-bound")
    out["maxFloorObservation"] = {"raw": hdb_row.get("max_floor_lvl"), "value": floor,
                                  "field": "max_floor_lvl", "isMeasuredHeight": False}
    existing = _height.classify(tags)
    out["existingHeightClassification"] = existing["classification"]
    out["existingHeightErrors"] = existing["errors"]
    if "building:levels" in tags:
        # The generic height audit may preserve an independently valid top
        # height despite a zero/null floor tag. Enrichment is stricter: any
        # invalid existing level evidence needs review before accepting a join.
        levels, levels_error = _height.level_count(tags["building:levels"])
        underground, underground_error = _height.level_count(tags.get("building:levels:underground"))
        underground_only = levels == 0 and not underground_error and underground is not None and underground > 0
        if levels_error or levels is None or (levels == 0 and not underground_only):
            out["existingHeightErrors"] = existing["errors"] + ["building:levels:invalid-existing-count"]
            return finish("manual-review", "existing-osm-numeric-tags-invalid-or-conflicting")
    # Bad numeric fields cannot be cured by overwriting them with a proxy.
    if existing["classification"] == "invalid" or existing["errors"]:
        return finish("manual-review", "existing-osm-numeric-tags-invalid-or-conflicting")
    if "height" in tags:
        return finish("preserved", "existing-valid-osm-height")
    if "building:levels" in tags:
        return finish("preserved", "existing-valid-osm-levels")
    if hdb_row.get("residential") != "Y" or hdb_row.get("multistorey_carpark") != "N":
        return finish("manual-review", "residential-non-carpark-use-not-confirmed")
    if "building:part" in tags and tags.get("building:part") not in (None, "no", ""):
        return finish("manual-review", "hdb-block-maximum-cannot-be-assigned-to-a-part")
    if tags.get("building") in (None, "no", ""):
        return finish("manual-review", "candidate-is-not-a-building-outline")
    # An HDB block maximum is insufficient to determine elevated/underground
    # vertical extents even if explicit height and levels happen to be absent.
    if any(key in tags for key in ("min_height", "building:min_level", "building:levels:underground")):
        return finish("manual-review", "additional-vertical-geometry-needs-resolution")
    estimate = floor * metres_per_floor
    if not math.isfinite(estimate) or estimate <= 0:
        raise ValueError("Floor-height estimate exceeds numeric range")
    rounded_estimate = round(estimate, 6)
    out["enrichment"] = {"classification": "inferred-hdb-max-floor-proxy",
                         "heightMetres": rounded_estimate if rounded_estimate > 0 else estimate, "levels": floor,
                         "metresPerFloor": metres_per_floor, "roofIncluded": False,
                         "sourceField": "max_floor_lvl", "isMeasuredHeight": False,
                         "limitations": ["Maximum floor level is used as an unverified floor-count proxy.",
                                         "Uniform configured metres per floor is assumed, not surveyed.",
                                         "Roof, podium, stilts and unequal storey heights are not measured."]}
    return finish("enrich-missing", "unique-exact-normalized-address-and-no-existing-height-or-levels")
