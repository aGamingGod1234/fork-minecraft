package dev.fork.gameplay;

import java.util.List;

/** Read-only projection. Commands and travel are always checked again by the server. */
public record ForkView(ForkEngine.State state, ForkEngine.State archived, List<ForkEngine.Effect> effects,
        List<String> roundPower, List<String> archivedPower,
        boolean pending, boolean paused, boolean canControl, boolean atCourt, boolean canTravel, boolean traveling,
        String issue, String locator, List<Place> places, boolean actorsReady) {
    public ForkView(ForkEngine.State state,ForkEngine.State archived,List<ForkEngine.Effect> effects,List<String> roundPower,List<String> archivedPower,
            boolean pending,boolean paused,boolean canControl,boolean atCourt,boolean canTravel,boolean traveling,String issue,String locator,List<Place> places) {
        this(state,archived,effects,roundPower,archivedPower,pending,paused,canControl,atCourt,canTravel,traveling,issue,locator,places,false);
    }
    public record Place(String id, String name) {}
}
