package dev.agaminggod.arenaagents.scenario.result;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioResetReceipt;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record MatchResultV1(
		String matchId,
		String scenarioId,
		String mapVersion,
		long worldSeed,
		long eventSeed,
		List<Standing> standings,
		List<ScenarioPublicEvent> events,
		ScenarioResetReceipt reset,
		String canonicalSha256
) {
	private static final Comparator<Standing> STANDING_ORDER = Comparator
			.comparingDouble(Standing::score).reversed()
			.thenComparing(Standing::participantId);

	public MatchResultV1 {
		matchId = safeId(matchId, "matchId");
		scenarioId = safeId(scenarioId, "scenarioId");
		mapVersion = bounded(mapVersion, "mapVersion", 64);
		standings = List.copyOf(Objects.requireNonNull(standings, "standings must not be null").stream()
				.sorted(STANDING_ORDER)
				.toList());
		if (standings.isEmpty()) throw new IllegalArgumentException("standings must not be empty");
		HashSet<String> participants = new HashSet<>();
		for (Standing standing : standings) {
			if (!participants.add(standing.participantId())) {
				throw new IllegalArgumentException("duplicate standing for " + standing.participantId());
			}
		}
		events = List.copyOf(Objects.requireNonNull(events, "events must not be null").stream()
				.sorted(ScenarioPublicEvent.CANONICAL_ORDER)
				.toList());
		reset = Objects.requireNonNull(reset, "reset must not be null");
		String computed = sha256(canonicalJsonWithoutHash(
				matchId, scenarioId, mapVersion, worldSeed, eventSeed, standings, events, reset
		));
		if (canonicalSha256 == null || canonicalSha256.isBlank()) {
			canonicalSha256 = computed;
		} else if (!computed.equals(canonicalSha256.toLowerCase(java.util.Locale.ROOT))) {
			throw new IllegalArgumentException("canonicalSha256 does not match canonical result content");
		} else {
			canonicalSha256 = computed;
		}
	}

	public String canonicalJson() {
		JsonObject object = JsonParser.parseString(canonicalJsonWithoutHash(
				matchId, scenarioId, mapVersion, worldSeed, eventSeed, standings, events, reset
		)).getAsJsonObject();
		object.addProperty("canonicalSha256", canonicalSha256);
		return object.toString();
	}

	private static String canonicalJsonWithoutHash(
			String matchId,
			String scenarioId,
			String mapVersion,
			long worldSeed,
			long eventSeed,
			List<Standing> standings,
			List<ScenarioPublicEvent> events,
			ScenarioResetReceipt reset
	) {
		JsonObject object = new JsonObject();
		object.addProperty("schemaVersion", 1);
		object.addProperty("matchId", matchId);
		object.addProperty("scenarioId", scenarioId);
		object.addProperty("mapVersion", mapVersion);
		object.addProperty("worldSeed", worldSeed);
		object.addProperty("eventSeed", eventSeed);
		JsonArray standingArray = new JsonArray();
		for (Standing standing : standings) standingArray.add(standing.toJson());
		object.add("standings", standingArray);
		JsonArray eventArray = new JsonArray();
		for (ScenarioPublicEvent event : events) {
			eventArray.add(JsonParser.parseString(event.canonicalJson()));
		}
		object.add("events", eventArray);
		object.add("reset", JsonParser.parseString(reset.canonicalJson()));
		return object.toString();
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static String safeId(String value, String field) {
		String normalized = bounded(value, field, 96).toLowerCase(java.util.Locale.ROOT);
		if (!normalized.matches("[a-z0-9][a-z0-9_-]*")) {
			throw new IllegalArgumentException(field + " is invalid");
		}
		return normalized;
	}

	private static String bounded(String value, String field, int maximum) {
		Objects.requireNonNull(value, field + " must not be null");
		String normalized = value.strip().replaceAll("\\s+", " ");
		if (normalized.isEmpty() || normalized.length() > maximum) {
			throw new IllegalArgumentException(field + " is blank or too long");
		}
		return normalized;
	}

	public record Standing(String participantId, String displayName, double score, String outcome) {
		public Standing {
			participantId = safeId(participantId, "participantId");
			displayName = bounded(displayName, "displayName", 64);
			if (!Double.isFinite(score)) throw new IllegalArgumentException("score must be finite");
			outcome = safeId(outcome, "outcome");
		}

		private JsonObject toJson() {
			JsonObject object = new JsonObject();
			object.addProperty("participantId", participantId);
			object.addProperty("displayName", displayName);
			object.addProperty("score", normalizedNumber(score));
			object.addProperty("outcome", outcome);
			return object;
		}

		private static BigDecimal normalizedNumber(double value) {
			if (value == 0.0D) return BigDecimal.ZERO;
			return BigDecimal.valueOf(value).stripTrailingZeros();
		}
	}
}
