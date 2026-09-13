package dev.fork.gameplay;

import java.util.List;

/** Read-only projection. Commands and travel are always checked again by the server. */
public record ForkView(ForkEngine.State state, ForkEngine.State archived, List<ForkEngine.Effect> effects,
        List<String> roundPower, List<String> archivedPower,
        boolean pending, boolean paused, boolean canControl, boolean atCourt, boolean canTravel, boolean traveling,
        String issue, String locator, List<Place> places, boolean actorsReady, Presentation presentation) {
    public ForkView(ForkEngine.State state,ForkEngine.State archived,List<ForkEngine.Effect> effects,List<String> roundPower,List<String> archivedPower,
            boolean pending,boolean paused,boolean canControl,boolean atCourt,boolean canTravel,boolean traveling,String issue,String locator,List<Place> places) {
        this(state,archived,effects,roundPower,archivedPower,pending,paused,canControl,atCourt,canTravel,traveling,issue,locator,places,false);
    }
    public ForkView(ForkEngine.State state,ForkEngine.State archived,List<ForkEngine.Effect> effects,List<String> roundPower,List<String> archivedPower,
            boolean pending,boolean paused,boolean canControl,boolean atCourt,boolean canTravel,boolean traveling,String issue,String locator,List<Place> places,boolean actorsReady) {
        this(state,archived,effects,roundPower,archivedPower,pending,paused,canControl,atCourt,canTravel,traveling,issue,locator,places,actorsReady,null);
    }
    public record Presentation(String bundleId,boolean ready,boolean active,String owner,long startEpochMs,
            long durationMs,long restoreAfterMs,String event,long eventOffsetMs,int round,String sourceDigest,String error,boolean buildPlaced) {}
    /** Capture ownership is distinct from the server lock against changing a presented branch. */
    public static boolean captureControl(boolean authorized,boolean presenting,java.util.UUID player,java.util.UUID owner) {
        return authorized&&(!presenting || player!=null&&player.equals(owner));
    }
    public record Place(String id, String name) {}
}
