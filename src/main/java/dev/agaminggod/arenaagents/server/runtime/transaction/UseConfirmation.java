package dev.agaminggod.arenaagents.server.runtime.transaction;

public record UseConfirmation(boolean observedStart, boolean observedRelease) {
	public UseConfirmation {
		if (observedRelease && !observedStart) {
			throw new IllegalArgumentException("release cannot be observed before use starts");
		}
	}

	public static UseConfirmation initial() {
		return new UseConfirmation(false, false);
	}

	public UseConfirmation observeUsing(boolean using) {
		if (confirmed()) return this;
		if (using) return observedStart ? this : new UseConfirmation(true, false);
		return observedStart ? new UseConfirmation(true, true) : this;
	}

	public boolean confirmed() {
		return observedStart && observedRelease;
	}
}
