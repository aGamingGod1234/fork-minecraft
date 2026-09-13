# National core coastline classification

The private artifact classifies acquisition coverage for all 1,716 planned cores. It does not establish generated, verified, or completed Minecraft world coverage. Its status remains **CANDIDATE** until the independent national mask audit passes. The source receipt passing its own checks does not replace that independent audit.

## Inputs and method

The tool reads the immutable national-v1 coastline mask in Minecraft XZ metres, its receipt, and the existing country-jobs manifest. It pins both data hashes and requires the receipt to bind the coastline file and the same administrative scope hash as the jobs. Every core is a unique, grid-aligned 1,024-metre square.

A Shapely STRtree indexes individual polygons. Per-core intersections compute continuous areas without block-center sampling or world reads. The source mask already clips its land/sea polygons to the approved administrative scope. Features marked `reason: outside-approved-administrative-scope` are excluded from in-scope totals. Line-only remnants of administrative clipping contribute no area.

The tool relies on that source clipping, binds it by scope hash, and reconciles each core against its approved `maskAreaSquareMetres`. It does not independently reload administrative geometry. A shortfall becomes in-scope unknown area; overlap or scope overflow above 0.01 m² causes failure. National land and sea totals must reproduce the receipt, and expected scope must equal 1,639,066,248.9807 m² within 0.01 m².

Classification uses a 0.01 m² numerical tolerance:

- `pure_sea`: only sea in the approved portion of the core.
- `mixed_coast`: both coastline land-side and sea areas.
- `coastline_side_land`: only coastline land-side area.
- `unknown`: explicit in-scope unknown area or an unclassified gap above tolerance.

Land-side area includes reservoirs and other inland water until a separate inland-water adapter is applied. It is not official dry-land area. Outside-scope area is reported separately and never counted as in-scope unknown. Small raw floating-point residuals are retained for inspection.

## Run

Requires Python and Shapely 2.x. Run on Desktop using the existing private data environment. From this worktree, set `$data` to the private `fork-singapore-full/data` directory:

```powershell
& "$data/.venv/Scripts/python.exe" tools/singapore-full/data-coast-coverage.py --self-test

& "$data/.venv/Scripts/python.exe" tools/singapore-full/data-coast-coverage.py `
  --mask "$data/coast-mask/national-v1/coast-mask-xz.geojson" `
  --receipt "$data/coast-mask/national-v1/coast-receipt.json" `
  --jobs "$data/coverage-derived-260912/country-jobs.json" `
  --output "$data/coast-mask/national-v1/core-coast-classification.json"
```

The output is created exclusively; an existing artifact is never overwritten. No job manifest, mask, queue, or world is modified. The process pins itself to one available CPU and records peak Windows working set, refusing output if that measurement exceeds 256 MiB.

## Recorded national candidate run

Eight synthetic checks passed: boundary clipping, gap-to-unknown accounting, outside-scope exclusion, explicit unknown, overlap rejection, scope-overflow rejection, pure land-side classification, and zero-area boundary remnants.

| Classification | Cores |
| --- | ---: |
| Coastline-side land | 561 |
| Mixed coast | 410 |
| Pure sea | 745 |
| Unknown above tolerance | 0 |
| Total | 1,716 |

| Area | Square metres |
| --- | ---: |
| Coastline land side | 774,220,366.5618297 |
| Sea | 864,845,882.4188704 |
| Raw numerical unknown/gap | 0.0000000008731149137020111 |
| Approved administrative scope | 1,639,066,248.9807 |

The national accounting residual is -0.0000002384185791015625 m². Runtime was 1.86 seconds with one CPU and 47,124,480 bytes peak working set (44.94 MiB). This run provides a list of 971 cores containing land-side area for potential prioritization; it does not change scheduling.

The private output is `coast-mask/national-v1/core-coast-classification.json` (734,175 bytes). Its SHA-256 is `d41b293dcac57541141977f22bb892741f3f0f81cbcd81b11cc5bc2d62505e6f`.

Pinned source SHA-256 values:

- Coast mask: `e8c3ea80ae5044edadaeb239249b83b26b4b585b10cdd9a134112b53d4c51c38`
- Country jobs: `68b2b048c7420c03494c27188f7783435e3382e2562c7299e73c9dcf0376fbe1`
- Administrative scope: `aa88ef3ad093817290ca48180fd21d00d1532fdfe89e9a10dab8fac3006676d1`
- Regional source PBF: `01e16a33157689db74c401f4a6e9204526883369b999e3c92a561ac4a5a78699`

The artifact also records the receipt hash, tool hash, source sizes, source and scope bounds, per-core bounds and areas, per-core gaps and residuals, and runtime. Runtime measurements mean a fresh output may have a different artifact hash even when its classification and area results are identical.


## Polygon-only national-v2 rebind

The classifier accepts `--expected-mask-sha256` for an explicitly pinned replacement mask; the default remains national-v1. `--prior-geometry-audit` records the prior v1 audit separately, requiring PASS for its national face audit and matching source JSON/PBF hashes. It does not promote the current mask to approved status.

For v2, use the command above with both mask and receipt paths under `national-v2`, output under `national-v2`, and these additional options:

```powershell
--expected-mask-sha256 85db7807ad75ea14ab7ef0aaedfd0f195fdd729c97a40c16f200dd3843db676d `
--prior-geometry-audit "$data/coast-mask/national-v1/independent-audit.json"
```

The v2 run created only `national-v2/core-coast-classification.json`; v1 was left immutable. The new artifact is 734,850 bytes with SHA-256 `6b1e6314d0ad4a0452c04bf3fb0f1c5a3eed7136ce591defff8bc79bd5d0010d`. All 1,716 per-core records are identical to v1: 561 land-side, 410 mixed coast, 745 sea, and no unknown above tolerance. Areas and accounting residuals are also identical. Runtime was 0.922 seconds on one CPU, with 47,910,912 bytes peak working set.

The artifact remains CANDIDATE. Its `geometryAudit.priorSourceGeometry` records the v1 source-geometry PASS, audit SHA-256 `13f3455568cfdc3fd38bfc5e56264621133a46fefad2d2e4eaad854ea5402f17`, and the mask hash to which that prior audit applies. `currentMaskNormalization` and `currentMaskIndependentAudit` remain pending in this frozen artifact. A later normalization receipt must be evaluated separately. The prior audit does not prove PBF omission completeness, sovereignty, or raster/world coverage. Eight focused tests and actual v2 input/area checks passed.
