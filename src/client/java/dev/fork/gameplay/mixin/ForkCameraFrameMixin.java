package dev.fork.gameplay.mixin;

import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.1.2 Camera.update runs before alignment, culling, and render-state extraction. */
@Mixin(Camera.class)
abstract class ForkCameraFrameMixin {
    @Inject(method="update(Lnet/minecraft/client/DeltaTracker;)V", at=@At("HEAD"))
    private void fork$samplePresentationFrame(CallbackInfo callback) {
        CameraDirectorClient.updatePresentationFrame();
    }
}
