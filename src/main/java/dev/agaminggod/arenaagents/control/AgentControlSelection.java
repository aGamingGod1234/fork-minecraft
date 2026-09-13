package dev.agaminggod.arenaagents.control;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class AgentControlSelection {
	private AgentControlSelection() {
	}

	public static String resolve(String currentAgentId, List<AgentControlAgent> agents) {
		String current = currentAgentId == null ? "" : currentAgentId;
		List<AgentControlAgent> checkedAgents = List.copyOf(Objects.requireNonNull(agents, "agents must not be null"));
		if (checkedAgents.stream().anyMatch(agent -> agent.agentId().equals(current))) {
			return current;
		}
		return checkedAgents.isEmpty() ? "" : checkedAgents.getFirst().agentId();
	}

	public static String move(String currentAgentId, int direction, List<AgentControlAgent> agents) {
		List<AgentControlAgent> checkedAgents = List.copyOf(Objects.requireNonNull(agents, "agents must not be null"));
		if (checkedAgents.isEmpty()) return "";
		String current = resolve(currentAgentId, checkedAgents);
		int currentIndex = 0;
		for (int index = 0; index < checkedAgents.size(); index++) {
			if (checkedAgents.get(index).agentId().equals(current)) {
				currentIndex = index;
				break;
			}
		}
		return checkedAgents.get(Math.floorMod(currentIndex + direction, checkedAgents.size())).agentId();
	}

	public static String resolveSelected(
			String currentAgentId,
			Set<String> selectedAgentIds,
			List<AgentControlAgent> agents
	) {
		Set<String> selected = Set.copyOf(Objects.requireNonNull(selectedAgentIds, "selectedAgentIds must not be null"));
		List<AgentControlAgent> checkedAgents = List.copyOf(Objects.requireNonNull(agents, "agents must not be null"));
		String current = currentAgentId == null ? "" : currentAgentId;
		if (selected.contains(current) && checkedAgents.stream().anyMatch(agent -> agent.agentId().equals(current))) {
			return current;
		}
		return checkedAgents.stream()
				.map(AgentControlAgent::agentId)
				.filter(selected::contains)
				.findFirst()
				.orElse("");
	}

	public static String removalDescription(List<AgentControlAgent> agents) {
		List<AgentControlAgent> checkedAgents = List.copyOf(Objects.requireNonNull(agents, "agents must not be null"));
		if (checkedAgents.isEmpty()) return "No agents are selected.";
		String names;
		if (checkedAgents.size() == 1) {
			names = checkedAgents.getFirst().displayName();
		} else if (checkedAgents.size() == 2) {
			names = checkedAgents.getFirst().displayName() + " and " + checkedAgents.getLast().displayName();
		} else {
			names = checkedAgents.subList(0, checkedAgents.size() - 1).stream()
					.map(AgentControlAgent::displayName)
					.collect(Collectors.joining(", "))
					+ ", and " + checkedAgents.getLast().displayName();
		}
		String subject = checkedAgents.size() == 1 ? "agent" : checkedAgents.size() + " agents";
		String pronoun = checkedAgents.size() == 1 ? "its" : "their";
		return "Remove " + subject + "? " + names + " and " + pronoun + " saved state will be removed.";
	}
}
