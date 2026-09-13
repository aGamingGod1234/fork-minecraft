package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.mixin.CachedUserNameToIdResolverAccessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;

public final class AgentSummonNameVerification {
	private AgentSummonNameVerification() {}

	public static int verify() {
		Path directory = null;
		try {
			directory = Files.createTempDirectory("arena-summon-names-");
			NoLookupCache cache = new NoLookupCache();
			assertFalse(CodexAgentManager.isPersistedPlayerNameReserved("CapabilityProbe", directory, cache, ignored -> false),
					"uncached offline name is available without resolving or synthesizing a profile");
			cache.names.put("existingonline", new NameAndId(UUID.randomUUID(), "ExistingOnline"));
			assertTrue(CodexAgentManager.isPersistedPlayerNameReserved("EXISTINGONLINE", directory, cache, ignored -> false),
					"cached online names remain reserved case-insensitively without remote lookup");
			String savedName = "SavedHuman";
			UUID savedId = AgentIdentity.offlinePlayerUuid(savedName);
			Path saved = directory.resolve(savedId + ".dat");
			Files.write(saved, new byte[0]);
			assertTrue(CodexAgentManager.isPersistedPlayerNameReserved(savedName, directory, cache,
					ignored -> { throw new AssertionError("Existing save must not be parsed or overwritten"); }),
					"even unreadable player data reserves its offline identity");
			Files.delete(saved);
			Path backup = directory.resolve(savedId + ".dat_old");
			Files.write(backup, new byte[0]);
			assertTrue(CodexAgentManager.isPersistedPlayerNameReserved(savedName, directory, cache, ignored -> false),
					"backup player data also protects a disconnected player's identity");
			Files.delete(backup);
			assertTrue(CodexAgentManager.isPersistedPlayerNameReserved(savedName, directory, cache,
					nameAndId -> nameAndId.id().equals(savedId)), "integrated owner data remains protected");

			AgentRegistry registry = AgentRegistry.createDefault(() -> {}, ignored -> {});
			Set<AgentId> pending = new HashSet<>();
			List<AgentId> rejected = new ArrayList<>();
			AtomicInteger checks = new AtomicInteger();
			java.util.function.Function<List<String>, AgentRecord> create = unavailable -> {
				AgentRecord record = registry.create("codex", "gpt-5.6-sol", "high", "priority", Optional.of("CapabilityProbe"),
						AgentGameMode.SURVIVAL, 1000, unavailable);
				pending.add(record.agentId());
				return record;
			};
			java.util.function.Consumer<AgentRecord> reject = record -> {
				rejected.add(record.agentId());
				pending.remove(record.agentId());
				registry.remove(record.agentId());
			};
			AgentRecord allocated = CodexAgentManager.allocatePlayerName(List.of("CapabilityProbe"), create, name -> {
				checks.incrementAndGet();
				return name.equals("CapabilityProbe2");
			}, reject);
			assertEquals("CapabilityProbe3", allocated.profile().userName().orElseThrow(), "live and persisted names both cause normal suffix allocation");
			assertEquals(2, checks.get(), "allocator stops after the first available identity");
			assertEquals(Set.of(allocated.agentId()), pending, "rejected name candidates leave no pending registration");
			assertEquals(1, registry.records().size(), "rejected name candidates leave no registered agent");
			reject.accept(allocated);
			checks.set(0);
			try {
				CodexAgentManager.allocatePlayerName(List.of(), create, name -> { checks.incrementAndGet(); return true; }, reject);
				throw new AssertionError("all-reserved namespace must fail at the bounded limit");
			} catch (AgentDomainException expected) {
				assertEquals("AGENT_NAME_ALLOCATION_EXHAUSTED", expected.code(), "bounded name exhaustion is an explicit domain failure");
			}
			assertEquals(64, checks.get(), "reservation checks are bounded even if every candidate collides");
			assertTrue(pending.isEmpty() && registry.records().isEmpty(), "name exhaustion cleans every candidate");
			try {
				CodexAgentManager.allocatePlayerName(List.of(), create, name -> { throw new IllegalStateException("cache unavailable"); }, reject);
				throw new AssertionError("reservation error must propagate");
			} catch (IllegalStateException expected) {
				assertEquals("cache unavailable", expected.getMessage(), "reservation failure remains visible");
			}
			assertTrue(pending.isEmpty() && registry.records().isEmpty(), "reservation exception also cleans its candidate");
			return 15;
		} catch (java.io.IOException exception) {
			throw new AssertionError("summon name fixture could not be created", exception);
		} finally {
			if (directory != null) {
				try {
					Files.deleteIfExists(directory.resolve(AgentIdentity.offlinePlayerUuid("SavedHuman") + ".dat"));
					Files.deleteIfExists(directory.resolve(AgentIdentity.offlinePlayerUuid("SavedHuman") + ".dat_old"));
					Files.deleteIfExists(directory);
				} catch (java.io.IOException exception) { throw new AssertionError("summon name fixture cleanup failed", exception); }
			}
		}
	}

	private static final class NoLookupCache implements UserNameToIdResolver, CachedUserNameToIdResolverAccessor {
		final Map<String, NameAndId> names = new java.util.concurrent.ConcurrentHashMap<>();
		@Override public Map<String, ?> arenaagents$cachedProfilesByName() { return names; }
		@Override public Optional<NameAndId> get(String name) { throw new AssertionError("Name reservation attempted resolving lookup"); }
		@Override public Optional<NameAndId> get(UUID id) { throw new AssertionError("Name reservation attempted profile lookup"); }
		@Override public void add(NameAndId profile) { throw new AssertionError("Name reservation mutated profile cache"); }
		@Override public void resolveOfflineUsers(boolean value) { throw new AssertionError("Name reservation changed resolver policy"); }
		@Override public void save() { throw new AssertionError("Name reservation saved profile cache"); }
	}

	private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
	private static void assertFalse(boolean value, String message) { assertTrue(!value, message); }
	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}
}
