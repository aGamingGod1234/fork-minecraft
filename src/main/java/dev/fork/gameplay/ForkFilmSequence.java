package dev.fork.gameplay;

import java.util.*;

/** Client orchestration observes server commits; it never computes or supplies role outcomes. */
public final class ForkFilmSequence {
    public record Action(String kind,String value) {}
    private String phase="idle", branch="", issue="";
    private long deadline, after, serial, epoch, revision;
    private int expected;
    private boolean workshop;
    private ForkEngine.State a,b;
    public String phase(){return phase;}
    public boolean active(){return !Set.of("idle","done","error").contains(phase);}
    public ForkEngine.State a(){return a;}
    public ForkEngine.State b(){return b;}
    public boolean owns(ForkView v){return v!=null&&v.canControl()&&v.state().branch().equals(branch)&&v.state().epoch()==epoch;}
    private List<Action> command(String phase,String command,long version,long now,long wait){this.phase=phase;serial=version;deadline=now+wait;return List.of(new Action("command",command));}
    private List<Action> fail(String message){phase="error";return List.of(new Action("abort",message));}
    public List<Action> start(ForkView v,long version,long now){
        if(active()) return List.of(new Action("notice","Film is already running. /fork film stop cancels it."));
        a=null;b=null;workshop=false;branch=v==null?"":v.state().branch();epoch=v==null?0:v.state().epoch();
        if(v==null)return command("session","fork new live",version,now,25000);
        if(v.state().mode()!=ForkEngine.Mode.LIVE)return fail("Film needs a LIVE session. Restart this world and run /fork film start; Fixture footage is never substituted.");
        if(!v.canControl())return fail("This player cannot control FORK.");
        var actions=new ArrayList<Action>();if(v.pending())actions.add(new Action("command","fork cancel"));
        actions.addAll(command("return","fork return",version,now,15000));return actions;
    }
    public List<Action> stop(){phase="done";return List.of(new Action("abort","Film stopped. Completed LIVE receipts remain saved."));}
    public List<Action> update(ForkView v,long version,long now,boolean cameraActive){
        if(!active())return List.of();
        if(now>deadline)return fail("Timed out during "+phase+". "+(v==null?"No FORK server status received.":v.issue()));
        if(v==null)return List.of();
        if(v.state().mode()!=ForkEngine.Mode.LIVE||!v.canControl())return fail("LIVE session or control permission changed.");
        boolean fresh=version>serial;
        if(Set.of("round","round_hold","a_hold","compare").contains(phase)) {
            var wanted=workshop?ForkEngine.Power.WORKSHOP:ForkEngine.Power.CLINIC;
            if(v.state().allocation()!=wanted||v.roundPower().stream().anyMatch(p->!p.equals(wanted.name())))return fail("Power allocation changed outside the film.");
        }
        if(!Set.of("session","return","reset","rewind").contains(phase)){
            if(!v.state().branch().equals(branch)||v.state().epoch()!=epoch)return fail("Branch or epoch changed outside the film. Recording stopped.");
            if(v.paused())return fail("Server paused: "+v.issue());
        }
        switch(phase){
            case "session":branch=v.state().branch();epoch=v.state().epoch();return command("return","fork return",version,now,15000);
            case "return":if(fresh&&v.atCourt()&&!v.traveling()) {epoch=v.state().epoch();return command("reset","fork rewind",version,now,15000);}break;
            case "reset":case "rewind":
                if(fresh&&v.state().epoch()>epoch&&!v.state().branch().equals(branch)&&v.state().round()==0&&!v.paused()){
                    branch=v.state().branch();epoch=v.state().epoch();
                    if(phase.equals("reset")){phase="reel";after=now+35000;deadline=now+45000;return List.of(new Action("reel",""));}
                    return command("power","fork power workshop",version,now,10000);
                }break;
            case "reel":
                if(!cameraActive&&now<after-1000)return fail("Showcase camera stopped before its final shot.");
                if(!cameraActive&&now>=after-1000)return command("power","fork power clinic",version,now,10000);
                break;
            case "power":
                if(fresh&&v.state().allocation()==(workshop?ForkEngine.Power.WORKSHOP:ForkEngine.Power.CLINIC)&&v.state().round()==0&&!v.pending()){
                    var out=new ArrayList<Action>();out.add(new Action("camera",workshop?"fork_workshop":"fork_clinic"));out.addAll(advance(v,version,now));return out;
                }break;
            case "round":
                if(v.state().round()>expected)return fail("Unexpected extra round; film stopped.");
                if(fresh&&v.state().round()==expected&&v.state().revision()==revision+1&&!v.pending()&&v.effects().size()==3&&v.roundPower().size()==expected){
                    phase="round_hold";after=now+2500;deadline=now+10000;return List.of();
                }
                if(fresh&&!v.pending()&&v.state().round()<expected&&!v.issue().equals(issue)&&!v.issue().isBlank())return fail("LIVE round did not commit: "+v.issue());
                break;
            case "round_hold":
                if(v.state().round()!=expected||v.state().revision()!=revision+1)return fail("Round state changed outside the film.");
                if(now>=after){
                    if(expected<6)return advance(v,version,now);
                    if(!workshop){a=v.state();phase="a_hold";after=now+6000;deadline=now+12000;return List.of(new Action("summary","A"));}
                    b=v.state();phase="compare";after=now+12000;deadline=now+18000;return List.of(new Action("command","fork compare"),new Action("summary","compare"));
                }break;
            case "a_hold":
                if(v.state().round()!=6||a==null)return fail("Clinic branch must finish all six rounds before rewind.");
                if(now>=after){workshop=true;var out=new ArrayList<Action>();out.add(new Action("close",""));out.addAll(command("rewind","fork rewind",version,now,15000));return out;}break;
            case "compare":
                if(now>=after){var out=new ArrayList<Action>();out.add(new Action("close",""));out.addAll(command("explore","fork explore",version,now,15000));return out;}break;
            case "explore":
                if(fresh&&!v.traveling()&&!v.atCourt()&&v.issue().contains("EXPLORE |")){phase="city";after=now+12000;deadline=now+18000;return List.of(new Action("camera","fork_city_flythrough"));}break;
            case "city":
                if(now>=after){var out=new ArrayList<Action>();out.add(new Action("stop_camera",""));out.addAll(command("final_return","fork return",version,now,15000));return out;}break;
            case "final_return":
                if(fresh&&v.atCourt()&&!v.traveling()){phase="done";return List.of(new Action("done","Recording sequence complete. Stop OBS. Captured showcase, twelve real LIVE rounds, A/B comparison and Singapore Explore."));}break;
            default:break;
        }
        return List.of();
    }
    private List<Action> advance(ForkView v,long version,long now){
        expected=v.state().round()+1;revision=v.state().revision();issue=v.issue();
        return command("round","fork advance",version,now,26000);
    }
}