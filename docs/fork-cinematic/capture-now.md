# Capture packet: new Market Street world

Cinematic edits everything. Lucas records and enters the commands below. Keep the same world session throughout A, rewind and B. Do not restart or change mode between branches.

1. In OBS choose **Profile > FORK Readiness1080p30**. Use the game-only picture, with no taskbar or desktop. Start Recording. In Minecraft run `/fork start live` only for the new initial run, then `/camera path play fork_intro`. Say “This is the FORK capture check.” Stop Recording after at least 20 seconds. The camera flies automatically and returns by itself.
2. Start a new OBS recording for the complete comparison. If the test has not advanced any rounds, continue from that same round-zero session. Run `/fork return` and wait for safe court arrival before the first power command; the new world's initial spawn is just outside the court guard. Show the initial mode and values for 5 seconds. Run `/fork power clinic`. Run `/fork advance` once, wait for its committed round, then repeat until **round 6**. Keep the waiting and any failure in the source. Do not issue another advance while a round is pending.
3. Show A's round-six result for 5 seconds. Run `/camera path stop-playback`, then `/fork rewind`. Show the restored round-zero state and two charges for 5 seconds. Run `/fork power workshop`. Advance one committed round at a time until **round 6**, preserving all waits and actual outcomes.
4. Show B's result for 5 seconds. Run `/fork compare` and keep the comparison completely still for **12 seconds**. Do not run a camera preset here: clean camera hides the HUD needed as evidence. Run `/fork inspect` and hold the role evidence readable for 5 seconds. Stop OBS Recording.
5. Record the scenic inserts in a new take: `/camera path play fork_clinic`, then `fork_workshop`, then `fork_courier`, then `fork_overview`. Wait for each preset to finish before entering the next command. Stop Recording. No manual flying or world edits.

If a live attempt fails, preserve the recording and stop advancing. Tell Main the displayed error. A fixture substitution needs explicit acceptance; the film must identify the actual mode.

The two configured folders are the prepared FORK OBS folder and Videos. Leave originals in place. Cinematic ingests only stable, closed files and reports their names and hashes. No editing, renaming or upload is required from Lucas.

For voice, use the same microphone in a separate OBS recording with game audio muted. Read the neutral script in narration-live-neutral.md, one numbered line at a time, with a quiet second before and after each line. Cinematic selects, times, captions and mixes it. The exact-result alternative is used only after the actual outcome is reviewed. Do not switch microphone or install a voice app.
