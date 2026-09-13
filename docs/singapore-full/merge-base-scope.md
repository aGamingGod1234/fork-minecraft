# Scoped provisional ground

The optional `--base-scope descriptor.json` writer mode fixes automatic ground outside Singapore. The default mode remains byte-compatible with the existing flat provisional writer. Existing jobs, accepted bundles, world files and public ZIPs are unchanged.

## Contract

The descriptor selects global EPSG:3414 coordinates with X=easting, Z=60000?northing, one block per metre. Every column is sampled at its center (x+0.5,z+0.5). Features are validated as nonempty, valid Polygon/MultiPolygon geometry before union; no topology repair is performed. Inputs must carry the expected X/Z frame and match their explicit SHA256 pins. An empty foreign collection is permitted; an empty country is rejected.

`BaseScope(path).classify((x0,z0,x1,z1))` returns row-major class names over positive half-open integer bounds. `chunk(cx,cz)` evaluates 256 columns. Masks are prepared once and evaluated using vectorized Shapely/NumPy operations. There are no Python per-column Shapely Point/polygon tests, and evaluation is limited to 1,048,576 columns per call.

Precedence is foreign exclusion, then outside country, then unknown or conflicting coast evidence, then known land or sea. `covers` includes polygon boundaries, including foreign boundaries. Country holes are outside the domain. Land/sea overlap, uncovered coast, and unrecognized coast source classes remain unknown.

| Column | Automatic provisional terrain |
| --- | --- |
| Known land inside country, outside foreign mask | Bedrock Y[-4,-3), dirt Y[-3,0), grass Y[0,1) |
| Known sea inside country, outside foreign mask | Bedrock Y[-4,-3), dirt Y[-3,0); no automatic water or grass |
| Unknown coast, outside country, or foreign | AIR/void throughout the vertical range |

The sea soil is a provisional construction substrate. It is not measured depth, ocean floor or bathymetry. Mapped sea water is a separate layer-30 run at Y[0,1). The writer records whether such a run exists in each sea column; it does not invent one. Missing sea water keeps that coverage incomplete.

Existing explicit terrain replacement and feature-layer precedence are preserved within the national domain. Bridges over mapped sea remain supported. Occupied feature runs outside country or on a foreign exclusion fail before final publication; AIR carve runs remain permitted there. Unknown columns receive no automatic ground, while explicit mapped feature evidence inside the country is retained and remains unaccepted. All four heightmaps remain zero in fully empty columns.

Scoped spawn selection requires a known land column with a solid highest block, space above it and below the world ceiling. All-sea or all-unknown jobs without a known-land spawn fail instead of inventing a spawn. A separate queue integration decision is required for generating sea-only worlds.

## Descriptor

Paths may be absolute or relative to the descriptor. This example names the verified source files, without encoding a machine-specific root.

```json
{
  "schemaVersion": 1,
  "kind": "country-coast-base-scope",
  "coordinateFrame": {
    "crs": "EPSG:3414",
    "x": "easting",
    "z": "60000-northing",
    "blocksPerMeter": 1
  },
  "sampling": "block-center",
  "country": {
    "path": "country-world.geojson",
    "sha256": "c6f5341c951026e19bee851de79d2438c623785c81c6c93e9bc92e5a7d25403b"
  },
  "foreignExclusions": {
    "path": "foreign-exclusions-world.geojson",
    "sha256": "b1880b77c8bca9846987cb93339d68700d5d558d343758e027bc433ae4c1185f"
  },
  "coast": {
    "path": "coast-mask-xz.geojson",
    "sha256": "85db7807ad75ea14ab7ef0aaedfd0f195fdd729c97a40c16f200dd3843db676d"
  }
}
```

Country pin was verified against `data/coverage-derived-260912/country-mask-world.geojson`; foreign pin against the existing benchmark frozen mask descriptor; coast pin against `data/coast-mask/national-v2/coast-mask-xz.geojson`. Production must use immutable verified copies and bind the descriptor, new code module, writer, configuration, and actual Python/native runtime through the existing versioned job contract. The tested Desktop data runtime supplies Shapely 2.1.2 and NumPy 2.4.6. The scope receipt records the imported versions; version strings alone are not a substitute for the pipeline runtime file hashes.

## Output and acceptance

The new `groundProfileId` is `country-coast-provisional-y0-v1`. `baseScope` contains the descriptor hash, mask hashes and byte lengths, coordinate/sampling contract, class counts and sea-water-layer counts. `baseTerrainScopeClipped=true` means automatic terrain passed this explicit mask policy. It does not establish national ground accuracy. `unknownCoastColumnsAccepted` is false whenever any unknown column occurs. Source fidelity, coast fidelity, actual ground, bathymetry, assembly and runtime acceptance remain false.

The existing `validate-world-fast.mjs` oracle assumes global grass/soil. It cannot accept this profile unchanged. A separately versioned validator must reproduce the policy using the actual pinned masks and bind its evidence to the generated output. The containment sidecar only establishes country/foreign eligibility for an owned rectangle; it does not prove this default-terrain behavior.

## Focused verification

Twelve new deterministic tests cover land, sea, foreign precedence, unknown and conflicting coast evidence, pinned hashes, invalid geometry, coordinate declarations, negative coordinates, adjacent chunk seams, empty heightmaps, mapped water and bridges, occupied runs outside scope, streamed manifests and safe spawn failure. An optional bounded real-source fixture verifies the frozen national country edge and Middle Rocks with the exact three pins above. The twelve existing writer tests remain applicable to legacy mode.

Run with the existing geospatial runtime:

```text
python -m unittest discover -s tools/singapore-full/merge/tests -p test_base_scope.py -v
python -m unittest discover -s tools/singapore-full/merge/tests -p test_overlay.py -v
```

Set `BASE_SCOPE_ACTUAL_FULL` to the private data root to include the bounded real-mask fixture. It reads the verified masks, samples 1,280 columns and builds one in-memory edge chunk. It does not launch national generation or change source data. Tiny synthetic streamed fixtures write only to temporary test directories.
