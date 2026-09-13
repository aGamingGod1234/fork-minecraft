# One-command camera capture

Main installs `media/edit/camera-paths-ground0.json` in the dedicated FORK profile before launch. The client loads paths at startup. This file targets court origin **[46, 0, 22]** and human feet **[54.5, 1, 29.5]** in the overworld. It must be rebound if Main accepts a different world transform. Camera playback moves only a local marker; it does not move the human or load distant scenery automatically.

First actual test after Main installs the world/profile: start OBS recording, then run **`/camera path play fork_overview`** once. Let the eight-second move finish, say one short sentence, and stop OBS after at least 20 seconds total. No mouse camera movement is required. Cinematic checks the resulting file. If the generator blocks the view, the picture clips a wall, or chunks are absent, report that frame; do not hand-fly or change the world. Framing is currently unrun, not a safety pass.

The emergency return is **`/camera path stop-playback`**. Stop playback before rewind, travel or any world replacement. Normal playback returns to the player's camera at its end. Seconds below assume 20 world ticks per second; actual wall time will be measured from the recording.

Capture in this order, keeping original recordings intact:

| Take | Preset / action | Duration and edit use |
|---|---|---|
| 1: full proof | Main's accepted A / rewind / B sequence in one uninterrupted world session; use `fork_comparison` for stationary reads | Preserve all committed rounds and waits. Hold A and B results at least 5 seconds each. Hold actual equal-round compare at least 12 seconds. No restart between A and B. This is the first full capture priority. |
| 2: intro | `/camera path play fork_intro` | 6 seconds; use source time 1–3 seconds provisionally for film 0–2. Elevated view of the court's power routes, not a country overview. |
| 3: overview | `/camera path play fork_overview` | 8 seconds; use source time 1–4 seconds provisionally for film 4–7. Only court/CBD coverage actually visible may be described. |
| 4: medic | `/camera path play fork_clinic` | 5 seconds; use source time 1–4 seconds provisionally for film 10–13. Camera-only approach does not prove a human walked through the clinic. |
| 5: engineer | `/camera path play fork_workshop` | 5 seconds; use source time 1–4 seconds provisionally for film 13–16. |
| 6: courier | `/camera path play fork_courier` | 5 seconds; use source time 1–4 seconds provisionally for film 16–19. |
| 7: comparison | `/camera path play fork_comparison`, then Main's verified compare display | 20-second stationary path. Capture at least 12 seconds of the actual comparison after the command UI clears; trim to film 60–70. Final six picture seconds stay still; narration ends by film 68. |

These are proposed source offsets relative to the first rendered preset frame, not fabricated ingest points. Cinematic measures exact recording timestamps and hashes after capture, selects the clean frames, captions the real voice and performs the complete edit. Lucas only starts/stops OBS, triggers the supplied preset when needed, and records narration once outcomes are fixed. Gameplay/Main own action sequencing and accepted runtime mode.

No distant CBD scenic camera is included until World verifies its loaded coordinates and line of sight. No manual camera setup, new camera stack, server movement, software build or world edit is needed from Lucas.
