# Singapore world generation source

This release contains the Singapore generation tools as of `1fdd298`, plus the exact historical first-party modules used for the **FORK - Lim Chu Kang** 1024-metre preview. The normal source includes subsequent fixes; it is not byte-identical to every module that made that ZIP. CBD and connected growth tools are included, but this snapshot does not establish a released or accepted CBD save.

The [published Lim Chu Kang preview](https://github.com/aGamingGod1234/fork-minecraft/releases/download/v2026.09.13-rc7/FORK-Lim-Chu-Kang-1024-public-v2.zip) is 1,745,910 bytes with SHA-256 `07e654ecae896905a39878f97b6c09df58f9788c81697569bd74e7c74440438f`. It preserves all six world payloads from the original package and adds source attribution and separate-save instructions. The historical records below retain the original package hash, `bc2d1ac5a24a066d7091d4b7ff93e90e84e12b60f19004b8079693f1c6b6e7ae`.

Its owned core is `[13312,13312,14336,14336)`, containing 4,096 chunks. A 128-metre halo was generated and then cropped. The grid is EPSG:3414: X = easting, Z = 60000 minus northing, with one block per metre.

The [preview proof](singapore-full/merge-lim-chu-kang-proof.md) records a full owned-core structural comparison and a representative Minecraft Java 26.1.2 load check. That runtime check visited 15 chunks with 19 sentinels, not every chunk. Client visual review was still pending at this snapshot.

## What produced the preview

| Stage | Source entrypoint |
| --- | --- |
| Open-data acquisition, mask and reference-complete extraction | `tools/singapore-full/data-*.py` |
| Reusable source selection with complete references | `tools/singapore-full/index/spatial_index.py` |
| Job orchestration | `pipeline-adapter.mjs` |
| Project complete building geometry / road nodes | `pipeline-features.mjs`, `pipeline-node-projection.mjs`, shared projection in `pipeline.mjs` |
| Bounded building rendering | `buildings/stream_renderer.py` and its building modules |
| Roads, mapped water and landcover | `pipeline-roads.py` and `roads/` |
| Country and foreign-land clipping | `pipeline-mask-runs.py` |
| Global-coordinate world writing | `merge/overlay.py`, `merge/run_spool.py`, `merge/anvil.py` |
| Independent evidence, crop and packaging checks | `validate-*.mjs`, `validate-*.py`, `merge/grow.py`, `merge/package.py` |

Paths without a prefix are relative to `tools/singapore-full`. The rural execution used building and road/surface runs. It did not call the separate `terrain/coast_surface.py` stage. `pipeline.mjs` also contains an older Arnis generation CLI; its projection function is reused by the current pipeline, but that Arnis CLI did not render this preview.

The national extracted source SHA-256 was `9fdaf1f6dcf3629a3dfaaac24d5837f03f97bbfea59fe7875d2c221b3ae21d0d`. The selected rural source was 3,827,840 bytes with SHA-256 `dcea9db0b6a1060d799fd3e958eefc7510a0896553cfab7ca7b3f55d203a0b5d`. Raw national data, generated worlds, machine receipts and native binaries are outside this source release.

## Historical source and later changes

[`preview-lim-chu-kang-1024/manifest.json`](../tools/singapore-full/preview-lim-chu-kang-1024/manifest.json) binds every archived file to its original execution hash and compares it with the normal `1fdd298` Git blob. `generation/` contains the executed generation module closure; `assembly/` contains the crop and runtime verification module closure. `.gitattributes` preserves their original bytes, including line endings. These historical files retain their original workspace restrictions. They are evidence and reproduction inputs, not the default source for new jobs.

Six source files in the frozen benchmark input differ substantively from the normal snapshot:

- `buildings/footprints.py`: estimates absent heights above known minimum elevations.
- `buildings/renderer.py`: separates invalid source documents from individual feature-tag quarantine.
- `buildings/stream_renderer.py`: includes later bounded-renderer and source-validation changes.
- `roads/water_landuse.py`: filters polygon edges for the sampled row before containment checks.
- `merge/overlay.py`: accepts additional full-cube facade minerals and metals emitted by building rendering.
- `pipeline-make-benchmark-jobs.mjs`: versions corrected jobs and binds explicit feature quarantine. This preparation helper was not an executed generation dependency, so it is not duplicated in the archive.

The five executed changed modules are present in `generation/`; other differences in that closure are only line endings. Current crop modules match the archived crop code after line-ending normalization. No generator behavior was changed while preparing this source delivery.

## Setup and source preparation

The recorded environment was Windows, Python 3.11.9 and Node 24.19.0. The data and terrain requirements pin osmium, Shapely, pyproj and NumPy. Projection uses `proj4@2.22.0` and its lockfile. The only repository dependency outside the Singapore tree is `tools/fork-world/nbt-region.mjs`, already part of this repository.

From the checkout root, use an external cache:

```powershell
$cache = Join-Path $env:LOCALAPPDATA 'FORK-Tools/fork-singapore-full/data'
py -3.11 -m venv "$cache/.venv"
$py = "$cache/.venv/Scripts/python.exe"
& $py -m pip install -r tools/singapore-full/data-requirements.txt -r tools/singapore-full/terrain/requirements.txt
powershell -NoProfile -File tools/singapore-full/pipeline-setup.ps1
& $py tools/singapore-full/data-download.py --manifest docs/singapore-full/data-sources.json --cache $cache --id osm-geofabrik-260912
& $py tools/singapore-full/data-mask.py "$cache/malaysia-singapore-brunei-260912.osm.pbf" "$cache/mask-new"
& $py tools/singapore-full/data-extract.py --source "$cache/malaysia-singapore-brunei-260912.osm.pbf" --mask "$cache/mask-new/singapore-admin-mask.geojson" --output-dir "$cache/extract-new"
```

`data-mask.py` takes two positional arguments and has no `--help` option. Download availability is upstream-controlled. A fresh extract is not automatically the accepted v4 extract: verify the reference-closure report and hashes, and follow the retained [source setup](singapore-full/data-setup.md), [mask audit](singapore-full/data-mask-audit.md), [source manifest](singapore-full/data-sources.json) and [provenance](singapore-full/data-provenance.md). Accepted repaired inputs and their original machine receipts are not reconstructed by the commands above.

With that exact accepted source available as `SOURCE.json`, index and export the rural geometry using these bounds in easting/northing order:

```text
python tools/singapore-full/index/spatial_index.py build --source SOURCE.json --index CACHE/source.sqlite --expected-sha256 9fdaf1f6dcf3629a3dfaaac24d5837f03f97bbfea59fe7875d2c221b3ae21d0d
python tools/singapore-full/index/spatial_index.py export --index CACHE/source.sqlite --bounds 13184 45536 14464 46816 --output CACHE/rural.json
```

Require the subset hash above and `referenceComplete=true`. Exports retain complete geometry; rendering applies the country mask and foreign-land exclusions separately.

## Generation and assembly boundary

The original adapter was invoked with a frozen job JSON and a new attempt directory:

```text
node tools/singapore-full/pipeline-adapter.mjs --job JOB.json --attempt-root NEW_ATTEMPT_OUTPUT
python tools/singapore-full/merge/grow.py --plan GROW_INPUT.json --world NEW_SNAPSHOT/world --manifest NEW_SNAPSHOT/grow-manifest.json --job-lease LEASE.json
```

For historical replay, replace those module paths with `preview-lim-chu-kang-1024/generation/pipeline-adapter.mjs` and `preview-lim-chu-kang-1024/assembly/grow.py`, respectively, beneath `tools/singapore-full`. Bind the job's tools to the same historical tree and verify the manifest hashes.

`JOB.json` must contain schema version 1; the exact source path/hash; all tool and dependency hashes; a modern 26.1.2 `level.dat` and adjacent `data/minecraft/world_gen_settings.dat`; projected country and foreign-exclusion masks; `terrain: {mode: "flat-provisional", groundY: 0}`; and one tile with core origin `[13312,13312]`, size `1024`, halo `128`. The rural run explicitly used `invalid-building-parts-report` quarantine with a maximum fraction of `0.1`. Omissions remain reported rather than accepted as complete source geometry.

These are command forms, not a one-command public rebuild. The writer and crop CLI enforce their original Windows project roots and current Desktop coordinator lease. The queue enforces resource limits. The benchmark job builder also expects private proof files and a pinned level template. Job JSON, source data, templates, valid leases, independent structural/coverage receipts and the Minecraft server are required external inputs. They are not credentials or binaries bundled with this code. The [growth contract](singapore-full/merge-grow-contract.md) and [CLI contract](singapore-full/merge-grow-cli.md) define accepted crop descriptors and evidence. Generated status alone is not release acceptance.

The optional `pipeline-grow*.mjs`, raster/DSM terrain, HDB fusion, namespace/queue tools and other benchmark validators remain research or staged expansion components unless their individual docs establish a narrower proof. The older Arnis CLI additionally requires its pinned Arnis 3.2.0 executable. This source snapshot does not claim completed Singapore, measured bare terrain, exact photographed facades, interiors or agent gameplay.

## Fidelity, licences and checks

The preview uses flat provisional ground at Y=0. OSM footprints and mapped surface geometry are source-based; absent heights, levels-to-height conversion, road widths, palettes, windows and facade patterns can be estimates. Copernicus DSM is roughly 30-metre surface elevation, including roofs and vegetation, not one-metre bare terrain. Map accuracy and missing real-world features are outside the structural proof.

Keep attribution to OpenStreetMap contributors and Geofabrik and the ODbL terms with OSM-derived data. Retain [source notices](singapore-full/data-licenses/NOTICE.txt), [licence files](singapore-full/data-licenses/) and [HDB notices](singapore-full/data-hdb-licence/ATTRIBUTION-NOTICE.txt) for their corresponding inputs. Those data terms do not replace the repository software licence. Review the retained terms when distributing derived datasets or worlds.

Focused source checks use existing synthetic fixtures; they do not generate the national world:

```text
python tools/singapore-full/data-height-audit.py --self-test
python tools/singapore-full/data-mask-audit.py --self-test
python tools/singapore-full/data-coast-build.py --self-test
python tools/singapore-full/data-hdb-tests.py
python -m unittest discover -s tools/singapore-full/index -p "test_*.py"
python -m unittest discover -s tools/singapore-full/buildings -p "test_*.py"
node --test tools/singapore-full/pipeline-adapter.test.mjs tools/singapore-full/pipeline-features.test.mjs tools/singapore-full/pipeline-job-policy.test.mjs tools/singapore-full/pipeline-benchmark-prep.test.mjs
```

Operational launch/repair scripts, history-dependent one-off runners, machine-path receipts and the coordination plan were omitted. [The delivery manifest](singapore-full/source-delivery.json) lists those exclusions. Source acquisition, projection, rendering, assembly and independent validators remain reviewable here.
