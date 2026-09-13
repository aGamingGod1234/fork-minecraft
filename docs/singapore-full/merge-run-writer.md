# Global block-run writer

`tools/singapore-full/merge/overlay.py` creates a new world from explicit, global-coordinate block runs. It does not copy the old Arnis scenery or mutate an existing save. The first strip uses the `flat-provisional-y0-v1` profile: bedrock at Y -4, dirt at -3 through -1, grass at 0 and player feet at 1. This is not surveyed terrain.

Input is one JSON object per line:

```json
{"x":29712,"z":30496,"yMin":1,"yMax":20,"block":"minecraft:glass","featureId":"way/123","geometryKind":"wall","sourceClass":"osm-explicit-height","layer":"building"}
```

Coordinates use EPSG:3414 with X equal to easting, Z equal to 60000 minus northing, and one block per metre. Vertical runs and output bounds are half-open. World bounds must be chunk aligned; Y is limited to [-64,320). Optional `properties` contain string key/value pairs. Only vanilla blocks with implemented heightmap semantics are accepted; unknown block types fail before world creation.

Layer order is terrain 10, landcover 20, water 30, road 40, building 50, bridge 60. The highest active layer wins independently of input order. Different materials tied at that highest layer are rejected. Identical duplicates are harmless. Lower-layer occlusion is counted in the receipt. Explicit terrain replaces only overlapping provisional ground intervals.

```text
python tools/singapore-full/merge/overlay.py
  --runs buildings.jsonl --runs roads.jsonl
  --world <new-attempt-output>/tile-nw/world
  --bounds 29712,30496,29840,30624
  --level-template <read-only-template>/level.dat
  --manifest <new-attempt-output>/tile-nw/world-receipt.json
  --job-lease <attempt>/job-lease.json
```

The command accepts only brand-new output directories. The manifest must also be new and remain outside the world, within the private full-Singapore project. A coordinator lease is required for more than four synthetic fixture chunks and for queue/run output namespaces. It binds the Desktop, one CPU thread, one coordinator slot, a UTC validity window, and the exact immutable attempt output root. The queue owns process scheduling and enforcement of its memory reservation.

Chunks store global coordinates directly. Region file coordinates, header slots, and chunk X/Z are checked on reopen. Palette packing uses modern non-straddling signed 64-bit longs. Heightmaps are recomputed for the supported material categories. Lighting is marked stale for Minecraft to recalculate. The world inherits the explicit template's DataVersion, records its SHA256, removes embedded player state, creates no entities or map-data IDs, and chooses a column with clear headroom for spawn.

Minecraft 26 templates require their external `data/minecraft/world_gen_settings.dat`, modern `Data.spawn.pos`, and `dimensions/minecraft/overworld/region/` layout. The writer preserves that dependency with its source hash, disables `data.generate_structures`, clears structure overrides, updates modern and legacy spawn fields, and enables only the vanilla data pack. It does not copy Arena agent state, POIs, player records or maps. Older templates with embedded `WorldGenSettings` retain their older root-region layout for Minecraft's normal migration.

Every output receipt is `WRITTEN_UNACCEPTED`, with `assemblyAccepted=false` and `runtimeLoadAccepted=false`. It records source file hashes, region hashes, DataVersion, Minecraft target 26.1.2, provisional ground classification, layer occlusion counts and spawn. A successful write does not prove source accuracy or runtime playability. The independent run-to-NBT oracle, geometry and height evidence, shared-boundary acceptance, and a headless target-version load remain separate gates.

This writer intentionally handles bounded strips, at most 512 chunks per invocation unless explicitly overridden. Queue attempts provide restart isolation. `package.py` separately supplies immutable checkpoints and clean-install checks for the translated-tile route; its current two-tile seam schema must not be treated as a four-tile or whole-island acceptance receipt.

`runtime_check.py` tests an immutable candidate through a private copy on Desktop, using a coordinator lease, localhost only, one CPU and a 3 GiB heap. The first 2×2 check requires all 256 chunk-load markers plus 16 exact source-proven wall/glass/roof/seam block markers, followed by a flushed clean shutdown. `runtime_receipt.py` checks those retained logs, process exit and input hashes. The first corrected candidate passed this check on 13 September 2026; its modern spawn remained `[29838,1,30624]` after the actual server save. This proves the tested world's headless loading and sampled block placement, not client appearance or AI behavior.
