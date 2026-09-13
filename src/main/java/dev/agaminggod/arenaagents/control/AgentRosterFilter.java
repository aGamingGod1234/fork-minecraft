package dev.agaminggod.arenaagents.control;

import java.util.Locale;

public record AgentRosterFilter(String query, String provider, String state) {
	public AgentRosterFilter {
		query = normalized(query);
		provider = normalized(provider);
		state = normalized(state);
	}

	public static AgentRosterFilter all() {
		return new AgentRosterFilter("", "", "");
	}

	public boolean matches(AgentRosterEntry entry) {
		if (entry == null) {
			return false;
		}
		String canonicalQuery = canonical(query);
		boolean queryMatches = canonicalQuery.isEmpty()
				|| canonical(entry.name()).contains(canonicalQuery)
				|| canonical(entry.provider()).contains(canonicalQuery)
				|| canonical(entry.modelLabel()).contains(canonicalQuery);
		return queryMatches
				&& exactOrAll(provider, entry.provider())
				&& exactOrAll(state, entry.state());
	}

	private static boolean exactOrAll(String expected, String actual) {
		return expected.isEmpty() || canonical(expected).equals(canonical(actual));
	}

	private static String normalized(String value) {
		return value == null ? "" : value.trim();
	}

	private static String canonical(String value) {
		return value.toLowerCase(Locale.ROOT);
	}
}
