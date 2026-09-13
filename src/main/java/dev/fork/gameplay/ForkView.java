package dev.fork.gameplay;

import java.util.List;

/** Read-only projection. Commands and travel are always checked again by the server. */
public record ForkView(ForkEngine.State state, ForkEngine.State archived, List<ForkEngine.Effect> effects,
        List<String> roundPower, List<String> archivedPower,
        boolean pending, boolean paused, boolean canControl, boolean atCourt, boolean canTravel, boolean traveling,
        String issue, String locator, List<Place> places) {
    public record Place(String id, String name) {}
}
