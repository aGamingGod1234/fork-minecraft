package dev.agaminggod.arenaagents.server.bridge;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Retains terminal action results until the authenticated coordinator acknowledges them.
 *
 * <p>The ledger is deliberately independent of action execution. A result is retained after
 * the physical action has been cleaned up, so reconnect replay can never execute the action a
 * second time. Session ownership is only a delivery hint; closing a session fences that hint
 * and makes the result eligible for the next session.</p>
 */
final class TerminalResultLedger {
	private static final int MAX_PENDING_RESULTS = 4_096;
	private static final int MAX_RETIRED_RESULTS = 4_096;
	private final Map<AgentId, GoalFence> goalFences = new LinkedHashMap<>();
	private final LinkedHashMap<Key, Entry> pending = new LinkedHashMap<>();
	private final LinkedHashMap<Key, RetiredEntry> retired = new LinkedHashMap<>();

	/** Advances the live lifecycle fence without pruning globally retained terminal identities. */
	synchronized void beginGoal(AgentId agentId, long goalRevision, UUID logicalGoalId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		if (goalRevision < 0L) throw new IllegalArgumentException("goalRevision must be nonnegative");
		goalFences.put(agentId, new GoalFence(goalRevision, logicalGoalId));
	}

	/** Retains every trusted terminal result until acknowledgement or global FIFO retirement. */
	synchronized boolean retain(ServerActionResult result) {
		Objects.requireNonNull(result, "result must not be null");
		GoalFence current = goalFences.get(result.agentId());
		UUID logicalGoalId = current == null ? null : current.logicalGoalId();
		if (current == null) goalFences.put(result.agentId(), new GoalFence(result.goalRevision(), null));
		Key key = new Key(result.agentId(), result.goalRevision(), result.actionId());
		Entry existing = pending.get(key);
		if (existing != null) {
			requireSameResult(existing.result(), result);
			return true;
		}
		RetiredEntry retiredEntry = retired.get(key);
		if (retiredEntry != null) {
			requireSameResult(retiredEntry.fingerprint(), result);
			if (retiredEntry.acknowledged()) return false;
			retired.remove(key);
		}
		pending.put(key, new Entry(result, logicalGoalId));
		trimPending();
		return true;
	}

	synchronized List<ServerActionResult> pending() {
		return pending.values().stream().map(Entry::result).toList();
	}

	/** Claims one enqueue attempt for a session, coalescing repeated server ticks. */
	synchronized boolean claim(ServerActionResult result, Object session) {
		Entry entry = pending.get(key(result));
		if (entry == null) return false;
		if (entry.queuedSession() == session) return false;
		entry.queuedSession = Objects.requireNonNull(session, "session must not be null");
		return true;
	}

	synchronized void release(ServerActionResult result, Object session) {
		Entry entry = pending.get(key(result));
		if (entry != null && entry.queuedSession() == session) entry.queuedSession = null;
	}

	/** Removes a result only when the caller owns its failed enqueue attempt. */
	synchronized void discard(ServerActionResult result, Object session) {
		Entry entry = pending.get(key(result));
		if (entry != null && entry.queuedSession() == session) pending.remove(key(result));
	}

	/** Fences all enqueue claims from a closed session without dropping the result. */
	synchronized void sessionClosed(Object session) {
		Objects.requireNonNull(session, "session must not be null");
		for (Entry entry : pending.values()) {
			if (entry.queuedSession() == session) entry.queuedSession = null;
		}
	}

	synchronized boolean acknowledge(AgentId agentId, long goalRevision, String actionId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(actionId, "actionId must not be null");
		Key key = new Key(agentId, goalRevision, actionId);
		Entry entry = pending.remove(key);
		if (entry != null) {
			retire(key, entry, true);
			return true;
		}
		RetiredEntry retiredEntry = retired.get(key);
		if (retiredEntry == null) return false;
		if (!retiredEntry.acknowledged()) retired.put(key, retiredEntry.acknowledge());
		return true;
	}

	synchronized void remove(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		goalFences.remove(agentId);
		removeAgent(pending, agentId);
		removeAgent(retired, agentId);
	}

	synchronized int pendingCount() {
		return pending.size();
	}

	private void trimPending() {
		while (pending.size() > MAX_PENDING_RESULTS) {
			Map.Entry<Key, Entry> oldest = pending.entrySet().iterator().next();
			pending.remove(oldest.getKey());
			retire(oldest.getKey(), oldest.getValue(), false);
		}
	}

	private void retire(Key key, Entry entry, boolean acknowledged) {
		RetiredEntry previous = retired.remove(key);
		boolean finalAcknowledged = acknowledged || previous != null && previous.acknowledged();
		retired.put(key, new RetiredEntry(fingerprint(entry.result()), entry.logicalGoalId(), finalAcknowledged));
		while (retired.size() > MAX_RETIRED_RESULTS) retired.remove(retired.keySet().iterator().next());
	}

	private static void requireSameResult(ServerActionResult expected, ServerActionResult actual) {
		if (!expected.equals(actual)) {
			throw new IllegalStateException("Terminal action identity is bound to a different result");
		}
	}

	private static void requireSameResult(String expectedFingerprint, ServerActionResult actual) {
		if (!expectedFingerprint.equals(fingerprint(actual))) {
			throw new IllegalStateException("Terminal action identity is bound to a different result");
		}
	}

	private static String fingerprint(ServerActionResult result) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			update(digest, result.agentId().toString());
			update(digest, Long.toString(result.goalRevision()));
			update(digest, result.actionId());
			update(digest, result.actionType().wireName());
			update(digest, result.traceId());
			update(digest, result.state().name());
			update(digest, result.reasonCode());
			update(digest, result.message());
			update(digest, Long.toString(result.elapsedMs()));
			update(digest, Long.toString(result.observedAtEpochMs()));
			update(digest, Boolean.toString(result.executionStarted()));
			update(digest, Boolean.toString(result.physicalAttempted()));
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void update(MessageDigest digest, String value) {
		byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
		digest.update((byte) (bytes.length >>> 24));
		digest.update((byte) (bytes.length >>> 16));
		digest.update((byte) (bytes.length >>> 8));
		digest.update((byte) bytes.length);
		digest.update(bytes);
	}

	private static void removeAgent(Map<Key, ?> entries, AgentId agentId) {
		for (Iterator<Key> iterator = entries.keySet().iterator(); iterator.hasNext();) {
			if (iterator.next().agentId().equals(agentId)) iterator.remove();
		}
	}

	private static Key key(ServerActionResult result) {
		return new Key(result.agentId(), result.goalRevision(), result.actionId());
	}

	private record Key(AgentId agentId, long goalRevision, String actionId) { }

	private record GoalFence(long goalRevision, UUID logicalGoalId) { }

	private record RetiredEntry(String fingerprint, UUID logicalGoalId, boolean acknowledged) {
		private RetiredEntry acknowledge() {
			return acknowledged ? this : new RetiredEntry(fingerprint, logicalGoalId, true);
		}
	}

	private static final class Entry {
		private final ServerActionResult result;
		private final UUID logicalGoalId;
		private Object queuedSession;

		private Entry(ServerActionResult result, UUID logicalGoalId) {
			this.result = result;
			this.logicalGoalId = logicalGoalId;
		}

		private ServerActionResult result() { return result; }
		private UUID logicalGoalId() { return logicalGoalId; }
		private Object queuedSession() { return queuedSession; }
	}
}
