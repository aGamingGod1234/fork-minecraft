package dev.agaminggod.arenaagents.server.group;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class AgentGroupSpawnCoordinator {
	private AgentGroupSpawnCoordinator() {
	}

	public static Result spawn(AgentGroup group, Function<AgentId, MemberStatus> ensurePresent) {
		AgentGroup checkedGroup = Objects.requireNonNull(group, "group must not be null");
		Function<AgentId, MemberStatus> checkedEnsurePresent = Objects.requireNonNull(
				ensurePresent,
				"ensurePresent must not be null"
		);
		int present = 0;
		int restoring = 0;
		ArrayList<AgentId> missing = new ArrayList<>();
		for (AgentId memberId : checkedGroup.memberIds()) {
			switch (Objects.requireNonNull(checkedEnsurePresent.apply(memberId), "member status must not be null")) {
				case PRESENT -> present++;
				case RESTORING -> restoring++;
				case MISSING -> missing.add(memberId);
			}
		}
		return new Result(checkedGroup.memberIds().size(), present, restoring, missing);
	}

	public enum MemberStatus {
		PRESENT,
		RESTORING,
		MISSING
	}

	public record Result(int total, int present, int restoring, List<AgentId> missingIds) {
		public Result {
			if (total < 0 || present < 0 || restoring < 0 || present + restoring > total) {
				throw new IllegalArgumentException("group spawn counts are invalid");
			}
			missingIds = List.copyOf(Objects.requireNonNull(missingIds, "missingIds must not be null"));
			if (present + restoring + missingIds.size() != total) {
				throw new IllegalArgumentException("group spawn result must account for every member");
			}
	}
	}
}
