"""Explicit terrain/building vertical policy. Standard library only; no raster I/O.

Uncertainty is a conservative additive bound in metres when all inputs declare a
bound, otherwise None (unknown). It is never silently converted to zero.
"""
from dataclasses import dataclass
from math import ceil, floor, isfinite
from typing import Optional, Sequence


def _finite(value, name):
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not isfinite(value):
        raise ValueError(f"{name} must be finite")


def _bound(value):
    if value is not None:
        _finite(value, "uncertainty_m")
        if value < 0:
            raise ValueError("uncertainty_m must be nonnegative")


def _sum_bound(*values):
    return None if any(v is None for v in values) else sum(values)


@dataclass(frozen=True)
class ElevationEvidence:
    elevation_m: float
    semantic: str
    vertical_datum: str
    source_id: str
    uncertainty_m: Optional[float] = None
    provenance: tuple = ()

    def __post_init__(self):
        _finite(self.elevation_m, "elevation_m")
        _bound(self.uncertainty_m)
        if self.semantic not in {"bare_earth", "estimated_ground", "surface_dsm", "absolute_roof"}:
            raise ValueError("unknown elevation semantic")
        if not self.vertical_datum or not self.source_id:
            raise ValueError("vertical_datum and source_id are required")


@dataclass(frozen=True)
class BuildingHeight:
    value_m: float
    semantic: str
    source_id: str
    vertical_datum: Optional[str] = None
    uncertainty_m: Optional[float] = None

    def __post_init__(self):
        _finite(self.value_m, "value_m")
        _bound(self.uncertainty_m)
        if self.semantic not in {"above_ground", "absolute_roof"} or not self.source_id:
            raise ValueError("explicit building semantic and source_id required")
        if self.semantic == "above_ground" and (self.value_m < 0 or self.vertical_datum is not None):
            raise ValueError("relative height must be nonnegative and have no vertical datum")
        if self.semantic == "absolute_roof" and not self.vertical_datum:
            raise ValueError("absolute roof elevation requires its vertical datum")


@dataclass(frozen=True)
class DatumTransform:
    source_datum: str
    target_datum: str
    offset_m: float
    source_id: str
    uncertainty_m: Optional[float] = None

    def __post_init__(self):
        _finite(self.offset_m, "offset_m")
        _bound(self.uncertainty_m)
        if not all((self.source_datum, self.target_datum, self.source_id)) or self.source_datum == self.target_datum:
            raise ValueError("a documented distinct-datum transform is required")


@dataclass(frozen=True)
class VerticalMapping:
    target_datum: str
    sea_level_y: float
    min_y: int
    max_y: int
    rounding: str
    transforms: tuple = ()

    def __post_init__(self):
        _finite(self.sea_level_y, "sea_level_y")
        if not self.target_datum or self.rounding not in {"nearest_ties_up", "nearest_half_away_from_zero", "floor"}:
            raise ValueError("explicit target datum and rounding are required")
        if any(isinstance(v, bool) or not isinstance(v, int) for v in (self.min_y, self.max_y)) or self.min_y > self.max_y:
            raise ValueError("inclusive integer Minecraft Y bounds are required")
        keys = [(t.source_datum, t.target_datum) for t in self.transforms]
        if len(keys) != len(set(keys)):
            raise ValueError("ambiguous duplicate datum transforms")


def _blocked(reason, sources=()):
    return dict(status="blocked", value_m=None, uncertainty_m=None, vertical_datum=None,
                source_ids=list(sources), reasons=[reason], block_y=None,
                quantization_error_m=None)


def _normalize(evidence, mapping):
    value, uncertainty = evidence.elevation_m, evidence.uncertainty_m
    sources = [evidence.source_id, *evidence.provenance]
    if evidence.vertical_datum != mapping.target_datum:
        transform = next((t for t in mapping.transforms if t.source_datum == evidence.vertical_datum
                          and t.target_datum == mapping.target_datum), None)
        if transform is None:
            return _blocked("vertical_datum_transform_required", sources)
        value += transform.offset_m
        uncertainty = _sum_bound(uncertainty, transform.uncertainty_m)
        sources.append(transform.source_id)
    return dict(status="estimated" if evidence.semantic == "estimated_ground" else "resolved",
                value_m=value, uncertainty_m=uncertainty, vertical_datum=mapping.target_datum,
                source_ids=list(dict.fromkeys(sources)), reasons=[], block_y=None,
                quantization_error_m=None)


def _voxelize(decision, mapping):
    if decision["status"] == "blocked":
        return decision
    continuous_y = mapping.sea_level_y + decision["value_m"]
    if mapping.rounding == "nearest_half_away_from_zero":
        block_y = floor(continuous_y + 0.5) if continuous_y >= 0 else ceil(continuous_y - 0.5)
    elif mapping.rounding == "nearest_ties_up":
        block_y = floor(continuous_y + 0.5)
    else:
        block_y = floor(continuous_y)
    result = dict(decision)
    result["block_y"] = block_y
    result["quantization_error_m"] = block_y - continuous_y
    if not mapping.min_y <= block_y <= mapping.max_y:
        result.update(status="blocked", block_y=None, reasons=["minecraft_y_out_of_bounds"])
    return result


def resolve_ground(source_elevation, mapping, *, ground_evidence=None, allow_estimated_ground=False):
    """Only explicit bare earth, or opted-in estimated ground, may be a base."""
    evidence = ground_evidence if ground_evidence is not None else source_elevation
    if evidence.semantic not in {"bare_earth", "estimated_ground"}:
        return _blocked("ground_evidence_required_dsm_is_not_bare_earth", (evidence.source_id,))
    if evidence.semantic == "estimated_ground" and not allow_estimated_ground:
        return _blocked("estimated_ground_requires_explicit_opt_in", (evidence.source_id,))
    return _voxelize(_normalize(evidence, mapping), mapping)


def resolve_building(source_elevation, building_height, mapping, *, ground_evidence=None,
                     allow_estimated_ground=False):
    """Return JSON-ready ground/roof decisions, retaining a known roof if base is blocked."""
    ground = resolve_ground(source_elevation, mapping, ground_evidence=ground_evidence,
                            allow_estimated_ground=allow_estimated_ground)
    if building_height.semantic == "absolute_roof":
        roof = _normalize(ElevationEvidence(building_height.value_m, "absolute_roof",
                          building_height.vertical_datum, building_height.source_id,
                          building_height.uncertainty_m), mapping)
    elif ground["status"] == "blocked":
        roof = _blocked("relative_height_requires_resolved_ground", (building_height.source_id,))
    else:
        roof = dict(ground)
        roof.update(value_m=ground["value_m"] + building_height.value_m,
                    uncertainty_m=_sum_bound(ground["uncertainty_m"], building_height.uncertainty_m),
                    source_ids=[*ground["source_ids"], building_height.source_id])
    if ground["status"] != "blocked" and roof["status"] != "blocked" and roof["value_m"] < ground["value_m"]:
        roof.update(status="blocked", reasons=["roof_below_ground"], block_y=None)
    roof = _voxelize(roof, mapping)
    status = "blocked" if "blocked" in (ground["status"], roof["status"]) else (
        "estimated" if "estimated" in (ground["status"], roof["status"]) else "resolved")
    return dict(status=status, ground=ground, roof=roof,
                source_surface_semantic=source_elevation.semantic,
                source_surface_id=source_elevation.source_id,
                building_height_semantic=building_height.semantic,
                mapping=dict(target_datum=mapping.target_datum, sea_level_y=mapping.sea_level_y,
                             min_y=mapping.min_y, max_y=mapping.max_y, rounding=mapping.rounding),
                scale_m_per_block=1.0)


def interpolate_ground(samples: Sequence[ElevationEvidence], weights: Sequence[float], *,
                       method: str, source_id: str, interpolation_uncertainty_m=None):
    """Explicit interpolation is an estimate, even when its inputs are measured.

    Unknown spatial interpolation error stays unknown. Missing values, mixed datums,
    DSM values and negative/extrapolating weights are rejected, not filled silently.
    """
    if not samples or len(samples) != len(weights) or not method or not source_id:
        raise ValueError("samples, matching weights, method and source_id are required")
    _bound(interpolation_uncertainty_m)
    for weight in weights:
        _finite(weight, "weight")
        if weight < 0:
            raise ValueError("negative interpolation weights are not permitted")
    if abs(sum(weights) - 1.0) > 1e-9:
        raise ValueError("weights must sum to one")
    if any(s is None or s.semantic not in {"bare_earth", "estimated_ground"} for s in samples):
        raise ValueError("all samples must contain explicit ground evidence")
    if len({s.vertical_datum for s in samples}) != 1:
        raise ValueError("normalize vertical datums before interpolation")
    uncertainty = None if any(s.uncertainty_m is None for s in samples) else sum(
        w * s.uncertainty_m for s, w in zip(samples, weights))
    uncertainty = _sum_bound(uncertainty, interpolation_uncertainty_m)
    return ElevationEvidence(sum(w * s.elevation_m for s, w in zip(samples, weights)),
                             "estimated_ground", samples[0].vertical_datum, source_id,
                             uncertainty, tuple(dict.fromkeys([f"interpolation:{method}",
                             *[p for s in samples for p in (s.source_id, *s.provenance)]])))
