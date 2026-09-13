package dev.agaminggod.arenaagents.scenario.presentation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record DirectorRecommendation(
		String kind,
		String targetId,
		String label,
		double x,
		double y,
		double z,
		long expiresAtTick,
		int priority
) {
	private static final Set<String> KINDS = Set.of("anchor", "participant");
	private static final Comparator<DirectorRecommendation> RANKING = Comparator
			.comparingInt(DirectorRecommendation::priority).reversed()
			.thenComparing(DirectorRecommendation::kind)
			.thenComparing(DirectorRecommendation::targetId)
			.thenComparing(DirectorRecommendation::label);

	public DirectorRecommendation {
		kind = ArenaSpectatorSnapshot.enumId(kind, "recommendation kind", KINDS);
		targetId = ArenaSpectatorSnapshot.id(targetId, "recommendation targetId", 64);
		label = ArenaSpectatorSnapshot.text(label, "recommendation label", 64);
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
				|| Math.abs(x) > 30_000_000.0D || Math.abs(z) > 30_000_000.0D
				|| y < -2_048.0D || y > 2_048.0D) {
			throw new IllegalArgumentException("recommendation coordinates must be finite and world-bounded");
		}
		if (expiresAtTick < 0L) throw new IllegalArgumentException("recommendation expiry must not be negative");
		if (priority < 0 || priority > 100) throw new IllegalArgumentException("recommendation priority is out of range");
	}

	public static Optional<DirectorRecommendation> select(
			long currentTick,
			List<DirectorRecommendation> candidates
	) {
		if (currentTick < 0L) throw new IllegalArgumentException("currentTick must not be negative");
		return Objects.requireNonNull(candidates, "candidates must not be null").stream()
				.map(candidate -> Objects.requireNonNull(candidate, "candidates must not contain null"))
				.filter(candidate -> candidate.currentAt(currentTick))
				.sorted(RANKING)
				.findFirst();
	}

	public boolean currentAt(long currentTick) {
		return currentTick >= 0L && currentTick <= expiresAtTick;
	}

	public DirectorRecommendation withExpiry(long revisedExpiry) {
		return new DirectorRecommendation(kind, targetId, label, x, y, z, revisedExpiry, priority);
	}

	JsonObject toJson() {
		JsonObject value = new JsonObject();
		value.addProperty("kind", kind);
		value.addProperty("targetId", targetId);
		value.addProperty("label", label);
		value.addProperty("x", ArenaSpectatorSnapshot.normalized(x));
		value.addProperty("y", ArenaSpectatorSnapshot.normalized(y));
		value.addProperty("z", ArenaSpectatorSnapshot.normalized(z));
		value.addProperty("expiresAtTick", expiresAtTick);
		value.addProperty("priority", priority);
		return value;
	}

	static DirectorRecommendation fromJson(JsonElement element) {
		JsonObject value = ArenaSpectatorSnapshot.object(element, "recommendation");
		ArenaSpectatorSnapshot.requireExactKeys(
				value,
				Set.of("kind", "targetId", "label", "x", "y", "z", "expiresAtTick", "priority"),
				"recommendation"
		);
		return new DirectorRecommendation(
				ArenaSpectatorSnapshot.string(value, "kind"),
				ArenaSpectatorSnapshot.string(value, "targetId"),
				ArenaSpectatorSnapshot.string(value, "label"),
				ArenaSpectatorSnapshot.finiteDouble(value, "x"),
				ArenaSpectatorSnapshot.finiteDouble(value, "y"),
				ArenaSpectatorSnapshot.finiteDouble(value, "z"),
				ArenaSpectatorSnapshot.exactLong(value, "expiresAtTick"),
				ArenaSpectatorSnapshot.exactInt(value, "priority")
		);
	}
}
