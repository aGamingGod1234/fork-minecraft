# Whole-Singapore fidelity audit

The initial pipeline milestone passes for two independently checked 128 m SVY21 tiles. This does not accept the complete Singapore world.

The two tiles are `svy21_x29712_z30496_s128_h32` and `svy21_x29840_z30496_s128_h32`. Each has a 128 m core, a 32 m halo, and a 192 m rendered square. Arnis writes one padded 512-block region containing 1,024 chunk headers; this padding is not evidence of 512 m of generated geographic coverage. The smoke source is the earlier cached district OSM extraction, not yet the new national extract.

## Measurements completed

- All source and generated output byte counts and SHA256 hashes match.
- Region sector offsets/counts are in bounds; declared region and chunk counts match their headers.
- Each tile has 16 independently recomputed source-coordinate controls. The maximum errors after rounding to the nearest block are 0.570493 m and 0.656613 m. Both are below the 0.707107 m two-axis rounding limit.
- Projection inverse error is below 1.5e-14 degrees. East maps to positive X; north maps to negative Z.
- The validator uses an independent WGS84 Transverse Mercator series, while the pipeline uses proj4. Nine published SLA SiReNT station pairs agree within 0.000633 m. The projection origin also agrees.
- The former spherical equirectangular proposal is provisional and rejected for the real-world 1:1 gate. Its spherical north/south meter differs from an ellipsoidal meter near Singapore. Production uses EPSG:3414.

SLA defines the [SVY21 parameters](https://app.sla.gov.sg/sirent/About/PlaneCoordinateSystem) and publishes the [reference station coordinate pairs](https://app.sla.gov.sg/sirent/Page/ReferenceStations). Grid X is SVY21 easting; grid Z is 60000 minus SVY21 northing. One Minecraft block represents one projected SVY21 meter. Real curved surfaces and details smaller than one meter are necessarily quantized.

## Fidelity still unaccepted

The actual shared-halo block comparison FAILED: 45,240 mismatches among 2,654,208 compared cells. Structural differences extend into tall buildings. Coordinate agreement is not enough to accept assembly; keep the seam gate failed until the generator or assembly is corrected and the complete overlap is rechecked.

Terrain remains flat at Y=0. Surveyed terrain, its vertical datum, every building's source height versus generated top, and clipping checks are incomplete. The target Minecraft range is Y=-64 through Y=319. Building height tags are relative building dimensions and must not be confused with terrain elevation or absolute roof height. A future height report must declare the minimum/maximum generated Y and zero clipped known features before the clipping gate passes.

Facade material/color tags do not establish photographic facade identity. The original district source contains 1,209 building/building-part elements: 1,015 with supported meter heights, 73 with levels only, 103 without heights, and 18 unsupported height representations. These are element counts, not necessarily distinct physical buildings. Only 217 have a building material or color tag. No image-to-facade comparison has been accepted. All 13,958 retained OSM elements have complete references, but the earlier extraction excluded 11 incomplete rendered relations; validating retained references does not restore those holes.

A complete national extract alone does not establish a complete built world. Coverage must be measured as the union of verified core tiles intersected with the frozen Singapore boundary, including offshore islands. Record missing tiles, overlap, feature truncation, excluded relations and out-of-bound source references. Do not add halo area or padded region chunks to coverage. Adjacent tiles must share one coordinate system and must pass an actual block comparison throughout their shared rendered halo before assembly is accepted.

OSM-derived data must retain [OpenStreetMap attribution and ODbL terms](https://www.openstreetmap.org/copyright). [Arnis](https://github.com/louis-e/arnis) is Apache-2.0; generator licensing does not replace the separate data notices. Remote imagery, terrain and other datasets each need their own source, acquisition time, hash, resolution, license and datum records.

## Executable checks

Run on Desktop with Node:

```powershell
node tools/singapore-full/validate-fidelity.test.mjs
node tools/singapore-full/validate-fidelity.mjs manifest PATH/TO/tile-manifest.json --out audit.json
node tools/singapore-full/validate-fidelity.mjs osm PATH/TO/render-complete.json --out source-audit.json
```

Output file paths resolve relative to the manifest's `output.worldPath`; source paths can be absolute private paths. The schema is `tools/singapore-full/validate-manifest.schema.json`. `initialMilestoneAccepted` covers source integrity, generated artifact structure and coordinates. `fullWorldAccepted` and `releaseAccepted` remain false because this executable does not independently measure full-world terrain, appearance and assembled geometry. Unsupported full-fidelity claims remain visible as errors or unmet gates.

Audit evidence is recorded in `fidelity-evidence.json`. No current hackathon world, Laptop instance or canonical source was edited by this audit.
