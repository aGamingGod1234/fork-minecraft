package dev.fork.gameplay;

import java.util.*;
import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import net.minecraft.client.Minecraft;

/** Bootstrap is separate from the fixed take; no server commands occur during its 90-second clock. */
public final class ForkProductCapture {
    public static final List<Integer> SECONDS=List.of(7,3,11,6,6,8,6,7,6,10,9,6,5);
    public static final List<String> PATHS=java.util.stream.IntStream.rangeClosed(1,13).mapToObj(i->String.format(Locale.ROOT,"fork_take_%02d",i)).toList();
    private static ForkCaptureGate gate;
    private static ForkCaptureGate.FocusLease focus;
    private static Runnable restorePerformance;
    private static CameraDirectorClient.PresentationClock clock;
    private static Object level,player;
    private static ForkEngine.State a,b;
    private static int overlay;
    private static boolean returnRequested;
    public static boolean active(){return gate!=null&&gate.active();}
    public static String status(){return gate==null?"idle":gate.phase();}
    public static void start(ForkEngine.State preparedA,ForkEngine.State preparedB){
        var c=Minecraft.getInstance();
        if(active())throw new IllegalStateException("Capture is already scheduled. /camera film stop cancels it.");
        if(c.level==null||c.player==null||c.getConnection()==null)throw new IllegalStateException("Join the Singapore world first.");
        CameraDirectorClient.validateTimedTake(PATHS,SECONDS.stream().map(s->s*20).toList());
        a=valid(preparedA,ForkEngine.Power.CLINIC)?preparedA:null;b=valid(preparedB,ForkEngine.Power.WORKSHOP)?preparedB:null;
        level=c.level;player=c.player;overlay=0;clock=null;returnRequested=false;
        gate=new ForkCaptureGate();
        int render=c.options.renderDistance().get(),simulation=c.options.simulationDistance().get(),fps=c.options.framerateLimit().get(),fov=c.options.fov().get();
        boolean vsync=c.options.enableVsync().get(),bob=c.options.bobView().get();
        restorePerformance=()->{c.options.renderDistance().set(render);c.options.simulationDistance().set(simulation);c.options.framerateLimit().set(fps);c.options.enableVsync().set(vsync);c.options.bobView().set(bob);c.options.fov().set(fov);};
        focus=new ForkCaptureGate.FocusLease(c.options.pauseOnLostFocus,value->c.options.pauseOnLostFocus=value);
        try {
        c.options.renderDistance().set(16);c.options.simulationDistance().set(5);c.options.framerateLimit().set(30);c.options.enableVsync().set(false);c.options.bobView().set(false);c.options.fov().set(70);
        c.setScreen(null);
        ForkFilmClient.notice("Checking the LIVE session and three agents. The 90-second take begins only after the 3-second countdown.");
        handle(gate.start(ForkClient.view(),ForkFilmClient.viewVersion(),millis()));
        } catch(Exception e){stop();throw e;}
    }
    public static boolean valid(ForkEngine.State s,ForkEngine.Power p){return s!=null&&s.mode()==ForkEngine.Mode.LIVE&&s.round()==6&&s.allocation()==p&&s.reroutes()==0;}
    private static long millis(){return System.nanoTime()/1000000;}
    private static boolean bodiesVisible(){
        var c=Minecraft.getInstance();if(c.level==null)return false;
        var names=new HashSet<String>();for(var p:c.level.players())names.add(p.getName().getString());
        return names.containsAll(Set.of("FORK_MEDIC","FORK_ENGINEER","FORK_COURIER"));
    }
    public static void tick(){
        if(!active())return;
        var c=Minecraft.getInstance();
        if(c.level!=level||c.player!=player||c.getConnection()==null){abort("World or player changed. Capture stopped.");return;}
        try{
            handle(gate.update(ForkClient.view(),ForkFilmClient.viewVersion(),millis(),bodiesVisible(),c.isPaused()));
            if(!active()||clock==null)return;
            double seconds=clock.advance(System.nanoTime(),false)/20;
            if(seconds>=90){finish("90-second presentation complete. Stop OBS; retain the full take in order.");return;}
            if(!CameraDirectorClient.ownsTimedTake(clock)){abort("Camera ownership changed before 90 seconds. This is not a complete take.");return;}
            int desired=seconds>=41&&seconds<47&&a!=null?1:seconds>=60&&seconds<70&&a!=null&&b!=null?2:0;
            if(desired!=overlay){
                if(desired>0)c.setScreen(new ForkFilmScreen(a,desired==2?b:null,true));
                else if(c.screen instanceof ForkFilmScreen)c.setScreen(null);
                overlay=desired;
            }
        }catch(Exception e){abort("Capture stopped: "+e.getMessage());}
    }
    private static void handle(ForkCaptureGate.Action action){
        if(action==null)return;var c=Minecraft.getInstance();
        switch(action.kind()){
            case "command" -> {if(action.value().equals("fork return"))returnRequested=true;c.getConnection().sendCommand(action.value());}
            case "countdown" -> {returnRequested=false;ForkFilmClient.notice("Three LIVE agents ready. Framebuffer "+c.getWindow().getWidth()+"x"+c.getWindow().getHeight()+"; render 16, simulation 5, 30 FPS cap, VSync/bobbing/focus-pause off. Recording starts in "+action.value()+"...");}
            case "begin" -> {c.setScreen(null);clock=CameraDirectorClient.playTimedTake(PATHS,SECONDS.stream().map(s->s*20).toList());}
            case "fail" -> abort(action.value());
            default -> throw new IllegalArgumentException("Unknown capture action");
        }
    }
    private static void abort(String message){stop();ForkFilmClient.notice(message);}
    private static void finish(String message){if(gate!=null)gate.complete();stop();ForkFilmClient.notice(message);}
    public static void stop(){
        var c=Minecraft.getInstance();var ownedClock=clock;clock=null;
        var view=ForkClient.view();
        boolean cancelReturn=returnRequested&&gate!=null&&gate.owns(view)&&!view.pending()&&c.level==level&&c.player==player&&c.getConnection()!=null;
        returnRequested=false;
        if(gate!=null)gate.cancel();
        try{
            if(cancelReturn)c.getConnection().sendCommand("fork cancel");
            if(c.screen instanceof ForkFilmScreen)c.setScreen(null);
            if(ownedClock!=null&&CameraDirectorClient.ownsTimedTake(ownedClock))CameraDirectorClient.stopPlaybackFromGui();
        }finally{try{if(restorePerformance!=null){var restore=restorePerformance;restorePerformance=null;restore.run();}}finally{if(focus!=null){focus.close();focus=null;c.options.save();}}}
    }
}