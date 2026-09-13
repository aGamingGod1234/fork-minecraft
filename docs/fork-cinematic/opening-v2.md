# Opening revision 2: road fork to city to map

The rejected short camera moves are replaced by a **58-metre rise and 19-metre eastward movement**, using the existing camera system. This is an authored camera route, not proof of attractive live framing.

Main installs `media/edit/camera-paths-city-v2.json` before the next client launch, alongside Gameplay's clean-camera update. Do not overwrite the live profile from this Cinematic worktree. The opening expects the literal fork vertex near **[54.5, 0.5, 30]**; World must confirm the actual street fork and its arms after the court/facade update. No invented road fork should be implied by the edit.

One camera command: **`/camera path play fork_intro`**. It starts close to the fork, then rises to **[63.5, 65, 40.5]**, looking inward at the district. It lasts 7 seconds at 20 world ticks/second. `fork_city_crane` traverses the same curve in 12 seconds for a gentler alternate take. `fork_overview` holds a stationary skyline view for 8 seconds. Keep Main's 12-16 render chunks and measure performance; the client camera does not move the human or guarantee chunks load around itself.

The immutable 11:51 preview-world blocks were sampled along the crane curve with a 0.3-metre clearance box: zero occupied-block hits. World supplied the clear eastward corridor; its facade material changes retain occupied cells. This check excludes the new court geometry, scene-edge visibility, shader behaviour and actual cinematic framing. A live check remains necessary. The route avoids the surveyed 99m/173m/281m towers instead of attempting to pass through them.

Film 2 remains 90 seconds:

- **0-2:** actual literal road fork, first two seconds of the accepted opening take.
- **2-4:** FORK title card, covering the middle of the same camera take.
- **4-7:** last three seconds of the seven-second opening, showing the substantial crane into the city. Camera movement is recorded at its actual speed.
- **7-10:** explicit cut to the attributed Singapore locator with the real district-source-centre marker. Label it as map orientation. Only the 256m district is generated; the whole island is not built.
- **10-60:** roles and actual A/rewind/B evidence, following the accepted shot structure.
- **60-70:** real equal-round comparison, final six picture seconds still and final two seconds narration-free.
- **70-90:** inspector, accepted features, truthful new-Astra/reused-Arena credits and GitHub end card.

World metadata supplies district bounds 1.281850162-1.284149838 latitude and 103.848849873-103.851150127 longitude. The locator marker is their centre, **1.283, 103.85**. It is a district marker, not a surveyed court coordinate. The Natural Earth outline and attribution are retained, and the obsolete fictional-court panel is removed. Final release/world acceptance is still pending.

Minimal human capture: select the prepared OBS profile and actual game-only source, start recording, trigger the supplied preset, let it return, speak one sentence, and stop after at least 20 seconds. Do not capture a desktop display or stretch 16:10 video to 16:9. Cinematic handles any aspect-preserving crop/padding, trim, captions and mix. Record the full A/rewind/B proof before scenic retakes. Human narration is the prepared 120-word script, with exact-outcome alternatives selected before recording.

