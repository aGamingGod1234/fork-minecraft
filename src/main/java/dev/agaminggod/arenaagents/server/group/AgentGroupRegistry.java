package dev.agaminggod.arenaagents.server.group;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AgentGroupRegistry {
	public static final int SCHEMA_VERSION = 1;
	public static final int MAX_GROUPS = 32;

	private final Map<String, AgentGroup> groups = new LinkedHashMap<>();
	private final Runnable onChange;

	public AgentGroupRegistry(Runnable onChange) {
		this.onChange = Objects.requireNonNull(onChange, "onChange must not be null");
	}

	public static AgentGroupRegistry restore(Snapshot snapshot, Runnable onChange) {
		Snapshot checked = Objects.requireNonNull(snapshot, "snapshot must not be null");
		AgentGroupRegistry registry = new AgentGroupRegistry(onChange);
		for (AgentGroup group : checked.groups()) {
			String key = key(group.name());
			if (registry.groups.putIfAbsent(key, group) != null) {
				throw new AgentDomainException("DUPLICATE_GROUP_NAME", "Saved group names must be unique");
			}
		}
		return registry;
	}

	public synchronized AgentGroup save(String name, List<AgentId> memberIds) {
		AgentGroup group = new AgentGroup(name, memberIds);
		String key = key(group.name());
		if (!groups.containsKey(key) && groups.size() >= MAX_GROUPS) {
			throw new AgentDomainException("GROUP_LIMIT_REACHED", "A world can contain at most 32 saved groups");
		}
		groups.put(key, group);
		onChange.run();
		return group;
	}

	public synchronized AgentGroup require(String name) {
		AgentGroup group = groups.get(key(AgentGroup.normalizeName(name)));
		if (group == null) throw new AgentDomainException("GROUP_NOT_FOUND", "Unknown saved group: " + name);
		return group;
	}

	public synchronized AgentGroup delete(String name) {
		AgentGroup removed = groups.remove(key(AgentGroup.normalizeName(name)));
		if (removed == null) throw new AgentDomainException("GROUP_NOT_FOUND", "Unknown saved group: " + name);
		onChange.run();
		return removed;
	}

	/** Removes deleted agent identities and drops groups that no longer have a member. */
	public synchronized int retainMembers(Set<AgentId> currentAgentIds) {
		Set<AgentId> checkedIds = Set.copyOf(Objects.requireNonNull(
				currentAgentIds, "currentAgentIds must not be null"
		));
		int removedMembers = 0;
		var iterator = groups.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, AgentGroup> entry = iterator.next();
			AgentGroup group = entry.getValue();
			List<AgentId> retained = group.memberIds().stream().filter(checkedIds::contains).toList();
			removedMembers += group.memberIds().size() - retained.size();
			if (retained.isEmpty()) {
				iterator.remove();
			} else if (retained.size() != group.memberIds().size()) {
				entry.setValue(new AgentGroup(group.name(), retained));
			}
		}
		if (removedMembers > 0) onChange.run();
		return removedMembers;
	}

	public synchronized boolean removeMember(AgentId agentId) {
		AgentId checkedId = Objects.requireNonNull(agentId, "agentId must not be null");
		boolean changed = false;
		var iterator = groups.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, AgentGroup> entry = iterator.next();
			AgentGroup group = entry.getValue();
			if (!group.memberIds().contains(checkedId)) continue;
			List<AgentId> retained = group.memberIds().stream().filter(memberId -> !memberId.equals(checkedId)).toList();
			if (retained.isEmpty()) iterator.remove();
			else entry.setValue(new AgentGroup(group.name(), retained));
			changed = true;
		}
		if (changed) onChange.run();
		return changed;
	}

	public synchronized List<AgentGroup> groups() {
		return List.copyOf(groups.values());
	}

	public synchronized Snapshot snapshot() {
		return new Snapshot(SCHEMA_VERSION, groups());
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	public record Snapshot(int schemaVersion, List<AgentGroup> groups) {
		public Snapshot {
			if (schemaVersion != SCHEMA_VERSION) {
				throw new AgentDomainException("UNSUPPORTED_GROUP_SCHEMA", "Unsupported saved-group schema: " + schemaVersion);
			}
			groups = List.copyOf(Objects.requireNonNull(groups, "groups must not be null"));
			if (groups.size() > MAX_GROUPS || groups.stream().anyMatch(Objects::isNull)) {
				throw new AgentDomainException("INVALID_GROUP_SNAPSHOT", "Saved-group snapshot is invalid");
			}
		}
	}
}
