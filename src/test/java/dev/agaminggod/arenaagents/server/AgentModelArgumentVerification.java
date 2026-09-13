package dev.agaminggod.arenaagents.server;

import com.mojang.brigadier.StringReader;

public final class AgentModelArgumentVerification {
	private AgentModelArgumentVerification() {
	}

	public static int verify() throws Exception {
		StringReader reader = new StringReader("kimi-code/k3 high");
		String model = AgentModelArgumentType.model().parse(reader);
		if (!"kimi-code/k3".equals(model)) throw new AssertionError("slash model token was not preserved: " + model);
		if (reader.getCursor() != "kimi-code/k3".length()) throw new AssertionError("model parser consumed the reasoning token");
		StringReader quotedReader = new StringReader("\"kimi-code/k3\" high");
		String quotedModel = AgentModelArgumentType.model().parse(quotedReader);
		if (!"kimi-code/k3".equals(quotedModel)) throw new AssertionError("quoted model token was not unwrapped: " + quotedModel);
		if (quotedReader.getCursor() != "\"kimi-code/k3\"".length()) throw new AssertionError("quoted model parser consumed the reasoning token");
		if (AgentModelArgumentType.networkInfo() == null) {
			throw new AssertionError("model argument network serializer was not defined");
		}
		if (AgentModelArgumentType.networkInfo().unpack(AgentModelArgumentType.model()).type()
				!= AgentModelArgumentType.networkInfo()) {
			throw new AssertionError("model argument serializer template did not retain its type");
		}
		return 6;
	}
}
