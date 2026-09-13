# Source-attributed roads and mapped surfaces

The reusable Python modules in `tools/singapore-full/roads/` convert complete OSM objects into deterministic one-metre road and mapped-surface cells. They do not write Minecraft regions, modify the hackathon world, extract the national dataset, or infer land from the Singapore administrative boundary.

The source adapter takes Overpass-style JSON and an injected `project(lon, lat)` function returning EPSG:3414 easting and northing. Production uses the shared SVY21 projection; output coordinates are X=easting and Z=60000-northing. Whole ways retain their OSM node references. Multipolygon ways are assembled by matching endpoint node IDs, including inner rings. Missing references, open rings and ambiguous hole ownership produce diagnostics instead of invented closure. Geometry is not rounded or clipped at this stage.

```python
from roads import adapt_osm, analyze_topology, emit_surface_runs

features = adapt_osm(overpass_json, project, source_sha256=source_hash).to_dict()
topology = analyze_topology(features, ground_y=0).to_dict()
output = emit_surface_runs(
    features, (29712, 30496, 29840, 30624),
    provisional_surface_y=0,
    clip_mask=country_membership,
)
```

The caller supplies the complete source features selected for a tile, its half-open integer core and an optional administrative membership callable sampled at block centres. The adapter never interprets this callable as surveyed land or coastline. `emit_surface_runs` returns runs, per-road width/vertical evidence, diagnostics and a deterministic run digest. The separate pipeline owns writing JSONL, resolving cross-layer conflicts and converting runs to Anvil blocks.

Each run follows the common contract: `x`, `z`, `yMin`, exclusive `yMax`, namespaced `block`, optional blockstate `properties`, `featureId`, `geometryKind`, `sourceClass` and numeric `layer`. Frozen precedence is terrain 10, landcover 20, water 30, roads 40, buildings 50 and accepted bridge surfaces 60. Runs are ordered by layer, source feature ID and coordinates. The merger must report unresolved conflicts; ordering alone does not prove physical correctness.

The first joined strip explicitly uses `flat-provisional-y0-v1`: surface Y=0, standing Y=1, vertical datum unknown, no surveyed-ground acceptance. Omitting `provisional_surface_y` leaves unresolved ground and water surfaces unwritten. Terrain policy uses `nearest_half_away_from_zero` when converting accepted elevation plus the explicit world offset. A DSM roof/vegetation sample is not accepted road ground. OSM `layer`, building `height` and an isolated `ele` tag do not establish bridge decks or tunnel floor profiles. Unknown bridges are withheld, and tunnel excavation is outside this surface pass.

Road width follows explicit `width`/`width:carriageway` when parseable. Estimated widths, widths derived from lane counts and highway defaults retain inferred provenance. Width precision is separate from positional precision. Road and landuse palettes are stylized Minecraft choices, not claims that every real material was surveyed. Mapped crossings preserve source geometry and uncertain paint extent; no zebra crossing is invented at an ordinary junction. Current surface runs do not add crosswalk stripe geometry.

Focused verification runs on Desktop:

```powershell
python -m unittest discover -s tools/singapore-full/roads/tests -v
```

The 45 tests cover width units and provenance, complete-reference conversion, multipolygon holes and independently tagged inner islands, core unions, shared candidate budgets, conservative bridge/tunnel policy, mapped crossings, administrative clipping and actual local OSM features. Fixtures include Market Street way 22921315, footway 633419647, canal 886588582 and Promontory grass area 116801285. Their source file SHA256 is `4d91cfadc9378f98b1a28f132d34d7ce6756beb2200ec7cc8e2d080945148b05`; OSM contributors and ODbL attribution remain attached. Passing these tests does not accept national completeness, final merged block seams, true terrain or facade identity.
