# RC9 narration capture

The current camera data is [camera-paths-narration-rc9.json](../../media/edit/camera-paths-narration-rc9.json), exactly matching the camera file in the [RC9 release](https://github.com/aGamingGod1234/fork-minecraft/releases/tag/v2026.09.13-rc9). Its SHA256 is `139e7d0959e9b38300d0cd59de8f6bdd19a7e20f4cb5246232ed9e50b690c667`. Older camera files in this directory preserve earlier revisions.

Install through the release instructions with Minecraft closed. Start OBS recording, return to Minecraft, then enter `/camera film take`. The command warms the wide view and opening before its countdown. It produces 90 seconds of content plus a three-second clean tail. `/camera film stop` cancels. Keep the game visible and unpaused during the take.

| Content time | Opening movement |
| --- | --- |
| 0.00–2.30 | Completely still street view |
| 2.30–3.60 | Fast pullback and rise to the wide CBD view |
| 3.60–4.55 | Hold the wide view; Lucas approved 3.60 for “Singapore” |
| 4.55–5.10 | Tilt upward toward the sky |
| 5.10–5.40 | Hold before the streets cue |
| 5.40 | Cut to the next scene |

For audio alignment, place the first visible pullback at narration **2.30 seconds** and retain 2.30 seconds of still road before it. The first movement is not content time zero. The remaining phrase cues retain the supplied timestamps. Intentional opening holds are part of the requested camera direction.

Capture temporarily uses the maximum supported 32-chunk render distance and restores prior preferences afterward. It preserves the window size and fullscreen mode. The wide view shows the loaded CBD; neither a 32-chunk setting nor the camera geometry proves visibility of every map edge or the whole country.

The exact JSON passed the Java sampler checks for 30 cuts and 93 seconds, including 5,341 moving and 210 intentional still frame intervals within shots. Geometry and subject framing were checked at the recorded 2880×1801 aspect ratio. Actual RC9 loaded scenery and GPU/OBS frame pacing still require a recording check. The prior RC8 clip had 16.2% OBS rendering lag, so a 60 FPS cap should not be described as verified smooth output.

The A/B scenes present twelve genuine previously committed LIVE rounds from the bundled historical journal. Recording does not invoke the provider again. Lucas handles narration, editing and subtitles; this package supplies the raw in-game camera sequence.
