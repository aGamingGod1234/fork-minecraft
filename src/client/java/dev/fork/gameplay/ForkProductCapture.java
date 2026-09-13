package dev.fork.gameplay;

import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** One raw take of prepared real LIVE states, without screens or provider waits. */
public final class ForkProductCapture {
    private static ForkCaptureGate gate;
    private static ForkCaptureTimeline timeline;
    private static ForkCaptureGate.FocusLease focus;
    private static Runnable restorePerformance;
    private static CameraDirectorClient.PresentationClock clock;
    private static Object level,player;
    private static boolean returnRequested,projectionRequested,buildRequested;
    private static long lastFrameNanos,frameCount,worstFrameGapNanos,lastCueOffset=-1;
    public static boolean active(){return gate!=null&&gate.active();}
    public static String status(){return gate==null?"idle":gate.phase();}
    public static void start(){
        var c=Minecraft.getInstance();
        if(active())throw new IllegalStateException("Capture is already scheduled. /camera film stop cancels it.");
        if(c.level==null||c.player==null||c.getConnection()==null)throw new IllegalStateException("Join the Singapore world first.");
        timeline=CameraDirectorClient.captureTimeline();
        level=c.level;player=c.player;clock=null;returnRequested=false;projectionRequested=false;buildRequested=false;
        lastFrameNanos=0;frameCount=0;worstFrameGapNanos=0;lastCueOffset=-1;gate=new ForkCaptureGate();
        int render=c.options.renderDistance().get(),simulation=c.options.simulationDistance().get(),fps=c.options.framerateLimit().get(),fov=c.options.fov().get();
        boolean vsync=c.options.enableVsync().get(),bob=c.options.bobView().get();
        var inactivity=c.options.inactivityFpsLimit().get();
        var window=c.getWindow();boolean fullscreen=window.isFullscreen();int windowWidth=window.getScreenWidth(),windowHeight=window.getScreenHeight();
        restorePerformance=()->{c.options.renderDistance().set(render);c.options.simulationDistance().set(simulation);c.options.framerateLimit().set(fps);c.options.enableVsync().set(vsync);c.options.bobView().set(bob);c.options.fov().set(fov);c.options.inactivityFpsLimit().set(inactivity);if(fullscreen){if(!window.isFullscreen())window.toggleFullScreen();window.updateFullscreenIfChanged();}else window.setWindowed(windowWidth,windowHeight);};
        focus=new ForkCaptureGate.FocusLease(c.options.pauseOnLostFocus,value->c.options.pauseOnLostFocus=value);
        try {
            c.options.renderDistance().set(16);c.options.simulationDistance().set(5);c.options.framerateLimit().set(60);c.options.enableVsync().set(false);c.options.bobView().set(false);c.options.fov().set(70);c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            c.setScreen(null);window.setWindowed(1920,1080);
            ForkFilmClient.notice("Checking three LIVE agents and complete recorded A/B evidence. Camera warmup precedes the 3-second countdown.");
            handle(gate.start(ForkClient.view(),ForkFilmClient.viewVersion(),millis(),c.player.getUUID().toString()));
        }catch(Exception e){stop();throw e;}
    }
    public static boolean valid(ForkEngine.State s,ForkEngine.Power p){return s!=null&&s.mode()==ForkEngine.Mode.LIVE&&s.round()==6&&s.allocation()==p&&s.reroutes()==0;}
    private static long millis(){return System.nanoTime()/1000000;}
    private static ForkCaptureGate.Projection projection(){
        var view=ForkClient.view();var p=view==null?null:view.presentation();
        return p==null?null:new ForkCaptureGate.Projection(p.bundleId(),p.ready(),p.active(),p.owner(),p.startEpochMs(),p.durationMs(),p.restoreAfterMs(),p.event(),p.eventOffsetMs(),p.sourceDigest(),p.error());
    }
    private static boolean bodiesVisible(){
        var c=Minecraft.getInstance();if(c.level==null)return false;
        var names=new HashSet<String>();for(var p:c.level.players())names.add(p.getName().getString());
        return names.containsAll(Set.of("FORK_MEDIC","FORK_ENGINEER","FORK_COURIER"));
    }
    public static void tick(){
        if(!active())return;
        var c=Minecraft.getInstance();
        if(c.getWindow().isIconified()){abort("Minecraft was minimized. Keep its window open while recording.");return;}
        if(c.level!=level||c.player!=player||c.getConnection()==null){abort("World or player changed. Capture stopped.");return;}
        try{
            if(clock!=null&&clock.elapsedTicks()>=timeline.playbackTicks()){finish();return;}
            handle(gate.update(ForkClient.view(),projection(),ForkFilmClient.viewVersion(),millis(),System.currentTimeMillis(),bodiesVisible(),clock!=null&&CameraDirectorClient.firstFrameReady(clock)&&c.getWindow().getWidth()==1920&&c.getWindow().getHeight()==1080,c.isPaused()));
            if(!active()||clock==null)return;
            // Only Camera.update advances presentation time. Tick code reads the rendered time.
            double ticks=clock.elapsedTicks();
            if(ticks>=timeline.playbackTicks()){finish();return;}
            if(!CameraDirectorClient.ownsTimedTake(clock)){abort("Camera ownership changed before the clean take finished.");return;}
            if(buildRequested&&ticks>=288) {
                var p=ForkClient.view().presentation();
                if(p==null||!p.buildPlaced())abort("The server did not confirm the building interaction. This take is incomplete.");
            }
        }catch(Exception e){abort("Capture stopped: "+e.getMessage());}
    }
    private static void frame(double ticks){try{renderFrame(ticks);}catch(Exception e){abort("Capture interaction failed: "+e.getMessage());}}
    private static void renderFrame(double ticks){
        if(!active()||!gate.phase().equals("running")||System.currentTimeMillis()<gate.startEpochMs())return;
        var c=Minecraft.getInstance();
        if(c.isPaused()||c.screen!=null){abort("A menu or pause interrupted the raw take.");return;}
        var presentation=ForkClient.view()==null?null:ForkClient.view().presentation();
        if(presentation!=null&&presentation.eventOffsetMs()!=lastCueOffset){
            lastCueOffset=presentation.eventOffsetMs();
            System.getLogger("FORK_CAPTURE").log(System.Logger.Level.INFO,"RAW_CUE_RECEIVED event="+presentation.event()+" atMs="+ticks*50+" lagMs="+(ticks*50-lastCueOffset));
        }
        if(ticks<1800){
            long expected=0;
            for(long offset:ForkRecordedTimeline.cueOffsets())if(offset<=ticks*50-250)expected=offset;
            if(presentation==null||presentation.eventOffsetMs()<expected){abort("A recorded world update arrived too late for the narration. This take is incomplete.");return;}
        }
        long now=System.nanoTime();
        if(lastFrameNanos!=0){long gap=now-lastFrameNanos;worstFrameGapNanos=Math.max(worstFrameGapNanos,gap);if(gap>500_000_000L){abort("Rendering stalled for more than half a second. This take is incomplete.");return;}}
        lastFrameNanos=now;frameCount++;
        if(!buildRequested&&ticks>=273){
            if(c.gameMode==null||!c.level.getBlockState(new BlockPos(446,1,425)).isAir()){abort("The building target is unavailable.");return;}
            buildRequested=true;
            c.gameMode.useItemOn(c.player,InteractionHand.MAIN_HAND,new BlockHitResult(new Vec3(446.5,1,425.5),Direction.UP,new BlockPos(446,0,425),false));
        }
    }
    private static void handle(ForkCaptureGate.Action action){
        if(action==null)return;var c=Minecraft.getInstance();
        switch(action.kind()){
            case "command" -> {
                if(action.value().equals("fork return"))returnRequested=true;
                if(action.value().equals("fork presentation prepare")){returnRequested=false;projectionRequested=true;}
                c.getConnection().sendCommand(action.value());
            }
            case "prime" -> {
                ForkFilmClient.notice("Prepared real LIVE states. Framebuffer "+c.getWindow().getWidth()+"x"+c.getWindow().getHeight()+"; 60 FPS cap. Warming the first shot.");
                c.setScreen(null);clock=CameraDirectorClient.primeTimedTake(timeline.paths(),timeline.durations());
                CameraDirectorClient.setTakeFrameListener(clock,ForkProductCapture::frame);
            }
            case "begin" -> {
                long startNanos=System.nanoTime()+Math.multiplyExact(Long.parseLong(action.value())-System.currentTimeMillis(),1_000_000L);
                CameraDirectorClient.armTimedTake(clock,startNanos);
                System.getLogger("FORK_CAPTURE").log(System.Logger.Level.INFO,"RAW_TAKE_ARMED startEpochMs="+action.value()+" duration=90 leadOut=3 fps=60");
            }
            case "fail" -> abort(action.value());
            default -> throw new IllegalArgumentException("Unknown capture action");
        }
    }
    private static void abort(String message){stop();ForkFilmClient.notice(message);}
    private static void finish(){
        if(lastFrameNanos==0||System.nanoTime()-lastFrameNanos>500_000_000L){abort("Rendering stalled across the take ending. This take is incomplete.");return;}
        System.getLogger("FORK_CAPTURE").log(System.Logger.Level.INFO,"RAW_TAKE_COMPLETE renderFrames="+frameCount+" worstGapMs="+worstFrameGapNanos/1_000_000.0);
        if(gate!=null)gate.complete();stop();
        ForkFilmClient.notice("90-second raw take and 3-second clean lead-out complete. Stop OBS; keep the narration's 90 seconds in order.");
    }
    public static void stop(){
        var c=Minecraft.getInstance();var ownedClock=clock;clock=null;
        var view=ForkClient.view();
        boolean sameOwner=gate!=null&&gate.owns(view)&&c.level==level&&c.player==player&&c.getConnection()!=null;
        boolean cancelReturn=returnRequested&&sameOwner&&!view.pending();
        boolean stopProjection=projectionRequested&&sameOwner;
        returnRequested=false;projectionRequested=false;
        if(gate!=null)gate.cancel();
        try{
            if(stopProjection)c.getConnection().sendCommand("fork presentation stop");
            else if(cancelReturn)c.getConnection().sendCommand("fork cancel");
            }finally{try{if(ownedClock!=null)CameraDirectorClient.stopOwnedTake(ownedClock);}finally{try{if(restorePerformance!=null){var restore=restorePerformance;restorePerformance=null;restore.run();}}finally{if(focus!=null){focus.close();focus=null;c.options.save();}}}}
    }
}
