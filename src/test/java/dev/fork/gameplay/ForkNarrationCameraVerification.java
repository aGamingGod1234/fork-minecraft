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
            var path=new CameraPath(value.get("name").getAsString(),frames,value.has("interpolation")?value.get("interpolation").getAsString():"catmull_rom");
            if(library.put(path.name(),path)!=null)throw new AssertionError("Duplicate path name");
        }
        var paths=timeline.paths().stream().map(library::get).toList();
        if(paths.contains(null))throw new AssertionError("Missing narration path");
        var reel=new CameraDirectorClient.CameraReel(paths,timeline.durations());
        for(int i=0;i<paths.size();i++){
            var at=reel.sample(timeline.starts().get(i));var first=paths.get(i).sample(0);
            if(distance(at,first)>1e-9)throw new AssertionError("Wrong cut pose "+i);
        }
        var holds=new ArrayList<double[]>();
        if(root.has("forkCaptureIntentionalHolds")){
            for(var item:root.getAsJsonArray("forkCaptureIntentionalHolds")){
                var h=item.getAsJsonObject();double start=h.get("startSeconds").getAsDouble(),end=h.get("endSeconds").getAsDouble();
                boolean approved=start==0&&end==2.30 || start>=3.60&&end<=4.55 || start>=5.10&&end<=5.40;
                if(!approved||!(end>start))throw new AssertionError("Unapproved stationary interval");
                holds.add(new double[]{start*20,end*20});
            }
            if(holds.stream().noneMatch(h->h[0]==0&&h[1]==46))throw new AssertionError("Road must stay still through Welcome");
            for(double[] hold:holds){
                var first=reel.sample(hold[0]);
                for(double t=hold[0];t<hold[1]-1e-7;t+=1.0/3)
                    if(poseDistance(first,reel.sample(t))>1e-7)throw new AssertionError("Authored hold moves at "+t/20+"s");
            }
            if(distance(reel.sample(46),reel.sample(72))<100)throw new AssertionError("Opening must actually pull back to a wide view");
            if(distance(reel.sample(91),reel.sample(102))>1e-7||reel.sample(91).pitch()-reel.sample(102).pitch()<10)
                throw new AssertionError("Opening must tilt upward from the stationary wide pose");
        }
        int moving=0,held=0;
        for(int frame=0;frame<5580;frame++){
            double tick=frame/3.0,next=(frame+1)/3.0;
            if(timeline.shotIndex(tick)!=timeline.shotIndex(next))continue;
            boolean intentional=holds.stream().anyMatch(h->tick>=h[0]-1e-7&&next<=h[1]+1e-7);
            double motion=poseDistance(reel.sample(tick),reel.sample(next));
            if(intentional){if(motion>1e-7)throw new AssertionError("Intentional hold moved at frame "+frame);held++;}
            else {if(motion<1e-8)throw new AssertionError("Unintended stationary frame "+frame);moving++;}
        }
        if(reel.durationTicks()!=1860)throw new AssertionError("Wrong duration");
        System.out.println("PASS actual JSON:30 exact cuts,93 seconds, "+moving+" moving and "+held+" intentional still intervals at60FPS");
    }
    private static double poseDistance(CameraPose a,CameraPose b){return distance(a,b)+Math.abs(Math.IEEEremainder(a.yaw()-b.yaw(),360))+Math.abs(a.pitch()-b.pitch());}
    private static double distance(CameraPose a,CameraPose b){return Math.abs(a.x()-b.x())+Math.abs(a.y()-b.y())+Math.abs(a.z()-b.z());}
}
