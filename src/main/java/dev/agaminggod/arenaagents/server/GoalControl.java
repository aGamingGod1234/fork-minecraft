package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.server.GoalPayload.Operation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class GoalControl {
	public static final int MAX_GOAL_LENGTH = 4_096;

	private static final String ERROR_EMPTY = "GOAL_EMPTY";
	private static final String ERROR_NULL = "GOAL_NULL";
	private static final String ERROR_TOO_LONG = "GOAL_TOO_LONG";

	private final Map<UUID, GoalStatus> statuses = new ConcurrentHashMap<>();

	public static String normalize(String rawGoal) {
		if (rawGoal == null) {
			throw validationFailure(ERROR_NULL, "Goal must not be null");
		}
		if (rawGoal.length() > MAX_GOAL_LENGTH) {
			throw validationFailure(
					ERROR_TOO_LONG,
					"Goal must not exceed " + MAX_GOAL_LENGTH + " characters"
			);
		}

		StringBuilder normalized = new StringBuilder(rawGoal.length());
		boolean separatorPending = false;
		for (int offset = 0; offset < rawGoal.length();) {
			int codePoint = rawGoal.codePointAt(offset);
			offset += Character.charCount(codePoint);
			if (Character.isWhitespace(codePoint)) {
				separatorPending = normalized.length() > 0;
				continue;
			}
			if (separatorPending) {
				normalized.append(' ');
				separatorPending = false;
			}
			normalized.appendCodePoint(codePoint);
		}

		if (normalized.isEmpty()) {
			throw validationFailure(ERROR_EMPTY, "Goal must contain non-whitespace text");
		}
		return normalized.toString();
	}

	public static boolean mayControl(CommandSourceStack source) {
		Objects.requireNonNull(source, "source must not be null");
		return Commands.LEVEL_GAMEMASTERS.check(source.permissions());
	}

	public static boolean mayControl(ControlAuthority authority) {
		return Objects.requireNonNull(authority, "authority must not be null").mayControlArenaAgents();
	}

	public static <T> Delivery<T> sendToSelected(
			Collection<T> selectedTargets,
			Predicate<? super T> supportsPayload,
			BiConsumer<? super T, ? super GoalPayload> sender,
			GoalPayload payload
	) {
		Objects.requireNonNull(selectedTargets, "selectedTargets must not be null");
		Objects.requireNonNull(supportsPayload, "supportsPayload must not be null");
		Objects.requireNonNull(sender, "sender must not be null");
		Objects.requireNonNull(payload, "payload must not be null");

		List<T> delivered = new ArrayList<>();
		List<T> unsupported = new ArrayList<>();
		List<DeliveryFailure<T>> failed = new ArrayList<>();
		for (T target : new LinkedHashSet<>(selectedTargets)) {
			Objects.requireNonNull(target, "selectedTargets must not contain null");
			try {
				if (!supportsPayload.test(target)) {
					unsupported.add(target);
					continue;
				}
				sender.accept(target, payload);
				delivered.add(target);
			} catch (RuntimeException exception) {
				failed.add(new DeliveryFailure<>(target, exception));
			}
		}
		return new Delivery<>(delivered, unsupported, failed);
	}

	public void rememberGoal(UUID playerId, String normalizedGoal) {
		Objects.requireNonNull(playerId, "playerId must not be null");
		String checkedGoal = normalize(normalizedGoal);
		if (!checkedGoal.equals(normalizedGoal)) {
			throw validationFailure("GOAL_NOT_NORMALIZED", "Stored goal must already be normalized");
		}
		statuses.put(playerId, new GoalStatus(Operation.SET, checkedGoal));
	}

	public void rememberStop(UUID playerId) {
		statuses.put(Objects.requireNonNull(playerId, "playerId must not be null"), GoalStatus.stopped());
	}

	public GoalStatus status(UUID playerId) {
		return statuses.getOrDefault(
				Objects.requireNonNull(playerId, "playerId must not be null"),
				GoalStatus.unknown()
		);
	}

	private static IllegalArgumentException validationFailure(String code, String message) {
		return new IllegalArgumentException(code + ": " + message);
	}

	public record GoalStatus(Status status, String goal) {
		private GoalStatus(Operation operation, String goal) {
			this(operation == Operation.SET ? Status.ACTIVE : Status.STOPPED, goal);
		}

		public GoalStatus {
			status = Objects.requireNonNull(status, "status must not be null");
			goal = Objects.requireNonNull(goal, "goal must not be null");
			if (status != Status.ACTIVE && !goal.isEmpty()) {
				throw new IllegalArgumentException("Only active status may carry a goal");
			}
		}

		private static GoalStatus stopped() {
			return new GoalStatus(Status.STOPPED, "");
		}

		private static GoalStatus unknown() {
			return new GoalStatus(Status.UNKNOWN, "");
		}
	}

	public enum Status {
		ACTIVE,
		STOPPED,
		UNKNOWN
	}

	@FunctionalInterface
	public interface ControlAuthority {
		boolean mayControlArenaAgents();
	}

	public record Delivery<T>(List<T> delivered, List<T> unsupported, List<DeliveryFailure<T>> failed) {
		public Delivery {
			delivered = List.copyOf(delivered);
			unsupported = List.copyOf(unsupported);
			failed = List.copyOf(failed);
		}
	}

	public record DeliveryFailure<T>(T target, RuntimeException cause) {
		public DeliveryFailure {
			Objects.requireNonNull(target, "target must not be null");
			Objects.requireNonNull(cause, "cause must not be null");
		}
	}
}
