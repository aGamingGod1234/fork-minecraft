package dev.fork.gameplay;

import java.util.UUID;

public final class ForkRoleIdentityVerification {
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        for(var role:ForkRoleIdentity.values()) {
            check(ForkRoleIdentity.forPlayer(role.playerUuid,role.playerName,false)==role,"Exact actor binding");
            check(ForkRoleIdentity.forPlayer(role.agentId,role.playerName,false)==null,"Engine AgentId is not the rendered offline player");
            check(ForkRoleIdentity.forPlayer(UUID.randomUUID(),role.playerName,false)==null,"A similarly named human cannot inherit role skin");
            check(ForkRoleIdentity.forPlayer(role.playerUuid,role.playerName,true)==null,"Never override local human, even with matching identity");
            check(ForkRoleIdentity.forPlayer(role.playerUuid,"Lucas",false)==null,"Name and UUID must both match");
            for(var other:ForkRoleIdentity.values()) if(other!=role) check(ForkRoleIdentity.forPlayer(role.playerUuid,other.playerName,false)==null,"Roles cannot inherit each other's textures");
            System.out.println(role+" agent="+role.agentId+" player="+role.playerUuid+" texture="+role.texture+".png");
        }
        check(ForkRoleIdentity.forPlayer(null,null,false)==null,"Missing identity leaves skin unchanged");
        System.out.println("PASS exact role UUID/name binding, engine/player UUID distinction, cross-role and local-human exclusions");
    }
}
