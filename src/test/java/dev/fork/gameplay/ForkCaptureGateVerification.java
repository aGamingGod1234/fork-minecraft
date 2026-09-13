package dev.fork.gameplay;
import java.util.*;
import java.util.concurrent.atomic.*;
public final class ForkCaptureGateVerification {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static ForkView view(ForkEngine e,boolean at,boolean ready){return new ForkView(e.state(),null,List.of(),List.of(),List.of(),false,false,true,at,ready,false,"","Singapore",List.of(),ready);}
 static void action(ForkCaptureGate.Action a,String kind,String value){check(a!=null&&a.kind().equals(kind)&&a.value().equals(value),"Expected "+kind+" "+value+", got "+a);}
 public static void main(String[] args){
  var e=new ForkEngine(ForkEngine.Mode.LIVE);var gate=new ForkCaptureGate();
  action(gate.start(null,0,0),"command","fork new live");
  check(gate.update(null,0,100,false,false)==null,"No view cannot begin capture");
  action(gate.update(view(e,false,false),1,200,false,false),"command","fork return");
  check(gate.update(view(e,true,true),1,300,true,false)==null,"Stale status cannot acknowledge return");
  check(gate.update(view(e,true,true),2,400,false,false)==null,"Server readiness cannot replace rendered NPCs");
  action(gate.update(view(e,true,true),3,1000,true,false),"countdown","3");
  action(gate.update(view(e,true,true),3,2000,true,false),"countdown","2");
  action(gate.update(view(e,true,true),3,3000,true,false),"countdown","1");
  check(gate.update(view(e,true,true),3,4000,true,false)==null,"New readiness acknowledgement required during countdown");
  action(gate.update(view(e,true,true),6,4100,true,false),"begin","");
  check(gate.phase().equals("running"),"Clock begins only after fresh three-body readiness and countdown");
  check(gate.update(view(e,true,true),7,4200,true,true).kind().equals("fail"),"Manual pause aborts instead of stretching take");
  var timeout=new ForkCaptureGate();timeout.start(null,0,0);check(timeout.update(null,0,25001,false,false).kind().equals("fail"),"Failed bootstrap records nothing");
  var lost=new ForkCaptureGate();lost.start(view(e,true,true),1,0);lost.update(view(e,true,true),2,100,true,false);check(lost.update(view(new ForkEngine(ForkEngine.Mode.LIVE),true,true),3,200,true,false).kind().equals("fail"),"Changed branch aborts countdown");
  var missing=new ForkCaptureGate();missing.start(view(e,true,true),1,0);missing.update(view(e,true,true),2,100,true,false);check(missing.update(view(e,true,true),3,200,false,false).kind().equals("fail"),"Missing NPC aborts countdown");
  var fixture=new ForkCaptureGate();check(fixture.start(view(new ForkEngine(ForkEngine.Mode.FIXTURE),true,true),0,0).kind().equals("fail"),"No Fixture substitution");
  var cancelled=new ForkCaptureGate();cancelled.start(null,0,0);cancelled.cancel();check(!cancelled.active()&&cancelled.update(view(e,true,true),1,100,true,false)==null,"Stop prevents later bootstrap actions");
  var pref=new AtomicBoolean(true);var writes=new AtomicInteger();var lease=new ForkCaptureGate.FocusLease(pref.get(),v->{pref.set(v);writes.incrementAndGet();});check(!pref.get(),"Focus pause disabled");lease.close();lease.close();check(pref.get()&&writes.get()==2,"Original true restored exactly once");
  var off=new AtomicBoolean(false);var offLease=new ForkCaptureGate.FocusLease(false,off::set);offLease.close();check(!off.get(),"Original false preserved");
  System.out.println("PASS capture gate: no empty/stale take, LIVE bootstrap+return, all three rendered bodies, fresh3secondcountdown, pause/missingbody/branchchange/timeout abort, stop, focus preference restored once");
 }
}