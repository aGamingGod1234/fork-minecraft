# Singapore CBD preview evidence

The separate Minecraft 26.1.2 CBD preview was assembled and runtime checked on 13 September 2026. Its public archive passed an independent audit. The current gameplay/capture save was not changed.

- Owned global SVY21 block extent: `[29696,29696,30720,30720)`, 1,024 × 1,024 metres, 4,096 chunks.
- Frame: EPSG:3414, X=easting, Z=60000−northing, one block per metre.
- Assembly: exact owned chunks copied from the independently checked 6,400-chunk source, with the 128-block halo removed.
- Safe spawn: `[30208,1,30208]`, stone floor and clear feet/head.
- Actual assembly: 18.453 seconds, 82,186,240-byte sampled child RSS, one CPU and a 1 GiB job limit.
- Actual runtime: isolated source copy, Minecraft 26.1.2, 15 selected chunks and 19 exact block checks including buildings, roads, water, terrain and spawn; clean server exit. Java ran for 11.891 seconds inside a 3 GiB job limit. The immutable candidate was unchanged.

The full source core has an independent 402,653,184-cell geometry proof. The runtime check is representative and does not mean every owned chunk was loaded by the server. No client visual or AI gameplay acceptance is implied.

The preview uses provisional flat ground, available source heights plus declared estimates, and generic facades rather than photograph-matched materials. It retains all 598 road/source diagnostic records, including 15 unresolved lateral impacts and an explicitly quarantined malformed woodland relation outside the render area. Source, route, global geometry and full fidelity completeness remain false. This is a CBD district preview, not the whole island or the eastern extension.

## Frozen public artifact

`FORK-Singapore-CBD-1024-public-v1.zip` — 2,365,899 bytes

SHA256: `04734b7190e73da203ffb93d8d7b71b4fea24dbcadc9616b5ef924fb04b11b13`

The archive contains six unchanged world payload files, the unchanged original README, `WORLD-SOURCES.txt`, `INSTALL-WORLD.txt` and `SOURCE-OMISSIONS.json`. It names a separate save, includes OpenStreetMap/ODbL attribution and the frozen source chain, and preserves all 598 omission records with one disclosed unnecessary contact-tag redaction. The independent archive audit verified CRC, safe paths, exact payload/omission equality, public metadata and all evidence bindings. Private receipts and machine paths are excluded.

## Evidence digests

| Evidence | SHA256 |
| --- | --- |
| Source structural gate | `48300a15b3b9ed7e5a503acfda60a4501f41d88a165ef6e85d774d0ac0a4e228` |
| Source writer manifest | `c3b920380bfc0882e1cb86aba0e76b4b3147f6de45e35002e160c07157290fd2` |
| Grown world manifest | `ab94682d963f390fc58cb390ef1e7a7fd6922f9c6aae2d6b2986678e7191dd6f` |
| Immutable world tree | `416028b2479b2632d03b52d5cd11d79595a3e32457e5dce79a66498cc4dfa471` |
| Runtime receipt | `61591c63574eb76cd5906d5ad666376e4c033d52fe9a57ba952aa54f1f9dd649` |
| Runtime plan file | `5a2a2bd2cb5b17fe544ab14d0e6364fbe5265dbc02f07e16958e603610d84b0d` |
| Private public-package receipt | `6fa1d0a0705197a3c88439a314ab6d19e8187c378bcbc4edf9e5d33576a43bd5` |

All crop and server processes exited, and their shared resource reservations were released. The public archive was handed to Main for publication; this record does not itself assert a hosting URL.
