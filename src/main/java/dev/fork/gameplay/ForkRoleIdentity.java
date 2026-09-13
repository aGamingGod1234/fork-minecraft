package dev.fork.gameplay;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Exact FORK actor bindings. Carpet renders the offline player UUID, not the engine AgentId. */
public enum ForkRoleIdentity {
    MEDIC("medic"), ENGINEER("engineer"), COURIER("courier");

    public final String texture;
    public final UUID agentId;
    public final String playerName;
    public final UUID playerUuid;

    ForkRoleIdentity(String texture) {
        this.texture=texture;
        agentId=new UUID(0x464f524b00000000L,ordinal()+1);
        playerName="FORK_"+name();
        // Same vanilla offline UUID rule used by Arena's AgentIdentity/OfflineAgentPlayers.
        playerUuid=UUID.nameUUIDFromBytes(("OfflinePlayer:"+playerName).getBytes(StandardCharsets.UTF_8));
    }

    public static ForkRoleIdentity forPlayer(UUID uuid,String name,boolean localHuman) {
        if(localHuman||uuid==null||name==null) return null;
        for(var role:values()) if(role.playerUuid.equals(uuid)&&role.playerName.equals(name)) return role;
        return null;
    }
}
