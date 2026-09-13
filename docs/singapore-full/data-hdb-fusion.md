# Optional HDB floor enrichment

This sidecar proposes 796 additional residential building outlines using exact normalized block-and-street matches to the official HDB Property Information snapshot. It does not modify the national OSM extract or any Minecraft world.

The accepted OSM extract is SHA-256 `9fdaf1f6dcf3629a3dfaaac24d5837f03f97bbfea59fe7875d2c221b3ae21d0d`. The HDB snapshot contains all 13,357 API rows and is SHA-256 `8e100ffbe97a6949f8a1509283f9287cee1c1548d1a1f48bf7192d30f33fe5d4`.

## What is offered

| Result | OSM objects |
| --- | ---: |
| Missing height and levels, unique eligible HDB match | 796 |
| Existing valid OSM levels preserved | 9,151 |
| Existing valid explicit OSM height preserved | 203 |
| Repeated OSM address, ambiguous | 173 |
| Invalid or conflicting OSM numeric evidence, review | 890 |
| Residential non-carpark use not confirmed, review | 308 |

There are 11,521 matching objects covering 11,430 distinct HDB addresses. Counts refer to mapped outlines and parts, not deduplicated physical buildings. The optional 796 estimates cover about 0.88% of the earlier 90,115-object missing-height baseline; the baseline source remains unchanged.

Matching uppercases and normalizes whitespace, then expands an explicit street-token allowlist. It accepts a block number with at most one trailing letter. It rejects ranges, multiple addresses, unsupported punctuation, duplicate normalized HDB keys, repeated OSM keys and unresolved parts. There is no nearest-building or fuzzy match. Missing address fields cannot be enriched.

## Observation and estimate

HDB's `max_floor_lvl` is a maximum floor level, not a measured height or guaranteed storey count. Each record preserves the raw HDB row, raw OSM address, source hashes and a separate inference object. The default policy uses 2.8 metres per maximum floor level, explicitly as a chosen proxy. A previously suggested HDB PDF citation returned HTTP 404 and is not evidence for this conversion.

The CLI accepts `--metres-per-floor` so a consumer can use the same declared policy as its renderer. Prefer the raw floor observation and shared renderer policy; do not present `inference.heightMetres` as surveyed roof height. Roofs, stilts, podiums and unequal floor heights are not measured. Existing valid OSM height or levels are preserved; malformed evidence requires review.

The output carries `optional: true`, `appliedToSource: false` and `appliedToWorld: false`. Source-mask limitations, including water-inclusive administrative geometry and unresolved land-scope acceptance, still apply. See the separate data mask audit.

## Run

Use Python 3.11 or later. The fusion and focused matching tests use the standard library and the adjacent checked-in height parser.

```text
python tools/singapore-full/data-hdb-tests.py
python tools/singapore-full/data-hdb-fusion.py --source /cache/singapore-overpass.json --extract-report /cache/extract-report.json --hdb /candidate/hdb-property-information.raw.json --hdb-descriptor /candidate/source-descriptor.json --output-dir /cache/hdb-fusion/new-run --metres-per-floor 2.8
```

Use a new output directory. The command validates the frozen source hashes and OSM reference-completeness receipt before joining. It writes the optional JSONL sidecar, a full matched-object decision ledger and a hash-bound summary. Keep raw source data and generated sidecars outside Git.

The current production receipt is [data-hdb-results.json](data-hdb-results.json). Its sidecar is `hdb-height-overrides-v1.jsonl` in private run-v2: 1,113,545 bytes, SHA-256 `4257330dcd925b8df242ed09341174ed3d33bbea128311afedc3c55b4e147511`. Run-v1 is retained; run-v2 adds separate licence metadata without changing matching decisions.

## Attribution and distribution

Contains information from HDB Property Information accessed on 2026-09-13 from Housing & Development Board via https://data.gov.sg/datasets/d_17f5382f26140b1fdae0ba2ef6239d2f/view which is made available under the terms of the Singapore Open Data Licence version 1.0: https://data.gov.sg/open-data-licence

Retained full HDB licence and source notice are in [data-hdb-licence](data-hdb-licence/). OSM attribution and ODbL obligations remain separately applicable. No HDB endorsement or official reconstruction status is claimed.

The joined sidecar is marked `redistributionStatus: REVIEW_REQUIRED`. Singapore Open Data Licence sublicensing terms do not establish unrestricted downstream relicensing of this combined artifact. It is not labelled wholly ODbL, and compatibility is not certified. These records are an optional private enrichment artifact, not an approved release input.

Verification: 13 focused matching tests pass. Independent checks cover all 796 offered records, 9,354 preserved records and 11,521 decisions, and confirm frozen input hashes remain unchanged. Thirty-two HDB address forms and 271 OSM address forms were conservatively rejected by the normalizer; they were not silently matched.
