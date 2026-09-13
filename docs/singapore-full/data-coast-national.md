# National coastline mask

This artifact derives coastal land-side and sea-side polygons from the pinned regional OpenStreetMap PBF. It is separate from administrative boundaries, inland-water features, elevation and world generation.

The frozen PBF contains 3,477 coastline ways and 252,420 referenced coastline nodes. The Singapore processing rectangle retains 415 complete ways. Their 16,884 clipped segments form 106 planar faces before clipping to the corrected administrative scope. The polygon-only output contains 64 features, including an explicit outside-scope unknown area. Two zero-area scope-boundary remnants are omitted; the retained polygons and classified areas are unchanged.

Within the corrected scope, the mask classifies 774,220,366.562 m² on the coastal land side and 864,845,882.419 m² on the sea side. Inland reservoirs and rivers remain on the coastal land side until the separate water layer is applied. These are derived mapped areas, not an official measurement of Singapore's dry land or sovereignty.

## Frozen artifacts

Private directory: `data/coast-mask/national-v2`.

| File | SHA-256 |
| --- | --- |
| coast-source.json | 04d8b8f65d2aab8740aa21001658ca074503a431de35d591b9070a30231f6d9b |
| coast-mask-en.geojson | 302c79bb35570912e04599fbcfaa170bef119f06eac1e00cb37d193c0b4c9b6b |
| coast-mask-xz.geojson | 85db7807ad75ea14ab7ef0aaedfd0f195fdd729c97a40c16f200dd3843db676d |

The regional source SHA-256 is `01e16a33157689db74c401f4a6e9204526883369b999e3c92a561ac4a5a78699`. The scope SHA-256 is `aa88ef3ad093817290ca48180fd21d00d1532fdfe89e9a10dab8fac3006676d1`, the separately audited administrative mask with the two Middle Rocks land polygons excluded.

Processing bounds in EPSG:3414 metres are [-2798.2187661255084, 11615.294587171073, 111120.70113909859, 56082.13786430602]. The coordinate and boundary rules are in [data-coast-build.md](data-coast-build.md). Outside the scope, classification remains unknown.

## Measured resource use

One foreground process, PID 50252, ran from 2026-09-13 09:24:04 UTC to 09:25:06 UTC. It was pinned to one logical CPU. Elapsed time was 62.01 seconds and peak working set was 167,354,368 bytes. A watchdog enforced a 2 GiB working-set limit, 180-second deadline, 8 GiB free-system-memory floor and 100 GiB free-disk floor. The process exited successfully with the source data unchanged.

The source-derived geometry and independent validation receipts are separate gates. Ring closure does not establish that every real shoreline is mapped. The OSM administrative mask also retains the previously documented South Ledge jurisdiction qualification. No mask receipt authorizes a claim of complete real-world or sovereign-territory fidelity.

The optional core classification artifact annotates the existing 1,716 planned cores by coastal land-side, sea-side and unknown area. It does not modify the job queue and does not count any core as generated or validated Minecraft output.

The polygon-only v2 rebuild reused the frozen 1 MB coast source, with no second PBF scan. PID 29684 completed it in 3.67 seconds, using 79,507,456 bytes peak working set on one CPU. The original v1 artifacts and their independent audit remain immutable.

The independent source-geometry audit found 103 valid directed rings. The remaining 25 ways form two open chains with all four endpoints outside the processing domain; there is no internal open endpoint. Across 101,304 directed probes there were zero known-class contradictions. Changi overlap disagreed by 0 m², and the EN/XZ transformation matched exactly. One GEOS overlay required a documented 1-micrometre audit-only precision fallback; original geometry was used for source direction and coordinate checks.

The final source-geometry gate is PASS, with separate scope and raster qualifications. The public evidence index is [data-coast-national-results.json](data-coast-national-results.json). The polygon normalization receipt verifies all 64 retained feature objects exactly, with zero class-area change. Current focused tests pass: nine builder checks, twenty strict-ring/face checks, eight national checks and eight core-coverage checks.

The final annotation binds mask SHA-256 85db7807ad75ea14ab7ef0aaedfd0f195fdd729c97a40c16f200dd3843db676d. Its 1,716 cores comprise 561 coastal-land-side cores, 410 mixed-coast cores and 745 pure-sea cores. These are source classifications, not completed world tiles. The raw annotation retains its as-written candidate label; the accompanying normalization and source-geometry receipts establish its final source binding without rewriting the frozen artifact.
