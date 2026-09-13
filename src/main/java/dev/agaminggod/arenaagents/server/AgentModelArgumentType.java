package dev.agaminggod.arenaagents.server;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.resources.Identifier;

public final class AgentModelArgumentType implements ArgumentType<String> {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("arenaagents", "model");
	private static final AgentModelArgumentType INSTANCE = new AgentModelArgumentType();
	private static final SingletonArgumentInfo<AgentModelArgumentType> NETWORK_INFO =
			SingletonArgumentInfo.contextFree(AgentModelArgumentType::model);
	private static boolean registered;

	private AgentModelArgumentType() {
	}

	static AgentModelArgumentType model() {
		return INSTANCE;
	}

	static SingletonArgumentInfo<AgentModelArgumentType> networkInfo() {
		return NETWORK_INFO;
	}

	public static synchronized void register() {
		if (registered) return;
		ArgumentTypeRegistry.registerArgumentType(
				ID,
				AgentModelArgumentType.class,
				NETWORK_INFO
		);
		registered = true;
	}

	@Override
	public String parse(StringReader reader) throws CommandSyntaxException {
		if (reader.canRead() && reader.peek() == '"') {
			String quotedModel = reader.readQuotedString();
			if (quotedModel.isEmpty()) {
				throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol().createWithContext(reader, "model");
			}
			return quotedModel;
		}
		int start = reader.getCursor();
		while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
			reader.skip();
		}
		if (reader.getCursor() == start) {
			throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol().createWithContext(reader, "model");
		}
		return reader.getString().substring(start, reader.getCursor());
	}
}
