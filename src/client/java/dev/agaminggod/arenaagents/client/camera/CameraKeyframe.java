package dev.agaminggod.arenaagents.client.camera;

/** An absolute camera pose at a point in a recorded path. */
public record CameraKeyframe(int tick, double x, double y, double z, float yaw, float pitch) {
	public CameraKeyframe {
		if (tick < 0) throw new IllegalArgumentException("tick must not be negative");
		finite(x, "x");
		finite(y, "y");
		finite(z, "z");
		if (!Float.isFinite(yaw)) throw new IllegalArgumentException("yaw must be finite");
		if (!Float.isFinite(pitch) || pitch < -90.0F || pitch > 90.0F) {
			throw new IllegalArgumentException("pitch must be finite and between -90 and 90");
		}
	}

	private static void finite(double value, String field) {
		if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
	}
}
