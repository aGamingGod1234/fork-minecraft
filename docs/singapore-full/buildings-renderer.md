# Deterministic building layer

`tools/singapore-full/buildings/renderer.py` compiles complete projected OSM building features into sparse vertical runs. Coordinates are global integer Minecraft X/Z: `x = SVY21 easting`, `z = 60000 - SVY21 northing`, one horizontal block per metre. Tile cores are half-open. Each output run has `x`, `z`, inclusive `yMin`, exclusive `yMax`, namespaced `block`, `featureId`, `geometryKind`, `sourceClass`, and `layer: building`.

The renderer constructs each complete footprint and roof before clipping output to a tile. Facade patterns use global coordinates and stable typed source IDs. A tile edge does not create a wall or restart a window pattern. Related building parts replace their outline globally; elevated gaps stay empty. The output requires clean terrain underneath. It does not remove previous Arnis buildings.

Example, with provisional flat ground explicitly declared:

```powershell
python tools/singapore-full/buildings/renderer.py --input buildings-projected.geojson --tile 29712 30496 29840 30624 --ground-y 0 --ground-source-class flat-provisional-y0-v1 --output building-runs.jsonl --manifest building-manifest.json
```

Input is a GeoJSON FeatureCollection already projected to the stated game X/Z coordinates. Preserve each complete Polygon/MultiPolygon including holes. Use typed `feature.id` such as `way/146335667`; keep OSM tags in `properties.tags` or directly in properties. Building parts need `properties.parent_identity` linking the actual outline feature ID. The compiler brings all linked sibling parts into scope before clipping. Unresolved parts are reported and rendered independently.

Explicit OSM height includes the roof. A building uses one common ground datum. `building:levels` is an estimate of height, and absent height uses a labeled default. Invalid/out-of-range heights raise rather than silently clip. `--invalid-feature-policy report` is an explicit provisional fallback: excluded IDs/reasons appear in the manifest and `completeSourceGeometryAccepted` is false. A successful process exit then proves usable output, not complete source acceptance. Never treat a roof/vegetation DSM as bare ground. The first profile is provisional surface Y=0 and standing Y=1, not surveyed terrain.

OSM type, material, colour and levels inform building palettes. Missing surfaces, windows, floor bands and broad housing/shophouse/office typologies remain estimates. Mapped tags translated into Minecraft blocks do not prove an exact photographed facade. Facade metadata retains URA/HDB typology reference URLs; no reference imagery is redistributed. Pitched roofs are labeled bounding-box approximations. See [OSM Simple 3D Buildings](https://wiki.openstreetmap.org/wiki/Simple_3D_Buildings) for height and parts semantics.

Run focused verification with `python -m unittest discover -s tools/singapore-full/buildings -p "test_*.py"`. Coverage includes holes, fractional projected geometry, whole-versus-split exact output, global facade patterns, parts overlap/air gaps, source contradictions and vertical overflow. Small real-source strip receipts belong in private build output, separate from source.
