package dev.fork.gameplay;

import java.util.EnumMap;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** Uses the same packaged ResourceTexture/PlayerSkin path as the existing Arena skin renderer. */
public final class ForkRoleSkins {
    private static final EnumMap<ForkRoleIdentity,PlayerSkin> SKINS=new EnumMap<>(ForkRoleIdentity.class);
    static {
        for(var role:ForkRoleIdentity.values()) {
            var id=Identifier.fromNamespaceAndPath("fork","textures/entity/"+role.texture+".png");
            var body=new ClientAsset.ResourceTexture(id,id);
            SKINS.put(role,new PlayerSkin(body,null,null,PlayerModelType.WIDE,false));
        }
    }
    private ForkRoleSkins() {}
    public static PlayerSkin skin(ForkRoleIdentity role) { return SKINS.get(role); }
}
