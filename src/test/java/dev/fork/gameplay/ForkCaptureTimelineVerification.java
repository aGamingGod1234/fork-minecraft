package dev.fork.gameplay;
import java.util.*;
public final class ForkCaptureTimelineVerification {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static List<ForkCaptureTimeline.Cue> cues(){return java.util.stream.IntStream.range(0,30).mapToObj(i->new ForkCaptureTimeline.Cue(ForkCaptureTimeline.PHRASE_TICKS.get(i)/20.0,"shot_"+i)).toList();}
 static void rejected(Runnable r){try{r.run();throw new AssertionError("Invalid timeline accepted");}catch(IllegalArgumentException expected){}}
 public static void main(String[] args){
  var timeline=new ForkCaptureTimeline(90,3,cues());
  check(timeline.durationTicks()==1800&&timeline.playbackTicks()==1860,"90+3");
  check(timeline.durations().stream().mapToInt(Integer::intValue).sum()==1860,"Exact sum");
  for(int i=1;i<30;i++){
   int tick=timeline.starts().get(i);
   check(timeline.shotIndex(tick-1e-6)==i-1,"Prior shot before cut "+i);
   check(timeline.shotIndex(tick)==i,"Next shot at cut "+i);
   check(tick*3==Math.round(cues().get(i).startSeconds()*60),"Exact60fps frame "+i);
  }
  check(timeline.shotIndex(1859.9)==29,"Closing includes leadout");
  var wrong=new ArrayList<>(cues());wrong.set(1,new ForkCaptureTimeline.Cue(.36,"bad"));
  rejected(()->new ForkCaptureTimeline(90,3,wrong));
  var duplicate=new ArrayList<>(cues());duplicate.set(1,new ForkCaptureTimeline.Cue(.35,"shot_0"));
  rejected(()->new ForkCaptureTimeline(90,3,duplicate));
  rejected(()->new ForkCaptureTimeline(90,0,cues()));
  System.out.println("PASS exact30 cuts,60fps boundaries, unique paths,90+3duration and invalid metadata rejection");
 }
}
