package dev.agaminggod.arenaagents.scenario.presentation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ArenaSpectatorSnapshotPayload(String encodedUpdate) implements CustomPacketPayload {
	public static final int MAX_ENCODED_BYTES = 8_192;
	private static final int SCHEMA_VERSION = 1;
	private static final Set<String> FULL_KEYS = Set.of("schemaVersion", "kind", "revision", "snapshot");
	private static final Set<String> DELTA_KEYS = Set.of("schemaVersion", "kind", "revision", "baseRevision", "changes");
	public static final Type<ArenaSpectatorSnapshotPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "arena_spectator_snapshot")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, ArenaSpectatorSnapshotPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_ENCODED_BYTES),
			ArenaSpectatorSnapshotPayload::encodedUpdate,
			ArenaSpectatorSnapshotPayload::new
	);

	public ArenaSpectatorSnapshotPayload {
		encodedUpdate = Objects.requireNonNull(encodedUpdate, "encodedUpdate must not be null");
		if (encodedUpdate.isBlank()) throw new IllegalArgumentException("encodedUpdate must not be blank");
		if (encodedUpdate.getBytes(StandardCharsets.UTF_8).length > MAX_ENCODED_BYTES) {
			throw new IllegalArgumentException("spectator payload exceeds " + MAX_ENCODED_BYTES + " UTF-8 bytes");
		}
		validateEnvelope(parse(encodedUpdate));
	}

	public static ArenaSpectatorSnapshotPayload full(ArenaSpectatorSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", SCHEMA_VERSION);
		root.addProperty("kind", Kind.FULL.wireName);
		root.addProperty("revision", snapshot.revision());
		root.add("snapshot", snapshot.toJsonBody());
		return new ArenaSpectatorSnapshotPayload(root.toString());
	}

	public static ArenaSpectatorSnapshotPayload delta(
			ArenaSpectatorSnapshot previous,
			ArenaSpectatorSnapshot next
	) {
		Objects.requireNonNull(previous, "previous must not be null");
		Objects.requireNonNull(next, "next must not be null");
		if (!previous.runId().equals(next.runId())) {
			throw new IllegalArgumentException("delta snapshots must belong to the same run");
		}
		if (next.revision() <= previous.revision()) {
			throw new IllegalArgumentException("delta revision must increase");
		}
		JsonObject before = previous.toJsonBody();
		JsonObject after = next.toJsonBody();
		JsonObject changes = new JsonObject();
		for (String key : ArenaSpectatorSnapshot.bodyKeys()) {
			JsonElement oldValue = before.get(key);
			JsonElement newValue = after.get(key);
			if (!Objects.equals(oldValue, newValue)) changes.add(key, newValue.deepCopy());
		}
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", SCHEMA_VERSION);
		root.addProperty("kind", Kind.DELTA.wireName);
		root.addProperty("revision", next.revision());
		root.addProperty("baseRevision", previous.revision());
		root.add("changes", changes);
		return new ArenaSpectatorSnapshotPayload(root.toString());
	}

	public Kind kind() {
		return kind(parse(encodedUpdate));
	}

	public long revision() {
		return ArenaSpectatorSnapshot.exactLong(parse(encodedUpdate), "revision");
	}

	public long baseRevision() {
		JsonObject root = parse(encodedUpdate);
		return kind(root) == Kind.DELTA ? ArenaSpectatorSnapshot.exactLong(root, "baseRevision") : 0L;
	}

	public ArenaSpectatorSnapshot applyTo(Optional<ArenaSpectatorSnapshot> base) {
		Objects.requireNonNull(base, "base must not be null");
		JsonObject root = parse(encodedUpdate);
		long revision = ArenaSpectatorSnapshot.exactLong(root, "revision");
		if (kind(root) == Kind.FULL) {
			return ArenaSpectatorSnapshot.fromJsonBody(revision,
					ArenaSpectatorSnapshot.object(root.get("snapshot"), "snapshot"));
		}
		ArenaSpectatorSnapshot current = base.orElseThrow(() ->
				new IllegalArgumentException("delta payload requires a current snapshot"));
		long expectedBase = ArenaSpectatorSnapshot.exactLong(root, "baseRevision");
		if (current.revision() != expectedBase) {
			throw new IllegalArgumentException("delta base revision does not match current snapshot");
		}
		JsonObject merged = current.toJsonBody();
		JsonObject changes = ArenaSpectatorSnapshot.object(root.get("changes"), "changes");
		for (String key : changes.keySet()) merged.add(key, changes.get(key).deepCopy());
		return ArenaSpectatorSnapshot.fromJsonBody(revision, merged);
	}

	@Override
	public Type<ArenaSpectatorSnapshotPayload> type() {
		return TYPE;
	}

	private static void validateEnvelope(JsonObject root) {
		int version = ArenaSpectatorSnapshot.exactInt(root, "schemaVersion");
		if (version != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported spectator payload schema");
		Kind kind = kind(root);
		long revision = ArenaSpectatorSnapshot.exactLong(root, "revision");
		if (revision <= 0L) throw new IllegalArgumentException("payload revision must be positive");
		if (kind == Kind.FULL) {
			ArenaSpectatorSnapshot.requireExactKeys(root, FULL_KEYS, "spectator full envelope");
			ArenaSpectatorSnapshot.fromJsonBody(
					revision, ArenaSpectatorSnapshot.object(root.get("snapshot"), "snapshot"));
			return;
		}
		ArenaSpectatorSnapshot.requireExactKeys(root, DELTA_KEYS, "spectator delta envelope");
		long baseRevision = ArenaSpectatorSnapshot.exactLong(root, "baseRevision");
		if (baseRevision <= 0L || revision <= baseRevision) {
			throw new IllegalArgumentException("delta revisions are invalid");
		}
		JsonObject changes = ArenaSpectatorSnapshot.object(root.get("changes"), "changes");
		if (!ArenaSpectatorSnapshot.bodyKeys().containsAll(changes.keySet())) {
			throw new IllegalArgumentException("delta contains unknown snapshot fields");
		}
	}

	private static Kind kind(JsonObject root) {
		String wireName = ArenaSpectatorSnapshot.string(root, "kind");
		for (Kind kind : Kind.values()) if (kind.wireName.equals(wireName)) return kind;
		throw new IllegalArgumentException("unsupported spectator payload kind");
	}

	private static JsonObject parse(String encoded) {
		try {
			JsonElement value = JsonParser.parseString(encoded);
			if (!value.isJsonObject()) throw new IllegalArgumentException("spectator payload must be a JSON object");
			return value.getAsJsonObject();
		} catch (JsonParseException exception) {
			throw new IllegalArgumentException("spectator payload is invalid JSON", exception);
		}
	}

	public enum Kind {
		FULL("full"),
		DELTA("delta");

		private final String wireName;

		Kind(String wireName) {
			this.wireName = wireName;
		}
	}
}
