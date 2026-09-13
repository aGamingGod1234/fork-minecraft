package dev.agaminggod.arenaagents.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class GoalControlVerification {
	private GoalControlVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyNormalizationAndLimits();
		assertions += verifyOperatorPolicy();
		assertions += verifyTargetedDelivery();
		assertions += verifyStatusTransitions();
		return assertions;
	}

	private static int verifyNormalizationAndLimits() {
		assertEquals("gather wood", GoalControl.normalize("  gather   wood "), "goal whitespace normalization");
		expectFailure(() -> GoalControl.normalize("x".repeat(GoalControl.MAX_GOAL_LENGTH + 1)), "GOAL_TOO_LONG");
		expectFailure(() -> GoalControl.normalize(" \t\n "), "GOAL_EMPTY");
		return 3;
	}

	private static int verifyOperatorPolicy() {
		GoalControl.ControlAuthority nonOperatorSource = () -> false;
		GoalControl.ControlAuthority operatorSource = () -> true;
		assertEquals(false, GoalControl.mayControl(nonOperatorSource), "non-operator cannot control agents");
		assertEquals(true, GoalControl.mayControl(operatorSource), "game master can control agents");
		return 2;
	}

	private static int verifyTargetedDelivery() {
		List<String> selected = List.of("agent-55", "agent-56");
		List<String> sent = new ArrayList<>();
		GoalControl.Delivery<String> delivery = GoalControl.sendToSelected(
				selected,
				target -> !"agent-56".equals(target),
				(target, payload) -> sent.add(target),
				GoalPayload.set("enter arena")
		);

		assertEquals(List.of("agent-55"), sent, "payload sent only to selected supported players");
		assertEquals(List.of("agent-55"), delivery.delivered(), "delivered players reported exactly");
		assertEquals(List.of("agent-56"), delivery.unsupported(), "unsupported selected players reported exactly");
		return 3;
	}

	private static int verifyStatusTransitions() {
		GoalControl control = new GoalControl();
		UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000055");
		assertEquals(GoalControl.Status.UNKNOWN, control.status(playerId).status(), "initial goal status");
		control.rememberGoal(playerId, "enter arena");
		assertEquals(GoalControl.Status.ACTIVE, control.status(playerId).status(), "active goal status");
		assertEquals("enter arena", control.status(playerId).goal(), "active goal text");
		control.rememberStop(playerId);
		assertEquals(GoalControl.Status.STOPPED, control.status(playerId).status(), "stopped goal status");
		return 4;
	}

	private static void expectFailure(Runnable action, String expectedCode) {
		try {
			action.run();
		} catch (IllegalArgumentException exception) {
			if (!exception.getMessage().contains(expectedCode)) {
				throw new AssertionError("expected error code " + expectedCode + " but got " + exception.getMessage());
			}
			return;
		}
		throw new AssertionError("expected failure " + expectedCode);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
