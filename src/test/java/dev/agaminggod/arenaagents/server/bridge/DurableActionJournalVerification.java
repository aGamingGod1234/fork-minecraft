package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public final class DurableActionJournalVerification {
	private DurableActionJournalVerification() {
	}

	public static int verify() {
		Path directory;
		try {
			directory = Files.createTempDirectory("arenaagents-action-journal-");
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
		Path path = directory.resolve("journal.json");
		AgentId agentId = AgentId.random();
		UUID goalId = UUID.randomUUID();
		ServerActionRequest request = request(agentId, 8L, "action-accepted", "step-accepted", 1L);

		DurableActionJournal accepted = DurableActionJournal.open(path);
		accepted.accept(request, goalId);
		accepted.close();
		DurableActionJournal acceptedReload = DurableActionJournal.open(path);
		if (persistentChannel(acceptedReload) != null) {
			throw new AssertionError("Loading an existing journal must not acquire an append channel before its owner starts");
		}
		assertEntry(acceptedReload, DurableActionJournal.Phase.ACCEPTED, request, null);
		expectFailure(() -> acceptedReload.accept(request, goalId), "ACTION_REPLAY");

		ServerActionResult uncertain = recoveryResult(request);
		acceptedReload.terminal(uncertain);
		FileChannel appendChannel = persistentChannel(acceptedReload);
		if (appendChannel == null || !appendChannel.isOpen()) {
			throw new AssertionError("First mutation must acquire a persistent append channel");
		}
		acceptedReload.close();
		if (appendChannel.isOpen()) throw new AssertionError("Closing the journal must release its append channel");
		DurableActionJournal terminalReload = DurableActionJournal.open(path);
		assertEntry(terminalReload, DurableActionJournal.Phase.TERMINAL, request, uncertain);
		UUID replacementGoalId = UUID.randomUUID();
		terminalReload.retainGoal(agentId, replacementGoalId);
		assertEntry(terminalReload, DurableActionJournal.Phase.TERMINAL, request, uncertain);
		terminalReload.terminal(uncertain);
		if (!terminalReload.acknowledge(agentId, 8L, request.actionId())) throw new AssertionError("Terminal result was not acknowledged");
		terminalReload.close();

		DurableActionJournal acknowledgedReload = DurableActionJournal.open(path);
		assertEntry(acknowledgedReload, DurableActionJournal.Phase.ACKNOWLEDGED, request, uncertain);
		if (!acknowledgedReload.acknowledge(agentId, 8L, request.actionId())) throw new AssertionError("Duplicate acknowledgement was not idempotent");
		acknowledgedReload.retainGoal(agentId, replacementGoalId);
		if (!acknowledgedReload.snapshot().isEmpty()) throw new AssertionError("Superseded acknowledged entry was not reclaimed");
		acknowledgedReload.close();

		Path capacityPath = directory.resolve("capacity.json");
		DurableActionJournal capacity = DurableActionJournal.open(capacityPath, 2);
		ServerActionRequest first = request(agentId, 9L, "capacity-1", "capacity-step-1", 2L);
		ServerActionRequest second = request(agentId, 9L, "capacity-2", "capacity-step-2", 3L);
		ServerActionRequest third = request(agentId, 9L, "capacity-3", "capacity-step-3", 4L);
		capacity.accept(first, goalId);
		capacity.accept(second, goalId);
		expectFailure(() -> capacity.accept(third, goalId), "ACTION_JOURNAL_FULL");
		ServerActionResult firstResult = result(first, "DONE");
		capacity.terminal(firstResult);
		capacity.acknowledge(agentId, 9L, first.actionId());
		capacity.accept(third, goalId);
		capacity.close();
		DurableActionJournal capacityReload = DurableActionJournal.open(capacityPath, 2);
		if (capacityReload.snapshot().size() != 2
				|| capacityReload.snapshot().stream().anyMatch(entry -> entry.request().actionId().equals(first.actionId()))) {
			throw new AssertionError("Acknowledged entry was not pressure-evicted before protected entries");
		}
		capacity.close();
		capacityReload.close();

		Path batchPath = directory.resolve("batch.journal");
		ServerActionRequest batchFirst = request(agentId, 10L, "batch-1", "batch-step-1", 5L);
		ServerActionRequest batchSecond = request(agentId, 10L, "batch-2", "batch-step-2", 6L);
		DurableActionJournal batch = DurableActionJournal.open(batchPath, 4);
		batch.accept(batchFirst, goalId);
		batch.accept(batchSecond, goalId);
		batch.close();
		DurableActionJournal batchReload = DurableActionJournal.open(batchPath, 4);
		int eventsBeforeRecovery = batchReload.persistedEventCountForVerification();
		batchReload.terminalizeAccepted(DurableActionJournalVerification::recoveryResult);
		if (batchReload.persistedEventCountForVerification() != eventsBeforeRecovery + 1) {
			throw new AssertionError("Accepted-only startup recovery was not persisted as one batched event");
		}
		DurableActionJournal recoveredBatch = DurableActionJournal.open(batchPath, 4);
		if (recoveredBatch.snapshot().size() != 2
				|| recoveredBatch.snapshot().stream().anyMatch(entry -> entry.phase() != DurableActionJournal.Phase.TERMINAL)) {
			throw new AssertionError("Batched uncertain outcomes did not survive reload");
		}
		if (batchReload.performanceSnapshotForVerification().appendCount() != 1L) {
			throw new AssertionError("Journal append instrumentation did not count each durable mutation");
		}
		batchReload.close();
		recoveredBatch.close();

		Path compactPath = directory.resolve("compact.journal");
		DurableActionJournal compact = DurableActionJournal.open(compactPath, 4, 4);
		ServerActionRequest compacted = request(agentId, 11L, "compact-1", "compact-step-1", 7L);
		compact.accept(compacted, goalId);
		compact.terminal(result(compacted, "DONE"));
		compact.acknowledge(agentId, 11L, compacted.actionId());
		compact.retainGoal(agentId, replacementGoalId);
		ServerActionRequest afterCompaction = request(agentId, 12L, "compact-2", "compact-step-2", 8L);
		compact.accept(afterCompaction, replacementGoalId);
		if (compact.persistedEventCountForVerification() != 1) {
			throw new AssertionError("Journal did not compact bounded history before the next append");
		}
		if (compact.performanceSnapshotForVerification().compactionCount() != 1L) {
			throw new AssertionError("Journal compaction instrumentation did not record the bounded rewrite");
		}
		compact.close();
		long validSize;
		try {
			validSize = Files.size(compactPath);
			Files.write(compactPath, new byte[] {0x01, 0x02, 0x03}, StandardOpenOption.APPEND);
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
		DurableActionJournal repaired = DurableActionJournal.open(compactPath, 4, 4);
		try {
			if (Files.size(compactPath) != validSize) throw new AssertionError("Incomplete crash tail was not truncated");
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
		assertEntry(repaired, DurableActionJournal.Phase.ACCEPTED, afterCompaction, null);
		repaired.terminal(result(afterCompaction, "DONE"));
		repaired.close();
		DurableActionJournal repairedReload = DurableActionJournal.open(compactPath, 4);
		assertEntry(repairedReload, DurableActionJournal.Phase.TERMINAL, afterCompaction, result(afterCompaction, "DONE"));
		repairedReload.close();

		Path detachedPath = directory.resolve("detached.json");
		DurableActionJournal detached = DurableActionJournal.open(detachedPath);
		ServerActionRequest detachedRequest = request(agentId, 13L, "detached-reply", "detached-step", 9L);
		ServerActionResult detachedResult = result(detachedRequest, "DONE");
		if (detached.terminalIfAccepted(detachedResult)) {
			throw new AssertionError("A detached reply must not require a journal entry");
		}
		if (!detached.snapshot().isEmpty()) {
			throw new AssertionError("Skipping journal terminalization must not invent an entry");
		}
		detached.accept(detachedRequest, goalId);
		if (!detached.terminalIfAccepted(detachedResult)) {
			throw new AssertionError("Accepted actions must still terminalize");
		}
		assertEntry(detached, DurableActionJournal.Phase.TERMINAL, detachedRequest, detachedResult);
		detached.close();

		try (var files = Files.list(directory)) {
			if (files.anyMatch(file -> file.getFileName().toString().contains(".tmp-"))) {
				throw new AssertionError("Journal left a temporary file after atomic replacement");
			}
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
		return 26;
	}

	private static FileChannel persistentChannel(DurableActionJournal journal) {
		try {
			var field = DurableActionJournal.class.getDeclaredField("persistentChannel");
			field.setAccessible(true);
			return (FileChannel) field.get(journal);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Could not inspect journal channel ownership", exception);
		}
	}

	private static void assertEntry(
			DurableActionJournal journal,
			DurableActionJournal.Phase phase,
			ServerActionRequest request,
			ServerActionResult result
	) {
		if (journal.snapshot().size() != 1) throw new AssertionError("Expected exactly one journal entry");
		DurableActionJournal.Entry entry = journal.snapshot().get(0);
		if (entry.phase() != phase || !entry.request().equals(request) || !java.util.Objects.equals(entry.result(), result)) {
			throw new AssertionError("Journal entry did not survive the crash-boundary reload");
		}
	}

	private static ServerActionRequest request(AgentId agentId, long revision, String actionId, String stepId, long sequence) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 25L);
		return new ServerActionRequest(
				agentId, revision, actionId, ActionType.WAIT, arguments,
				new ActionProvenance("codex", "gpt-5.6-sol", "high", "priority", "program", 1L, stepId, sequence)
		);
	}

	private static ServerActionResult result(ServerActionRequest request, String reason) {
		return new ServerActionResult(
				request.agentId(), request.goalRevision(), request.actionId(), request.type(), request.traceId(),
				ServerActionState.SUCCEEDED, reason, "Done", 1L, 1L, true, true
		);
	}

	private static ServerActionResult recoveryResult(ServerActionRequest request) {
		return new ServerActionResult(
				request.agentId(), request.goalRevision(), request.actionId(), request.type(), request.traceId(),
				ServerActionState.FAILED, "RECOVERY_UNCERTAIN",
				"Server restarted after accepting the action; its physical outcome is uncertain and it will not be replayed",
				0L, 1L, true, true
		);
	}

	private static void expectFailure(Runnable operation, String code) {
		try {
			operation.run();
			throw new AssertionError("Expected " + code);
		} catch (AgentDomainException exception) {
			if (!code.equals(exception.code())) throw new AssertionError("Expected " + code + " but got " + exception.code());
		}
	}
}
