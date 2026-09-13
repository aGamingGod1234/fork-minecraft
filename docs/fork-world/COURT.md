# FORK court v1

Fictional, bounded neighbourhood court. White clinic with cyan lintel on the west; gray workshop with amber lintel on the east; a dark generator plinth and yellow fork stem in the south. Glass clinic windows and a broad open entrance make the medic readable. All materials are vanilla plain blocks. This is a placement specification, not a generated Minecraft save or a verified playable installation.

## Gameplay import

Read `data/fork-world/court-v1.json`. Initialize all 4096 positions to palette index 0 (air), then overlay each `[x,y,z,paletteIndex]` cell. Coordinates are relative integers. Add origin `[0,64,0]` in `minecraft:overworld` with no rotation. The maximum included absolute cell is `[15,79,15]`. Never infer an unbounded clear command. Freeze the absolute transform with the accepted world/checkpoint; do not rebase during rewind.

438 non-air cells; 3658 air cells; ground is relative y=0. Actors retain contract anchors. Clinic entrance x=3..4,z=5 is open, with a counter to each side and three clear blocks above the walking cells. Courier route x=3,z=12..3 then x=4,z=3 has clear headroom. Workshop entry is open south. Routes are flush cyan/amber/yellow floor blocks, with no scored electrical behavior. Generator is a plain decorative plinth, not a block entity. Sea lanterns are constant decorative lighting, not claims that power is on.

Only the eleven listed upper cornice cells may be removed by the paused facade demonstration. Ground, counters, generator and actor stations are excluded. After restore, verify every air and non-air cell, actor identity/anchor and scenario state. The six outside sentinel coordinates are relative observation points, not placement instructions; record their existing block states and verify unchanged.

Camera positions and look targets are relative, measured in blocks. Add origin to both before creating client camera artifacts. Camera acceptance must also bind the pristine world hash, dimension, fixed origin and arrival. Stop cameras before rewind/travel. World hash and actual game camera clearance are unrun, so these anchors are preparation only. The only public place is the fictional court; no latitude/longitude marker is invented.

Travel is only at rounds 0/6 without pending work; only the human moves. Require loaded, safe arrival; preserve actors/resources/branch/results; keep return available during load and preserve the last accepted view on failure. Main/Gameplay implement and verify those controls.

## Reproducible fixture

Run `node tools/fork-world/test-court.mjs`. The test pins the JSON SHA256 and checks bounds, floor, anchors, three-block route headroom, connected walking space, demolition scope, outside sentinels and three in-memory full-volume restores including an introduced obstruction in an air cell. These are specification checks, not actual game restore evidence.

`node tools/fork-world/build-court.mjs` deterministically reproduces the specification. Do not change the frozen hash without a newly accepted layout. Actual materialization, human walking, camera inspection and three live restores remain unrun.
