package dev.agaminggod.arenaagents.control;

import java.util.List;

/** Stable management selection that is independent from transient roster widget focus. */
public final class AgentControlSelectionState {
	private String selectedAgentId = "";

	public void reconcile(List<AgentControlAgent> agents) {
		selectedAgentId = AgentControlSelection.resolve(selectedAgentId, agents);
	}

	public void select(String agentId, List<AgentControlAgent> agents) {
		selectedAgentId = AgentControlSelection.resolve(agentId, agents);
	}

	public String selectedAgentId() {
		return selectedAgentId;
	}
}
