package dev.agaminggod.arenaagents.scenario.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Bounded canonicalize/apply/exhaustive-verify reset pipeline. */
public final class ScenarioArenaResetJob {
	public static final int MAXIMUM_WORK_PER_TICK = 2_048;
	public static final int MAXIMUM_VISIBLE_APPLICATIONS_PER_TICK = 256;
	public static final int MAXIMUM_CORRECTION_PASSES = 3;
	public static final long MAXIMUM_TICK_NANOS = 4_000_000L;
	private static final int MANAGED_CHUNK_TICKET_RADIUS = 0;
	private static final TicketType MANAGED_CHUNK_TICKET = new TicketType(
			TicketType.NO_TIMEOUT,
			TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE
	);

	private final List<ScenarioArenaBlueprint.Placement> source;
	private final ScenarioArenaBlueprint.SiteBounds siteBounds;
	private final TreeMap<Long, ScenarioArenaBlueprint.Placement> canonicalByPosition = new TreeMap<>();
	private final ArrayList<ScenarioArenaBlueprint.Placement> canonicalBuilder = new ArrayList<>();
	private List<ScenarioArenaBlueprint.Placement> canonical = List.of();
	private List<ScenarioArenaBlueprint.Placement> applicationOrder = List.of();
	private List<ChunkPos> managedChunks = List.of();
	private final Set<ChunkPos> retainedChunks = new LinkedHashSet<>();
	private Iterator<ScenarioArenaBlueprint.Placement> canonicalIterator;
	private MessageDigest blueprintHasher;
	private Phase phase = Phase.CANONICALIZE;
	private int sourceIndex;
	private int phaseIndex;
	private int applied;
	private int changedBlocks;
	private int loadedChunks;
	private int clearedColumns;
	private int clearHeight;
	private int clearTotal;
	private int clearTopY;
	private int verified;
	private int mismatches;
	private final ArrayList<String> mismatchSamples = new ArrayList<>();
	private final ArrayList<ScenarioArenaBlueprint.Placement> mismatchedPlacements = new ArrayList<>();
	private int correctionPasses;
	private String blueprintHash = "unavailable";
	private MessageDigest managedHasher;
	private ScenarioResetReceipt receipt;
	private String failureReason = "";
	private Phase terminalWorkPhase;
	private int terminalCompleted;
	private int terminalTotal;

	public ScenarioArenaResetJob(List<ScenarioArenaBlueprint.Placement> placements) {
		this(placements, null);
	}

	public ScenarioArenaResetJob(ScenarioArenaBlueprint blueprint) {
		this(Objects.requireNonNull(blueprint, "blueprint must not be null").placements(), blueprint.siteBounds());
	}

	private ScenarioArenaResetJob(
			List<ScenarioArenaBlueprint.Placement> placements,
			ScenarioArenaBlueprint.SiteBounds siteBounds
	) {
		this.source = List.copyOf(Objects.requireNonNull(placements, "placements must not be null"));
		this.siteBounds = siteBounds;
		for (ScenarioArenaBlueprint.Placement placement : source) {
			Objects.requireNonNull(placement, "placements must not contain null");
		}
	}

	public Tick tick(ServerLevel level) {
		Objects.requireNonNull(level, "level must not be null");
		if (phase == Phase.COMPLETE || phase == Phase.FAILED) return snapshotTick(0);
		long started = System.nanoTime();
		int worked = 0;
		try {
			while (worked < maximumWorkForPhase(phase)
					&& (worked == 0 || System.nanoTime() - started < MAXIMUM_TICK_NANOS)) {
				if (!step(level)) break;
				worked++;
			}
		} catch (RuntimeException exception) {
			releaseManagedChunkTickets(level);
			throw exception;
		}
		return snapshotTick(worked);
	}

	private boolean step(ServerLevel level) {
		return switch (phase) {
			case CANONICALIZE -> canonicalizeOne();
			case LOAD_CHUNKS -> loadChunkOne(level);
			case CLEAR -> clearColumnOne(level);
			case APPLY -> applyOne(level);
			case VERIFY -> verifyOne(level);
			case REPAIR -> repairOne(level);
			case COMPLETE, FAILED -> false;
		};
	}

	private boolean canonicalizeOne() {
		if (sourceIndex < source.size()) {
			ScenarioArenaBlueprint.Placement placement = source.get(sourceIndex++);
			canonicalByPosition.put(placement.position().asLong(), placement);
			if (sourceIndex == source.size()) beginCanonicalFinalization();
			return true;
		}
		if (canonicalIterator == null) beginCanonicalFinalization();
		if (!canonicalIterator.hasNext()) {
			finishCanonicalization();
			return false;
		}
		ScenarioArenaBlueprint.Placement canonicalPlacement = canonicalIterator.next();
		if (siteBounds == null || !canonicalPlacement.state().isAir()) {
			canonicalBuilder.add(canonicalPlacement);
			updateHash(blueprintHasher, canonicalPlacement.position(), canonicalPlacement.state());
		}
		if (!canonicalIterator.hasNext()) finishCanonicalization();
		return true;
	}

	private void beginCanonicalFinalization() {
		if (canonicalIterator != null) return;
		canonicalIterator = canonicalByPosition.values().iterator();
		blueprintHasher = sha256();
	}

	private void finishCanonicalization() {
		canonical = List.copyOf(canonicalBuilder);
		applicationOrder = applicationOrder(canonical);
		managedChunks = siteBounds == null ? managedChunks(canonical) : managedChunks(siteBounds);
		blueprintHash = HexFormat.of().formatHex(blueprintHasher.digest());
		phase = managedChunks.isEmpty() ? (siteBounds == null ? Phase.APPLY : Phase.CLEAR) : Phase.LOAD_CHUNKS;
		phaseIndex = 0;
	}

	private boolean loadChunkOne(ServerLevel level) {
		if (phaseIndex >= managedChunks.size()) {
			beginClearOrApply(level);
			return false;
		}
		ChunkPos chunk = managedChunks.get(phaseIndex);
		if (retainedChunks.add(chunk)) {
			level.getChunkSource().addTicketWithRadius(
					MANAGED_CHUNK_TICKET,
					chunk,
					MANAGED_CHUNK_TICKET_RADIUS
			);
		}
		level.getChunk(chunk.x(), chunk.z());
		phaseIndex++;
		loadedChunks++;
		if (phaseIndex == managedChunks.size()) beginClearOrApply(level);
		return true;
	}

	private void beginClearOrApply(ServerLevel level) {
		if (siteBounds == null) {
			beginApply();
			return;
		}
		phase = Phase.CLEAR;
		phaseIndex = 0;
		clearedColumns = 0;
		clearTopY = level.getMaxY();
		clearHeight = Math.max(0, clearTopY - siteBounds.clearFloorY() - 1);
		clearTotal = Math.multiplyExact(siteBounds.columnCount(), clearHeight);
		clearScheduledTicks(level);
	}

	/** Clears one complete vertical column so multiple height levels disappear together. */
	private boolean clearColumnOne(ServerLevel level) {
		if (clearHeight == 0 || clearedColumns >= siteBounds.columnCount()) {
			clearScheduledTicks(level);
			beginApply();
			return false;
		}
		int width = siteBounds.maximumX() - siteBounds.minimumX() + 1;
		int x = siteBounds.minimumX() + clearedColumns % width;
		int z = siteBounds.minimumZ() + clearedColumns / width;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = clearTopY - 1; y > siteBounds.clearFloorY(); y--) {
			cursor.set(x, y, z);
			if (!level.getBlockState(cursor).isAir()
					&& level.setBlock(cursor, Blocks.AIR.defaultBlockState(),
					Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS)) {
				changedBlocks++;
			}
		}
		clearedColumns++;
		phaseIndex = Math.min(clearTotal, Math.multiplyExact(clearedColumns, clearHeight));
		if (clearedColumns == siteBounds.columnCount()) {
			clearScheduledTicks(level);
			beginApply();
		}
		return true;
	}

	private void clearScheduledTicks(ServerLevel level) {
		if (clearHeight == 0) return;
		BoundingBox area = new BoundingBox(
				siteBounds.minimumX(), siteBounds.clearFloorY() + 1, siteBounds.minimumZ(),
				siteBounds.maximumX(), clearTopY - 1, siteBounds.maximumZ());
		level.getBlockTicks().clearArea(area);
		level.getFluidTicks().clearArea(area);
	}

	private void beginApply() {
		phase = Phase.APPLY;
		phaseIndex = 0;
	}

	private boolean applyOne(ServerLevel level) {
		if (phaseIndex >= applicationOrder.size()) {
			phase = Phase.VERIFY;
			phaseIndex = 0;
			managedHasher = sha256();
			return false;
		}
		ScenarioArenaBlueprint.Placement placement = applicationOrder.get(phaseIndex);
		if (!level.hasChunkAt(placement.position())) {
			fail(level, "UNLOADED_MANAGED_CHUNK");
			return false;
		}
		BlockState current = level.getBlockState(placement.position());
		if (!verificationEquivalent(current, placement.state())) {
			if (level.setBlock(placement.position(), placement.state(), 2)) changedBlocks++;
		}
		phaseIndex++;
		applied++;
		if (phaseIndex == applicationOrder.size()) {
			phase = Phase.VERIFY;
			phaseIndex = 0;
			managedHasher = sha256();
		}
		return true;
	}

	private boolean verifyOne(ServerLevel level) {
		if (phaseIndex >= canonical.size()) {
			completeVerification(level);
			return false;
		}
		ScenarioArenaBlueprint.Placement expected = canonical.get(phaseIndex);
		BlockPos position = expected.position();
		if (!level.hasChunkAt(position)) {
			fail(level, "UNLOADED_MANAGED_CHUNK");
			return false;
		}
		BlockState actual = level.getBlockState(position);
		updateHash(managedHasher, position, actual);
		if (!verificationEquivalent(actual, expected.state())) {
			mismatches++;
			mismatchedPlacements.add(expected);
			if (mismatchSamples.size() < 5) {
				mismatchSamples.add(position.toShortString() + " expected "
						+ stableStateKey(expected.state()) + " but found " + stableStateKey(actual));
			}
		}
		phaseIndex++;
		verified++;
		if (phaseIndex == canonical.size()) completeVerification(level);
		return true;
	}

	private void completeVerification(ServerLevel level) {
		String managedHash = HexFormat.of().formatHex(managedHasher.digest());
		boolean matches = mismatches == 0 && managedHash.equals(blueprintHash);
		VerificationDecision decision = decideAfterVerification(matches ? 0 : Math.max(1, mismatches), correctionPasses);
		if (decision == VerificationDecision.REPAIR) {
			phase = Phase.REPAIR;
			phaseIndex = 0;
			return;
		}
		receipt = new ScenarioResetReceipt(
				blueprintHash, managedHash, canonical.size(), applied, verified, matches
		);
		terminalWorkPhase = Phase.VERIFY;
		terminalCompleted = verified;
		terminalTotal = canonical.size();
		phase = decision == VerificationDecision.COMPLETE ? Phase.COMPLETE : Phase.FAILED;
		if (decision == VerificationDecision.FAIL) failureReason = "RESET_VERIFICATION_DID_NOT_CONVERGE";
		releaseManagedChunkTickets(level);
	}

	private boolean repairOne(ServerLevel level) {
		if (phaseIndex >= mismatchedPlacements.size()) {
			beginReverification();
			return false;
		}
		ScenarioArenaBlueprint.Placement expected = mismatchedPlacements.get(phaseIndex);
		BlockPos position = expected.position();
		if (!level.hasChunkAt(position)) {
			fail(level, "UNLOADED_MANAGED_CHUNK");
			return false;
		}
		if (!verificationEquivalent(level.getBlockState(position), expected.state())
				&& level.setBlock(position, expected.state(),
				Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS)) {
			changedBlocks++;
		}
		phaseIndex++;
		if (phaseIndex == mismatchedPlacements.size()) beginReverification();
		return true;
	}

	private void beginReverification() {
		correctionPasses++;
		phase = Phase.VERIFY;
		phaseIndex = 0;
		verified = 0;
		mismatches = 0;
		mismatchSamples.clear();
		mismatchedPlacements.clear();
		managedHasher = sha256();
	}

	private void fail(ServerLevel level, String reason) {
		terminalWorkPhase = phase;
		terminalCompleted = currentCompletedWork();
		terminalTotal = currentTotalPlacements();
		failureReason = reason;
		phase = Phase.FAILED;
		receipt = new ScenarioResetReceipt(
				blueprintHash, "unavailable", canonical.size(), applied, verified, false
		);
		releaseManagedChunkTickets(level);
	}

	private void releaseManagedChunkTickets(ServerLevel level) {
		for (ChunkPos chunk : retainedChunks) {
			level.getChunkSource().removeTicketWithRadius(
					MANAGED_CHUNK_TICKET,
					chunk,
					MANAGED_CHUNK_TICKET_RADIUS
			);
		}
		retainedChunks.clear();
	}

	public Phase phase() {
		return phase;
	}

	public int totalPlacements() {
		if ((phase == Phase.COMPLETE || phase == Phase.FAILED) && terminalWorkPhase != null) return terminalTotal;
		return currentTotalPlacements();
	}

	private int currentTotalPlacements() {
		return switch (phase) {
			case CANONICALIZE -> source.size()
					+ (canonicalIterator == null ? source.size() : canonicalByPosition.size());
			case LOAD_CHUNKS -> managedChunks.size();
			case CLEAR -> clearTotal;
			case APPLY, VERIFY, COMPLETE, FAILED -> canonical.size();
			case REPAIR -> mismatchedPlacements.size();
		};
	}

	public int completedWork() {
		if ((phase == Phase.COMPLETE || phase == Phase.FAILED) && terminalWorkPhase != null) return terminalCompleted;
		return currentCompletedWork();
	}

	private int currentCompletedWork() {
		return switch (phase) {
			case CANONICALIZE -> sourceIndex + canonicalBuilder.size();
			case LOAD_CHUNKS -> loadedChunks;
			case CLEAR -> phaseIndex;
			case APPLY -> applied;
			case VERIFY, COMPLETE, FAILED -> verified;
			case REPAIR -> phaseIndex;
		};
	}

	public Optional<ScenarioResetReceipt> receipt() {
		return Optional.ofNullable(receipt);
	}

	public String failureReason() {
		return failureReason;
	}

	public int mismatchCount() {
		return mismatches;
	}

	public int correctionPasses() {
		return correctionPasses;
	}

	/** Number of placements that changed the observed world state, not just processed entries. */
	public int changedBlocks() {
		return changedBlocks;
	}

	public List<String> mismatchSamples() {
		return List.copyOf(mismatchSamples);
	}

	public String progressLabel() {
		int total = totalPlacements();
		int completed = completedWork();
		int percent = total == 0 ? 100 : (int) Math.min(100L, 100L * completed / total);
		return phase.displayName() + " " + completed + " / " + total + " (" + percent + "%)";
	}

	public void close(ServerLevel level) {
		releaseManagedChunkTickets(Objects.requireNonNull(level, "level must not be null"));
	}

	public List<ScenarioArenaBlueprint.Placement> canonicalPlacements() {
		return canonical;
	}

	private Tick snapshotTick(int worked) {
		return new Tick(phase, worked, completedWork(), totalPlacements(), changedBlocks, receipt(), failureReason);
	}

	public static List<ScenarioArenaBlueprint.Placement> canonicalize(
			List<ScenarioArenaBlueprint.Placement> placements
	) {
		TreeMap<Long, ScenarioArenaBlueprint.Placement> canonical = new TreeMap<>();
		for (ScenarioArenaBlueprint.Placement placement : List.copyOf(placements)) {
			canonical.put(placement.position().asLong(), placement);
		}
		return List.copyOf(canonical.values());
	}

	public static List<ChunkPos> managedChunks(List<ScenarioArenaBlueprint.Placement> placements) {
		return List.copyOf(List.copyOf(Objects.requireNonNull(placements, "placements must not be null")).stream()
				.map(placement -> Objects.requireNonNull(placement, "placements must not contain null").position())
				.map(position -> new ChunkPos(position.getX() >> 4, position.getZ() >> 4))
				.distinct()
				.sorted(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z))
				.toList());
	}

	public static List<ChunkPos> managedChunks(ScenarioArenaBlueprint.SiteBounds bounds) {
		Objects.requireNonNull(bounds, "bounds must not be null");
		ArrayList<ChunkPos> result = new ArrayList<>();
		for (int chunkX = bounds.minimumX() >> 4; chunkX <= bounds.maximumX() >> 4; chunkX++) {
			for (int chunkZ = bounds.minimumZ() >> 4; chunkZ <= bounds.maximumZ() >> 4; chunkZ++) {
				result.add(new ChunkPos(chunkX, chunkZ));
			}
		}
		result.sort(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
		return List.copyOf(result);
	}

	/** Air clears top-down, containment builds bottom-up and center-out, then fluids are introduced. */
	public static List<ScenarioArenaBlueprint.Placement> applicationOrder(
			List<ScenarioArenaBlueprint.Placement> placements
	) {
		List<ScenarioArenaBlueprint.Placement> canonical = canonicalize(placements);
		if (canonical.isEmpty()) return List.of();
		int minX = canonical.stream().mapToInt(placement -> placement.position().getX()).min().orElseThrow();
		int maxX = canonical.stream().mapToInt(placement -> placement.position().getX()).max().orElseThrow();
		int minZ = canonical.stream().mapToInt(placement -> placement.position().getZ()).min().orElseThrow();
		int maxZ = canonical.stream().mapToInt(placement -> placement.position().getZ()).max().orElseThrow();
		long centerX2 = (long) minX + maxX;
		long centerZ2 = (long) minZ + maxZ;
		Comparator<ScenarioArenaBlueprint.Placement> spatial = Comparator
				.comparingInt((ScenarioArenaBlueprint.Placement placement) -> placement.position().getY())
				.thenComparingLong(placement -> horizontalDistanceSquaredTimesFour(
						placement.position(), centerX2, centerZ2))
				.thenComparingInt(placement -> placement.position().getX())
				.thenComparingInt(placement -> placement.position().getZ());
		ArrayList<ScenarioArenaBlueprint.Placement> ordered = new ArrayList<>(canonical.size());
		canonical.stream().filter(placement -> placement.state().isAir())
				.sorted(spatial.reversed()).forEach(ordered::add);
		canonical.stream().filter(placement -> !placement.state().isAir()
					&& placement.state().getFluidState().isEmpty())
				.sorted(spatial).forEach(ordered::add);
		canonical.stream().filter(placement -> !placement.state().getFluidState().isEmpty())
				.sorted(spatial).forEach(ordered::add);
		return List.copyOf(ordered);
	}

	private static long horizontalDistanceSquaredTimesFour(BlockPos position, long centerX2, long centerZ2) {
		long dx2 = 2L * position.getX() - centerX2;
		long dz2 = 2L * position.getZ() - centerZ2;
		return dx2 * dx2 + dz2 * dz2;
	}

	public static String hash(List<ScenarioArenaBlueprint.Placement> placements) {
		return hashCanonical(canonicalize(placements));
	}

	public static ScenarioResetReceipt verifyCanonical(
			List<ScenarioArenaBlueprint.Placement> expectedPlacements,
			List<ScenarioArenaBlueprint.Placement> actualPlacements
	) {
		List<ScenarioArenaBlueprint.Placement> expected = canonicalize(expectedPlacements);
		List<ScenarioArenaBlueprint.Placement> actual = canonicalize(actualPlacements);
		String expectedHash = hashCanonical(expected);
		String actualHash = hashCanonical(actual);
		boolean exact = expected.size() == actual.size();
		if (exact) {
			for (int index = 0; index < expected.size(); index++) {
				ScenarioArenaBlueprint.Placement wanted = expected.get(index);
				ScenarioArenaBlueprint.Placement observed = actual.get(index);
				if (!wanted.position().equals(observed.position())
						|| !verificationEquivalent(wanted.state(), observed.state())) {
					exact = false;
				}
			}
		}
		return new ScenarioResetReceipt(
				expectedHash,
				actualHash,
				expected.size(),
				expected.size(),
				expected.size(),
				exact && expectedHash.equals(actualHash)
		);
	}

	private static String hashCanonical(List<ScenarioArenaBlueprint.Placement> placements) {
		MessageDigest digest = sha256();
		for (ScenarioArenaBlueprint.Placement placement : placements) {
			updateHash(digest, placement.position(), placement.state());
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void updateHash(MessageDigest digest, BlockPos position, BlockState state) {
		String entry = position.getX() + "," + position.getY() + "," + position.getZ()
				+ "=" + stableStateKey(state) + "\n";
		digest.update(entry.getBytes(StandardCharsets.UTF_8));
	}

	private static String stableStateKey(BlockState state) {
		StringBuilder value = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		state.getValues()
				.filter(entry -> !worldDerivedProperty(entry.property().getName()))
				.sorted(Comparator.comparing(entry -> entry.property().getName()))
				.forEach(entry -> value.append('|')
						.append(entry.property().getName()).append('=').append(entry.valueName()));
		return value.toString();
	}

	private static boolean worldDerivedProperty(String name) {
		// These values are recomputed from neighboring blocks after placement. They do
		// not describe authored geometry and cannot be compared to isolated default
		// states without falsely rejecting correct fences, walls, panes, or leaves.
		return switch (name) {
			case "distance", "north", "east", "south", "west", "up" -> true;
			default -> false;
		};
	}

	private static boolean verificationEquivalent(BlockState first, BlockState second) {
		return stableStateKey(first).equals(stableStateKey(second));
	}

	public static int batchEnd(int startInclusive, int totalSize, int maximum) {
		if (startInclusive < 0 || totalSize < startInclusive || maximum < 1) {
			throw new IllegalArgumentException("invalid reset batch bounds");
		}
		return (int) Math.min((long) totalSize, (long) startInclusive + maximum);
	}

	/** Paces visible world mutation while keeping bookkeeping and verification fast. */
	public static int maximumWorkForPhase(Phase phase) {
		Phase checked = Objects.requireNonNull(phase, "phase must not be null");
		return checked == Phase.APPLY || checked == Phase.REPAIR
				? MAXIMUM_VISIBLE_APPLICATIONS_PER_TICK
				: MAXIMUM_WORK_PER_TICK;
	}

	public static VerificationDecision decideAfterVerification(int mismatchCount, int completedCorrectionPasses) {
		if (mismatchCount < 0 || completedCorrectionPasses < 0) {
			throw new IllegalArgumentException("verification counters must not be negative");
		}
		if (mismatchCount == 0) return VerificationDecision.COMPLETE;
		return completedCorrectionPasses < MAXIMUM_CORRECTION_PASSES
				? VerificationDecision.REPAIR : VerificationDecision.FAIL;
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	public enum Phase {
		CANONICALIZE("canonicalizing"),
		LOAD_CHUNKS("loading chunks"),
		CLEAR("clearing site"),
		APPLY("processing blueprint blocks"),
		VERIFY("verifying"),
		REPAIR("repairing arena"),
		COMPLETE("complete"),
		FAILED("failed");

		private final String displayName;

		Phase(String displayName) {
			this.displayName = displayName;
		}

		public String displayName() {
			return displayName;
		}
	}

	public enum VerificationDecision {
		COMPLETE,
		REPAIR,
		FAIL
	}

	public record Tick(
			Phase phase,
			int worked,
			int completed,
			int total,
			int changedBlocks,
			Optional<ScenarioResetReceipt> receipt,
			String failureReason
	) {
		public Tick {
			Objects.requireNonNull(phase, "phase must not be null");
			if (worked < 0 || completed < 0 || completed > total || total < 0 || changedBlocks < 0) {
				throw new IllegalArgumentException("progress is invalid");
			}
			receipt = Objects.requireNonNull(receipt, "receipt must not be null");
			failureReason = Objects.requireNonNull(failureReason, "failureReason must not be null");
		}
	}
}
