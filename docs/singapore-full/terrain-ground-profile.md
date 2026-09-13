# Shared ground profile

`tools/singapore-full/terrain/flat-provisional-y0.json` is the frozen first joined-strip profile. It is a construction plane: occupied surface block **Y=0**, standing/player feet **Y=1**. It does not establish Singapore ground elevation. All geometry consumers must record its `id` and keep `actualGroundAccepted: false`.

Horizontal coordinates use EPSG:3414: Minecraft X equals easting and Z equals 60000 minus northing. One block represents one projected metre. Coordinates are global; a building must receive one consistent ground decision across tiles. Terrain raster samples use the horizontal block centre `(x + 0.5, z + 0.5)`.

The versioned JSON contract is `ground-profile.schema.json`; `ground_profile.py` enforces cross-field rules. `vertical.surfaceY` is the occupied top surface block, not feet height. `vertical.standingOffset` is always 1. `minY` and `maxY` are inclusive occupied-block limits. Neither `surfaceY: 0` nor a configurable offset implies physical mean sea level. In flat mode, `datum` and `seaLevelY` are null, surveyed is false and ground uncertainty is unknown.

Production vertical quantization is pinned as `vertical.rounding: "nearest_half_away_from_zero"`: `floor(y + 0.5)` for nonnegative values and `ceil(y - 0.5)` for negatives. Thus +0.5 becomes +1 and -0.5 becomes -1. Consumers must not substitute Python's ties-to-even `round()`. The first flat Y=0 profile is unaffected.

Consumers in the initial strip use `flat_surface(profile, global_x, global_z)` or the equivalent JSON fields. Buildings receive `surfaceY` as their ground base according to their explicit renderer convention; roads and water geometry record the same profile. Do not silently pass `standingY` as ground. Consumers must reject an unsupported mode instead of falling back to Y=0.

`elevation-evidence` profiles require a named vertical datum, explicit `seaLevelY` offset and `samplesPath`. Their samples preserve source elevations, semantic class, nodata, country membership and land membership. The administrative boundary includes territorial water and is not a land mask. Unknown land stays unknown until actual coast/land polygons are available.

Copernicus GLO-30 is a surface model that includes buildings and vegetation, with approximately 30 m source sampling. Resampling it to one-metre blocks supplies interpolation, not one-metre bare-earth accuracy. Its `surface-dsm` evidence cannot be promoted to actual ground. Relative building heights may only be added to independent bare-earth evidence, or an explicitly allowed and labelled ground estimate through the separate ground policy. A datum transformation must have its own evidence; no constant geoid correction is assumed.

Run on Desktop with the project's prepared Python:

```text
python tools/singapore-full/terrain/ground_profile.py tools/singapore-full/terrain/flat-provisional-y0.json
python -m unittest discover -s tools/singapore-full/terrain -p test_ground_profile.py
```

No current game world or Laptop profile is changed by these tools.
