package dev.agaminggod.arenaagents.server;

import java.util.Locale;
import java.util.Objects;

/** A small, persisted action that runs after a skit step reaches its trigger time. */
public record SkitAction(
		Type type,
		int durationTicks,
		float forward,
		float strafe,
		boolean sprint,
		boolean sneak,
		String itemId
) {
	public static final int MAX_DURATION_TICKS = 20 * 60 * 60;

	public SkitAction {
		Objects.requireNonNull(type, "type must not be null");
		if (durationTicks < 0 || durationTicks > MAX_DURATION_TICKS) {
			throw new IllegalArgumentException("durationTicks must be between 0 and " + MAX_DURATION_TICKS);
		}
		if (!Float.isFinite(forward) || forward < -1.0F || forward > 1.0F
				|| !Float.isFinite(strafe) || strafe < -1.0F || strafe > 1.0F) {
			throw new IllegalArgumentException("movement values must be finite and in [-1, 1]");
		}
		itemId = itemId == null ? "" : itemId.strip().toLowerCase(Locale.ROOT);
		if (itemId.length() > 128 || (!itemId.isEmpty() && !itemId.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))) {
			throw new IllegalArgumentException("itemId must be a namespaced item identifier");
		}
		if (type == Type.EQUIP && itemId.isEmpty()) throw new IllegalArgumentException("EQUIP requires an itemId");
		if (type != Type.EQUIP && !itemId.isEmpty()) throw new IllegalArgumentException("itemId is only valid for EQUIP");
		if (type != Type.MOVE && (forward != 0.0F || strafe != 0.0F || sprint)) {
			throw new IllegalArgumentException("movement values are only valid for MOVE");
		}
		if (type == Type.MOVE && durationTicks == 0) throw new IllegalArgumentException("MOVE requires a positive duration");
	}

	public static SkitAction move(int durationTicks) {
		return move(durationTicks, 1.0F, 0.0F, false);
	}

	public static SkitAction move(int durationTicks, float forward, float strafe, boolean sprint) {
		return new SkitAction(Type.MOVE, durationTicks, forward, strafe, sprint, false, "");
	}

	public static SkitAction waitTicks(int durationTicks) {
		return new SkitAction(Type.WAIT, durationTicks, 0.0F, 0.0F, false, false, "");
	}

	public static SkitAction jump() {
		return new SkitAction(Type.JUMP, 1, 0.0F, 0.0F, false, false, "");
	}

	public static SkitAction equip(String itemId) {
		return new SkitAction(Type.EQUIP, 1, 0.0F, 0.0F, false, false, itemId);
	}

	public static SkitAction use(int durationTicks) {
		return new SkitAction(Type.USE, durationTicks, 0.0F, 0.0F, false, false, "");
	}

	public static SkitAction swing() {
		return new SkitAction(Type.SWING, 1, 0.0F, 0.0F, false, false, "");
	}

	public static SkitAction emote(int durationTicks, boolean sneak) {
		return new SkitAction(Type.EMOTE, durationTicks, 0.0F, 0.0F, false, sneak, "");
	}

	public enum Type {
		MOVE,
		WAIT,
		JUMP,
		EQUIP,
		USE,
		SWING,
		EMOTE
	}
}
