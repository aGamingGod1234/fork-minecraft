# Full Singapore merge checkpoint contract

`tools/singapore-full/merge/package.py` uses Python 3.11's standard library. It does not install a world, modify input tiles, delete files, download resources, compress packages or publish releases.

## Inputs and fixed destination

Only a brand-new output directory below Desktop's `FORK-Tools/fork-singapore-full/merged` is accepted. Existing directories are never adopted. The checkpoint JSON must live outside both the output and input directories. Symlinks and Windows junctions are rejected.

The merge driver supplies each tile as `{id, path, core_bounds, transform}`. `core_bounds` is `[minimum X, minimum Z, exclusive maximum X, exclusive maximum Z]` in blocks. `transform` preserves the coordinate module's descriptor; this module records it without inventing a second transform. The full coordinate contract is stored with its SHA256. Each tile's complete relative file inventory, per-file bytes/SHA256 and canonical tree SHA256 are retained.

`seam_gate` requires `status: PASS` and `evidence_path` to a retained seam receipt. The evidence file is hashed, parsed and rechecked using `translation.require_seam_gate` on every checkpoint/resume. The real report must certify exactly the two input IDs. This first implementation fails closed for larger tile sets until aggregate seam evidence is implemented; one passing pair cannot certify an entire island. A declared PASS cannot override failed underlying evidence. The currently failing real structural seam blocks real assembly and publication. Synthetic fixtures explicitly set `synthetic: true` in both gate and report; their sealed manifests remain `package_ready: false` and state `SYNTHETIC FIXTURE ONLY`.

## Driver API

1. `initialize_manifest(manifest_path, output_world, tile_inputs, coordinate_contract, seam_gate, synthetic=False)` validates sources first, creates the new empty output and writes the first receipt.
2. Write each complete output region to a private temporary path and atomically rename it into the new world. Do not rewrite a checkpointed region later.
3. `checkpoint_manifest(manifest_path, completed_files=None)` verifies all source files and previously checkpointed outputs, then records selected completed output files. Relative names use `/`; `None` selects all files currently present.
4. `verify_manifest(manifest_path)` is the resume gate. Changed inputs, missing/changed recorded outputs, changed seam evidence, changed contract and uncheckpointed extra files all fail. An interrupted file is never reused merely because it exists. A crash after writing a complete region but before its checkpoint requires the driver to independently validate that region before explicitly checkpointing it; ordinary resume does not silently adopt it.
5. `seal_manifest(manifest_path)` verifies all outputs and the clean-world gate, then records `SEALED`. No further checkpoint writes are accepted.

Each write uses a unique sibling temporary file, flush, `fsync`, then `os.replace`. A crash before the replacement preserves the previous receipt. Leftover temporary files are not used for resume or deleted by this tool. Manifest self-hashing detects accidental alteration; it is not an authenticated signature.

The CLI accepts `initialize <manifest> --spec <json>`, `checkpoint <manifest>`, `verify <manifest>`, `seal <manifest>` and `clean <world>`. Failures exit 2 with a JSON error.

## Clean-world gate and limits

The gate rejects directories/files named `playerdata`, `players`, `entities`, `fork`, `session.lock`, credential stores, private key files, `.env`, `.git`, `.ssh` and `.codex`, including empty directories and case variations. Both `level.dat` and `level.dat_old` are parsed using the shared `anvil.read_level_dat`; any embedded `Player` tag or malformed NBT fails closed. An overworld region and valid level metadata are required. This gate checks recognizable credential paths, not arbitrary secrets hidden in innocent binary names; the driver must use controlled world-only outputs.

Chunk validity, inline entity translations, block entities and map-ID collisions are separate merge-driver/NBT gates. Region hashes do not prove these properties. No per-tile `data/map_0.dat` may be blindly combined across tiles. The file/clean gate is separate from a headless Minecraft 26.1.2 load test and manual Laptop appearance check. `runtime_load_gate` and `manual_appearance_gate` remain `PENDING`, and `install_ready` stays false. A sealed real manifest means its bytes are prepared for those next gates, not that the world is visually accepted or the whole island is accurately reconstructed.

Release packaging must retain source/licence attribution (including applicable OpenStreetMap ODbL attribution), coordinate/height/terrain uncertainty, tool versions and the final input/output manifest. This module does not invent source accuracy or assert host upload limits. Hosting constraints must be verified before choosing archive sizes.

## Synthetic tests

Run `python -m unittest discover -s tools/singapore-full/merge/tests -p test_package.py -v` with the shared `anvil.py` importable. Tests leave explicitly named, unique `synthetic-package-tests-*` directories below the new-world root. These tiny fixtures are not playable worlds and cannot produce a deliverable manifest. Tests cover resume, changed inputs/outputs, interrupted atomic writes, strict seam status, synthetic isolation, embedded players, forbidden runtime/credential paths and malformed level data.
