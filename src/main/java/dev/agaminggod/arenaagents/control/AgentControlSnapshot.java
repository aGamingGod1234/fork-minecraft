package dev.agaminggod.arenaagents.control;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record AgentControlSnapshot(
		int schemaVersion,
		boolean canControl,
		boolean automationAvailable,
		String automationStatus,
		long generatedAtEpochMs,
		List<AgentControlAgent> agents,
		List<AgentControlGroup> groups,
		List<AgentControlModelOption> catalog
) {
	public static final int SCHEMA_VERSION = 7;
	public static final int MAX_AGENTS = AgentConstants.DEFAULT_AGENT_LIMIT;

	public AgentControlSnapshot(boolean canControl, long generatedAtEpochMs, List<AgentControlAgent> agents) {
		this(SCHEMA_VERSION, canControl, true, "Automation ready", generatedAtEpochMs, agents,
				List.of(), AgentControlCatalog.currentOptions());
	}

	public AgentControlSnapshot(
			boolean canControl,
			boolean automationAvailable,
			String automationStatus,
			long generatedAtEpochMs,
			List<AgentControlAgent> agents
	) {
		this(SCHEMA_VERSION, canControl, automationAvailable, automationStatus, generatedAtEpochMs, agents,
				List.of(), AgentControlCatalog.currentOptions());
	}

	public AgentControlSnapshot(
			int schemaVersion,
			boolean canControl,
			boolean automationAvailable,
			String automationStatus,
			long generatedAtEpochMs,
			List<AgentControlAgent> agents,
			List<AgentControlModelOption> catalog
	) {
		this(schemaVersion, canControl, automationAvailable, automationStatus, generatedAtEpochMs,
				agents, List.of(), catalog);
	}

	public AgentControlSnapshot {
		if (schemaVersion != SCHEMA_VERSION) {
			throw new IllegalArgumentException("Unsupported control snapshot schema: " + schemaVersion);
		}
		if (generatedAtEpochMs <= 0L) {
			throw new IllegalArgumentException("generatedAtEpochMs must be positive");
		}
		automationStatus = Objects.requireNonNull(automationStatus, "automationStatus must not be null");
		if (automationStatus.isBlank() || automationStatus.length() > 160) {
			throw new IllegalArgumentException("automationStatus must contain 1 to 160 characters");
		}
		agents = List.copyOf(Objects.requireNonNull(agents, "agents must not be null"));
		groups = List.copyOf(Objects.requireNonNull(groups, "groups must not be null"));
		catalog = List.copyOf(Objects.requireNonNull(catalog, "catalog must not be null"));
		if (agents.size() > MAX_AGENTS) {
			throw new IllegalArgumentException("Control snapshot exceeds the supported agent limit");
		}
		if (catalog.isEmpty() || catalog.size() > AgentControlModelOption.MAX_OPTIONS) {
			throw new IllegalArgumentException("Control snapshot catalog has an invalid item count");
		}
		if (groups.size() > AgentControlGroup.MAX_GROUPS) {
			throw new IllegalArgumentException("Control snapshot exceeds the saved-group limit");
		}
	}

	/** Group controls are useful only when a roster or saved group can contain more than one agent. */
	public boolean groupAvailable() {
		return agents.size() > 1 || !groups.isEmpty();
	}

	public static AgentControlSnapshot fromRecords(
			boolean canControl,
			long generatedAtEpochMs,
			List<AgentRecord> records
	) {
		Objects.requireNonNull(records, "records must not be null");
		List<AgentControlAgent> agents = records.stream()
				.sorted(Comparator.comparingLong(AgentRecord::createdAtEpochMs).thenComparing(record -> record.agentId().toString()))
				.map(AgentControlSnapshot::fromRecord)
				.toList();
		return new AgentControlSnapshot(canControl, generatedAtEpochMs, agents);
	}

	public static AgentControlSnapshot fromRecords(
			boolean canControl,
			boolean automationAvailable,
			String automationStatus,
			long generatedAtEpochMs,
			List<AgentRecord> records
	) {
		AgentControlSnapshot ready = fromRecords(canControl, generatedAtEpochMs, records);
		return new AgentControlSnapshot(
				canControl, automationAvailable, automationStatus, generatedAtEpochMs, ready.agents()
		);
	}

	public static AgentControlSnapshot fromRecords(
			boolean canControl,
			boolean automationAvailable,
			String automationStatus,
			long generatedAtEpochMs,
			List<AgentRecord> records,
			List<AgentControlModelOption> catalog
	) {
		AgentControlSnapshot ready = fromRecords(canControl, generatedAtEpochMs, records);
		return new AgentControlSnapshot(
				SCHEMA_VERSION, canControl, automationAvailable, automationStatus,
				generatedAtEpochMs, ready.agents(), List.of(), catalog
		);
	}

	public static AgentControlSnapshot fromRecords(
			boolean canControl,
			boolean automationAvailable,
			String automationStatus,
			long generatedAtEpochMs,
			List<AgentRecord> records,
			List<AgentControlGroup> groups,
			List<AgentControlModelOption> catalog
	) {
		AgentControlSnapshot ready = fromRecords(canControl, generatedAtEpochMs, records);
		return new AgentControlSnapshot(
				SCHEMA_VERSION, canControl, automationAvailable, automationStatus,
				generatedAtEpochMs, ready.agents(), groups, catalog
		);
	}

	private static AgentControlAgent fromRecord(AgentRecord record) {
		Objects.requireNonNull(record, "records must not contain null");
		String displayName = AgentIdentity.displayName(record.agentId(), record.profile());
		String currentGoal = record.currentGoal().map(goal -> goal.prompt()).orElse("");
		return new AgentControlAgent(
				record.agentId().toString(),
				record.agentId().shortValue(),
				AgentControlAgent.truncate(displayName, AgentControlAgent.MAX_DISPLAY_NAME_LENGTH),
				record.profile().userName().orElse(""),
				record.profile().provider(),
				record.profile().model(),
				record.profile().reasoning(),
				AgentIdentity.playerName(record.agentId(), record.profile()),
				record.profile().visualIdentity().individualVariant(),
				record.state().name(),
				AgentControlAgent.truncate(currentGoal, AgentControlAgent.MAX_CURRENT_GOAL_LENGTH),
				record.queuedGoals().size(),
				AgentControlAgent.truncate(record.lastSummary(), AgentControlAgent.MAX_LAST_SUMMARY_LENGTH),
				AgentControlAgent.truncate(record.lastError(), AgentControlAgent.MAX_LAST_ERROR_LENGTH),
				record.automaticProgress(),
				record.entityUuid().isPresent()
		);
	}
}
