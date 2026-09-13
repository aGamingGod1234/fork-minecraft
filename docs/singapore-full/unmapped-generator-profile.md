# Unmapped Overworld generator profile

`flat-air-only-v1` is an explicit profile for future scoped Minecraft 26.1.2 jobs.
It changes what Minecraft generates beyond supplied chunks. It does not change
mapped chunks, generate ocean, or add a platform.

Existing previews and the default writer path retain their original generator.
The writer may select this profile only through explicit `--unmapped-generator
AIR_ONLY` with a verified `--base-scope`. Existing worlds are not migration targets.

## API

```python
settings, receipt = prepare_worldgen_profile(
    source_bytes,
    profile="flat-air-only-v1",
    base_scope=scope,  # an actual constructor-verified BaseScope
    context={
        "sourceSettings": {"path": source_path, "bytes": source_size, "sha256": source_sha},
        "outputSettingsPath": output_path,
        "baseScopeDescriptor": {"path": descriptor_path, "bytes": descriptor_size,
                                "sha256": descriptor_sha},
        "jobId": job_id,  # optional additional context
    },
)
```

The function returns a cloned typed NBT document and a preparation receipt. It
writes no files. The exact compressed input bytes must match the source record.
The scope descriptor is read again and must match both its record and the
constructor-verified scope receipt. Use the same pinned BaseScope module and
geospatial runtime as the writer. A caller-supplied boolean or receipt dictionary
does not substitute for constructing BaseScope.

The source must be a gzip-compressed modern external
`data/minecraft/world_gen_settings.dat`: root `DataVersion` is INT 4790,
`data` is a compound, and its `seed` is LONG. Its Overworld must already be
`minecraft:flat` with the `minecraft:plains` biome and correctly typed settings.
Malformed or legacy input is rejected.

## Exact mutation contract

Only these paths can change:

- `/data/dimensions/minecraft:overworld/generator/settings/layers` becomes
  LIST of COMPOUND containing one `block: STRING minecraft:air`,
  `height: INT 1` layer.
- The same settings compound's `features` and `lakes` become BYTE 0.
- Its `structure_overrides` becomes an empty LIST of STRING.
- `/data/generate_structures` becomes BYTE 0.

An optional legacy `structures` key must be absent or an empty compound.
The existing biome, generator type, seed, other dimensions, root name and all
metadata outside the mutation paths remain unchanged. The transform verifies
this by restoring the allowed fields and comparing the complete encoded typed
document with the source. Already matching values are retained.

## Receipt and later verification

The receipt kind is `scoped-unmapped-worldgen-profile`. It records source and
prepared output file pins, the verified scope descriptor and masks, the complete
context and its hash, actual mutation paths, and preservation hashes.

`outputSettings.sha256` covers the prepared gzip bytes produced by
`gzip.compress(write_nbt(settings), mtime=0)`, matching `anvil.write_level_dat`.
`outputNbtSha256` separately covers the uncompressed encoded NBT. The prepared
output record has `materialized: false`; its existence is not implied.

The writer must verify the written file against the prepared pin. A separate
scoped settings guard checks the actual file, and a later isolated runtime
probe must generate a previously absent chunk and verify its blocks and
heightmaps. Preparation leaves `generatedNewChunksTested`,
`runtimeLoadAccepted`, `fullWorldAccepted` and `fullFidelity` false.

This profile does not establish surveyed ground, coastline completeness,
bathymetry, or complete Singapore coverage.
