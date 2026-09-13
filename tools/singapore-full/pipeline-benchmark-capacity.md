# Internal capacity review: 1024 m benchmark tiles

Scope: read-only audit of current Desktop `full-singapore-pipeline` adapter and merge writer. No generation started and no shared source changed.

## Required settings

- One benchmark tile per job: `coreSize:1024`, `halo:128`, chunk-aligned `coreOrigin`.
- Actual render extent is 1280 by 1280 blocks, or **6400 chunks**. `pipeline-adapter.mjs` currently omits the writer's `--max-chunks` option, leaving `overlay.py` at **512**. Pass `--max-chunks 6400`; otherwise this fails before world creation.
- A valid Desktop coordinator lease is required for more than four chunks. Lease must cover the complete stage duration, because the writer validates it both before parsing and before writing.
- Explicit benchmark stage timeout is necessary. Adapter defaults to 180 seconds and clamps at 1800 seconds. Use the approved benchmark budget, at most 1800, and report actual elapsed time. No measured 6400-chunk runtime exists yet.
- The adapter already sets numerical-library thread environment variables to 1. This does not enforce the requested **4 GB process-tree memory ceiling**. `coordinatorPeakRssBytes` measures only Node's coordinator, not the Python renderer/writer. Parent scheduler must retain one-CPU/4-GB monitoring or enforcement and the two-heavy-job global limit.

## Concrete memory risk

`write_overlay` retains every input Run in `columns`, then every generated chunk NBT tree in `chunks`. Region grouping preserves those trees. Reopening a written region allocates another decoded region. Spawn selection additionally collects up to **1,638,400 tuples** before `min(candidates)`.

Small read-only Desktop allocation measurement with current writer, using the first 2000 existing tile-sw building runs:

| Allocation | Measured retained Python heap |
| --- | ---: |
| 2000 parsed Runs plus 371-column index | 1,135,927 bytes |
| Average per sampled run including index | 568 bytes |
| One empty provisional-ground chunk | 78,236 bytes |
| 6400 such empty chunks, arithmetic lower bound | 500,710,400 bytes |

These are tracemalloc allocations, not process RSS or a guaranteed peak. Dense building chunks are larger. Three million similarly structured runs alone would retain roughly 1.70 GB before chunk trees, spawn tuples, parsed JSON temporaries, region reopening, interpreter overhead and other pipeline stages. Therefore passing a larger chunk cap alone does not establish 4-GB feasibility.

## Bounded writer change proposed to merge lead

1. Validate and spool runs into per-region buckets inside the fresh attempt directory, recording input hashes and counts.
2. Load, resolve and write one region of at most 1024 chunks at a time. Reopen/check it, retain its digest/receipt and release both runs and chunk trees before the next region.
3. Maintain the best spawn candidate incrementally instead of collecting every column tuple.
4. Preserve immutable attempts. A late conflict may leave an explicitly incomplete private world; only write the completion manifest after all regions and dependencies pass. Never relabel a partial directory as accepted.
5. Record child peak RSS and stage elapsed time. Streaming writer does not by itself resolve the building renderer's separate in-memory run-list/serialization cost; dense benchmark telemetry must cover that process too.

No source fidelity claims change. Flat provisional terrain, estimated heights/facades, overlaps and independent acceptance gates remain explicit.
