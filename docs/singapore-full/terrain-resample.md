# SVY21 terrain sample contract

`tools/singapore-full/terrain/resample/resample.py` produces a bounded JSON tile. It writes no Minecraft world, does not schedule country generation, and does not derive building ground levels.

The horizontal grid uses EPSG:3414 with explicit easting/northing order. Minecraft X is easting; Minecraft Z is `60000 - northing`. Integer coordinates denote block edges. Column `(x,z)` is sampled at `E=x+0.5, N=60000-(z+0.5)`. Each output cell spans one metre. Arrays are row-major, rows increase Z (south), columns increase X (east). `pyproj` transforms the centers to longitude/latitude with `always_xy=True`. This avoids the native CRS axis-order ambiguity. There is no implied vertical offset or conversion to Minecraft Y.

This is resampling of a surface DSM at approximately 30 m native spacing. One-metre output spacing adds no measured detail. Buildings and vegetation remain in DSM elevations. `surveyed=false`, `one_metre_measurement=false`, `semantic=surface_dsm`, and the actual source vertical datum accompany the values. Copernicus EGM2008 heights are not silently converted to Singapore Height Datum or sea level. No numerical uncertainty is invented.

## Masks and coastlines

`--country-mask` is the jurisdiction boundary. The existing Singapore administrative mask has three components and includes territorial water; it is not a land mask. `--land-mask` optionally accepts actual coastal land polygons with holes and islands. Without it every in-country `land_mask` value is JSON null and `land_status=unknown_no_coastline_mask`. Elevations may still be sampled, but downstream construction must not treat these as established land.

Polygon and MultiPolygon GeoJSON are accepted, including Feature/FeatureCollection wrappers. The default coordinates are WGS84 longitude/latitude; `--mask-crs EPSG:3414` accepts projected input instead. Features are unioned without topology repair or simplification. Invalid polygons and line-only coast datasets are rejected. Holes and disconnected components survive. Centers on polygon boundaries count inside. This is center classification, not subcell coastline reconstruction.

Cells outside the country are `outside_country`; cells explicitly outside supplied land polygons are `water`; neither triggers a raster read. Valid raster cells are `valid`; missing source cells are `nodata`; uncovered positions are `outside_raster`. Excluded and missing elevations are null, never zero. Land state is independent of raster availability. A positive height does not establish land. The raster source list is searched only when a source reports `outside`; a `nodata` result never falls back to another source or interpolation method.

## Raster reader interface

`CopernicusTile(path)` is loaded from sibling `terrain/raster/copernicus.py` by default. During worktree integration `--raster-reader <absolute-path>` can point to that isolated reader. It provides:

```python
tile.metadata  # JSON-serializable source ID, checksum, georeference, spacing, datum
tile.sample_lonlat(lon, lat, method="nearest")
# {status: valid|nodata|outside, elevation_m: float|None, source_id: str,
#  semantic: surface_dsm, vertical_datum: EGM2008, vertical_unit: m, ...}
```

Nearest is the default. Bilinear is explicit and flagged `interpolated=true`; null contributors remain null according to the reader. Source pixel centers come from the TIFF reader, never from the one-metre block convention. Copernicus PixelIsPoint ties sample `[0,0]` directly to its geographic tie point. Mixed vertical datums or mixed surface semantics fail instead of mixing silently.

## Bounded invocation

Use the existing Desktop `fork-singapore-full/data/.venv` Python (pyproj, shapely, numpy). No additional package installation is needed. Example paths below are workspace-relative:

```powershell
python tools/singapore-full/terrain/resample/resample.py `
  --country-mask data/mask-260912/singapore-admin-mask.geojson `
  --raster data/Copernicus_DSM_COG_10_N01_00_E103_00_DEM.tif `
  --x 28000 --z 21255 --width 8 --height 8 `
  --method nearest --output terrain/resample/sample-8x8.json
```

The hard cap is 65,536 cells per invocation, checked before source loading and again by the importable function. There is no override. Additional tiles or full-country iteration require a separate coordinator lease. The CLI explicitly hashes each input raster once after sampling; the importable `sample_tile` preserves caller-supplied metadata, so its caller must attach acquisition receipt hashes. Input file checksums, source metadata, software versions, method and mask topology counts make an output auditable. Paths in the JSON are basenames rather than sensitive machine paths unless supplied by the reader itself. Copernicus vertical-datum evidence comes from the official product description, not a vertical CRS tag in these TIFFs; that distinction remains in source metadata.

Run focused tests with `python -m unittest discover -s tools/singapore-full/terrain/resample -p 'test_*.py'`. Tests cover SVY21 origin/axis order, exact block centers, row order, disjoint islands and holes, boundary points, water versus jurisdiction, missing values, source selection, bilinear flags and cell budget rejection.

`verify_real.py --data-root <data> --raster-reader <reader.py> --output <private-receipt.json>` runs only one 4x4 district tile and its two 2x4 halves, once per method. On the acquired N01/E103 tile this passed with 16 valid cells per tile, all land classifications unknown, three country components, and exact partition invariance of all arrays. Nearest elevation was 13.117673873901367 m; bilinear ranged from 13.096422203496935 to 13.158992498671799 m. One compressed raster block (1,684,230 bytes) was decoded across all 64 samples. These values are surface heights, not proven street or building bases. Source raster SHA256: `fe2cf9dbc8a06ac328fb6d3d11a3764adac203e91adfae6cb066e07390480417`.

Source references: [SLA SVY21 survey specifications](https://www.sla.gov.sg/qql/slot/u143/Newsroom/Circulars/Land-Survey/CS%20Directive%20V5_0.pdf); [Copernicus DSM description and vertical datum](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM).
