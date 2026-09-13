# Connected save assembly

`tools/singapore-full/merge/grow.py` makes a separate immutable Minecraft 26.1.2 save from accepted global-coordinate cores. It crops source halos and copies verified chunk NBT without translation. Buildings are not regenerated during assembly.

```text
python tools/singapore-full/merge/grow.py --plan grow-input.json --world <private-full-project>/merged/grow-cbd-east-v1/world --manifest <private-full-project>/merged/grow-cbd-east-v1/grow-manifest.json --job-lease grow-lease.json
```

The plan uses `schemaVersion: 1`, `spawn_source_id`, and the source descriptors in [merge-grow-contract.md](merge-grow-contract.md). The chosen spawn source supplies only `level.dat` and `data/minecraft/world_gen_settings.dat`. Its modern spawn must remain within its owned core, above a solid floor and beneath two air blocks. Every source must already have a bound actual structural gate and complete roads/water evidence. A buildings-only import cannot enter a published snapshot.

The initial CBD core is `[29696,29696,30720,30720)`, a 1024-metre square, excluding its 128-metre generation halo. Twelve adjacent 256-metre east cores occupy `[30720,29696,31488,30720)`. CBD alone is a valid first snapshot. Each later addition requires a fresh snapshot identifier; existing saves are never overwritten. Extent is a bounding box, not a claim that holes or the whole country are covered.

The command requires an explicit current Desktop coordinator lease:

```json
{
  "id": "assigned-by-coordinator",
  "machine": "Desktop",
  "approvedBy": "/root/singapore_full_coordinator",
  "heavyJobSlot": "A",
  "cpuThreads": 1,
  "outputRoot": "<private-full-project>/merged/grow-cbd-east-v1",
  "startsUtc": "<assigned-start>",
  "expiresUtc": "<assigned-expiry>"
}
```

The external queue/owned runner enforces the process-tree memory cap, CPU affinity and host free-memory/disk floors. The CLI binds the exact new snapshot root and lease hash, rechecks the lease before promotion, and rejects existing output worlds or manifests. It does not acquire a heavy-job slot itself.

Source worlds and evidence are hashed before copying and checked again afterward. Region writing retains one decoded chunk at a time, verifies the written canonical NBT, and rejects duplicate ownership or missing owned chunks. A private failed staging directory is retained for diagnosis; no completed manifest is emitted. Successful promotion produces `STRUCTURAL_PASS_RUNTIME_PENDING`, with source and output hashes, exact chunk counts, owned cores and safe-spawn proof.

This receipt does not establish runtime load, client appearance, agents/gameplay, accurate terrain elevations, or complete Singapore coverage. A headless load check on another copy and client review are separate gates. Preserve the filming world throughout.

Validation: 32 focused growth tests passed, including actual modern config files in the CLI integration fixture, adjacent cores sharing region files, conflicting halos, negative coordinates, source mutation, unsafe spawn, missing coverage, expired leases and overwrite refusal. These fixtures are synthetic and are not production-world acceptance evidence.
