package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.RespawnPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

public final class NativeAgentWhisperTargetsVerification {
	private static final AgentId AGENT_ID = new AgentId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
	private static final UUID ENTITY_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

	private NativeAgentWhisperTargetsVerification() {
	}

	public static int verify() {
		AgentRecord loaded = record(AGENT_ID, Optional.of(ENTITY_ID));
		AgentRecord unloaded = record(
				new AgentId(UUID.fromString("44444444-4444-4444-4444-444444444444")),
				Optional.empty()
		);
		NativeAgentWhisperTargets targets = NativeAgentWhisperTargets.fromRecords(List.of(loaded, unloaded));

		assertEquals(Optional.of(AGENT_ID), targets.resolve(ENTITY_ID), "loaded fake player resolves to its stable agent ID");
		assertEquals(Optional.empty(), targets.resolve(UUID.randomUUID()), "ordinary player is not intercepted");
		assertEquals(1, targets.size(), "unloaded agents are not treated as online whisper recipients");
		try (var input = NativeAgentWhisperTargetsVerification.class.getClassLoader()
				.getResourceAsStream("arenaagents.mixins.json")) {
			if (input == null) throw new AssertionError("server mixin configuration is missing");
			String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			assertEquals(true, config.contains("MsgCommandMixin"),
					"native whisper mixin is registered for /msg, /tell, and /w");
		} catch (java.io.IOException exception) {
			throw new AssertionError("could not read server mixin configuration", exception);
		}
		return 4;
	}

	private static AgentRecord record(AgentId agentId, Optional<UUID> entityUuid) {
		return new AgentRecord(
				1,
				agentId,
				entityUuid,
				Optional.empty(),
				new AgentProfile("codex", "gpt-5.6-luna", "xhigh", "priority", Optional.of("Flint"), 0, AgentGameMode.SURVIVAL),
				AgentLifecycleState.IDLE,
				Optional.empty(),
				0L,
				List.of(),
				"",
				"",
				true,
				RespawnPolicy.PAUSE_UNTIL_RESPAWN,
				Optional.empty(),
				1_750_000_000_000L,
				1_750_000_000_000L,
				""
		);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
