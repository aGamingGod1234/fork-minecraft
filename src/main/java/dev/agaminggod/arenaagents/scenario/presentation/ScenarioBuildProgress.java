package dev.agaminggod.arenaagents.scenario.presentation;

import dev.agaminggod.arenaagents.scenario.runtime.ScenarioArenaResetJob;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Bounded, human-readable state for the deterministic arena construction pipeline. */
public record ScenarioBuildProgress(
		String buildId, String scenarioTitle, String phase, long revision,
		int completed, int total, int changedBlocks,
		int originX, int originY, int originZ,
		Status status, String detail, String errorCode, String confirmationToken
) {
	public static final int MAX_BUILD_ID_LENGTH = 80;
	public static final int MAX_TITLE_LENGTH = 96;
	public static final int MAX_PHASE_LENGTH = 32;
	public static final int MAX_DETAIL_LENGTH = 240;
	public static final int MAX_ERROR_CODE_LENGTH = 64;
	public static final int MAX_CONFIRMATION_TOKEN_LENGTH = 80;
	public static final int MAX_TOTAL_WORK = 16_000_000;
	private static final Set<String> PHASES = Set.of(
			"awaiting_confirmation", "canonicalizing", "loading_chunks", "clearing_site", "applying_blocks",
			"verifying", "repair", "complete", "failed", "cancelled");

	public ScenarioBuildProgress(
			String buildId, String scenarioTitle, String phase, long revision,
			int completed, int total, int changedBlocks,
			int originX, int originY, int originZ, Status status, String detail
	) {
		this(buildId, scenarioTitle, phase, revision, completed, total, changedBlocks,
				originX, originY, originZ, status, detail,
				status == Status.FAILED ? "ARENA_BUILD_FAILED" : "", "");
	}

	public ScenarioBuildProgress {
		buildId = boundedText(buildId, "buildId", MAX_BUILD_ID_LENGTH);
		scenarioTitle = boundedText(scenarioTitle, "scenarioTitle", MAX_TITLE_LENGTH);
		phase = boundedText(phase, "phase", MAX_PHASE_LENGTH).toLowerCase(Locale.ROOT);
		if (!PHASES.contains(phase)) throw new IllegalArgumentException("phase is unsupported");
		if (revision <= 0L) throw new IllegalArgumentException("revision must be positive");
		if (total < 0 || total > MAX_TOTAL_WORK) throw new IllegalArgumentException("total work is invalid");
		if (completed < 0 || completed > total) throw new IllegalArgumentException("completed work is invalid");
		if (changedBlocks < 0 || changedBlocks > MAX_TOTAL_WORK) throw new IllegalArgumentException("changed block count is invalid");
		status = Objects.requireNonNull(status, "status must not be null");
		detail = boundedText(detail, "detail", MAX_DETAIL_LENGTH);
		errorCode = optionalText(errorCode, "errorCode", MAX_ERROR_CODE_LENGTH);
		confirmationToken = optionalText(confirmationToken, "confirmationToken", MAX_CONFIRMATION_TOKEN_LENGTH);
		if (status == Status.READY && !phase.equals("complete")) throw new IllegalArgumentException("ready progress must use the complete phase");
		if (status == Status.FAILED && !phase.equals("failed")) throw new IllegalArgumentException("failed progress must use the failed phase");
		if (status == Status.CANCELLED && !phase.equals("cancelled")) throw new IllegalArgumentException("cancelled progress must use the cancelled phase");
		if (status == Status.CONFIRMATION_REQUIRED && !phase.equals("awaiting_confirmation")) throw new IllegalArgumentException("confirmation progress must await confirmation");
		if (status == Status.BUILDING && Set.of("awaiting_confirmation", "complete", "failed", "cancelled").contains(phase)) throw new IllegalArgumentException("building progress cannot use a terminal phase");
		if ((status == Status.CONFIRMATION_REQUIRED) != !confirmationToken.isEmpty()) throw new IllegalArgumentException("only confirmation progress can carry a confirmation token");
		if (status == Status.FAILED && errorCode.isEmpty()) throw new IllegalArgumentException("failed progress must carry an error code");
		if (status != Status.FAILED && !errorCode.isEmpty()) throw new IllegalArgumentException("only failed progress can carry an error code");
	}

	public static ScenarioBuildProgress building(String buildId, String scenarioTitle, ScenarioArenaResetJob.Tick tick,
			int originX, int originY, int originZ, long revision, String detail) {
		Objects.requireNonNull(tick, "tick must not be null");
		return new ScenarioBuildProgress(buildId, scenarioTitle, wirePhase(tick.phase()), revision,
				tick.completed(), tick.total(), tick.changedBlocks(), originX, originY, originZ,
				Status.BUILDING, detail, "", "");
	}

	public static ScenarioBuildProgress fromResetTick(String buildId, String scenarioTitle, ScenarioArenaResetJob.Tick tick,
			int originX, int originY, int originZ, long revision, String detail) {
		Objects.requireNonNull(tick, "tick must not be null");
		return switch (tick.phase()) {
			case COMPLETE -> ready(buildId, scenarioTitle, tick.total(), tick.changedBlocks(), originX, originY, originZ, revision, detail);
			case FAILED -> failed(buildId, scenarioTitle, tick, originX, originY, originZ, revision, detail);
			default -> building(buildId, scenarioTitle, tick, originX, originY, originZ, revision, detail);
		};
	}

	public static ScenarioBuildProgress ready(String buildId, String scenarioTitle, int total, int changedBlocks,
			int originX, int originY, int originZ, long revision, String detail) {
		return new ScenarioBuildProgress(buildId, scenarioTitle, "complete", revision, total, total, changedBlocks,
				originX, originY, originZ, Status.READY, detail, "", "");
	}

	public static ScenarioBuildProgress failed(String buildId, String scenarioTitle, ScenarioArenaResetJob.Tick tick,
			int originX, int originY, int originZ, long revision, String detail) {
		Objects.requireNonNull(tick, "tick must not be null");
		String code = tick.failureReason().isBlank() ? "ARENA_BUILD_FAILED" : tick.failureReason();
		return new ScenarioBuildProgress(buildId, scenarioTitle, "failed", revision,
				tick.completed(), tick.total(), tick.changedBlocks(), originX, originY, originZ,
				Status.FAILED, detail, code, "");
	}

	public static ScenarioBuildProgress rejected(String buildId, String scenarioTitle,
			int originX, int originY, int originZ, String detail) {
		return rejected(buildId, scenarioTitle, 1L, originX, originY, originZ, detail);
	}

	public static ScenarioBuildProgress rejected(String buildId, String scenarioTitle, long revision,
			int originX, int originY, int originZ, String detail) {
		return new ScenarioBuildProgress(buildId, scenarioTitle, "failed", revision, 0, 0, 0,
				originX, originY, originZ, Status.FAILED, detail, "LAUNCH_REJECTED", "");
	}

	public static ScenarioBuildProgress confirmationRequired(String buildId, String scenarioTitle, long revision,
			int originX, int originY, int originZ, String detail, String confirmationToken) {
		return new ScenarioBuildProgress(buildId, scenarioTitle, "awaiting_confirmation", revision, 0, 0, 0,
				originX, originY, originZ, Status.CONFIRMATION_REQUIRED, detail, "", confirmationToken);
	}

	public static ScenarioBuildProgress cancelled(String buildId, String scenarioTitle, long revision,
			int total, int changedBlocks, int originX, int originY, int originZ, String detail) {
		return new ScenarioBuildProgress(buildId, scenarioTitle, "cancelled", revision, total, total, changedBlocks,
				originX, originY, originZ, Status.CANCELLED, detail, "", "");
	}

	public boolean terminal() {
		return status == Status.READY || status == Status.FAILED || status == Status.CANCELLED;
	}

	public int percent() {
		return total == 0 ? (status == Status.READY ? 100 : 0) : Math.clamp((int) (100L * completed / total), 0, 100);
	}

	public String originLabel() {
		return originX + ", " + originY + ", " + originZ;
	}

	public String humanPhase() {
		return switch (phase) {
			case "awaiting_confirmation" -> "Waiting for confirmation";
			case "canonicalizing" -> "Preparing blueprint";
			case "loading_chunks" -> "Loading arena area";
			case "clearing_site" -> "Clearing and flattening site";
			case "applying_blocks" -> "Processing blueprint blocks";
			case "verifying" -> "Verifying arena";
			case "repair" -> "Repairing arena";
			case "complete" -> "Arena ready";
			case "failed" -> "Arena build failed";
			case "cancelled" -> "Arena build cancelled";
			default -> phase;
		};
	}

	public static String wirePhase(ScenarioArenaResetJob.Phase phase) {
		return switch (Objects.requireNonNull(phase, "phase must not be null")) {
			case CANONICALIZE -> "canonicalizing";
			case LOAD_CHUNKS -> "loading_chunks";
			case CLEAR -> "clearing_site";
			case APPLY -> "applying_blocks";
			case VERIFY -> "verifying";
			case REPAIR -> "repair";
			case COMPLETE -> "complete";
			case FAILED -> "failed";
		};
	}

	private static String boundedText(String value, String field, int maximum) {
		value = Objects.requireNonNull(value, field + " must not be null");
		if (value.isBlank() || value.length() > maximum || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new IllegalArgumentException(field + " is invalid");
		return value;
	}

	private static String optionalText(String value, String field, int maximum) {
		value = Objects.requireNonNull(value, field + " must not be null");
		if (value.length() > maximum || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new IllegalArgumentException(field + " is invalid");
		return value;
	}

	public enum Status {
		BUILDING("building"), CONFIRMATION_REQUIRED("confirmation_required"), READY("ready"),
		FAILED("failed"), CANCELLED("cancelled");
		private final String wireName;
		Status(String wireName) { this.wireName = wireName; }
		public String wireName() { return wireName; }
		public static Status fromWireName(String value) {
			for (Status status : values()) if (status.wireName.equals(value)) return status;
			throw new IllegalArgumentException("unsupported build progress status");
		}
	}
}
