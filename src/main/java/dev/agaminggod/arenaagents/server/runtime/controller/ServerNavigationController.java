package dev.agaminggod.arenaagents.server.runtime.controller;

import carpet.helpers.EntityPlayerActionPack;
import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.LocalPathfinder;
import dev.agaminggod.arenaagents.client.navigation.PathNode;
import dev.agaminggod.arenaagents.client.navigation.PathOutcome;
import dev.agaminggod.arenaagents.client.navigation.PathPlan;
import dev.agaminggod.arenaagents.client.navigation.TraversalType;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates;
import dev.agaminggod.arenaagents.server.runtime.input.InputLease;
import dev.agaminggod.arenaagents.server.runtime.input.InputOwner;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

public final class ServerNavigationController implements ServerController {
	public static final int DEFAULT_MAX_PATH_LENGTH = 256;
	public static final double MAX_LOCAL_PLANNING_DISTANCE = 32.0D;
	private static final int MIN_AIR_RESERVE = 60;
	private static final long STALL_TIMEOUT_MS = 4_000L;
	private static final int MAX_REPLANS = 3;
	private static final double INTERMEDIATE_WAYPOINT_HORIZONTAL_TOLERANCE_SQUARED = 0.36D;
	private static final double INTERMEDIATE_WAYPOINT_VERTICAL_TOLERANCE = 0.25D;
	private static final double ENDPOINT_STABILITY_DISTANCE = 0.1D;

	private final Vec3 destination;
	private final double tolerance;
	private final boolean sprint;
	private final long timeoutMs;
	private final ElapsedTimeAccumulator elapsedTime;
	private final ServerPathPlanner planner = new ServerPathPlanner();
	private LocalPathfinder.Search search;
	private MinecraftNavigationWorld searchWorld;
	private GridPosition searchOrigin;
	private Set<GridPosition> searchGoals = Set.of();
	private final LinkedHashSet<GridPosition> previousFrontiers = new LinkedHashSet<>();
	private boolean searchRecovery;
	private PathPlan plan;
	private int waypointIndex;
	private WaypointProgress progress;
	private double lastProgressValue;
	private Vec3 navigationStartPosition;
	private GridPosition resolvedEndpointPosition;
	private Vec3 resolvedEndpointTarget;
	private Vec3 endpointStabilityPosition;
	private boolean endpointStabilityConfirmed;
	private InputLease inputLease;
	private LeasedServerInputController inputController;
	private AgentInputStates.MotorState motorState;
	private ResourceKey<Level> startingDimension;

	public ServerNavigationController(
			Vec3 destination,
			double tolerance,
			boolean sprint,
			long startedAt,
			long timeoutMs
	) {
		this.destination = Objects.requireNonNull(destination, "destination must not be null");
		if (!Double.isFinite(tolerance)
				|| tolerance < ProtocolConstants.MIN_MOVEMENT_TOLERANCE
				|| tolerance > ProtocolConstants.MAX_MOVEMENT_TOLERANCE
				|| timeoutMs <= 0L || timeoutMs > ProtocolConstants.MAX_DURATION_MS) {
			throw new IllegalArgumentException("invalid navigation tolerance or timeout");
		}
		this.tolerance = tolerance;
		this.sprint = sprint;
		this.timeoutMs = timeoutMs;
		this.elapsedTime = new ElapsedTimeAccumulator(startedAt);
	}

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		Objects.requireNonNull(player, "player must not be null");
		ResourceKey<Level> currentDimension = player.level().dimension();
		if (startingDimension == null) {
			startingDimension = currentDimension;
		} else if (!remainsInDimension(startingDimension, currentDimension)) {
			return fail(player, "ACTION_DIMENSION_CHANGED",
					"Agent player changed dimension during navigation", currentProgress());
		}
		long elapsedMs = elapsedTime.advance(nowEpochMs);
		if (!player.isAlive()) {
			return fail(player, "AGENT_DEAD", "Agent player died", currentProgress());
		}
		if (player.isPassenger() || player.isFallFlying()) {
			return fail(player, "UNSUPPORTED_NAVIGATION_MODE", "Mounted travel and gliding require player control inputs", currentProgress());
		}
		if (elapsedMs >= timeoutMs) {
			return fail(player, "ACTION_TIMEOUT", "Navigation timed out", currentProgress());
		}
		if (!hasAirReserve(player.isInWater(), player.getAirSupply())) {
			return fail(player, "AIR_RESERVE_REACHED", "Water traversal stopped at its breathing reserve", currentProgress());
		}
		MinecraftNavigationWorld world = new MinecraftNavigationWorld(player.level());
		if (navigationStartPosition == null) navigationStartPosition = player.position();
		if (plan == null) {
			TickResult planned = replan(player, world, nowEpochMs, elapsedMs, false);
			if (planned != null) return planned;
		}
		lastProgressValue = navigationProgress(player.position());
		TickResult endpointResult = verifyEndpoint(world, player);
		if (endpointResult != null) return endpointResult;
		List<PathNode> nodes = plan.nodes();
		if (waypointIndex >= nodes.size()) {
			return replanOrResult(player, world, nowEpochMs, elapsedMs);
		}
		PathNode waypoint = nodes.get(waypointIndex);
		if (!world.isTraversable(waypoint.position())) {
			TickResult replanned = replan(player, world, nowEpochMs, elapsedMs, true);
			if (replanned != null) return replanned;
			nodes = plan.nodes();
			waypoint = nodes.get(waypointIndex);
		}
		boolean finalWaypoint = waypointIndex == nodes.size() - 1;
		Vec3 target = targetFor(world, waypoint, finalWaypoint);
		boolean reached = reachedTarget(world, player.position(), waypoint, finalWaypoint);
		double activeWaypointDistance = player.position().distanceTo(target);
		WaypointProgress.Update update = progress.observe(activeWaypointDistance, reached, nowEpochMs);
		lastProgressValue = navigationProgress(player.position());
		if (reached) {
			waypointIndex++;
			if (waypointIndex >= nodes.size()) {
				return replanOrResult(player, world, nowEpochMs, elapsedMs);
			}
			waypoint = nodes.get(waypointIndex);
			finalWaypoint = waypointIndex == nodes.size() - 1;
			target = targetFor(world, waypoint, finalWaypoint);
			progress.waypointAdvanced(player.position().distanceTo(target), nowEpochMs);
		}
		if (update.decision() == WaypointProgress.Decision.FAIL) {
			return fail(player, "PATH_BLOCKED", "Navigation could not recover from repeated stalls", lastProgressValue);
		}
		if (update.decision() == WaypointProgress.Decision.REPLAN) {
			TickResult replanned = replan(player, world, nowEpochMs, elapsedMs, true);
			if (replanned != null) return replanned;
			waypoint = plan.nodes().get(waypointIndex);
			target = targetFor(world, waypoint, waypointIndex == plan.nodes().size() - 1);
		}
		drive(player, world, waypoint, target, nowEpochMs);
		return TickResult.running(lastProgressValue);
	}

	@Override
	public void cancel(ServerPlayer player) {
		if (inputLease == null) {
			motorState = null;
			return;
		}
		try {
			inputController.release(inputLease);
		} catch (LeasedServerInputController.StaleInputLeaseException ignored) {
			// A lifecycle clear may already have invalidated every lease.
		}
		inputLease = null;
		inputController = null;
		motorState = null;
	}

	private TickResult replanOrResult(
			ServerPlayer player,
			MinecraftNavigationWorld world,
			long nowEpochMs,
			long elapsedMs
	) {
		TickResult endpointResult = verifyEndpoint(world, player);
		if (endpointResult != null) return endpointResult;
		TickResult replanned = replan(player, world, nowEpochMs, elapsedMs, false);
		if (replanned != null) return replanned;
		lastProgressValue = navigationProgress(player.position());
		return TickResult.running(currentProgress());
	}

	static boolean satisfiesDestinationTolerance(double remaining, double tolerance) {
		return remaining <= tolerance;
	}

	static boolean remainsInDimension(ResourceKey<Level> startingDimension, ResourceKey<Level> currentDimension) {
		return Objects.requireNonNull(startingDimension, "startingDimension must not be null")
				.equals(Objects.requireNonNull(currentDimension, "currentDimension must not be null"));
	}

	Vec3 targetFor(PathNode waypoint, boolean finalWaypoint) {
		Objects.requireNonNull(waypoint, "waypoint must not be null");
		return targetFor(waypoint, finalWaypoint, waypoint.position().y());
	}

	boolean reachedTarget(Vec3 playerPosition, PathNode waypoint, boolean finalWaypoint) {
		return reachedTarget(playerPosition, waypoint, finalWaypoint, waypoint.position().y());
	}

	boolean reachedTarget(Vec3 playerPosition, PathNode waypoint, boolean finalWaypoint, double supportHeight) {
		Objects.requireNonNull(playerPosition, "playerPosition must not be null");
		if (!Double.isFinite(supportHeight)) return false;
		Vec3 target = targetFor(waypoint, finalWaypoint, supportHeight);
		return targetsExactDestination(waypoint, finalWaypoint)
				? satisfiesDestinationTolerance(playerPosition.distanceTo(target), tolerance)
				: reachedWaypoint(playerPosition, target);
	}

	private Vec3 targetFor(MinecraftNavigationWorld world, PathNode waypoint, boolean finalWaypoint) {
		if (!supportedEndpoint(world, waypoint.position())) {
			if (waypoint.traversal() == TraversalType.SWIM) return center(waypoint.position()).add(0.0D, 0.4D, 0.0D);
			if (waypoint.traversal() == TraversalType.CLIMB) return center(waypoint.position());
		}
		Vec3 horizontalTarget = targetFor(waypoint, finalWaypoint);
		double supportHeight = world.supportHeight(
				waypoint.position(), horizontalTarget.x, horizontalTarget.z);
		return Double.isFinite(supportHeight)
				? targetFor(waypoint, finalWaypoint, supportHeight)
				: horizontalTarget;
	}

	private Vec3 targetFor(PathNode waypoint, boolean finalWaypoint, double supportHeight) {
		boolean exactDestination = targetsExactDestination(waypoint, finalWaypoint);
		Vec3 horizontalTarget = exactDestination ? destination : center(waypoint.position());
		double targetY = exactDestination && hasFullBlockSupportHeight(waypoint.position(), supportHeight)
				? destination.y
				: supportHeight;
		return new Vec3(horizontalTarget.x, targetY, horizontalTarget.z);
	}

	/**
	 * Returns the last server-observed navigation facts for callers that publish action evidence.
	 * The endpoint is retained from the selected final path node, never inferred from raw proximity.
	 */
	public AuthoritativeState authoritativeState(ServerPlayer player, long observedAtEpochMs) {
		Objects.requireNonNull(player, "player must not be null");
		MinecraftNavigationWorld world = new MinecraftNavigationWorld(player.level());
		Vec3 position = player.position();
		lastProgressValue = navigationProgress(position);
		boolean endpointStandable = resolvedEndpointPosition != null && resolvedEndpointTarget != null
				&& supportedEndpoint(world, resolvedEndpointPosition)
				&& Double.isFinite(world.supportHeight(
						resolvedEndpointPosition,
						resolvedEndpointTarget.x,
						resolvedEndpointTarget.z));
		boolean withinTolerance = resolvedEndpointTarget != null
				&& satisfiesDestinationTolerance(position.distanceTo(resolvedEndpointTarget), tolerance);
		boolean noCollision = player.level().noCollision(player.getBoundingBox());
		boolean physicallyValid = endpointStandable && withinTolerance && noCollision
				&& !player.isInWall() && player.onGround();
		boolean stable = physicallyValid && endpointStabilityConfirmed && endpointStabilityPosition != null
				&& position.distanceTo(endpointStabilityPosition) <= ENDPOINT_STABILITY_DISTANCE;
		return new AuthoritativeState(
				position,
				grid(position),
				destination,
				resolvedEndpointTarget,
				resolvedEndpointPosition,
				resolvedEndpointTarget == null ? null : position.distanceTo(resolvedEndpointTarget),
				tolerance,
				lastProgressValue,
				endpointStandable,
				withinTolerance,
				noCollision,
				player.isInWall(),
				player.onGround(),
				stable,
				observedAtEpochMs
		);
	}

	public record AuthoritativeState(
			Vec3 position,
			GridPosition blockPosition,
			Vec3 requestedDestination,
			Vec3 resolvedEndpoint,
			GridPosition resolvedEndpointBlock,
			Double distanceToEndpoint,
			double tolerance,
			double progress,
			boolean endpointStandable,
			boolean withinTolerance,
			boolean noCollision,
			boolean inWall,
			boolean onGround,
			boolean stable,
			long observedAtEpochMs
	) {
	}

	private TickResult verifyEndpoint(MinecraftNavigationWorld world, ServerPlayer player) {
		if (resolvedEndpointPosition == null || resolvedEndpointTarget == null) {
			endpointStabilityPosition = null;
			endpointStabilityConfirmed = false;
			return null;
		}
		boolean endpointStandable = supportedEndpoint(world, resolvedEndpointPosition)
				&& Double.isFinite(world.supportHeight(
						resolvedEndpointPosition,
						resolvedEndpointTarget.x,
						resolvedEndpointTarget.z));
		boolean withinTolerance = satisfiesDestinationTolerance(
				player.position().distanceTo(resolvedEndpointTarget), tolerance);
		boolean physicallyValid = endpointStandable && withinTolerance
				&& player.level().noCollision(player.getBoundingBox())
				&& !player.isInWall() && player.onGround();
		if (!physicallyValid) {
			endpointStabilityPosition = null;
			endpointStabilityConfirmed = false;
			return null;
		}
		if (endpointStabilityPosition != null
				&& player.position().distanceTo(endpointStabilityPosition) <= ENDPOINT_STABILITY_DISTANCE) {
			endpointStabilityConfirmed = true;
			return succeed(player, "DESTINATION_REACHED", "Destination reached at a verified standing position");
		}
		endpointStabilityPosition = player.position();
		endpointStabilityConfirmed = false;
		cancel(player);
		return TickResult.running(lastProgressValue);
	}

	private double navigationProgress(Vec3 position) {
		Vec3 target = resolvedEndpointTarget == null ? destination : resolvedEndpointTarget;
		if (navigationStartPosition == null) navigationStartPosition = position;
		return progressFromActualDistance(navigationStartPosition, target, position, lastProgressValue);
	}

	static double progressFromActualDistance(Vec3 start, Vec3 endpoint, Vec3 current, double previousProgress) {
		Objects.requireNonNull(start, "start must not be null");
		Objects.requireNonNull(endpoint, "endpoint must not be null");
		Objects.requireNonNull(current, "current must not be null");
		double startDistance = start.distanceTo(endpoint);
		double remaining = current.distanceTo(endpoint);
		if (!Double.isFinite(startDistance) || !Double.isFinite(remaining)) return previousProgress;
		double currentProgress = startDistance <= 1.0E-7D
				? (remaining <= 1.0E-7D ? 1.0D : 0.0D)
				: 1.0D - remaining / startDistance;
		if (!Double.isFinite(currentProgress)) return previousProgress;
		return Math.max(0.0D, Math.min(1.0D, currentProgress));
	}

	private boolean reachedTarget(
			MinecraftNavigationWorld world,
			Vec3 playerPosition,
			PathNode waypoint,
			boolean finalWaypoint
	) {
		if (!supportedEndpoint(world, waypoint.position())
				&& (waypoint.traversal() == TraversalType.SWIM || waypoint.traversal() == TraversalType.CLIMB)) {
			Vec3 target = targetFor(world, waypoint, finalWaypoint);
			double dx = playerPosition.x - target.x;
			double dz = playerPosition.z - target.z;
			return dx * dx + dz * dz <= INTERMEDIATE_WAYPOINT_HORIZONTAL_TOLERANCE_SQUARED
					&& Math.abs(playerPosition.y - target.y) <= (waypoint.traversal() == TraversalType.SWIM ? 0.7D : 0.25D);
		}
		return reachedTarget(
				playerPosition,
				waypoint,
				finalWaypoint,
				world.supportHeight(waypoint.position(), playerPosition.x, playerPosition.z)
		);
	}

	private static boolean hasFullBlockSupportHeight(GridPosition position, double supportHeight) {
		return Math.abs(supportHeight - position.y()) < 1.0E-7D;
	}

	private boolean targetsExactDestination(PathNode waypoint, boolean finalWaypoint) {
		return finalWaypoint && waypoint.position().equals(grid(destination));
	}

	private TickResult replan(
			ServerPlayer player,
			MinecraftNavigationWorld world,
			long nowEpochMs,
			long elapsedMs,
			boolean recovery
	) {
		GridPosition actualOrigin = grid(player.position());
		if (searchWorld != null && !searchWorld.isCurrent()) {
			search = null;
			previousFrontiers.clear();
		}
		if (search != null && !actualOrigin.equals(searchOrigin)) search = null;
		if (search == null) {
			cancel(player);
			plan = null;
			GridPosition start = nearestTraversable(world, actualOrigin, 1, 2);
			if (start == null) return fail(player, "NO_STANDABLE_PATH", "Start has no supported, climbable or surface-water position", currentProgress());
			boolean destinationIsLocal = center(start).distanceTo(destination) <= MAX_LOCAL_PLANNING_DISTANCE;
			searchGoals = destinationIsLocal ? Set.copyOf(standableGoalsWithinTolerance(
					world, destination, tolerance, (int) Math.ceil(tolerance) + 1, (int) Math.ceil(tolerance) + 1)) : Set.of();
			if (destinationIsLocal && searchGoals.isEmpty() && world.cellAt(grid(destination)) != WalkabilityView.Cell.UNLOADED) {
				return fail(player, "NO_STANDABLE_PATH", "Destination has no supported position within the requested tolerance", currentProgress());
			}
			searchWorld = world;
			searchOrigin = actualOrigin;
			searchRecovery = recovery;
			search = planner.beginSearch(start, searchGoals, grid(destination), (int) MAX_LOCAL_PLANNING_DISTANCE, previousFrontiers);
		}
		ServerPathPlanner.PlanningResult planning = planner.resume(search, searchWorld);
		if (planning.deferred()) return TickResult.running(currentProgress());
		PathPlan candidate = planning.plan();
		search = null;
		if (candidate.outcome() != PathOutcome.FOUND) {
			return fail(player, candidate.outcome() == PathOutcome.NODE_LIMIT ? "PATH_LIMIT_REACHED" : "NO_PATH",
					"No reachable local route or unexplored route boundary is available", currentProgress());
		}
		if (candidate.nodes().size() > DEFAULT_MAX_PATH_LENGTH) {
			candidate = new PathPlan(candidate.nodes().subList(0, DEFAULT_MAX_PATH_LENGTH), PathOutcome.FOUND, candidate.expandedNodes());
		}
		GridPosition endpoint = candidate.nodes().getLast().position();
		boolean finalSegment = searchGoals.contains(endpoint);
		if (!finalSegment) {
			previousFrontiers.add(endpoint);
			while (previousFrontiers.size() > 64) previousFrontiers.remove(previousFrontiers.getFirst());
		}
		plan = candidate;
		if (finalSegment) {
			PathNode finalNode = candidate.nodes().get(candidate.nodes().size() - 1);
			resolvedEndpointPosition = finalNode.position();
			resolvedEndpointTarget = targetFor(world, finalNode, true);
			endpointStabilityPosition = null;
			endpointStabilityConfirmed = false;
		} else {
			resolvedEndpointPosition = null;
			resolvedEndpointTarget = null;
			endpointStabilityPosition = null;
			endpointStabilityConfirmed = false;
		}
		waypointIndex = Math.min(1, Math.max(0, candidate.nodes().size() - 1));
		PathNode activeWaypoint = candidate.nodes().get(waypointIndex);
		boolean finalWaypoint = waypointIndex == candidate.nodes().size() - 1;
		double activeWaypointDistance = player.position().distanceTo(targetFor(world, activeWaypoint, finalWaypoint));
		if (progress == null) {
			progress = new WaypointProgress(activeWaypointDistance, nowEpochMs, STALL_TIMEOUT_MS, MAX_REPLANS);
		} else if (searchRecovery) {
			progress.replanned(activeWaypointDistance, nowEpochMs);
		} else {
			progress.waypointAdvanced(activeWaypointDistance, nowEpochMs);
		}
		return null;
	}

	private void drive(
			ServerPlayer player,
			MinecraftNavigationWorld world,
			PathNode waypoint,
			Vec3 target,
			long nowEpochMs
	) {
		boolean gapJump = waypoint.traversal() == TraversalType.JUMP_GAP;
		boolean shallowWater = world.isShallowWater(waypoint.position());
		boolean swimming = waypoint.traversal() == TraversalType.SWIM || player.isInWater();
		boolean climbing = waypoint.traversal() == TraversalType.CLIMB;
		boolean crouching = waypoint.traversal() == TraversalType.CROUCH;
		double climbDx = player.getX() - target.x;
		double climbDz = player.getZ() - target.z;
		boolean atClimbColumn = climbDx * climbDx + climbDz * climbDz <= 0.16D;
		boolean descendingClimb = climbing && atClimbColumn && target.y < player.getY() - 0.15D;
		if (inputLease == null) {
			inputController = AgentInputRuntime.controller(player);
			inputLease = inputController.acquire(AgentInputRuntime.requireAgentId(player), InputOwner.NAVIGATION, 100);
		}
		Vec3 lookTarget = target.add(0.0D, 0.85D, 0.0D);
		Vec3 delta = lookTarget.subtract(player.getEyePosition());
		double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		float targetYaw = net.minecraft.util.Mth.wrapDegrees(
				(float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
		float targetPitch = net.minecraft.util.Mth.clamp(
				(float) -Math.toDegrees(Math.atan2(delta.y, horizontal)), -90.0F, 90.0F);
		if (climbing && atClimbColumn) {
			net.minecraft.core.Direction wall = world.climbDirection(waypoint.position());
			if (wall != null) targetYaw = (float) Math.toDegrees(Math.atan2(-wall.getStepX(), wall.getStepZ()));
		}
		if (motorState == null) motorState = AgentInputStates.MotorState.initial(player.getYRot(), player.getXRot());
		AgentInputStates.MotorStep step = AgentInputStates.stepMotor(
				motorState,
				new AgentInputStates.MotorTarget(
						targetYaw,
						targetPitch,
						!descendingClimb,
						waypoint.traversal() == TraversalType.JUMP_UP || gapJump || shallowWater || swimming || (climbing && target.y > player.getY() + 0.15D),
						!swimming && !crouching && !climbing && (sprint || gapJump) && player.getFoodData().getFoodLevel() > 6
				),
				nowEpochMs
		);
		motorState = step.state();
		inputController.apply(inputLease, new dev.agaminggod.arenaagents.server.runtime.input.AgentInputState(
				step.forward(), step.strafe(), step.jump(), crouching, step.sprint(),
				false, false, step.state().yaw(), step.state().pitch(),
				player.getInventory().getSelectedSlot(), InteractionHand.MAIN_HAND
		));
	}

	private TickResult succeed(ServerPlayer player, String reasonCode, String message) {
		cancel(player);
		return TickResult.succeeded(reasonCode, message);
	}

	private TickResult fail(ServerPlayer player, String reasonCode, String message, double progressValue) {
		cancel(player);
		return TickResult.failed(reasonCode, message, progressValue);
	}

	private double currentProgress() {
		return lastProgressValue;
	}

	private static boolean reachedWaypoint(Vec3 player, Vec3 waypoint) {
		double dx = player.x - waypoint.x;
		double dz = player.z - waypoint.z;
		return dx * dx + dz * dz <= INTERMEDIATE_WAYPOINT_HORIZONTAL_TOLERANCE_SQUARED
				&& Math.abs(player.y - waypoint.y) <= INTERMEDIATE_WAYPOINT_VERTICAL_TOLERANCE;
	}

	static boolean shouldRetryPlanning(
			PathOutcome outcome,
			boolean deferred,
			long elapsedMs,
			long timeoutMs
	) {
		Objects.requireNonNull(outcome, "outcome must not be null");
		if (!deferred && outcome != PathOutcome.NODE_LIMIT && outcome != PathOutcome.TIME_LIMIT) return false;
		return elapsedMs >= 0L && timeoutMs > 0L && elapsedMs < timeoutMs;
	}

	private static Vec3 center(GridPosition position) {
		return new Vec3(position.x() + 0.5D, position.y(), position.z() + 0.5D);
	}

	private static GridPosition grid(Vec3 position) {
		return new GridPosition(
				(int) Math.floor(position.x),
				(int) Math.floor(position.y),
				(int) Math.floor(position.z)
		);
	}

	private static GridPosition nearestTraversable(
			MinecraftNavigationWorld world,
			GridPosition origin,
			int horizontalRadius,
			int verticalRadius
	) {
		for (int radius = 0; radius <= horizontalRadius; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
					for (int vertical = 0; vertical <= verticalRadius; vertical++) {
						GridPosition above = new GridPosition(
								origin.x() + dx,
								origin.y() + vertical,
								origin.z() + dz
						);
						if (world.isTraversable(above)) return above;
						if (vertical > 0) {
							GridPosition below = new GridPosition(
									origin.x() + dx,
									origin.y() - vertical,
									origin.z() + dz
							);
							if (world.isTraversable(below)) return below;
						}
					}
				}
			}
		}
		return null;
	}

	private static List<GridPosition> standableGoalsWithinTolerance(
			MinecraftNavigationWorld world,
			Vec3 destination,
			double tolerance,
			int horizontalRadius,
			int verticalRadius
	) {
		GridPosition origin = grid(destination);
		ArrayList<GridPosition> candidates = new ArrayList<>();
		for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
			for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
				for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
					GridPosition candidate = new GridPosition(origin.x() + dx, origin.y() + dy, origin.z() + dz);
					if (supportedEndpoint(world, candidate) && candidateSatisfiesTolerance(candidate, destination, tolerance)) {
						candidates.add(candidate);
					}
				}
			}
		}
		candidates.sort(Comparator.comparingDouble(value -> center(value).distanceToSqr(destination)));
		return List.copyOf(candidates);
	}

	static boolean candidateSatisfiesTolerance(GridPosition candidate, Vec3 destination, double tolerance) {
		return candidate.equals(grid(destination)) || center(candidate).distanceTo(destination) <= tolerance;
	}

	private static boolean supportedEndpoint(MinecraftNavigationWorld world, GridPosition position) {
		TraversalType traversal = world.traversalAt(position);
		return traversal == TraversalType.WALK || traversal == TraversalType.CROUCH;
	}

	static boolean hasAirReserve(boolean inWater, int air) {
		return !inWater || air > MIN_AIR_RESERVE;
	}
}
