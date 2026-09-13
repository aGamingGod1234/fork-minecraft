package dev.fork.gameplay;
import java.util.*;
import java.util.concurrent.atomic.*;
public final class ForkCaptureGateVerification {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static ForkView view(ForkEngine e,boolean at,boolean ready){return new ForkView(e.state(),null,List.of(),List.of(),List.of(),false,false,true,at,ready,false,"","Singapore",List.of(),ready);}
 static ForkCaptureGate.Projection projection(boolean active,long start){return new ForkCaptureGate.Projection("bundle",true,active,"human",start,90000,93000,"initial",0,"digest","");}
 static void action(ForkCaptureGate.Action a,String kind,String value){check(a!=null&&a.kind().equals(kind)&&a.value().equals(value),"Expected "+kind+" "+value+", got "+a);}
 static ForkCaptureGate.Action update(ForkCaptureGate g,ForkView v,ForkCaptureGate.Projection p,long serial,long now,boolean bodies,boolean frame,boolean paused){return g.update(v,p,serial,now,now,bodies,frame,paused);}
 public static void main(String[] args){
  var e=new ForkEngine(ForkEngine.Mode.LIVE);var gate=new ForkCaptureGate();
  action(gate.start(null,0,0,"human"),"command","fork new live");
  check(update(gate,null,null,0,100,false,false,false)==null,"No view cannot begin");
  action(update(gate,view(e,false,false),null,1,200,false,false,false),"command","fork return");
  check(update(gate,view(e,true,true),null,1,300,true,false,false)==null,"Stale return rejected");
  check(update(gate,view(e,true,true),null,2,400,false,false,false)==null,"Rendered bodies required");
  action(update(gate,view(e,true,true),null,3,500,true,false,false),"command","fork presentation prepare");
  check(update(gate,view(e,true,true),new ForkCaptureGate.Projection("",false,false,"",0,90000,93000,"",0,"","old failure"),3,600,true,false,false)==null,"Stale error cannot reject a fresh prepare");
  action(update(gate,view(e,false,true),projection(false,0),4,700,true,false,false),"prime","");
  check(update(gate,view(e,false,true),projection(false,0),5,800,true,false,false)==null,"Wait for rendered sections");
  action(update(gate,view(e,false,true),projection(false,0),6,900,true,true,false),"command","fork presentation start bundle");
  action(update(gate,view(e,false,true),projection(true,4000),7,1000,true,true,false),"begin","4000");
  check(update(gate,view(e,false,true),new ForkCaptureGate.Projection("",false,false,"",4000,90000,93000,"done",93000,"digest",""),8,97000,true,true,false)==null,"Terminal packet at93s waits for final rendered sample");
  check(update(gate,view(e,false,true),projection(true,4000),9,97001,true,true,true).kind().equals("fail"),"Pause aborts");
  var timeout=new ForkCaptureGate();timeout.start(null,0,0,"human");check(update(timeout,null,null,0,25001,false,false,false).kind().equals("fail"),"No empty bootstrap");
  var lost=new ForkCaptureGate();lost.start(view(e,true,true),1,0,"human");check(update(lost,view(new ForkEngine(ForkEngine.Mode.LIVE),true,true),null,2,100,true,false,false).kind().equals("fail"),"Branch ownership");
  var power=new ForkCaptureGate();power.start(view(e,true,true),1,0,"human");e.power(ForkEngine.Power.CLINIC);check(update(power,view(e,true,true),null,2,100,true,false,false).kind().equals("fail"),"Power change without revision");
  var fixture=new ForkCaptureGate();check(fixture.start(view(new ForkEngine(ForkEngine.Mode.FIXTURE),true,true),0,0,"human").kind().equals("fail"),"No Fixture");
  var cancelled=new ForkCaptureGate();cancelled.start(null,0,0,"human");cancelled.cancel();check(!cancelled.active(),"Stop");
  var pref=new AtomicBoolean(true);var writes=new AtomicInteger();var lease=new ForkCaptureGate.FocusLease(pref.get(),v->{pref.set(v);writes.incrementAndGet();});check(!pref.get(),"Focus pause disabled");lease.close();lease.close();check(pref.get()&&writes.get()==2,"Restore exactly once");
  System.out.println("PASS fresh LIVE prime, three bodies, render warmup, scheduled clock, ownership/pause guards and restore");
 }
}
