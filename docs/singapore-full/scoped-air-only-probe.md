# Scoped AIR_ONLY runtime probe

`tools/singapore-full/merge/scoped_runtime_probe.py` checks one newly generated
neighbor of a new, isolated one-chunk fixture. It does not broaden the district
runtime whitelist or edit accepted worlds.

The immutable source is
`fork-singapore-full/merged/scoped-air-only-probe-v1/world`, with bounds
`[30208,30208,30224,30224)`, mapped chunk `[1888,1888]`, and absent target chunk
`[1889,1888]`. The runtime copy is separately created at
`fork-singapore-full/runtime-check/scoped-air-only-probe-v1/world`.

Pass `--spec <JSON>` with these fields:

```json
{
  "candidate": "<absolute immutable fixture world path>",
  "writerManifestPath": "<absolute writer JSON path>",
  "writerManifestSha256": "<SHA-256>",
  "profileReceiptPath": "<absolute exported profile JSON path>",
  "profileReceiptSha256": "<SHA-256>",
  "targetChunk": [1889, 1888],
  "admissionPath": "<absolute immutable queue admission JSON path>",
  "admissionSha256": "<SHA-256>"
}
```

`--prepare-only` performs source checks without creating a runtime attempt or
launching Java. The writer must pin every actual file, declare one chunk and
DataVersion 4790, and embed `unmappedGenerator` equal to the separately exported
`scoped-unmapped-worldgen-profile` receipt (`profileId: flat-air-only-v1`). The
runner checks compressed and typed settings hashes and independently verifies
that only the five permitted generator fields differ from pristine source
settings. The target must be absent; mapped spawn must have a solid floor and
air at feet/head.

A runtime launch additionally requires a pinned shared admission containing
`acceptedByCoordinator: true`, `processorOffset: 8`, `cpuThreads: 1`, and
`memoryGiB: 3`. The coordinator supplies the actual reservation ID alongside
those fields. The probe uses the existing server JAR and previously accepted
EULA file, localhost port 25579, BelowNormal priority, and a Windows Job Object
with a 3 GiB aggregate limit. Free RAM and disk floors are 8 GiB and 100 GiB.
The 180-second limit reserves 20 seconds for owned cleanup.

Acceptance requires actual loaded markers for both chunks, actual block checks
for spawn floor/feet/head, retained ordered stdin, clean save/shutdown, zero
exit, and verified process death. After shutdown, the exact target chunk must
have modern `minecraft:full` status, all 24 sections, all 98,304 cells equal to
canonical `minecraft:air`, and all four required 37-long heightmaps entirely
zero. Protochunks, missing sections, hidden solid cells, changed source files,
or changed generator metadata fail. Java's actual peak working set is retained.

Evidence stays in the separate runtime attempt. `runtime-receipt.json` binds
the finalized runtime state, spec, stdin transcript, console, target NBT, and
unchanged-source result. A passing receipt covers this one neighbor and spawn;
`fullWorldAccepted` and `fullFidelity` remain false. No Java launch is authorized
by the existence of the runner or its tests.
