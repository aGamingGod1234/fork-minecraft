package dev.fork.gameplay.mixin;

import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppresses the whole vanilla HUD, including chat, actionbar, locator and status. */
@Mixin(Gui.class)
abstract class ForkCameraGuiMixin {
    @Inject(method="extractRenderState",at=@At("HEAD"),cancellable=true)
    private void fork$cleanCameraHud(CallbackInfo callback) {
        if(CameraDirectorClient.cleanPlaybackActive()) callback.cancel();
    }
}
