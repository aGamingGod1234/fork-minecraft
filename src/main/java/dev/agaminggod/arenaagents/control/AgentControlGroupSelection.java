package dev.agaminggod.arenaagents.control;

import java.util.List;
import java.util.Objects;

public final class AgentControlGroupSelection {
	private AgentControlGroupSelection() {
	}

	public static String resolve(String selectedName, List<AgentControlGroup> groups) {
		List<AgentControlGroup> checkedGroups = List.copyOf(Objects.requireNonNull(groups, "groups must not be null"));
		if (checkedGroups.isEmpty()) return "";
		String selected = Objects.requireNonNullElse(selectedName, "");
		return checkedGroups.stream()
				.filter(group -> group.name().equalsIgnoreCase(selected))
				.map(AgentControlGroup::name)
				.findFirst()
				.orElse(checkedGroups.getFirst().name());
	}

	public static List<String> members(String selectedName, List<AgentControlGroup> groups) {
		String resolved = resolve(selectedName, groups);
		if (resolved.isEmpty()) return List.of();
		return groups.stream()
				.filter(group -> group.name().equals(resolved))
				.findFirst()
				.map(AgentControlGroup::memberIds)
				.orElse(List.of());
	}
}
