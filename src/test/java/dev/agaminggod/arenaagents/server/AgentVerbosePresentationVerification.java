package dev.agaminggod.arenaagents.server;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionProgress;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;
import java.util.OptionalInt;

public final class AgentVerbosePresentationVerification {
	private static final AgentId AGENT_ID = AgentId.parse("00000000-0000-0000-0000-000000000501");

	private AgentVerbosePresentationVerification() {
	}

	public static void main(String[] args) {
		System.out.println("Curated verbose presentation verification passed: " + verify() + " assertions");
	}

	public static int verify() {
		verifyCuratedLabels();
		verifyTechnicalTextIsHidden();
		verifyProgressMilestones();
		verifyRespawnActionStart();
		verifyReadablePhysicalEvents();
		return 70;
	}

	private static void verifyCuratedLabels() {
		for (String stage : new String[] {"output", "planner", "agent_message"}) {
			assertEquals("[Sol] Thinking: I need wood first.", rendered(stage, "I need wood first."),
					stage + " uses the public Thinking label");
		}
		assertEquals("[Sol] Plan: Find a tree, then craft tools.",
				rendered("decision", "Find a tree, then craft tools."), "decisions use the Plan label");
		assertEquals("[Sol] Action: Moving to the nearest oak tree.",
				rendered("action", "Moving to the nearest oak tree."), "action starts use the Action label");
		assertEquals("[Sol] Progress: 25% there.", rendered("progress", "25% there."),
				"milestones use the Progress label");
		assertEquals("[Sol] Result: Collected 4 oak logs.", rendered("result", "Collected 4 oak logs."),
				"successful outcomes use the Result label");
		assertEquals("[Sol] Problem: The path is blocked. Trying another route.",
				rendered("retry", "The path is blocked. Trying another route."), "retries use the Problem label");
		assertEquals("[Sol] Error: Movement timed out after 15 seconds.",
				rendered("error", "Movement timed out after 15 seconds."), "terminal failures use the Error label");
		assertEquals("[Sol] Voice: Processing nearby speech.",
				rendered("voice", "Processing nearby speech."), "voice input has a truthful dedicated label");
		assertTrue(curatedStage("voice"), "voice input milestones are visible when verbose mode is enabled");
		assertTrue(curatedStage("output"), "public agent summaries remain visible");
		assertTrue(!curatedStage("provider"), "provider plumbing is not a player-facing stage");
		assertTrue(!curatedStage("lifecycle"), "lifecycle plumbing is not a player-facing stage");
		assertTrue(!curatedStage("conversation"), "conversation plumbing is not a player-facing stage");
	}

	private static void verifyTechnicalTextIsHidden() {
		for (String technical : new String[] {
			"native:item/completed actionId=move-7",
			"Provider chunk native:item/completed",
			"trace 123e4567-e89b-12d3-a456-426614174000 finished",
			"trace 00000000-0000-0000-0000-000000000000 finished",
			"{\"type\":\"tool_call\",\"callId\":\"call-1\"}",
			"Result payload {\"action\":\"move-7\"}",
			"tool call action_id=mine-4",
			"Action ID: action-progress-1",
			"call_id=call_abc123",
			"ArenaScript dispatched action-progress-1",
			"Completed program-1-1:1:1:arena-state-1",
			"Completed agent-a:1:1:1:program-1-1:1:1:arena-state-1",
			"Completed program-1-1:1:1:step-7",
			"Completed trace-agent-a-1-1-initial",
			"Result payload [1,2,3]",
			"Result payload [{\"action\":\"move-7\"}]"
		}) {
			assertEquals("Technical details hidden.", AgentVerboseChat.sanitizeMessage(technical),
					"technical provider and action records never reach player chat");
		}
		assertEquals("I will collect wood, then craft tools.",
				AgentVerboseChat.sanitizeMessage("I will collect wood, then craft tools."),
				"ordinary public summaries remain readable");
		for (String ordinary : new String[] {
			"Take an action-oriented approach and wait.",
			"Make a call-back plan before nightfall.",
			"Trace-based planning is useful.",
			"Use [the east entrance] and wait."
		}) {
			assertEquals(ordinary, AgentVerboseChat.sanitizeMessage(ordinary),
					"ordinary prose that resembles a technical prefix remains readable");
		}
	}

	private static void verifyProgressMilestones() {
		AgentVerboseState state = new AgentVerboseState();
		ServerActionRequest first = request("action-first", ActionType.NAVIGATE_TO);
		assertTrue(!beginAction(state, first), "verbose-off action starts stay silent");
		assertTrue(standardActivityEnabled(state), "verbose off retains standard automatic activity chat");

		state.setEnabled(true);
		assertTrue(!standardActivityEnabled(state), "verbose on replaces standard automatic activity chat");
		assertTrue(beginAction(state, first), "the first typed action start is emitted");
		assertTrue(!beginAction(state, first), "a repeated typed action start is suppressed");
		assertEquals(OptionalInt.empty(), milestone(state, progress(first, 0.0D)), "zero-percent heartbeats stay silent");
		assertEquals(OptionalInt.empty(), milestone(state, progress(first, 0.16D)), "sub-milestone progress stays silent");
		assertEquals(OptionalInt.of(25), milestone(state, progress(first, 0.251D)), "the first quarter is emitted once");
		assertEquals(OptionalInt.empty(), milestone(state, progress(first, 0.26D)), "repeated first-quarter progress is suppressed");
		assertEquals(OptionalInt.empty(), milestone(state, progress(first, 0.20D)), "progress regressions are suppressed");
		assertEquals(OptionalInt.of(50), milestone(state, progress(first, 0.51D)), "the halfway milestone is emitted once");
		assertEquals(OptionalInt.of(75), milestone(state, progress(first, 0.99D)), "the final chat milestone stops at seventy-five percent");
		assertEquals(OptionalInt.empty(), milestone(state, progress(first, 1.0D)), "one hundred percent is reserved for the terminal result");

		ServerActionRequest second = request("action-second", ActionType.BREAK_BLOCK);
		assertTrue(beginAction(state, second), "a new action resets action-start coalescing");
		assertEquals(OptionalInt.of(25), milestone(state, progress(second, 0.25D)), "a new action gets its own milestones");
		finishAction(state, result(second, ServerActionState.SUCCEEDED, "BLOCK_BROKEN", "Block broken", 2_000L));
		assertTrue(beginAction(state, second), "a terminal result clears the completed action state");

		state.setEnabled(false);
		state.setEnabled(true);
		assertTrue(beginAction(state, second), "turning verbose off clears coalescing state");
		clearActivity(state);
		assertTrue(beginAction(state, second), "session reset clears coalescing state");
	}

	private static void verifyRespawnActionStart() {
		ServerActionRequest respawn = request("action-respawn", ActionType.RESPAWN);
		assertEquals("Respawning.", AgentActivityPresentation.actionStart(ActionType.RESPAWN),
				"accepted respawns have one typed action-start message");
		AgentVerboseState verbose = new AgentVerboseState();
		verbose.setEnabled(true);
		assertTrue(verbose.beginAction(respawn), "an accepted respawn emits its action start");
		assertTrue(!verbose.beginAction(respawn), "respawn action-start retries are coalesced");
		AgentVerboseState concise = new AgentVerboseState();
		assertTrue(concise.standardActivityEnabled(), "verbose-off respawns retain concise automatic chat policy");
		assertTrue(!concise.beginAction(respawn), "verbose-off respawns do not add an action-start line");
		assertEquals(
				"[Sol] Error: Connection lost while respawning. Reconnect the coordinator and try again.",
				rendered("error", respawnDisconnectMessage()),
				"a disconnected pending respawn has one local terminal error message"
		);
	}

	private static void verifyReadablePhysicalEvents() {
		ServerActionRequest move = request("action-timeout", ActionType.NAVIGATE_TO);
		ServerActionResult blocked = result(
				move, ServerActionState.FAILED, "PATH_BLOCKED", "The next waypoint was obstructed", 500L);
		assertEquals("retry", verboseResultStage(blocked), "blocked paths use the Problem stage");
		assertEquals("The path is blocked. Trying another route.", verboseResult(blocked),
				"blocked paths use concise recovery copy");
		ServerActionResult noPath = result(
				move, ServerActionState.FAILED, "NO_PATH", "No route reached the destination", 600L);
		assertEquals("retry", verboseResultStage(noPath), "missing paths use the Problem stage");
		assertEquals("The path is blocked. Trying another route.", verboseResult(noPath),
				"missing paths use the same actionable recovery copy");

		ServerActionResult timeout = result(
				move, ServerActionState.TIMED_OUT, "ACTION_TIMEOUT", "Action exceeded its execution deadline", 15_000L);
		assertEquals("error", verboseResultStage(timeout), "timed-out actions use the Error stage");
		assertEquals("Movement timed out after 15 seconds.", verboseResult(timeout),
				"timeouts use readable activity copy and duration");
		ServerActionResult terminal = result(
				move, ServerActionState.FAILED, "ACTION_REJECTED", "The destination is protected", 700L);
		assertEquals("error", verboseResultStage(terminal), "genuinely terminal failures use the Error stage");
		ServerActionResult cancelled = result(
				move, ServerActionState.CANCELLED, "ACTION_CANCELLED", "Cancelled by a newer plan", 800L);
		assertEquals("result", verboseResultStage(cancelled), "cancelled actions use the Result stage");
		assertEquals("25% there.", progressMessage(ActionType.NAVIGATE_TO, 25),
				"movement milestones describe useful progress without action identifiers");

		ServerActionRequest mine = request("action-success", ActionType.BREAK_BLOCK);
		ServerActionResult success = result(mine, ServerActionState.SUCCEEDED, "BLOCK_BROKEN", "Block broken", 900L);
		assertEquals("result", verboseResultStage(success), "successful actions use the Result stage");
		assertEquals("Block broken.", verboseResult(success), "successful action messages remain concise");
		assertEquals("25% complete.", progressMessage(ActionType.BREAK_BLOCK, 25),
				"non-movement milestones use compact completion copy");
	}

	private static String rendered(String stage, String message) {
		return AgentVerboseChat.line("Sol", "codex", stage, message).getString();
	}

	private static ServerActionRequest request(String actionId, ActionType type) {
		return new ServerActionRequest(
				AGENT_ID, 1L, actionId, type, new JsonObject(),
				new ActionProvenance("codex", "gpt-5.6-sol", "high", "priority", "program", 1L, "step", 1L)
		);
	}

	private static ServerActionProgress progress(ServerActionRequest request, double progress) {
		return new ServerActionProgress(
				request.agentId(), request.goalRevision(), request.actionId(), request.type(), progress, 1_000L, 2_000L);
	}

	private static ServerActionResult result(
			ServerActionRequest request,
			ServerActionState state,
			String reason,
			String message,
			long elapsedMs
	) {
		return new ServerActionResult(
				request.agentId(), request.goalRevision(), request.actionId(), request.type(), state,
				reason, message, elapsedMs, 2_000L);
	}

	private static boolean curatedStage(String stage) {
		return AgentVerboseChat.curatedStage(stage);
	}

	private static boolean beginAction(AgentVerboseState state, ServerActionRequest request) {
		return state.beginAction(request);
	}

	private static OptionalInt milestone(AgentVerboseState state, ServerActionProgress progress) {
		return state.progressMilestone(progress);
	}

	private static void finishAction(AgentVerboseState state, ServerActionResult result) {
		state.finishAction(result);
	}

	private static void clearActivity(AgentVerboseState state) {
		state.clearActivity();
	}

	private static boolean standardActivityEnabled(AgentVerboseState state) {
		return state.standardActivityEnabled();
	}

	private static String verboseResultStage(ServerActionResult result) {
		return AgentActivityPresentation.verboseResultStage(result);
	}

	private static String verboseResult(ServerActionResult result) {
		return AgentActivityPresentation.verboseResult(result);
	}

	private static String progressMessage(ActionType type, int milestone) {
		return AgentActivityPresentation.progress(type, milestone);
	}

	private static String respawnDisconnectMessage() {
		try {
			return (String) AgentActivityPresentation.class.getMethod("respawnDisconnected").invoke(null);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("missing disconnected-respawn presentation", exception);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}
}
