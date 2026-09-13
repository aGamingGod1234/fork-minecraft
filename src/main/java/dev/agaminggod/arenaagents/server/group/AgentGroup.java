package dev.agaminggod.arenaagents.server.group;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record AgentGroup(String name, List<AgentId> memberIds) {
	public static final int MAX_NAME_CODE_POINTS = 32;
	public static final int MAX_MEMBERS = 16;

	public AgentGroup {
		name = normalizeName(name);
		memberIds = List.copyOf(Objects.requireNonNull(memberIds, "memberIds must not be null"));
		if (memberIds.isEmpty() || memberIds.size() > MAX_MEMBERS) {
			throw new AgentDomainException("INVALID_GROUP_SIZE", "A saved group must contain 1 to 16 agents");
		}
		if (memberIds.stream().anyMatch(Objects::isNull)) {
			throw new AgentDomainException("INVALID_GROUP_MEMBER", "Saved groups cannot contain a null agent ID");
		}
		if (new LinkedHashSet<>(memberIds).size() != memberIds.size()) {
			throw new AgentDomainException("DUPLICATE_GROUP_MEMBER", "A saved group cannot contain the same agent twice");
		}
	}

	public static String normalizeName(String value) {
		String normalized = Objects.requireNonNull(value, "name must not be null").strip().replaceAll("\\s+", " ");
		int codePoints = normalized.codePointCount(0, normalized.length());
		if (codePoints == 0 || codePoints > MAX_NAME_CODE_POINTS
				|| normalized.codePoints().anyMatch(Character::isISOControl)) {
			throw new AgentDomainException("INVALID_GROUP_NAME", "Group names must contain 1 to 32 visible characters");
		}
		return normalized;
	}
}
