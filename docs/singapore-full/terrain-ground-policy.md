# Ground and building height policy

`tools/singapore-full/terrain/policy/ground_policy.py` is a standard-library-only,
JSON-ready vertical policy. It performs no raster access and does not modify a world.

The national horizontal grid is one metre per block: Minecraft X is SVY21 easting,
Z is 60000 minus SVY21 northing. This module only decides elevations and Y coordinates.
Horizontal interpolation onto one-metre cells does not improve the source accuracy.

## Evidence semantics

Copernicus GLO-30 is a roughly 30 m digital **surface** model in EGM2008. Roofs,
infrastructure and vegetation can contribute to its elevations. It is not a surveyed
one-metre bare-earth surface. The raster and resampling modules must label it
`surface_dsm`; this policy will not use that label as a building base.

`ElevationEvidence(elevation_m, semantic, vertical_datum, source_id,
uncertainty_m=None, provenance=())` accepts `bare_earth`, `estimated_ground`,
`surface_dsm` and `absolute_roof`. Actual evidence or a declared estimation method,
not this module, must justify the label. An estimate may cite its DSM source in
provenance but remains an estimate. Missing raster cells must be resolved outside
this module or left blocked; do not pass zero elevations for nodata.

`BuildingHeight(value_m, semantic, source_id, vertical_datum=None,
uncertainty_m=None)` distinguishes `above_ground` from `absolute_roof`.
A relative height cannot declare a datum. An absolute roof must declare one.
OSM `height` may be mapped to above-ground height by an upstream interpreter only
after unit/tag validation; a floor count is an estimate, not measured height.

## Explicit vertical configuration

`VerticalMapping(target_datum, sea_level_y, min_y, max_y, rounding, transforms=())`
has no implicit datum, Y offset, world height or rounding default. `sea_level_y`
is the block coordinate assigned to **zero of the selected vertical datum**.
The name does not assert that EGM2008 zero equals Singapore's local mean sea level.
A deployment requiring local tidal/mean-sea-level alignment must supply documented
evidence and a transform. No Singapore datum conversion is bundled here.

Y = sea_level_y + target-datum elevation in metres. Rounding is explicit:
`floor`, `nearest_ties_up`, or `nearest_half_away_from_zero`. `nearest_ties_up`
sends half values toward positive infinity, including negative values.
`nearest_half_away_from_zero` sends +0.5 to +1 and -0.5 to -1; this is the
shared national terrain/roads policy. Quantization applies to the final world Y
after adding the offset, not to elevation before adding it. Bounds are inclusive.
Out-of-range values are blocked and their
physical elevation retained; there is no clipping, scaling or automatic lowering.
The signed quantization error is returned separately from source uncertainty.

`DatumTransform(source_datum, target_datum, offset_m, source_id,
uncertainty_m=None)` is an explicit additive correction valid only where its
external evidence supports a constant offset. It is not a geoid transformation
engine. There is no implicit inverse, chained or guessed transform. Duplicate
direct transforms are rejected. Spatially varying transformations belong upstream.

## Stable functions

```
resolve_ground(source_elevation, mapping,
               ground_evidence=None, allow_estimated_ground=False)
resolve_building(source_elevation, building_height, mapping,
                 ground_evidence=None, allow_estimated_ground=False)
interpolate_ground(samples, weights, method=..., source_id=...,
                   interpolation_uncertainty_m=None)
```

The keyword arguments above are keyword-only. A supplied ground override must still
have ground semantics. Estimated ground requires an explicit opt-in. DSM plus
relative building height is blocked, so the policy never adds building height on
top of an already elevated roof pixel. An absolute roof can be resolved independently
but the complete building stays blocked until its base is resolved. Roof elevations
below ground also block construction.

Each decision returns `status` (`resolved`, `estimated`, or `blocked`), `value_m`,
`uncertainty_m`, `vertical_datum`, `source_ids`, `reasons`, `block_y`, and
`quantization_error_m`. A building also includes `ground`, `roof`, original source
semantics, the mapping and `scale_m_per_block: 1.0`. These dictionaries can be
serialized directly with `json.dumps(..., allow_nan=False)`. Physical values can be
present in a blocked result for diagnostics; the status must gate voxel construction.

Uncertainty is a conservative additive bound in metres, **not** a statistical
confidence interval. Unknown inputs stay `None`; they do not become zero. Providers
must not label RMSE or percentile errors as a hard bound without justification.
An uncertain roof near the ground does not prove clearance even if nominal values
are ordered. `resolved` means semantic compatibility, not survey-certified accuracy.

Ground interpolation accepts only explicit ground evidence in one datum and
nonnegative weights summing to one. It returns `estimated_ground`, with all sources
and the method retained. Its error stays unknown unless the caller supplies both
sample uncertainty and an interpolation error allowance. Interpolation never
promotes DSM to bare earth or supplies fabricated one-metre accuracy.

## Verification

From the repository root on Desktop:

```
py -3 -m unittest discover -s tools/singapore-full/terrain/policy -p "test_*.py" -v
```

The numeric fixtures cover measured ground plus relative height; DSM-only ambiguity;
absolute roof elevations; unknown and estimated ground; datum transforms; negative
rounding; world-height bounds; invalid inputs; and interpolation provenance/error.
All fixture elevations and the LOCAL_DATUM offset are synthetic test values. They
are not real Singapore ground measurements or a published datum correction.
