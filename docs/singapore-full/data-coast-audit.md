# Independent coastline closure audit

This tool checks the original directed OSM node graph before treating any shoreline as a polygon. Every supplied raw way is assigned to one complete directed ring or explicitly reported unresolved. It never snaps endpoints, reverses ways, repairs polygons, or closes a chain along a straight or administrative boundary.

## Input and API

`audit(document)` returns `(report, mask_geojson)`. Input:

```json
{
  "crs": "EPSG:3414",
  "coordinate_space": "east_north",
  "bounds": [0, 0, 100000, 60000],
  "source": {"sha256": "original-source-hash"},
  "ways": [
    {"id": 123, "node_ids": [1, 2, 3, 1],
     "coordinates": [[1, 1], [2, 1], [1, 2], [1, 1]],
     "tags": {"natural": "coastline"}}
  ]
}
```

Supply every raw coastline way, including open and malformed ways. Raw OSM IDs must be positive integers; missing identity placeholders are rejected. Preserve raw node IDs and all original coordinates; do not clip ways first. Bounds describe the extraction domain, not a coastline. An optional `minecraft_xz` coordinate space transforms both coordinates and bounds using E=X, N=60000-Z. Output geometry is always projected EPSG:3414 east/north, explicitly identified as non-geographic GeoJSON.

```text
python tools/singapore-full/data-coast-audit.py --self-test
python tools/singapore-full/data-coast-audit.py source.json --report audit.json --mask mask.geojson
python tools/singapore-full/data-coast-audit.py source.json --report audit.json --mask mask.geojson --compare-mask candidate.geojson
```

The comparison input must declare `crs: "EPSG:3414"` or the equivalent GeoJSON named CRS and use east/north coordinates. Unknown classes or invalid polygons are rejected. It uses feature property `class` with `land`, `sea`, or `unknown` and EPSG:3414 geometry. The independent mask leaves the exterior unknown. A separate face-based builder may resolve more area, but positive-area opposite classifications within independently known faces fail comparison. Unresolved ways do not alone make the CLI fail: they are a required explicit result, not an ignored error.

## Checks and interpretation

- Connectivity uses raw node identity at all way nodes, including interior junctions. Every node in a component must have exactly one incoming and one outgoing segment. Reversed segments, branches, duplicate segments and disconnected endpoints remain unresolved.
- The component must traverse every edge exactly once and return to its original raw node. Repeated coordinates cannot substitute for missing identity.
- A complete ring must be simple, nondegenerate and valid. Crossings or touches between separate rings invalidate both; nested rings must alternate land and sea orientation.
- OSM direction establishes land to the left and water to the right. A positive signed area in east/north means a land interior. Mirroring northing into Minecraft Z reverses the sign, so the same land ring is clockwise in X/Z. No source orientation is silently corrected.
- Closed source rings may intersect extraction bounds and are clipped only after their closure is proven. Boundary contacts are reported. Domain edges are never counted as source shoreline.
- Unresolved source geometry entering a ring face makes the entire face unknown. Missing source geometry makes all faces unknown. This is deliberately conservative; absence of supplied coastline is not evidence of sea.
- Use `classify_point(document, mask, E, N)` for sample centers. A center exactly on a raw shoreline is unknown. Polygon boundaries alone cannot encode that zero-area rule reliably.
- Area masks are a geometric interpretation of supplied source topology and direction, not independent proof that OSM describes present-day physical shoreline correctly.

## Changi truth witnesses

Choose witnesses from actual extracted source ways and their IDs, not an administrative maritime polygon or hand-drawn rectangle. For each disputed reclaimed or coastal patch, record the original source hash, directed coastline way and adjacent node IDs, exact east/north coordinates, graph component/ring status, and a nearby source building/runway/landuse feature when present. Test sample centers on both sides of an actual oriented segment away from vertices; establish which side is land using its left normal in east/north, then check the corresponding Minecraft sample centers after the Z reflection. Keep points on coastlines or in unresolved faces unknown. A runway or building is corroborating source evidence, not a replacement boundary.

No live Changi claim is made by the synthetic fixtures. The source supplied by the data lead must establish those witnesses before an audit can assert that the actual map classifies them correctly.

## Verification and provenance

`--self-test` covers split rings, a reversed way, coincident-but-distinct endpoints, an interior branch, self-intersection, nested sea/island rings, wrong nesting orientation, mirrored coordinates, extraction boundary contact, unresolved interior lines, missing or malformed geometry, degenerate unresolved points, missing node identities, malformed tags, duplicate IDs, mask partition, comparison contradiction, and comparison schema validation.

Primary reference: [OpenStreetMap natural=coastline documentation](https://wiki.openstreetmap.org/wiki/Tag:natural%3Dcoastline), consulted 2026-09-13. It specifies end-to-start node connectivity and land-left direction; this audit applies those rules in projected east/north before the Minecraft coordinate reflection.

## Reproducible face verification

`verify_faces(document, en_mask, xz_mask, receipt, original_export_bytes, manifest, bundle_file_bytes)` returns the ring report plus `face_mask_audit` and the conservative ring mask. `verify_face_bundle(bundle_directory, original_export_path)` reads only those named inputs and the adjacent `.json.manifest.json`. This is a bounded benchmark check; nearest-segment clearance uses pairwise comparisons and is intended for small regional bundles.

```text
python tools/singapore-full/data-coast-audit.py --verify-faces BUNDLE_DIRECTORY --original-export EXPORT.json --report independent-audit.json --mask independent-ring-mask.geojson
```

The gate verifies every original coastline intersecting the extraction domain, original node order and tags, complete references, independently projected coordinates, hashes, extent, polygon validity, exact coverage and nonoverlap. It proves every interior mask boundary is supported by source shoreline and every clipped source segment is represented using exact geometry set differences. It then checks land-left/sea-right probes at three offsets on each clipped segment, all supplied face witnesses, and the exact EN-to-XZ geometry/class correspondence.

Open raw chains are reported separately from clipped faces. Both open endpoints must lie outside the domain for this resolved-face gate. A source segment whose probe is unknown or contradictory fails the gate, so this mode does not certify unresolved regional masks as complete. Ring-only unknown outside complete rings is expected and does not alone fail a face check.

The Changi audit run passed 516 probes on 86 clipped original segments and 12 stored witnesses. All four original coastline ways intersecting its domain had unchanged references, tags and coordinates; domain coverage, overlap, unsupported boundary length and EN/XZ transformation differences were zero. This statement is limited to the supplied original regional export, not proof that the upstream source omits no real-world shoreline.
