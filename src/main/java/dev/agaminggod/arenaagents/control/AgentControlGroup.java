package dev.agaminggod.arenaagents.control;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record AgentControlGroup(String name, List<String> memberIds) {
	public static final int MAX_GROUPS = 32;
	public static final int MAX_MEMBERS = 16;
	public static final int MAX_NAME_CODE_POINTS = 32;

	public AgentControlGroup {
		name = normalizeName(name);
		memberIds = List.copyOf(Objects.requireNonNull(memberIds, "memberIds must not be null"));
		if (memberIds.isEmpty() || memberIds.size() > MAX_MEMBERS) {
			throw new IllegalArgumentException("A saved group must contain 1 to 16 agents");
		}
		memberIds = memberIds.stream().map(memberId -> {
			if (memberId == null) throw new IllegalArgumentException("Saved groups cannot contain a null agent ID");
			return AgentId.parse(memberId).toString();
		}).toList();
		if (new LinkedHashSet<>(memberIds).size() != memberIds.size()) {
			throw new IllegalArgumentException("A saved group cannot contain the same agent twice");
		}
	}

	public static String normalizeName(String value) {
		String normalized = Objects.requireNonNull(value, "name must not be null").strip().replaceAll("\\s+", " ");
		int codePoints = normalized.codePointCount(0, normalized.length());
		if (codePoints == 0 || codePoints > MAX_NAME_CODE_POINTS
				|| normalized.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Group names must contain 1 to 32 visible characters");
		}
		return normalized;
	}
}
