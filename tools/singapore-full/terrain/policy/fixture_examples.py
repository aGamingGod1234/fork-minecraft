"""Print deterministic numeric policy evidence as JSON; contains synthetic data only."""
import json
from ground_policy import (ElevationEvidence as E, BuildingHeight as H,
                           VerticalMapping as M, resolve_building, interpolate_ground)


def cases():
    mapping = M("EGM2008", 64, -64, 319, "nearest_ties_up")
    ground = E(12.25, "bare_earth", "EGM2008", "synthetic:survey", 0.2)
    dsm = E(48, "surface_dsm", "EGM2008", "synthetic:dsm", 4)
    height = H(30, "above_ground", "synthetic:height", uncertainty_m=0.5)
    interpolated = interpolate_ground(
        [E(v, "bare_earth", "EGM2008", f"synthetic:corner:{i}", 0.2)
         for i, v in enumerate([0, 10, 20, 30])], [0.25] * 4,
        method="bilinear", source_id="synthetic:interpolated", interpolation_uncertainty_m=1.0)
    return {
        "fixture_version": 1,
        "synthetic_only": True,
        "measured_ground_relative_height": resolve_building(dsm, height, mapping, ground_evidence=ground),
        "dsm_only_relative_height": resolve_building(dsm, height, mapping),
        "absolute_roof_elevation": resolve_building(ground, H(80, "absolute_roof", "synthetic:roof", "EGM2008", 0.3), mapping),
        "interpolated_without_opt_in": resolve_building(dsm, height, mapping, ground_evidence=interpolated),
        "interpolated_with_opt_in": resolve_building(dsm, height, mapping, ground_evidence=interpolated, allow_estimated_ground=True),
    }


if __name__ == "__main__":
    print(json.dumps(cases(), indent=2, allow_nan=False))
