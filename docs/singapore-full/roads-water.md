# Water and mapped landuse raster adapter
	ools/singapore-full/roads/water_landuse.py converts complete projected GeoJSON areas to sparse Minecraft surface recommendations. It does not write a world, determine terrain elevation, fill water depth, derive a national land mask, or claim photographic materials.
## Coordinate and tile contract
Input features must already use x = EPSG:3414 easting and z = 60000 - EPSG:3414 northing. One block represents one projected metre. Geographic longitude/latitude or unconverted northing is not accepted by contract. The caller owns projection.

asterize(features, core, clip_mask=None) accepts a FeatureCollection or iterable of Features. core=(min_x,min_z,max_x,max_z) contains integer block coordinates and is half open on its maximum edges. Each cell samples its centre (x+0.5,z+0.5). Retain complete feature geometry through this step; avoid clipping polygons to the tile first. Adjacent cores therefore have no duplicated writes and agree with a single larger-core raster.
An optional clip_mask is an already projected Polygon/MultiPolygon geometry or a predicate (x,z)->bool. It only restricts writes. Singapore's administrative boundary includes territorial sea; membership in that boundary does not establish land. No cells are emitted merely because the clip mask includes a position. An externally verified physical land mask is a separate dataset and responsibility.
Polygon and MultiPolygon exteriors are supported with any ring winding. Holes remain unwritten. Cell centres exactly on an exterior boundary are included; centres on a hole boundary are excluded. Every ring must be closed and finite. Incomplete reference flags, unclosed rings, lines and degenerate rings produce diagnostics. The adapter does not repair invalid polygon topology; callers must provide valid complete GeoJSON geometry.
## Surface meaning and precedence
The result contains cells[(x,z)] = SurfaceCell(...) and deterministic diagnostics. 	o_dict() provides JSON-serializable sorted cells.
- mapped_water: natural=water, waterway=riverbank, landuse=reservoir or basin. The geometry establishes a mapped water boundary. It does not establish bottom elevation, water level, salinity or navigability.
- mapped_landuse: supported OSM landuse categories. The category is source evidence; the selected grass, dirt or stone block is an explicit stylized palette choice.
- Unknown space: no output. There is no implicit grass, ocean or other fallback fill.
- Coastline: diagnostic coastline_not_land_mask, even if a submitted line happens to be closed. This module never fabricates land from an incomplete coastline.
Every cell retains its OSM source ID, mapped category and material_basis=stylized_palette_not_surveyed_surface. Water wins over overlapping landuse. Equal-priority overlaps use source ID then canonical feature JSON, so input order cannot change the result. A downstream layer compositor must resolve buildings and roads against these recommendations; this adapter does not overwrite another layer.
## Real fixture and checks
The fixture contains two complete ways from the existing district extraction: canal way 886588582 and The Promontory@Marina Bay grass way 116801285. Its metadata retains the original XML SHA256, OSM attribution and ODbL terms. Fixture projection used pyproj 3.7.2 with Transformer.from_crs(4326,3414,always_xy=True); projected coordinates retain six decimals. This small fixture is not evidence of national coverage.
Runtime and tests use Python standard library only. Run on Desktop:
`powershell
py -3.11 tools/singapore-full/roads/tests/test_water_landuse.py
py -3.11 tools/singapore-full/roads/water_landuse.py tools/singapore-full/roads/fixtures/water_osm.json --core 29340 30030 29410 30110 --out canal-surface.json
`
The focused tests cover real mapped canal/lawn output, holes and winding, disconnected multipolygons, adjacent tile equivalence, administrative masking without invented land, unknown/incomplete coastlines, invalid coordinates, deterministic precedence and invalid cores.
Source data: [OpenStreetMap contributors, ODbL](https://www.openstreetmap.org/copyright). See fixture metadata for provenance. No water/shoreline completeness claim is made.
A preflight candidate budget defaults to 1,000,000 bounding-box cell visits across all source areas. It fails before sampling if exceeded. Use smaller cores for larger datasets; an explicit positive integer max_candidates keyword or --max-candidates CLI override can raise this limit. Invalid feature properties produce diagnostics rather than crashing. The test suite contains eleven focused checks, including malformed properties and country-sized budget rejection.
