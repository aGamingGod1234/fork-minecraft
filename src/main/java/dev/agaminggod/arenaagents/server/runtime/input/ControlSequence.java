package dev.agaminggod.arenaagents.server.runtime.input;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.InteractionHand;

/** Executes only frames and conditional jumps supplied by the model, with a total tick budget. */
public final class ControlSequence {
	private final List<Frame> frames;
	private final int maxTicks;
	private int frameIndex;
	private int frameTicks;
	private int elapsedTicks;
	private Status terminal;

	public ControlSequence(List<Frame> frames, int maxTicks) {
		this.frames = List.copyOf(frames);
		if (frames.isEmpty() || frames.size() > 64) throw new IllegalArgumentException("frames must contain 1..64 inputs");
		if (maxTicks < 1 || maxTicks > 2000) throw new IllegalArgumentException("maxTicks must be 1..2000");
		for (Frame frame : frames) {
			for (Branch branch : frame.branches()) {
				if (branch.nextFrame() < 0 || branch.nextFrame() > frames.size()) {
					throw new IllegalArgumentException("nextFrame must address a frame or the end of the sequence");
				}
			}
		}
		this.maxTicks = maxTicks;
	}

	public static ControlSequence parse(JsonObject arguments) {
		List<Frame> frames = new ArrayList<>();
		for (var element : arguments.getAsJsonArray("frames")) {
			JsonObject frame = element.getAsJsonObject();
			List<Branch> branches = new ArrayList<>();
			if (frame.has("branches") && !frame.get("branches").isJsonNull()) {
				for (var branchElement : frame.getAsJsonArray("branches")) {
					JsonObject branch = branchElement.getAsJsonObject();
					String condition = branch.get("condition").getAsString();
					boolean numeric = condition.endsWith("_below");
					branches.add(new Branch(condition, numeric ? branch.get("value").getAsDouble() :
							(branch.get("value").getAsBoolean() ? 1.0D : 0.0D), branch.get("nextFrame").getAsInt()));
				}
			}
			frames.add(new Frame(input(frame), frame.get("ticks").getAsInt(), branches));
		}
		return new ControlSequence(frames, arguments.get("maxTicks").getAsInt());
	}

	public static AgentInputState input(JsonObject frame) {
		InteractionHand hand = switch (frame.get("hand").getAsString()) {
			case "main" -> InteractionHand.MAIN_HAND;
			case "off" -> InteractionHand.OFF_HAND;
			default -> throw new IllegalArgumentException("hand must be main or off");
		};
		return new AgentInputState(frame.get("forward").getAsFloat(), frame.get("strafe").getAsFloat(),
				frame.get("jump").getAsBoolean(), frame.get("sneak").getAsBoolean(), frame.get("sprint").getAsBoolean(),
				frame.get("attack").getAsBoolean(), frame.get("use").getAsBoolean(), frame.get("yaw").getAsFloat(),
				frame.get("pitch").getAsFloat(), frame.get("selectedSlot").getAsInt(), hand);
	}

	/** Each running step supplies input for the next server tick; completion releases the previous input. */
	public Step next(Facts facts) {
		Objects.requireNonNull(facts, "facts must not be null");
		if (terminal != null) return step(null, terminal);
		if (frameTicks == frames.get(frameIndex).ticks()) {
			frameIndex++;
			frameTicks = 0;
		}
		if (frameIndex == frames.size()) return finish(Status.COMPLETED);
		if (elapsedTicks >= maxTicks) return finish(Status.BUDGET_EXHAUSTED);
		for (Branch branch : frames.get(frameIndex).branches()) {
			if (branch.matches(facts)) {
				frameIndex = branch.nextFrame();
				frameTicks = 0;
				if (frameIndex == frames.size()) return finish(Status.BRANCH_STOPPED);
				break;
			}
		}
		frameTicks++;
		elapsedTicks++;
		return step(frames.get(frameIndex).input(), Status.RUNNING);
	}

	private Step finish(Status status) {
		terminal = status;
		return step(null, status);
	}

	private Step step(AgentInputState input, Status status) {
		return new Step(input, status, frameIndex, elapsedTicks, maxTicks);
	}

	public record Frame(AgentInputState input, int ticks, List<Branch> branches) {
		public Frame {
			Objects.requireNonNull(input, "input must not be null");
			if (ticks < 1 || ticks > 200) throw new IllegalArgumentException("frame ticks must be 1..200");
			branches = List.copyOf(branches);
			if (branches.size() > 16) throw new IllegalArgumentException("a frame may have at most 16 branches");
		}
	}

	public record Branch(String condition, double value, int nextFrame) {
		public Branch {
			Objects.requireNonNull(condition, "condition must not be null");
			double maximum = switch (condition) {
				case "health_below" -> 2048.0D;
				case "food_below" -> 20.0D;
				case "air_below" -> 100000.0D;
				case "on_fire", "in_water", "on_ground", "horizontal_collision", "hurt", "using_item" -> 1.0D;
				default -> throw new IllegalArgumentException("Unknown observed control condition: " + condition);
			};
			if (!Double.isFinite(value) || value < 0 || value > maximum
					|| (!condition.endsWith("_below") && value != 0 && value != 1)) {
				throw new IllegalArgumentException("Invalid observed control condition value");
			}
		}

		boolean matches(Facts facts) {
			return switch (condition) {
				case "health_below" -> facts.health() < value;
				case "food_below" -> facts.food() < value;
				case "air_below" -> facts.air() < value;
				case "on_fire" -> facts.onFire() == (value == 1);
				case "in_water" -> facts.inWater() == (value == 1);
				case "on_ground" -> facts.onGround() == (value == 1);
				case "horizontal_collision" -> facts.horizontalCollision() == (value == 1);
				case "hurt" -> facts.hurt() == (value == 1);
				case "using_item" -> facts.usingItem() == (value == 1);
				default -> throw new IllegalStateException("Unvalidated control condition");
			};
		}
	}

	public record Facts(double health, int food, int air, boolean onFire, boolean inWater,
			boolean onGround, boolean horizontalCollision, boolean hurt, boolean usingItem) { }

	public enum Status { RUNNING, COMPLETED, BRANCH_STOPPED, BUDGET_EXHAUSTED }
	public record Step(AgentInputState input, Status status, int frameIndex, int elapsedTicks, int maxTicks) { }
}
