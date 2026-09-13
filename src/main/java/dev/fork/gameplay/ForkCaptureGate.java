package dev.fork.gameplay;

import java.util.function.Consumer;

/** Pure bootstrap gate: a take cannot begin until server and rendered bodies agree. */
public final class ForkCaptureGate {
    public record Action(String kind,String value) {}
    private String phase="idle",branch="";
    private long epoch,revision,version,deadline,countdownAt,countdownVersion,lastVersion,lastSeen;
    private int countdown=-1;
    public String phase(){return phase;}
    public boolean active(){return !phase.equals("idle")&&!phase.equals("failed")&&!phase.equals("done")&&!phase.equals("cancelled");}
    public boolean owns(ForkView v){return v!=null&&v.canControl()&&v.state().branch().equals(branch)&&v.state().epoch()==epoch;}
    private Action fail(String cause){phase="failed";return new Action("fail",cause);}
    private Action command(String next,String command,long serial,long now){phase=next;version=serial;deadline=now+25000;return new Action("command",command);}
    private void claim(ForkView v){branch=v.state().branch();epoch=v.state().epoch();revision=v.state().revision();}
    private String invalid(ForkView v){
        if(v.state().mode()!=ForkEngine.Mode.LIVE)return "A LIVE session is required; no Fixture substitution is allowed.";
        if(!v.canControl())return "This player cannot control the FORK session.";
        if(v.pending()||v.paused())return "Finish or resolve the current LIVE round before starting a take. "+v.issue();
        return null;
    }
    public Action start(ForkView v,long serial,long now){
        lastVersion=serial;lastSeen=now;
        if(v==null)return command("session","fork new live",serial,now);
        String problem=invalid(v);if(problem!=null)return fail(problem);
        claim(v);return command("return","fork return",serial,now);
    }
    public Action update(ForkView v,long serial,long now,boolean bodiesVisible,boolean paused){
        if(!active())return null;
        if(paused)return fail("Capture paused before completion. Resume the game and start a new take.");
        if(serial!=lastVersion){lastVersion=serial;lastSeen=now;}
        if(now>deadline&&!phase.equals("running"))return fail("Capture bootstrap timed out during "+phase+". No take was recorded. "+(v==null?"The server did not acknowledge the requested fresh LIVE session. Earlier receipts remain saved.":v.issue()));
        if(v==null)return null;
        String problem=invalid(v);if(problem!=null)return fail(problem);
        if(phase.equals("session")){
            if(serial<=version)return null;
            claim(v);return command("return","fork return",serial,now);
        }
        if(!owns(v)||v.state().revision()!=revision)return fail("The LIVE branch changed outside this capture. No complete take is certified.");
        if(phase.equals("return")){
            if(serial<=version||!v.atCourt()||v.traveling())return null;
            phase="ready";version=serial;deadline=now+20000;
        }
        boolean ready=v.actorsReady()&&v.atCourt()&&!v.traveling()&&bodiesVisible;
        if(phase.equals("ready")){
            if(!ready)return null;
            phase="countdown";countdownAt=now+3000;countdownVersion=serial;deadline=now+10000;
        }
        if(phase.equals("countdown")||phase.equals("running")){
            if(!ready)return fail("Three ready LIVE agents must remain visible at the court. Capture stopped.");
            if(now-lastSeen>5000)return fail("Server readiness updates stopped. Capture stopped.");
        }
        if(phase.equals("countdown")){
            if(now>=countdownAt&&serial>countdownVersion){phase="running";return new Action("begin","");}
            int seconds=Math.max(1,(int)Math.ceil((countdownAt-now)/1000.0));
            if(seconds!=countdown){countdown=seconds;return new Action("countdown",Integer.toString(seconds));}
        }
        return null;
    }
    public void complete(){phase="done";}
    public void cancel(){if(active())phase="cancelled";}
    /** Restores the captured preference once, including error and repeated stop paths. */
    public static final class FocusLease implements AutoCloseable {
        private final boolean previous;private Consumer<Boolean> setter;
        public FocusLease(boolean previous,Consumer<Boolean> setter){this.previous=previous;this.setter=setter;setter.accept(false);}
        public void close(){if(setter!=null){var restore=setter;setter=null;restore.accept(previous);}}
    }
}