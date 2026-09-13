# Singapore building height audit

`tools/singapore-full/data-height-audit.py` inventories height provenance for every tagged `building` or `building:part` object in the completed, polygon-selected OSM extract. It produces compact JSONL records and a summary. It does not generate buildings or claim the mapped data reproduces every real building.

Run with the standard-library Python interpreter:

```powershell
python tools/singapore-full/data-height-audit.py --self-test
python tools/singapore-full/data-height-audit.py --source <extract>/singapore-overpass.json --extract-report <extract>/extract-report.json --output-dir <new-audit-directory>
```

The source must match the completed extraction receipt's byte count and SHA-256 and pass its reference-completeness gate. The audit refuses unfinished or changed source files and existing output directories. It reads the elements array incrementally; it never loads the entire regional source into memory. Output records contain no copied geometry or coordinates.

| Classification | Meaning |
| --- | --- |
| `mapped-valid-explicit` | A positive, parseable `height` tag with no contradictory vertical geometry fields. This is mapped information, not independent survey verification. |
| `inferred-levels` | No explicit height; valid whole-number `building:levels` gives an estimate using the recorded metres-per-level assumption, default 3. |
| `missing` | No supported above-ground height evidence. Heights stay null. Includes an explicitly underground-only building. |
| `invalid` | Malformed height/count, contradictory geometry, or insufficiently coherent data for automatic height use. |

Metres, feet (`ft`), and feet/inches are parsed. Bare numbers mean metres. Missing unit spacing is accepted with a warning. Decimal commas, ranges, lists, approximate prose, nonfinite values, and nonpositive heights are rejected. A malformed explicit height stays invalid even if a floor count exists. `height` already includes the roof and must not have roof height added twice. `ele`, height restrictions, `building:height`, and `est_height` are never promoted into an observed height. The latter two are retained as warnings. [OSM height specification](https://wiki.openstreetmap.org/wiki/Key:height)

Floor counts exclude roof and fully underground levels. Fractional counts are invalid. A zero count with positive underground levels is recorded without inventing an above-ground height. Skipped floors described by `building:min_level` are already included in the top-level count; they are not added again or subtracted from the total top height. The minimum must be below the count. Inferred height adds explicit roof height when available, otherwise roof levels at the same assumed storey height. Without either roof tag, a warning records that total height may be understated. [OSM building levels specification](https://wiki.openstreetmap.org/wiki/Key:building:levels)

`min_height` is the bottom's distance above the local ground. The audit preserves it separately and checks it is below the explicit or estimated top. It is never added to the total `height`. [OSM minimum height specification](https://wiki.openstreetmap.org/wiki/Key:min_height)

Each record includes OSM type/ID, building/part kind, classification, explicit and estimated metres, level and roof fields, relevant raw tags, errors, and warnings. The summary contains class totals, object kinds, tag frequencies, invalid examples, source/mask fingerprints, and output integrity metadata. Counts describe OSM objects, not deduplicated physical buildings: outlines and parts may overlap. Tagged points lack footprints and are warned. Crossing geometries selected by the Singapore administrative mask still need clipping during generation.

Material and colour tags describe only partial appearance; they do not provide exact facades. URA height controls constrain permitted development and do not measure existing buildings. A DSM records surface elevation and cannot be used directly as building height. Missing information remains visible rather than silently becoming an exactness claim.

## Measured v4 result, 13 September 2026

The completed repaired extraction, SHA-256 `9fdaf1f6dcf3629a3dfaaac24d5837f03f97bbfea59fe7875d2c221b3ae21d0d`, was audited after its reference and integrity gates passed. It includes 134,734 tagged OSM objects: 126,159 building-only objects, 8,481 part-only objects, and 94 carrying both tags.

| Height provenance | Objects |
| --- | ---: |
| Mapped valid explicit | 3,829 |
| Inferred from levels | 38,913 |
| Missing | 90,115 |
| Invalid | 1,877 |

The source has 5,199 height tags, but 1,325 are nonpositive. Tag presence alone substantially overstates useful height coverage. Other recorded problems include fractional or malformed floor counts and contradictory minimum or roof bounds. Raw tags and errors are retained in the JSONL so future correction does not lose the source evidence.

The streaming pass took 4.954 seconds. Windows reported a peak working set of 51,023,872 bytes (48.66 MiB). The 48,773,204-byte JSONL has SHA-256 `4504f41a1cd0ca11801b44075f21767eca2abc8e62254d5a9f9a3c358493e50b`. See `data-height-audit.results.json` for counts and source fingerprints and `data-height-audit.metrics.json` for the memory receipt. Large per-object data stays in the private cache.
