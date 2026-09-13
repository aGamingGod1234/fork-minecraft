# Expansion attempt and road-staging partial

The requested1024x1024 real-map expansion was not produced. The one local public OSM API request failed immediately at13:08:48 SGT with `connect EACCES146.75.45.55:443`. World stopped that mechanism and handed Main the runnable fetch script and hashes. No expanded raw source arrived before this handoff; no Arnis process launched. There is no verified expanded transform and no1024-world hash.

Requested API bounds: west103.845399492, south1.278400648, east103.854600508, north1.287599352, centred on103.85E/1.283N. The acquisition failure record and prepared pipeline are retained. They are not proof of generated coverage. `finish-expand-1024.mjs` is prepared but unexecuted; source parsing was tested against the accepted extract and reproduced all1383 geometry elements and ASCII tags. Its UTF-8 decoding corrects93 legacy non-ASCII tag encoding differences. Full expanded-pipeline verification remains unrun.

The useful partial is a new stopped256x256 copy:
`assets/fork-world/artifacts/expand-1306/road-only-256/FORK Three Prong 256`.
It retains the accepted court and facade materials and adds a compact three-pronged road mask. This is expressly not a substitute claim for1024 coverage. Earlier raw, visual and installed worlds remain untouched.

The road occupies98 ground cells;78 required material changes. It touches26 existing paved cells, uses gray road material and white markings, and demolishes no building. All changed positions were reopened and checked. The court transform `[46,0,22]`, relative anchors,11-cell demolition mask and six sentinels are unchanged. Declared safe spawn is `[54,1,29]`; player/session data is absent. Main must start a new session and regenerate INITIAL, preserving the existing scored session.

Old256-frame road facts, not expanded coordinates:

- Vertex `[61,0,47]`, heading225 degrees.
- Prongs `[58.17157,0,56.89949]`, `[54.63604,0,53.36396]`, `[51.10051,0,49.82843]`.
- Stem end `[66.65685,0,41.34315]`.
- Crane start `[68.77817,6,39.22183]`, end `[75.14214,36,32.85786]`, looking at `[58.87868,0,49.12132]`.
- The crane's101 sampled positions clear the measured column heights by at least2blocks. Lens framing, occlusion through the complete shot and curves remain Cinematic's live check.

`road-staging-old-frame.json` contains the full mask and endpoints. `road-only-manifest.json` contains before/after materials, exact source/target region hashes and validation. The final copy includes its OSM source offer, WORLD-NOTICES and additional ROAD-STAGING-PATCH notice. This fork is interpretive game staging, not original OSM road geometry. No new between-building corridor or island-wide flight was accepted. Preserve the established, separately verified routes and actual coverage labels.

Main owns runtime fixture A/B, three restores, visit/return, package/transfer and Laptop visual checks. World ran no UI, video render, new model, helper or Git write. The source/world freeze remains13:20 SGT.
