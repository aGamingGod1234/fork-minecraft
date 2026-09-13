# Height fidelity audit

The two real 128 m SVY21 tile artifacts remain unaccepted for complete building-height fidelity. This report measures their NBT blocks and audits the frozen district source. It does not accept seams, terrain, building identity or a full Singapore world.

The source SHA256 is 1218ed4f5103caa13c49feb457dbf9c8131bdd0eb065cfed235a0e5aabdef2f9. It contains 13,958 elements and 1,209 building/building-part elements: 1,015 positive mapped meter heights, 73 levels-only elements, 103 with neither, and 18 unsupported nonpositive heights, all height=0. Zero-height outlines may be placeholders with separate tower parts. These are element counts, not distinct buildings. The source's maximum mapped height is 283 m, One Raffles Place Tower 1, way/325793773. Mapped does not mean surveyed or current.

Three source conflicts are explicit. Two parts, way/463857990 and way/463857991, have height=280 with min_height=282 and 281 respectively, so their declared bottoms exceed their total height. A third conflict is The Fullerton Hotel, way/46595395, has height=25 and building:height=37. The audit retains all conflicting values. It does not repair that conflict or replace unsupported height tags with floor-count estimates. The only elevation tags are restaurant and rooftop-viewpoint POIs. Neither establishes ground elevation or a vertical datum.

## Actual NBT measurements

| Tile | Non-air Y range | Mapped footprint intersections | Fully rendered footprints | No compatible top column |
|---|---|---:|---:|---:|
| svy21_x29712_z30496_s128_h32 | -64 through 250 | 57 | 39 | 9 |
| svy21_x29840_z30496_s128_h32 | -64 through 285 | 39 | 26 | 11 |

All non-air blocks in the actual region artifacts were scanned. Padded region chunks count toward the measured block range but do not count as geographic coverage. Evidence binds each manifest and measured region with SHA256.

Footprint columns use the independently implemented SVY21 projection, round source vertices to global blocks, and subtract the manifest render origin for local NBT coordinates. Only interior column centers of complete closed source ways are tested. Intersecting footprints can be clipped by the tile boundary; that fact is recorded. Relations and incomplete polygons remain unmeasured.

Column maxima include overlapping tower parts, roofs, trees and any other non-air blocks. A compatible column is a diagnostic, never proof that the building has the correct height. For example, Samsung's mapped 172 m footprint has top Y=173 throughout the sampled interior. CapitaGreen's 242 m footprint has median Y=243 and maximum Y=250. Prudential Tower's outline is tagged 20 m, while a separate tower part is 145 m; the outline's high columns are not by themselves evidence of a bad source height. AIA Tower's 83 m outline samples Y=121..122 in the eastern tile and requires part/overlap review.

## Vertical interpretation and clipping

Height is a dimension relative to local ground. It is not an absolute altitude. min_height describes an elevated bottom, and roof:height describes a roof portion; neither is added to an explicit total height. Levels remain counts rather than measured heights. Available Arnis source snippets implement different floor cycles and a ground-floor bonus, and do not prove the exact installed binary's source provenance.

Both manifests declare flat-provisional ground Y=0, surveyed=false. No real terrain datum is accepted. All supported source heights fit that provisional build volume, and neither measured region reaches Y=319. This establishes no observed ceiling candidate in these two tiles, not zero clipping across Singapore or correct heights for all buildings.

Minecraft block positions run from Y=-64 through Y=319. Their occupied vertical volume is [-64,320). Source roof datum at 319..320 is reported as an unresolved block-face convention. Roof datum above 320 cannot fit at that ground offset. The focused test uses the actual maximum mapped height, 283 m, with a hypothetical ground Y=64: roof datum 347 cannot fit. Changing terrain requires rerunning the check with validated per-feature ground values.

The tool keeps fullWorldAccepted and releaseAccepted false. Building clipping remains unaccepted because identity, parts, unknown heights, relation geometry and surveyed ground are incomplete. The separate failed seam gate remains failed.

## Reproduce on Desktop

Run node tools/singapore-full/validate-height.test.mjs.

Run node tools/singapore-full/validate-height.mjs SOURCE.json --manifest WEST/tile-manifest.json --manifest EAST/tile-manifest.json --out docs/singapore-full/fidelity-height-evidence.json.

SOURCE.json is the frozen district render-complete.json, not a national coverage claim. Optional --ground-y changes only the source-wide hypothetical datum audit; each tile comparison uses its own manifest groundY. The JSON evidence includes full per-feature diagnostics and actual artifact hashes. No current game, Laptop or canonical source was changed.

## Deterministic column-run contract

Optional --runs FILE.jsonl audits the new generator's {x,z,yMin,yMax,block,properties,featureId,geometryKind,sourceClass,layer} records. yMin is inclusive and yMax exclusive: -64..320 is the full representable vertical extent. Properties are optional string-valued objects. Layer is required explicitly as 10, 20, 30, 40, 50, 60 or the matching name terrain, landcover, water, road, building, bridge. Missing layers, numeric strings and other values fail the raw writer contract; geometryKind does not supply a default. Air runs still participate in conflict detection but are excluded from occupied height. Source classes remain mapped, inferred or unknown, and building source IDs bind back to the frozen OSM height inventory when available.

The streaming audit rejects malformed metadata and out-of-volume runs. It records generated per-feature vertical extents and expected source roof datum separately. Each column is split at every run endpoint before resolving its highest active layer. Different states among that layer's winners fail structural validation, including conflicts within one feature and explicit air runs. Same-state duplicates are allowed. Higher numeric layers win over lower layers. Conflicting states fully hidden beneath a higher layer are recorded as occludedLowerLayerConflicts and do not fail. A conflict exposed over only part of its interval still fails over the exposed part. Counts describe resolved vertical intervals rather than input run pairs. Resolution is independent of input order and matches the deterministic writer contract.

Crossing review keeps up to 262,144 columns in memory and marks completeCrossingAudit=false and structurallyValid=false if that limit is reached. HeightAgreementAccepted, fullWorldAccepted and releaseAccepted remain false even when run structure is valid. At this audit's completion no real deterministic strip JSONL was available; run-contract checks use focused synthetic boundary, provenance and overlap fixtures. Existing measured NBT evidence applies to the two Arnis tiles only.
