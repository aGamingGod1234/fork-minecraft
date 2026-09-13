# Copernicus GLO-30 raster reader

`tools/singapore-full/terrain/raster/copernicus.py` provides bounded, read-only elevation access using Python 3.11+ and NumPy. It does not generate Minecraft blocks, download files, alter source rasters, or install packages. The prepared Desktop `data/.venv` already supplies NumPy.

```python
from copernicus import CopernicusTile

with CopernicusTile("Copernicus_DSM_COG_10_N01_00_E103_00_DEM.tif") as tile:
    metadata = tile.metadata
    evidence = tile.sample_lonlat(103.85, 1.28, method="bilinear")
```

CLI, from the reader directory:

```text
python copernicus.py TILE.tif --lonlat 103.85 1.28 --method bilinear
python -m unittest test_copernicus.py
```

`--lonlat` may be repeated. Omitting it prints metadata without reading raster blocks. The JSON output includes the number of decoded blocks and compressed raster bytes read. The library object exposes these as `blocks_decoded` and `compressed_bytes_read`. Each instance owns its file handle and cache; use separate instances for concurrent workers.

## Numeric contract

- Input is longitude then latitude in EPSG:4326 degrees. `sample_lonlat` supports `nearest` and `bilinear`.
- The result contains `elevation_m`, `status`, `semantic`, `vertical_datum`, `vertical_unit`, `method`, `source_id`, and fractional `pixel_col_row`. Valid/nodata results also contain contributing pixel coordinates. Status is `valid`, `nodata`, or `outside`; unavailable elevation is `None`.
- Nodata sentinels, NaN and infinity return `nodata`. Zero remains a valid measurement; this tool does not classify zero as ocean, missing data or bare ground. Bilinear interpolation touching a positive-weight nodata neighbour remains unavailable. It does not silently renormalize around missing values.
- The metadata explicitly distinguishes pixel centers from outer pixel bounds. The two inspected tiles use **PixelIsPoint**. E103 sample `[column=0,row=0]` is `[103,2]`; E104 is `[104,2]`. Both use longitude step `1/3600` degree and latitude step `-1/3600` degree, with dimensions 3600 by 3600. Do not introduce an additional half-pixel shift. PixelIsArea fixtures are separately tested and converted to centers by a half-pixel offset.
- Nearest uses half-open pixel-index bounds `[-0.5, width-0.5)` and `[-0.5, height-0.5)` and rounds ties toward the increasing index. Bilinear uses the enclosing centers and requires every positive-weight neighbour to exist in this tile. It does not clamp, extrapolate or interpolate across tile seams; the caller must resolve seam neighbours at a mosaic layer. An exact edge center does not need a zero-weight outside neighbour.
- Neither inspected tile contains a GDAL nodata tag. Metadata reports that absence instead of inventing a sentinel. Samples remain raw floating point metres without rounding or Minecraft Y offsets.

## What the heights mean

Copernicus describes this product as a **digital surface model** including buildings, infrastructure and vegetation. GLO-30 has one-arc-second latitude spacing and a nominal 30 m resolution. Its vertical reference is **EGM2008, EPSG:3855**, in metres. These facts are from the [Copernicus product specification](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM). The EGM2008 declaration is product-level evidence; these two TIFF files embed the horizontal WGS84 keys but do not embed a vertical CRS declaration.

Consequently every result is labelled `semantic="surface_dsm"`. Resampling onto one-metre Minecraft cells does not create one-metre measured terrain detail. Do not use this DSM sample as a bare-earth foundation and then add a relative building height: a roof or tree canopy may already contribute to the sample. Ground estimation, a true terrain source, surveyed heights, land/water masks and any transformation into another vertical datum belong to separate explicit policies.

The downloaded files use the [Copernicus AWS COG distribution](https://copernicus-dem-30m.s3.amazonaws.com/readme.html). Keep the acquisition receipts, source URLs, hashes and the distribution's licence/attribution with downstream outputs. This reader is not a replacement for those receipts.

## Supported storage profile and bounds

The reader handles classic TIFF, top-left, north-up, tiled, single-band IEEE float32, EPSG:4326, one scale/tiepoint transform, uncompressed or Deflate compression, and TIFF predictor 1 or floating-point predictor 3. It rejects unsupported profiles rather than guessing. It reads the full-resolution first image directory, not overviews. The floating predictor operation follows the published TIFF byte-plane convention documented by the [libtiff reference implementation](https://gitlab.com/libtiff/libtiff/-/blob/master/libtiff/tif_predict.c); no libtiff source code is copied.

Both real tiles use 1024 by 1024 compressed blocks. One queried location decodes at most four necessary blocks for bilinear sampling, even though a whole tile contains roughly 13 million samples. The default LRU retains two decoded blocks, about 8 MiB for these tiles. Intermediate byte arrays are temporary. Metadata entries are capped at 1 MiB, individual decoded blocks at 16 MiB, and decompression is bounded by expected block size. The module starts no worker threads or processes.

## Verified samples and tests

Bounded real-file checks on 13 September 2026:

| Tile | Longitude, latitude | Method | Elevation above EGM2008 | Decoded blocks | Compressed bytes |
| --- | --- | --- | ---: | ---: | ---: |
| N01 E103 | 103.85, 1.28 | Bilinear, exact center | 13.554924964904785 m | 1 | 1,684,230 |
| N01 E104 | 104.02, 1.33 | Nearest | 4.233908653259277 m | 1 | 929,455 |

These are DSM samples, not surveyed ground control. Tiny synthetic fixtures verify known negative, zero and positive heights, four-block bilinear interpolation, missing values, point/area alignment, endianness, both predictors, edge behavior, bounded cache reuse and explicit rejection. They are independent of Minecraft and do not require the large source tiles. The private real-data evidence receipt records actual source hashes and sampled results without copying the GeoTIFFs into Git.
