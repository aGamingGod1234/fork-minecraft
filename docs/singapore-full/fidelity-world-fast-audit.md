# Exact fast source-run world oracle

The separate validate-world-fast.mjs oracle compares every block in a bounded core using vertical intervals. It retains full Y[-64,320) coverage and the modern world-settings guard. Tiny fixture equivalence passes; actual 256 m and 1024 m runs await the coordinator's resource lease.

## Interface

Run from a worktree containing the existing validate-world-settings.mjs:

    node tools/singapore-full/validate-world-fast.mjs --world WORLD --bounds xMin,zMin,xMax,zMax --runs INPUT.jsonl --out OUTPUT.json

Repeat --runs for multiple source files. In the isolated height-gates worktree, supply --settings-validator PATH/TO/validate-world-settings.mjs. This dependency is explicit and its SHA256 is recorded. There is no alternate settings schema.

Bounds must be chunk-aligned and contain at most 1,048,576 columns. A 1024 m core compares exactly 4096 chunks and 402,653,184 logical cells. Halo blocks do not enter that comparison. All active-overworld region files, including halo-only files, are hashed and their chunk coordinates, header slots and DataVersion checked. allocatedChunkCount can therefore exceed comparedCoreChunkCount.

## Exactness

The background is bedrock at Y=-4, dirt at [-3,0), grass at Y=0 and air elsewhere. Explicit source runs override background. Layer is mandatory: terrain 10, landcover 20, water 30, road 40, building 50 or bridge 60; corresponding names are accepted. Arbitrary nonempty sourceClass values are retained, including the actual generator's estimated class.

Per-column source intervals split at every endpoint. The highest active layer wins. Different states at that layer fail; fully hidden lower conflicts remain diagnostic. Bridge air can clear lower solids. Complete block properties participate in equality. Missing sections mean air; missing chunks fail.

Every output column is compared, including cells without source runs. Uniform section skipping first checks all 4096 decoded palette indices. Mixed sections are converted to maximal state intervals. Intersecting unequal intervals count every mismatched cell without per-cell string lookup or hashing. Heightmaps use the exact padded 9-bit WORLD_SURFACE encoding and expected highest non-air block. Spawn requires clear feet/head and dry solid support.

Malformed palettes, duplicate sections, invalid packed data, wrong global coordinates/slots and chunk DataVersion mismatches fail. Palettes are limited to 1..4096 entries before unpacking, preventing Uint16 palette-index truncation. Minecraft air, cave_air and void_air remain distinct block states for equality; all three count as air only for heightmaps and spawn.

## Memory and hashes

Source runs use 12-byte records in fixed 768 KiB typed-array pages, plus two 4 MiB column-index arrays at 1024 squared. Growth adds pages without copying old arrays. Retained run storage is capped at 512 MiB. Interned full states are capped at 65,536 entries and a conservative 64 MiB text/object estimate. Individual JSONL records are limited to 1 MiB characters. One compressed chunk is decoded at a time, with a 64 MiB decompression cap.

These limits support a 2 GiB process-memory target; a measured 1024 m peak is still required before claiming that target has passed. peakRssBytes is the process high-water RSS reported by Node, not an isolated per-call sample. Tiny suite peak includes its malformed-NBT fixture and scalar-equivalence setup.

Input SHA256 is calculated from the exact streamed bytes parsed. Region SHA256 is streamed before and after comparison and changes fail validation. File evidence binds all active-overworld regions, although only core blocks are compared. worldFiles.path is a region basename, resolved under worldSettings.regionDirectory.

expectedIntervalSha256 and actualIntervalSha256 use the explicitly versioned fork.column-intervals.v1 traversal and canonical complete states. They are not the prior oracle's expanded x,z,y block hashes. Optional --legacy-hashes true computes those old hashes only for <=256-column equivalence fixtures. Actual/core acceptance depends on exact comparisons, not hash equality alone.

## Report contract and verification

Result fields include status, errors, bounds, comparedCells, mismatchedCells, chunkCount, comparedCoreChunkCount, allocatedChunkCount, missingColumns, heightmapMismatches, spawnClear, spawn, dataVersion, inputErrors, metadataErrors and sameLayerConflictingCells. inputs and sourceFiles contain the same {path,sha256,bytes} records. worldFiles binds region bytes. Runtime, terrain, facade accuracy and full-world acceptance remain false.

Run the focused suite with:

    node tools/singapore-full/validate-world-fast.test.mjs PATH/validate-world-settings.mjs PATH/validate-feature-world.mjs docs/singapore-full/fidelity-world-fast-tests.json

Fourteen tiny 16x16 cases cover negative global coordinates, bridge-air carving, hidden and exposed conflicts, unexpected non-air at Y=300 in a source-empty section, corrupted heightmaps, wrong chunks, absent modern settings, full property equality, invalid off-window input, Y=-64 and Y=319, rejection beyond Y=320, unsupported layers and palette-index overflow. Compatible fixtures match the scalar oracle's expanded block hashes and exact mismatch counts. Failed missing-chunk diagnostics may omit conflicts in that unreadable chunk, while both oracles reject the world.

No real benchmark or runtime process was launched by this implementation assignment.
