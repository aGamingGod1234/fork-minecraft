package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

public final class AgentSpawnPlacementVerification {
	private AgentSpawnPlacementVerification() {
	}

	public static int verify() {
		Vec3 origin = new Vec3(10.0D, 64.0D, -4.0D);
		Vec3 forward = AgentSpawnPlacement.inFrontOf(origin, new Vec3(0.6D, -0.8D, 0.0D));
		assertCoordinate(forward.x, 12.0D, "forward x");
		assertCoordinate(forward.y, 64.0D, "forward y");
		assertCoordinate(forward.z, -4.0D, "forward z");

		Vec3 verticalFallback = AgentSpawnPlacement.inFrontOf(origin, new Vec3(0.0D, -1.0D, 0.0D));
		assertCoordinate(verticalFallback.x, 10.0D, "fallback x");
		assertCoordinate(verticalFallback.y, 64.0D, "fallback y");
		assertCoordinate(verticalFallback.z, -2.0D, "fallback z");
		if (!origin.equals(new Vec3(10.0D, 64.0D, -4.0D))) {
			throw new AssertionError("spawn placement mutated the source position");
		}
		List<Vec3> candidates = AgentSpawnPlacement.candidates(origin, new Vec3(0.0D, 0.0D, 1.0D));
		if (candidates.size() != 20) throw new AssertionError("expected 20 bounded spawn candidates");
		if (!candidates.getFirst().equals(new Vec3(10.0D, 64.0D, -2.0D))) {
			throw new AssertionError("first candidate must remain directly in front of the player");
		}
		if (new HashSet<>(candidates).size() != candidates.size()) {
			throw new AssertionError("spawn candidate grid contains duplicate positions");
		}
		AgentId id = new AgentId(UUID.fromString("193a9add-1234-5678-9abc-123456789abc"));
		AgentProfile profile = new AgentProfile(
				"codex", "gpt-5.6-sol", "high", "fast", java.util.Optional.empty(), 0,
				AgentGameMode.SURVIVAL);
		String playerName = OfflineAgentPlayers.playerName(id, profile);
		if (playerName.length() > 16) {
			throw new AssertionError("offline agent username exceeds Minecraft's limit: " + playerName);
		}
		if (!playerName.equals(OfflineAgentPlayers.playerName(id, profile))) {
			throw new AssertionError("offline agent username is not stable: " + playerName);
		}
		if (AgentIdentity.skinForPlayerName(playerName).isPresent()) {
			throw new AssertionError("public player name must not impersonate the legacy skin transport: " + playerName);
		}
		return 13;
	}

	private static void assertCoordinate(double actual, double expected, String label) {
		if (Math.abs(actual - expected) > 1.0E-9D) {
			throw new AssertionError(label + " expected " + expected + " but was " + actual);
		}
	}
}
