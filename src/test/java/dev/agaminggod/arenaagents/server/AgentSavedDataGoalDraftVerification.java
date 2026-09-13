package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.goal.DraftIntent;
import dev.agaminggod.arenaagents.server.goal.PendingGoalDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AgentSavedDataGoalDraftVerification {
	private AgentSavedDataGoalDraftVerification() {
	}

	public static int verify() {
		verifyStaleDraftsDoNotExhaustLimit();
		verifyCurrentDraftsStillEnforceLimit();
		return 9;
	}

	private static void verifyStaleDraftsDoNotExhaustLimit() {
		AgentSavedData data = fullRegistry();
		List<AgentRecord> records = data.registry().records();
		List<PendingGoalDraft> staged = new ArrayList<>();
		for (int agentIndex = 0; agentIndex < records.size(); agentIndex++) {
			AgentRecord record = records.get(agentIndex);
			for (int requesterIndex = 0; requesterIndex < 2; requesterIndex++) {
				PendingGoalDraft draft = draft(record, agentIndex, requesterIndex, record.goalRevision());
				data.stageGoalDraft(draft);
				staged.add(draft);
			}
		}
		assertEquals(AgentConstants.DEFAULT_AGENT_LIMIT * 2, data.goalDrafts().size(),
				"fixture reaches the bounded draft limit");

		for (int index = 0; index < records.size() - 1; index++) {
			AgentRecord record = records.get(index);
			data.registry().start(record.agentId(), "advance the goal revision", 2_000L + index);
		}
		AgentRecord revised = data.registry().require(records.getFirst().agentId());
		PendingGoalDraft replacement = new PendingGoalDraft(
				UUID.fromString("00000000-0000-0000-0000-000000009999"),
				revised.agentId(),
				UUID.fromString("00000000-0000-0000-0000-000000008888"),
				"clarify the current goal",
				Optional.empty(),
				DraftIntent.CONFIRM_TRANSLATION,
				3_000L,
				revised.goalRevision(),
				revised.currentGoal().map(dev.agaminggod.arenaagents.agent.AgentGoal::goalId)
		);
		data.stageGoalDraft(replacement);

		AgentRecord unchanged = records.getLast();
		List<PendingGoalDraft> remaining = data.goalDrafts();
		assertEquals(3, remaining.size(), "stale revisions are pruned before the global limit is applied");
		assertTrue(remaining.contains(replacement), "the new current-revision draft is accepted");
		assertTrue(remaining.contains(staged.get(staged.size() - 2)),
				"a current draft from another requester is preserved");
		assertTrue(remaining.contains(staged.getLast()), "all current drafts survive stale pruning");
		assertTrue(remaining.stream().allMatch(candidate -> candidate.matches(data.registry().require(candidate.agentId()))),
				"every retained draft matches its current agent record");
		assertEquals(unchanged.goalRevision(), data.registry().require(unchanged.agentId()).goalRevision(),
				"pruning does not mutate current agent records");
	}

	private static void verifyCurrentDraftsStillEnforceLimit() {
		AgentSavedData data = fullRegistry();
		List<AgentRecord> records = data.registry().records();
		for (int agentIndex = 0; agentIndex < records.size(); agentIndex++) {
			for (int requesterIndex = 0; requesterIndex < 2; requesterIndex++) {
				data.stageGoalDraft(draft(records.get(agentIndex), agentIndex, requesterIndex, 0L));
			}
		}
		AgentRecord first = records.getFirst();
		PendingGoalDraft overflow = new PendingGoalDraft(
				UUID.fromString("00000000-0000-0000-0000-000000007777"),
				first.agentId(),
				UUID.fromString("00000000-0000-0000-0000-000000006666"),
				"one clarification too many",
				Optional.empty(),
				DraftIntent.CONFIRM_TRANSLATION,
				4_000L,
				first.goalRevision(),
				Optional.empty()
		);
		assertThrowsCode(() -> data.stageGoalDraft(overflow), "GOAL_DRAFT_LIMIT_REACHED");
		assertEquals(AgentConstants.DEFAULT_AGENT_LIMIT * 2, data.goalDrafts().size(),
				"valid drafts remain bounded after a rejected insertion");
	}

	private static AgentSavedData fullRegistry() {
		AgentSavedData data = new AgentSavedData();
		for (int index = 0; index < AgentConstants.DEFAULT_AGENT_LIMIT; index++) {
			data.registry().create("gpt-5.6-luna", "low", Optional.of("Draft" + index), 1_000L + index);
		}
		return data;
	}

	private static PendingGoalDraft draft(AgentRecord record, int agentIndex, int requesterIndex, long revision) {
		return new PendingGoalDraft(
				UUID.fromString(String.format("00000000-0000-0000-%04d-%012d", agentIndex, requesterIndex + 1)),
				record.agentId(),
				UUID.fromString(String.format("00000000-0000-0001-%04d-%012d", agentIndex, requesterIndex + 1)),
				"clarify goal " + agentIndex + " for requester " + requesterIndex,
				Optional.empty(),
				DraftIntent.CONFIRM_TRANSLATION,
				1_500L + (agentIndex * 2L) + requesterIndex,
				revision,
				Optional.empty()
		);
	}

	private static void assertThrowsCode(Runnable action, String code) {
		try {
			action.run();
		} catch (AgentDomainException exception) {
			if (code.equals(exception.code())) return;
			throw new AssertionError("expected " + code + " but got " + exception.code(), exception);
		}
		throw new AssertionError("expected " + code);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
