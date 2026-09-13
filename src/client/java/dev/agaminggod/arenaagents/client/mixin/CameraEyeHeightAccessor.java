package dev.agaminggod.arenaagents.client.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface CameraEyeHeightAccessor {
	@Accessor("eyeHeight")
	void arenaagents$setEyeHeight(float height);

	@Accessor("eyeHeightOld")
	void arenaagents$setEyeHeightOld(float height);
}
