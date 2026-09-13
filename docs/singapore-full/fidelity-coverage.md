# Measured country coverage gate

Current result: **country coverage FAIL; corrected joined candidate structurally eligible**. The current report measures only attempt `attempt-20260913-1642`: one joined 256 x 256 m extent containing four 128 m cores. Its verified union is **65,536 m2** (0.065536 km2), or **0.00399837408%** of the corrected frozen administrative mask. Old Arnis cores are excluded from the current union.

All 256 expected global chunk headers are present in two modern overworld region files. Every inventoried output file matches its SHA256, including `level.dat` and the external world-generation settings. The independent corrected gate binds 25,165,824 oracle cells and 22,020,096 seam cells with zero mismatches. It retains block and heightmap evidence from byte-identical regions and independently rechecks modern metadata.

Current writer manifest SHA256: `6924EEB071FCD1CADACE84ACD053ADADC7CC5A363A92E8A2523EE752579E7776`. Corrected typed gate SHA256: `14F4F17183C01A479542CE6470C9D25C5E5E7CDA6F42F18EAE649FF854EAEE14`. **Actual terrain, nationwide assembly and full-world acceptance remain false.** This metadata audit leaves its own runtime field unaccepted because it does not launch Minecraft. The separate completed runtime evidence in `fidelity-joined-runtime.json` passes for this exact corrected candidate. No expensive block oracle was repeated.

## Boundary and planned extent

The current mask excludes the exact two Malaysian Middle Rocks source polygons from the raw OSM maritime mask. Its SHA256 is `AA88EF3AD093817290CA48180FD21D00D1532FDFE89E9A10DAB8FAC3006676D1`. Independent artifact audit `4F5388F35D197F50B63617AD886D88A33830DD0B6E7BEB573FBE9C79FEF7954E` passed source reconstruction, exact exclusion geometry, negative controls, and boundary-precedence controls.

Artifact validation does not grant country or renderer acceptance. The boundary evidence still reports `rendererAdapterVerified=false` and `SouthLedgeSovereigntyVerified=false`. Renderer sampling must give `exclusions.covers(point)` precedence over `derived.covers(point)`, including shared hole boundaries. Preserving South Ledge source geometry does not establish sovereignty.

The corrected mask's three projected components total **1,639,066,248.98 m2**. It includes administrative waters and is not a coastline, land-area estimate, or accepted sovereignty boundary. Both disconnected eastern components have zero generated coverage. Missing generated area is **1,639,000,712.98 m2**.

The corrected 1,716-core job manifest leaves 0 m2 of the mask outside its planned union. Every planned core still has missing generated area. Its SHA256 is `68B2B048C7420C03494C27188F7783435E3382E2562C7299E73C9DCF0376FBE1`. Planning does not acquire source features or generate the world. This current metadata-only audit does not rehash the large acquired source files; earlier file acquisition evidence remains in the historical report and data lead's registry.

## Historical evidence

`fidelity-coverage-historical-raw-mask.json` retains the earlier raw-mask measurement, annotated `REJECTED_FOREIGN_LAND`. The executable rejects that known raw mask semantically even when its file hash and geometry are valid.

`fidelity-coverage-historical-joined-structural.json` retains the original attempt-1628 block geometry measurement. Its combined gate `13B091B7E121C55FC00D0EF090493BD1FC9D03729128204B7C3AC767A28EE584` was revoked for modern spawn/layout. The report is explicitly historical and its revocation records current ineligibility. The adapter rejects that gate for current coverage.

## Run

Use Python with Shapely 2 and pyproj, and Node on PATH. The Desktop data environment has Shapely 2.1.2 and pyproj 3.7.2. Set variables to the existing data root, attempt-1642 output directory and fidelity worktree:

```powershell
& "$dataRoot/.venv/Scripts/python.exe" tools/singapore-full/validate-coverage.py --joined-manifest "$candidateRoot/writer-manifest.json" --joined-world-root "$candidateRoot/world" --joined-manifest-sha256 6924EEB071FCD1CADACE84ACD053ADADC7CC5A363A92E8A2523EE752579E7776 --joined-gate "$fidelityWorktree/.work/fidelity/repaired-joined-gate.json" --joined-gate-sha256 14F4F17183C01A479542CE6470C9D25C5E5E7CDA6F42F18EAE649FF854EAEE14 --boundary "$dataRoot/mask-audit/singapore-admin-mask-minus-middle-rocks.geojson" --boundary-sha256 AA88EF3AD093817290CA48180FD21D00D1532FDFE89E9A10DAB8FAC3006676D1 --boundary-audit "$dataRoot/mask-audit/independent-exclusion-audit.json" --boundary-audit-sha256 4F5388F35D197F50B63617AD886D88A33830DD0B6E7BEB573FBE9C79FEF7954E --expected "$dataRoot/coverage-derived-260912/country-jobs.json" --out docs/singapore-full/fidelity-coverage-measured.json
& "$dataRoot/.venv/Scripts/python.exe" tools/singapore-full/validate-coverage.test.py
```

Exit 1 means missing coverage or unmet evidence/semantic gates. The JSON report is written even when the gate fails.

The joined adapter requires independently pinned manifest and gate files with matching manifest hash, bounds, chunk count, full-volume oracle comparison, zero mismatches and positive seam comparisons. For DataVersion 4790 it requires the corrected typed metadata evidence, modern spawn schema, vanilla datapacks, modern overworld region directory, external world settings, and exact gate-to-output region identities. All output hashes are independently rechecked. Global chunk headers must cover the declared extent exactly, with valid nonoverlapping sector allocations and no unlisted MCA files anywhere under the world root.

`--tiles` remains available for Arnis-local manifests and calls `validate-fidelity.mjs`. Do not combine overlapping old tiles and their replacement joined candidate. Such overlaps fail the gate and never inflate union area.

`--expected` accepts core arrays, `tiles`, or `expectedCores`. Object manifests bind `maskSha256` to the current boundary. Records carry `coreOrigin: [x,z]` and `coreSize` directly or in a nested `tile` object. Numeric `tile` arrays are grid metadata. Missing-core samples are capped at 100 with complete total counts. All boundary components and holes are preserved through PROJ; absent or invalid frozen evidence never produces an invented national percentage.

## Focused verification

Twenty tests pass. They cover adjacent/overlapping cores, offshore components and holes, partial expected coverage, missing frozen evidence, raw-mask semantic rejection, gate-to-manifest linkage, wrong grids, changed outputs, missing global chunks, aliased sectors, unlisted MCA files, modern directory support, unbound world settings, and mismatched corrected region identities.

`verifiedCoreCount` counts independently verified coverage extents. The joined candidate is one extent, not one 128 m tile. Halos and padded MCA areas never contribute to measured coverage. Country land filter, sovereignty, nationwide assembly, release and full-world acceptance remain held.
