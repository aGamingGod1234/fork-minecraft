package dev.fork.gameplay;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Simulated server receipts exercise automation; this never runs or fabricates demo footage. */
public final class ForkFilmVerification {
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static ForkView view(ForkEngine e,boolean at,String issue){var rs=e.receipts();return new ForkView(e.state(),null,rs.isEmpty()?List.of():rs.getLast().effects(),rs.stream().map(r->r.state().allocation().name()).toList(),List.of(),e.pending()!=null,e.paused(),true,at,true,false,issue,"Singapore",List.of());}
    public static void main(String[] args){
        var time=new AtomicLong();var engine=new ForkEngine(ForkEngine.Mode.LIVE,time::get);var film=new ForkFilmSequence();
        long now=0,version=1,reelEnd=0,commitAt=0;boolean at=true;String issue="";int advances=0,rewinds=0;long compareAt=-1,exploreAt=-1;
        List<ForkFilmSequence.Action> actions=film.start(view(engine,at,issue),version,now);
        while(film.active()&&now<300000){
            for(var action:actions){switch(action.kind()){
                case "abort" -> throw new AssertionError(action.value());
                case "reel" -> reelEnd=now+35000;
                case "summary" -> {if(action.value().equals("compare"))compareAt=now;}
                case "command" -> {switch(action.value()){
                    case "fork return" -> {at=true;issue="At court";}
                    case "fork rewind" -> {if(rewinds==1)check(engine.state().round()==6,"Never rewind unfinished A");long ep=engine.beginRewind();engine.finishRewind(ep,true);rewinds++;}
                    case "fork power clinic" -> engine.power(ForkEngine.Power.CLINIC);
                    case "fork power workshop" -> engine.power(ForkEngine.Power.WORKSHOP);
                    case "fork advance" -> {check(engine.pending()==null,"No duplicate pending request");engine.begin();advances++;commitAt=now+1500;}
                    case "fork explore" -> {exploreAt=now;at=false;issue="EXPLORE | Building enabled";}
                    default -> {}
                }}
                default -> {}
            }}
            now+=100;time.set(now*1000000);version++;
            if(engine.pending()!=null&&now>=commitAt){var t=engine.pending();engine.commit(new ForkEngine.Batch(t,List.of(new ForkEngine.Intent(ForkEngine.Role.MEDIC,t.requestId()+"m","request_spare"),new ForkEngine.Intent(ForkEngine.Role.ENGINEER,t.requestId()+"e","repair"),new ForkEngine.Intent(ForkEngine.Role.COURIER,t.requestId()+"c","deliver_spare"))));}
            actions=film.update(view(engine,at,issue),version,now,now<reelEnd);
        }
        check(film.phase().equals("done")&&advances==12&&rewinds==2,"All real receipt-driven phases finish");
        check(film.a().round()==6&&film.b().round()==6&&film.a().mode()==ForkEngine.Mode.LIVE,"Actual LIVE states captured");
        check(exploreAt-compareAt>=12000,"Full twelve-second comparison hold");
        var fixture=new ForkEngine(ForkEngine.Mode.FIXTURE);check(new ForkFilmSequence().start(view(fixture,true,""),1,0).getFirst().kind().equals("abort"),"Never substitute Fixture");
        var stopped=new ForkFilmSequence();stopped.start(view(engine,true,""),1,0);stopped.stop();check(!stopped.active()&&stopped.update(view(engine,true,""),2,1000,false).isEmpty(),"Stop prevents all later work");
        var stalled=new ForkFilmSequence();stalled.start(null,1,0);check(stalled.update(null,1,26000,false).getFirst().kind().equals("abort"),"Missing server timeout");
        System.out.println("PASS film: twelve LIVE receipt waits, no duplicate advances, A6-only rewind, B6,12s comparison,Explore/return,Fixture rejection,stop,timeout");
    }
}