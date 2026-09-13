# FORK Singapore Map
This is a separate actual Minecraft 26.1.2 world for the film locator shot. It is a cartographic miniature of Singapore's generalized main island, not a claim of whole-island 1:1 coverage. Gameplay remains in the separate Market Street world.

## Install and capture
1. On Laptop, close Minecraft before copying the complete folder `assets/fork-locator/artifacts/singapore-map-v1/FORK - Singapore Map` into the desired instance's saves directory. Keep its name distinct. Do not overwrite the Market Street save.
2. Launch with Minecraft 26.1.2. The world uses DataVersion 4790, including the current `dimensions/minecraft/overworld/region` layout.
3. It starts in creative mode at (128,68,128), on the island's solid floor at Y67. It has peaceful difficulty, frozen noon, clear weather and disabled mobs.
4. For capture, use 16:9 and FOV50. Suggested render distance is 16 chunks. Run `/gamemode spectator`, then `/tp @s 128 220 128 180 90`. This is north-up, with -Z at the screen top. Hide HUD using F1. No automatic launch or UI actions are included.
5. Whole-island camera geometry at eye Y220 sees 253.67 x 142.69 blocks across the Y67 land plane. The 220 x 111.73 silhouette fits. Player teleport specifies feet, so the first-person eye is slightly higher and adds framing margin. Use a 16:9 window; aspect below 1.542 crops the width.
6. A second top-down keyframe is `/tp @s 145.5 110 173.5 180 90`. The gold marker reaches Y70. Record/capture/render only on Laptop.

## Geography and appearance
Made with Natural Earth. Admin 0 Countries v5.1.1, 1:10m, public domain.
Source: https://www.naturalearthdata.com/downloads/10m-cultural-vectors/10m-admin-0-countries/
Terms: https://www.naturalearthdata.com/about/terms-of-use/
The exact 40-vertex canonical outline in media/edit/locator-film-v3.svg is rasterized at block centres without invented neighbourhoods. Offshore islands are not separately depicted. The ocean uses blue concrete; land uses prismarine, dark prismarine sides and a pale prismarine-brick coast. Land rises four blocks above the ocean plane.
SVG X maps to Minecraft X and SVG Y maps to Minecraft Z. The transform is X=18+(svgX-80)*220/1150, Z=128+(svgY-550)*220/1150. Approximate display scale is184 metres per block, never1:1.
The marker uses the accepted district source centre at latitude1.283, longitude103.85. Its exact miniature position is (145.0224604574,172.5363046939), rounded to block (145,173). It is not a surveyed court coordinate. The gold dot is deliberately oversized for legibility.

## Verification and limitations
The manifest records source byte hashes, the transform, world size, and every generated world file SHA256. Windows checkout line endings are recorded in the source byte hash; the generator separately pins the LF-normalized canonical SVG hash.
All1024 chunks across four region files are decoded after writing. All262144 surface columns are checked against expected material assignment. Spawn is on solid land. The world has no copied player/account data, entities, stats, advancements or session.lock. Only the stopped template's level schema and selected vanilla world settings are used.
Live Minecraft load and the final visual shot still require Laptop verification. The source outline is a generalized geographic locator and makes no claim of current surveyed coastline or full offshore coverage.
