package dev.fork.gameplay;

import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.List;

public final class ForkFilmClient {
    private static final ForkFilmSequence sequence=new ForkFilmSequence();
    private static long version;
    private static CameraDirectorClient.PresentationClock clock;
    private static FabricClientCommandSource source;
    private static Object level,player;
    private static boolean owned;
    private static Object preparedLevel;
    public static void acceptView(){version++;}
    public static long viewVersion(){return version;}
    public static void register(){
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,registry)->dispatcher.register(ClientCommands.literal("camera").then(ClientCommands.literal("film")
            .then(ClientCommands.literal("start").executes(c->{
                if(sequence.active()||ForkProductCapture.active()){notice("Film is already running. /camera film stop cancels it.");return 0;}
                var client=c.getSource().getClient();if(client.player==null||client.level==null)return 0;
                source=c.getSource();level=client.level;player=client.player;owned=false;
                clock=new CameraDirectorClient.PresentationClock(System.nanoTime(),client.isPaused());
                apply(sequence.start(ForkClient.view(),version,0));return sequence.active()?1:0;
            }))
            .then(ClientCommands.literal("take").executes(c->{if(sequence.active()) {notice("Wait for preparation, or stop it first.");return 0;}source=c.getSource();try{boolean ready=preparedLevel==c.getSource().getClient().level&&ForkProductCapture.valid(sequence.a(),ForkEngine.Power.CLINIC)&&ForkProductCapture.valid(sequence.b(),ForkEngine.Power.WORKSHOP);if(!ready)notice("Product take: three LIVE agents will be verified first. No completed A/B comparison is cached; its slots show product footage without invented results.");ForkProductCapture.start(ready?sequence.a():null,ready?sequence.b():null);return 1;}catch(Exception e){notice(e.getMessage());return 0;}}))
            .then(ClientCommands.literal("prepare").executes(c->{if(sequence.active()||ForkProductCapture.active())return 0;var client=c.getSource().getClient();source=c.getSource();level=client.level;player=client.player;owned=false;clock=new CameraDirectorClient.PresentationClock(System.nanoTime(),client.isPaused());apply(sequence.prepare(ForkClient.view(),version,0));return sequence.active()?1:0;}))
            .then(ClientCommands.literal("stop").executes(c->{if(ForkProductCapture.active())ForkProductCapture.stop();else apply(sequence.stop());return 1;}))
            .then(ClientCommands.literal("status").executes(c->{notice("Film: "+sequence.phase()+" | Take: "+ForkProductCapture.status());return 1;})))));
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            ForkProductCapture.tick();
            if(!sequence.active())return;
            if(client.level!=level||client.player!=player||client.getConnection()==null){apply(sequence.stop());return;}
            long now=(long)(clock.advance(System.nanoTime(),client.isPaused())*50);
            apply(sequence.update(ForkClient.view(),version,now,CameraDirectorClient.cleanPlaybackActive()));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{ForkProductCapture.stop();if(sequence.active())apply(sequence.stop());});
    }
    private static void apply(List<ForkFilmSequence.Action> actions){
        var client=Minecraft.getInstance();
        for(var action:actions){
            switch(action.kind()){
                case "command" -> {if(client.getConnection()!=null){owned=true;client.getConnection().sendCommand(action.value());}}
                case "reel" -> {client.setScreen(null);if(CameraDirectorClient.playShowcase(source)==0){apply(sequence.stop());notice("Film stopped: showcase presets missing or invalid.");}}
                case "camera" -> {client.setScreen(null);CameraDirectorClient.playFromGui(action.value(),true);}
                case "summary" -> {stopCamera();client.setScreen(new ForkFilmScreen(sequence.a(),sequence.b()));}
                case "close" -> {if(client.screen instanceof ForkFilmScreen)client.setScreen(null);}
                case "stop_camera" -> stopCamera();
                case "abort" -> {
                    stopCamera();if(client.screen instanceof ForkFilmScreen)client.setScreen(null);
                    if(owned&&sequence.owns(ForkClient.view())&&client.getConnection()!=null&&client.level==level&&client.player==player){client.getConnection().sendCommand("fork cancel");if(ForkClient.view()!=null&&!ForkClient.view().atCourt())client.getConnection().sendCommand("fork return");}
                    owned=false;notice(action.value());
                }
                case "done" -> {preparedLevel=client.level;owned=false;stopCamera();notice(action.value());}
                default -> notice(action.value());
            }
        }
    }
    private static void stopCamera(){if(CameraDirectorClient.cleanPlaybackActive())CameraDirectorClient.stopPlaybackFromGui();}
    public static void notice(String text){var c=Minecraft.getInstance();if(source!=null)source.sendFeedback(Component.literal("FORK FILM: "+text));else if(c.gui!=null)c.gui.setOverlayMessage(Component.literal("FORK FILM: "+text),false);}
}