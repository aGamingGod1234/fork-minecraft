# Singapore tile merge coordinate contract

This contract preserves the fixed global metric grid used by the tile pipeline.
It does not establish surveyed terrain, complete buildings or visual fidelity.

## Coordinates and evidence

The horizontal grid is SVY21, EPSG:3414: Minecraft `x = easting`,
`z = 60000 - northing`, with one block per metre. Positive x is east and
positive z is south. The merge preserves each source y without translation.
The pipeline quantizes source coordinates once to the nearest global block.
The merge performs **no rounding, scaling, reprojection or origin adjustment**.

For the `global-grid-to-Arnis-local` adapter, global block position equals
Arnis-local position plus `tile.renderOrigin`. `TileTransform.from_manifest`
requires a generated schema-1 manifest, the exact fixed grid, a successful
coordinate agreement gate, all four corner control points, and the actual
Arnis `metadata.json` showing local projection, scale 1, minima 0, and maxima
equal to `tile.renderSize`. Every control point must agree exactly.
Missing, contradictory, fractional, or misaligned evidence fails.

Arnis rounds output storage up to 512-block regions. This is file padding,
not a displacement or evidence that the whole region belongs to the tile.
Metadata bounds describe the render extent; never derive the transform from
the `.mca` filename, its file count, or padded region dimensions.

## Core ownership

Each tile owns only `[coreX, coreX + coreSize) × [coreZ, coreZ + coreSize)`.
The west/north boundaries are included and east/south boundaries excluded.
The render halo and padding never own output blocks. Adjacent core boundaries
therefore neither duplicate nor drop a block. Overlapping cores are an error;
input order or last-writer selection must never settle an ambiguity.

Origins, core size and halo must all align exactly to 16-block chunks. This
version intentionally rejects a partial chunk rather than rounding its origin
or copying halo cells. Supporting nonaligned sources requires a separately
verified block reassembly implementation.

The inspected first real tile has core origin `(29712,30496)`, size 128,
halo 32 and render origin `(29680,30464)`. Its local core is `[32,160)` on
both axes: source chunks `[2,10)`, 64 chunks of the padded 1024. Its chunk
translation is `(1855,1904)`, producing global chunks x `[1857,1865)` and
z `[1906,1914)`. The adjacent tile starts x 29840, translating by
`(1863,1904)` and owning global x chunks `[1865,1873)`.

Negative chunk coordinates use floor division for region indexes and positive
modulo for region slots: chunk `(-1,-1)` belongs to region `(-1,-1)`, slot
`(31,31)`. Crossing a region boundary never changes the global origin.

## Python integration API

`TileTransform.from_manifest(manifest, metadata)` validates source evidence.
`core_source_chunks()` yields local `(cx,cz)` tuples in z-then-x order.
`translate_chunk(cx,cz)` returns the destination tuple and rejects halo or
padding chunks. `delta_chunks` supplies `(dx,dz)` **in chunk units** to
`nbt_io.translate_chunk(chunk, dx, dz)`. The NBT writer must update chunk and
block-entity/tick coordinates while preserving source blocks and heightmaps.
Unsupported entity or structure references must fail rather than stay stale.

`plan_ownership(transforms)` returns deterministic `ChunkAssignment` records
sorted by destination z, x, then tile ID. Record fields are `tile_id`, `source`
and `destination`; `region` and `region_slot` are derived tuple properties.
Duplicate IDs or duplicate destination ownership fail. This function is a
diagnostic coordinate plan, not permission to publish a world.

`to_descriptor()` returns the JSON-compatible keys `id`, `coreOrigin`,
`coreSize`, `halo`, `renderOrigin`, `renderSize`, `sourceCoreChunks`,
`deltaChunks`, and `destinationCoreChunks`. Bounds use
`[minX,minZ,maxXExclusive,maxZExclusive]`; chunk fields are in chunk units.
`COORDINATE_CONTRACT` is the shared manifest contract; `GRID` is the exact
pipeline grid. `svy21_to_block` accepts already quantized integer metres only.

## Assembly gate

Before any real assembly, verify source hashes and require an actual shared
halo block comparison for each adjacent pair. `require_seam_gate(receipt,
left_id, right_id)` rejects missing, stale-pair, zero-coverage, synthetic or
failed receipts. It requires `status=PASS`, `assemblyAccepted=true`, a positive
integer `compared`, zero integer `mismatches`, and zero structural mismatch
counts when present. Ownership cropping cannot repair disagreeing buildings.

The inspected h32 real pair currently has a **FAIL** receipt: 45,240 block
mismatches in 2,654,208 comparisons. Coordinate tests passing do not override
that gate. No real merged-world pass is claimed by this implementation.

Focused tests cover real-shape manifest mapping, adjacent cores, excluded
halo and padding, negative origins, region boundaries, strict input evidence,
overlap rejection, deterministic records and seam rejection.
