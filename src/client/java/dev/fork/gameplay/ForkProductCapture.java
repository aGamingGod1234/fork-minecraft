package dev.fork.gameplay;
import java.util.List;
import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import net.minecraft.client.Minecraft;

/** A fixed 90-second presentation; no server commands or provider requests are sent during capture. */
public final class ForkProductCapture {
    public static final List<Integer> SECONDS=List.of(7,3,11,6,6,8,6,7,6,10,9,6,5);
    private static final List<String> PATHS=List.of("fork_intro","fork_city_crane","fork_city_flythrough","fork_courier","fork_overview","fork_clinic","fork_overview","fork_city_crane","fork_workshop","fork_comparison","fork_city_flythrough","fork_city_crane","fork_overview");
    private static CameraDirectorClient.PresentationClock clock;
    private static Object level,player;
    private static ForkEngine.State a,b;
    private static int overlay;
    public static boolean active(){return clock!=null;}
    public static void start(ForkEngine.State preparedA,ForkEngine.State preparedB){
        var c=Minecraft.getInstance();
        if(active())throw new IllegalStateException("A 90-second take is already running");
        a=valid(preparedA,ForkEngine.Power.CLINIC)?preparedA:null;
        b=valid(preparedB,ForkEngine.Power.WORKSHOP)?preparedB:null;
        level=c.level;player=c.player;overlay=0;c.setScreen(null);
        clock=CameraDirectorClient.playTimedTake(PATHS,SECONDS.stream().map(s->s*20).toList());
    }
    public static boolean valid(ForkEngine.State s,ForkEngine.Power p){return s!=null&&s.mode()==ForkEngine.Mode.LIVE&&s.round()==6&&s.allocation()==p&&s.reroutes()==0;}
    public static void tick(){
        if(clock==null)return;
        var c=Minecraft.getInstance();
        if(c.level!=level||c.player!=player){stop();return;}
        double seconds=clock.advance(System.nanoTime(),c.isPaused())/20;
        int desired=seconds>=41&&seconds<47&&a!=null?1:seconds>=60&&seconds<70&&a!=null&&b!=null?2:0;
        if(desired!=overlay){
            if(desired>0)c.setScreen(new ForkFilmScreen(a,desired==2?b:null,true));
            else if(c.screen instanceof ForkFilmScreen)c.setScreen(null);
            overlay=desired;
        }
        if(seconds>=90){stop();ForkFilmClient.notice("90-second presentation complete. Stop OBS; trim only before and after the take.");}
        else if(!CameraDirectorClient.cleanPlaybackActive()){stop();ForkFilmClient.notice("Take interrupted before 90 seconds. This is not a complete take.");}
    }
    public static void stop(){
        clock=null;var c=Minecraft.getInstance();if(c.screen instanceof ForkFilmScreen)c.setScreen(null);
        if(CameraDirectorClient.cleanPlaybackActive())CameraDirectorClient.stopPlaybackFromGui();
    }
}