# National preview assembly

These Desktop tools connect the existing national production queue to the exact-core world assembler. Source worlds and their frozen validation records remain unchanged.

- `prepare_core.py` checks the pinned V3/V4 national authority and reconstructs the original validation binding. It supplies exact copies of road metadata to the existing source-omission classifier.
- `finish_core.py` verifies road and inland-water runs, independently compares the coastline mask at every owned metre cell, and admits only evidence accepted by the existing coverage loader.
- `run_batch.py --limit 77` checks a connected group from `validated-candidates.json`, retaining failed source classifications. It uses one CPU and 2 GiB, accounts for that reservation in the queue, and stops individual jobs at the memory/disk/time floor.
- `deliver_preview.py` selects a completely filled rectangle from ready tiles. It creates a new immutable save, compares the copied chunks with their sources, then runs an isolated Minecraft 26.1.2 server check and packages the unchanged candidate.
- `public_preview.py` adds installation instructions, OSM provenance, all road/water diagnostic occurrences, and explicit limitations to a separately verified public ZIP. It does not upload anything.

The explicit `verify-national-source-and-final-spawn` policy requires a national core whose original oracle passed without a spawn exception. The assembler separately checks actual source spawn blocks, retains that proof, and compares it with the assembled save. Components remain `NOT_STANDALONE`; a runtime pass is still required before delivery.

National render inputs include border halos. Empty inland-water evidence therefore binds and recounts the entire run file but proves zero water emission only inside the owned core. Water outside that core is permitted inside the declared render bounds. This does not establish source-water absence.

The reconstruction uses one block per horizontal metre. Ground is provisional and flat, some heights are estimated, and facades are generic. Neither the preview nor these checks establish a complete or visually exact Singapore reconstruction. The world package does not contain the FORK gameplay mod or AI agents.

Focused checks: national authority/binding and spawn-policy tests; empty inland-water halo tests; assembled-world receipt tests; runtime-plan tests. Real-world source hashes, coastline comparisons, exact chunk copies, safe spawn and representative server loading are checked by the delivery pipeline.
