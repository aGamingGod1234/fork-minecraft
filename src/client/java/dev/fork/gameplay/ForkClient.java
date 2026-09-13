package dev.fork.gameplay;

import com.google.gson.Gson;
import dev.fork.integration.ForkStatusPayload;
import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import dev.agaminggod.arenaagents.client.gui.AgentControlScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public final class ForkClient implements ClientModInitializer {
    private static final Gson JSON = new Gson();
    private static ForkView view;
    public static ForkView view() { return view; }
    @Override public void onInitializeClient() {
        ForkFilmClient.register();
        ClientPlayNetworking.registerGlobalReceiver(ForkStatusPayload.TYPE,(payload,context)->context.client().execute(()->{
            if(payload.json().equals("camera_stop")) { CameraDirectorClient.stopPlaybackFromGui(); return; }
            ForkView next=JSON.fromJson(payload.json(),ForkView.class);
            boolean changed=!next.equals(view); view=next; ForkFilmClient.acceptView();
            if(changed && context.client().screen instanceof AgentControlScreen screen) screen.acceptForkView();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->view=null);
    }
}
