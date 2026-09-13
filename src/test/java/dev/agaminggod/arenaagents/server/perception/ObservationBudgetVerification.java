package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class ObservationBudgetVerification {
	private ObservationBudgetVerification() {
	}

	public static int verify() {
		JsonObject previous = observation(20.0D, false, 0.0D, "idle");
		JsonObject current = observation(18.0D, true, 2.0D, "mine");
		AttentionFactDelta delta = AttentionFactDelta.between(previous, current, 7L, 123L);
		assertTrue(delta.attention(), "material factual changes gain attention");
		assertEquals(7L, delta.eventSequence(), "event sequence is retained exactly");
		assertTrue(delta.changedFacts().contains("player.health"), "health delta is factual");
		assertTrue(delta.changedFacts().contains("player.onFire"), "fire delta is factual");
		assertFalse(delta.changedFacts().contains("player.fallDistance"),
				"ordinary fall progress stays quiet below the hazard threshold");
		assertFalse(delta.changedFacts().contains("currentAction"),
				"action lifecycle is delivered by typed action events instead of attention");
		assertFalse(delta.changedFacts().stream().anyMatch(path -> path.contains("danger") || path.contains("flee") || path.contains("fight")),
				"deltas do not invent tactical labels");
		JsonObject movingBefore = movingObservation(0.0D, 0.0D, "minecraft:air", 8.0D);
		JsonObject movingAfter = movingObservation(1.0D, 15.0D, "minecraft:wooden_pickaxe", 7.0D);
		AttentionFactDelta movementDelta = AttentionFactDelta.between(movingBefore, movingAfter, 8L, 124L);
		assertFalse(movementDelta.attention(), "an active action does not interrupt itself with movement observations");
		assertEquals(List.of(), movementDelta.changedFacts(), "self-generated action churn has no attention facts");
		JsonObject factualBefore = observation(20.0D, false, 0.0D, "idle");
		JsonObject factualAfter = factualBefore.deepCopy();
		factualAfter.getAsJsonObject("player").addProperty("health", 18.0D);
		factualAfter.getAsJsonObject("inventory").addProperty("selectedItem", "minecraft:torch");
		factualAfter.getAsJsonArray("entities").add(entity(1));
		factualAfter.getAsJsonArray("blocks").add(block(1));
		factualAfter.getAsJsonObject("world").addProperty("raining", true);
		AttentionFactDelta worldDelta = AttentionFactDelta.between(factualBefore, factualAfter, 8L, 124L);
		assertTrue(worldDelta.attention(), "idle observations publish non-action factual changes");
		assertTrue(worldDelta.changedFacts().contains("player.health"), "damage changes are eligible without action progress");
		assertTrue(worldDelta.changedFacts().contains("inventory"), "inventory changes are eligible without action progress");
		assertTrue(worldDelta.changedFacts().contains("entities.00000000-0000-0000-0000-000000000001"),
				"entity changes are eligible without action progress");
		assertFalse(worldDelta.changedFacts().stream().anyMatch(path -> path.startsWith("blocks")),
				"ordinary block visibility churn stays quiet");
		assertFalse(worldDelta.changedFacts().contains("world"), "weather changes stay quiet");
		AttentionFactDelta initialDelta = AttentionFactDelta.between(null, current, 1L, 123L);
		assertFalse(initialDelta.attention(), "initial observation is not attention");
		assertEquals(List.of(), initialDelta.changedFacts(), "initial observation has no changed facts");
		JsonObject heartbeat = current.deepCopy();
		heartbeat.getAsJsonObject("world").addProperty("gameTime", 99L);
		heartbeat.getAsJsonObject("world").addProperty("dayTime", 99L);
		AttentionFactDelta timeOnly = AttentionFactDelta.between(current, heartbeat, 8L, 124L);
		assertFalse(timeOnly.attention(), "world clock heartbeat is not attention");
		assertEquals(List.of(), timeOnly.changedFacts(), "world clock heartbeat has no changed facts");
		JsonObject crowdedBefore = observation(20.0D, false, 0.0D, "idle");
		JsonObject crowdedAfter = observation(20.0D, false, 0.0D, "idle");
		for (int index = 0; index < 300; index++) {
			crowdedBefore.getAsJsonArray("entities").add(entity(index));
			crowdedAfter.getAsJsonArray("entities").add(entity(index + 300));
		}
		for (int index = 0; index < 128; index++) {
			crowdedBefore.getAsJsonArray("blocks").add(block(index));
			crowdedAfter.getAsJsonArray("blocks").add(block(index + 128));
		}
		AttentionFactDelta crowded = AttentionFactDelta.between(crowdedBefore, crowdedAfter, 9L, 125L);
		assertTrue(crowded.changedFacts().size() <= 256, "changed facts remain protocol bounded at maximum disjoint entity and block changes");
		assertTrue(crowded.changedFacts().contains("entities"), "entity overflow coalesces to a factual aggregate");
		assertFalse(crowded.changedFacts().contains("blocks"), "ordinary block visibility changes stay quiet");
		MultiplexedServerBridge.PublishedObservationState publication = new MultiplexedServerBridge.PublishedObservationState(16);
		AgentId retryAgent = AgentId.parse("01234567-89ab-cdef-0123-456789abcdef");
		publication.commit(retryAgent, previous);
		AttentionFactDelta failedPublication = publication.delta(retryAgent, current, 10L, 126L);
		assertTrue(failedPublication.attention(), "failed enqueue sees the material change");
		assertTrue(publication.markDirty(retryAgent), "failed enqueue retains a bounded retry marker");
		AttentionFactDelta retryPublication = publication.delta(retryAgent, current, 11L, 127L);
		assertTrue(retryPublication.attention() && retryPublication.changedFacts().contains("player.health"),
				"retry retains the last delivered baseline and factual material change");
		publication.commit(retryAgent, current);
		assertFalse(publication.delta(retryAgent, current, 12L, 128L).attention(), "successful retry advances the delivered baseline");
		MultiplexedServerBridge.PublishedObservationState boundedPublication =
				new MultiplexedServerBridge.PublishedObservationState(AgentConstants.DEFAULT_AGENT_LIMIT);
		for (int index = 0; index < AgentConstants.DEFAULT_AGENT_LIMIT; index++) {
			boundedPublication.markDirty(AgentId.parse(String.format("00000000-0000-0000-0000-%012d", index + 100)));
		}
		AgentId overflowRetry = AgentId.parse("00000000-0000-0000-0000-000000000116");
		assertFalse(boundedPublication.markDirty(overflowRetry), "failed deliveries stop at the bounded retry capacity");
		assertTrue(boundedPublication.markDirty(AgentId.parse("00000000-0000-0000-0000-000000000100")),
				"a repeated failed delivery remains retry-safe without another marker");
		assertEquals(AgentConstants.DEFAULT_AGENT_LIMIT, boundedPublication.retainedCount(),
				"failed-delivery retry markers remain bounded");
		boundedPublication.clear();
		assertEquals(0, boundedPublication.retainedCount(),
				"session loss clears old delivered baselines before reconnect");
		ObservationDispatchQueue<AgentId> removedQueue = new ObservationDispatchQueue<>(16, 8);
		removedQueue.offer(retryAgent);
		publication.markDirty(retryAgent);
		assertTrue(removedQueue.remove(retryAgent), "removed agent leaves pending observation queue");
		publication.remove(retryAgent);
		List<AgentId> removedDrains = new ArrayList<>();
		for (int drain = 0; drain < 8; drain++) removedQueue.drain(removedDrains::add);
		assertEquals(List.of(), removedDrains, "removed pending agent never retries across later drains");
		assertEquals(0, publication.retainedCount(), "removed agent leaves no delivered or dirty publication state");

		ObservationDispatchQueue<String> queue = new ObservationDispatchQueue<>(3, 2);
		assertTrue(queue.offer("agent-a"), "first observation request is queued");
		assertFalse(queue.offer("agent-a"), "duplicate observation request is coalesced");
		queue.offer("agent-b");
		queue.offer("agent-c");
		assertThrows(() -> queue.offer("agent-d"), "distinct request beyond capacity fails closed");
		List<String> first = new ArrayList<>();
		queue.drain(first::add);
		assertEquals(List.of("agent-a", "agent-b"), first, "drain is FIFO and tick bounded");
		List<String> second = new ArrayList<>();
		queue.drain(second::add);
		assertEquals(List.of("agent-c"), second, "remaining observation is deferred to the next drain");
		queue.offer("stale-agent");
		queue.clear();
		assertEquals(0, queue.pendingCount(), "session loss clears stale queued observations before reconnect");

		ObservationSectionCache<String, JsonObject> cache = new ObservationSectionCache<>(2, 1L, JsonObject::deepCopy);
		AtomicInteger loads = new AtomicInteger();
		JsonObject initial = cache.getOrCompute("agent-a", 100L, () -> value("v" + loads.incrementAndGet()));
		initial.addProperty("value", "caller mutation");
		assertEquals("v1", cache.getOrCompute("agent-a", 101L, () -> value("v" + loads.incrementAndGet())).get("value").getAsString(),
				"fresh cache reuse is defensively copied");
		assertEquals("v2", cache.getOrCompute("agent-a", 102L, () -> value("v" + loads.incrementAndGet())).get("value").getAsString(),
				"same-position spatial changes reload within the two-tick bound");
		cache.invalidate("agent-a");
		assertEquals("v3", cache.getOrCompute("agent-a", 112L, () -> value("v" + loads.incrementAndGet())).get("value").getAsString(),
				"explicit invalidation reloads");

		Vec3 eye = new Vec3(0.0D, 1.6D, 0.0D);
		Vec3 forward = new Vec3(0.0D, 0.0D, 1.0D);
		assertTrue(ObservationVisibility.isWithinViewCone(eye, forward, new Vec3(0.0D, 1.6D, 5.0D)),
				"a target in front is visible to the current view");
		assertFalse(ObservationVisibility.isWithinViewCone(eye, forward, new Vec3(0.0D, 1.6D, -5.0D)),
				"a target behind the player is not reported as visible");
		assertTrue(ObservationVisibility.isWithinViewCone(eye, forward, new Vec3(1.0D, 1.6D, 0.0D)),
				"touch-distance awareness does not disappear outside the camera cone");
		assertFalse(ObservationVisibility.isWithinViewCone(eye, forward, new Vec3(8.0D, 1.6D, 0.0D)),
				"a distant side target is outside the bounded visual cone");
		HashMap<Long, Boolean> blockVisibility = new HashMap<>();
		AtomicInteger blockTraces = new AtomicInteger();
		BlockPos sharedPosition = new BlockPos(4, 64, 7);
		assertTrue(ObservationVisibility.memoizedBlockVisibility(
				blockVisibility, sharedPosition, position -> blockTraces.incrementAndGet() == 1),
				"first block visibility lookup uses the ray result");
		assertTrue(ObservationVisibility.memoizedBlockVisibility(
				blockVisibility, new BlockPos(4, 64, 7), position -> false),
				"block and container sections share a cached visible result");
		assertFalse(ObservationVisibility.memoizedBlockVisibility(
				blockVisibility, new BlockPos(5, 64, 7), position -> {
					blockTraces.incrementAndGet();
					return false;
				}), "a distinct block retains its own visibility result");
		assertFalse(ObservationVisibility.memoizedBlockVisibility(
				blockVisibility, new BlockPos(5, 64, 7), position -> true),
				"occluded block results are cached as well as visible results");
		assertEquals(2, blockTraces.get(), "two positions require two block ray traces");
		ObservationDispatchQueue<String> burst = new ObservationDispatchQueue<>(
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_AGENT_LIMIT
		);
		for (int index = 0; index < 16; index++) {
			String agentId = "agent-" + index;
			assertTrue(burst.offer(agentId), "each agent enters a bounded burst once");
			assertFalse(burst.offer(agentId), "same agent burst events coalesce");
		}
		List<String> burstFirst = new ArrayList<>();
		burst.drain(burstFirst::add);
		assertEquals(AgentConstants.DEFAULT_AGENT_LIMIT, burstFirst.size(), "one drain serves the sixteen-agent tick budget");
		assertEquals(0, burst.pendingCount(), "sixteen-agent burst clears in one drain");
		return 58 + verifyLandmarkContext() + verifyCurrentContainers() + verifyLoadedSight() + verifySpatialMutations()
				+ verifyNonSolidVisibility();
	}

	private static int verifyNonSolidVisibility() {
		HashMap<BlockPos, BlockState> states = new HashMap<>();
		HashMap<BlockPos, BlockEntity> blockEntities = new HashMap<>();
		BlockGetter world = new BlockGetter() {
			@Override public BlockEntity getBlockEntity(BlockPos position) { return blockEntities.get(position); }
			@Override public BlockState getBlockState(BlockPos position) {
				return states.getOrDefault(position, Blocks.AIR.defaultBlockState());
			}
			@Override public FluidState getFluidState(BlockPos position) { return getBlockState(position).getFluidState(); }
			@Override public int getHeight() { return 384; }
			@Override public int getMinY() { return -64; }
		};
		BlockPos target = new BlockPos(0, 0, 3);
		BlockPos occluder = new BlockPos(0, 0, 1);
		Vec3 eye = new Vec3(0.5D, 0.5D, 0.5D);
		CollisionContext context = CollisionContext.empty();
		int assertions = 0;
		for (var block : List.of(Blocks.STONE, Blocks.WATER, Blocks.LAVA, Blocks.DANDELION, Blocks.TORCH, Blocks.OAK_SLAB,
				Blocks.END_PORTAL, Blocks.END_GATEWAY)) {
			states.put(target, block.defaultBlockState());
			assertTrue(ObservationVisibility.traceBlock(world, context, eye, target),
					"clear sight reports visible " + block);
			states.put(occluder, Blocks.STONE.defaultBlockState());
			assertFalse(ObservationVisibility.traceBlock(world, context, eye, target),
					"a solid wall hides " + block);
			states.remove(occluder);
			assertions += 2;
		}
		states.clear();
		assertFalse(ObservationVisibility.traceBlock(world, context, eye, target), "clear sight never invents an air target");
		states.put(target, Blocks.LIGHT.defaultBlockState());
		assertFalse(ObservationVisibility.traceBlock(world, context, eye, target), "a clear ray does not reveal an invisible dry light block");
		states.put(target, Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true));
		assertTrue(ObservationVisibility.traceBlock(world, context, eye, target), "water in an invisible light block remains visible");
		states.put(target, Blocks.STRUCTURE_VOID.defaultBlockState());
		assertFalse(ObservationVisibility.traceBlock(world, context, eye, target), "a clear ray does not reveal invisible structure voids");
		BlockState movingState = Blocks.MOVING_PISTON.defaultBlockState();
		states.put(target, movingState);
		blockEntities.put(target, net.minecraft.world.level.block.piston.MovingPistonBlock.newMovingBlockEntity(
				target, movingState, Blocks.STONE.defaultBlockState(), net.minecraft.core.Direction.SOUTH, true, false));
		assertTrue(ObservationVisibility.traceBlock(world, context, eye, target),
				"a direct hit on a moving piston's actual block-entity geometry remains visible");
		return assertions + 5;
	}

	private static int verifySpatialMutations() {
		var agent = AgentId.parse("01234567-89ab-cdef-0123-456789abcdef");
		var position = new BlockPos(510, 64, 0);
		var revisions = new dev.agaminggod.arenaagents.world.WorldMutationRevisions();
		var cache = new ObservationSectionCache<RawSpatialObservation.Key, String>(16, 10L, value -> value);
		java.util.function.Supplier<RawSpatialObservation.Key> key = () -> ServerObservationCollector.spatialKey(
				agent, "minecraft:overworld", position,
				revisions.revision(position.getX(), position.getZ(), ServerObservationCollector.BLOCK_RADIUS));
		cache.getOrCompute(key.get(), 100L, () -> "empty");
		revisions.recordMutation(512, 0);
		assertEquals("chest", cache.getOrCompute(key.get(), 100L, () -> "chest"),
				"a neighboring-region placement refreshes a stationary observer in the same tick");
		assertEquals("chest", cache.getOrCompute(key.get(), 101L, () -> "unexpected rescan"),
				"unchanged local geometry still reuses the cached scan");
		revisions.recordMutation(4096, 4096);
		assertEquals("chest", cache.getOrCompute(key.get(), 102L, () -> "unexpected distant rescan"),
				"unrelated distant changes do not invalidate nearby candidates");
		return 3;
	}

	private static int verifyCurrentContainers() {
		var position = new BlockPos(0, 64, 3);
		var states = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
		var candidates = List.of(new RawSpatialObservation.ContainerCandidate(0, 64, 3,
				"minecraft:chest", List.of("transfer_container"), 12.75D));
		java.util.function.Supplier<com.google.gson.JsonArray> observe = () ->
				ServerObservationCollector.nearbyTransactionTargets(candidates, states::get, target -> true,
						new Vec3(0.0D, 64.0D, 0.0D), target -> false);
		states.put(position, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
		assertEquals("minecraft:chest", observe.get().get(0).getAsJsonObject().get("blockId").getAsString(),
				"initial container facts identify the live chest");
		states.put(position, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState());
		var replacement = observe.get().get(0).getAsJsonObject();
		assertEquals("minecraft:furnace", replacement.get("blockId").getAsString(),
				"another player's replacement refreshes the cached container ID immediately");
		assertEquals("[\"furnace_transaction\"]", replacement.get("capabilities").toString(),
				"replacement capabilities come from the live block");
		assertFalse(replacement.get("withinInteractionRange").getAsBoolean(),
				"container reach uses the player's authoritative interaction range");
		states.put(position, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		assertEquals(0, observe.get().size(), "removed containers cannot survive in cached observations");
		states.put(position, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
		assertEquals(0, observe.get().size(), "ordinary replacement blocks are not transaction targets");
		assertEquals(0, ServerObservationCollector.nearbyTransactionTargets(candidates, target -> {
			throw new AssertionError("hidden or unloaded candidates must not read block state");
		}, target -> false, Vec3.ZERO, target -> true).size(), "visibility and load checks precede state reads");
		return 7;
	}

	private static int verifyLoadedSight() {
		var origin = new Vec3(0.5D, 65.62D, 0.5D);
		Vec3 endpoint = ServerObservationCollector.loadedSightEndpoint(origin, new Vec3(0.0D, 0.0D, 1.0D),
				position -> (position.getZ() >> 4) != 1);
		assertTrue(endpoint != null && endpoint.z > 15.0D && endpoint.z < 16.0D,
				"sight stops before an unloaded intermediate chunk even when the far endpoint is loaded");
		endpoint = ServerObservationCollector.loadedSightEndpoint(origin, new Vec3(-1.0D, 0.0D, 0.0D),
				position -> (position.getX() >> 4) != -1);
		assertTrue(endpoint != null && endpoint.x > 0.0D && endpoint.x < 0.5D,
				"negative-axis sight stops on the loaded side of the first boundary");
		assertTrue(ServerObservationCollector.loadedSightEndpoint(origin, new Vec3(0.0D, 0.0D, 1.0D),
				position -> false) == null, "an unloaded observer chunk cannot start a sight trace");
		endpoint = ServerObservationCollector.loadedSightEndpoint(origin, new Vec3(0.0D, 1.0D, 0.0D),
				position -> true);
		assertEquals(origin.add(0.0D, ServerObservationCollector.LANDMARK_SIGHT_DISTANCE, 0.0D), endpoint,
				"a vertical ray stays within the loaded chunk and retains its full length");
		endpoint = ServerObservationCollector.loadedSightEndpoint(new Vec3(0.5D, 65.62D, 0.6D),
				new Vec3(1.0D, 0.0D, 1.0D).normalize(),
				position -> (position.getX() >> 4) != 0 || (position.getZ() >> 4) != 1);
		assertTrue(endpoint != null && endpoint.z < 16.0D && endpoint.z > 15.9D,
				"a diagonal ray cannot skip a short crossing through an unloaded chunk near a corner");
		endpoint = ServerObservationCollector.loadedSightEndpoint(origin,
				new Vec3(1.0D, 0.0D, 1.0D).normalize(),
				position -> (position.getX() >> 4) != 0 || (position.getZ() >> 4) != 1);
		assertTrue(endpoint != null && endpoint.z < 16.0D,
				"an exact corner crossing cannot enter an unloaded side chunk during block clipping");
		assertTrue(ServerObservationCollector.loadedSightEndpoint(new Vec3(0.0D, 64.0D, 0.5D),
				new Vec3(-1.0D, 0.0D, 0.0D), position -> position.getX() >= 0) == null,
				"a ray starting on the boundary cannot enter the immediately unloaded chunk");
		AtomicInteger checks = new AtomicInteger();
		Vec3 direction = new Vec3(0.8D, 0.0D, 0.6D);
		endpoint = ServerObservationCollector.loadedSightEndpoint(origin, direction, position -> {
			checks.incrementAndGet();
			return true;
		});
		assertEquals(origin.add(direction.scale(ServerObservationCollector.LANDMARK_SIGHT_DISTANCE)), endpoint,
				"fully loaded sight keeps its intended range");
		assertTrue(checks.get() <= 2 + ServerObservationCollector.LANDMARK_SIGHT_DISTANCE / 8,
				"sight checks scale with crossed chunks rather than individual blocks");
		var boundaryOrigin = new Vec3(0.0D, 64.0D, 0.5D);
		var forward = new Vec3(1.0D, 0.0D, 0.0D);
		Boolean entersUnloaded = BlockGetter.traverseBlocks(boundaryOrigin, boundaryOrigin.add(forward.scale(256.0D)),
				Boolean.TRUE, (ignored, position) -> position.getX() < 0 ? Boolean.TRUE : null, ignored -> Boolean.FALSE);
		assertTrue(entersUnloaded, "vanilla clipping visits the chunk behind an exact boundary origin");
		for (double x : new double[] {0.0D, 1.0E-6D}) {
			assertTrue(ServerObservationCollector.loadedSightEndpoint(new Vec3(x, 64.0D, 0.5D), forward,
					position -> position.getX() >= 0) == null,
					"the backwards clip expansion cannot read an unloaded chunk behind the observer");
		}
		assertTrue(ServerObservationCollector.loadedSightEndpoint(new Vec3(0.0D, 64.0D, 0.0D),
				new Vec3(1.0D, 0.0D, 1.0D).normalize(),
				position -> (position.getX() >> 4) != -1 || (position.getZ() >> 4) != 0) == null,
				"backwards expansion checks side chunks at an observer corner");
		endpoint = ServerObservationCollector.loadedSightEndpoint(boundaryOrigin, forward, position -> true);
		AtomicInteger visits = new AtomicInteger();
		BlockGetter.traverseBlocks(boundaryOrigin, endpoint, Boolean.TRUE, (ignored, position) -> {
			visits.incrementAndGet();
			return null;
		}, ignored -> Boolean.FALSE);
		assertTrue(visits.get() > 256, "loaded boundary rays retain vanilla traversal through the expanded endpoints");
		int assertions = 14;
		for (int sign : new int[] {-1, 1}) {
			Vec3 axis = new Vec3(sign, 0.0D, 0.0D);
			Vec3 diagonal = new Vec3(sign, 0.0D, sign).normalize();
			assertions += verifyExpandedSightEndpoint(new Vec3(-sign * 1.0E-5D, 64.0D, 0.5D), axis,
					position -> sign > 0 ? position.getX() < 256 : position.getX() >= -256);
			Vec3 diagonalEndpoint = new Vec3(sign * (192.0D - 1.0E-5D), 64.0D, sign * (192.0D - 1.0E-5D));
			assertions += verifyExpandedSightEndpoint(diagonalEndpoint.subtract(diagonal.scale(256.0D)), diagonal,
					position -> sign > 0 ? position.getX() < 192 && position.getZ() < 192
							: position.getX() >= -192 && position.getZ() >= -192);
		}
		assertions += verifyRoundedChunkCrossing(
				new Vec3(29999063.99999D, 64.5D, 29999015.99999D),
				new Vec3(0.7071067811872547D, 0.0D, 0.7071067811858404D), 256.0D,
				position -> (position.getX() >> 4) != 1874943 || (position.getZ() >> 4) != 1874941);
		assertions += verifyRoundedChunkCrossing(new Vec3(-16.0D, 64.5D, -16.0D),
				new Vec3(-0.9863981263522539D, 0.0D, 0.16437377019696023D), 153.9209845965773D,
				position -> (position.getX() >> 4) != -2 || (position.getZ() >> 4) != -2);
		return assertions + verifyLoadedCenterTrace();
	}

	private static int verifyRoundedChunkCrossing(Vec3 origin, Vec3 direction, double distance,
			java.util.function.Predicate<BlockPos> loaded) {
		assertTrue(BlockGetter.traverseBlocks(origin, origin.add(direction.scale(distance)), Boolean.TRUE,
				(ignored, position) -> !loaded.test(position) ? Boolean.TRUE : null, ignored -> Boolean.FALSE),
				"vanilla traverses the side chunk at a rounded or mixed-direction corner");
		Vec3 endpoint = ServerObservationCollector.loadedSightEndpoint(origin, direction, distance, loaded);
		assertTrue(endpoint == null || !BlockGetter.traverseBlocks(origin, endpoint, Boolean.TRUE,
				(ignored, position) -> !loaded.test(position) ? Boolean.TRUE : null, ignored -> Boolean.FALSE),
				"corner guards prevent the rounded clip from reading any unloaded side chunk");
		return 2;
	}

	private static int verifyLoadedCenterTrace() {
		Vec3 origin = new Vec3(0.5D, 64.5D, 0.5D);
		Vec3 direction = new Vec3(1.01D, 0.0D, 1.0D).normalize();
		BlockPos target = new BlockPos(16, 64, 16);
		java.util.function.Predicate<BlockPos> loaded = position -> (position.getX() >> 4) != 0 || (position.getZ() >> 4) != 1;
		Vec3 endpoint = ServerObservationCollector.loadedSightEndpoint(origin, direction, loaded);
		BlockPos sampled = BlockGetter.traverseBlocks(origin, endpoint, Boolean.TRUE, (ignored, position) -> {
			if (!loaded.test(position)) throw new AssertionError("the guarded sample must stay loaded");
			return position.equals(target) ? position.immutable() : null;
		}, ignored -> null);
		assertEquals(target, sampled, "the fan can safely sample a block through the loaded side of a corner");
		assertTrue(BlockGetter.traverseBlocks(origin, Vec3.atCenterOf(target), Boolean.TRUE,
				(ignored, position) -> !loaded.test(position) ? Boolean.TRUE : null, ignored -> Boolean.FALSE),
				"rechecking the sampled block center takes a different path through the unloaded side");
		assertFalse(ObservationVisibility.hasLoadedSightPath(origin, Vec3.atCenterOf(target), loaded),
				"final visibility rejects the unloaded center path before clipping");
		AtomicInteger checks = new AtomicInteger();
		assertTrue(ObservationVisibility.hasLoadedSightPath(origin, new Vec3(3.5D, 64.5D, 0.5D), position -> {
			checks.incrementAndGet();
			return position.getX() < 16;
		}), "nearby visibility ignores unloaded chunks beyond its actual target");
		assertEquals(1, checks.get(), "a short center trace checks only its own chunk");
		return 5;
	}

	private static int verifyExpandedSightEndpoint(Vec3 origin, Vec3 direction,
			java.util.function.Predicate<BlockPos> loaded) {
		Vec3 nominalEndpoint = origin.add(direction.scale(ServerObservationCollector.LANDMARK_SIGHT_DISTANCE));
		assertTrue(BlockGetter.traverseBlocks(origin, nominalEndpoint, Boolean.TRUE,
				(ignored, position) -> !loaded.test(position) ? Boolean.TRUE : null, ignored -> Boolean.FALSE),
				"vanilla's expanded endpoint crosses the nominally out-of-range unloaded boundary");
		Vec3 endpoint = ServerObservationCollector.loadedSightEndpoint(origin, direction, loaded);
		assertTrue(endpoint != null, "loaded ray still has a usable endpoint before the far boundary");
		assertFalse(BlockGetter.traverseBlocks(origin, endpoint, Boolean.TRUE,
				(ignored, position) -> !loaded.test(position) ? Boolean.TRUE : null, ignored -> Boolean.FALSE),
				"the guarded ray never traverses the unloaded chunk beyond its expanded endpoint");
		return 3;
	}

	private static int verifyLandmarkContext() {
		net.minecraft.server.Bootstrap.bootStrap();
		var scaffolding = net.minecraft.world.level.block.Blocks.SCAFFOLDING.defaultBlockState();
		var origin = BlockPos.ZERO;
		var world = net.minecraft.world.level.EmptyBlockGetter.INSTANCE;
		assertFalse(scaffolding.getVisualShape(world, origin, new LandmarkShapeContext(false)).isEmpty(),
				"standing above scaffolding blocks visual rays");
		assertTrue(scaffolding.getVisualShape(world, origin, new LandmarkShapeContext(true)).isEmpty(),
				"descending changes visual rays without requiring a block mutation");
		ObservationSectionCache<ServerObservationCollector.LandmarkSampleKey, Integer> cache =
				new ObservationSectionCache<>(16, 200L, value -> value);
		AtomicInteger samples = new AtomicInteger();
		var initial = landmarkKey(1L, false, 64.0D);
		assertEquals(1, cache.getOrCompute(initial, 100L, samples::incrementAndGet), "initial landmark fan sampled");
		assertEquals(1, cache.getOrCompute(initial, 101L, samples::incrementAndGet), "stationary fan reused across ticks");
		assertEquals(2, cache.getOrCompute(landmarkKey(2L, false, 64.0D), 101L, samples::incrementAndGet),
				"world revision refreshes candidates in the same tick");
		assertEquals(3, cache.getOrCompute(landmarkKey(2L, true, 64.0D), 101L, samples::incrementAndGet),
				"descending refreshes candidates even with unchanged eyes and blocks");
		assertEquals(4, cache.getOrCompute(landmarkKey(2L, true, 63.9D), 101L, samples::incrementAndGet),
				"collision context feet refresh candidates even with unchanged eyes");
		return 7;
	}

	private static ServerObservationCollector.LandmarkSampleKey landmarkKey(long revision, boolean descending, double feetY) {
		return new ServerObservationCollector.LandmarkSampleKey(AgentId.parse("00000000-0000-0000-0000-000000000001"),
				"minecraft:overworld", 0, 64, 0, 0.0D, 65.62D, 0.0D, 0.0F, 0.0F,
				feetY, descending, net.minecraft.world.item.Items.AIR, revision);
	}

	private static final class LandmarkShapeContext extends net.minecraft.world.phys.shapes.EntityCollisionContext {
		private LandmarkShapeContext(boolean descending) {
			super(descending, false, 2.0D, net.minecraft.world.item.ItemStack.EMPTY, false, null);
		}
	}

	private static JsonObject movingObservation(double x, double yaw, String selectedItem, double entityDistance) {
		JsonObject value = observation(20.0D, false, 0.0D, "navigate_to");
		value.addProperty("status", x == 0.0D ? "PLANNING" : "ACTING");
		JsonObject position = new JsonObject();
		position.addProperty("x", x);
		position.addProperty("y", 64.0D);
		position.addProperty("z", 0.0D);
		value.add("position", position);
		JsonObject velocity = new JsonObject();
		velocity.addProperty("x", x == 0.0D ? 0.0D : 0.1D);
		velocity.addProperty("y", 0.0D);
		velocity.addProperty("z", 0.0D);
		value.add("velocity", velocity);
		JsonObject view = new JsonObject();
		view.addProperty("yaw", yaw);
		view.addProperty("pitch", 0.0D);
		value.add("view", view);
		JsonObject player = value.getAsJsonObject("player");
		player.addProperty("onGround", x == 0.0D);
		player.addProperty("foodLevel", x == 0.0D ? 20 : 19);
		player.addProperty("fallDistance", 0.0D);
		JsonObject lastAttacker = new JsonObject();
		lastAttacker.addProperty("uuid", "00000000-0000-0000-0000-000000000002");
		lastAttacker.addProperty("type", "minecraft:zombie");
		lastAttacker.addProperty("distance", entityDistance);
		player.add("lastAttacker", lastAttacker);
		JsonObject effect = new JsonObject();
		effect.addProperty("effectId", "minecraft:speed");
		effect.addProperty("amplifier", 0);
		effect.addProperty("duration", x == 0.0D ? 100 : 99);
		com.google.gson.JsonArray effects = new com.google.gson.JsonArray();
		effects.add(effect);
		player.add("effects", effects);
		value.getAsJsonObject("inventory").addProperty("selectedItem", selectedItem);
		JsonObject nearbyEntity = entity(1);
		nearbyEntity.addProperty("distance", entityDistance);
		value.getAsJsonArray("entities").add(nearbyEntity);
		JsonObject lastResult = new JsonObject();
		lastResult.addProperty("present", x != 0.0D);
		value.add("lastResult", lastResult);
		return value;
	}

	private static JsonObject observation(double health, boolean onFire, double fallDistance, String actionType) {
		JsonObject value = new JsonObject();
		JsonObject player = new JsonObject();
		player.addProperty("health", health);
		player.addProperty("onFire", onFire);
		player.addProperty("fallDistance", fallDistance);
		value.add("player", player);
		value.add("inventory", new JsonObject());
		value.add("entities", new com.google.gson.JsonArray());
		value.add("blocks", new com.google.gson.JsonArray());
		JsonObject world = new JsonObject();
		world.addProperty("dimension", "minecraft:overworld");
		world.addProperty("gameTime", 0L);
		world.addProperty("dayTime", 0L);
		world.addProperty("raining", false);
		world.addProperty("thundering", false);
		value.add("world", world);
		JsonObject currentAction = new JsonObject();
		currentAction.addProperty("active", !"idle".equals(actionType));
		currentAction.addProperty("actionType", actionType);
		value.add("currentAction", currentAction);
		return value;
	}

	private static JsonObject entity(int index) {
		JsonObject value = new JsonObject();
		value.addProperty("uuid", String.format("00000000-0000-0000-0000-%012d", index));
		value.addProperty("type", "minecraft:zombie");
		return value;
	}

	private static JsonObject block(int index) {
		JsonObject value = new JsonObject();
		value.addProperty("x", index);
		value.addProperty("y", 64);
		value.addProperty("z", 0);
		value.addProperty("blockId", "minecraft:stone");
		return value;
	}

	private static JsonObject value(String text) {
		JsonObject value = new JsonObject();
		value.addProperty("value", text);
		return value;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertThrows(Runnable operation, String label) {
		try {
			operation.run();
		} catch (IllegalStateException expected) {
			return;
		}
		throw new AssertionError(label);
	}
}
