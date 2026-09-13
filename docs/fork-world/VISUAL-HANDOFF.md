# Market Street visual correction

Use `data/fork-world/court-market-street-v2.json` with the new stopped world at `assets/fork-world/artifacts/visual-1210/world-output/FORK Market Street Visual`. The new copy includes `fork-court.json` so the world-local override selects the corrected layout. The installed world, original generation and committed LiveR1 session are untouched.

The court has stone paving, a literal branching route, a shared two-storey clinic/workshop frontage, medical-cross and W block signs, concealed lighting and a neutral utility cabinet. The origin remains `[46,0,22]`. All role anchors, courier route, human arrival, dynamic prop positions, 11 demolition cells and outside sentinels are preserved. Signs are large symbols made of ordinary cubes; literal facility names remain the existing UI's responsibility.

Main must extend the existing ForkSession palette allowlist with `stone_bricks`, `smooth_quartz`, `gray_concrete`, `brown_terracotta`. These are ordinary full cubes with no block entities. No shared Java or packaged resource was edited by World. The packaged fallback resource is `src/main/resources/data/fork/world/court-v1.json`; the world-local override takes precedence. Update any profile override or explicit `fork.courtFile` consistently.

Start a NEW session and regenerate its INITIAL checkpoint. Do not reload this layout into the committed LiveR1 session. Main must verify actual placement, projected sockets/lights, one demolition/restore, outside sentinels, role identities and scenario state before accepting the new session. Preserve the earlier session and archive.

The city patch replaces existing facade materials inside the source footprints with CapitaSpring fins and green terrace accents, plus CapitaGreen glass/planting accents. A short designed access pavement joins the court to existing paving. No geometry, occupied-cell position or column height changes. CapitaGreen's clipped crown is not invented. Source heights/defaults and partial boundary buildings remain imperfect. Roof work is limited to the existing garden surface; no new roof silhouette is claimed.

`patch-city-visuals.mjs` pins the source region hash, refuses an existing output, creates a stopped copy and records every material change. `verify-visual-patch.mjs` reopens both saved regions and compares every decoded block, court contract and manifest. Changed chunks are marked for relighting; Main owns 26.1.2 conversion/reopen and visual verification. A shader cannot substitute for this verification.

The city destination is in `data/fork-world/city-view-destination.json`: feet `[63.5,1,23.5]`, looking into the district toward `[128,110,30]`. The saved world has solid pavement and three air blocks at arrival. This is structural safety evidence only; Main must check loaded arrival and return. Keep travel human-only at round0/6 with no pending work.

Bundle `VISUAL-WORLD-NOTICES.txt`, `world-sources`, the patch manifest, the court JSON, the patch and NBT scripts, and the Arnis license with the final package. Show `Map data © OpenStreetMap contributors` in world/package credits with the copyright link. Actual coverage is a compact Market/Cecil/Cross Street district; never label the generated scene as all Singapore.

Reference pages and owner fact sheets support selected architectural features. Image pixels could not be retrieved for a direct side-by-side comparison in this worker, so photographic similarity and actual game appearance remain unverified. Skins, shaders, camera implementation and film belong to Gameplay/Cinematic/Main.
