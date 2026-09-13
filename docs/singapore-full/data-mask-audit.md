# Singapore country-mask audit

`tools/singapore-full/data-mask-audit.py` checks the actual OpenStreetMap Singapore administrative relation, its boundary PBF and an optional source PBF. It does not generate Minecraft chunks or modify source data.

The mask is an **administrative boundary with territorial-water geometry**, not a land polygon. Use coastline and elevation data to decide where land exists. Never fill the country bounding rectangle or all administrative water as terrain.

## Reproduce

Use the existing data environment with `osmium`, `shapely` and `pyproj`:

```text
python tools/singapore-full/data-mask-audit.py --self-test
python tools/singapore-full/data-mask-audit.py --mask <singapore-admin-mask.geojson> --boundary-pbf <singapore-admin-mask.osm.pbf> --source-pbf <malaysia-singapore-brunei-260912.osm.pbf> --output <audit.json>
```

Add `--export-exclusions <output-directory>` to create the explicit foreign-land exclusions, a separately named derived administrative mask, and their hash receipt. The original mask remains unchanged.

The script uses sequential readers and only retains the small boundary and selected island-way coordinates. C++ filters exclude unrelated objects before Python callbacks; the full 250 MB source audit ran in about five seconds on the prepared Desktop. It creates only the requested audit JSON. The source scan is optional; a run without it explicitly leaves source-relation equality and named-island evidence unverified.

The structural gate rejects invalid/non-polygon geometry, a rectangle masquerading as the country, missing OSM references, a dropped component or any topological mismatch from the boundary PBF. With a source PBF, it also checks that country relation version, tags and member references match the source. Named island/coastline witnesses provide source object IDs and component coverage. They are samples, not a survey of every island or every coastline segment. Exit status 0 means the checks passed, 1 means structural failure, and 2 means a land-scope finding requires review. Structural `boundary_integrity` remains separately reported.

## Actual 12 September 2026 source

- Country: [OSM relation 536780](https://www.openstreetmap.org/relation/536780), Singapore, `admin_level=2`, `ISO3166-1=SG`.
- GeoJSON SHA256: `5783CB68A03F3DCAE77BAF8C37CE96222944C03024007DF45A627900D9D61978`.
- Geometry: valid `MultiPolygon`, three disjoint components. Three components do **not** mean three islands.
- Boundary PBF: 1,395 nodes, 113 ways, six relations, no unresolved references. Country relation version 78, tags and all member references match the source PBF exactly; all 17 outer country ways reconstruct the GeoJSON exactly.
- Main administrative component: approximately **1,421.667 km²**, including mainland/offshore territory and water; this is not Singapore land area.
- Eastern component: approximately **216.613 km²** of maritime geometry around Pedra Branca.
- Small eastern component: approximately **0.756884 km²**, maritime geometry surrounding South Ledge.
- The bounding rectangle is about 4,751.761 km², **2.899 times** the administrative area of 1,639.037 km². Neither number is land area.

Source geometry checks cover mainland [Singapore Island relation 1769123](https://www.openstreetmap.org/relation/1769123), [Pulau Ubin 2243165](https://www.openstreetmap.org/relation/2243165), [Sentosa 9574725](https://www.openstreetmap.org/relation/9574725), [Pulau Semakau 9964061](https://www.openstreetmap.org/relation/9964061), and named coastlines for Bukom, both Hantu islands, Pawai, Satumu, Sekudu, Senang and Sudong. These are fully inside the main administrative component. Tekong is a named source-node witness, so its result proves that point's inclusion, not the entire island coastline. The JSON preserves the geometry kind so these evidence levels stay distinguishable.

Areas are calculated geodesically on the WGS84 ellipsoid. Coordinates are longitude, latitude, in degrees. Geometry is compared topologically, so ordering or ring direction alone does not fail the check.

## Remote components and boundaries

The Pedra Branca water component is assembled from ways [1147865283](https://www.openstreetmap.org/way/1147865283), [1147865296](https://www.openstreetmap.org/way/1147865296), [1147865302](https://www.openstreetmap.org/way/1147865302) and [1147865303](https://www.openstreetmap.org/way/1147865303). Their OSM tags identify maritime geometry. Pedra Branca's actual island coastline is [way 469394519](https://www.openstreetmap.org/way/469394519), a much smaller feature.

The third component is maritime [way 1453274362](https://www.openstreetmap.org/way/1453274362). Its identification with South Ledge is a geographic inference: it encloses the named South Ledge coastline [1146100464](https://www.openstreetmap.org/way/1146100464). Middle Rocks is separate: [site relation 18694563](https://www.openstreetmap.org/relation/18694563), coastlines 1359256910 and 1359256911.

Singapore's MFA records the ICJ decision that Pedra Branca belongs to Singapore, Middle Rocks belongs to Malaysia, and South Ledge belongs to the state in whose territorial waters it is located; the judgment did not delimit those waters. [MFA statement, July 2017](https://geneva-un.mfa.gov.sg/mission-updates/press-20170701-01-jul-2017/) A [January 2025 MFA reply](https://www.mfa.gov.sg/newsroom/press-statements-transcripts-and-photos/pq-jan-2025-written-07-jan-2025/) states that surrounding maritime delimitation remains outstanding.

Retain the source's provenance, but do not present OSM contributor geometry as proof of agreed maritime boundaries or label South Ledge's sovereignty definitively. This audit verifies source consistency, not legal title or survey completeness.

## Actual land-scope gate: review required

The source-consistency check passes, but the actual negative controls show **both Middle Rocks coastlines 1359256910 and 1359256911 are fully covered by component 2**. Middle Rocks is Malaysian under the cited ICJ account. The OSM Singapore maritime polygon therefore cannot be used blindly as a Singapore-only land filter.

The JSON reports `boundary_integrity=PASS`, `land_scope=REVIEW_REQUIRED`, `country_land_filter_accepted=false` and the exact offending source IDs. South Ledge also carries an unresolved-delimitation finding. The parent-approved derived artifacts exclude only the two named Middle Rocks land geometries. They do not redefine South Ledge or turn the administrative mask into a complete land survey. This audit does not change the extractor or original boundary.

Frozen artifacts, mirrored beside this document:

| Artifact | SHA256 |
| --- | --- |
| `data-mask-audit.exclusions.geojson` | `EC62589D40215C460635AD7523C803DCD1597CF935BE928DC6340B597D7B46AF` |
| `data-mask-audit.derived.geojson` | `AA88EF3AD093817290CA48180FD21D00D1532FDFE89E9A10DAB8FAC3006676D1` |
| `data-mask-audit.exclusions-receipt.json` | `83FFC608F1656A8F7CBA42217A881E2297DBCE2AC5902576E07E2E0BAD4B2DF4` |

For cell sampling, first reject any point for which **`exclusions.covers(point)`** is true, then apply the derived mask. This precedence matters at shared polygon boundaries. The derived mask has zero intersection with Middle Rocks' polygon interiors and retains the separate Pedra Branca and South Ledge source geometries. `LAND_SCOPE_REVIEW_REQUIRED` remains until the renderer applies the exclusions and its own negative control passes.

## Focused tests

Seven regression tests cover rejecting a rectangle, rejecting self-intersection, detecting loss of a remote component, detecting unresolved way/node references, refusing the wrong country identity, avoiding similarly named foreign islands, and preventing a country-only land claim when a known foreign land control is included. The actual-data run independently checks complete relation reconstruction and emits hashes and source witness results for review.
