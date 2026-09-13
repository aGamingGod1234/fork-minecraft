package dev.fork.gameplay;

import com.google.gson.*;
import dev.agaminggod.arenaagents.client.camera.*;
import java.nio.file.*;
import java.util.*;

/** Runs the actual Java sampler against the deliverable JSON, without opening a game. */
public final class ForkNarrationCameraVerification {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Camera JSON path required");
        JsonObject root;
        try(var reader=Files.newBufferedReader(Path.of(args[0]))){root=JsonParser.parseReader(reader).getAsJsonObject();}
        var cues=new ArrayList<ForkCaptureTimeline.Cue>();
        for(var item:root.getAsJsonArray("forkCaptureTimeline")){
            var cue=item.getAsJsonObject();cues.add(new ForkCaptureTimeline.Cue(cue.get("startSeconds").getAsDouble(),cue.get("path").getAsString()));
        }
        var timeline=new ForkCaptureTimeline(root.get("forkCaptureDurationSeconds").getAsDouble(),root.get("forkCaptureLeadOutSeconds").getAsDouble(),cues);
        var library=new HashMap<String,CameraPath>();
        for(var item:root.getAsJsonArray("paths")){
            var value=item.getAsJsonObject();var frames=new ArrayList<CameraKeyframe>();
            for(var frame:value.getAsJsonArray("keyframes")){
                var f=frame.getAsJsonObject();frames.add(new CameraKeyframe(f.get("tick").getAsInt(),f.get("x").getAsDouble(),f.get("y").getAsDouble(),f.get("z").getAsDouble(),f.get("yaw").getAsFloat(),f.get("pitch").getAsFloat()));
            }
            var path=new CameraPath(value.get("name").getAsString(),frames);
            if(library.put(path.name(),path)!=null)throw new AssertionError("Duplicate path name");
        }
        var paths=timeline.paths().stream().map(library::get).toList();
        if(paths.contains(null))throw new AssertionError("Missing narration path");
        var reel=new CameraDirectorClient.CameraReel(paths,timeline.durations());
        for(int i=0;i<paths.size();i++){
            var at=reel.sample(timeline.starts().get(i));var first=paths.get(i).sample(0);
            if(distance(at,first)>1e-9)throw new AssertionError("Wrong cut pose "+i);
        }
        int moving=0;
        for(int frame=0;frame<5580;frame++){
            double tick=frame/3.0,next=(frame+1)/3.0;
            if(timeline.shotIndex(tick)!=timeline.shotIndex(next))continue;
            if(distance(reel.sample(tick),reel.sample(next))<1e-8)throw new AssertionError("Stationary frame "+frame);
            moving++;
        }
        if(reel.durationTicks()!=1860)throw new AssertionError("Wrong duration");
        System.out.println("PASS actual JSON:30 exact cuts,93 seconds, "+moving+" moving same-shot frame intervals at60FPS");
    }
    private static double distance(CameraPose a,CameraPose b){return Math.abs(a.x()-b.x())+Math.abs(a.y()-b.y())+Math.abs(a.z()-b.z());}
}
