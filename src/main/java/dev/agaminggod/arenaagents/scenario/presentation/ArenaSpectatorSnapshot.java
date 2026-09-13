package dev.agaminggod.arenaagents.scenario.presentation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public record ArenaSpectatorSnapshot(
		long revision,
		String runId,
		String scenarioId,
		String scenarioTitle,
		String phaseTitle,
		long elapsedTick,
		long durationTicks,
		boolean terminal,
		String mapVersion,
		long worldSeed,
		long eventSeed,
		String resultHash,
		List<Standing> standings,
		List<ScenarioPublicEvent> feed,
		Optional<DirectorRecommendation> recommendation
) {
	public static final int MAX_STANDINGS = 16;
	public static final int MAX_FEED_ENTRIES = 6;
	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
	private static final Set<String> BODY_KEYS = Set.of(
			"runId", "scenarioId", "scenarioTitle", "phaseTitle", "elapsedTick", "durationTicks",
			"terminal", "mapVersion", "worldSeed", "eventSeed", "resultHash", "standings", "feed",
			"recommendation"
	);
	private static final Comparator<Standing> STANDING_ORDER = Comparator
			.comparingDouble(Standing::score).reversed()
			.thenComparing(Standing::participantId);

	public ArenaSpectatorSnapshot {
		if (revision <= 0L) throw new IllegalArgumentException("revision must be positive");
		runId = id(runId, "runId", 96);
		scenarioId = id(scenarioId, "scenarioId", 64);
		scenarioTitle = text(scenarioTitle, "scenarioTitle", 96);
		phaseTitle = text(phaseTitle, "phaseTitle", 80);
		if (elapsedTick < 0L) throw new IllegalArgumentException("elapsedTick must not be negative");
		if (durationTicks <= 0L || elapsedTick > durationTicks + 1L) {
			throw new IllegalArgumentException("scenario time is out of range");
		}
		mapVersion = text(mapVersion, "mapVersion", 64);
		resultHash = Objects.requireNonNull(resultHash, "resultHash must not be null").strip().toLowerCase(Locale.ROOT);
		if (terminal && !HASH.matcher(resultHash).matches()) {
			throw new IllegalArgumentException("terminal snapshot requires a lowercase SHA-256 result hash");
		}
		if (!terminal && !resultHash.isEmpty()) {
			throw new IllegalArgumentException("non-terminal snapshot cannot contain a result hash");
		}
		List<Standing> suppliedStandings = List.copyOf(Objects.requireNonNull(standings, "standings must not be null"));
		HashSet<String> participants = new HashSet<>();
		for (Standing standing : suppliedStandings) {
			Standing checked = Objects.requireNonNull(standing, "standings must not contain null");
			if (!participants.add(checked.participantId())) {
				throw new IllegalArgumentException("duplicate standing for " + checked.participantId());
			}
		}
		List<Standing> sorted = suppliedStandings.stream().sorted(STANDING_ORDER).limit(MAX_STANDINGS).toList();
		ArrayList<Standing> ranked = new ArrayList<>(sorted.size());
		for (int index = 0; index < sorted.size(); index++) ranked.add(sorted.get(index).withRank(index + 1));
		standings = List.copyOf(ranked);
		List<ScenarioPublicEvent> orderedFeed = Objects.requireNonNull(feed, "feed must not be null").stream()
				.map(event -> Objects.requireNonNull(event, "feed must not contain null"))
				.sorted(ScenarioPublicEvent.CANONICAL_ORDER)
				.toList();
		feed = List.copyOf(orderedFeed.subList(Math.max(0, orderedFeed.size() - MAX_FEED_ENTRIES), orderedFeed.size()));
		recommendation = Objects.requireNonNull(recommendation, "recommendation must not be null")
				.filter(value -> !terminal && value.currentAt(elapsedTick));
	}

	public ArenaSpectatorSnapshot withRevision(long revisedRevision) {
		return new ArenaSpectatorSnapshot(
				revisedRevision, runId, scenarioId, scenarioTitle, phaseTitle, elapsedTick, durationTicks,
				terminal, mapVersion, worldSeed, eventSeed, resultHash, standings, feed, recommendation
		);
	}

	public static Optional<PublicView> retainPublication(
			Optional<PublicView> retainedView,
			Optional<PublicView> observedView
	) {
		Objects.requireNonNull(retainedView, "retainedView must not be null");
		Objects.requireNonNull(observedView, "observedView must not be null");
		if (observedView.isPresent()) return observedView;
		return retainedView.filter(PublicView::terminal);
	}

	public static ArenaSpectatorSnapshot fromPublicView(long revision, PublicView view) {
		Objects.requireNonNull(view, "view must not be null");
		List<Standing> standings = view.participants().stream()
				.map(participant -> Standing.candidate(
						participant.participantId(),
						participant.displayName(),
						participant.providerFamily(),
						participant.score(),
						participant.healthPercent(),
						participant.status()
				))
				.toList();
		Optional<DirectorRecommendation> recommendation = Optional.empty();
		if (!view.terminal()) {
			long expiry = Math.min(view.durationTicks() + 1L, view.elapsedTick() + 40L);
			ArrayList<DirectorRecommendation> candidates = new ArrayList<>();
			candidates.add(new DirectorRecommendation(
					"anchor",
					"overview",
					"Arena overview",
					view.originX(),
					view.originY() + 4.0D,
					view.originZ(),
					expiry,
					10
			));
			for (ParticipantView participant : view.participants()) {
				if (participant.status().equals("eliminated") || participant.status().equals("dead")) continue;
				int statusPriority = participant.status().equals("recovering") ? 35 : 20;
				int healthInterest = Math.clamp((100 - participant.healthPercent()) / 5, 0, 20);
				candidates.add(new DirectorRecommendation(
						"participant",
						participant.participantId(),
						"Follow " + participant.displayName(),
						participant.x(),
						participant.y() + 1.5D,
						participant.z(),
						expiry,
						statusPriority + healthInterest
				));
			}
			recommendation = DirectorRecommendation.select(view.elapsedTick(), candidates);
		}
		return new ArenaSpectatorSnapshot(
				revision,
				view.runId(),
				view.scenarioId(),
				view.scenarioTitle(),
				view.phaseTitle(),
				view.elapsedTick(),
				view.durationTicks(),
				view.terminal(),
				view.mapVersion(),
				view.worldSeed(),
				view.eventSeed(),
				view.resultHash(),
				standings,
				view.feed(),
				recommendation
		);
	}

	JsonObject toJsonBody() {
		JsonObject value = new JsonObject();
		value.addProperty("runId", runId);
		value.addProperty("scenarioId", scenarioId);
		value.addProperty("scenarioTitle", scenarioTitle);
		value.addProperty("phaseTitle", phaseTitle);
		value.addProperty("elapsedTick", elapsedTick);
		value.addProperty("durationTicks", durationTicks);
		value.addProperty("terminal", terminal);
		value.addProperty("mapVersion", mapVersion);
		value.addProperty("worldSeed", worldSeed);
		value.addProperty("eventSeed", eventSeed);
		value.addProperty("resultHash", resultHash);
		JsonArray standingValues = new JsonArray();
		for (Standing standing : standings) standingValues.add(standing.toJson());
		value.add("standings", standingValues);
		JsonArray feedValues = new JsonArray();
		for (ScenarioPublicEvent event : feed) feedValues.add(feedToJson(event));
		value.add("feed", feedValues);
		if (recommendation.isPresent()) value.add("recommendation", recommendation.orElseThrow().toJson());
		else value.add("recommendation", null);
		return value;
	}

	static ArenaSpectatorSnapshot fromJsonBody(long revision, JsonObject value) {
		requireExactKeys(value, BODY_KEYS, "snapshot");
		JsonArray standings = array(value, "standings");
		JsonArray feed = array(value, "feed");
		if (standings.size() > MAX_STANDINGS) throw new IllegalArgumentException("snapshot has too many standings");
		if (feed.size() > MAX_FEED_ENTRIES) throw new IllegalArgumentException("snapshot has too many feed entries");
		ArrayList<Standing> decodedStandings = new ArrayList<>(standings.size());
		for (JsonElement element : standings) decodedStandings.add(Standing.fromJson(element));
		ArrayList<ScenarioPublicEvent> decodedFeed = new ArrayList<>(feed.size());
		for (JsonElement element : feed) decodedFeed.add(feedFromJson(element));
		JsonElement recommendation = value.get("recommendation");
		return new ArenaSpectatorSnapshot(
				revision,
				string(value, "runId"),
				string(value, "scenarioId"),
				string(value, "scenarioTitle"),
				string(value, "phaseTitle"),
				exactLong(value, "elapsedTick"),
				exactLong(value, "durationTicks"),
				bool(value, "terminal"),
				string(value, "mapVersion"),
				exactLong(value, "worldSeed"),
				exactLong(value, "eventSeed"),
				string(value, "resultHash"),
				decodedStandings,
				decodedFeed,
				recommendation == null || recommendation.isJsonNull()
						? Optional.empty() : Optional.of(DirectorRecommendation.fromJson(recommendation))
		);
	}

	static Set<String> bodyKeys() {
		return BODY_KEYS;
	}

	private static JsonObject feedToJson(ScenarioPublicEvent event) {
		JsonObject value = new JsonObject();
		value.addProperty("elapsedTick", event.elapsedTick());
		value.addProperty("participantId", event.participantId());
		value.addProperty("participantDisplayName", event.participantDisplayName());
		value.addProperty("category", event.category());
		value.addProperty("actionFamily", event.actionFamily());
		value.addProperty("scoreDelta", normalized(event.scoreDelta()));
		value.addProperty("state", event.state());
		return value;
	}

	private static ScenarioPublicEvent feedFromJson(JsonElement element) {
		JsonObject value = object(element, "feed entry");
		requireExactKeys(value, Set.of(
				"elapsedTick", "participantId", "participantDisplayName", "category", "actionFamily", "scoreDelta", "state"
		), "feed entry");
		return new ScenarioPublicEvent(
				exactLong(value, "elapsedTick"),
				string(value, "participantId"),
				string(value, "participantDisplayName"),
				string(value, "category"),
				string(value, "actionFamily"),
				finiteDouble(value, "scoreDelta"),
				string(value, "state")
		);
	}

	static String id(String value, String field, int maximum) {
		String normalized = text(value, field, maximum).toLowerCase(Locale.ROOT);
		if (!ID.matcher(normalized).matches()) throw new IllegalArgumentException(field + " is invalid");
		return normalized;
	}

	static String enumId(String value, String field, Set<String> allowed) {
		String normalized = id(value, field, 32);
		if (!allowed.contains(normalized)) throw new IllegalArgumentException(field + " is unsupported");
		return normalized;
	}

	static String text(String value, String field, int maximum) {
		Objects.requireNonNull(value, field + " must not be null");
		String normalized = value.strip().replaceAll("\\s+", " ");
		if (normalized.isEmpty() || normalized.length() > maximum) {
			throw new IllegalArgumentException(field + " is blank or too long");
		}
		return normalized;
	}

	static BigDecimal normalized(double value) {
		return value == 0.0D ? BigDecimal.ZERO : BigDecimal.valueOf(value).stripTrailingZeros();
	}

	static JsonObject object(JsonElement element, String field) {
		if (element == null || !element.isJsonObject()) throw new IllegalArgumentException(field + " must be an object");
		return element.getAsJsonObject();
	}

	static JsonArray array(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonArray()) throw new IllegalArgumentException(field + " must be an array");
		return value.getAsJsonArray();
	}

	static String string(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(field + " must be a string");
		}
		return value.getAsString();
	}

	static boolean bool(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException(field + " must be a boolean");
		}
		return value.getAsBoolean();
	}

	static long exactLong(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(field + " must be an integer");
		}
		try {
			return value.getAsBigDecimal().longValueExact();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(field + " must be an exact integer", exception);
		}
	}

	static int exactInt(JsonObject object, String field) {
		long value = exactLong(object, field);
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(field + " exceeds integer range");
		}
		return (int) value;
	}

	static double finiteDouble(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(field + " must be numeric");
		}
		double result = value.getAsDouble();
		if (!Double.isFinite(result)) throw new IllegalArgumentException(field + " must be finite");
		return result;
	}

	static void requireExactKeys(JsonObject object, Set<String> allowed, String field) {
		if (!object.keySet().equals(allowed)) {
			throw new IllegalArgumentException(field + " contains missing or unknown fields");
		}
	}

	public record Standing(
			int rank,
			String participantId,
			String displayName,
			String providerFamily,
			double score,
			int healthPercent,
			String status
	) {
		public Standing {
			if (rank < 0 || rank > MAX_STANDINGS) throw new IllegalArgumentException("standing rank is out of range");
			participantId = id(participantId, "standing participantId", 64);
			displayName = text(displayName, "standing displayName", 64);
			providerFamily = id(providerFamily, "standing providerFamily", 32);
			if (!Double.isFinite(score) || Math.abs(score) > 1_000_000.0D) {
				throw new IllegalArgumentException("standing score must be finite and bounded");
			}
			if (healthPercent < 0 || healthPercent > 100) throw new IllegalArgumentException("healthPercent is out of range");
			status = id(status, "standing status", 32);
		}

		public static Standing candidate(
				String participantId,
				String displayName,
				String providerFamily,
				double score,
				int healthPercent,
				String status
		) {
			return new Standing(0, participantId, displayName, providerFamily, score, healthPercent, status);
		}

		Standing withRank(int revisedRank) {
			return new Standing(revisedRank, participantId, displayName, providerFamily, score, healthPercent, status);
		}

		JsonObject toJson() {
			JsonObject value = new JsonObject();
			value.addProperty("rank", rank);
			value.addProperty("participantId", participantId);
			value.addProperty("displayName", displayName);
			value.addProperty("providerFamily", providerFamily);
			value.addProperty("score", normalized(score));
			value.addProperty("healthPercent", healthPercent);
			value.addProperty("status", status);
			return value;
		}

		static Standing fromJson(JsonElement element) {
			JsonObject value = object(element, "standing");
			requireExactKeys(value, Set.of(
					"rank", "participantId", "displayName", "providerFamily", "score", "healthPercent", "status"
			), "standing");
			return new Standing(
					exactInt(value, "rank"),
					string(value, "participantId"),
					string(value, "displayName"),
					string(value, "providerFamily"),
					finiteDouble(value, "score"),
					exactInt(value, "healthPercent"),
					string(value, "status")
			);
		}
	}

	public record ParticipantView(
			String participantId,
			String displayName,
			String providerFamily,
			double score,
			int healthPercent,
			String status,
			double x,
			double y,
			double z
	) {
		public ParticipantView {
			Standing validated = Standing.candidate(
					participantId, displayName, providerFamily, score, healthPercent, status);
			participantId = validated.participantId();
			displayName = validated.displayName();
			providerFamily = validated.providerFamily();
			score = validated.score();
			healthPercent = validated.healthPercent();
			status = validated.status();
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
					|| Math.abs(x) > 30_000_000.0D || Math.abs(z) > 30_000_000.0D
					|| y < -2_048.0D || y > 2_048.0D) {
				throw new IllegalArgumentException("participant focus coordinates must be finite and world-bounded");
			}
		}
	}

	public record PublicView(
			String runId,
			String scenarioId,
			String scenarioTitle,
			String phaseTitle,
			long elapsedTick,
			long durationTicks,
			boolean terminal,
			String mapVersion,
			long worldSeed,
			long eventSeed,
			String resultHash,
			double originX,
			double originY,
			double originZ,
			List<ParticipantView> participants,
			List<ScenarioPublicEvent> feed
	) {
		public PublicView {
			runId = id(runId, "public view runId", 96);
			scenarioId = id(scenarioId, "public view scenarioId", 64);
			scenarioTitle = text(scenarioTitle, "public view scenarioTitle", 96);
			phaseTitle = text(phaseTitle, "public view phaseTitle", 80);
			if (elapsedTick < 0L || durationTicks <= 0L || elapsedTick > durationTicks + 1L) {
				throw new IllegalArgumentException("public view scenario time is out of range");
			}
			mapVersion = text(mapVersion, "public view mapVersion", 64);
			resultHash = Objects.requireNonNull(resultHash, "public view resultHash must not be null");
			if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)) {
				throw new IllegalArgumentException("public view origin must be finite");
			}
			participants = List.copyOf(Objects.requireNonNull(participants, "public view participants must not be null"));
			if (participants.size() > 64) throw new IllegalArgumentException("public view has too many participants");
			feed = List.copyOf(Objects.requireNonNull(feed, "public view feed must not be null"));
		}
	}

	public static final class PublicationCadence {
		private final int intervalTicks;
		private long lastPublishedTick = Long.MIN_VALUE;

		public PublicationCadence(int intervalTicks) {
			if (intervalTicks <= 0) throw new IllegalArgumentException("intervalTicks must be positive");
			this.intervalTicks = intervalTicks;
		}

		public boolean due(long currentTick) {
			if (currentTick < 0L) throw new IllegalArgumentException("currentTick must not be negative");
			return lastPublishedTick == Long.MIN_VALUE || currentTick - lastPublishedTick >= intervalTicks;
		}

		public void markPublished(long currentTick) {
			if (!due(currentTick)) throw new IllegalStateException("publication is not due");
			lastPublishedTick = currentTick;
		}
	}
}
