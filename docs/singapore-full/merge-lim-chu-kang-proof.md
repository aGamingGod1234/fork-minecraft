# Lim Chu Kang: first 1024-metre save

On 13 September 2026 at 18:08 SGT, the separate **FORK - Lim Chu Kang** save passed its representative Minecraft Java 26.1.2 runtime check and was packaged successfully.

The owned core is `[13312,13312,14336,14336)` in the shared EPSG:3414 frame, where Minecraft X is easting and Z is 60000 minus northing. Its 4,096 chunks cover 1024 by 1024 horizontal metres. The source render contained 6,400 chunks with a 128-metre halo; assembly excluded the halo and preserved global chunk coordinates.

This is mapped building, road, landcover and water geometry on **flat provisional ground**, with declared estimated building heights and quarantined omissions. It is not complete Singapore, a claim of exact visual fidelity, or an AI-agent gameplay build. Client visual review remains pending.

Evidence retained under the private full-Singapore project:

| Artifact | Relative path / SHA-256 |
|---|---|
| Package | `merged/grow-lim-chu-kang-v1/FORK-Lim-Chu-Kang-1024.zip` |
| Package SHA-256 | `bc2d1ac5a24a066d7091d4b7ff93e90e84e12b60f19004b8079693f1c6b6e7ae` |
| Package size | 1,747,503 bytes |
| World snapshot | `merged/grow-lim-chu-kang-v1/world` |
| World tree digest | `ac67de6f01ff234ef50db5964c0abc5415097f260f6b1a06c0ebb902d6e8a9fc` |
| Runtime receipt | `runtime-check/lim-chu-kang-v1/runtime-receipt.json` |
| Runtime receipt SHA-256 | `8addedab29641cfa576a68bbd707d7a34f969f6ec086f8bb927e18086fc7c935` |

The independent source gate compared all 402,653,184 owned-core cells across the full 384-block height, with zero mismatches and verified input/output bindings. Its gate SHA-256 is `154dd2cd25076a8a8de2efba95e5d932f86b7031c9d9985ba23cedbdfca1c793`.

Crop assembly used a 1 GiB process-tree cap and one CPU at offset 8. It finished in 12.234 seconds, with the main copying process peaking at 57,896,960 resident bytes. Source files and coverage proofs were rechecked after exact canonical chunk copying. Only the new save's display name changed; its verified modern spawn and external generation settings were preserved.

Runtime used a separate copy, Java 25 and the pinned Minecraft 26.1.2 server JAR. It confirmed 15 selected chunks and 19 actual NBT-proven building, road, water, terrain and spawn checks, including all four quadrants. The server exited cleanly with code 0 after 11.297 seconds. It did **not** runtime-load all 4,096 owned chunks. The whole controlled tree had a 3 GiB cap, one CPU at offset 8, and at least 15,027,892,224 bytes of free host RAM during the check.

The exact commands sent to server stdin and sentinel definitions are retained and hashed in the runtime receipt. The candidate world remained unchanged. The final ZIP was reopened: all six world-file payload hashes matched the candidate, the ZIP had zero CRC errors, and the only additional entry was the installation/qualification README.

All runtime and packaging processes exited. The active filming world and Laptop were not modified.
