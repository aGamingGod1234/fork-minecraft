package dev.agaminggod.arenaagents.server.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public final class BlockObservationOrdering {
	private static final Comparator<Candidate> ORDER = Comparator
			.comparingLong(Candidate::distanceSquared)
			.thenComparingInt(candidate -> Math.abs(candidate.y()))
			.thenComparingInt(Candidate::y)
			.thenComparingInt(Candidate::x)
			.thenComparingInt(Candidate::z)
			.thenComparing(Candidate::blockId);

	private BlockObservationOrdering() {
	}

	public static List<Candidate> select(List<Candidate> candidates, int maximumEntries, int maximumPerBlockType) {
		return select(candidates, maximumEntries, maximumPerBlockType, candidate -> true);
	}

	public static List<Candidate> select(
			List<Candidate> candidates,
			int maximumEntries,
			int maximumPerBlockType,
			Predicate<Candidate> admissible
	) {
		return selectOrdered(ordered(candidates), maximumEntries, maximumPerBlockType, admissible);
	}

	static List<Candidate> selectOrdered(
			List<Candidate> ordered,
			int maximumEntries,
			int maximumPerBlockType,
			Predicate<Candidate> admissible
	) {
		return selectOrderedWithVisibilityBudget(
				ordered, maximumEntries, maximumPerBlockType,
				Integer.MAX_VALUE, Integer.MAX_VALUE, admissible);
	}

	static List<Candidate> selectOrderedWithVisibilityBudget(
			List<Candidate> ordered,
			int maximumEntries,
			int maximumPerBlockType,
			int maximumVisibilityChecks,
			int maximumVisibilityChecksPerBlockType,
			Predicate<Candidate> admissible
	) {
		return selectOrderedWithVisibilityBudget(
				ordered, maximumEntries, maximumPerBlockType,
				maximumVisibilityChecks, maximumVisibilityChecksPerBlockType,
				candidate -> true, admissible);
	}

	static List<Candidate> selectOrderedWithVisibilityBudget(
			List<Candidate> ordered,
			int maximumEntries,
			int maximumPerBlockType,
			int maximumVisibilityChecks,
			int maximumVisibilityChecksPerBlockType,
			Predicate<Candidate> eligibleWithoutVisibilityCheck,
			Predicate<Candidate> admissible
	) {
		Objects.requireNonNull(ordered, "ordered must not be null");
		Objects.requireNonNull(eligibleWithoutVisibilityCheck, "eligibleWithoutVisibilityCheck must not be null");
		Objects.requireNonNull(admissible, "admissible must not be null");
		if (maximumEntries <= 0 || maximumPerBlockType <= 0
				|| maximumVisibilityChecks <= 0 || maximumVisibilityChecksPerBlockType <= 0) {
			throw new IllegalArgumentException("observation limits must be positive");
		}
		ArrayList<Candidate> selected = new ArrayList<>(Math.min(maximumEntries, ordered.size()));
		Map<String, Integer> counts = new HashMap<>();
		Map<String, Integer> visibilityChecksByType = new HashMap<>();
		int visibilityChecks = 0;
		for (Candidate candidate : ordered) {
			int count = counts.getOrDefault(candidate.blockId(), 0);
			if (count >= maximumPerBlockType) continue;
			if (!eligibleWithoutVisibilityCheck.test(candidate)) continue;
			if (visibilityChecks >= maximumVisibilityChecks) break;
			int typeChecks = visibilityChecksByType.getOrDefault(candidate.blockId(), 0);
			if (typeChecks >= maximumVisibilityChecksPerBlockType) continue;
			visibilityChecks++;
			visibilityChecksByType.put(candidate.blockId(), typeChecks + 1);
			if (!admissible.test(candidate)) continue;
			selected.add(candidate);
			counts.put(candidate.blockId(), count + 1);
			if (selected.size() >= maximumEntries) break;
		}
		return List.copyOf(selected);
	}

	public static List<Candidate> ordered(List<Candidate> candidates) {
		Objects.requireNonNull(candidates, "candidates must not be null");
		ArrayList<Candidate> ordered = new ArrayList<>(candidates);
		ordered.sort(ORDER);
		return List.copyOf(ordered);
	}

	public record Candidate(int x, int y, int z, String blockId) {
		public Candidate {
			blockId = Objects.requireNonNull(blockId, "blockId must not be null");
			if (blockId.isBlank()) throw new IllegalArgumentException("blockId must not be blank");
		}

		private long distanceSquared() {
			long dx = x;
			long dy = y;
			long dz = z;
			return dx * dx + dy * dy + dz * dz;
		}
	}
}
