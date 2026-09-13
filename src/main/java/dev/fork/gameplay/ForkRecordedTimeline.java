package dev.fork.gameplay;

import java.util.*;

/** Fixed narration cues; source states always come from validated persisted receipts. */
public final class ForkRecordedTimeline {
    public static final long DURATION_MS=90_000, RESTORE_AFTER_MS=93_000, MAX_LAG_MS=250;
    public record Cue(String name,long offsetMs,ForkEngine.State state) {}
    public static List<Cue> cues(ForkRecordedBundle b) {
        var cues=new ArrayList<Cue>(); var times=cueOffsets();
        cues.add(new Cue("initial",times.get(0),b.initial()));
        cues.add(new Cue("clinic-preview",times.get(1),b.clinic().getLast().state()));
        cues.add(new Cue("workshop-preview",times.get(2),b.workshop().getLast().state()));
        cues.add(new Cue("clinic-choice",times.get(3),b.clinicChoice()));
        for(int i=0;i<6;i++) cues.add(new Cue("clinic-round-"+(i+1),times.get(4+i),b.clinic().get(i).state()));
        cues.add(new Cue("rewind-initial",times.get(10),b.initial()));
        cues.add(new Cue("workshop-choice",times.get(11),b.workshopChoice()));
        for(int i=0;i<6;i++) cues.add(new Cue("workshop-round-"+(i+1),times.get(12+i),b.workshop().get(i).state()));
        cues.add(new Cue("compare-clinic",times.get(18),b.clinic().getLast().state()));
        cues.add(new Cue("compare-workshop",times.get(19),b.workshop().getLast().state()));
        cues.add(new Cue("experiment-choice",times.get(20),b.workshopChoice()));
        return List.copyOf(cues);
    }
    public static List<Long> cueOffsets() {
        var times=new ArrayList<Long>(List.of(0L,32_850L,35_250L,37_800L));
        for(int i=0;i<6;i++) times.add(41_850L+i*700);
        times.add(51_850L);times.add(55_250L);
        for(int i=0;i<6;i++) times.add(57_850L+i*550);
        times.add(62_050L);times.add(65_150L);times.add(71_450L);
        return List.copyOf(times);
    }
    public static int indexAt(List<Cue> cues,long elapsedMs) {
        int found=0;
        for(int i=1;i<cues.size()&&cues.get(i).offsetMs()<=elapsedMs;i++) found=i;
        return found;
    }
    private ForkRecordedTimeline() {}
}
