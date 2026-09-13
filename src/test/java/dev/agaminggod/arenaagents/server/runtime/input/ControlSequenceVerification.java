package dev.agaminggod.arenaagents.server.runtime.input;

import java.util.List;
import net.minecraft.world.InteractionHand;

public final class ControlSequenceVerification {
	private static final ControlSequence.Facts SAFE = facts(20, false);
	private ControlSequenceVerification() { }

	public static int verify() {
		AgentInputState drawing = state(0.0F, true, 0.0F);
		AgentInputState aiming = state(0.0F, true, 25.0F);
		ControlSequence sequence = new ControlSequence(List.of(frame(drawing, 2), frame(aiming, 3)), 5);
		check(sequence.next(SAFE).input().equals(drawing), "first tick uses the first authored frame");
		check(sequence.next(SAFE).input().equals(drawing), "frame lasts exactly its authored duration");
		check(sequence.next(SAFE).input().equals(aiming), "aim can change without releasing held use");
		sequence.next(SAFE);
		ControlSequence.Step fifth = sequence.next(SAFE);
		check(fifth.input().use() && fifth.elapsedTicks() == 5, "last authored tick still holds use");
		ControlSequence.Step completed = sequence.next(SAFE);
		check(completed.status() == ControlSequence.Status.COMPLETED && completed.input() == null, "normal completion releases input after its final tick");
		check(sequence.next(SAFE).equals(completed), "completed sequence cannot issue more input");

		ControlSequence.Branch modelEscape = new ControlSequence.Branch("health_below", 10, 1);
		ControlSequence branching = new ControlSequence(List.of(
				new ControlSequence.Frame(drawing, 20, List.of(modelEscape)), frame(state(-1.0F, false, 90.0F), 2)), 10);
		check(branching.next(SAFE).frameIndex() == 0, "unmatched condition preserves the model's first frame");
		ControlSequence.Step escape = branching.next(facts(4, false));
		check(escape.frameIndex() == 1 && escape.input().forward() == -1.0F, "observed damage selects only the model-authored branch");
		check(!escape.input().use(), "a model branch can explicitly release use");
		ControlSequence noPolicy = new ControlSequence(List.of(frame(drawing, 2)), 2);
		check(noPolicy.next(facts(1, true)).input().equals(drawing), "low health and fire do not invent a safety policy");

		ControlSequence stop = new ControlSequence(List.of(new ControlSequence.Frame(drawing, 5,
				List.of(new ControlSequence.Branch("on_fire", 1, 1)))), 10);
		ControlSequence.Step stopped = stop.next(facts(20, true));
		check(stopped.status() == ControlSequence.Status.BRANCH_STOPPED && stopped.elapsedTicks() == 0,
				"a model-authored stop branch can prevent the first physical input");

		ControlSequence loop = new ControlSequence(List.of(new ControlSequence.Frame(drawing, 5,
				List.of(new ControlSequence.Branch("on_ground", 1, 0)))), 6);
		for (int tick = 0; tick < 6; tick++) check(loop.next(SAFE).status() == ControlSequence.Status.RUNNING, "bounded loop emits an authored tick");
		ControlSequence.Step exhausted = loop.next(SAFE);
		check(exhausted.status() == ControlSequence.Status.BUDGET_EXHAUSTED && exhausted.elapsedTicks() == 6,
				"self-jumps cannot bypass the total tick budget");
		check(loop.next(SAFE).equals(exhausted), "exhausted sequence never restarts");

		reject(() -> new ControlSequence(List.of(new ControlSequence.Frame(drawing, 2,
				List.of(new ControlSequence.Branch("on_ground", 1, 2)))), 3), "branch outside the frame list is rejected");
		reject(() -> new ControlSequence.Branch("food_below", 21, 0), "invalid threshold is rejected at the boundary");
		reject(() -> new ControlSequence.Branch("nearest_hostile", 1, 0), "hidden target selection cannot be a condition");
		reject(() -> new ControlSequence.Frame(drawing, 201, List.of()), "oversized frame is rejected");
		reject(() -> new ControlSequence(List.of(frame(drawing, 1)), 2001), "oversized sequence budget is rejected");
		return 24;
	}

	private static ControlSequence.Frame frame(AgentInputState input, int ticks) { return new ControlSequence.Frame(input, ticks, List.of()); }
	private static AgentInputState state(float forward, boolean use, float yaw) {
		return new AgentInputState(forward, 0.0F, false, false, false, false, use, yaw, 0.0F, 0, InteractionHand.OFF_HAND);
	}
	private static ControlSequence.Facts facts(double health, boolean fire) {
		return new ControlSequence.Facts(health, 20, 300, fire, false, true, false, false, false);
	}
	private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
	private static void reject(Runnable action, String reason) {
		try { action.run(); } catch (IllegalArgumentException expected) { return; }
		throw new AssertionError(reason);
	}
}
