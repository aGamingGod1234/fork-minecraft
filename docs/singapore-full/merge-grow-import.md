# Mini PC to Desktop run import contract

Mini PC exports building inputs. Desktop owns the combined world writer, road and
water integration, country-mask clipping, connected-world growth and acceptance.
This contract is not evidence that any new tile or part of Singapore is complete.

## Coordinates and run records

Use the fixed SVY21 frame, EPSG:3414: `x = easting`, `z = 60000 - northing`,
one block per metre, positive x east and positive z south. Coordinates in the
transferred runs are already global integer blocks. Import performs no origin
shift, reprojection, scaling or second rounding.

Each UTF-8 JSONL record describes one vertical column segment:

```json
{"x":30770,"z":29696,"yMin":0,"yMax":1,"block":"minecraft:smooth_stone","featureId":"way/741934166","geometryKind":"floor","sourceClass":"estimated","layer":"building"}
```

This is a schema example, not a claim about accepted coverage. Required fields:

| Field | Contract |
| --- | --- |
| `x`, `z` | Integer global block coordinates. |
| `yMin`, `yMax` | Integers; `yMin` included, `yMax` excluded; `-64 <= yMin < yMax <= 320`. |
| `block` | Namespaced vanilla block supported by the Desktop writer's palette and heightmap semantics. |
| `properties` | Optional object of string keys and string values. |
| `featureId` | Nonempty stable source identifier; preserve relation/way identity. |
| `geometryKind` | Nonempty classification such as floor, wall or roof. |
| `sourceClass` | Nonempty provenance classification; estimated details remain estimated. |
| `layer` | `building` or 50 for this producer. Desktop also accepts terrain 10, landcover 20, water 30, road 40 and bridge 60. |

The Desktop resolver gives the highest active layer precedence. Conflicting
materials at the same winning layer fail; file order cannot select a winner.
Keep source overlap resolutions, exclusions and warnings in the evidence rather
than silently converting them into exact-source claims.

## Ownership and package binding

Every job must identify `coreBounds` and `renderBounds` as
`[minX,minZ,maxXExclusive,maxZExclusive]` in global blocks. Bounds must be
16-block aligned, the render extent must contain the core, and neighboring
owned cores must not overlap. Halos supply context and never own output chunks.
Do not derive bounds from occupied building columns or padded MCA extents.

The current building manifest uses `schema="fork-building-runs-v1"`,
`coordinateSystem="EPSG:3414"`, `axisMapping="x=E,z=60000-N"`,
`horizontalBlocksPerMetre=1` and `tileCore` for the owned core.
Its `verticalRangeInclusive=[-64,319]` describes the allowed block heights;
individual run `yMax` remains exclusive. Obtain render bounds from the frozen
job/source-closure evidence; `tileCore` alone does not establish a halo.

The immutable transfer package must bind:

- Job/tile IDs, coordinate frame, core/render bounds and ground profile.
- Original source snapshot, extracted source closure, projected geometry and
  their byte lengths and SHA-256 hashes, with source attribution and licenses.
- Generator code revision plus entrypoint/dependency hashes, exact arguments,
  projection configuration and relevant source versions.
- Run files, building manifests and every evidence receipt by relative path,
  byte length and SHA-256. Reject absolute paths and parent-directory escapes.
- Run counts, source feature counts, exclusions, overlap resolutions, warnings
  and measured execution results. Preserve incomplete or failed qualifications.

Verify source and transferred input hashes before consumption and record the
exact inputs in Desktop receipts. Keep received bytes immutable. If an adapter
changes encoding, crops records or normalizes fields, write a new artifact and
bind both its input hash and output hash.

There are two distinct hashes in the current producer. The building manifest's
`runsSha256` hashes compact, key-sorted JSON records joined by LF with no final
newline. The transfer binding's `jobs[].runsFile.sha256` hashes the actual file
bytes, including spaces and line endings. Verify each using its defined
representation; the semantic record hash does not replace the transport hash.

## Desktop integration and acceptance

An imported building package remains geometry-only input. In particular,
`requiresCleanTerrainLayer=true`, provisional ground, unknown actual terrain,
or `fullWorldAccepted=false` must survive import. A geometry/performance pass
does not accept roads, water, terrain, facades or the assembled world.

Desktop must combine the imported building runs with the accepted road, water,
terrain and country-mask inputs for the same global extent before producing a
candidate. Record the hashes of all those inputs, the exact ownership crop and
every resolved layer conflict. Apply country-mask clipping on Desktop when the
producer declares `sourceCountryMaskApplied=false`.

The candidate then needs the exact run-to-NBT writer check, independent
structural/source and shared-boundary gates, and complete owned-chunk coverage
before growth. The final connected world still needs its target-version runtime
gate. A failed or unqualified source-geometry result
cannot be upgraded merely because the world loads. Modern Minecraft 26 world
configuration must use the external world-generation settings, modern spawn and
dimension region layout described in [the writer contract](merge-run-writer.md).

Only qualified Desktop worlds may enter `grow_regions.merge_regions()`.
Growth copies globally positioned owned chunks without translation, rejects
holes/duplicate ownership and preserves canonical NBT through verified streaming
writes into a new region directory. It does not repair missing roads, source
geometry or failed fidelity evidence. Final receipts must distinguish imported
inputs, written candidates, structurally accepted regions and runtime acceptance;
none of those implies a completed one-to-one Singapore.
