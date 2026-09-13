# Real-input media gate

The prepared Laptop OBS profile is `FORK Readiness 1080p30`: 1920x1080, 30 fps, MKV, x264, 48 kHz stereo, one recorded audio track. Its configured recording folder is `%LOCALAPPDATA%\FORK-Tools\preflight-20260913\obs`. Watch that folder only. Do not read stream/account configuration or change the profile while Lucas operates it.

After Lucas stops the short game-view recording:

```
powershell.exe -NoProfile -File tools/fork-cinematic/ingest-recording.ps1 -RecordingName "<actual-name>.mkv"
node tools/fork-cinematic/real-input-diagnostic.mjs "media/source/<actual-name>.mkv"
```

Ingest checks stability and obtains an exclusive read handle before copying. Originals stay intact. The diagnostic uses two real 7.5-second trims joined in order, a clearly internal title, captured speech/game audio, normalization, H.264/AAC export, 450-frame verification and full decode. It also extracts a three-frame review strip and the recorded audio. It creates no synthetic replacement voice or pictures. A recording shorter than 15 seconds is rejected rather than padded. No full-source final-film render runs here.

Inspect the review strip and the audio measurement. A nonzero audio signal alone does not prove an audible human sentence. Ask Lucas to play the resulting 15-second file once and confirm readable Minecraft picture, his spoken sentence and game audio. Record that answer separately from technical decode. The prepared profile's one mixed track is not an isolated narration stem; final voice must be recorded without game/music playback in a quiet take.

The current fixture is a diagnostic, not an accepted submission mode. Main supplies final mode/world acceptance and Lucas's fixture scope decision if needed. Keep one world session for the complete A/rewind/B take; restarting currently resets it. Cinematic owns the finished edit and mix.

For World's locator, receive the actual SVG and full source credits into the private Cinematic delivery folder, then run:

```
node tools/fork-cinematic/import-locator.mjs .work/fork/cinematic/<locator>.svg .work/fork/cinematic/<credits>.txt <World-SHA256>
```

This uses existing FFmpeg to rasterize the supplied passive SVG, retains the vector/credit hashes and source text, and creates a 1080p still. It does not generate geography. Review attribution and the court marker before acceptance. If FFmpeg cannot decode it, retain the source and report the limit; install no renderer.

Shot 4 accepts an `image` segment with `source`, `sha256`, `in: 0`, `frames: 90`, `audio: false`, and `captionSafeReviewed: true`. All other gameplay slots still require actual recorded clips. The locator's outline is overview geography; the scored court is fictional and no full-island walkability is claimed.
