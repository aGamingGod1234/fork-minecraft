package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import java.util.List;

public final class CoordinatorStatusVerification {
	private CoordinatorStatusVerification() {
	}

	public static void main(String[] args) {
		System.out.println("CoordinatorStatusVerification assertions=" + verify());
	}

	public static int verify() {
		JsonObject payload = payload();
		CoordinatorStatusSnapshot snapshot = MultiplexedServerBridge.decodeCoordinatorStatus(payload, 1_000L);
		assertTrue(snapshot.reconciled(), "reconciled status decoded");
		assertTrue(snapshot.supports("agent-a", "codex", "gpt-5.6-sol", "high"), "supported identity decoded");
		assertTrue(snapshot.fresh(3_500L, 2_500L), "freshness boundary inclusive");
		assertTrue(snapshot.latencies().size() == 1, "latency health decoded");
		JsonObject recovery = extendedRecoveryPayload();
		CoordinatorStatusSnapshot recovered = MultiplexedServerBridge.decodeCoordinatorStatus(recovery, 1_000L);
		assertTrue(recovered.bridgeSessionEpoch() == 7L, "bridge session epoch decoded");
		assertTrue(recovered.runtimeGeneration().equals("a".repeat(64)), "runtime generation decoded");
		assertTrue(recovered.profiles().getFirst().serviceTier().equals("priority"), "exact service tier decoded");
		assertTrue(recovered.components().getFirst().boundary().equals("create"), "failing component boundary decoded");
		JsonObject fractionalLatency = payload();
		fractionalLatency.getAsJsonArray("latencies").get(0).getAsJsonObject().addProperty("p50Ms", 25.25D);
		assertTrue(MultiplexedServerBridge.decodeCoordinatorStatus(fractionalLatency, 1_000L).latencies().getFirst().p50Ms() == 25.25D,
				"fractional local latency is preserved");
		JsonObject legacy = payload();
		legacy.remove("latencies");
		assertTrue(MultiplexedServerBridge.decodeCoordinatorStatus(legacy, 1_000L).latencies().isEmpty(),
				"legacy status without latencies remains compatible");
		JsonObject extendedScheduler = payload();
		extendedScheduler.getAsJsonObject("scheduler").addProperty("mode", "adaptive");
		extendedScheduler.getAsJsonObject("scheduler").addProperty("configuredTarget", 4);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("target", 4);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("minConcurrency", 4);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("maxConcurrency", 16);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("urgentReserve", 1);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("ordinaryActiveLimit", 3);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("activeOrdinary", 0);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("activeUrgent", 0);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("pendingOrdinary", 0);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("pendingUrgent", 0);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("growthCount", 0);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("backoffCount", 0);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("lastChangeReason", "initial");
		extendedScheduler.getAsJsonObject("scheduler").addProperty("healthyCompletions", 0);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("ordinaryReservationRejections", 0);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("urgentReservationRejections", 0);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("active", 5);
		extendedScheduler.getAsJsonObject("scheduler").addProperty("target", 5);
		assertTrue(MultiplexedServerBridge.decodeCoordinatorStatus(extendedScheduler, 1_000L).scheduler().maxConcurrent() == 4,
				"extended scheduler telemetry remains wire-compatible");
		assertTrue(MultiplexedServerBridge.decodeCoordinatorStatus(extendedScheduler, 1_000L).scheduler().hardConcurrentLimit() == 16,
				"adaptive growth validates against the hard concurrency limit");
		extendedScheduler.getAsJsonObject("scheduler").addProperty("privateSchedulerField", "must not cross");
		assertThrows(() -> MultiplexedServerBridge.decodeCoordinatorStatus(extendedScheduler, 1_000L), "unknown scheduler field rejected");
		JsonObject numericIdentity = payload();
		numericIdentity.getAsJsonArray("profiles").get(0).getAsJsonObject().addProperty("provider", 7);
		assertThrows(() -> MultiplexedServerBridge.decodeCoordinatorStatus(numericIdentity, 1_000L), "numeric status identity rejected");
		payload.addProperty("prompt", "must never cross this seam");
		assertThrows(() -> MultiplexedServerBridge.decodeCoordinatorStatus(payload, 1_000L), "unknown private field rejected");
		JsonObject invalidLatency = payload();
		invalidLatency.getAsJsonArray("latencies").get(0).getAsJsonObject().addProperty("p95Ms", -1);
		assertThrows(() -> MultiplexedServerBridge.decodeCoordinatorStatus(invalidLatency, 1_000L), "invalid latency rejected");
		JsonObject catalog = catalogPayload();
		List<AgentControlModelOption> models = MultiplexedServerBridge.decodeCatalog(catalog);
		assertTrue(models.size() == 1, "catalog model decoded");
		assertTrue(models.getFirst().displayName().equals("GPT Future"), "catalog display name preserved");
		assertTrue(models.getFirst().reasoningEfforts().equals(List.of("medium", "ultra")),
				"catalog reasoning efforts preserved");
		assertTrue(models.getFirst().serviceTiers().equals(List.of("priority", "fast")),
				"catalog speed tiers preserved");
		catalog.addProperty("privatePrompt", "must never cross this seam");
		assertThrows(() -> MultiplexedServerBridge.decodeCatalog(catalog), "unknown catalog field rejected");
		return 20;
	}

	private static JsonObject extendedRecoveryPayload() {
		JsonObject payload = payload();
		payload.getAsJsonArray("profiles").get(0).getAsJsonObject().addProperty("serviceTier", "priority");
		payload.addProperty("bridgeSessionEpoch", 7L);
		payload.addProperty("runtimeGeneration", "a".repeat(64));
		JsonArray components = new JsonArray();
		JsonObject component = new JsonObject();
		component.addProperty("component", "provider:codex");
		component.addProperty("state", "degraded");
		component.addProperty("fallbackMode", "last_valid");
		component.addProperty("boundary", "create");
		component.addProperty("failureCode", "PROVIDER_TIMEOUT");
		component.addProperty("consecutiveFailureCount", 2);
		component.addProperty("nextProbeAtEpochMs", 4_000L);
		component.addProperty("generation", 3L);
		component.add("lastRecoveryAtEpochMs", com.google.gson.JsonNull.INSTANCE);
		components.add(component);
		payload.add("components", components);
		return payload;
	}

	private static JsonObject catalogPayload() {
		JsonObject payload = new JsonObject();
		payload.addProperty("refreshedAtEpochMs", 1_000L);
		JsonArray models = new JsonArray();
		JsonObject model = new JsonObject();
		model.addProperty("provider", "codex");
		model.addProperty("id", "gpt-future");
		model.addProperty("model", "gpt-future-wire");
		model.addProperty("displayName", "GPT Future");
		JsonArray efforts = new JsonArray();
		efforts.add("medium");
		efforts.add("ultra");
		model.add("reasoningEfforts", efforts);
		JsonArray tiers = new JsonArray();
		tiers.add("priority");
		tiers.add("fast");
		model.add("serviceTiers", tiers);
		models.add(model);
		payload.add("models", models);
		return payload;
	}

	private static JsonObject payload() {
		JsonObject payload = new JsonObject();
		payload.addProperty("reconciled", true);
		JsonArray profiles = new JsonArray();
		JsonObject profile = new JsonObject();
		profile.addProperty("agentId", "agent-a");
		profile.addProperty("provider", "codex");
		profile.addProperty("model", "gpt-5.6-sol");
		profile.addProperty("reasoningEffort", "high");
		profiles.add(profile);
		payload.add("profiles", profiles);
		payload.addProperty("supportedProfileCount", 1);
		payload.addProperty("rosterReadyCount", 1);
		payload.addProperty("rosterCount", 1);
		JsonObject scheduler = new JsonObject();
		scheduler.addProperty("active", 0);
		scheduler.addProperty("pending", 0);
		scheduler.addProperty("maxConcurrent", 4);
		scheduler.addProperty("maxPending", 12);
		scheduler.addProperty("warning", false);
		payload.add("scheduler", scheduler);
		JsonArray circuits = new JsonArray();
		JsonObject circuit = new JsonObject();
		circuit.addProperty("provider", "codex");
		circuit.addProperty("model", "gpt-5.6-sol");
		circuit.addProperty("operation", "decide");
		circuit.addProperty("count", 0);
		circuit.addProperty("p50Ms", 0);
		circuit.addProperty("p95Ms", 0);
		circuit.addProperty("failureRate", 0.0);
		circuit.addProperty("circuit", "closed");
		circuits.add(circuit);
		payload.add("circuits", circuits);
		JsonArray latencies = new JsonArray();
		JsonObject latency = new JsonObject();
		latency.addProperty("operation", "observation_to_plan");
		latency.addProperty("count", 8);
		latency.addProperty("p50Ms", 25);
		latency.addProperty("p95Ms", 80);
		latencies.add(latency);
		payload.add("latencies", latencies);
		return payload;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
		} catch (BridgeProtocolException expected) {
			return;
		}
		throw new AssertionError(label);
	}
}
