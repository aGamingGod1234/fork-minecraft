---
name: minecraft-control
description: Control the embodied Minecraft player through factual observations, native tools, and bounded input programs.
---

# Minecraft player control

Use native tools to act in the world. Plain assistant text has no Minecraft effect. You choose targets, routes, resources, reactions, and retries. The executor applies the inputs you authorize and reports what happened.

## Read facts, choose, act, verify

1. Read the current goal, newest event, and last result. Use capabilities for supported fields, runtime availability, and requested versus effective execution settings. Observe repeats the effective settings; a provider mapping does not change your selected identity.
2. Use observe for a new sample and inspect for details. Check freshness, coverage, world identity, dimension, and revisions before relying on a fact.
3. Choose a tool or bounded program using observed targets and your own strategy. Sequence only steps whose arguments are already known; inspect results before choosing dependent actions.
4. Distinguish accepted input, attempted use, projectile spawn, verified effect, and verified goal. Finish asks the server to check the immutable goal contract. A failed check leaves it active.

## Observations and memory

Observe reports freshness.fresh for the sample barrier. False identifies cached facts. rememberedSections identifies older sections retained during death. A missing entity, block, or slot may be omitted by coverage limits rather than absent. Images and hidden server state are outside this interface.

Inspect supports inventory, menu, entities, blocks, landmarks, nearby_containers, item, block, events, recipes, and mechanics. Pages use offset 0..4096 and limit 1..32. Item detail requires slot; block detail requires visible x/y/z. Events accept afterSequence for newly delivered player-accessible events. Recipes lists installed rules; an exact recipeId retrieves its ingredient, result, and workstation display details with explicit coverage. Mechanics reports installed version and current native player attributes and abilities. Recipe rules do not reveal hidden resources or positions. Copy returned stack fingerprints, containerId, stateId, raw slot indexes, target identities, and hit geometry. Continue a shortened page using nextOffset; unreturned details remain unknown.

ExploreFrontier lists observed positions and unknown neighboring cells. It never chooses or travels to a destination. Reachability remains unknown until checked by the body. Choose coordinates explicitly with moveTo or input frames. Stored places are scoped to world and dimension and can become stale.

Notebook stores model-authored notes up to 2048 characters. QueryMemory returns historical notes and action receipts with separate provenance; pass offset or the returned nextOffset to continue a page. DISPATCHED and UNKNOWN are unresolved operations, not successful effects. Query kind unresolved retrieves their arguments; observe and capabilities report a bounded summary and count. Receipts distinguish server results from coordinator uncertainty and report evictions. Notes are hypotheses or remembered plans, not proof of current state. After reconnect or context replacement, compare uncertain operations with fresh facts before deciding whether to retry. Death retains the goal where lifecycle policy permits it; current inventory, lastDeath, and lastLostInventory are distinct facts.

## Input ownership and precision

Control holds a complete frame for 1 to 200 server ticks. Supply every input, including false buttons, view, hand, and selectedSlot. Completion, cancellation, and expired leases release input. Vanilla game-mode and interaction restrictions remain authoritative.

Act with control_sequence runs 1 to 64 complete frames with maxTicks from 1 to 2000. Each frame has 1 to 200 ticks and up to 16 optional branches. The server checks branches before applying each tick. The first matching branch jumps to its zero-based nextFrame; an index equal to the frame count stops. A jump resets that frame duration. Backward jumps remain bounded by maxTicks. The complete action arguments must fit 32768 UTF-8 JSON bytes, including all frames, branches, or book pages. Per-field limits do not waive this total budget.

Branches read only your player facts. Numeric conditions are health_below (0..2048), food_below (0..20), and air_below (0..100000). Boolean conditions are on_fire, in_water, on_ground, horizontal_collision, hurt, and using_item. You author both condition and response. A completed frame program proves input execution; it does not prove your larger intended effect.

StartAction returns a handle immediately. ActionStatus reads its state or terminal receipt. CancelAction requires the exact actionId and goalRevision and waits for acknowledgement. ReplaceAction starts its replacement only after CANCELLED. If cancellation is unconfirmed or the old action already completed, reassess before another mutation.

RunProgram executes your ArenaScript using the same interpreter as script mode. Source is at most 65536 UTF-8 bytes; maxActions defaults to 64 and caps at 256; timeoutMs defaults to 30000 and caps at 120000. Author an explicit program.onUnhandledAttention mode: continue_and_notify completes the current action before yielding; pause_and_notify cancels it before yielding. Watchers, branches, selected targets, and reactions come from your source. No other model plans its steps. Player calls await correlated physical results and new observation barriers; world.inspect and world.queryMemory are bounded reads, world.remember writes your notes. A deadline or lifecycle change stops further steps and requests release of its physical action; unknown acknowledgement remains uncertain. Program completion or program.finish yields to you; use the separate finish tool for factual goal verification.

## Interaction details

Mining requires an observed, visible, reachable non-air expectedBlockId. A broken block does not prove its drop entered inventory. Melee attempts and bow release do not prove a hit. Use effect evidence, inventory changes, and fresh observations.

Menu clicks use the current menuId, containerId, stateId, raw slot, button, clickType, expectedItemId, and expectedCount. Include expectedFingerprint for exact variants. Inspect after a click because cursor, slots, costs, and options can change. Generic clicks preserve a held cursor; close explicitly when appropriate. Provide containerId and stateId together for legacy menu operations. A rejected or partial operation is not automatically safe to repeat.

Block hit offsets are within the block from 0 to 1. Entity hit offsets are relative to the observed entity position. Copy usable hit geometry and choose the hand explicitly. Sign writes compare expectedLines; book edits compare expectedFingerprint. A book title signs it. Beacon effects use observed legal effect IDs or none. Mechanics still depend on current world, menu, and game-mode state.

## Tool examples

These examples demonstrate accepted syntax, not a world script. Replace illustrative coordinates, UUIDs, handles, slots, revisions, and fingerprints with actual observed references. Numeric arguments are literal values.

### observe

Request a fresh player observation. Read freshness and coverage; an unavailable freshness barrier returns explicitly stale cached facts.

```json executor-call
{"tool":"observe","arguments":{}}
```

### capabilities

List the versioned action fields, query sections, limits, and runtime support for this player.

```json executor-call
{"tool":"capabilities","arguments":{}}
```

Read the shared ArenaScript language and API reference before writing runProgram source.

```json executor-call
{"tool":"capabilities","arguments":{"section":"program"}}
```

### inspect

Request a focused page of player-accessible facts. Item queries need a slot; block queries need visible x/y/z coordinates. Read coverage and freshness.

```json executor-call
{"tool":"inspect","arguments":{"section":"inventory","offset":0,"limit":16}}
```

```json executor-call
{"tool":"inspect","arguments":{"section":"recipes","recipeId":"minecraft:crafting_table","offset":0,"limit":16}}
```

### actionStatus

Inspect the active action or a retained terminal receipt without changing the player.

```json executor-call
{"tool":"actionStatus","arguments":{"actionId":"native:agent-a:3:7"}}
```

### cancelAction

Cancel the exact active handle and wait for its authoritative terminal result. A stale handle cannot cancel another action.

```json executor-call
{"tool":"cancelAction","arguments":{"actionId":"native:agent-a:3:7","goalRevision":3}}
```

### replaceAction

Cancel the exact active handle, wait for acknowledgement, then execute your replacement. No replacement runs after uncertain cancellation.

```json executor-call
{"tool":"replaceAction","arguments":{"actionId":"native:agent-a:3:7","goalRevision":3,"actionType":"look_at","arguments":{"x":12,"y":65,"z":12}}}
```

### startAction

Start one model-chosen action and return its handle immediately. Poll actionStatus for the factual result or cancel the exact handle.

```json executor-call
{"tool":"startAction","arguments":{"actionType":"wait","arguments":{"durationMs":500}}}
```

### notebook

Save or replace one model-written note of up to 2048 characters in this agent and world. Notes are hypotheses or plans, never authoritative game evidence.

```json executor-call
{"tool":"notebook","arguments":{"key":"return-route","text":"Observed bridge at 12, 64, 12 in the Overworld. Recheck before crossing."}}
```

### queryMemory

Read this agent and world's saved notes and action receipts. Continue pages with nextOffset. Memory records are historical, not fresh world observations.

```json executor-call
{"tool":"queryMemory","arguments":{"kind":"notes","text":"bridge","offset":0,"limit":10}}
```

```json executor-call
{"tool":"queryMemory","arguments":{"kind":"unresolved","offset":0,"limit":10}}
```

### runProgram

Run your bounded ArenaScript through the shared interpreter. This example reads the current player state, executes one model-chosen wait, and yields at source exhaustion.

```json executor-call
{"tool":"runProgram","arguments":{"source":"program.onUnhandledAttention(\"pause_and_notify\"); const self = player.state(); if (self.health > 0) { await player.wait(50); }","maxActions":4,"timeoutMs":5000}}
```

### lookAround

Turn the player through 2 to 8 short camera steps; call observe afterward to inspect the newly visible landmarks.

```json executor-call
{"tool":"lookAround","arguments":{"centerYaw":90,"pitch":0,"steps":4,"ticksPerStep":3}}
```

### control

Hold one complete player input frame for 1 to 200 server ticks. Use for precise movement, jumps, attacks, item use, view, and hotbar control.

```json executor-call
{"tool":"control","arguments":{"forward":1,"strafe":0,"jump":true,"sneak":false,"sprint":true,"attack":false,"use":false,"yaw":0,"pitch":0,"selectedSlot":0,"hand":"main","ticks":8}}
```

### moveTo

Navigate toward one short, confirmed waypoint through bounded loaded safe waypoints; use control for ordinary exploration.

```json executor-call
{"tool":"moveTo","arguments":{"x":12,"y":64,"z":12,"tolerance":1,"sprint":true,"timeoutMs":30000}}
```

### exploreFrontier

List factual observed or unknown adjacent-space candidates. This tool never chooses or executes a destination; choose explicitly with moveTo.

```json executor-call
{"tool":"exploreFrontier","arguments":{"radius":24,"limit":16}}
```

### mine

Mine one observed, visible, in-range block coordinate with its exact current blockId.

```json executor-call
{"tool":"mine","arguments":{"x":11,"y":64,"z":10,"expectedBlockId":"minecraft:oak_log","timeoutMs":15000}}
```

### say

Send public chat, a private message, or nearby proximity speech.

```json executor-call
{"tool":"say","arguments":{"message":"I found the marked chest.","audience":"proximity"}}
```

### wait

Pause briefly and wait for the body result.

```json executor-call
{"tool":"wait","arguments":{"durationMs":500}}
```

### act

Execute one supported advanced player action. Supply exactly the fields required by that actionType.

```json executor-call
{"tool":"act","arguments":{"actionType":"wait","arguments":{"durationMs":500}}}
```

### sequence

Prefer sequence for safe 2+ action chains. Execute 2 to 8 exact model-authored actions in order, stopping on the first factual failure; use separate calls when a later step needs fresh facts.

```json executor-call
{"tool":"sequence","arguments":{"actions":[{"actionType":"navigate_to","arguments":{"x":11,"y":64,"z":10,"tolerance":1,"sprint":true,"timeoutMs":30000}},{"actionType":"break_block","arguments":{"x":11,"y":64,"z":10,"expectedBlockId":"minecraft:oak_log","timeoutMs":15000}}]}}
```

### finish

Ask Minecraft to verify the immutable active goal. A failed check keeps the goal active.

```json executor-call
{"tool":"finish","arguments":{"summary":"Crafted and collected the iron pickaxe."}}
```

## Advanced action reference

Call through act, startAction, or a sequence step. Optional fields may be omitted. Capabilities lists the current action contract.

### move_to

Fields: `x`, `y`, `z`, `tolerance`, `sprint`.

```json executor-call
{"tool":"act","arguments":{"actionType":"move_to","arguments":{"x":12,"y":64,"z":12,"tolerance":1,"sprint":true}}}
```

### control

Fields: `forward`, `strafe`, `jump`, `sneak`, `sprint`, `attack`, `use`, `yaw`, `pitch`, `selectedSlot`, `hand`, `ticks`.

```json executor-call
{"tool":"act","arguments":{"actionType":"control","arguments":{"forward":0,"strafe":1,"jump":false,"sneak":true,"sprint":false,"attack":false,"use":true,"yaw":90,"pitch":15,"selectedSlot":3,"hand":"off","ticks":10}}}
```

### control_sequence

Fields: `frames`, `maxTicks`.

```json executor-call
{"tool":"act","arguments":{"actionType":"control_sequence","arguments":{"frames":[{"forward":0.5,"strafe":0,"jump":false,"sneak":false,"sprint":false,"attack":false,"use":false,"yaw":90,"pitch":0,"selectedSlot":0,"hand":"main","ticks":20,"branches":[{"condition":"horizontal_collision","value":true,"nextFrame":1}]},{"forward":0,"strafe":0,"jump":false,"sneak":false,"sprint":false,"attack":false,"use":false,"yaw":90,"pitch":0,"selectedSlot":0,"hand":"main","ticks":1}],"maxTicks":40}}}
```

### look_at

Fields: `x`, `y`, `z`.

```json executor-call
{"tool":"act","arguments":{"actionType":"look_at","arguments":{"x":12,"y":65,"z":12}}}
```

### attack

Fields: `targetId`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"attack","arguments":{"targetId":"550e8400-e29b-41d4-a716-446655440000","timeoutMs":15000}}}
```

### select_item

Fields: `itemId`.

```json executor-call
{"tool":"act","arguments":{"actionType":"select_item","arguments":{"itemId":"minecraft:oak_log"}}}
```

### use_item

Fields: `durationMs`, `hand` optional, `expectedItemId` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"use_item","arguments":{"durationMs":1000,"hand":"main","expectedItemId":"minecraft:apple"}}}
```

### break_block

Fields: `x`, `y`, `z`, `expectedBlockId`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"break_block","arguments":{"x":11,"y":64,"z":10,"expectedBlockId":"minecraft:oak_log","timeoutMs":15000}}}
```

### pick_up_item

Fields: `targetSelector`.

```json executor-call
{"tool":"act","arguments":{"actionType":"pick_up_item","arguments":{"targetSelector":"550e8400-e29b-41d4-a716-446655440000"}}}
```

### place_block

Fields: `x`, `y`, `z`, `face`, `itemId`, `desiredState` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"place_block","arguments":{"x":11,"y":64,"z":10,"face":"up","itemId":"minecraft:cobblestone","desiredState":"minecraft:cobblestone"}}}
```

### chat

Fields: `message`, `audience` optional, `recipientId` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"chat","arguments":{"message":"I found the cave.","audience":"direct","recipientId":"550e8400-e29b-41d4-a716-446655440000"}}}
```

### wait

Fields: `durationMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"wait","arguments":{"durationMs":500}}}
```

### set_door

Fields: `x`, `y`, `z`, `open`.

```json executor-call
{"tool":"act","arguments":{"actionType":"set_door","arguments":{"x":11,"y":64,"z":10,"open":true}}}
```

### drop_item

Fields: `slot`, `count`.

```json executor-call
{"tool":"act","arguments":{"actionType":"drop_item","arguments":{"slot":9,"count":1}}}
```

### navigate_to

Fields: `x`, `y`, `z`, `tolerance`, `sprint`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"navigate_to","arguments":{"x":12,"y":64,"z":12,"tolerance":1,"sprint":true,"timeoutMs":30000}}}
```

### transfer_container

Fields: `x`, `y`, `z`, `sourceKind`, `sourceSlot`, `destinationKind`, `destinationSlot`, `count`, `expectedItemId`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"transfer_container","arguments":{"x":11,"y":64,"z":10,"sourceKind":"player","sourceSlot":9,"destinationKind":"container","destinationSlot":0,"count":1,"expectedItemId":"minecraft:iron_ingot","timeoutMs":15000}}}
```

### craft_inventory

Fields: `recipeId`, `count`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"craft_inventory","arguments":{"recipeId":"minecraft:oak_planks","count":4,"timeoutMs":15000}}}
```

### craft_table

Fields: `recipeId`, `x`, `y`, `z`, `count`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"craft_table","arguments":{"recipeId":"minecraft:iron_pickaxe","x":11,"y":64,"z":10,"count":1,"timeoutMs":30000}}}
```

### furnace_transaction

Fields: `x`, `y`, `z`, `operation`, `inventorySlot`, `count`, `expectedItemId`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"furnace_transaction","arguments":{"x":11,"y":64,"z":10,"operation":"insert_input","inventorySlot":9,"count":1,"expectedItemId":"minecraft:raw_iron","timeoutMs":15000}}}
```

### equip_item

Fields: `sourceSlot`, `targetSlot`, `expectedItemId`.

```json executor-call
{"tool":"act","arguments":{"actionType":"equip_item","arguments":{"sourceSlot":9,"targetSlot":"head","expectedItemId":"minecraft:iron_helmet"}}}
```

### select_tool

Fields: `sourceSlot`, `hotbarSlot`, `expectedItemId`, `minRemainingDurability`.

```json executor-call
{"tool":"act","arguments":{"actionType":"select_tool","arguments":{"sourceSlot":9,"hotbarSlot":0,"expectedItemId":"minecraft:iron_pickaxe","minRemainingDurability":1}}}
```

### block_with_shield

Fields: `durationMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"block_with_shield","arguments":{"durationMs":2000}}}
```

### use_ranged

Fields: `targetId`, `drawDurationMs`, `timeoutMs`.

```json executor-call
{"tool":"act","arguments":{"actionType":"use_ranged","arguments":{"targetId":"550e8400-e29b-41d4-a716-446655440000","drawDurationMs":1000,"timeoutMs":15000}}}
```

### interact_block

Fields: `x`, `y`, `z`, `face`, `hand`, `expectedItemId`, `hitX` optional, `hitY` optional, `hitZ` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"interact_block","arguments":{"x":11,"y":64,"z":10,"face":"up","hand":"main","expectedItemId":"minecraft:bucket","hitX":0.5,"hitY":1,"hitZ":0.5}}}
```

### interact_entity

Fields: `targetId`, `hand`, `expectedItemId`, `hitX` optional, `hitY` optional, `hitZ` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"interact_entity","arguments":{"targetId":"550e8400-e29b-41d4-a716-446655440000","hand":"main","expectedItemId":"minecraft:wheat","hitX":0,"hitY":0.8,"hitZ":0}}}
```

### dismount

No arguments.

```json executor-call
{"tool":"act","arguments":{"actionType":"dismount","arguments":{}}}
```

### start_fall_flying

No arguments.

```json executor-call
{"tool":"act","arguments":{"actionType":"start_fall_flying","arguments":{}}}
```

### wake_up

No arguments.

```json executor-call
{"tool":"act","arguments":{"actionType":"wake_up","arguments":{}}}
```

### set_flight

Fields: `enabled`.

```json executor-call
{"tool":"act","arguments":{"actionType":"set_flight","arguments":{"enabled":true}}}
```

### write_sign

Fields: `x`, `y`, `z`, `front`, `lines`, `expectedLines`.

```json executor-call
{"tool":"act","arguments":{"actionType":"write_sign","arguments":{"x":11,"y":64,"z":10,"front":true,"lines":["Storage","","",""],"expectedLines":["","","",""]}}}
```

### edit_book

Fields: `slot`, `pages`, `title` optional, `expectedFingerprint`.

```json executor-call
{"tool":"act","arguments":{"actionType":"edit_book","arguments":{"slot":0,"pages":["The bridge is at 12, 64, 12."],"title":"Travel notes","expectedFingerprint":"7ae1bde6c0fc2a4f34d8ad4d405bf364674c4129b8f76164a01eac465c740fd2"}}}
```

### menu_click

Fields: `menuId`, `containerId`, `stateId`, `slot`, `button`, `clickType`, `expectedItemId`, `expectedCount`, `expectedFingerprint` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"menu_click","arguments":{"menuId":"minecraft:generic_9x3","containerId":2,"stateId":7,"slot":0,"button":0,"clickType":"PICKUP","expectedItemId":"minecraft:iron_ingot","expectedCount":1,"expectedFingerprint":"7ae1bde6c0fc2a4f34d8ad4d405bf364674c4129b8f76164a01eac465c740fd2"}}}
```

### menu_close

Fields: `menuId`, `containerId`, `stateId`.

```json executor-call
{"tool":"act","arguments":{"actionType":"menu_close","arguments":{"menuId":"minecraft:generic_9x3","containerId":2,"stateId":8}}}
```

### beacon_effects

Fields: `menuId`, `containerId`, `stateId`, `primaryEffectId`, `secondaryEffectId`.

```json executor-call
{"tool":"act","arguments":{"actionType":"beacon_effects","arguments":{"menuId":"minecraft:beacon","containerId":2,"stateId":8,"primaryEffectId":"minecraft:speed","secondaryEffectId":"none"}}}
```

### menu_transfer

Fields: `menuId`, `sourceSlot`, `destinationSlot`, `count`, `expectedItemId`, `timeoutMs`, `containerId` optional, `stateId` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"menu_transfer","arguments":{"menuId":"minecraft:anvil","sourceSlot":0,"destinationSlot":1,"count":1,"expectedItemId":"minecraft:iron_ingot","timeoutMs":15000,"containerId":2,"stateId":7}}}
```

### menu_button

Fields: `menuId`, `buttonId`, `timeoutMs`, `containerId` optional, `stateId` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"menu_button","arguments":{"menuId":"minecraft:merchant","buttonId":0,"timeoutMs":15000,"containerId":2,"stateId":7}}}
```

### anvil_rename

Fields: `menuId`, `name`, `timeoutMs`, `containerId` optional, `stateId` optional.

```json executor-call
{"tool":"act","arguments":{"actionType":"anvil_rename","arguments":{"menuId":"minecraft:anvil","name":"Miner","timeoutMs":15000,"containerId":2,"stateId":7}}}
```

### respawn

No arguments.

```json executor-call
{"tool":"act","arguments":{"actionType":"respawn","arguments":{}}}
```

## Dependent calls and rejected inputs

A combined turn can use a known look coordinate and then read its observation. Wait for the observation before choosing an unseen target.

```json executor-calls
{"calls":[{"tool":"act","arguments":{"actionType":"look_at","arguments":{"x":12,"y":65,"z":12}}},{"tool":"observe","arguments":{}}]}
```

These inputs fail the tool boundary. A valid call can still fail current world checks.

```json executor-bad-call
{"tool":"observe","arguments":{"radius":10}}
```

```json executor-bad-call
{"tool":"inspect","arguments":{"section":"item"}}
```

```json executor-bad-call
{"tool":"inspect","arguments":{"section":"inventory","limit":33}}
```

```json executor-bad-call
{"tool":"control","arguments":{"forward":1,"ticks":8}}
```

```json executor-bad-call
{"tool":"cancelAction","arguments":{"actionId":"native:agent-a:3:7"}}
```

```json executor-bad-call
{"tool":"exploreFrontier","arguments":{"seek":"nether"}}
```

```json executor-bad-call
{"tool":"notebook","arguments":{"key":"route","text":""}}
```

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"menu_click","arguments":{"menuId":"minecraft:generic_9x3","containerId":2,"stateId":7,"slot":-1,"button":0,"clickType":"PICKUP","expectedItemId":"minecraft:iron_ingot","expectedCount":1}}}
```

```json executor-bad-call
{"tool":"act","arguments":{"actionType":"control_sequence","arguments":{"frames":[{"forward":0,"strafe":0,"jump":false,"sneak":false,"sprint":false,"attack":false,"use":false,"yaw":90,"pitch":0,"selectedSlot":0,"hand":"main","ticks":1}],"maxTicks":0}}}
```

```json executor-bad-call
{"tool":"say","arguments":{"message":"Hello","audience":"direct"}}
```
