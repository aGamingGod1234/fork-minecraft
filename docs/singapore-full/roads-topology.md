# Road topology and vertical evidence

`tools/singapore-full/roads/topology.py` is a Python 3.11 standard-library module. It analyzes complete projected OSM GeoJSON before tile ownership or rasterization. It does not write Minecraft blocks, invent road connections, add default zebra crossings, or derive bridge heights from OSM layers.

## Public interface

```python
result = analyze_topology(feature_collection_or_feature_iterable,
    ground_y=0, terrain_sampler=None, y_offset=0,
    tolerance=1e-7, spatial_cell_m=128)
manifest_fragment = result.to_dict()

# Raster callers must evaluate each sample position, not reuse a way's first Y.
policy = vertical_policy(feature, x=world_x, z=world_z,
    ground_y=0, terrain_sampler=sample_terrain, y_offset=0)
if policy.allow_surface:
    # Provisional writes may be allowed by the caller's diagnostic mode.
    # Accepted 1:1 geometry additionally requires policy.accepted_geometry.
    surface_y = policy.y
record = policy.to_dict()
```

Input geometry is `LineString` for complete road ways, or `Point` for mapped crossing nodes. Coordinates are already transformed as **x = EPSG:3414 easting, z = 60000 - northing**, at one metre per block. This module performs no projection and cannot certify source accuracy. Optional third coordinates are ignored; they are never implicitly treated as surveyed height.

Features require unique stable `id` (or `properties.osm_id` / `properties.id`). Tags may be direct `properties` or nested `properties.tags`; nested tags take precedence. `properties.nodes`, when present, must align exactly with the complete way coordinates. Incomplete arrays, duplicate feature IDs and non-finite positions fail explicitly. Ways are sorted by ID. MultiLineString inputs must first be normalized into complete source ways by the importer; this module does not reconstruct them.

## Junctions

`TopologyResult.intersections` contains deterministic **pairwise** records. A junction with three roads therefore has three pairs. Each record preserves source way IDs, floating coordinates, integer block ownership via `floor(x), floor(z)`, evidence, node references, relation and uncertainties.

- `at_grade`: an explicit shared OSM node with compatible tags. This describes mapped topology; it does not establish surveyed height.
- `transition`: both ways share an endpoint but their vertical sections differ, such as a bridge and its approach. Preserve connectivity and obtain a measured ramp profile separately.
- `grade_separated`: incompatible layers or structure classes overlap geometrically without shared nodes. Do not connect or flatten these roads.
- `geometric_candidate`: surface lines intersect but lack a shared OSM node. Keep the candidate for source review; never silently create a routable junction.
- `unresolved`: conflicting shared nodes, invalid layers, or overlapping structures whose connectivity is unproven.

Numerical tolerance is in metres and is not a road snapping radius. A spatial grid limits comparisons to nearby segments. Shared node IDs are processed before geometric candidates so explicit evidence takes precedence. Collinear overlapping lines do not become invented crossings. Self-intersections within one way are not returned as inter-way junctions. Inconsistent coordinates attached to the same node ID produce diagnostics.

## Vertical contract

`VerticalPolicy` exposes `way_id`, `mode`, `layer`, `y`, `allow_surface`, `accepted_geometry`, `provenance`, and `uncertainties`. `TopologyResult.vertical_policies` samples the first road vertex only for a compact diagnostic inventory. **It must never be used as a constant height profile for a whole way.**

The terrain callable accepts `(x,z)` and returns `elevation_m`, `datum`, `semantics`, `surveyed`, and `source_id`, or `None`. Supported ground semantics are `bare_earth`, `estimated_ground` and `road_surface`. `surface_dsm` includes roofs and vegetation and is withheld as known road ground. Source elevation and explicit `y_offset` are combined then quantized half away from zero. A supplied datum is recorded; this module does not transform EGM96 to EGM2008 or any other datum. The caller must supply compatible data and offset.

`ground_y=0` produces a **provisional**, explicitly inferred surface for ordinary roads, with `accepted_geometry=False`. Valid but unsurveyed ground samples are similarly diagnostic. Accepted geometry requires appropriate semantics, source and datum metadata, and `surveyed=True`. Source tags `ele`, `height`, `maxheight` and `layer` do not independently satisfy that gate. Generic OSM `height` is not road-surface altitude, and a legal clearance is not bridge deck height.

Bridge decks and tunnel floors remain unresolved unless `properties.vertical_evidence` contains a surveyed sample with `semantics='bridge_deck'` or `'tunnel_floor'`, plus `position:[x,z]`, explicit `sample_radius_m`, elevation, datum and source ID. Evidence is only valid inside that radius. One measured point is never extended along the whole way. An unmeasured structure never receives the provisional flat-ground fallback.

Tunnels always have `allow_surface=False`, even when a measured floor exists. A separate volume-aware tunnel builder may consume the measured floor; the surface pass must never fill the tunnel at ground level. `tunnel=building_passage` is a covered passage potentially at street level, and `covered=yes` alone does not mean underground. Nonzero layers without a known structure are unresolved rather than treated as metres. Invalid layers and conflicting bridge/tunnel tags remain blocked.

## Crossings and paint

Only mapped `highway=crossing` points and path ways explicitly tagged `footway=crossing`, `cycleway=crossing`, etc. produce `MappedCrossing` records. `crossing=no` produces none. Source geometry and tags are preserved; association uses exact node references or numerical geometric coincidence, never a guessed nearby-road snap.

`crossing=traffic_signals` does not establish painted markings. Explicit `crossing:markings=zebra` or legacy `crossing=zebra` establish zebra style; `crossing:markings=no` or `crossing=unmarked` establish no paint. Other explicit marking styles are retained as their original values. `crossing:markings=yes` means marked but unspecified, not zebra.

A point crossing has no known extent or orientation, so `paint_geometry_available=False`. A crossing way has a usable paint footprint only if a positive explicit metre `width` is present and markings are specified. The resulting flag means source axis and width are available; a renderer still must honor the source marking style, vertical policy and source accuracy. No stripe spacing or road width is invented here.

## Verification

```text
python -m unittest discover -s tools/singapore-full/roads/tests -p test_topology.py -v
```

Focused cases cover shared-node junctions, candidate-only geometric intersections, bridge crossings and abutments, conflicting topology, tunnel surface exclusion, measured-radius boundaries, DSM rejection, provisional ground provenance, building passages, absent versus mapped crossings, explicit marking styles, deterministic ordering/bucket size, and invalid reference arrays. Fixtures are deliberately synthetic semantic cases, not a claim of measured Singapore validation. Production OSM accuracy and measured road profiles remain separate gates.

## Source semantics

Rules above follow the OpenStreetMap primary documentation: [layer](https://wiki.openstreetmap.org/wiki/Key:layer), [bridge](https://wiki.openstreetmap.org/wiki/Key:bridge), [tunnel](https://wiki.openstreetmap.org/wiki/Key:tunnel), [elevation](https://wiki.openstreetmap.org/wiki/Key:ele), [height](https://wiki.openstreetmap.org/wiki/Key:height), [crossing nodes](https://wiki.openstreetmap.org/wiki/Tag:highway=crossing), and [crossing properties](https://wiki.openstreetmap.org/wiki/Key:crossing). Numerical intersection, evidence gates and block ownership are implementation policies built on those tag meanings.
