package dev.agaminggod.arenaagents.server;

import com.google.gson.JsonArray;
import com.mojang.serialization.JsonOps;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

public final class ChunkedSavedPayloadVerification {
	private ChunkedSavedPayloadVerification() {
	}

	public static void main(String[] arguments) throws IOException {
		System.out.println("PASS: " + verify() + " chunked SavedData assertions");
	}

	public static int verify() throws IOException {
		String payload = ("agent-state-☃-" + "x".repeat(997)).repeat(90);
		List<String> chunks = ChunkedSavedPayload.split(payload);
		assertTrue(chunks.size() > 1, "oversized payload is split");
		for (String chunk : chunks) {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream output = new DataOutputStream(bytes)) {
				output.writeUTF(chunk);
			}
			assertTrue(bytes.size() <= 65_537, "each NBT UTF payload remains encodable");
		}
		assertEquals(payload, ChunkedSavedPayload.join("legacy", chunks), "chunked payload round trip");
		assertEquals("legacy", ChunkedSavedPayload.join("legacy", List.of()), "legacy payload migration");
		assertThrows(() -> ChunkedSavedPayload.join("", java.util.Collections.nCopies(1_025, "")),
				"chunk count is rejected before joining");
		String maximumChunk = "x".repeat(60_000);
		assertThrows(() -> ChunkedSavedPayload.join("", List.of(maximumChunk + "x")),
				"individual chunk byte limit is enforced");
		assertThrows(() -> ChunkedSavedPayload.join("", java.util.Collections.nCopies(280, maximumChunk)),
				"aggregate payload byte limit is enforced");
		JsonArray excessiveChunks = new JsonArray();
		for (int index = 0; index < 1_025; index++) excessiveChunks.add("");
		assertTrue(ChunkedSavedPayload.chunksCodec().parse(JsonOps.INSTANCE, excessiveChunks).result().isEmpty(),
				"SavedData codec rejects excessive chunk lists before record construction");
		return 8 + chunks.size();
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
		} catch (IllegalArgumentException expected) {
			return;
		}
		throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
