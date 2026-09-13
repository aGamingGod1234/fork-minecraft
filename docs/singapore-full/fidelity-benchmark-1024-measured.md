# Measured 1024 m benchmark results

Rural Lim Chu Kang and Changi coast completed generation and passed independent structural validation. Both comparisons covered all 402,653,184 cells in the exact owned 1024 m core, all 4096 core chunks, consumed source runs, global coordinates, metadata and heightmaps, with zero mismatches. Their original measurement receipts remain WRITTEN_UNACCEPTED and are linked to separate typed acceptance gates. These results do not grant production, runtime, visual, real-terrain or facade acceptance.

CBD has no successful full benchmark. V1 failed source validation. V2 completed the building and road stages, then the writer rejected minecraft:iron_block. A later writer-only repair is a separate measurement and cannot convert that failed receipt into successful end-to-end throughput.

| Case | Result | Wall seconds | CPU seconds | Peak sampled RSS bytes | Peak Job commit bytes |
| --- | --- | ---: | ---: | ---: | ---: |
| Lim Chu Kang V1 | Structural PASS | 882.418 | 830.641 | 520,421,376 | 535,113,728 |
| Changi coast V1 | Structural PASS | 156.319 | 144.422 | 755,474,432 | 734,859,264 |
| CBD V1 | Failed source validation | 66.819 | 64.375 | 440,819,712 | 431,091,712 |
| CBD V2 | Failed writer | 329.851 | 322.703 | 1,484,300,288 | 1,726,554,112 |

Each process tree had a 1 logical CPU and 4 GiB committed-memory limit. Rural and coast used logical CPU2; CBD V2 used CPU4. Coast overlapped CBD V2 on different physical cores. These observations do not establish parallel speedup. CPU accounting includes exited descendants. RSS is a non-atomic sum with a nominal 50 ms sampling interval, so brief peaks may be missed and shared pages can count twice. Peak Job commit is a separate Windows measurement.

| Case | Selected source bytes | Entire output bytes | World-only bytes |
| --- | ---: | ---: | ---: |
| Lim Chu Kang V1 | 3,827,840 | 196,368,904 | 26,346,217 |
| Changi coast V1 | 3,931,618 | 414,216,221 | 26,346,219 |
| CBD V1, partial failed output | 5,545,981 | 393,462,192 | unavailable |
| CBD V2, partial failed output | 5,545,981 | 1,237,829,263 | unavailable |

These are logical file lengths, not allocated disk blocks. Entire output includes intermediate runs, source projections, logs and any world files. World-only bytes cover the rendered tile and halo; they are not final assembled-world storage. Selected source bytes cover the raw OSM snapshot, while auxiliary masks, templates and code are pinned in the frozen job specification.

The rural roads stage took 817.056 seconds, about 92.6% of its full measured wall time; its writer took 56.943 seconds. Coast roads took 67.707 seconds and its writer 72.869 seconds. CBD V2's completed stages before the writer totaled 312.077 seconds by adapter stage timers: buildings 64.172, building mask 16.369, roads 231.072, plus projection. Its failed writer consumed another 16.228 seconds. Stage values are wall timers, without separately measured stage CPU or memory; interstage orchestration explains differences from the full Job measurement.

The observed successful-case range is 156.319 to 882.418 seconds. It describes these two cases only. No national ETA or land estimate is available: dense generation did not complete, there is only one sample per completed class, and the 1716 administrative-mask jobs have unverified land-intersecting and water-only counts. Changi coast is not a water-only benchmark. Rural/coast share V1 code and settings hashes; CBD V2 has different code and settings hashes, so even a future separately repaired candidate cannot silently fill that compatible sample set. Source export, inventory hashing, independent validation and final merge are outside the measured generator interval. Terrain remains flat-provisional Y0.

[fidelity-benchmark-1024-measured.json](fidelity-benchmark-1024-measured.json) contains exact metrics, receipt and gate hashes, relative evidence locations, per-stage wall times, core bounds, code/settings hashes and unavailable-estimate reasons. Its two accepted cases were loaded through the strict receipt loader against their unchanged live output inventories and referenced proofs. Failed receipts are excluded from successful-throughput calculations.
