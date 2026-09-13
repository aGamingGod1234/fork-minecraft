package dev.fork.gameplay;

import java.util.*;

/** Immutable absolute narration boundaries, expressed in exact 20 Hz ticks. */
public final class ForkCaptureTimeline {
    public record Cue(double startSeconds,String path) {}
    public static final List<Integer> PHRASE_TICKS=List.of(0,7,46,108,155,237,273,295,367,397,427,514,565,657,705,756,837,937,1037,1105,1157,1241,1303,1377,1429,1476,1537,1576,1636,1660);
    private final List<Cue> cues;
    private final List<Integer> starts,durations;
    private final List<String> paths;
    private final int durationTicks,leadOutTicks;
    public ForkCaptureTimeline(double durationSeconds,double leadOutSeconds,List<Cue> input){
        durationTicks=exactTicks(durationSeconds);leadOutTicks=exactTicks(leadOutSeconds);
        if(durationTicks!=1800||leadOutTicks!=60)throw new IllegalArgumentException("Narration capture requires 90 seconds plus 3 seconds of clean lead-out.");
        cues=List.copyOf(input);
        starts=cues.stream().map(c->exactTicks(c.startSeconds())).toList();
        if(!starts.equals(PHRASE_TICKS))throw new IllegalArgumentException("Camera timeline does not match the 28 exact narration phrase starts.");
        paths=cues.stream().map(Cue::path).toList();
        if(paths.stream().anyMatch(p->p==null||p.isBlank())||new HashSet<>(paths).size()!=paths.size())throw new IllegalArgumentException("Every narration shot needs its own camera path.");
        var slots=new ArrayList<Integer>();
        for(int i=0;i<starts.size();i++)slots.add((i+1<starts.size()?starts.get(i+1):playbackTicks())-starts.get(i));
        durations=List.copyOf(slots);
    }
    private static int exactTicks(double seconds){
        double ticks=seconds*20;
        if(!Double.isFinite(ticks)||ticks<0||ticks>Integer.MAX_VALUE||Math.abs(ticks-Math.rint(ticks))>1e-7)throw new IllegalArgumentException("Camera boundaries must land on exact 20 Hz ticks.");
        return (int)Math.rint(ticks);
    }
    public List<Cue> cues(){return cues;}
    public List<Integer> starts(){return starts;}
    public List<Integer> durations(){return durations;}
    public List<String> paths(){return paths;}
    public int durationTicks(){return durationTicks;}
    public int leadOutTicks(){return leadOutTicks;}
    public int playbackTicks(){return durationTicks+leadOutTicks;}
    public int shotIndex(double ticks){
        if(!Double.isFinite(ticks))throw new IllegalArgumentException("Invalid presentation time");
        for(int i=1;i<starts.size();i++)if(ticks<starts.get(i))return i-1;
        return starts.size()-1;
    }
}