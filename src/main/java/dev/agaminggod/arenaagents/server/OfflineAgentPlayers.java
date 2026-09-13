package dev.agaminggod.arenaagents.server;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentDeathSnapshot;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Relative;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.LevelResource;

public final class OfflineAgentPlayers {
	private static final Map<AgentId, CachedIdentity> IDENTITIES = new LinkedHashMap<>();

	private OfflineAgentPlayers() {
	}

	public static String playerName(AgentId agentId, AgentProfile profile) {
		return identity(agentId, profile).playerName();
	}

	public static UUID offlineUuid(AgentId agentId, AgentProfile profile) {
		return identity(agentId, profile).offlineUuid();
	}

	/** Drops only immutable derived identity data; live ServerPlayer instances are never cached here. */
	static synchronized void invalidateIdentity(AgentId agentId) {
		IDENTITIES.remove(Objects.requireNonNull(agentId, "agentId must not be null"));
	}

	static synchronized void clearIdentityCache() {
		IDENTITIES.clear();
	}

	private static synchronized CachedIdentity identity(AgentId agentId, AgentProfile profile) {
		AgentId checkedId = Objects.requireNonNull(agentId, "agentId must not be null");
		AgentProfile checkedProfile = Objects.requireNonNull(profile, "profile must not be null");
		CachedIdentity cached = IDENTITIES.get(checkedId);
		if (cached != null && cached.profile().equals(checkedProfile)) return cached;
		String playerName = AgentIdentity.playerName(checkedId, checkedProfile);
		CachedIdentity revised = new CachedIdentity(
				checkedProfile, playerName, AgentIdentity.offlinePlayerUuid(playerName));
		if (!IDENTITIES.containsKey(checkedId) && IDENTITIES.size() >= AgentConstants.MAX_CONFIGURED_AGENTS) {
			IDENTITIES.remove(IDENTITIES.keySet().iterator().next());
		}
		IDENTITIES.put(checkedId, revised);
		return revised;
	}

	static String legacyTransportPlayerName(AgentId agentId, AgentProfile profile) {
		return legacyPlayerNames(agentId, profile).getLast();
	}

	static List<String> legacyPlayerNames(AgentId agentId, AgentProfile profile) {
		return AgentIdentity.legacyPlayerNames(agentId, profile);
	}

	public static void spawn(
			MinecraftServer server,
			AgentId agentId,
			AgentProfile profile,
			Vec3 position,
			float yaw,
			float pitch,
			net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			AgentGameMode gameMode
	) {
		spawn(server, agentId, profile, position, yaw, pitch, dimension, toGameType(gameMode));
	}

	public static void spawn(
			MinecraftServer server,
			AgentId agentId,
			AgentProfile profile,
			Vec3 position,
			float yaw,
			float pitch,
			net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			GameType gameMode
	) {
		CachedIdentity expected = identity(agentId, profile);
		String name = expected.playerName();
		if (server.getPlayerList().getPlayerByName(name) != null || EntityPlayerMPFake.isSpawningPlayer(name)) {
			throw new AgentDomainException("AGENT_PLAYER_EXISTS", "The offline player for this agent already exists");
		}
		UUID uuid = expected.offlineUuid();
		boolean accepted;
		OfflineAgentProfileLookup.begin(name, uuid);
		try {
			accepted = EntityPlayerMPFake.createFake(
					name,
					server,
					position,
					yaw,
					pitch,
					dimension,
					gameMode,
					gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR
			);
		} finally {
			OfflineAgentProfileLookup.end(name, uuid);
		}
		if (!accepted) {
			throw new AgentDomainException("PLAYER_SPAWN_FAILED", "Carpet rejected the offline agent player spawn");
		}
	}

	public static Optional<ServerPlayer> find(MinecraftServer server, AgentId agentId, AgentProfile profile) {
		CachedIdentity expected = identity(agentId, profile);
		UUID expectedUuid = expected.offlineUuid();
		String expectedName = expected.playerName();
		ServerPlayer byUuid = server.getPlayerList().getPlayer(expectedUuid);
		if (isManagedFakePlayer(byUuid, expectedUuid, expectedName)) return Optional.of(byUuid);
		ServerPlayer byName = server.getPlayerList().getPlayerByName(expectedName);
		return isManagedFakePlayer(byName, expectedUuid, expectedName) ? Optional.of(byName) : Optional.empty();
	}

	/** Finds either deployed technical username without mistaking an ordinary player for a managed fake. */
	public static Optional<ServerPlayer> findLegacyPlayer(
			MinecraftServer server,
			AgentId agentId,
			AgentProfile profile
	) {
		Objects.requireNonNull(server, "server must not be null");
		ArrayList<ServerPlayer> matches = new ArrayList<>();
		for (String legacyName : legacyPlayerNames(agentId, profile)) {
			findByIdentity(server, legacyName).filter(player -> !matches.contains(player)).ifPresent(matches::add);
		}
		if (matches.size() > 1) {
			throw new AgentDomainException(
					"AMBIGUOUS_LEGACY_AGENT_PLAYER", "Multiple legacy bodies exist for one Arena agent");
		}
		return matches.stream().findFirst();
	}

	/**
	 * Saves exact UUID-owned progress before the canonical profile is constructed. The legacy body remains connected
	 * and authoritative until {@link #completeConnectedLegacyMigration(LegacyBodyMigration, ServerPlayer)} succeeds.
	 */
	public static Optional<LegacyBodyMigration> stageConnectedLegacyMigration(
			MinecraftServer server,
			AgentId agentId,
			AgentProfile profile
	) {
		ServerPlayer legacy = findLegacyPlayer(server, agentId, profile).orElse(null);
		if (legacy == null) return Optional.empty();
		CachedIdentity canonical = identity(agentId, profile);
		String canonicalName = canonical.playerName();
		UUID canonicalUuid = canonical.offlineUuid();
		legacy.getStats().save();
		legacy.getAdvancements().save();
		copyLegacyIdentityFiles(
				server.getWorldPath(LevelResource.PLAYER_DATA_DIR),
				server.getWorldPath(LevelResource.PLAYER_STATS_DIR),
				server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR),
				legacy.getUUID(), canonicalUuid);
		return Optional.of(new LegacyBodyMigration(legacy, canonicalUuid, canonicalName));
	}

	/** Commits an already staged migration only after the canonical fake player is fully connected. */
	public static ServerPlayer completeConnectedLegacyMigration(
			LegacyBodyMigration migration,
			ServerPlayer canonical
	) {
		Objects.requireNonNull(migration, "migration must not be null");
		Objects.requireNonNull(canonical, "canonical player must not be null");
		ServerPlayer legacy = migration.legacyPlayer();
		if (!isManagedFakePlayer(canonical, migration.canonicalUuid(), migration.canonicalName())) {
			throw new AgentDomainException(
					"INVALID_CANONICAL_AGENT_PLAYER", "Replacement is not the staged canonical fake player");
		}
		if (!(legacy instanceof EntityPlayerMPFake) || legacy.isRemoved()) {
			throw new AgentDomainException(
					"LEGACY_AGENT_PLAYER_GONE", "Legacy player disappeared before migration committed");
		}
		stop(legacy);
		canonical.restoreFrom(legacy, true);
		canonical.teleportTo(
				legacy.level(), legacy.getX(), legacy.getY(), legacy.getZ(), Set.<Relative>of(),
				legacy.getYRot(), legacy.getXRot(), false);
		canonical.setDeltaMovement(legacy.getDeltaMovement());
		copyStats(legacy, canonical);
		copyAdvancements(legacy, canonical);
		copyScoreboard(legacy, canonical);
		canonical.getStats().save();
		canonical.getAdvancements().save();
		remove(legacy);
		return canonical;
	}

	static void copyLegacyIdentityFiles(
			Path playerDataDirectory,
			Path statsDirectory,
			Path advancementsDirectory,
			UUID oldUuid,
			UUID newUuid
	) {
		Objects.requireNonNull(playerDataDirectory, "playerDataDirectory must not be null");
		Objects.requireNonNull(statsDirectory, "statsDirectory must not be null");
		Objects.requireNonNull(advancementsDirectory, "advancementsDirectory must not be null");
		Objects.requireNonNull(oldUuid, "oldUuid must not be null");
		Objects.requireNonNull(newUuid, "newUuid must not be null");
		if (oldUuid.equals(newUuid)) return;
		copyIdentityFile(playerDataDirectory, oldUuid, newUuid, ".dat");
		copyIdentityFile(statsDirectory, oldUuid, newUuid);
		copyIdentityFile(advancementsDirectory, oldUuid, newUuid);
	}

	/** Compatibility helper for callers that only migrate the JSON identity stores. */
	static void copyLegacyIdentityFiles(Path statsDirectory, Path advancementsDirectory, UUID oldUuid, UUID newUuid) {
		Objects.requireNonNull(statsDirectory, "statsDirectory must not be null");
		Objects.requireNonNull(advancementsDirectory, "advancementsDirectory must not be null");
		Objects.requireNonNull(oldUuid, "oldUuid must not be null");
		Objects.requireNonNull(newUuid, "newUuid must not be null");
		if (oldUuid.equals(newUuid)) return;
		copyIdentityFile(statsDirectory, oldUuid, newUuid);
		copyIdentityFile(advancementsDirectory, oldUuid, newUuid);
	}

	private static Optional<ServerPlayer> findByIdentity(MinecraftServer server, String name) {
		UUID uuid = AgentIdentity.offlinePlayerUuid(name);
		ServerPlayer byUuid = server.getPlayerList().getPlayer(uuid);
		if (isManagedFakePlayer(byUuid, uuid, name)) return Optional.of(byUuid);
		ServerPlayer byName = server.getPlayerList().getPlayerByName(name);
		return isManagedFakePlayer(byName, uuid, name) ? Optional.of(byName) : Optional.empty();
	}

	private static void copyIdentityFile(Path directory, UUID oldUuid, UUID newUuid) {
		copyIdentityFile(directory, oldUuid, newUuid, ".json");
	}

	private static void copyIdentityFile(Path directory, UUID oldUuid, UUID newUuid, String extension) {
		Path source = directory.resolve(oldUuid + extension);
		if (!Files.isRegularFile(source)) return;
		Path target = directory.resolve(newUuid + extension);
		Path staged = directory.resolve(newUuid + extension + ".arenaagents-migrating");
		try {
			Files.createDirectories(directory);
			Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			try {
				Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			throw new AgentDomainException(
					"AGENT_IDENTITY_MIGRATION_FAILED", "Could not preserve legacy player progress: " + exception.getMessage());
		} finally {
			try {
				Files.deleteIfExists(staged);
			} catch (IOException ignored) {
				// A later migration attempt replaces the bounded staging file.
			}
		}
	}

	private static void copyStats(ServerPlayer source, ServerPlayer target) {
		for (StatType<?> type : BuiltInRegistries.STAT_TYPE) copyStats(type, source, target);
	}

	private static <T> void copyStats(StatType<T> type, ServerPlayer source, ServerPlayer target) {
		for (Stat<T> stat : type) target.getStats().setValue(target, stat, source.getStats().getValue(stat));
	}

	private static void copyAdvancements(ServerPlayer source, ServerPlayer target) {
		for (AdvancementHolder advancement : source.level().getServer().getAdvancements().getAllAdvancements()) {
			Set<String> completed = new HashSet<>();
			source.getAdvancements().getOrStartProgress(advancement).getCompletedCriteria().forEach(completed::add);
			ArrayList<String> targetCompleted = new ArrayList<>();
			target.getAdvancements().getOrStartProgress(advancement)
					.getCompletedCriteria().forEach(targetCompleted::add);
			for (String criterion : targetCompleted) {
				if (!completed.contains(criterion)) target.getAdvancements().revoke(advancement, criterion);
			}
			for (String criterion : completed) target.getAdvancements().award(advancement, criterion);
		}
	}

	private static void copyScoreboard(ServerPlayer source, ServerPlayer target) {
		var scoreboard = source.level().getScoreboard();
		ScoreHolder sourceHolder = ScoreHolder.forNameOnly(source.getScoreboardName());
		ScoreHolder targetHolder = ScoreHolder.forNameOnly(target.getScoreboardName());
		for (var objective : scoreboard.getObjectives()) {
			var sourceInfo = scoreboard.getPlayerScoreInfo(sourceHolder, objective);
			if (sourceInfo == null) continue;
			ScoreAccess sourceAccess = scoreboard.getOrCreatePlayerScore(sourceHolder, objective);
			ScoreAccess targetAccess = scoreboard.getOrCreatePlayerScore(targetHolder, objective);
			targetAccess.set(sourceInfo.value());
			if (sourceInfo.isLocked()) targetAccess.lock();
			else targetAccess.unlock();
			targetAccess.display(sourceAccess.display());
			targetAccess.numberFormatOverride(sourceInfo.numberFormat());
		}
		var team = scoreboard.getPlayersTeam(source.getScoreboardName());
		if (team != null) scoreboard.addPlayerToTeam(target.getScoreboardName(), team);
	}

	public record LegacyBodyMigration(ServerPlayer legacyPlayer, UUID canonicalUuid, String canonicalName) {
		public LegacyBodyMigration {
			Objects.requireNonNull(legacyPlayer, "legacyPlayer must not be null");
			Objects.requireNonNull(canonicalUuid, "canonicalUuid must not be null");
			canonicalName = Objects.requireNonNull(canonicalName, "canonicalName must not be null");
		}
	}

	static boolean isManagedFakePlayer(ServerPlayer player, AgentId agentId, AgentProfile profile) {
		CachedIdentity expected = identity(agentId, profile);
		return isManagedFakePlayer(player, expected.offlineUuid(), expected.playerName());
	}

	private record CachedIdentity(AgentProfile profile, String playerName, UUID offlineUuid) {
		private CachedIdentity {
			Objects.requireNonNull(profile, "profile must not be null");
			Objects.requireNonNull(playerName, "playerName must not be null");
			Objects.requireNonNull(offlineUuid, "offlineUuid must not be null");
		}
	}

	private static boolean isManagedFakePlayer(ServerPlayer player, UUID expectedUuid, String expectedName) {
		return player != null && matchesManagedIdentity(
				player instanceof EntityPlayerMPFake,
				player.getUUID(),
				player.getGameProfile().name(),
				expectedUuid,
				expectedName
		);
	}

	static boolean matchesManagedIdentity(
			boolean fakePlayer,
			UUID actualUuid,
			String actualName,
			UUID expectedUuid,
			String expectedName
	) {
		return fakePlayer
				&& expectedUuid.equals(actualUuid)
				&& expectedName.equals(actualName);
	}

	public static EntityPlayerActionPack actions(ServerPlayer player) {
		requireFakePlayer(player);
		return ((ServerPlayerInterface) player).getActionPack();
	}

	public static void stop(ServerPlayer player) {
		AgentInputRuntime.clear(player);
		actions(player).stopAll();
		player.stopUsingItem();
	}

	public static void remove(ServerPlayer player) {
		EntityPlayerMPFake fake = requireFakePlayer(player);
		stop(fake);
		fake.kill(Component.literal("Arena agent removed"));
	}

	static void retainConnectedDeath(ServerPlayer player) {
		EntityPlayerMPFake fake = requireFakePlayer(player);
		stop(fake);
		fake.setHealth(0.0F);
	}

	static ServerPlayer respawnConnected(ServerPlayer player) {
		EntityPlayerMPFake fake = requireFakePlayer(player);
		if (fake.isAlive()) {
			throw new AgentDomainException("AGENT_NOT_DEAD", "Only a dead connected agent player can be respawned");
		}
		UUID expectedUuid = fake.getUUID();
		String expectedName = fake.getGameProfile().name();
		var connection = fake.connection;
		connection.handleClientCommand(new ServerboundClientCommandPacket(
				ServerboundClientCommandPacket.Action.PERFORM_RESPAWN
		));
		ServerPlayer replacement = connection.player;
		if (replacement == fake || !isManagedFakePlayer(replacement, expectedUuid, expectedName)
				|| !replacement.isAlive()) {
			throw new AgentDomainException("PLAYER_SPAWN_FAILED", "Vanilla did not replace the connected agent player");
		}
		return replacement;
	}

	private static EntityPlayerMPFake requireFakePlayer(ServerPlayer player) {
		if (player instanceof EntityPlayerMPFake fake) return fake;
		throw new AgentDomainException("INVALID_AGENT_PLAYER", "Arena agent control requires a Carpet fake player");
	}

	/** Revalidates persisted vanilla respawn configuration without requiring the transient dead player. */
	public static VanillaRespawnTarget resolveVanillaRespawn(MinecraftServer server, AgentDeathSnapshot death) {
		java.util.Objects.requireNonNull(server, "server must not be null");
		java.util.Objects.requireNonNull(death, "death must not be null");
		GameType gameMode = GameType.byName(death.gameMode(), null);
		if (gameMode == null) throw new AgentDomainException("INVALID_DEATH_SNAPSHOT", "Unknown saved game mode");
		if (death.respawnDimensionId().isPresent()) {
			var level = server.getAllLevels();
			net.minecraft.server.level.ServerLevel configured = null;
			for (var candidate : level) {
				if (candidate.dimension().identifier().toString().equals(death.respawnDimensionId().orElseThrow())) {
					configured = candidate;
					break;
				}
			}
			if (configured != null) {
				BlockPos pos = BlockPos.containing(
						death.respawnX().orElseThrow(), death.respawnY().orElseThrow(), death.respawnZ().orElseThrow()
				);
				float yaw = death.respawnYaw().orElseThrow();
				float pitch = death.respawnPitch().orElseThrow();
				boolean forced = death.respawnForced().orElseThrow();
				BlockState state = configured.getBlockState(pos);
				if (state.getBlock() instanceof RespawnAnchorBlock
						&& (forced || state.getValue(RespawnAnchorBlock.CHARGE) > 0)
						&& RespawnAnchorBlock.canSetSpawn(configured, pos)) {
					Optional<Vec3> stand = RespawnAnchorBlock.findStandUpPosition(EntityType.PLAYER, configured, pos);
					if (stand.isPresent()) {
						float anchorYaw = calculateRespawnYaw(stand.orElseThrow(), pos);
						Optional<AnchorCharge> anchor = forced ? Optional.empty() : Optional.of(new AnchorCharge(configured, pos, state));
						return new VanillaRespawnTarget(configured, stand.orElseThrow(), anchorYaw, 0.0F, gameMode, false, anchor);
					}
				}
				if (state.getBlock() instanceof BedBlock
						&& configured.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos).canSetSpawn(configured)) {
					Direction facing = state.getValue(BedBlock.FACING);
					Optional<Vec3> stand = BedBlock.findStandUpPosition(EntityType.PLAYER, configured, pos, facing, yaw);
					if (stand.isPresent()) {
						float respawnYaw = calculateRespawnYaw(stand.orElseThrow(), pos);
						return new VanillaRespawnTarget(configured, stand.orElseThrow(), respawnYaw, 0.0F, gameMode, false, Optional.empty());
					}
				}
				if (forced && state.getBlock().isPossibleToRespawnInThis(state)) {
					BlockState above = configured.getBlockState(pos.above());
					if (above.getBlock().isPossibleToRespawnInThis(above)) {
						return new VanillaRespawnTarget(configured, new Vec3(pos.getX() + 0.5D, pos.getY() + 0.1D, pos.getZ() + 0.5D), yaw, pitch, gameMode, false, Optional.empty());
					}
				}
			}
		}
		var fallback = server.findRespawnDimension();
		var data = fallback.getRespawnData();
		return new VanillaRespawnTarget(fallback, data.pos().getBottomCenter(), data.yaw(), data.pitch(), gameMode, true, Optional.empty());
	}

	/** Mirrors vanilla's private RespawnPosAngle look-at calculation. */
	static float calculateRespawnYaw(Vec3 position, BlockPos spawnBlock) {
		Vec3 direction = Vec3.atBottomCenterOf(spawnBlock).subtract(position).normalize();
		return Mth.wrapDegrees((float) (Mth.atan2(direction.z, direction.x) * 57.2957763671875D - 90.0D));
	}

	public record VanillaRespawnTarget(
			net.minecraft.server.level.ServerLevel level, Vec3 position, float yaw, float pitch,
			GameType gameMode, boolean adjustSharedSpawn, Optional<AnchorCharge> anchorCharge
	) {
		public VanillaRespawnTarget {
			java.util.Objects.requireNonNull(level, "level must not be null");
			java.util.Objects.requireNonNull(position, "position must not be null");
			java.util.Objects.requireNonNull(gameMode, "gameMode must not be null");
			java.util.Objects.requireNonNull(anchorCharge, "anchorCharge must not be null");
		}

		public Vec3 finalPosition(ServerPlayer player) {
			return adjustSharedSpawn
					? player.adjustSpawnLocation(level, level.getRespawnData().pos()).getBottomCenter()
					: position;
		}

		public Runnable commitWorldEffects() {
			return anchorCharge.map(AnchorCharge::consume).orElse(() -> { });
		}
	}

	public record AnchorCharge(net.minecraft.server.level.ServerLevel level, BlockPos pos, BlockState expected) {
		public AnchorCharge {
			java.util.Objects.requireNonNull(level, "level must not be null");
			java.util.Objects.requireNonNull(pos, "pos must not be null");
			java.util.Objects.requireNonNull(expected, "expected must not be null");
		}

		Runnable consume() {
			BlockState current = level.getBlockState(pos);
			if (!current.equals(expected) || !(current.getBlock() instanceof RespawnAnchorBlock)
					|| current.getValue(RespawnAnchorBlock.CHARGE) <= 0) {
				throw new AgentDomainException("RESPAWN_TARGET_CHANGED", "Respawn anchor changed before commit");
			}
			BlockState consumed = current.setValue(RespawnAnchorBlock.CHARGE, current.getValue(RespawnAnchorBlock.CHARGE) - 1);
			level.setBlock(pos, consumed, 3);
			return () -> {
				if (level.getBlockState(pos).equals(consumed)) level.setBlock(pos, expected, 3);
			};
		}
	}

	public static GameType toGameType(AgentGameMode gameMode) {
		return switch (gameMode) {
			case SURVIVAL -> GameType.SURVIVAL;
			case CREATIVE -> GameType.CREATIVE;
			case ADVENTURE -> GameType.ADVENTURE;
		};
	}
}
