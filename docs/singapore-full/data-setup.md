# Singapore source tools

These tools acquire open geodata and derive masked, reference-checked source files. They do not claim all real-world features are mapped or that a Minecraft world has been generated.

Use Python 3.11 or newer. Create a virtual environment in a data cache outside the Git checkout, then install tools/singapore-full/data-requirements.txt. All command paths below are relative to this checkout; substitute an external cache path for CACHE.

```text
python -m venv CACHE/.venv
CACHE/.venv/Scripts/python.exe -m pip install -r tools/singapore-full/data-requirements.txt
CACHE/.venv/Scripts/python.exe tools/singapore-full/data-download.py --manifest docs/singapore-full/data-sources.json --cache CACHE
CACHE/.venv/Scripts/python.exe tools/singapore-full/data-mask.py CACHE/malaysia-singapore-brunei-260912.osm.pbf CACHE/mask
CACHE/.venv/Scripts/python.exe tools/singapore-full/data-extract.py --source CACHE/malaysia-singapore-brunei-260912.osm.pbf --mask CACHE/mask/singapore-admin-mask.geojson --output-dir CACHE/extract
CACHE/.venv/Scripts/python.exe tools/singapore-full/data-coverage.py --mask CACHE/mask/singapore-admin-mask.geojson --output-dir CACHE/coverage
```

On Linux or macOS use CACHE/.venv/bin/python. The extractor uses two IO workers and queue size four. Existing output directories are immutable; choose a fresh directory for each attempt. Never pass an incomplete JSON to generation. Require extract-report.json, matching source/output hashes and referenceComplete=true.

The input PBF includes Malaysia and Brunei. Source selection uses OpenStreetMap relation 536780, a valid multipolygon with three components including remote eastern maritime components. Its administrative boundaries include territorial waters. This is not a land-fill mask. Country geometry remains in WGS84; coverage jobs project to EPSG:3414 and use Minecraft X=easting, Z=60000-northing at one block per metre.

Reference-only nodes/ways can lie outside Singapore to preserve complete geometry; their tags are removed. Features intersecting the border must still be clipped to the national mask by rendering. Data about an offshore island can exist even when its island is smaller than a land-cover raster cell. Do not replace coastline geometry with a rectangular extent.

Height tags are mapped assertions, not survey certification. Levels-only heights are inferred; absent or invalid heights remain missing. Material/colour tags are partial facade evidence, not exact facade textures. The two Copernicus DSM tiles include roofs and vegetation at roughly 30m spacing. They are not 1m bare terrain and must not be used as ground under full building height without correction.

Coverage jobs are all planned, generated=false and verified=false. The mask-derived expected list includes territorial-water cores; jobs must not turn those cores into land. Seam, coastline, source-reference and fidelity gates remain required before an expensive national rollout.

Source licences and notices are retained in data-licenses. OSM-derived data retains ODbL obligations; this does not change unrelated code's licence. Distributing a derived database or a produced work based on one can require sharing the corresponding derivative database or changes under ODbL. Consult the retained terms for the release's actual structure; no expert legal review is claimed.

The original OSM maritime polygon encloses Middle Rocks land features despite official attribution to Malaysia. Therefore referenceComplete=true does not mean Singapore-only land. Keep the original mask provenance, apply separately audited foreign-land exclusions, and leave unclear jurisdiction areas explicitly unverified.
