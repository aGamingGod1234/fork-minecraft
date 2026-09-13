package dev.agaminggod.arenaagents.protocol;

import com.google.gson.JsonObject;

public record ActionCommand(
		String commandId,
		ActionType type,
		JsonObject arguments,
		long issuedAtEpochMs
) {
	public ActionCommand {
		commandId = requireCommandId(commandId);
		if (type == null) {
			throw new ProtocolException(ProtocolConstants.ERROR_INVALID_FIELD, "type must not be null");
		}
		if (arguments == null) {
			throw new ProtocolException(ProtocolConstants.ERROR_INVALID_FIELD, "arguments must not be null");
		}
		arguments = ProtocolCodec.validateActionArguments(type, arguments);
		if (issuedAtEpochMs <= 0L) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_OUT_OF_RANGE,
					"issuedAtEpochMs must be positive"
			);
		}
	}

	@Override
	public JsonObject arguments() {
		return arguments.deepCopy();
	}

	private static String requireCommandId(String commandId) {
		if (commandId == null || commandId.isBlank()) {
			throw new ProtocolException(ProtocolConstants.ERROR_INVALID_FIELD, "commandId must not be blank");
		}
		if (commandId.length() > ProtocolConstants.MAX_COMMAND_ID_LENGTH) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_OUT_OF_RANGE,
					"commandId must not exceed " + ProtocolConstants.MAX_COMMAND_ID_LENGTH + " characters"
			);
		}
		return commandId;
	}
}
