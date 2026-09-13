# Terrain tools integration

The terrain lane provides a bounded local Copernicus reader, SVY21 resampling, explicit vertical evidence policy and the common first-strip ground profile. It does not generate or approve a Minecraft world.

The tools were verified on Desktop with Python 3.11.9, NumPy 2.4.6, pyproj 3.7.2 and Shapely 2.1.2, already installed in the data environment. `tools/singapore-full/terrain/requirements.txt` records these versions. No new packages were installed for this work.

Run the integrated tiny milestone with the prepared Python:

```text
python tools/singapore-full/terrain/run_milestone.py --data-root PRIVATE_DATA_ROOT --output PRIVATE_OUTPUT_JSON
```

The data directory must contain `Copernicus_DSM_COG_10_N01_00_E103_00_DEM.tif`, the corresponding E104 tile, and `mask-260912/singapore-admin-mask.geojson`. The command reads two point samples, samples two overlapping 3×2 block areas, checks exact shared-coordinate equality and runs synthetic ground/roof policy examples. It writes a private evidence JSON with actual source hashes, source reads, timings and explicit acceptance limits. It does not visit the entire country, start a server, write region files or change game profiles.

Initial real samples were 13.554924964904785 m at longitude 103.85, latitude 1.28, and 4.233908653259277 m at longitude 104.02, latitude 1.33. These values are source **surface model** samples, not independently measured bare-earth truth. The first sample used bilinear sampling at an exact native pixel; the second used nearest sampling. The custom limited GeoTIFF decoder was tested with independently encoded numeric TIFF fixtures because an alternate raster decoder was not installed. Independent external comparison remains a later verification step.

The integrated test deliberately passes the actual DSM evidence to a synthetic 30 m relative building height and checks that the decision is blocked. A plausible elevation number must not let a renderer add a building height to a rooftop. Synthetic bare-earth and absolute-roof fixtures separately verify arithmetic and datum requirements; they do not establish real Singapore ground coverage.

Run the focused suites in these four directories with `python -m unittest discover -s DIRECTORY -p "test_*.py"`: `tools/singapore-full/terrain`, then its `raster`, `resample` and `policy` subdirectories. The respective suites cover the shared profile, TIFF interpretation, geographic sampling/masks and vertical evidence decisions.

The first joined-strip renderer must consume `flat-provisional-y0.json`: occupied surface Y=0, standing feet Y=1, global half-away-from-zero vertical quantization, and no physical datum or ground acceptance. Real elevation profiles require explicit source evidence and conversions. The country boundary includes territorial water; no actual land mask is available in this terrain milestone. The raster reader intentionally does not interpolate across two source TIFFs, so source-edge coverage must be checked before national generation.
