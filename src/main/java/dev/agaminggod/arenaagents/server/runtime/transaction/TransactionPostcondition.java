package dev.agaminggod.arenaagents.server.runtime.transaction;

import java.util.Objects;

public final class TransactionPostcondition {
	private TransactionPostcondition() {
	}

	public sealed interface Verdict permits Verdict.Succeeded, Verdict.Failed {
		record Succeeded(String message) implements Verdict {
			public Succeeded {
				message = Objects.requireNonNull(message, "message must not be null");
			}
		}

		record Failed(String reasonCode, String message) implements Verdict {
			public Failed {
				if (reasonCode == null || reasonCode.isBlank()) {
					throw new IllegalArgumentException("reasonCode must not be blank");
				}
				message = Objects.requireNonNull(message, "message must not be null");
			}
		}
	}
}
