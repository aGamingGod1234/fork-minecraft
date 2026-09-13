package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentValidators;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;

public final class GoalCompiler {
	private static final int MAX_COMPOUND_LEAVES = 16;
	private static final int MAX_TRANSLATION_CANDIDATES = 64;
	private static final List<String> SMALL_COUNTS = List.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen");
	private static final List<String> TENS = List.of("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety");
	private static final String COUNT = "(?:[+-]?\\d\\S*|" + String.join("|", SMALL_COUNTS)
			+ "|(?:" + String.join("|", TENS) + ")(?:[ -](?:one|two|three|four|five|six|seven|eight|nine))?)";
	private static final Pattern POSITION = Pattern.compile(
			"^(?:go|move|travel|get)(?: to)?(?: coordinates?)?\\s+"
					+ "(?:x\\s*=\\s*)?(-?\\d+)\\s*,?\\s*"
					+ "(?:y\\s*=\\s*)?(-?\\d+)\\s*,?\\s*"
					+ "(?:z\\s*=\\s*)?(-?\\d+)"
					+ "(?:\\s+and\\s+stop(?:\\s+there)?)?$"
	);
	private static final Pattern ADVANCEMENT = Pattern.compile("^(?:complete|get|earn) (?:the )?advancement ([a-z0-9_.-]+:[a-z0-9_./-]+)$");
	private static final Pattern ADVANCEMENT_NAME_PREFIX = Pattern.compile("^(?:complete|get|earn) (?:the )?advancement (.+)$");
	private static final Pattern ADVANCEMENT_NAME_SUFFIX = Pattern.compile("^(?:complete|get|earn) (?:the )?(.+?) advancement$");
	private static final Pattern KILL = Pattern.compile("^(?:kill|slay|defeat) (?:(?:the|a|an) )?(.+)$");
	private static final Pattern KILL_COUNT = Pattern.compile("^(?:at least\\s+)?(" + COUNT + ")\\s+(.+)$");
	private static final Pattern BEAT_GAME = Pattern.compile("^beat (?:the )?game$");
	private static final Pattern BEAT_GAME_TRANSLATION = Pattern.compile(
			"^(?:(?:go(?: and)?\\s+)?beat (?:the )?game"
					+ "(?:\\s+and\\s+(?:kill|slay|defeat) (?:(?:the|a|an) )?(?:ender|enemy) dragon)?"
					+ "|(?:go(?: and)?\\s+)?(?:kill|slay|defeat) (?:(?:the|a|an) )?(?:ender|enemy) dragon"
					+ "\\s+and\\s+beat (?:the )?game)$"
	);
	private static final Pattern ITEM = Pattern.compile("^(get|obtain|collect|bring|gather|acquire|fetch|craft|make) (?:me )?(?:(?:at least )?(" + COUNT + ") )?(?:(?:a|an|some) )?(.+?)(?: for me)?$");
	private static final Pattern ITEM_ALTERNATIVE_COUNT = Pattern.compile("^(?:at least\\s+)?(" + COUNT + ")\\s+(.+)$");
	private static final Pattern REFERENCED_ITEM_CLAUSE = Pattern.compile("^(?:keep|retain|hold|carry)\\s+(?:it|them|these|those)(?:\\s.*)?$");
	private static final Pattern BLOCK = Pattern.compile("^(build|construct|place|put|set|mine|break|destroy) (?:with |using |from )?(?:(?:a|an|some|the) )?(.+?)(?: for me)?$");
	private static final Pattern BLOCK_LOCATION_SUFFIX = Pattern.compile(
			"\\s+(?:at|on)(?: coordinates?)?\\s+"
					+ "(?:x\\s*=\\s*)?(-?\\d+)\\s*,?\\s*"
					+ "(?:y\\s*=\\s*)?(-?\\d+)\\s*,?\\s*"
					+ "(?:z\\s*=\\s*)?(-?\\d+)$"
	);
	private static final Pattern SURVIVE = Pattern.compile("^survive\\b.*$");
	private static final Pattern SUBJECTIVE = Pattern.compile("\\b(?:good|better|best|strong|stronger|useful|decent|nice|appropriate|some kind of)\\b");
	private static final Pattern GOAL_LEAD = Pattern.compile("^(?:get|obtain|collect|bring|craft|make|go|move|travel|come|kill|slay|defeat|build|mine|find|gather|chop|break|place|beat|survive|explore|follow|protect|farm|smelt|cook|trade|complete|earn)\\b");
	private static final Pattern LIVE_STEERING = Pattern.compile(
			"^(?:continue|keep going|watch out|try another route|retry|resume|come here|come to me|come with me|come|over here|this way|follow me|follow us|follow|stay close|stay with me|wait|stop|hold on|go there|behind me|to me)$"
	);

	public GoalCompilation compile(String request, RegistryAccess registries, long createdAtTick) {
		return compile(request, registries, createdAtTick, ignored -> true, GoalPredicate.DEFAULT_DIMENSION);
	}

	public GoalCompilation compile(String request, RegistryAccess registries, long createdAtTick, String dimensionId) {
		return compile(request, registries, createdAtTick, ignored -> true, dimensionId);
	}

	public GoalCompilation compile(
			String request,
			RegistryAccess registries,
			long createdAtTick,
			Predicate<String> advancementExists
	) {
		return compile(request, registries, createdAtTick, advancementExists, GoalPredicate.DEFAULT_DIMENSION);
	}

	public GoalCompilation compile(
			String request,
			RegistryAccess registries,
			long createdAtTick,
			Predicate<String> advancementExists,
			ServerLevel sourceLevel
	) {
		Objects.requireNonNull(sourceLevel, "sourceLevel must not be null");
		GoalCompilation compilation = compile(
				request, registries, createdAtTick, advancementExists,
				sourceLevel.dimension().identifier().toString()
		);
		if (compilation.kind() != GoalCompilation.Kind.ACCEPTED) return compilation;
		try {
			GoalPredicateWorldValidator.validate(sourceLevel, compilation.acceptedSpec().orElseThrow().completion());
			return compilation;
		} catch (AgentDomainException exception) {
			return GoalCompilation.rejected(exception.getMessage());
		}
	}

	public GoalCompilation compile(
			String request,
			RegistryAccess registries,
			long createdAtTick,
			Predicate<String> advancementExists,
			String dimensionId
	) {
		Objects.requireNonNull(registries, "registries must not be null");
		Objects.requireNonNull(advancementExists, "advancementExists must not be null");
		Objects.requireNonNull(dimensionId, "dimensionId must not be null");
		String original = AgentValidators.normalizePrompt(request);
		String command = stripTrailingPunctuation(stripPoliteness(original.toLowerCase(Locale.ROOT)));
		TranslationLeafBudget translationBudget = candidateTranslationBudget(command, compoundClauses(command));
		if (!translationBudget.valid()) return GoalCompilation.rejected(translationBudget.rejection());
		if (SUBJECTIVE.matcher(command).find()) {
			return GoalCompilation.needsTranslation("I need you to clarify the exact Minecraft result you want.");
		}

		Matcher position = POSITION.matcher(command);
		if (position.matches()) {
			try {
				GoalPredicate predicate = new GoalPredicate.PositionWithin(
						dimensionId,
						Integer.parseInt(position.group(1)), Integer.parseInt(position.group(2)), Integer.parseInt(position.group(3)),
						1.0D, 20
				);
				return accepted(original, predicate, createdAtTick, "Goal set: reach the requested coordinates.");
			} catch (NumberFormatException | AgentDomainException exception) {
				return GoalCompilation.rejected("Those coordinates are outside Minecraft's supported range.");
			}
		}

		Matcher advancement = ADVANCEMENT.matcher(command);
		if (advancement.matches()) {
			if (!advancementExists.test(advancement.group(1))) {
				return GoalCompilation.rejected("That advancement ID does not exist on this server.");
			}
			return accepted(original, new GoalPredicate.AdvancementGranted(advancement.group(1)), createdAtTick,
					"Goal set: earn " + advancement.group(1) + ".");
		}

		Matcher kill = KILL.matcher(command);
		if (BEAT_GAME.matcher(command).matches()) {
			return accepted(original, new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true), createdAtTick,
					"Goal set: defeat minecraft:ender_dragon.");
		}
		if (kill.matches()) {
			TranslationLeafBudget budget = killLeafBudget(kill.group(1));
			if (!budget.valid()) return GoalCompilation.rejected(budget.rejection());
			List<String> matches = matchEntities(kill.group(1), registries);
			if (matches.size() == 1) {
				String entityId = matches.getFirst();
				return accepted(original, new GoalPredicate.EntityKilledByAgent(entityId, true), createdAtTick,
						"Goal set: defeat " + entityId + ".");
			}
		}

		Matcher item = ITEM.matcher(command);
		if (item.matches()) {
			if (isCraftingVerb(item.group(1))) return craftingNeedsTranslation();
			int count;
			try {
				count = item.group(2) == null ? 1 : parseCount(item.group(2));
			} catch (NumberFormatException exception) {
				return GoalCompilation.rejected("The requested item count is outside the supported range.");
			}
			if (count <= 0) return GoalCompilation.rejected("The requested item count must be positive.");
			List<String> matches = matchItems(item.group(3), registries);
			if (matches.size() == 1) {
				String itemId = matches.getFirst();
				if (GoalInventoryCapacity.exceeds(itemId, count, registries)) return unrepresentableItemCount();
				return accepted(original, new GoalPredicate.InventoryContains(itemId, count), createdAtTick,
						"Goal set: obtain " + itemId + " x" + count + ".");
			}
		}
		Matcher block = BLOCK.matcher(command);
		if (block.matches()) {
			Matcher location = BLOCK_LOCATION_SUFFIX.matcher(normalizedTarget(block.group(2)));
			if (isDestructiveBlockVerb(block.group(1)) && location.find()) {
				try {
					GoalPredicate predicate = new GoalPredicate.BlockMatches(
							dimensionId,
							Integer.parseInt(location.group(1)),
							Integer.parseInt(location.group(2)),
							Integer.parseInt(location.group(3)),
							"minecraft:air",
							Map.of()
					);
					return accepted(original, predicate, createdAtTick, "Goal set: clear the requested block position.");
				} catch (NumberFormatException | AgentDomainException exception) {
					return GoalCompilation.rejected("Those block coordinates are outside Minecraft's supported range.");
				}
			}
			List<String> sourceBlocks = relatedBlocks(block.group(2), registries);
			if (sourceBlocks.isEmpty()) {
				return GoalCompilation.needsTranslation("I could not identify the exact Minecraft block for that request.");
			}
		}

		GoalCompilation compound = compileCompound(original, command, registries, createdAtTick);
		if (compound != null) return compound;
		if (kill.matches()) {
			List<String> matches = matchEntities(kill.group(1), registries);
			return GoalCompilation.needsTranslation(matches.isEmpty()
					? "I could not identify the exact Minecraft entity to defeat."
					: "More than one Minecraft entity matches that request.");
		}
		if (item.matches()) {
			List<String> matches = matchItems(item.group(3), registries);
			return GoalCompilation.needsTranslation(matches.isEmpty()
					? "I could not identify the exact Minecraft item you want."
					: "More than one Minecraft item matches that request.");
		}

		return GoalCompilation.needsTranslation("I need an exact result before I can start this goal.");
	}

	public static boolean looksLikeGoalRequest(String request) {
		String normalized = normalizedCommand(request);
		if (LIVE_STEERING.matcher(normalized).matches()) return false;
		return GOAL_LEAD.matcher(normalized).find();
	}

	/** Live steering such as "come here" / "follow me" must reach the coordinator, not start a new goal. */
	public static boolean isLiveSteeringRequest(String request) {
		return LIVE_STEERING.matcher(normalizedCommand(request)).matches();
	}

	/**
	 * Returns true for the one spoken compound goal whose wording maps to a single,
	 * server-constrained terminal result without a player choosing among candidates.
	 */
	public static boolean isDeterministicTranslation(String request) {
		String command = normalizedCommand(request);
		return BEAT_GAME_TRANSLATION.matcher(command).matches() && !BEAT_GAME.matcher(command).matches();
	}

	/**
	 * Speech is only consumed as a goal change when the agent is idle, or the player already
	 * opted into replace/queue through {@code /agent goal}. Busy agents keep working and still hear the line.
	 */
	public static boolean consumePlayerSpeechAsGoal(boolean hasActiveGoal, String text, boolean replaceOrQueueOptIn) {
		if (replaceOrQueueOptIn) return looksLikeGoalRequest(text) || isLiveSteeringRequest(text);
		if (hasActiveGoal) return false;
		if (isLiveSteeringRequest(text)) return false;
		return looksLikeGoalRequest(text);
	}

	private static String normalizedCommand(String request) {
		return stripTrailingPunctuation(stripPoliteness(AgentValidators.normalizePrompt(request).toLowerCase(Locale.ROOT)));
	}

	public List<String> candidateIdsFor(String request, RegistryAccess registries) {
		return candidateIdsFor(request, registries, Map.of());
	}

	public GoalTranslationConstraint translationConstraintFor(String request, RegistryAccess registries) {
		Objects.requireNonNull(registries, "registries must not be null");
		String command = stripTrailingPunctuation(stripPoliteness(
				AgentValidators.normalizePrompt(request).toLowerCase(Locale.ROOT)));
		List<GoalClause> clauses = compoundClauses(command);
		TranslationLeafBudget budget = candidateTranslationBudget(command, clauses);
		if (!budget.valid()) {
			throw new AgentDomainException("GOAL_TRANSLATION_CONSTRAINT_MISMATCH", budget.rejection());
		}
		if (BEAT_GAME_TRANSLATION.matcher(command).matches() && !BEAT_GAME.matcher(command).matches()) {
			return new GoalTranslationConstraint(
					List.of(new GoalTranslationConstraint.KillClause(List.of(
							new GoalTranslationConstraint.KillAlternative(List.of("minecraft:ender_dragon"), 1)
					))),
					List.of()
			);
		}
		ArrayList<GoalTranslationConstraint.KillClause> killClauses = new ArrayList<>();
		ArrayList<GoalTranslationConstraint.ItemClause> itemClauses = new ArrayList<>();
		Set<String> catalog = Set.copyOf(candidateIdsFor(request, registries));
		if (clauses.size() > 1) {
			for (GoalClause clause : clauses) {
				if (clause.kind() == ClauseKind.KILL) {
					killClauses.add(killConstraintFor(clause.target(), registries, catalog));
				} else if (clause.kind() == ClauseKind.ITEM) {
					itemClauses.add(itemConstraintFor(clause.target(), clause.count(), registries, catalog));
				}
			}
		} else {
			Matcher kill = KILL.matcher(command);
			if (kill.matches() && !BEAT_GAME.matcher(command).matches()) {
				killClauses.add(killConstraintFor(kill.group(1), registries, catalog));
			}
			Matcher item = ITEM.matcher(command);
			if (item.matches()) {
				itemClauses.add(itemConstraintFor(
						item.group(3), item.group(2) == null ? 1 : parseCount(item.group(2)), registries, catalog));
			}
		}
		return killClauses.isEmpty() && itemClauses.isEmpty()
				? GoalTranslationConstraint.none()
				: new GoalTranslationConstraint(killClauses, itemClauses);
	}

	/** Removes redundant subjective confirmation from Minecraft's objective beat-the-game terminal result. */
	public GoalPredicate normalizeTranslatedPredicate(String request, GoalPredicate predicate) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		String command = stripTrailingPunctuation(stripPoliteness(
				AgentValidators.normalizePrompt(request).toLowerCase(Locale.ROOT)));
		if (!BEAT_GAME_TRANSLATION.matcher(command).matches() || BEAT_GAME.matcher(command).matches()) return predicate;
		GoalPredicate normalized = withoutOperatorConfirmation(predicate);
		return normalized == null ? predicate : normalized;
	}

	private static GoalPredicate withoutOperatorConfirmation(GoalPredicate predicate) {
		return switch (predicate) {
			case GoalPredicate.OperatorConfirmed ignored -> null;
			case GoalPredicate.AllOf value -> normalizedCompound(value.predicates(), true);
			case GoalPredicate.AnyOf value -> normalizedCompound(value.predicates(), false);
			default -> predicate;
		};
	}

	private static GoalPredicate normalizedCompound(List<GoalPredicate> predicates, boolean allOf) {
		List<GoalPredicate> normalized = predicates.stream()
				.map(GoalCompiler::withoutOperatorConfirmation)
				.filter(Objects::nonNull)
				.toList();
		if (normalized.isEmpty()) return null;
		if (normalized.size() == 1) return normalized.getFirst();
		return allOf ? new GoalPredicate.AllOf(normalized) : new GoalPredicate.AnyOf(normalized);
	}

	public List<String> candidateIdsFor(
			String request,
			RegistryAccess registries,
			Map<String, String> liveAdvancementTitles
	) {
		Objects.requireNonNull(registries, "registries must not be null");
		Objects.requireNonNull(liveAdvancementTitles, "liveAdvancementTitles must not be null");
		String command = stripTrailingPunctuation(stripPoliteness(
				AgentValidators.normalizePrompt(request).toLowerCase(Locale.ROOT)));
		List<GoalClause> clauses = compoundClauses(command);
		if (!candidateTranslationBudget(command, clauses).valid()) return List.of();
		if (clauses.size() > 1) {
			TreeSet<String> candidates = new TreeSet<>();
			for (GoalClause clause : clauses) {
				candidates.addAll(candidatesForClause(clause, registries, liveAdvancementTitles));
				if (candidates.size() >= MAX_TRANSLATION_CANDIDATES) break;
			}
			return candidates.stream().limit(MAX_TRANSLATION_CANDIDATES).toList();
		}
		String advancementName = advancementName(command);
		if (advancementName != null) {
			return relatedAdvancements(advancementName, liveAdvancementTitles);
		}
		Matcher item = ITEM.matcher(command);
		if (item.matches()) {
			return relatedCandidates(ClauseKind.ITEM, item.group(3), registries);
		}
		Matcher kill = KILL.matcher(command);
		if (BEAT_GAME_TRANSLATION.matcher(command).matches()) return List.of("minecraft:ender_dragon");
		if (kill.matches()) {
			return relatedCandidates(ClauseKind.KILL, stripKillCount(kill.group(1)), registries);
		}
		Matcher block = BLOCK.matcher(command);
		if (block.matches()) {
			if (isDestructiveBlockVerb(block.group(1))
					&& BLOCK_LOCATION_SUFFIX.matcher(normalizedTarget(block.group(2))).find()) {
				return List.of("minecraft:air");
			}
			List<String> sourceBlocks = relatedBlocks(block.group(2), registries);
			return sourceBlocks.stream().limit(MAX_TRANSLATION_CANDIDATES).toList();
		}
		return List.of();
	}

	private static String advancementName(String command) {
		Matcher prefix = ADVANCEMENT_NAME_PREFIX.matcher(command);
		if (prefix.matches()) return prefix.group(1);
		Matcher suffix = ADVANCEMENT_NAME_SUFFIX.matcher(command);
		return suffix.matches() ? suffix.group(1) : null;
	}

	private static List<String> relatedAdvancements(String rawTarget, Map<String, String> liveAdvancementTitles) {
		String target = normalizedAdvancementText(rawTarget);
		if (target.isEmpty()) return List.of();
		TreeSet<String> matches = new TreeSet<>();
		for (Map.Entry<String, String> entry : liveAdvancementTitles.entrySet()) {
			String id = Objects.requireNonNull(entry.getKey(), "advancement ID must not be null");
			String title = Objects.requireNonNull(entry.getValue(), "advancement title must not be null");
			String normalizedId = normalizedAdvancementText(id);
			String normalizedTitle = normalizedAdvancementText(title);
			if (normalizedId.equals(target) || normalizedTitle.equals(target)
					|| normalizedId.contains(" " + target) || normalizedTitle.contains(target)) {
				matches.add(id);
			}
		}
		return matches.stream().limit(MAX_TRANSLATION_CANDIDATES).toList();
	}

	private static String normalizedAdvancementText(String value) {
		return value.toLowerCase(Locale.ROOT)
				.replace(':', ' ')
				.replace('/', ' ')
				.replace('_', ' ')
				.replaceAll("[^a-z0-9]+", " ")
				.strip()
				.replaceAll("\\s+", " ");
	}

	private static GoalCompilation compileCompound(String original, String command, RegistryAccess registries, long createdAtTick) {
		List<GoalClause> clauses = compoundClauses(command);
		if (clauses.size() < 2) return null;
		if (clauses.size() > MAX_COMPOUND_LEAVES) {
			return GoalCompilation.rejected("A compound goal may contain at most " + MAX_COMPOUND_LEAVES + " factual results.");
		}
		TranslationLeafBudget budget = compoundTranslationBudget(clauses);
		if (!budget.valid()) return GoalCompilation.rejected(budget.rejection());
		if (clauses.stream().anyMatch(clause -> clause.kind() != ClauseKind.ITEM && clause.kind() != ClauseKind.KILL)) {
			return GoalCompilation.needsTranslation("Confirm the exact factual results for this compound goal.");
		}
		ArrayList<GoalPredicate> predicates = new ArrayList<>();
		HashMap<String, Integer> inventoryPredicateIndexes = new HashMap<>();
		for (GoalClause clause : clauses) {
			if (clause.kind() == ClauseKind.ITEM) {
				if (clause.requiresCreation()) return craftingNeedsTranslation();
				if (clause.count() <= 0) return GoalCompilation.rejected("The requested item count must be positive.");
				List<String> matches = matchItems(clause.target(), registries);
				if (matches.size() != 1) return GoalCompilation.needsTranslation(matches.isEmpty()
						? "I could not identify every Minecraft item in that request."
						: "More than one Minecraft item matches part of that request.");
				String itemId = matches.getFirst();
				Integer priorIndex = inventoryPredicateIndexes.get(itemId);
				int combinedCount = clause.count();
				if (priorIndex != null) {
					try {
						combinedCount = Math.addExact(
								((GoalPredicate.InventoryContains) predicates.get(priorIndex)).count(), clause.count());
					} catch (ArithmeticException exception) {
						return unrepresentableItemCount();
					}
				}
				if (GoalInventoryCapacity.exceeds(itemId, combinedCount, registries)) return unrepresentableItemCount();
				GoalPredicate combined = new GoalPredicate.InventoryContains(itemId, combinedCount);
				if (priorIndex == null) {
					inventoryPredicateIndexes.put(itemId, predicates.size());
					predicates.add(combined);
				} else {
					predicates.set(priorIndex, combined);
				}
			} else {
				List<String> matches = matchEntities(clause.target(), registries);
				if (matches.size() != 1) return GoalCompilation.needsTranslation(matches.isEmpty()
						? "I could not identify every Minecraft entity in that request."
						: "More than one Minecraft entity matches part of that request.");
				predicates.add(new GoalPredicate.EntityKilledByAgent(matches.getFirst(), true));
			}
		}
		GoalPredicate compound = new GoalPredicate.AllOf(predicates);
		if (GoalInventoryCapacity.exceeds(compound, registries)) return unrepresentableItemCount();
		return accepted(original, compound, createdAtTick,
				"Goal set: complete " + predicates.size() + " factual Minecraft results.");
	}

	private static List<GoalClause> compoundClauses(String command) {
		String[] parts = command.split("\\s+and\\s+");
		if (parts.length < 2) return List.of();
		ArrayList<GoalClause> clauses = new ArrayList<>();
		GoalClause inherited = null;
		for (String part : parts) {
			GoalClause clause = parseClause(part.strip(), inherited);
			if (clause == null) return List.of();
			clauses.add(clause);
			inherited = clause;
		}
		return List.copyOf(clauses);
	}

	private static GoalClause parseClause(String value, GoalClause inherited) {
		Matcher kill = KILL.matcher(value);
		if (kill.matches()) return new GoalClause(ClauseKind.KILL, kill.group(1), 1, false, false);
		Matcher item = ITEM.matcher(value);
		if (item.matches()) {
			try {
				return new GoalClause(ClauseKind.ITEM, item.group(3), item.group(2) == null ? 1 : parseCount(item.group(2)), isCraftingVerb(item.group(1)), false);
			} catch (NumberFormatException exception) {
				return new GoalClause(ClauseKind.ITEM, item.group(3), -1, isCraftingVerb(item.group(1)), false);
			}
		}
		Matcher block = BLOCK.matcher(value);
		if (block.matches()) {
			return new GoalClause(ClauseKind.BLOCK, block.group(2), 1, false, isDestructiveBlockVerb(block.group(1)));
		}
		String advancement = advancementName(value);
		if (advancement != null) return new GoalClause(ClauseKind.ADVANCEMENT, advancement, 1, false, false);
		if (POSITION.matcher(value).matches()) return new GoalClause(ClauseKind.POSITION, value, 1, false, false);
		if (SURVIVE.matcher(value).matches()) return new GoalClause(ClauseKind.SURVIVE, value, 1, false, false);
		if (SUBJECTIVE.matcher(value).find()) return new GoalClause(ClauseKind.OPERATOR, value, 1, false, false);
		if (inherited == null) return null;
		if (REFERENCED_ITEM_CLAUSE.matcher(value).matches()) return new GoalClause(ClauseKind.OPERATOR, value, 1, false, false);
		String target = value.replaceFirst("^(?:the|a|an|some)\\s+", "").strip();
		if (target.isEmpty()) return null;
		if (inherited.kind() == ClauseKind.KILL) return new GoalClause(ClauseKind.KILL, target, 1, false, false);
		if (inherited.kind() == ClauseKind.BLOCK) {
			return new GoalClause(ClauseKind.BLOCK, target, 1, false, inherited.destructiveBlock());
		}
		if (inherited.kind() == ClauseKind.ADVANCEMENT) {
			return new GoalClause(ClauseKind.ADVANCEMENT, target.replaceFirst("\\s+advancement$", ""), 1, false, false);
		}
		if (inherited.kind() != ClauseKind.ITEM) return null;
		Matcher counted = Pattern.compile("^(?:(?:at least\\s+)?(" + COUNT + ")\\s+)?(?:(?:the|a|an|some)\\s+)?(.+)$").matcher(value);
		if (!counted.matches()) return null;
		try {
			return new GoalClause(ClauseKind.ITEM, counted.group(2), counted.group(1) == null ? 1 : parseCount(counted.group(1)), inherited.requiresCreation(), false);
		} catch (NumberFormatException exception) {
			return new GoalClause(ClauseKind.ITEM, counted.group(2), -1, inherited.requiresCreation(), false);
		}
	}

	private enum ClauseKind { ITEM, KILL, BLOCK, ADVANCEMENT, POSITION, SURVIVE, OPERATOR }
	private record GoalClause(
			ClauseKind kind, String target, int count, boolean requiresCreation, boolean destructiveBlock
	) { }

	private static List<String> candidatesForClause(
			GoalClause clause,
			RegistryAccess registries,
			Map<String, String> liveAdvancementTitles
	) {
		return switch (clause.kind()) {
			case ITEM -> relatedCandidates(ClauseKind.ITEM, clause.target(), registries);
			case KILL -> relatedCandidates(ClauseKind.KILL, stripKillCount(clause.target()), registries);
			case BLOCK -> {
				if (clause.destructiveBlock()
						&& BLOCK_LOCATION_SUFFIX.matcher(normalizedTarget(clause.target())).find()) {
					yield List.of("minecraft:air");
				}
				List<String> blocks = relatedBlocks(clause.target(), registries);
				yield blocks.stream().limit(MAX_TRANSLATION_CANDIDATES).toList();
			}
			case ADVANCEMENT -> relatedAdvancements(clause.target(), liveAdvancementTitles);
			case POSITION, SURVIVE, OPERATOR -> List.of();
		};
	}

	private static String stripKillCount(String target) {
		Matcher counted = KILL_COUNT.matcher(target);
		return counted.matches() ? counted.group(2) : target;
	}

	private static GoalTranslationConstraint.KillClause killConstraintFor(
			String target,
			RegistryAccess registries, Set<String> catalog
	) {
		ArrayList<GoalTranslationConstraint.KillAlternative> alternatives = new ArrayList<>();
		for (String rawAlternative : catalogTarget(target).split("\\s+or\\s+", -1)) {
			String alternative = rawAlternative.strip();
			Matcher counted = KILL_COUNT.matcher(alternative);
			int count = counted.matches() ? parseCount(counted.group(1)) : 1;
			String entityTarget = counted.matches() ? counted.group(2) : alternative;
			alternatives.add(new GoalTranslationConstraint.KillAlternative(
					requireCatalogCandidates(relatedCandidates(ClauseKind.KILL, entityTarget, registries), catalog), count));
		}
		return new GoalTranslationConstraint.KillClause(alternatives);
	}

	private static GoalTranslationConstraint.ItemClause itemConstraintFor(
			String target,
			int defaultCount,
			RegistryAccess registries, Set<String> catalog
	) {
		String factualTarget = catalogTarget(SUBJECTIVE.matcher(target).replaceAll(" ").replaceAll("\\s+", " ").strip());
		List<String> alternatives = explicitAlternatives(factualTarget);
		String finalTarget = stripItemAlternativeCount(alternatives.getLast());
		String sharedNoun = alternatives.size() > 1 ? sharedItemNoun(finalTarget) : "";
		ArrayList<GoalTranslationConstraint.ItemAlternative> constraints = new ArrayList<>();
		for (int index = 0; index < alternatives.size(); index++) {
			String alternative = alternatives.get(index);
			Matcher counted = ITEM_ALTERNATIVE_COUNT.matcher(alternative);
			int count = counted.matches() ? parseCount(counted.group(1)) : defaultCount;
			String itemTarget = counted.matches() ? counted.group(2) : alternative;
			List<String> candidates = index < alternatives.size() - 1 && !sharedNoun.isEmpty() && !itemTarget.contains(" ")
					? relatedItems(itemTarget + " " + sharedNoun, registries)
					: List.of();
			if (candidates.isEmpty()) candidates = relatedItems(itemTarget, registries);
			constraints.add(new GoalTranslationConstraint.ItemAlternative(requireCatalogCandidates(candidates, catalog), count));
		}
		return new GoalTranslationConstraint.ItemClause(constraints);
	}

	private static List<String> requireCatalogCandidates(List<String> candidates, Set<String> catalog) {
		if (candidates.isEmpty()) throw new AgentDomainException("GOAL_TRANSLATION_CANDIDATES_UNAVAILABLE",
				"No registered item or entity matches a required goal clause; specify its Minecraft name or identifier");
		if (candidates.size() > MAX_TRANSLATION_CANDIDATES || !catalog.containsAll(candidates)) {
			throw new AgentDomainException("GOAL_TRANSLATION_CATALOG_TOO_BROAD",
					"The requested category exceeds the bounded identifier catalog; specify a narrower category");
		}
		return candidates;
	}

	private static TranslationLeafBudget candidateTranslationBudget(String command, List<GoalClause> clauses) {
		if (clauses.size() > 1) return compoundTranslationBudget(clauses);
		Matcher kill = KILL.matcher(command);
		if (kill.matches() && !BEAT_GAME.matcher(command).matches()) return killLeafBudget(kill.group(1));
		Matcher item = ITEM.matcher(command);
		if (!item.matches()) return TranslationLeafBudget.valid(0);
		return itemLeafBudget(item.group(2) == null ? 1 : parseItemCount(item.group(2)), item.group(3));
	}

	private static TranslationLeafBudget compoundTranslationBudget(List<GoalClause> clauses) {
		if (clauses.size() > MAX_COMPOUND_LEAVES) return TranslationLeafBudget.overBudget();
		int leaves = 0;
		for (GoalClause clause : clauses) {
			TranslationLeafBudget clauseBudget = switch (clause.kind()) {
				case KILL -> killLeafBudget(clause.target());
				case ITEM -> itemLeafBudget(clause.count(), clause.target());
				default -> TranslationLeafBudget.valid(1);
			};
			if (!clauseBudget.valid()) return clauseBudget;
			leaves += clauseBudget.leaves();
			if (leaves > MAX_COMPOUND_LEAVES) return TranslationLeafBudget.overBudget();
		}
		return TranslationLeafBudget.valid(leaves);
	}

	private static TranslationLeafBudget alternativeLeafBudget(String target) {
		String[] alternatives = target.split("\\s+or\\s+", -1);
		if (alternatives.length > MAX_COMPOUND_LEAVES) return TranslationLeafBudget.overBudget();
		for (String alternative : alternatives) {
			if (alternative.isBlank()) return TranslationLeafBudget.overBudget();
		}
		return TranslationLeafBudget.valid(alternatives.length);
	}

	private static TranslationLeafBudget itemLeafBudget(int defaultCount, String target) {
		if (defaultCount <= 0) return TranslationLeafBudget.invalidItemCount();
		TranslationLeafBudget alternatives = alternativeLeafBudget(target);
		if (!alternatives.valid()) return alternatives;
		for (String alternative : explicitAlternatives(target)) {
			Matcher counted = ITEM_ALTERNATIVE_COUNT.matcher(alternative);
			if (counted.matches() && parseItemCount(counted.group(1)) <= 0) {
				return TranslationLeafBudget.invalidItemCount();
			}
		}
		return alternatives;
	}

	private static int parseItemCount(String value) {
		try {
			return parseCount(value);
		} catch (NumberFormatException exception) {
			return -1;
		}
	}

	private static int parseCount(String value) {
		int small = SMALL_COUNTS.indexOf(value);
		if (small >= 0) return small;
		String[] parts = value.split("[ -]", 2);
		int tens = TENS.indexOf(parts[0]);
		if (tens >= 0) return (tens + 2) * 10 + (parts.length == 1 ? 0 : SMALL_COUNTS.indexOf(parts[1]));
		return Integer.parseInt(value);
	}

	private static String stripItemAlternativeCount(String target) {
		Matcher counted = ITEM_ALTERNATIVE_COUNT.matcher(target);
		return counted.matches() ? counted.group(2) : target;
	}

	private static TranslationLeafBudget killLeafBudget(String target) {
		String[] alternatives = target.split("\\s+or\\s+", -1);
		int leaves = 0;
		for (String alternative : alternatives) {
			Matcher counted = KILL_COUNT.matcher(alternative.strip());
			int count = 1;
			if (counted.matches()) {
				try {
					int parsed = parseCount(counted.group(1));
					if (parsed <= 0) return TranslationLeafBudget.nonpositive();
					if (parsed > MAX_COMPOUND_LEAVES) {
						return TranslationLeafBudget.overBudget();
					}
					count = parsed;
				} catch (NumberFormatException | ArithmeticException exception) {
					return TranslationLeafBudget.overBudget();
				}
			}
			leaves += count;
			if (leaves > MAX_COMPOUND_LEAVES) return TranslationLeafBudget.overBudget();
		}
		return TranslationLeafBudget.valid(leaves);
	}

	private record TranslationLeafBudget(int leaves, String rejection) {
		private boolean valid() {
			return rejection.isEmpty();
		}

		private static TranslationLeafBudget valid(int leaves) {
			return new TranslationLeafBudget(leaves, "");
		}

		private static TranslationLeafBudget nonpositive() {
			return new TranslationLeafBudget(0, "The requested kill count must be positive.");
		}

		private static TranslationLeafBudget overBudget() {
			return new TranslationLeafBudget(0,
					"The requested kills exceed the limit of " + MAX_COMPOUND_LEAVES + " factual results.");
		}

		private static TranslationLeafBudget invalidItemCount() {
			return new TranslationLeafBudget(0, "The requested item count must be a positive supported integer.");
		}
	}

	private static List<String> relatedCandidates(ClauseKind kind, String rawTarget, RegistryAccess registries) {
		String target = catalogTarget(SUBJECTIVE.matcher(rawTarget).replaceAll(" ").replaceAll("\\s+", " ").strip());
		List<String> alternatives = explicitAlternatives(target);
		String sharedNoun = kind == ClauseKind.ITEM && alternatives.size() > 1
				? sharedItemNoun(stripItemAlternativeCount(alternatives.getLast()))
				: "";
		TreeSet<String> candidates = new TreeSet<>();
		for (int index = 0; index < alternatives.size(); index++) {
			String alternative = alternatives.get(index);
			if (kind == ClauseKind.KILL) alternative = stripKillCount(alternative);
			if (kind == ClauseKind.ITEM) alternative = stripItemAlternativeCount(alternative);
			List<String> related;
			if (kind == ClauseKind.ITEM) {
				related = index < alternatives.size() - 1 && !sharedNoun.isEmpty() && !alternative.contains(" ")
						? relatedItems(alternative + " " + sharedNoun, registries)
						: List.of();
				if (related.isEmpty()) related = relatedItems(alternative, registries);
			} else {
				related = relatedEntities(alternative, registries);
			}
			if (alternatives.size() > 1 && related.isEmpty()) return List.of();
			for (String candidate : related) {
				if (candidates.size() >= MAX_TRANSLATION_CANDIDATES) break;
				candidates.add(candidate);
			}
		}
		return List.copyOf(candidates);
	}

	private static List<String> explicitAlternatives(String target) {
		String[] parts = target.split("\\s+or\\s+", MAX_COMPOUND_LEAVES + 1);
		if (parts.length < 2 || parts.length > MAX_COMPOUND_LEAVES) return List.of(target);
		ArrayList<String> alternatives = new ArrayList<>(parts.length);
		for (String part : parts) {
			String alternative = part.replaceFirst("^(?:the|a|an|some)\\s+", "").strip();
			if (alternative.isEmpty()) return List.of(target);
			alternatives.add(alternative);
		}
		return List.copyOf(alternatives);
	}

	private static String sharedItemNoun(String finalAlternative) {
		int nounSeparator = finalAlternative.lastIndexOf(' ');
		return nounSeparator > 0 ? finalAlternative.substring(nounSeparator + 1) : "";
	}

	private static boolean isCraftingVerb(String verb) {
		return verb.equals("craft") || verb.equals("make");
	}

	private static boolean isDestructiveBlockVerb(String verb) {
		return verb.equals("mine") || verb.equals("break") || verb.equals("destroy");
	}

	private static GoalCompilation craftingNeedsTranslation() {
		return GoalCompilation.needsTranslation(
				"I can verify possession, but not that this item was newly crafted. Clarify whether obtaining it counts."
		);
	}

	private static GoalCompilation unrepresentableItemCount() {
		return GoalCompilation.rejected("The requested item count exceeds the player's inventory capacity for that item.");
	}

	private static GoalCompilation accepted(String original, GoalPredicate predicate, long createdAtTick, String message) {
		return GoalCompilation.accepted(GoalSpec.create(original, predicate, createdAtTick), message);
	}

	private static List<String> matchItems(String target, RegistryAccess registries) {
		String wanted = normalizedTarget(target);
		Set<String> forms = singularForms(wanted);
		ArrayList<String> matches = new ArrayList<>();
		Registry<Item> itemRegistry = registries.lookup(Registries.ITEM).orElse(BuiltInRegistries.ITEM);
		for (Identifier id : itemRegistry.keySet()) {
			Item item = itemRegistry.getValue(id);
			if (item == null) continue;
			String descriptionName = descriptionName(item.getDescriptionId());
			if (forms.contains(id.toString()) || forms.contains(pathName(id)) || forms.contains(descriptionName)) {
				matches.add(id.toString());
			}
		}
		return matches.stream().distinct().sorted().toList();
	}

	private static List<String> relatedItems(String target, RegistryAccess registries) {
		String wanted = catalogTarget(target);
		if (wanted.isEmpty()) return List.of();
		Set<String> forms = singularForms(wanted);
		Registry<Item> itemRegistry = registries.lookup(Registries.ITEM).orElse(BuiltInRegistries.ITEM);
		boolean tools = wanted.endsWith(" tool") || wanted.endsWith(" tools");
		String material = tools ? wanted.replaceFirst("\\s+tools?$", "") : "";
		Set<String> toolKinds = Set.of("axe", "hoe", "pickaxe", "shovel", "sword");
		return itemRegistry.keySet().stream()
				.filter(id -> {
					Item item = itemRegistry.getValue(id);
					String description = item == null ? "" : descriptionName(item.getDescriptionId());
					String path = pathName(id);
					return forms.stream().anyMatch(form -> id.toString().equals(form)
							|| path.equals(form) || description.equals(form)
							|| path.endsWith(" " + form) || description.endsWith(" " + form))
							|| tools && path.startsWith(material + " ") && toolKinds.contains(path.substring(material.length() + 1));
				})
				.map(Identifier::toString).distinct().sorted().toList();
	}

	private static List<String> relatedBlocks(String target, RegistryAccess registries) {
		String wanted = BLOCK_LOCATION_SUFFIX.matcher(catalogTarget(target))
				.replaceFirst("")
				.replaceFirst("^(?:with|using|from|of)\\s+", "")
				.strip();
		if (wanted.isEmpty()) return List.of();
		Set<String> wantedForms = blockPhraseForms(wanted);
		Registry<Block> blockRegistry = registries.lookup(Registries.BLOCK).orElse(BuiltInRegistries.BLOCK);
		ArrayList<Identifier> ids = new ArrayList<>(blockRegistry.keySet());
		ids.sort(Identifier::compareTo);
		ArrayList<String> matches = new ArrayList<>();
		for (Identifier id : ids) {
			Block block = blockRegistry.getValue(id);
			String path = pathName(id);
			String description = block == null ? "" : descriptionName(block.getDescriptionId());
			if (wantedForms.contains(id.toString()) || wantedForms.contains(normalizedTarget(path)) || wantedForms.contains(normalizedTarget(description))) {
				matches.add(id.toString());
				if (matches.size() == MAX_TRANSLATION_CANDIDATES) break;
			}
		}
		return List.copyOf(matches);
	}

	private static List<String> matchEntities(String target, RegistryAccess registries) {
		String wanted = normalizedTarget(target);
		Registry<EntityType<?>> entityRegistry = registries.lookup(Registries.ENTITY_TYPE).orElse(BuiltInRegistries.ENTITY_TYPE);
		return entityRegistry.keySet().stream()
				.filter(id -> {
					EntityType<?> entityType = entityRegistry.getValue(id);
					String displayName = entityType == null ? "" : entityType.getDescription().getString().toLowerCase(Locale.ROOT);
					return wanted.equals(id.toString()) || wanted.equals(pathName(id)) || wanted.equals(displayName);
				})
				.map(Identifier::toString)
				.distinct()
				.sorted()
				.toList();
	}

	private static List<String> relatedEntities(String target, RegistryAccess registries) {
		String wanted = catalogTarget(target);
		if (wanted.isEmpty()) return List.of();
		Set<String> forms = singularForms(wanted);
		Registry<EntityType<?>> registry = registries.lookup(Registries.ENTITY_TYPE).orElse(BuiltInRegistries.ENTITY_TYPE);
		return registry.keySet().stream()
				.filter(id -> forms.stream().anyMatch(form ->
						id.toString().equals(form) || pathName(id).equals(form) || pathName(id).endsWith(" " + form)))
				.map(Identifier::toString).distinct().sorted().toList();
	}

	private static String pathName(Identifier id) {
		return id.getPath().replace('_', ' ');
	}

	private static String descriptionName(String descriptionId) {
		int separator = descriptionId.lastIndexOf('.');
		return (separator < 0 ? descriptionId : descriptionId.substring(separator + 1)).replace('_', ' ').toLowerCase(Locale.ROOT);
	}

	private static String normalizedTarget(String value) {
		return value.strip().replaceAll("\\s+", " ");
	}

	/** Retrieval ignores ordinary source and purpose qualifiers; the model still translates the complete request. */
	private static String catalogTarget(String value) {
		String target = normalizedTarget(value).toLowerCase(Locale.ROOT)
				.replaceFirst("^(?:a|an|the|some|any|either)\\s+", "")
				.replaceFirst("^(?:kind|type|sort)\\s+of\\s+", "")
				.replaceFirst("\\s+(?:from|in|into|with|without|for|to|that|which|while|and)\\b.*$", "")
				.strip();
		return target.contains(":") ? target : target.replace('_', ' ');
	}

	private static Set<String> singularForms(String value) {
		if (value.endsWith("ies") && value.length() > 3) {
			return Set.of(value, value.substring(0, value.length() - 3) + "y", value.substring(0, value.length() - 1));
		}
		if ((value.endsWith("ches") || value.endsWith("shes") || value.endsWith("xes") || value.endsWith("zes")) && value.length() > 2) {
			return Set.of(value, value.substring(0, value.length() - 2), value.substring(0, value.length() - 1));
		}
		if (value.length() > 1 && value.endsWith("s") && !value.endsWith("ss")) {
			return Set.of(value, value.substring(0, value.length() - 1));
		}
		return Set.of(value);
	}

	/**
	 * Returns the bounded phrase variants used to match natural-language block names to registry paths.
	 * Variants only change the final word, which keeps multiword names precise and avoids substring matches.
	 */
	private static Set<String> blockPhraseForms(String value) {
		String normalized = normalizedTarget(value).replace('_', ' ');
		String[] words = normalized.split("\\s+");
		if (words.length == 0 || normalized.isEmpty()) return Set.of();
		String last = words[words.length - 1];
		TreeSet<String> forms = new TreeSet<>();
		for (String lastForm : singularForms(last)) {
			forms.add(withLastWord(words, lastForm));
		}
		if (last.length() > 1 && !last.endsWith("s") && !last.endsWith("ss")) {
			forms.add(withLastWord(words, last + "s"));
		}
		return Set.copyOf(forms);
	}

	private static String withLastWord(String[] words, String last) {
		StringBuilder result = new StringBuilder();
		for (int index = 0; index < words.length - 1; index++) {
			if (index > 0) result.append(' ');
			result.append(words[index]);
		}
		if (words.length > 1) result.append(' ');
		return result.append(last).toString();
	}

	private static String stripPoliteness(String request) {
		String result = request;
		for (String prefix : List.of("hey, ", "hey ", "please ", "can you ", "could you ", "would you ", "go and ")) {
			if (result.startsWith(prefix)) {
				result = result.substring(prefix.length()).strip();
				return stripPoliteness(result);
			}
		}
		return result;
	}

	private static String stripTrailingPunctuation(String request) {
		return request.replaceFirst("[.!?]+$", "").strip();
	}
}
