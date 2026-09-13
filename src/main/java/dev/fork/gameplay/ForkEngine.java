package dev.fork.gameplay;

import dev.fork.core.ForkContract;
import java.util.*;
import java.util.function.LongSupplier;

/** All scored changes pass through one atomic, server-owned batch commit. */
public final class ForkEngine {
    private static final String PROCESS_NONCE = Long.toUnsignedString(System.currentTimeMillis(), 36) + "-" + Long.toUnsignedString(System.nanoTime(), 36);
    private static final java.util.concurrent.atomic.AtomicLong BRANCH_IDS = new java.util.concurrent.atomic.AtomicLong();
    public enum Mode { LIVE, FIXTURE, RECORDED }
    public enum Power { CLINIC, WORKSHOP }
    public enum Role { MEDIC, ENGINEER, COURIER }
    public record Battery(String id, String holder, int charge) {}
    public record State(String branch, long epoch, int round, long revision, Mode mode,
            Power allocation, int reroutes, int repair, int downtime, int gridActiveRound,
            List<Battery> batteries, String courierWaypoint, String service, List<String> allocationHistory) {
        public State { batteries = List.copyOf(batteries); allocationHistory = List.copyOf(allocationHistory); }
        public int charge() { return batteries.stream().mapToInt(Battery::charge).sum(); }
        public boolean complete() { return round == ForkContract.ROUNDS; }
    }
    public record Ticket(String branch, long epoch, int round, long baseRevision, String requestId) {}
    public record Intent(Role role, String actionId, String action) {}
    public record Batch(Ticket ticket, List<Intent> intents) {
        public Batch { intents = List.copyOf(intents); }
    }
    public record Effect(Role role, String proposal, String validation, String effect) {}
    public record Receipt(Ticket ticket, State state, List<Effect> effects) {
        public Receipt { effects = List.copyOf(effects); }
    }
    public record Archive(State state, List<Receipt> receipts) {
        public Archive { receipts = List.copyOf(receipts); }
    }
    private record Committed(Batch batch, Receipt receipt) {}
    private final LongSupplier nanos;
    private final Mode mode;
    private State state;
    private Ticket pending;
    private long deadline;
    private long epoch;
    private long sequence;
    private boolean paused;
    private int liveAttempts;
    private final Map<String, Committed> receipts = new LinkedHashMap<>();
    private final List<Archive> archives = new ArrayList<>();
    private final Set<String> actionIds = new HashSet<>();

    public ForkEngine(Mode mode) { this(mode, System::nanoTime); }
    public ForkEngine(Mode mode, LongSupplier nanos) {
        this(mode, nanos, 0);
    }
    public ForkEngine(Mode mode, LongSupplier nanos, long initialEpoch) {
        this.mode = Objects.requireNonNull(mode); this.nanos = Objects.requireNonNull(nanos);
        if (initialEpoch < 0) throw new IllegalArgumentException("Invalid epoch");
        epoch = initialEpoch;
        state = initial();
    }
    private State initial() {
        return new State(PROCESS_NONCE + "-" + BRANCH_IDS.incrementAndGet(), epoch, 0, 0, mode, null, 0, 0, 0, 0,
                List.of(new Battery("B0", "clinic.socket0", 1), new Battery("B1", "courier", 1)),
                "courier", "", List.of());
    }
    public synchronized State state() { return state; }
    public synchronized Ticket pending() { expire(); return pending; }
    public synchronized boolean paused() { return paused; }
    public synchronized List<Archive> archives() { return List.copyOf(archives); }
    public synchronized List<Receipt> receipts() { return receipts.values().stream().map(Committed::receipt).toList(); }
    public synchronized void power(Power power) {
        requireIdle(); Objects.requireNonNull(power);
        if (state.complete()) throw new IllegalStateException("Run complete");
        if (state.allocation == power) return;
        if (state.round > 0 && state.reroutes != 0) throw new IllegalStateException("One reroute already used");
        var history = new ArrayList<>(state.allocationHistory);
        history.add("before round " + (state.round + 1) + ": " + power);
        state = new State(state.branch, epoch, state.round, state.revision, mode, power,
                state.round == 0 ? 0 : 1, state.repair, state.downtime, state.gridActiveRound,
                state.batteries, state.courierWaypoint, state.service, history);
    }
    public synchronized Ticket begin() {
        return begin(false);
    }
    public synchronized Ticket begin(boolean explicitRetry) {
        requireIdle();
        if (mode == Mode.RECORDED) throw new IllegalStateException("Recorded mode cannot call or advance");
        if (state.complete() || state.allocation == null) throw new IllegalStateException("Choose power or rewind");
        if(mode == Mode.LIVE) {
            if(liveAttempts>0 && (!explicitRetry || liveAttempts>=2)) throw new IllegalStateException("One explicit retry only; rewind after two attempts");
            if(explicitRetry && liveAttempts==0) throw new IllegalStateException("No failed attempt to retry");
            liveAttempts++;
        }
        pending = new Ticket(state.branch, epoch, state.round + 1, state.revision, "request-" + (++sequence));
        deadline = nanos.getAsLong() + ForkContract.TOTAL_ATTEMPT_SECONDS * 1_000_000_000L;
        return pending;
    }
    public synchronized boolean cancel() { boolean had = pending != null; pending = null; return had; }
    public synchronized void pause() { cancel(); paused = true; }
    private void expire() { if (pending != null && nanos.getAsLong() >= deadline) pending = null; }
    private void requireIdle() {
        expire();
        if (paused || pending != null) throw new IllegalStateException("Paused or round pending");
    }
    public synchronized Receipt receipt(Ticket ticket) {
        if (!Objects.equals(ticket.branch, state.branch) || ticket.epoch != epoch) return null;
        var found = receipts.get(ticket.requestId);
        return found != null && found.receipt.ticket.equals(ticket) ? found.receipt : null;
    }
    public synchronized Receipt commit(Batch batch) {
        Objects.requireNonNull(batch);
        Ticket t = Objects.requireNonNull(batch.ticket);
        if (!Objects.equals(t.branch, state.branch) || t.epoch != epoch) throw new IllegalArgumentException("Stale branch/epoch");
        var previous = receipts.get(t.requestId);
        if (previous != null) {
            if (!previous.batch.equals(batch)) throw new IllegalArgumentException("Request ID reused with different batch");
            return previous.receipt;
        }
        expire();
        if (paused || !t.equals(pending)) throw new IllegalArgumentException("No matching pending request");
        if (batch.intents.size() != 3) throw new IllegalArgumentException("Exactly three role intents required");
        var byRole = new EnumMap<Role, Intent>(Role.class);
        var newIds = new HashSet<String>();
        for (Intent intent : batch.intents) {
            if (intent == null || intent.role == null || intent.action == null || intent.action.isBlank()
                    || intent.action.length() > 64 || intent.actionId == null || intent.actionId.isBlank()
                    || intent.actionId.length() > 128 || !newIds.add(intent.actionId) || actionIds.contains(intent.actionId)
                    || byRole.put(intent.role, intent) != null) throw new IllegalArgumentException("Malformed or duplicate role/action ID");
        }
        var batteries = new ArrayList<>(state.batteries);
        boolean delivery = byRole.get(Role.COURIER).action.equals("deliver_spare") && batteries.get(1).holder.equals("courier");
        if (delivery) batteries.set(1, new Battery("B1", "clinic.socket1", batteries.get(1).charge));
        boolean grid = state.gridActiveRound > 0 && t.round >= state.gridActiveRound;
        boolean clinic = grid || state.allocation == Power.CLINIC;
        boolean workshop = grid || state.allocation == Power.WORKSHOP;
        if (!clinic) for (int i = 0; i < batteries.size(); i++) {
            Battery b = batteries.get(i);
            if (b.holder.startsWith("clinic.socket") && b.charge > 0) {
                batteries.set(i, new Battery(b.id, b.holder, b.charge - 1)); clinic = true; break;
            }
        }
        boolean repair = byRole.get(Role.ENGINEER).action.equals("repair") && workshop && state.repair < 3;
        int repairs = state.repair + (repair ? 1 : 0);
        int gridRound = state.gridActiveRound == 0 && repairs == 3 ? t.round + 1 : state.gridActiveRound;
        var effects = new ArrayList<Effect>();
        for (Role role : Role.values()) {
            String a = byRole.get(role).action;
            String allowed = switch (role) { case MEDIC -> "request_spare"; case ENGINEER -> "repair"; case COURIER -> "deliver_spare"; };
            boolean legal = a.equals("wait") || a.equals(allowed);
            String effect = switch (role) {
                case MEDIC -> a.equals("request_spare") ? "spare requested (nonbinding)" : "wait";
                case ENGINEER -> repair ? "repair +1" : "wait; no powered repair";
                case COURIER -> delivery ? "B1 holder=clinic.socket1; waypoint=clinic" : "wait; no delivery";
            };
            effects.add(new Effect(role, a, legal ? "valid" : "rejected as wait", effect));
        }
        State next = new State(state.branch, epoch, t.round, state.revision + 1, mode, state.allocation,
                state.reroutes, repairs, state.downtime + (clinic ? 0 : 1), gridRound, batteries,
                delivery ? "clinic" : state.courierWaypoint, state.service + (clinic ? "1" : "0"), state.allocationHistory);
        Receipt receipt = new Receipt(t, next, effects);
        receipts.put(t.requestId, new Committed(batch, receipt)); actionIds.addAll(newIds);
        state = next; pending = null; liveAttempts = 0;
        return receipt;
    }
    /** Detaches old work before the adapter touches world cells. Epoch is never checkpointed. */
    public synchronized long beginRewind() {
        cancel(); paused = true; epoch++;
        archives.add(new Archive(state, receipts()));
        receipts.clear(); actionIds.clear();
        return epoch;
    }
    public synchronized void finishRewind(long expectedEpoch, boolean verified) {
        if (!paused || epoch != expectedEpoch) throw new IllegalStateException("Stale restore");
        if (!verified) throw new IllegalStateException("Restore verification failed; remains paused");
        state = initial(); paused = false; liveAttempts = 0;
    }
    public synchronized Batch fixture(Ticket t) {
        if (mode != Mode.FIXTURE) throw new IllegalStateException("Fixture requested outside Fixture mode");
        return new Batch(t, List.of(new Intent(Role.MEDIC, t.requestId + "-medic", "request_spare"),
                new Intent(Role.ENGINEER, t.requestId + "-engineer", "repair"),
                new Intent(Role.COURIER, t.requestId + "-courier", t.round == 2 ? "deliver_spare" : "wait")));
    }
}
