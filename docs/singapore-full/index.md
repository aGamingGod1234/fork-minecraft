# Singapore source index

`tools/singapore-full/index/spatial_index.py` converts an accepted Overpass JSON source into a reusable SQLite spatial index. It uses Python 3.11's standard library, including SQLite RTree. No package installation or full JSON load is needed.

```powershell
py -3.11 tools/singapore-full/index/spatial_index.py build --source SOURCE.json --index VERSION/source.sqlite --expected-sha256 ACCEPTED_SHA256
py -3.11 tools/singapore-full/index/spatial_index.py export --index VERSION/source.sqlite --bounds 29680 29216 30000 29536 --output VERSION/pilot.json
py -3.11 -m unittest discover -s tools/singapore-full/index -p "test_*.py" -v
```

The coordinator must accept the source and grant the resource lease before a country-sized build. Always provide its accepted SHA256 in production. The index validates every reference and rehashes the source after building; appended or out-of-order nodes, ways and relations are supported. It publishes the SQLite file only after completion. A failed build retains a diagnostic `.building` file and does not replace an existing index.

Bounds are **min easting, min northing, max easting, max northing in EPSG:3414 metres**. The project grid is `X = easting`, `Z = 60000 - northing`; therefore an X/Z box `[x0,z0,x1,z1]` becomes `[x0,60000-z1,x1,60000-z0]`. The caller supplies its desired halo in these bounds. Selection uses full original feature bounding boxes and then complete references, never tile-clipped geometry.

Exports include:

- Every intersecting way and all of its nodes, including nodes outside the requested box.
- Intersecting tagged nodes and geometric `multipolygon` or `building` relations.
- Complete descendants and geometric parent relations of selected ways/relations. Global route relations do not trigger expansion.
- Building outlines and their contained sibling parts. This checks polygon rings, courtyard holes and crossing edges. Explicit building relations remain authoritative even when a geometric containment test cannot close a ring.

The output is ordinary Overpass JSON with original element fields and tags preserved. Dependency elements whose source tags were removed remain untagged. The adjacent `.json.manifest.json` records the original source hash, query bounds, closure policy, exact subset/closure hash, deterministic export identity, class counts and `referenceComplete`. Elements are sorted by type and ID and serialized canonically, so repeat exports have identical bytes.

The exporter does **not** clip country boundaries or authorize source fidelity. Complete features can cross the national mask or tile boundary. The renderer must clip generated output cells against both the accepted country mask and the tile core. Raw geometry, heights and source relationships remain intact for that rendering step.

An existing matching export is served after validating every required manifest field, its exact source/query/schema identity, and the subset hash. The closure hash must equal that subset hash and `referenceComplete` must be true. A streaming pass over the cached subset independently checks its element/class counts and that every referenced element exists. This never opens the original country source. Modified files, incomplete or contradictory metadata, and mismatched identities fail rather than silently overwrite. Existing complete manifests remain compatible; no national index rebuild or export rewrite is needed. Use a new versioned output path when any identity input changes. `SCHEMA` must be bumped if index interpretation or closure semantics change.

Validated on 13 September 2026 against accepted national extract v4:

| Check | Result |
| --- | --- |
| Source SHA256 | `9fdaf1f6dcf3629a3dfaaac24d5837f03f97bbfea59fe7875d2c221b3ae21d0d` |
| Source elements | 2,654,278 nodes; 442,500 ways; 3,128 relations |
| Index build | 39.547 seconds; 166 MiB observed peak RSS |
| SQLite size and integrity | 890,773,504 bytes; `quick_check = ok` |
| Pilot bounds | `[29680,29216,30000,29536]` |
| Complete pilot closure | 40,037 nodes; 2,074 ways; 410 relations |
| First export / cached request | 3.719 seconds / 0.015 seconds |
| Pilot subset SHA256 | `12709086eb05a0d1e7a334a1c4d1518afd7ff6a7404f7a2253c0b5411ec5198d` |
| Focused automated checks | 16 passed, including missing references, appended nodes, boundary crossings, full relation/sibling closure, source-offline cache reuse, incomplete/contradictory cache metadata rejection, independent cached reference validation and RTree rounding |

Timing is one observed Desktop run, not a general throughput guarantee. The 0.015-second cached timing predates the additional cached-subset reference/count validation; it is not a measurement of the stronger validator. The retained private build receipt records the process and memory/disk floors. Its original PowerShell exit-code capture returned null; atomic completion, exact source counts and an independent SQLite integrity check established successful completion.
