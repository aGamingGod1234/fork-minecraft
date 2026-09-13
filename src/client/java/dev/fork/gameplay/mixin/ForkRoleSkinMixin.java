package dev.fork.gameplay.mixin;

import dev.fork.gameplay.ForkClient;
import dev.fork.gameplay.ForkRoleIdentity;
import dev.fork.gameplay.ForkRoleSkins;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Higher priority only for the three exact FORK fake-player identities in an active FORK world. */
@Mixin(value=AbstractClientPlayer.class,priority=1100)
abstract class ForkRoleSkinMixin {
    @Inject(method="getSkin",at=@At("HEAD"),cancellable=true)
    private void fork$roleSkin(CallbackInfoReturnable<PlayerSkin> callback) {
        if(ForkClient.view()==null) return;
        var player=(AbstractClientPlayer)(Object)this;
        var role=ForkRoleIdentity.forPlayer(player.getUUID(),player.getGameProfile().name(),player instanceof LocalPlayer);
        if(role!=null) callback.setReturnValue(ForkRoleSkins.skin(role));
    }
}
