# Film production

All commands run on Laptop from the Cinematic worktree. Existing Node and FFmpeg 9 are used. No dependencies are installed and no Minecraft software is built here.

Focused metadata checks: `node --test --test-isolation=none tools/fork-cinematic/film.test.mjs`. File-backed subprocess I/O avoids the worker's rejected Node pipe handles; commands still invoke the same headless FFmpeg directly. The default media lease ends at 13:10 SGT. An expired lease stops rendering until Main supplies a valid continuing assignment; it never extends the event deadlines.

`node tools/fork-cinematic/film.mjs inspect media/source/<recording>.mkv` returns duration, streams and SHA256. Keep private recordings in ignored `media/source/`; large output stays in ignored `media/output/`. Never stage raw footage.

Edit `media/edit/film-edit.json` with accepted release/world/mode/camera hashes, exact source times, matching narration, actual credits and evidence. The tracked file is an empty template until those inputs exist. Private completed manifests should live in `.work/fork/cinematic/` until Main accepts the release.

Each shot contains segments that add up to its exact frame count. A `clip` segment has `source`, `sha256`, `in`, `out`, `frames`, `audio` (true only for captured game sound), `audioGainDb` (normally -20), and `captionSafeReviewed: true`. Use `freeze` for the last six seconds of the real comparison, with the real `source`, `sha256`, `in`, `frames`, and caption review. A `card` has `textFile`, `frames`, and `reviewed: true`; cards are permitted only in shots 2, 22 and 23. The locator is actual accepted map media, not a generated card. All segments need meaningful evidence in the parent shot. Do not put a filename into the manifest until the source file exists and its hash is known.

Clip timings are seconds in the original recording. Clips play forward at normal speed. Condense round waits using several short segments, maintaining chronological committed rounds and a visible `Waits trimmed` cue in the source or a reviewed text overlay. Optional `overlayTextFile` adds a top cue without changing game values. Preserve mode labels. Clip `out - in` must match `frames / 30` within one frame; do not invent motion or results. A freeze is a held real frame, and its in point must fall within the source duration.

Voice entries select actual human recordings with source/hash/in/out and target `at`. Correct text to the final take and confirm the transcript. Every take must fit its assigned window; no automatic speech speedup. Captions derive from these actual take times. Keep takes short enough for readable captions. Put the exact final transcript in the editable manifest.

Commands:

```
node tools/fork-cinematic/film.mjs validate .work/fork/cinematic/accepted-edit.json
node tools/fork-cinematic/film.mjs render .work/fork/cinematic/accepted-edit.json
node tools/fork-cinematic/film.mjs verify media/output/<run-id>
```

Rendering validates provenance before running FFmpeg, uses serial segment encodes and four x264 threads, preserves the image aspect ratio, concatenates exact frame counts, captions actual narration, creates human `voice-final.wav`, mixes captured ambience and voice, then measures and normalizes the full mix. It writes exact argument arrays and filter graphs for every command. FFmpeg 9 receives graph text through `-filter_complex`, never the removed `-filter_complex_script`. Rendering creates a new output folder and never overwrites an earlier release. It does not publish.

The final verification decodes the whole MP4, counts 2700 frames, checks H.264/AAC/1920x1080/30 fps/48 kHz/90 seconds, and measures integrated loudness and true peak. Headless decode does not pass human viewing. Lucas must watch all 90 seconds locally with sound; Main records hosted full viewing and judge access separately.

Use captured game ambience as the selected music fallback. No unlicensed instrumental, synthetic voice, or generated visual footage. The local system font is used for rendered typography; no font file is redistributed. Credits must list the accepted map source/license, actual new Astra work and evidence, reused Agent Arena, game notice, human narrator and captured audio. Main supplies approved coverage and release credits.
