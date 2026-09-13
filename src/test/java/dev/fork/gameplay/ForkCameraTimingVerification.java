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
        var parked=new PresentationClock(Long.MAX_VALUE,false);
        equal(parked.advance(1_000_000_000L,false),0);
        equal(parked.elapsedTicks(),0);
        parked.armAt(4_000_000_000L);
        equal(parked.advance(3_999_000_000L,false),0);
        equal(parked.advance(4_000_000_000L,false),0);
        equal(parked.advance(4_016_666_667L,false),0.33333334);
        equal(parked.elapsedTicks(),0.33333334); // observation does not move time
        boolean rearmed=false;
        try { parked.armAt(5_000_000_000L); } catch(IllegalStateException expected){rearmed=true;}
        if(!rearmed)throw new AssertionError("A running clock cannot be rearmed");
        var cues=java.util.stream.IntStream.range(0,30).mapToObj(i->new ForkCaptureTimeline.Cue(ForkCaptureTimeline.PHRASE_TICKS.get(i)/20.0,"s"+i)).toList();
        var timeline=new ForkCaptureTimeline(90,3,cues);
        var spoken=java.util.stream.IntStream.range(0,30).mapToObj(i->new CameraPath("s"+i,List.of(new CameraKeyframe(0,i*100,1,0,0,0),new CameraKeyframe(100,i*100+10,1,0,0,0)))).toList();
        var timed=new dev.agaminggod.arenaagents.client.camera.CameraDirectorClient.CameraReel(spoken,timeline.durations());
        equal(timed.durationTicks(),1860);
        for(int i=1;i<30;i++){equal(timed.sample(timeline.starts().get(i)).x(),i*100);if(timed.sample(timeline.starts().get(i)-.001).x()>=i*100)throw new AssertionError("Narration crosscut");}
        if(timed.sample(1859.9).x()<=timed.sample(1859.5).x())throw new AssertionError("Closing leadout must keep moving");
        System.out.println("PASS parked first frame, future arm, render-only observation, all30 exact cuts and moving93s leadout");
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
        var durations = new int[]{100,140,100,100,100,160};
        var paths = new java.util.ArrayList<CameraPath>();
        for(int i=0;i<6;i++) paths.add(new CameraPath("s"+i,List.of(new CameraKeyframe(0,i*1000,1,0,0,0),new CameraKeyframe(durations[i],i*1000+10,1,0,0,0))));
        var reel = new dev.agaminggod.arenaagents.client.camera.CameraDirectorClient.CameraReel(paths);
        paths.clear();
        equal(reel.durationTicks(),700);
        if(reel.sample(99.9).x()>=11 || reel.sample(239.9).x()>=1011) throw new AssertionError("Cross-cut interpolation");
        equal(reel.sample(100).x(),1000); equal(reel.sample(240).x(),2000);
        if(reel.complete(699.9)||!reel.complete(700)) throw new AssertionError("Exact reel end");
        var rc=new PresentationClock(0,false);
        equal(reel.sample(rc.advance(27_000_000_000L,false)).x(),5000);
        equal(rc.advance(28_000_000_000L,true),540);
        equal(rc.advance(40_000_000_000L,false),540);
        if(!reel.complete(rc.advance(48_000_000_000L,false))) throw new AssertionError("Resume same clock");
        equal(reel.sample(new PresentationClock(0,false).advance(0,false)).x(),0);
        boolean rejected=false;
        try { new dev.agaminggod.arenaagents.client.camera.CameraDirectorClient.CameraReel(List.of(new CameraPath("zero",List.of(new CameraKeyframe(0,0,0,0,0,0))))); } catch(IllegalArgumentException expected){rejected=true;}
        if(!rejected) throw new AssertionError("Reject zero duration");
        System.out.println("PASS immutable six-shot reel, cuts99.9/100 and239.9/240,700tick end,multisegment stall,pause/resume,fresh restart,zero duration rejection");
        System.out.println("PASS fractional frames, stalled frame duration, pause/resume, fresh replay, yaw boundary, authored duration and loop sampling");
    }
}
