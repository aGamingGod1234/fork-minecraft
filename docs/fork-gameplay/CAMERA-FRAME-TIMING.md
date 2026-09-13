# Frame-based camera timing

CameraDirector previously advanced one unit per END_CLIENT_TICK and sampled integer path ticks. Local Minecraft 26.1.2 mapped bytecode confirms Minecraft.runTick caps its catch-up loop with Math.min(10, dueTicks). A stall longer than 500 ms can therefore discard elapsed presentation time and stretch the authored camera duration. This is a concrete timing defect; actual Laptop clip FPS and the cause of every reported jitter remain unverified here.

The same mapped artifacts confirm GameRenderer.update(DeltaTracker, boolean) calls Camera.update(DeltaTracker) before extraction. Camera.update calls alignWithEntity before computing culling and projection matrices. The new required mixin injects at Camera.update HEAD, samples the existing path with fractional monotonic elapsed time, and gives the local marker identical old/current position and rotation. Vanilla then aligns and extracts the same exact frame pose. No server player, provider, branch or world state is changed. Preset files, geometry, yaw interpolation and authored duration are unchanged.

Vanilla already interpolates tick positions and uses Mth.rotLerp for entity yaw. We did not establish that ordinary marker ticking corrupts interpolation, and do not claim the previous camera necessarily stepped visibly at 20 Hz. Eliminating the second interpolation permits exact fractional path evaluation and removes dependence on client catch-up ticks; it cannot increase rendering FPS.

Playback time excludes paused frames, including the transition interval on resuming. That conservative boundary avoids counting a pause with no intermediate frames. Disconnect/world/player replacement retains the existing cleanup, camera restoration and saved HUD/chat preferences. Both GUI and command entry paths get a fresh clock; looping uses the authored duration. The existing tick event retains lifecycle/clean-presentation checks but no longer drives motion.

Verification: dependency-free javac compilation PASS for the exact nested PresentationClock extracted into a temporary compilation harness, CameraPath/Keyframe/Pose, and ForkCameraTimingVerification. Runtime deliberately not retried after the established prepared-JDK access failure. Full client/mixin compilation and actual film verification are pending Main. Test covers sub-tick sampling, a delayed frame maintaining elapsed duration, pause/resume, a fresh playback clock, initially paused playback, angular continuity across numeric yaw wrapping, endpoint and loop sampling. The yaw check preserves existing geometry; it is not evidence of a prior vanilla yaw defect.

Main commands from the canonical checkout after integrating:

```powershell
.\gradlew.bat compileJava compileClientJava jar
& 'C:/Users/User/AppData/Local/FORK-Tools/java/jdk-25.0.4.1+1/bin/javac.exe' -cp build/classes/java/client -d build/fork-camera-verification src/test/java/dev/fork/gameplay/ForkCameraTimingVerification.java
& 'C:/Users/User/AppData/Local/FORK-Tools/java/jdk-25.0.4.1+1/bin/java.exe' -cp 'build/classes/java/client;build/fork-camera-verification' dev.fork.gameplay.ForkCameraTimingVerification
```

Main should verify one preset's elapsed unpaused duration, pause/resume without a jump, manual/natural stop, disconnect/world change, and hidden HUD/hand with saved settings restored. Main/Cinematic own the actual Laptop clip, FPS assessment and preset speed/geometry. No native UI or Laptop inspection was performed by Gameplay.
