package dev.fork.gameplay;

import java.util.function.Consumer;

/** Pure bootstrap and acknowledged projection gate; render time never waits for cue packets. */
public final class ForkCaptureGate {
    public record Action(String kind,String value) {}
    public record Projection(String bundleId,boolean ready,boolean active,String owner,long startEpochMs,long durationMs,long restoreAfterMs,String event,long eventOffsetMs,String sourceDigest,String error) {}
    private String phase="idle",branch="",owner="",bundle="",digest="";
    private long epoch,revision,version,deadline,lastVersion,lastSeen,startEpochMs;
    private ForkEngine.Power allocation;
    public String phase(){return phase;}
    public boolean active(){return !phase.equals("idle")&&!phase.equals("failed")&&!phase.equals("done")&&!phase.equals("cancelled");}
    public boolean owns(ForkView v){return v!=null&&v.canControl()&&v.state().branch().equals(branch)&&v.state().epoch()==epoch;}
    public boolean ownsProjection(Projection p){return p!=null&&p.owner().equals(owner)&&p.bundleId().equals(bundle)&&!bundle.isEmpty();}
    public long startEpochMs(){return startEpochMs;}
    private Action fail(String cause){phase="failed";return new Action("fail",cause);}
    private Action command(String next,String command,long serial,long now){phase=next;version=serial;deadline=now+25000;return new Action("command",command);}
    private void claim(ForkView v){branch=v.state().branch();epoch=v.state().epoch();revision=v.state().revision();allocation=v.state().allocation();}
    private String invalid(ForkView v){
        if(v.state().mode()!=ForkEngine.Mode.LIVE)return "A LIVE session is required; no Fixture substitution is allowed.";
        if(!v.canControl())return "This player cannot control the FORK session.";
        if(v.pending()||v.paused())return "Finish or resolve the current LIVE round before starting a take. "+v.issue();
        return null;
    }
    public Action start(ForkView v,long serial,long now,String playerId){
        owner=playerId;lastVersion=serial;lastSeen=now;
        if(v==null)return command("session","fork new live",serial,now);
        String problem=invalid(v);if(problem!=null)return fail(problem);
        claim(v);return command("return","fork return",serial,now);
    }
    public Action update(ForkView v,Projection p,long serial,long now,long epochNow,boolean bodiesVisible,boolean firstFrameReady,boolean paused){
        if(!active())return null;
        if(paused){
            if(phase.equals("arm")||phase.equals("running"))return fail("The take was paused. Resume the game and start a new take.");
            if(now>deadline)return fail("Capture preparation stayed paused too long. Resume the game and try again.");
            return null; // Before arming, a loading/menu pause must not consume or start the take.
        }
        if(serial!=lastVersion){lastVersion=serial;lastSeen=now;}
        if(now>deadline&&!phase.equals("running"))return fail("Capture preparation timed out during "+phase+". "+(v==null?"No LIVE server acknowledgment.":v.issue()));
        if(v==null)return null;
        String problem=invalid(v);if(problem!=null)return fail(problem);
        if(phase.equals("session")){
            if(serial<=version)return null;
            claim(v);return command("return","fork return",serial,now);
        }
        if(!owns(v)||v.state().revision()!=revision||v.state().allocation()!=allocation)return fail("The authoritative LIVE branch changed outside this capture.");
        if(phase.equals("return")){
            if(serial<=version||!v.atCourt()||v.traveling())return null;
            phase="bodies";deadline=now+20000;
        }
        if(!v.actorsReady()||!bodiesVisible){
            if(phase.equals("bodies"))return null;
            return fail("All three LIVE agents must remain present. Capture stopped.");
        }
        if(phase.equals("bodies"))return command("prepare","fork presentation prepare",serial,now);
        if(p!=null&&!p.error().isBlank()&&(!phase.equals("prepare")||serial>version))return fail("Recorded world preparation failed: "+p.error());
        if(phase.equals("prepare")){
            if(serial<=version||p==null||!p.ready()||!p.owner().equals(owner)||!p.event().equals("initial"))return null;
            if(p.durationMs()!=90000||p.restoreAfterMs()!=93000||p.sourceDigest().isBlank())return fail("The prepared LIVE recording has an incompatible timeline.");
            bundle=p.bundleId();digest=p.sourceDigest();phase="warmup";deadline=now+90000;
            return new Action("prime","");
        }
        if(phase.equals("running")&&epochNow>=startEpochMs+93000&&p!=null&&!p.active()&&p.error().isBlank())return null;
        if(phase.equals("warmup")||phase.equals("arm")||phase.equals("running")){
            if(!ownsProjection(p)||!p.ready()||!p.sourceDigest().equals(digest))return fail("Recorded world ownership or source evidence changed.");
            if(now-lastSeen>5000)return fail("Server readiness updates stopped.");
        }
        if(phase.equals("warmup")){
            if(!firstFrameReady)return null;
            return command("arm","fork presentation start "+bundle,serial,now);
        }
        if(phase.equals("arm")){
            if(serial<=version||!p.active())return null;
            if(p.startEpochMs()-epochNow<500)return fail("The recording start acknowledgment arrived too late.");
            startEpochMs=p.startEpochMs();phase="running";
            return new Action("begin",Long.toString(startEpochMs));
        }
        if(phase.equals("running")&&!p.active()&&epochNow<startEpochMs+93000)return fail("The recorded world stopped before the clean take finished.");
        return null;
    }
    public void complete(){phase="done";}
    public void cancel(){if(active())phase="cancelled";}
    public static final class FocusLease implements AutoCloseable {
        private final boolean previous;private Consumer<Boolean> setter;
        public FocusLease(boolean previous,Consumer<Boolean> setter){this.previous=previous;this.setter=setter;setter.accept(false);}
        public void close(){if(setter!=null){var restore=setter;setter=null;restore.accept(previous);}}
    }
}
