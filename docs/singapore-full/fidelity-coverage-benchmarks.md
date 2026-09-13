# Rural and Coast1024 measured coverage

The two independently accepted experimental cores cover **2,097,152 m2 (2.097152 km2)** in total. They are **two disconnected areas**, with zero overlap. All measured core area lies inside the frozen derived administrative mask; overlap with the explicit foreign exclusions is 0 m2.

| Accepted core | Global X/Z bounds | Counted area | Core chunks |
| --- | --- | ---: | ---: |
| Rural, Lim Chu Kang | [13312,13312,14336,14336] | 1,048,576 m2 | 4,096 |
| Coast, Changi | [45056,18432,46080,19456] | 1,048,576 m2 | 4,096 |

Each generated render has a 128 m halo and 6,400 allocated chunks. Only the independently compared 1024 m core is counted. Halos and padded region extents contribute no area. The optional Market Street candidate is excluded from this measurement.

This is **0.12794797046%** of the frozen derived administrative mask, leaving **1,636,969,096.98 m2** without admitted core coverage. Administrative coverage includes waters. Land area and water area are separately marked unmeasured because this mask is not a land mask. Both disconnected eastern administrative components remain uncovered.

`fidelity-coverage-benchmarks-measured.json` contains the exact proof bindings and per-component geometry. Current world bytes and headers were checked: 18 inventoried files and 26,346,217 bytes for Rural; 18 files and 26,346,219 bytes for Coast. All 4,096 expected owned-core headers exist in each world, with valid nonoverlapping sector allocations.

The adapter pins and checks each gate, summary, receipt, frozen job, writer manifest and prior oracle proof. It requires the exact full-volume core comparison, zero cell/heightmap/metadata failures, matching writer/oracle output maps and actual world file hashes. It preserves the accepted prior source-run hash maps. **Source runs and voxel cells were not rescanned, so this is not renewed source admission.** The unchanged strict Node loader was read as the binding reference but was not invoked because it would rehash large source runs.

Country semantics, runtime, visual, terrain, facade and full-world acceptance remain false. These are experimental noncontiguous core extents, not an assembled national world.

## Reproduction

Use the existing data Python environment with Shapely and pyproj:

```text
python tools/singapore-full/validate-coverage-benchmarks.py
  --benchmark GATE_PATH GATE_SHA256 SUMMARY_PATH SUMMARY_SHA256
  --benchmark GATE_PATH GATE_SHA256 SUMMARY_PATH SUMMARY_SHA256
  --boundary DERIVED_WGS84_MASK --boundary-sha256 SHA256
  --foreign-exclusions WGS84_EXCLUSIONS --foreign-sha256 SHA256
  --out REPORT.json
```

For this report, gates and summaries are in fidelity worktree `.work/fidelity/rural1024-validation-1` and `coast1024-validation-1`.

| Evidence | SHA256 |
| --- | --- |
| Rural gate | 154dd2cd25076a8a8de2efba95e5d932f86b7031c9d9985ba23cedbdfca1c793 |
| Rural summary | 1bc35a966781057a2364c830109c6ee9b3df07a258bb078d226bbb99c76809a2 |
| Coast gate | dcbc3a2c189d5162dc12daae4968e34aee487d987692f2f89f6eef4cbf08dfdb |
| Coast summary | 36b6c00d08eb6526d54226571d4d1a55891b3ecbeb73515aadd31e5fc4371a03 |
| Derived mask | aa88ef3ad093817290ca48180fd21d00d1532fdfe89e9a10dab8fac3006676d1 |
| Foreign exclusions | ec62589d40215c460635ad7523c803dcd1597cf935be928dc6340b597d7b46af |

Four focused adapter tests cover core-only area, rejecting halo/render bounds, rejecting an unrelated summary gate and rejecting partial-volume or failed-heightmap evidence. The actual two-core metadata audit passes. Existing LF-pinned run fixtures and root attributes were not edited.
