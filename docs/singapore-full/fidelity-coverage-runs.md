# Independent run-mask oracle

`validate-coverage-runs.py` checks scalar vertical runs at **global block centres (x+0.5, z+0.5)**. This convention was confirmed in `pipeline-mask-runs.py`, the road adapter receipt, and with the pipeline owner. X and Z are integers; yMin is inclusive and yMax is exclusive. Tile origins never change sampling.

Membership is exactly `derived.covers(center) and not exclusions.covers(center)`. Exclusions take precedence, including shared boundaries. Ordinary country-hole boundaries remain covered unless the explicit exclusion polygon covers them. The oracle uses Shapely prepared `covers`, without importing the pipeline mask implementation or expanding boundaries by an epsilon.

Two modes keep the evidence precise:

- `exact-filter` verifies the consumed output equals the independently filtered, explicitly supplied predecessor records. A SHA256 payload multiset preserves duplicate counts and detects deleted or changed records while permitting legal reordering. JSON payload bytes must remain unchanged; only CRLF/LF terminators are ignored. This does not prove upstream feature completeness.
- `containment` verifies only that consumed runs lie inside the allowed mask. It does not claim filter completeness. The road emitter currently masks during rasterization and has no truly unmasked road predecessor, so road evidence must be reported with this limitation. Buildings can use exact-filter where raw and corresponding masked records are available.

Both modes bind exact run, country-mask and foreign-exclusion file hashes. Input/output hashes become verified only after EOF. A valid prefix cannot pass: reaching the combined record or byte cap returns `INCOMPLETE`, exit 1, and unaccepted evidence. Defaults are 10,000 total input/output records and 32 MiB total run bytes, with a 1 MiB line limit and a bounded membership cache. Raising these caps for real multi-million-run files requires the coordinator's available work lease. No such large audit was run for this change.

Country semantics, upstream source completeness, renderer and full-world acceptance remain false even when the narrowly scoped predicate check passes. Empty containment output is unproven and fails instead of producing a vacuous acceptance.

## Frozen projected artifacts

Current projected country mask:

`data/coverage-derived-260912/country-mask-world.geojson`

SHA256 `c6f5341c951026e19bee851de79d2438c623785c81c6c93e9bc92e5a7d25403b`

Current projected foreign exclusions:

`pipeline-mask-20260913-v1/foreign-exclusions-world.geojson`

SHA256 `b1880b77c8bca9846987cb93339d68700d5d558d343758e027bc433ae4c1185f`

Both paths are relative to the private Singapore workspace root. The foreign projection receipt binds the original WGS84 exclusion artifact `ec62589d40215c460635ad7523c803dcd1597cf935be928dc6340b597d7b46af`. Use `--foreign-crs world-xz` for the exact projected artifact. The optional explicit `wgs84` mode projects exclusions independently with PROJ EPSG:3414 and Z=60000-northing; it is not an automatic CRS guess.

## CLI

Exact-filter example, with one or more `--input PATH SHA256` pairs:

```powershell
& "$privateRoot/data/.venv/Scripts/python.exe" tools/singapore-full/validate-coverage-runs.py --mode exact-filter --input "$rawRuns" "$rawSha256" --output-runs "$maskedRuns" --output-sha256 "$maskedSha256" --country-mask "$privateRoot/data/coverage-derived-260912/country-mask-world.geojson" --country-sha256 c6f5341c951026e19bee851de79d2438c623785c81c6c93e9bc92e5a7d25403b --foreign-exclusions "$privateRoot/pipeline-mask-20260913-v1/foreign-exclusions-world.geojson" --foreign-sha256 b1880b77c8bca9846987cb93339d68700d5d558d343758e027bc433ae4c1185f --foreign-crs world-xz --max-records 10000 --max-bytes 33554432 --out "$newReport"
```

For containment-only evidence, set `--mode containment` and omit predecessor `--input` pairs. The report is a new immutable file; existing report files and input paths cannot be overwritten. Exit 0 means the requested narrow audit passed. Exit 1 means failed or incomplete evidence. Per-layer counts and up to 32 violating coordinate samples are included.

## Completed bounded verification

Twelve tests pass. They cover exact shell/hole/exclusion boundaries; legal reorder; duplicate deletion; changed heights; foreign output; empty output; bad hashes; record and byte caps hiding invalid tails; actual EOF at a cap; near-edge points without epsilon expansion; boolean coordinates; and accidental WGS84-as-XZ input.

The committed `fidelity-coverage-runs-controls-*.jsonl` files are **synthetic controls, not actual building or road datasets**. They use the current frozen projected masks:

- `fidelity-coverage-runs-controls-exact.json`: PASS, five synthetic inputs produce two retained copies of an interior record. Two foreign-rock controls and one outside-country control are excluded.
- `fidelity-coverage-runs-controls-negative.json`: expected FAIL, all three deliberately invalid consumed records are detected.

Each receipt labels its synthetic scope and binds exact fixture and frozen geometry hashes. No large run dataset, world generation or current world was read or modified.
