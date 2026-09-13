package dev.fork.gameplay.mixin;

import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The director uses a marker camera; never draw the human's first-person arm over it. */
@Mixin(GameRenderer.class)
abstract class ForkCameraHandMixin {
    @Inject(method="renderItemInHand",at=@At("HEAD"),cancellable=true)
    private void fork$cleanCameraHand(CallbackInfo callback) {
        if(CameraDirectorClient.cleanPlaybackActive()) callback.cancel();
    }
}
