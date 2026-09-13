# Joined Singapore preview: independent structural result

The corrected deterministic 256 by 256 block world passes the joined-world structural and MC26 metadata gate. A separate copy also passes real Minecraft 26.1.2 headless loading, with all 256 chunks and sixteen non-ground building and seam positions verified. The earlier Arnis tile seam failure remains historical evidence for the abandoned generation route.

This preview covers the four 128 m cores beginning at global SVY21-derived coordinates [29712,30496], [29840,30496], [29712,30624] and [29840,30624]. X is SVY21 easting and Z is 60000 minus SVY21 northing. The joined half-open bounds are [29712,30496,29968,30752], containing 256 chunks and 65,536 square meters of core area. This is a small district preview, not a complete Singapore world.

## What passed

The independent Node NBT reader decoded all six shared halo and diagonal-corner intersections between the four generated worlds. All 22,020,096 compared block states across Y=-64 through Y=319 match. Chunk coordinates, region-file coordinates and header slots agree. Frozen run-file and world-file hashes match their manifests.

The joined world was then compared with a separate block oracle derived from its 181,821 owned-core JSONL runs, including every background and air cell. All 25,165,824 cells match. There are no missing columns or conflicting winning layers. All WORLD_SURFACE heightmaps agree with the expected columns, chunk DataVersion values agree with level.dat, and the spawn at [29838,1,30624] has clear feet/head cells over non-air, non-fluid support.

The oracle uses the specified highest-active-layer rule. Equal-layer conflicting winning states fail; fully hidden lower-layer conflicts are reported separately. Its behavior was first checked against a persistent one-chunk synthetic fixture: 98,304 cells matched, including a bridge-layer air opening and two hidden lower-layer material conflicts. Synthetic evidence does not count as geographic coverage.

The current structural receipt is `fidelity-joined-gate.json`. It binds writer manifest SHA256 `6924EEB071FCD1CADACE84ACD053ADADC7CC5A363A92E8A2523EE752579E7776` and pipeline result SHA256 `FEC53D72B363D66D1E58AD33BFBD64016A883B352C5B7272E584620543DB4F56`. Detailed independent block/seam results are retained alongside it.

## MC26 correction

The first runtime attempt exposed omitted external world-generation settings, a stale modern Data.spawn.pos, and legacy region placement. The first oracle checked legacy spawn fields, so its combined spawn/layout acceptance was revoked. The serialized block-content and heightmap measurements remain valid.

The corrected candidate stores regions under dimensions/minecraft/overworld/region, supplies version-matched data/minecraft/world_gen_settings.dat with generate_structures disabled, enables only vanilla datapacks, and sets the active modern spawn to [29838,1,30624]. Both region files and the owned source runs are byte-identical to the fully checked artifacts. A focused metadata/dependency audit therefore rebinds the existing block proof without repeating millions of unchanged comparisons. The historical gate and its revocation remain recorded.

## Actual runtime result

The completed runtime receipt records 256 of 256 loaded chunks, sixteen of sixteen exact non-ground wall, glass, roof and seam sentinels, a clean exit with no surviving server process, and unchanged original candidate files. The copy ran for 13.703 seconds; the maximum observed OS peak working set was 577,130,496 bytes (shutdown was not separately sampled). This small startup test is not a national generation throughput benchmark.

The independent receipt binding in fidelity-joined-runtime.json rehashes the server jar, console, structural gate, writer manifest and all four original world files. It also verifies the receipt's canonical content checksum, each expected marker in actual server log output, and the actual block state at every sentinel position using the independent NBT reader. Two review-found false-PASS cases are now regression checks: changing a sentinel to diamond at Y=300, or replacing the canonical receipt digest with zeroes, both fail. Runtime receipt SHA256 is 8E9EE91002F12AA3231D3E9AF70F8147CC6EABA75A18A2A31C2CDB693B0ED814. Launcher-state acceptance remains false because that snapshot precedes the completed checker; the final runtime receipt supplies acceptance.

## What this does not accept

The surface is still flat at Y=0. Terrain, facade accuracy, full-country coverage, visual inspection and AI behavior remain unaccepted. Runtime loading passed separately for this exact candidate. Every rendered run in this candidate carries sourceClass `estimated`; mapped source geometry does not make generated appearance surveyed or photographic. The pilot uses 142 projected building features from a frozen national-source extract, with source SHA256 `12709086eb05a0d1e7a334a1c4d1518afd7ff6a7404f7a2253c0b5411ec5198d`.

No source world was edited by the checks. Runtime testing must use a separate copy. The original source and output artifacts remain frozen. Later changes to roads, terrain, materials or the generator need a new bounded run and applicable checks.

## Reproduce

```powershell
node tools/singapore-full/validate-feature-seams.mjs PATH/TO/pipeline-result.json seam-audit.json
node tools/singapore-full/validate-feature-world.mjs --world PATH/TO/joined/world --bounds 29712,30496,29968,30752 --runs PATH/TO/joined/owned-core.runs.jsonl --out joined-audit.json
```

The block oracle reads global NBT coordinates without a tile offset. Its audit region is deliberately bounded. Run it only within the coordinator's available validation resource slot.
