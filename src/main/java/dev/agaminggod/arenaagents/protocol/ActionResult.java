package dev.agaminggod.arenaagents.protocol;

public record ActionResult(
		String commandId,
		ActionState state,
		String reasonCode,
		String message,
		long completedAtEpochMs
) {
	public ActionResult {
		commandId = requireText(commandId, "commandId", ProtocolConstants.MAX_COMMAND_ID_LENGTH, false);
		if (state == null) {
			throw new ProtocolException(ProtocolConstants.ERROR_INVALID_FIELD, "state must not be null");
		}
		if (!state.isTerminal()) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_INVALID_FIELD,
					"ActionResult state must be terminal"
			);
		}
		reasonCode = requireText(reasonCode, "reasonCode", ProtocolConstants.MAX_REASON_CODE_LENGTH, false);
		message = requireText(message, "message", ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH, true);
		if (completedAtEpochMs <= 0L) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_OUT_OF_RANGE,
					"completedAtEpochMs must be positive"
			);
		}
	}

	private static String requireText(String value, String field, int maxLength, boolean emptyAllowed) {
		if (value == null) {
			throw new ProtocolException(ProtocolConstants.ERROR_INVALID_FIELD, field + " must not be null");
		}
		if (!emptyAllowed && value.isBlank()) {
			throw new ProtocolException(ProtocolConstants.ERROR_INVALID_FIELD, field + " must not be blank");
		}
		if (value.length() > maxLength) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_OUT_OF_RANGE,
					field + " must not exceed " + maxLength + " characters"
			);
		}
		return value;
	}
}
