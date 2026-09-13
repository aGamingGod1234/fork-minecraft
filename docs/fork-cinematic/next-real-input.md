# Cinematic execution when the recording arrives

All commands run in this Laptop worktree. This packet is for the editor, not a request for Lucas to edit.

1. Inspect the two known OBS folders. Ingest only a new, stable, closed recording:

   `powershell -NoProfile -File tools/fork-cinematic/ingest-recording.ps1 -RecordingName ACTUAL_FILENAME.mkv -RecordingRoot Prepared`

   Use `-RecordingRoot Videos` only when the actual file is in Videos. The ingest preserves the original and checks an exclusive read handle and SHA256.

2. Run `node tools/fork-cinematic/film.mjs inspect media/source/ACTUAL_FILENAME.mkv`. Report the actual filename, duration, dimensions, frame rate and audio streams immediately. A30fps encoded stream does not prove30fps game rendering.

3. During the accepted film lease ending14:20, run `node tools/fork-cinematic/real-input-diagnostic.mjs media/source/ACTUAL_FILENAME.mkv 2026-09-13T06:20:00Z`. This uses15seconds of real input, trims/joins, titles, retains captured audio, exports450frames and fully decodes. Do not relabel a missed11:35 gate as passed on time.

4. View the diagnostic's review-strip.png and actual frames. Check desktop/window borders, hand/chat contamination, black frames, occlusion, unreadable comparison and source privacy. Update only the actual review fields in diagnostic-manifest.json. The script cannot establish a human sentence, game frame smoothness or human playback by itself.

5. Once gameViewConfirmed and sourcePrivacyReviewed are true, run `node tools/fork-cinematic/select-review.mjs media/output/ACTUAL_DIAGNOSTIC_FOLDER`. The existing preview shows the selected real MP4. Play it in Chrome through the supported browser UI. Record actual playback evidence; human viewing is a separate confirmation.

6. Fill film-edit-live-neutral.json from the full same-session A/rewind/B source and scenic takes. Record actual source hashes/in/out times, equal rounds, actual mode and accepted world/release hashes. Keep all final gates false until their evidence exists. Choose the neutral voice or the verified exact-outcome alternative, then use the real human take's in/out points. narration-timing-DRAFT.srt is only a worksheet; the renderer generates final captions from actual voice timings.

7. Use the new named locator-film-v3-1080p.png after its visual review. Keep the previous locator-cbd-1080p.png unchanged. Validate with `node tools/fork-cinematic/film.mjs validate media/edit/film-edit-live-neutral.json`, then render with the same path and `render`. The existing pipeline produces2700frames/1080p30/H264AAC, SRT, voice-final.wav, credits, edit manifest, commands and technical verification. Source build work stops13:10. Main explicitly authorized film editing/export/playback through14:20; do not extend that deadline.

8. Measure actual C and E. Main measures U. Request full90-second local human playback, then Main handles hosting, full hosted viewing and submission. No final film or playback claim is supported until those steps pass.
