package dev.fork.gameplay;

/** Detailed metrics belong in inspect/compare, not the narrow actionbar. */
public final class ForkPresentation {
    private ForkPresentation() {}
    public static java.util.List<String> details(ForkView view) {
        var lines=new java.util.ArrayList<String>();
        lines.add(compact(view.state(),view.paused(),view.pending()));
        lines.add(view.atCourt()?"At court":"Outside court: use Locator > Return");
        if(!view.issue().isBlank()) lines.add(view.issue().trim());
        stateLines(lines,"Current",view.state(),view.roundPower());
        lines.add("ROLE PROPOSAL / VALIDATION / EFFECT (last committed round)");
        if(view.effects().isEmpty()) lines.add(view.pending()?"Waiting for all three role proposals; nothing committed":"No round committed yet");
        for(var effect:view.effects()) {
            lines.add(effect.role()+": "+effect.proposal());
            lines.add("  "+effect.validation()+" | "+effect.effect());
        }
        if(view.archived()!=null) {
            lines.add(view.state().complete()?"COMPARISON: equal six-round runs":"Comparison waits for current round 6/6");
            stateLines(lines,"Archived A",view.archived(),view.archivedPower());
        } else lines.add("Complete A, rewind, then complete B to compare six rounds each.");
        return java.util.List.copyOf(lines);
    }
    private static void stateLines(java.util.List<String> lines,String label,ForkEngine.State s,java.util.List<String> powers) {
        lines.add(label+" | "+s.mode()+" | "+s.round()+"/6 | branch "+s.branch()+" | epoch "+s.epoch());
        var cells=new StringBuilder("Service: ");
        for(int i=0;i<6;i++) cells.append(i<s.service().length()?(s.service().charAt(i)=='1'?"[1] ":"[0] "):"[ ] ");
        lines.add(cells.toString());
        lines.add("Downtime "+s.downtime()+" | Repair "+s.repair()+"/3 | Charge "+s.charge()+" | Reroutes "+s.reroutes()+"/1");
        lines.add("Grid "+(s.gridActiveRound()==0?"inactive":"from round "+s.gridActiveRound())+" | Courier "+s.courierWaypoint()+" | Revision "+s.revision());
        for(var b:s.batteries()) lines.add(b.id()+": "+b.holder()+" | charge "+b.charge());
        lines.add("Power by round: "+powers);
        lines.add("Allocation changes: "+s.allocationHistory());
    }
    public static String compact(ForkEngine.State state, boolean paused, boolean pending) {
        String power=state.allocation()==null?"Choose power":state.allocation()==ForkEngine.Power.CLINIC?"Clinic":"Workshop";
        String status=paused?"Paused":pending?"Thinking":state.complete()?"Complete":"Ready";
        return "FORK | "+state.mode()+" | R"+state.round()+"/6 | "+power+" | "+status;
    }
}
