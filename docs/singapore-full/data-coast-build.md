# Coastline-derived land and sea

The Changi mask uses four complete OSM ways tagged `natural=coastline`, including every original node reference and coordinate. The source is the frozen, reference-complete Changi spatial export. Administrative geometry only limits the permitted scope; it supplies no land evidence.

OSM coastline direction places land on the left and sea on the right, as documented by the [OSM coastline specification](https://wiki.openstreetmap.org/wiki/Tag:natural%3Dcoastline). It represents mean high-water springs. Inland lakes, terrain height and bathymetry require separate sources.

## Derivation

The builder clips actual projected shorelines to an explicit processing rectangle and polygonizes their linework with the rectangle boundary. Rectangle edges are clipping support, never artificial shoreline. It assigns each face using small left/right probes from actual directed shore segments. A probe must lie inside the face and its source segment midpoint must touch that face boundary.

Faces with conflicting or absent shoreline evidence remain unknown. Open chains ending inside the processing domain are not joined; their unresolved influence remains unknown. A way with invalid self-intersecting geometry currently holds the entire derived mask unknown. No source geometry is repaired or snapped.

A separate strict audit accounts for every raw way in a closed directed ring or an unresolved component. It checks node identities, orientation, intersections and nesting. A local tile may contain an open chain whose real geometry crosses the processing boundary; the strict ring audit remains unresolved while directed face evidence can classify that bounded tile.

## Runtime contract

The output `coast-mask-xz.geojson` is a FeatureCollection with `coordinate_space: minecraft_xz`. Coordinates are derived from EPSG:3414:

- X = easting.
- Z = 60000 - northing.
- Sample a Minecraft column at X + 0.5, Z + 0.5.
- Read `properties.class`: `land`, `sea` or `unknown`.

Collect the labels of all polygons covering the sample. Exactly one distinct label determines the classification. Multiple labels or no covering polygon mean unknown. This handles shared shoreline boundaries without silently preferring land. Unknown and outside-scope columns must not be advertised as mapped land.

The EN GeoJSON is retained for independent comparison. The builder never emits terrain heights or water columns. The terrain adapter owns any declared sea-surface and provisional depth policy; those values are not measured bathymetry.

## Changi artifact

The processing bounds are E 44928–46208, N 40416–41696, equivalent to X 44928–46208, Z 18304–19584. The 1280 m square includes a 1024 m core and 128 m halo.

The source export is SHA-256 `5dc2b60b7b3b196a59a30e6a09a145f3c376d5748aed77a403824463641d7e6b`. The first mask contains 86 clipped source segments and three faces: 1,351,153.684 m² mapped land and 287,246.316 m² mapped sea. Domain coverage gap and overlap are zero. The source scope is the separately audited administrative mask minus Middle Rocks; this is not a sovereignty determination.

Runtime mask SHA-256: `85478c14c21d4cd4175e1a4b1cf3902ef40c6d14e0fd9953c3b3595f10c7e109`. Its private location is `data/coast-mask/changi-v1/coast-mask-xz.geojson`.

## Reproduction

Use the pinned environment from [data-setup.md](data-setup.md). The builder requires pyproj and Shapely; regional PBF acquisition additionally uses pyosmium. Outputs require a new directory and sources require an explicit SHA-256.

```text
python tools/singapore-full/data-coast-build.py --self-test
python tools/singapore-full/data-coast-audit.py --self-test
python tools/singapore-full/data-coast-build.py --source /cache/changi.json --source-sha256 5dc2b60b7b3b196a59a30e6a09a145f3c376d5748aed77a403824463641d7e6b --source-kind json --bounds-en 44928 40416 46208 41696 --scope /cache/singapore-admin-mask-minus-middle-rocks.geojson --out /cache/coast-mask/changi-new
```

Raw inputs and generated national masks stay outside Git. OSM attribution and ODbL terms are retained in every source receipt and in the existing data licence notices. Classification is derived from mapped shorelines; it is not a claim that every real coastal change or island is mapped.

The spatial export must retain its adjacent `.manifest.json`; requested bounds must fit the source export coverage. Nine builder/source-coverage checks and twenty strict-ring/face tests pass. The Changi independent audit passes 516 directional probes and verifies exact source geometry and EN/XZ agreement; its public receipt is [data-coast-changi.json](data-coast-changi.json).
