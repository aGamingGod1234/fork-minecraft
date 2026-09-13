# FORK console and travel handoff

Current console/travel increment (11:45 handoff): the existing Field Console switches to Run, Locator and Director once its separate authoritative FORK status arrives. Main must register client initializer dev.fork.gameplay.ForkClient. A one-second server snapshot supplies actual mode, current and complete archived state, six service cells, six-round power histories, every metric, and the latest three proposal/validation/effect results. Previous/Next and wheel scrolling keep long details readable at small GUI sizes. All commands are revalidated by the server. Inspector/Compare command acknowledgments still appear in chat.

Locator renders the 40-vertex generalized Singapore main-island polygon supplied by World from Natural Earth Admin 0 v5.1.1 (public domain; Made with Natural Earth). Source: https://www.naturalearthdata.com/downloads/10m-cultural-vectors/10m-admin-0-countries/ . Offshore islands and Pedra Branca are not shown. This is not playable-world coverage and has no unaccepted court marker. Next shows coverage, accepted places, and travel status.

Visit is disabled unless packaged publicPlaces contains accepted:true, id, name, and arrival:[x,y,z]. Accepted IDs use lowercase letters/digits/underscore/hyphen. Coordinates are relative to the packaged court origin unless coordinateSpace:absolute is explicit. Dimension must match. World/Main currently has accepted no destinations. Do not mark a destination accepted without Main. The private corrected origin46,0,22 was observed but is not adopted by Gameplay.

Visit requires human at court, round0/6, all three bodies, no pause or pending work. It retains bounded chunk tickets for up to10seconds and checks world border, feet/head AIR, solid floor, hazards and the player's complete collision box. No teleport occurs until safe. Timeout/cancel/disconnect releases tickets and preserves position; Return cancels a pending visit before checking the court arrival. Return is a human safety action even if the human walked outside between rounds, and never commits a scored change. Power/advance/retry/demolish/rewind reject out-of-court use or outstanding visits. Actors, branch and resources remain unchanged during travel. Existing interaction fences apply outside too.

Director exposes the existing installed preset names with Play preset and Stop camera. Cinematic owns camera-paths.json and Main installs it before launch using the accepted World transform. No presets are invented here. Playback now advances on an independent unpaused client tick counter, so fixed world daylight cannot freeze camera motion. Main must still verify actual playback/restore on Laptop.

Focused recipient gate: compile main+client and verify entrypoints; open Run at small and normal GUI scales; complete equal six-round A/B and inspect all metrics; confirm Visit disabled with current unaccepted data; test Return, cancellation and outside-court advance rejection; test each installed preset and typed rewind/return camera stops. Actual approved Live whole-attempt20s timing and A-canary absence in real B requests remain pending, separate from passing pure tests.
Runtime repair increment: handshake snapshots now use Minecraft's server task executor instead of waiting for a gameplay tick. The five-second handshake deadline is unchanged. Authentication still validates the existing secret and registry revision. The suspected trigger is paused integrated-server gameplay; actual recipient paused/unpaused handshake and Live20-second proof must be recorded by Main.

The actionbar now shows only `FORK | MODE | Rn/6 | allocation | status` (under64characters). Inspect and compare retain full metrics. The fictional test court is not accepted as the final Singapore scene; new real-data geography remains a World/Main acceptance task.

The reused console Overview action area now has Clinic, Workshop, Advance, Cancel, Rewind, Inspect, Compare and Director buttons. All supported console sizes now expose the complete Run controls and paged detail rows. Buttons send FORK commands directly and return to the world so the server acknowledgment is visible. Console Rewind stops the existing camera playback before requesting server restore. Typed rewind and travel now send camera stop before restore/teleport; the console also stops playback before sending the command.

Main's repair verification: build main+client; launch the matched Laptop package; test coordinator handshake once while the world is active and once with Esc pause, retaining logs. Explicitly restart into `/fork new live`, choose power and advance. Coordinator logs now include `[FORK runtime]` attempt start, each role proposal with requested/effective reported profile, elapsed milliseconds, and batch/error result. A batch-sent entry is not the server receipt: verify the server's committed round/value too. All three roles must complete within the unchanged20-second whole-attempt budget. A timeout/error must leave the round/resources unchanged and allow exactly one explicit retry.

After a stopped-world restart, `/fork start` refuses to silently regenerate a previous session. `/fork new fixture` or `/fork new live` explicitly starts a fresh session, preserves earlier receipt/archive files and advances the persisted epoch. It does not resume the earlier round or claim an in-memory archive was restored. New role request/workspace IDs are isolated by fresh branch, epoch and request.

If a role body is absent for15seconds, FORK cancels pending work and pauses without a commit. `/fork recover` requests only missing bodies with another15-second bound; `/fork rewind` must then verify all three bodies and INITIAL before advance.

Main Fabric entrypoint: `dev.fork.integration.ForkEntrypoint`. Keep the existing Arena initializer. Client entrypoint: `dev.fork.gameplay.ForkClient`; retain the existing Arena client initializer and add it to Main verifyEntrypoints. Package accepted World JSON at `/data/fork/world/court-v1.json`. The diagnostic override is `-Dfork.courtFile=<accepted JSON>`.

Use a fresh dedicated FORK world/profile with no existing Arena registry actors. Main owns its single game/coordinator topology. `/fork start fixture` places exactly the accepted 16-cube at the origin in the accepted packaged JSON, creates three dedicated bodies through existing `OfflineAgentPlayers`, and captures INITIAL. No provider access is required for fixture bodies. This is an explicit diagnostic mode, not approval to submit fixtures.

Manual recipient action gate:

1. Launch the recipient package, enter its world and open chat as operator.
2. Main must position the human using the accepted World transform. Do not reuse old Y=65 with a new ground-zero world. After start, `/fork return` uses packaged court origin + [8.5,1,7.5] or an accepted playerArrival.
3. `/fork start fixture`. Wait until all three named bodies appear.
4. `/fork power workshop`.
5. `/fork advance`. Expected visible chat/HUD: FIXTURE, round 1/6, service 1_____, downtime 0, repair 1/3, charge 1. Workshop floor repair indicator changes.
6. `/fork inspect` shows the three proposals, validation and effects. Save actual screenshot/video and logs. Unit tests alone do not pass this gate.

Full canonical A/rewind/B:

1. `/fork rewind`, `/fork power clinic`, then `/fork advance` six times. Expect service 111111, downtime 0, repair 0/3, grid inactive, charge 2.
2. `/fork demolish` removes the 11 accepted facade cells with block particles/sound and pauses advance. `/fork rewind` must report INITIAL verified, round 0 and charge 2. Repeat twice more for actual three-restore evidence.
3. `/fork power workshop`, then advance six times. Expect B1 delivery round 2, service 110111, downtime 1, repair 3/3, grid round 4, charge 0.
4. `/fork compare` requires two complete six-round branches. `/fork inspect` describes the latest committed round. `/fork cancel` remains available while pending.

Live uses a separate fresh run chosen with `/fork start live`, never an automatic fallback. Three role calls use the existing approved Codex ProviderService with new branch/epoch/request role IDs and empty recovery summaries. Runtime profile is gpt-5.6-luna/xhigh/priority; this is separate from development model identity. Each whole attempt is 20 seconds, including role setup and queues. Only `/fork retry` allows the second attempt after timeout/error/cancel. A second failed attempt requires rewind. No physical tools are exposed to these role threads.

Receipts and immutable comparison archives are under the dedicated world's `fork` directory. The epoch counter is outside INITIAL and increases on explicit restart/rewind. Replayed historical receipts reconcile presentation to current state. All 4096 cells including AIR and the six JSON sentinels are verified during restore; actors keep their dedicated identities and return to initial anchors. Ordinary Arena bridge actions and block/item/entity interactions are fenced while FORK is active; players use adventure mode with empty inventories.

Validation: dependency-free Java core/regression tests passed; Node runner tests cover complete batch, whole-attempt timeout/late output, cancellation and isolated role contexts. Prepared-JDK sandbox access prevented Gradle compilation. Main must compile the Minecraft adapter and run recipient fixture, restore, interaction and Live checks. Actual game proof, live provider timing, recipient HUD readability and full restore evidence remain UNRUN. The current increment implements locator/visit/return and camera stops; recipient travel and playback proof remains UNRUN.
