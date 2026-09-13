package dev.agaminggod.arenaagents.control;

import dev.agaminggod.arenaagents.agent.AgentGoal;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.RespawnPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AgentControlVerification {
	private static final long NOW_EPOCH_MS = 1_750_000_000_000L;
	private static final String AGENT_UUID = "12345678-1234-5678-9abc-123456789abc";

	private AgentControlVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifySnapshotRoundTripAndBounds();
		assertions += verifyProviderPresets();
		assertions += verifyRuntimeCatalogBecomesAuthoritative();
		assertions += verifyCommandConstruction();
		assertions += verifySelectionStability();
		assertions += verifyActionSafety();
		assertions += verifySnapshotOrdering();
		return assertions;
	}

	private static int verifySnapshotRoundTripAndBounds() {
		AgentRecord record = new AgentRecord(
				1,
				new AgentId(UUID.fromString(AGENT_UUID)),
				Optional.of(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")),
				Optional.empty(),
				new AgentProfile("kimi", "kimi-code/k3", "max", Optional.of("Builder"), 2),
				AgentLifecycleState.ACTING,
				Optional.of(AgentGoal.create("Build a safe house", NOW_EPOCH_MS)),
				3L,
				List.of(AgentGoal.create("Collect food", NOW_EPOCH_MS)),
				"Placed the foundation",
				"",
				false,
				RespawnPolicy.PAUSE_UNTIL_RESPAWN,
				Optional.empty(),
				NOW_EPOCH_MS,
				NOW_EPOCH_MS,
				""
		);
		AgentControlSnapshot snapshot = AgentControlSnapshot.fromRecords(true, NOW_EPOCH_MS, List.of(record));
		AgentControlSnapshot decoded = AgentControlSnapshotCodec.decode(AgentControlSnapshotCodec.encode(snapshot));
		AgentControlSnapshotPayload outboundPayload = AgentControlSnapshotPayload.fromSnapshot(snapshot);
		assertTrue(outboundPayload.snapshot() == snapshot, "trusted outbound snapshots skip a JSON parse");
		AgentControlSnapshotPayload inboundPayload = new AgentControlSnapshotPayload(outboundPayload.encodedSnapshot());
		assertTrue(inboundPayload.snapshot() == inboundPayload.snapshot(), "validated inbound snapshots are parsed once");
		expectFailure(
				() -> AgentControlSnapshotCodec.decode("🙂".repeat(AgentControlSnapshotCodec.MAX_ENCODED_BYTES / 2)),
				"snapshot wire limit counts UTF-8 bytes"
		);
		AgentControlAgent agent = decoded.agents().getFirst();

		assertEquals(snapshot, decoded, "snapshot JSON round trip");
		assertEquals(AGENT_UUID, agent.agentId(), "snapshot carries stable full agent ID");
		assertEquals("Builder", agent.displayName(), "snapshot carries optional user name");
		assertEquals(Optional.of("Builder"), AgentWorldNamePolicy.tag(agent),
				"world tags use the exact public name without a provider glyph or model suffix");
		assertEquals(Optional.empty(), AgentWorldNamePolicy.tag(agent(AGENT_UUID, "   ")),
				"blank display names omit the world tag");
		assertEquals("Build a safe house", agent.currentGoal(), "snapshot carries active goal");
		assertEquals(1, agent.queuedGoalCount(), "snapshot carries queue count");
		assertEquals(false, agent.automaticProgress(), "snapshot carries automatic progress preference");
		assertTrue(agent.entityPresent(), "snapshot carries entity presence");
		assertTrue(decoded.automationAvailable(), "legacy ready snapshot reports available automation");
		assertEquals("Automation ready", decoded.automationStatus(), "snapshot carries a human-readable readiness message");
		assertTrue(!snapshot.groupAvailable(), "a single-agent snapshot without saved groups hides group controls");
		AgentControlGroup group = new AgentControlGroup("Builders", List.of(AGENT_UUID));
		AgentControlGroup canonicalGroup = new AgentControlGroup(
				"Canonical", List.of(AGENT_UUID.toUpperCase(java.util.Locale.ROOT))
		);
		assertEquals(List.of(AGENT_UUID), canonicalGroup.memberIds(),
				"saved group canonicalizes persistent identity spelling");
		expectFailure(() -> new AgentControlGroup(
				"Duplicate identities", List.of(AGENT_UUID, AGENT_UUID.toUpperCase(java.util.Locale.ROOT))
		), "saved group rejects UUID casing duplicates");
		AgentControlSnapshot grouped = new AgentControlSnapshot(
				AgentControlSnapshot.SCHEMA_VERSION,
				true,
				true,
				"Automation ready",
				NOW_EPOCH_MS,
				List.of(agent),
				List.of(group),
				AgentControlCatalog.currentOptions()
		);
		assertEquals(List.of(group), AgentControlSnapshotCodec.decode(
				AgentControlSnapshotCodec.encode(grouped)).groups(), "snapshot carries saved identity groups");
		assertTrue(grouped.groupAvailable(), "a saved group enables group controls for one agent");
		expectFailure(() -> AgentControlSnapshotCodec.decode("{\"schemaVersion\":999}"), "unsupported snapshot schema");
		expectFailure(
				() -> new AgentControlSnapshot(true, NOW_EPOCH_MS, java.util.Collections.nCopies(17, agent)),
				"snapshot agent bound"
		);
		return 18;
	}

	private static int verifyProviderPresets() {
		assertEquals(List.of("codex", "gemini", "kimi", "cursor"), AgentControlCatalog.providers(), "provider order");
		assertEquals("gpt-5.6-luna", AgentControlCatalog.defaultModel("codex"), "Codex model default");
		assertEquals("xhigh", AgentControlCatalog.defaultReasoning("codex", "gpt-5.6-luna"), "Codex reasoning default");
		assertEquals("fast", AgentControlCatalog.defaultServiceTier("codex", "gpt-5.6-luna"), "Codex speed default");
		assertEquals("gemini-3.1-pro", AgentControlCatalog.defaultModel("gemini"), "Gemini model default");
		assertEquals("thinking", AgentControlCatalog.defaultReasoning("gemini", "claude-sonnet-4-6"),
				"Claude actors use the Gemini catalog's model-specific reasoning");
		assertEquals("medium", AgentControlCatalog.defaultReasoning("gemini", "gpt-oss-120b"),
				"GPT OSS actors use their supported reasoning default");
		assertEquals("priority", AgentControlCatalog.defaultServiceTier("gemini", "gemini-3.1-pro"),
				"non-Codex speed default");
		assertEquals("kimi-code/k3", AgentControlCatalog.defaultModel("kimi"), "Kimi model default");
		assertEquals(List.of("high", "low"), AgentControlCatalog.reasoningEfforts("gemini", "gemini-3.1-pro"),
				"Gemini Pro efforts");
		assertEquals(List.of("high", "medium", "low"),
				AgentControlCatalog.reasoningEfforts("gemini", "gemini-3.6-flash"), "Gemini Flash efforts");
		assertTrue(AgentControlCatalog.models("kimi").contains("kimi-code/k3-256k"),
				"Kimi live 256k model preset");
		assertEquals(List.of("low", "high", "max"), AgentControlCatalog.reasoningEfforts("kimi", "kimi-code/k3-256k"),
				"Kimi K3-256k efforts");
		assertEquals(List.of("low", "high", "max"), AgentControlCatalog.reasoningEfforts("kimi", "kimi-code/k3"),
				"Kimi K3 efforts");
		assertEquals(List.of("high"), AgentControlCatalog.reasoningEfforts("kimi", "kimi-code/kimi-for-coding"),
				"Kimi fixed effort");
		assertEquals("K2.7 Coding Highspeed",
				AgentControlCatalog.displayName("kimi", "kimi-code/kimi-for-coding-highspeed"),
				"Kimi aliases use the installed CLI display name instead of a guessed label");
		assertEquals(List.of("low", "medium", "high", "xhigh", "max", "ultra"),
				AgentControlCatalog.reasoningEfforts("codex", "gpt-5.6-sol"), "Codex effort choices");
		assertEquals(List.of("priority", "fast"), AgentControlCatalog.serviceTiers("codex", "gpt-5.6-sol"),
				"Codex speed choices");
		assertTrue(AgentControlCatalog.hasSpeedMode("codex", "gpt-5.6-sol"),
				"Codex exposes speed mode only when the live profile advertises it");
		assertTrue(!AgentControlCatalog.hasSpeedMode("gemini", "gemini-3.1-pro"),
				"providers without a speed capability do not show a fake speed choice");
		expectFailure(() -> AgentControlCatalog.defaultModel("unknown"), "unknown provider");
		return 20;
	}

	private static int verifyCommandConstruction() {
		assertEquals(
				"codex summon-configured codex gpt-5.6-sol high fast survival \"Builder One\"",
				AgentControlCommandBuilder.summon("codex", "gpt-5.6-sol", "high", "Builder One"),
				"Codex summon command"
		);
		assertEquals(
				"codex summon-configured kimi \"kimi-code/k3\" max priority survival Scout",
				AgentControlCommandBuilder.summon("kimi", "kimi-code/k3", "max", "Scout"),
				"Kimi summon command"
		);
		assertEquals(
				"codex summon-configured codex gpt-5.6-sol ultra fast survival Speedy",
				AgentControlCommandBuilder.summon(
						"codex", "gpt-5.6-sol", "ultra", "fast", "Speedy", AgentGameMode.SURVIVAL),
				"Codex fast-mode command"
		);
		assertEquals(
				"codex start " + AGENT_UUID + " Build a safe shelter",
				AgentControlCommandBuilder.prompt("start", AGENT_UUID, "  Build\na safe\tshelter  "),
				"prompt whitespace normalization"
		);
		assertEquals(
				"codex stop " + AGENT_UUID,
				AgentControlCommandBuilder.agent("stop", AGENT_UUID),
				"agent lifecycle command"
		);
		assertEquals(
				"codex respawn " + AGENT_UUID,
				AgentControlCommandBuilder.agent("respawn", AGENT_UUID),
				"dead-agent respawn command"
		);
		assertEquals(
				"codex group save Builders " + AGENT_UUID,
				AgentControlCommandBuilder.saveGroup("Builders", List.of(AGENT_UUID)),
				"saved group command"
		);
		assertEquals("codex group spawn Builders", AgentControlCommandBuilder.spawnGroup("Builders"),
				"spawn saved group command");
		assertEquals("codex group delete Builders", AgentControlCommandBuilder.deleteGroup("Builders"),
				"delete saved group command");
		expectFailure(
				() -> AgentControlCommandBuilder.agent("remove;op", AGENT_UUID),
				"operation allowlist"
		);
		expectFailure(
				() -> AgentControlCommandBuilder.prompt("start", AGENT_UUID, " "),
				"blank prompt"
		);
		return 11;
	}

	private static int verifySelectionStability() {
		AgentControlAgent first = agent(AGENT_UUID, "First");
		AgentControlAgent second = agent("87654321-4321-8765-cba9-987654321abc", "Second");
		assertEquals(first.agentId(), AgentControlSelection.resolve("", List.of(first, second)), "select first initially");
		assertEquals(second.agentId(), AgentControlSelection.resolve(second.agentId(), List.of(first, second)),
				"retain existing selection");
		assertEquals(first.agentId(), AgentControlSelection.resolve("missing", List.of(first, second)),
				"fall back after removal");
		assertEquals("", AgentControlSelection.resolve(first.agentId(), List.of()), "clear empty selection");
		assertEquals(first.agentId(), AgentControlSelection.move(second.agentId(), -1, List.of(first, second)),
				"previous moves both the visual anchor and command selection");
		assertEquals(second.agentId(), AgentControlSelection.move(first.agentId(), 1, List.of(first, second)),
				"next moves both the visual anchor and command selection");
		assertEquals("Remove 2 agents? First and Second and their saved state will be removed.",
				AgentControlSelection.removalDescription(List.of(first, second)),
				"batch removal confirmation identifies the exact destructive scope");
		assertEquals(second.agentId(), AgentControlSelection.resolveSelected(
				"missing", java.util.Set.of(second.agentId()), List.of(first, second)
		), "snapshot refresh keeps the visual anchor inside the command selection");
		List<AgentControlGroup> groups = List.of(
				new AgentControlGroup("Builders", List.of(first.agentId(), second.agentId())),
				new AgentControlGroup("Scouts", List.of(second.agentId()))
		);
		assertEquals("Builders", AgentControlGroupSelection.resolve("", groups),
				"first saved group is selected initially");
		assertEquals("Scouts", AgentControlGroupSelection.resolve("Scouts", groups),
				"saved group selection survives snapshot refresh");
		assertEquals("Builders", AgentControlGroupSelection.resolve("Deleted", groups),
				"deleted saved group falls back to the first roster");
		assertEquals(List.of(first.agentId(), second.agentId()),
				AgentControlGroupSelection.members("Builders", groups),
				"loading a saved group restores its ordered persistent identities");
		return 12;
	}

	private static int verifyRuntimeCatalogBecomesAuthoritative() {
		List<AgentControlModelOption> live = List.of(
				new AgentControlModelOption(
						"codex", "gpt-future", "GPT Future", List.of("medium", "ultra"), List.of("priority", "fast")
				),
				new AgentControlModelOption(
						"kimi", "kimi-code/live", "Kimi Live", List.of("high"), List.of()
				)
		);
		try {
			AgentControlCatalog.installRuntimeCatalog(live);
			assertEquals(List.of("codex", "kimi"), AgentControlCatalog.providers(),
					"runtime catalog controls provider order");
			assertEquals(List.of("gpt-future"), AgentControlCatalog.models("codex"),
					"runtime catalog controls model choices");
			assertEquals("GPT Future", AgentControlCatalog.displayName("codex", "gpt-future"),
					"runtime catalog preserves provider display names");
			assertEquals(List.of("medium", "ultra"), AgentControlCatalog.reasoningEfforts("codex", "gpt-future"),
					"runtime catalog controls reasoning choices");
			assertEquals(List.of("priority", "fast"), AgentControlCatalog.serviceTiers("codex", "gpt-future"),
					"runtime catalog controls speed choices");
			AgentControlSnapshot snapshot = new AgentControlSnapshot(
					AgentControlSnapshot.SCHEMA_VERSION, true, true, "Automation ready", NOW_EPOCH_MS, List.of(), live
			);
			assertEquals(live, AgentControlSnapshotCodec.decode(AgentControlSnapshotCodec.encode(snapshot)).catalog(),
					"runtime catalog survives the client snapshot wire format");
		} finally {
			AgentControlCatalog.resetRuntimeCatalog();
		}
		assertEquals("gpt-5.6-luna", AgentControlCatalog.defaultModel("codex"),
				"disconnect reset restores the safe fallback catalog");
		assertEquals(List.of("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol", "gpt-5.6-sol-wm"),
				AgentControlCatalog.models("codex").subList(0, 4),
				"fallback model sequence remains Luna, Terra, Sol, Sol WM");
		assertTrue(!AgentControlCatalog.models("codex").contains("codex-auto-review"),
				"fallback model sequence omits provider-internal hidden models");
		return 9;
	}

	private static int verifyActionSafety() {
		AgentControlAgent idle = agent(AGENT_UUID, "Idle");
		AgentControlAgent paused = agent(
				"87654321-4321-8765-cba9-987654321abc", "Paused", "PAUSED", "Build shelter"
		);
		AgentControlAgent acting = agent(
				"aaaaaaaa-4321-8765-cba9-987654321abc", "Acting", "ACTING", "Gather food"
		);
		AgentControlAgent dead = agent(
				"bbbbbbbb-4321-8765-cba9-987654321abc", "Dead", "DEAD", ""
		);
		AgentControlAgent disconnected = agent(
				"cccccccc-4321-8765-cba9-987654321abc", "Disconnected", "DISCONNECTED", "Gather food"
		);
		assertTrue(AgentControlActions.supports(idle, "start"), "idle agent can start");
		assertTrue(!AgentControlActions.supports(acting, "start"), "acting agent cannot start another current goal");
		assertTrue(AgentControlActions.supports(acting, "steer"), "active goal can be steered");
		assertTrue(AgentControlActions.supports(paused, "resume"), "paused agent can resume");
		assertTrue(AgentControlActions.supports(disconnected, "resume"), "disconnected agent can resume after coordinator recovery");
		assertTrue(AgentControlActions.supports(dead, "respawn"), "dead agent exposes operator respawn");
		assertTrue(!AgentControlActions.supports(idle, "respawn"), "living agent has no respawn action");
		assertTrue(!AgentControlActions.everySupports(List.of(paused, acting), "resume"),
				"batch action is disabled when any selected agent is incompatible");
		assertEquals("Starting up...", AgentControlPresentation.stateLabel("STARTING"),
				"technical starting state is presented as plain language");
		assertEquals("Working", AgentControlPresentation.stateLabel("ACTING"),
				"technical acting state is presented as plain language");
		assertEquals("Dead - ready to respawn", AgentControlPresentation.stateLabel("DEAD"),
				"dead state advertises the operator respawn affordance");
		assertEquals("GPT 5.6 Sol | High", AgentControlPresentation.profileLabel(idle),
				"agent profile copy is human readable");
		assertEquals("Fast mode", AgentControlPresentation.speedLabel("fast"),
				"fast service tier has a human-readable label");
		assertEquals("Normal", AgentControlPresentation.speedLabel("priority"),
				"provider-native priority tier is presented as the normal player speed");
		return 12;
	}

	private static int verifySnapshotOrdering() {
		AgentControlSnapshotStore store = new AgentControlSnapshotStore();
		AgentControlSnapshot newer = new AgentControlSnapshot(true, NOW_EPOCH_MS + 2L, List.of());
		AgentControlSnapshot older = new AgentControlSnapshot(false, NOW_EPOCH_MS + 1L, List.of());
		assertTrue(store.accept(newer), "new snapshot accepted");
		assertTrue(!store.accept(older), "stale snapshot rejected");
		assertEquals(newer, store.current().orElseThrow(), "stale snapshot does not replace state");
		store.clear();
		assertTrue(store.current().isEmpty(), "disconnect clears snapshot state");
		return 4;
	}

	private static AgentControlAgent agent(String id, String name) {
		return agent(id, name, "IDLE", "");
	}

	private static AgentControlAgent agent(String id, String name, String state, String currentGoal) {
		return new AgentControlAgent(
				id,
				id.substring(0, 8),
				name,
				"codex",
				"gpt-5.6-sol",
				"high",
				"SolCyan_" + id.substring(0, 8).toUpperCase(java.util.Locale.ROOT),
				0,
				state,
				currentGoal,
				0,
				"",
				"",
				true,
				true
		);
	}

	private static void expectFailure(Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + " should fail");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private static void assertTrue(boolean actual, String label) {
		if (!actual) {
			throw new AssertionError(label);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
