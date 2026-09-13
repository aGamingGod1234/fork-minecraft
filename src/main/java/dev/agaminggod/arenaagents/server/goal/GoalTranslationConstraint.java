package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;

/** Server-authored factual requirements that a translated predicate may not weaken. */
public record GoalTranslationConstraint(List<KillClause> killClauses, List<ItemClause> itemClauses) {
	private static final int MAX_LEAVES = 16;
	private static final GoalTranslationConstraint NONE = new GoalTranslationConstraint(List.of(), List.of());

	public GoalTranslationConstraint {
		killClauses = List.copyOf(Objects.requireNonNull(killClauses, "killClauses must not be null"));
		itemClauses = List.copyOf(Objects.requireNonNull(itemClauses, "itemClauses must not be null"));
		int leaves = 0;
		for (KillClause clause : killClauses) {
			Objects.requireNonNull(clause, "kill clause must not be null");
			for (KillAlternative alternative : clause.alternatives()) {
				leaves = Math.addExact(leaves, alternative.count());
			}
		}
		for (ItemClause clause : itemClauses) {
			Objects.requireNonNull(clause, "item clause must not be null");
			leaves = Math.addExact(leaves, clause.alternatives().size());
		}
		if (leaves > MAX_LEAVES) throw new IllegalArgumentException("translation constraints may require at most 16 leaves");
	}

	/** Compatibility constructor for constraints persisted before item quantities were captured. */
	public GoalTranslationConstraint(List<KillClause> killClauses) {
		this(killClauses, List.of());
	}

	public static GoalTranslationConstraint none() {
		return NONE;
	}

	/** A new translation request must offer every identifier needed by its factual constraints. */
	public void requireCatalog(List<String> candidateIds) {
		Set<String> offered = Set.copyOf(candidateIds);
		if (candidateIds.size() > 64 || offered.size() != candidateIds.size()) {
			throw new AgentDomainException("GOAL_TRANSLATION_CATALOG_TOO_BROAD", "Translation catalog must contain at most 64 unique identifiers");
		}
		boolean complete = killClauses.stream().flatMap(clause -> clause.alternatives().stream())
				.allMatch(alternative -> !alternative.entityTypes().isEmpty() && offered.containsAll(alternative.entityTypes()))
				&& itemClauses.stream().flatMap(clause -> clause.alternatives().stream())
						.allMatch(alternative -> !alternative.itemIds().isEmpty() && offered.containsAll(alternative.itemIds()));
		if (!complete) throw new AgentDomainException("GOAL_TRANSLATION_CATALOG_MISMATCH",
				"Translation catalog omits a required item or entity identifier; specify a narrower goal");
	}

	public void validate(GoalPredicate predicate) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		if (killClauses.isEmpty() && itemClauses.isEmpty()) return;
		for (EvidencePath path : evidencePaths(predicate)) {
			if (!satisfiesKillClauses(path.kills(), 0, new boolean[path.kills().size()])
					|| !satisfiesItemClauses(disjointInventoryEvidence(path.items()), 0)) {
				throw new AgentDomainException(
						"GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
						"Translated predicate does not prove every requested item or kill count; overlapping inventory groups may need a more specific predicate"
				);
			}
		}
	}

	private boolean satisfiesKillClauses(List<String> kills, int clauseIndex, boolean[] used) {
		if (clauseIndex == killClauses.size()) return true;
		for (KillAlternative alternative : killClauses.get(clauseIndex).alternatives()) {
			if (claimAlternative(kills, used, alternative, 0, 0, clauseIndex)) return true;
		}
		return false;
	}

	private boolean claimAlternative(
			List<String> kills,
			boolean[] used,
			KillAlternative alternative,
			int searchFrom,
			int claimed,
			int clauseIndex
	) {
		if (claimed == alternative.count()) return satisfiesKillClauses(kills, clauseIndex + 1, used);
		for (int index = searchFrom; index < kills.size(); index++) {
			if (used[index] || !alternative.entityTypes().contains(kills.get(index))) continue;
			used[index] = true;
			if (claimAlternative(kills, used, alternative, index + 1, claimed + 1, clauseIndex)) return true;
			used[index] = false;
		}
		return false;
	}

	private boolean satisfiesItemClauses(List<InventoryEvidence> available, int clauseIndex) {
		if (clauseIndex == itemClauses.size()) return true;
		for (ItemAlternative alternative : itemClauses.get(clauseIndex).alternatives()) {
			ArrayList<InventoryEvidence> remaining = new ArrayList<>(available);
			List<Integer> order = java.util.stream.IntStream.range(0, available.size()).boxed()
					.sorted(Comparator.comparingInt(index -> futureUses(available.get(index), clauseIndex + 1))).toList();
			int needed = alternative.count();
			for (int index : order) {
				InventoryEvidence evidence = remaining.get(index);
				if (!alternative.itemIds().containsAll(evidence.itemIds())) continue;
				int claimed = Math.min(needed, evidence.count());
				needed -= claimed;
				remaining.set(index, new InventoryEvidence(evidence.itemIds(), evidence.count() - claimed));
				if (needed == 0) break;
			}
			if (needed == 0 && satisfiesItemClauses(remaining, clauseIndex + 1)) return true;
		}
		return false;
	}

	private int futureUses(InventoryEvidence evidence, int start) {
		int uses = 0;
		for (int index = start; index < itemClauses.size(); index++) {
			if (itemClauses.get(index).alternatives().stream().anyMatch(value -> value.itemIds().containsAll(evidence.itemIds()))) uses++;
		}
		return uses;
	}

	/** Disjoint guarantees can add; overlapping guarantees cannot prove independent inventory counts. */
	private static List<InventoryEvidence> disjointInventoryEvidence(List<InventoryEvidence> evidence) {
		HashMap<Set<String>, Integer> strongest = new HashMap<>();
		evidence.forEach(value -> strongest.merge(value.itemIds(), value.count(), Math::max));
		List<InventoryEvidence> ordered = strongest.entrySet().stream()
				.map(entry -> new InventoryEvidence(entry.getKey(), entry.getValue()))
				.sorted(Comparator.comparingInt((InventoryEvidence value) -> value.itemIds().size())
						.thenComparing(Comparator.comparingInt(InventoryEvidence::count).reversed())
						.thenComparing(value -> String.join(",", new java.util.TreeSet<>(value.itemIds())))).toList();
		ArrayList<InventoryEvidence> independent = new ArrayList<>();
		HashSet<String> claimedIds = new HashSet<>();
		for (InventoryEvidence value : ordered) {
			if (value.itemIds().stream().anyMatch(claimedIds::contains)) continue;
			independent.add(value);
			claimedIds.addAll(value.itemIds());
		}
		return List.copyOf(independent);
	}

	private static List<EvidencePath> evidencePaths(GoalPredicate predicate) {
		return switch (predicate) {
			case GoalPredicate.EntityKilledByAgent value -> List.of(
					new EvidencePath(List.of(value.entityType()), List.of()));
			case GoalPredicate.InventoryContains value -> List.of(
					new EvidencePath(List.of(), List.of(new InventoryEvidence(Set.of(value.itemId()), value.count()))));
			case GoalPredicate.InventoryContainsAny value -> List.of(
					new EvidencePath(List.of(), List.of(new InventoryEvidence(Set.copyOf(value.itemIds()), value.count()))));
			case GoalPredicate.AllOf value -> allOfPaths(value.predicates());
			case GoalPredicate.AnyOf value -> value.predicates().stream()
					.flatMap(child -> evidencePaths(child).stream())
					.toList();
			default -> List.of(new EvidencePath(List.of(), List.of()));
		};
	}

	private static List<EvidencePath> allOfPaths(List<GoalPredicate> predicates) {
		List<EvidencePath> paths = List.of(new EvidencePath(List.of(), List.of()));
		for (GoalPredicate predicate : predicates) {
			ArrayList<EvidencePath> combined = new ArrayList<>();
			for (EvidencePath left : paths) {
				for (EvidencePath right : evidencePaths(predicate)) {
					ArrayList<String> kills = new ArrayList<>(left.kills().size() + right.kills().size());
					kills.addAll(left.kills());
					kills.addAll(right.kills());
					ArrayList<InventoryEvidence> items = new ArrayList<>(left.items());
					items.addAll(right.items());
					combined.add(new EvidencePath(List.copyOf(kills), List.copyOf(items)));
				}
			}
			paths = List.copyOf(combined);
		}
		return paths;
	}

	private record InventoryEvidence(Set<String> itemIds, int count) {}

	private record EvidencePath(List<String> kills, List<InventoryEvidence> items) {
	}

	public record KillClause(List<KillAlternative> alternatives) {
		public KillClause {
			alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives must not be null"));
			if (alternatives.isEmpty()) throw new IllegalArgumentException("kill clause alternatives must not be empty");
			alternatives.forEach(value -> Objects.requireNonNull(value, "kill alternative must not be null"));
		}
	}

	public record KillAlternative(List<String> entityTypes, int count) {
		public KillAlternative {
			entityTypes = List.copyOf(Objects.requireNonNull(entityTypes, "entityTypes must not be null"));
			if (entityTypes.size() > 64 || new HashSet<>(entityTypes).size() != entityTypes.size()) {
				throw new IllegalArgumentException("entityTypes must contain at most 64 unique identifiers");
			}
			for (String entityType : entityTypes) {
				if (entityType == null || !entityType.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
					throw new IllegalArgumentException("entityTypes must contain namespaced identifiers");
				}
			}
			if (count <= 0 || count > MAX_LEAVES) {
				throw new IllegalArgumentException("kill alternative count must be between 1 and 16");
			}
		}
	}

	public record ItemClause(List<ItemAlternative> alternatives) {
		public ItemClause {
			alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives must not be null"));
			if (alternatives.isEmpty()) throw new IllegalArgumentException("item clause alternatives must not be empty");
			alternatives.forEach(value -> Objects.requireNonNull(value, "item alternative must not be null"));
		}
	}

	public record ItemAlternative(List<String> itemIds, int count) {
		public ItemAlternative {
			itemIds = List.copyOf(Objects.requireNonNull(itemIds, "itemIds must not be null"));
			if (itemIds.size() > 64 || new HashSet<>(itemIds).size() != itemIds.size()) {
				throw new IllegalArgumentException("itemIds must contain at most 64 unique identifiers");
			}
			for (String itemId : itemIds) {
				if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
					throw new IllegalArgumentException("itemIds must contain namespaced identifiers");
				}
			}
			if (count <= 0) throw new IllegalArgumentException("item alternative count must be positive");
		}
	}
}
