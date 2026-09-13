package dev.agaminggod.arenaagents.client.camera;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** A bounded, sorted camera timeline with smooth Catmull-Rom position interpolation. */
public record CameraPath(String name, List<CameraKeyframe> keyframes) {
	public static final int MAX_KEYFRAMES = 512;
	public static final int MAX_DURATION_TICKS = 20 * 60 * 60;

	public CameraPath {
		name = requireName(name);
		Objects.requireNonNull(keyframes, "keyframes must not be null");
		if (keyframes.isEmpty()) throw new IllegalArgumentException("a camera path needs at least one keyframe");
		if (keyframes.size() > MAX_KEYFRAMES) throw new IllegalArgumentException("a camera path may contain at most " + MAX_KEYFRAMES + " keyframes");
		ArrayList<CameraKeyframe> sorted = new ArrayList<>(keyframes);
		if (sorted.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("keyframes must not contain null");
		sorted.sort(Comparator.comparingInt(CameraKeyframe::tick));
		for (int i = 1; i < sorted.size(); i++) {
			if (sorted.get(i - 1).tick() == sorted.get(i).tick()) {
				throw new IllegalArgumentException("keyframe ticks must be strictly increasing");
			}
		}
		if (sorted.getLast().tick() > MAX_DURATION_TICKS) throw new IllegalArgumentException("camera path is too long");
		keyframes = List.copyOf(sorted);
	}

	public int durationTicks() {
		return keyframes.getLast().tick();
	}

	public CameraPath append(CameraKeyframe frame) {
		Objects.requireNonNull(frame, "frame must not be null");
		ArrayList<CameraKeyframe> next = new ArrayList<>(keyframes);
		if (frame.tick() == next.getLast().tick()) next.set(next.size() - 1, frame);
		else next.add(frame);
		return new CameraPath(name, next);
	}

	/** Samples this path in ticks. Position is smoothed; angles use shortest-turn interpolation. */
	public CameraPose sample(double tick) {
		if (!Double.isFinite(tick)) throw new IllegalArgumentException("tick must be finite");
		if (keyframes.size() == 1 || tick <= keyframes.getFirst().tick()) return pose(keyframes.getFirst());
		if (tick >= durationTicks()) return pose(keyframes.getLast());
		int right = 1;
		while (right < keyframes.size() && keyframes.get(right).tick() < tick) right++;
		CameraKeyframe b = keyframes.get(right);
		CameraKeyframe a = keyframes.get(right - 1);
		double amount = (tick - a.tick()) / (double) (b.tick() - a.tick());
		double smooth = amount * amount * (3.0D - 2.0D * amount);
		CameraKeyframe before = right >= 2 ? keyframes.get(right - 2) : a;
		CameraKeyframe after = right + 1 < keyframes.size() ? keyframes.get(right + 1) : b;
		return new CameraPose(
				catmull(before.x(), a.x(), b.x(), after.x(), amount),
				catmull(before.y(), a.y(), b.y(), after.y(), amount),
				catmull(before.z(), a.z(), b.z(), after.z(), amount),
				interpolateAngle(a.yaw(), b.yaw(), (float) smooth),
				lerp(a.pitch(), b.pitch(), (float) smooth)
		);
	}

	private static CameraPose pose(CameraKeyframe frame) {
		return new CameraPose(frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch());
	}

	private static double catmull(double p0, double p1, double p2, double p3, double t) {
		return 0.5D * ((2.0D * p1) + (-p0 + p2) * t + (2.0D * p0 - 5.0D * p1 + 4.0D * p2 - p3) * t * t
				+ (-p0 + 3.0D * p1 - 3.0D * p2 + p3) * t * t * t);
	}

	private static float interpolateAngle(float from, float to, float amount) {
		double delta = ((double) to - from) % 360.0D;
		if (delta >= 180.0D) delta -= 360.0D;
		if (delta < -180.0D) delta += 360.0D;
		return (float) (from + delta * amount);
	}

	private static float lerp(float from, float to, float amount) {
		return from + (to - from) * amount;
	}

	private static String requireName(String value) {
		Objects.requireNonNull(value, "name must not be null");
		String checked = value.strip();
		if (checked.isEmpty() || checked.length() > 64 || !checked.matches("[A-Za-z0-9_.-]+")) {
			throw new IllegalArgumentException("name must be 1-64 letters, numbers, '.', '_' or '-'");
		}
		return checked;
	}
}
