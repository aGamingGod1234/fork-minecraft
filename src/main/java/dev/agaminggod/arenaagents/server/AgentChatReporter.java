package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class AgentChatReporter {
	private AgentChatReporter() {
	}

	public static void planning(CodexAgentManager manager, AgentRecord record) {
		// Public agent summaries describe intent. Generic planner state stays in the field console.
	}

	public static void stillPlanning(CodexAgentManager manager, AgentRecord record) {
		// Periodic planner heartbeats do not belong in player chat.
	}

	public static void acting(CodexAgentManager manager, AgentRecord record, ServerActionRequest request) {
		AgentVerboseState verbose = verboseState(manager);
		if (verbose != null && verbose.beginAction(request)) {
			AgentVerboseChat.report(
					manager, verbose, record, "action", AgentActivityPresentation.actionStart(request.type()));
		}
		if (verbose != null && !verbose.standardActivityEnabled()) return;
		if (AgentActivityPresentation.shouldAnnounceAction(request.type())) {
			report(manager, record, AgentActivityPresentation.action(request.type()), ChatFormatting.WHITE);
		}
	}

	public static void decision(CodexAgentManager manager, AgentRecord record, String summary) {
		AgentVerboseChat.report(manager, record, "decision", summary);
		// The concrete action that follows is clearer than repeating the planner's internal summary.
	}

	public static void result(CodexAgentManager manager, AgentRecord record, ServerActionResult result) {
		AgentVerboseState verbose = verboseState(manager);
		if (verbose != null && !verbose.standardActivityEnabled()) return;
		AgentActivityPresentation.result(result).ifPresent(message ->
				report(manager, record, message, ChatFormatting.RED));
	}

	public static void completed(CodexAgentManager manager, AgentRecord record, String summary) {
		AgentVerboseState verbose = verboseState(manager);
		if (verbose != null && !verbose.standardActivityEnabled()) return;
		report(manager, record, summary == null || summary.isBlank() ? "Task complete" : summary, ChatFormatting.GREEN);
	}

	public static void goalVerified(CodexAgentManager manager, AgentRecord record, java.util.List<GoalEvidence.Fact> facts) {
		GoalEvidence.Fact fact = firstFact(facts, true);
		String detail = fact == null ? "server-observed requirements satisfied" : fact.expectedValue();
		report(manager, record, "Goal verified: " + detail + ".", ChatFormatting.GREEN);
	}

	public static void goalNotComplete(CodexAgentManager manager, AgentRecord record, java.util.List<GoalEvidence.Fact> facts) {
		GoalEvidence.Fact fact = firstFact(facts, false);
		String detail = fact == null
				? "requirements are not yet satisfied"
				: "expected " + fact.expectedValue() + ", observed " + fact.observedValue();
		report(manager, record, "Goal not complete: " + detail + ". Continuing.", ChatFormatting.YELLOW);
	}

	private static GoalEvidence.Fact firstFact(java.util.List<GoalEvidence.Fact> facts, boolean fallbackToFirst) {
		if (facts == null || facts.isEmpty()) return null;
		return facts.stream().filter(fact -> !fact.satisfied()).findFirst()
				.orElse(fallbackToFirst ? facts.getFirst() : null);
	}

	public static void failed(CodexAgentManager manager, AgentRecord record, String error) {
		failed(manager, record, null, error);
	}

	public static void failed(CodexAgentManager manager, AgentRecord record, String reasonCode, String error) {
		AgentVerboseState verbose = verboseState(manager);
		if (verbose != null && !verbose.standardActivityEnabled()) {
			AgentVerboseChat.report(manager, verbose, record, "error", readableError(error));
			return;
		}
		if (reasonCode != null && !AgentActivityPresentation.shouldShowInChat(reasonCode, true)) return;
		report(manager, record, "Needs attention: " + readableError(error), ChatFormatting.RED);
	}

	public static void disconnected(CodexAgentManager manager, AgentRecord record) {
		AgentVerboseState verbose = verboseState(manager);
		if (verbose != null && !verbose.standardActivityEnabled()) {
			AgentVerboseChat.report(
					manager, verbose, record, "error",
					"Connection lost. Reconnect the coordinator, then resume or restart this task.");
			return;
		}
		report(manager, record,
				"Connection lost. Reconnect the coordinator, then resume or restart this task.", ChatFormatting.RED);
	}

	public static void respawnDisconnected(CodexAgentManager manager, AgentRecord record) {
		AgentVerboseState verbose = verboseState(manager);
		if (verbose == null || verbose.standardActivityEnabled()) return;
		AgentVerboseChat.report(manager, verbose, record, "error", AgentActivityPresentation.respawnDisconnected());
	}

	private static AgentVerboseState verboseState(CodexAgentManager manager) {
		var server = manager.server();
		return server == null ? null : AgentVerboseState.forServer(server);
	}

	private static void report(
			CodexAgentManager manager,
			AgentRecord record,
			String message,
			ChatFormatting messageColor
	) {
		if (!manager.automaticProgress(record.agentId())) return;
		MutableComponent prefix = Component.literal("[" + manager.displayName(record) + "]")
				.withStyle(familyColor(record.profile().provider()));
		MutableComponent body = Component.literal(" " + (message == null || message.isBlank() ? "No details." : message))
				.withStyle(messageColor);
		manager.server().getPlayerList().broadcastSystemMessage(prefix.append(body), false);
	}

	private static ChatFormatting familyColor(String provider) {
		return switch (provider.toLowerCase(Locale.ROOT)) {
			case "codex" -> ChatFormatting.AQUA;
			case "gemini", "antigravity" -> ChatFormatting.LIGHT_PURPLE;
			case "kimi" -> ChatFormatting.GOLD;
			default -> ChatFormatting.WHITE;
		};
	}

	private static String readableError(String error) {
		if (error == null || error.isBlank()) return "the model provider returned no error details.";
		String compact = error.replace('\n', ' ').replace('\r', ' ').trim();
		return compact.length() <= 240 ? compact : compact.substring(0, 237) + "...";
	}
}
