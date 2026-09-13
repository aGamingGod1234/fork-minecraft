package dev.agaminggod.arenaagents.client.gui;

/** Tracks the last rendered server revision and one command awaiting its requested snapshot. */
public record ConsoleMutationState(long acceptedRevision, long pendingMutationId) {
	public ConsoleMutationState {
		if (acceptedRevision < 0L) throw new IllegalArgumentException("acceptedRevision must not be negative");
		if (pendingMutationId < 0L) throw new IllegalArgumentException("pendingMutationId must not be negative");
	}

	public static ConsoleMutationState initial(long revision) {
		return new ConsoleMutationState(revision, 0L);
	}

	public boolean mutationPending() {
		return pendingMutationId > 0L;
	}

	public boolean accepts(long revision) {
		return revision > acceptedRevision;
	}

	public ConsoleMutationState accept(long revision, long acknowledgedMutationId) {
		if (acknowledgedMutationId < 0L) {
			throw new IllegalArgumentException("acknowledgedMutationId must not be negative");
		}
		if (!accepts(revision)) return this;
		boolean acknowledged = mutationPending() && acknowledgedMutationId >= pendingMutationId;
		return new ConsoleMutationState(revision, acknowledged ? 0L : pendingMutationId);
	}

	public ConsoleMutationState beginMutation(long mutationId) {
		if (mutationId <= 0L) throw new IllegalArgumentException("mutationId must be positive");
		return mutationPending() ? this : new ConsoleMutationState(acceptedRevision, mutationId);
	}
}
