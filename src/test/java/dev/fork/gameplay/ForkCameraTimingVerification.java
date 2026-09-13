package dev.fork.gameplay;

import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient.PresentationClock;
import dev.agaminggod.arenaagents.client.camera.CameraKeyframe;
import dev.agaminggod.arenaagents.client.camera.CameraPath;
import java.util.List;

/** Dependency-free timing/path assertions; no game, provider, or world mutation. */
public final class ForkCameraTimingVerification {
    private static void equal(double actual, double expected) {
        if (Math.abs(actual - expected) > 0.000001) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        PresentationClock clock = new PresentationClock(0, false);
        equal(clock.advance(16_666_667, false), 0.33333334);
        equal(clock.advance(33_333_334, false), 0.66666668);
        equal(clock.advance(1_000_000_000, false), 20); // delayed tick/frame must not stretch duration
        equal(clock.advance(1_010_000_000, true), 20);
        equal(clock.advance(9_000_000_000L, true), 20);
        equal(clock.advance(9_010_000_000L, false), 20); // paused interval excluded
        equal(clock.advance(9_035_000_000L, false), 20.5);
        PresentationClock fresh = new PresentationClock(9_035_000_000L, false);
        equal(fresh.advance(9_085_000_000L, false), 1); // replay resets elapsed
        PresentationClock initiallyPaused = new PresentationClock(0, true);
        equal(initiallyPaused.advance(1_000_000_000, false), 0);
        equal(initiallyPaused.advance(1_050_000_000, false), 1);
        CameraPath path = new CameraPath("yaw-boundary", List.of(
            new CameraKeyframe(0, 0, 1, 0, -179, 0),
            new CameraKeyframe(20, 10, 1, 0, 179, 0),
            new CameraKeyframe(40, 20, 1, 0, 177, 0)));
        double before = path.sample(19.99).yaw(), after = path.sample(20.01).yaw();
        if (Math.abs(after - before) < 350) throw new AssertionError("Expected numeric wrap at authored keyframe");
        double angularDistance = Math.abs(Math.IEEEremainder(after - before, 360));
        if (angularDistance > 0.01) throw new AssertionError("Geometric yaw must stay continuous");
        if (path.sample(0.5).x() == path.sample(0).x()) throw new AssertionError("Fractional frame must move");
        equal(path.durationTicks(), 40);
        equal(path.sample(40).x(), 20);
        equal(path.sample(40.5 % path.durationTicks()).x(), path.sample(0.5).x());
        System.out.println("PASS fractional frames, stalled frame duration, pause/resume, fresh replay, yaw boundary, authored duration and loop sampling");
    }
}
