package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import java.io.IOException;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class OfflineAgentPlayersVerification {
	private OfflineAgentPlayersVerification() {
	}

	public static void main(String[] args) {
		System.out.printf("PASS: %d offline agent identity migration assertions%n", verify());
	}

	public static int verify() {
		assertEquals(-180.0F,
				OfflineAgentPlayers.calculateRespawnYaw(new Vec3(0.5D, 65.0D, 1.5D), new BlockPos(0, 64, 0)),
				"respawn yaw matches vanilla look-at calculation");
		assertEquals(90.0F,
				OfflineAgentPlayers.calculateRespawnYaw(new Vec3(1.5D, 65.0D, 0.5D), new BlockPos(0, 64, 0)),
				"respawn yaw points toward the saved bed or anchor");
		String expectedName = "Sol2_C7CA442D";
		UUID expectedUuid = UUID.nameUUIDFromBytes(
				("OfflinePlayer:" + expectedName).getBytes(StandardCharsets.UTF_8));
		assertTrue(OfflineAgentPlayers.matchesManagedIdentity(
				true, expectedUuid, expectedName, expectedUuid, expectedName),
				"exact Carpet fake-player identity is accepted");
		assertTrue(!OfflineAgentPlayers.matchesManagedIdentity(
				false, expectedUuid, expectedName, expectedUuid, expectedName),
				"human player with a reserved UUID and name is rejected");
		assertTrue(!OfflineAgentPlayers.matchesManagedIdentity(
				true, UUID.randomUUID(), expectedName, expectedUuid, expectedName),
				"fake player with only the reserved name is rejected");
		assertTrue(!OfflineAgentPlayers.matchesManagedIdentity(
				true, expectedUuid, "ordinary_player", expectedUuid, expectedName),
				"fake player with only the reserved UUID is rejected");
		AgentId agentId = new AgentId(UUID.fromString("193a9add-1234-5678-9abc-123456789abc"));
		AgentProfile profile = new AgentProfile(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.empty(), 2, AgentGameMode.SURVIVAL);
		AgentProfile renamedProfile = new AgentProfile(
				"codex", "gpt-5.6-sol", "high", "priority", Optional.of("RenamedAgent"), 2, AgentGameMode.SURVIVAL);
		assertEquals("RenamedAgent", OfflineAgentPlayers.playerName(agentId, renamedProfile),
				"profile changes replace cached technical names");
		assertEquals(OfflineAgentPlayers.offlineUuid(agentId, renamedProfile),
				UUID.nameUUIDFromBytes(("OfflinePlayer:RenamedAgent").getBytes(StandardCharsets.UTF_8)),
				"profile changes replace cached offline UUIDs");
		assertEquals("c02_193A9ADD", OfflineAgentPlayers.legacyTransportPlayerName(agentId, profile),
				"the previous installed username remains discoverable for one-time migration");
		assertEquals(List.of("Sol2_C7CA442D", "c02_193A9ADD"),
				OfflineAgentPlayers.legacyPlayerNames(agentId, profile),
				"both deployed fake-player handle generations are migration candidates");
		verifyIdentityFileMigration();
		return 12;
	}

	private static void verifyIdentityFileMigration() {
		try {
			Path root = Files.createTempDirectory("arena-agent-identity-migration-");
			Path playerData = Files.createDirectories(root.resolve("playerdata"));
			Path stats = Files.createDirectories(root.resolve("stats"));
			Path advancements = Files.createDirectories(root.resolve("advancements"));
			UUID oldUuid = UUID.fromString("193a9add-1234-5678-9abc-123456789abc");
			UUID newUuid = UUID.fromString("293a9add-1234-5678-9abc-123456789abc");
			Files.writeString(playerData.resolve(oldUuid + ".dat"), "player progress");
			Files.writeString(stats.resolve(oldUuid + ".json"), "{\"mined\":17}");
			Files.writeString(advancements.resolve(oldUuid + ".json"), "{\"story/root\":true}");
			Files.writeString(stats.resolve(newUuid + ".json"), "stale");
			OfflineAgentPlayers.copyLegacyIdentityFiles(playerData, stats, advancements, oldUuid, newUuid);
			assertEquals("player progress", Files.readString(playerData.resolve(newUuid + ".dat")),
					"playerdata migrates before the canonical profile is constructed");
			assertEquals("{\"mined\":17}", Files.readString(stats.resolve(newUuid + ".json")),
					"stats migrate before the canonical profile is constructed");
			assertEquals("{\"story/root\":true}", Files.readString(advancements.resolve(newUuid + ".json")),
					"advancement criteria and timestamps migrate before profile construction");
			assertTrue(Files.exists(stats.resolve(oldUuid + ".json")),
					"staging is recoverable and does not orphan the connected legacy body");
			try (var paths = Files.walk(root)) {
				paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException exception) {
						throw new RuntimeException(exception);
					}
				});
			}
		} catch (IOException exception) {
			throw new AssertionError("identity-file migration fixture failed", exception);
		}
	}

	private static void assertEquals(float expected, float actual, String label) {
		if (Math.abs(expected - actual) > 0.001F) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertEquals(String expected, String actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}
}
