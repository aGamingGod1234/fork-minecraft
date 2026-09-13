package dev.agaminggod.arenaagents.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The same accepted/rejected action fixtures run through both Java and coordinator validators. */
public final class PlayerActionSchemaVerification {
	private PlayerActionSchemaVerification() { }

	public static int verify() {
		try (var source = PlayerActionSchemaVerification.class.getResourceAsStream("/player-action-schema-cases.json")) {
			if (source == null) throw new AssertionError("Shared player action schema fixtures are missing");
			var fixtures = JsonParser.parseString(new String(source.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
			int assertions = 0;
			for (var value : fixtures) {
				JsonObject fixture = value.getAsJsonObject();
				String name = fixture.get("name").getAsString();
				boolean expected = fixture.get("valid").getAsBoolean();
				JsonObject arguments = fixture.getAsJsonObject("action").deepCopy();
				ActionType type = ActionType.fromWireName(arguments.remove("type").getAsString()).orElseThrow();
				try {
					ProtocolCodec.validateActionArguments(type, arguments);
					if (!expected) throw new AssertionError("Java accepted rejected shared action case: " + name);
				} catch (ProtocolException rejection) {
					if (expected) throw new AssertionError("Java rejected accepted shared action case: " + name, rejection);
				}
				assertions++;
			}
			return assertions;
		} catch (IOException exception) {
			throw new AssertionError("Shared player action schema fixtures could not be read", exception);
		}
	}
}
