package dev.agaminggod.arenaagents.scenario.runtime;

import com.google.gson.JsonObject;
import java.util.Objects;

public record ScenarioResetReceipt(
		String blueprintSha256,
		String managedVolumeSha256,
		int plannedPlacements,
		int appliedPlacements,
		int verifiedPlacements,
		boolean verified
) {
	public ScenarioResetReceipt {
		blueprintSha256 = hash(blueprintSha256, "blueprintSha256");
		managedVolumeSha256 = hash(managedVolumeSha256, "managedVolumeSha256");
		if (plannedPlacements < 0 || appliedPlacements < 0 || verifiedPlacements < 0
				|| appliedPlacements > plannedPlacements || verifiedPlacements > plannedPlacements) {
			throw new IllegalArgumentException("reset receipt counts are invalid");
		}
		if (verified && (appliedPlacements != plannedPlacements
				|| verifiedPlacements != plannedPlacements
				|| !blueprintSha256.equals(managedVolumeSha256))) {
			throw new IllegalArgumentException("verified reset receipt must exhaustively match the blueprint");
		}
	}

	public static ScenarioResetReceipt verified(
			String blueprintSha256,
			String managedVolumeSha256,
			int plannedPlacements,
			int appliedPlacements,
			int verifiedPlacements
	) {
		return new ScenarioResetReceipt(
				blueprintSha256, managedVolumeSha256, plannedPlacements,
				appliedPlacements, verifiedPlacements, true
		);
	}

	public String canonicalJson() {
		JsonObject object = new JsonObject();
		object.addProperty("blueprintSha256", blueprintSha256);
		object.addProperty("managedVolumeSha256", managedVolumeSha256);
		object.addProperty("plannedPlacements", plannedPlacements);
		object.addProperty("appliedPlacements", appliedPlacements);
		object.addProperty("verifiedPlacements", verifiedPlacements);
		object.addProperty("verified", verified);
		return object.toString();
	}

	private static String hash(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
		if (normalized.isEmpty() || normalized.length() > 128 || !normalized.matches("[a-z0-9_-]+")) {
			throw new IllegalArgumentException(field + " is invalid");
		}
		return normalized;
	}
}
