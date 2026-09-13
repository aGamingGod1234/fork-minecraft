package dev.fork.gameplay;
import java.util.*;

/** Regression for the real status -> capture gate ownership boundary. */
public final class ForkCaptureControlVerification {
    private static final UUID OWNER=UUID.fromString("00000000-0000-0000-0000-000000000001"),OTHER=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    private static ForkView view(ForkEngine e,UUID player,boolean authorized,boolean presenting){
        boolean control=ForkView.captureControl(authorized,presenting,player,presenting?OWNER:null);
        return new ForkView(e.state(),null,List.of(),List.of(),List.of(),false,false,control,!presenting,false,false,"","Singapore",List.of(),true);
    }
    private static ForkCaptureGate.Projection projection(boolean running){return new ForkCaptureGate.Projection("bundle",true,running,OWNER.toString(),4000,90000,93000,"initial",0,"digest","");}
    private static void action(ForkCaptureGate.Action a,String kind){check(a!=null&&a.kind().equals(kind),"Expected "+kind+", got "+a);}
    public static void main(String[] args){
        var e=new ForkEngine(ForkEngine.Mode.LIVE);
        check(ForkView.captureControl(true,false,OTHER,null),"Normal authorized human retains control");
        check(ForkView.captureControl(true,true,OWNER,OWNER),"Presented branch retains its authorized capture owner");
        check(!ForkView.captureControl(true,true,OTHER,OWNER),"Unrelated authorized human cannot acquire current capture");
        check(!ForkView.captureControl(false,true,OWNER,OWNER),"Ownership never substitutes for real permission");
        var gate=new ForkCaptureGate();
        action(gate.start(view(e,OWNER,true,false),1,0,OWNER.toString()),"command");
        check(gate.update(view(e,OWNER,true,false),null,1,100,100,true,false,true)==null,"Cached pre-arm pause waits without issuing a command");
        action(gate.update(view(e,OWNER,true,false),null,2,200,200,true,false,false),"command");
        action(gate.update(view(e,OWNER,true,true),projection(false),3,300,300,true,false,false),"prime");
        check(gate.owns(view(e,OWNER,true,true)),"Owner can still issue cleanup after server prime");
        action(gate.update(view(e,OWNER,true,true),projection(false),4,400,400,true,true,false),"command");
        action(gate.update(view(e,OWNER,true,true),projection(true),5,1000,1000,true,true,false),"begin");
        action(gate.update(view(e,OWNER,true,true),projection(true),6,2000,2000,true,true,true),"fail");
        check(gate.owns(view(e,OWNER,true,true)),"An interrupted take still owns server cleanup");
        gate.cancel();
        var retry=new ForkCaptureGate();
        action(retry.start(view(e,OWNER,true,false),7,3000,OWNER.toString()),"command");
        var unauthorized=new ForkCaptureGate();
        action(unauthorized.start(view(e,OTHER,true,true),7,3000,OTHER.toString()),"fail");
        var timeout=new ForkCaptureGate();timeout.start(null,0,0,OWNER.toString());
        action(timeout.update(null,null,0,25001,25001,false,false,true),"fail");
        System.out.println("PASS real status control through prime/arm/abort/retry, unauthorized and unrelated denial, bounded pre-arm pause");
    }
}
