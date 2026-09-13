package dev.fork.gameplay;

/** Detailed metrics belong in inspect/compare, not the narrow actionbar. */
public final class ForkPresentation {
    private ForkPresentation() {}
    public static String compact(ForkEngine.State state, boolean paused, boolean pending) {
        String power=state.allocation()==null?"Choose power":state.allocation()==ForkEngine.Power.CLINIC?"Clinic":"Workshop";
        String status=paused?"Paused":pending?"Thinking":state.complete()?"Complete":"Ready";
        return "FORK | "+state.mode()+" | R"+state.round()+"/6 | "+power+" | "+status;
    }
}
