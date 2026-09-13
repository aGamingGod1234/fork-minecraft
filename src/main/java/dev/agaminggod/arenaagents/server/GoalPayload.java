package dev.agaminggod.arenaagents.server;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record GoalPayload(Operation operation, String goal) implements CustomPacketPayload {
	public static final Type<GoalPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "goal_control")
	);
	private static final StreamCodec<RegistryFriendlyByteBuf, Operation> OPERATION_CODEC = ByteBufCodecs.BOOL
			.map(stopped -> stopped ? Operation.STOP : Operation.SET, operation -> operation == Operation.STOP)
			.cast();
	public static final StreamCodec<RegistryFriendlyByteBuf, GoalPayload> CODEC = StreamCodec.composite(
			OPERATION_CODEC,
			GoalPayload::operation,
			ByteBufCodecs.stringUtf8(GoalControl.MAX_GOAL_LENGTH),
			GoalPayload::goal,
			GoalPayload::new
	);

	public GoalPayload {
		operation = Objects.requireNonNull(operation, "operation must not be null");
		goal = Objects.requireNonNull(goal, "goal must not be null");
		if (operation == Operation.STOP) {
			if (!goal.isEmpty()) {
				throw new IllegalArgumentException("STOP payload must not carry a goal");
			}
		} else {
			String normalized = GoalControl.normalize(goal);
			if (!normalized.equals(goal)) {
				throw new IllegalArgumentException("SET payload goal must already be normalized");
			}
		}
	}

	public static GoalPayload set(String rawGoal) {
		return new GoalPayload(Operation.SET, GoalControl.normalize(rawGoal));
	}

	public static GoalPayload stop() {
		return new GoalPayload(Operation.STOP, "");
	}

	@Override
	public Type<GoalPayload> type() {
		return TYPE;
	}

	public enum Operation {
		SET("set"),
		STOP("stop");

		private final String wireName;

		Operation(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}
	}
}
