package dev.agaminggod.arenaagents.server.voice;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** World-scoped persistence for skit voice profiles and speech cues. */
public final class VoiceDirectorSavedData extends SavedData {
	private static final Codec<VoiceProfile> PROFILE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("profile").forGetter(VoiceProfile::profileId),
			Codec.STRING.optionalFieldOf("tone", VoiceProfile.DEFAULT_TONE).forGetter(VoiceProfile::tone),
			Codec.DOUBLE.optionalFieldOf("speed", VoiceProfile.DEFAULT_SPEED).forGetter(VoiceProfile::speed),
			Codec.INT.optionalFieldOf("radius", VoiceProfile.DEFAULT_RADIUS).forGetter(VoiceProfile::radius)
	).apply(instance, VoiceProfile::new));
	private static final Codec<VoiceCue> CUE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("delay_ticks").forGetter(VoiceCue::delayTicks),
			Codec.STRING.fieldOf("text").forGetter(VoiceCue::text),
			Codec.STRING.optionalFieldOf("profile", "").forGetter(VoiceCue::profileId),
			Codec.STRING.optionalFieldOf("tone", "").forGetter(VoiceCue::tone),
			Codec.DOUBLE.optionalFieldOf("speed", -1.0D).forGetter(VoiceCue::speed),
			Codec.INT.optionalFieldOf("radius", 0).forGetter(VoiceCue::radius)
	).apply(instance, VoiceCue::new));
	private static final Codec<VoiceScript> SCRIPT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("name").forGetter(VoiceScript::name),
			Codec.STRING.fieldOf("agent").forGetter(VoiceScript::agentSelector),
			CUE_CODEC.listOf().fieldOf("cues").forGetter(VoiceScript::cues)
	).apply(instance, VoiceScript::new));
	private static final Codec<VoiceDirectorSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.unboundedMap(Codec.STRING, PROFILE_CODEC).optionalFieldOf("profiles", Map.of())
					.forGetter(data -> data.profiles),
			SCRIPT_CODEC.listOf().optionalFieldOf("scripts", List.of())
					.forGetter(data -> List.copyOf(data.scripts.values()))
	).apply(instance, VoiceDirectorSavedData::decode));
	public static final SavedDataType<VoiceDirectorSavedData> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("arenaagents", "voice_director"),
			VoiceDirectorSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
	private static final int MAX_PROFILES = 256;
	private static final int MAX_SCRIPTS = 128;
	private final Map<String, VoiceProfile> profiles;
	private final Map<String, VoiceScript> scripts;

	public VoiceDirectorSavedData() {
		this(Map.of(), List.of());
	}

	private VoiceDirectorSavedData(Map<String, VoiceProfile> profiles, List<VoiceScript> scripts) {
		if (profiles.size() > MAX_PROFILES) throw new IllegalArgumentException("Too many persisted voice profiles");
		if (scripts.size() > MAX_SCRIPTS) throw new IllegalArgumentException("Too many persisted voice scripts");
		this.profiles = new LinkedHashMap<>(Objects.requireNonNull(profiles, "profiles must not be null"));
		this.scripts = new LinkedHashMap<>();
		for (VoiceScript script : scripts) {
			if (this.scripts.put(script.name(), script) != null) throw new IllegalArgumentException("Duplicate voice script: " + script.name());
		}
	}

	private static VoiceDirectorSavedData decode(Map<String, VoiceProfile> profiles, List<VoiceScript> scripts) {
		return new VoiceDirectorSavedData(profiles, scripts);
	}

	public static VoiceDirectorSavedData get(MinecraftServer server) {
		return Objects.requireNonNull(server, "server must not be null").overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public VoiceProfile profile(AgentId agentId) {
		return profiles.getOrDefault(agentId.toString(), VoiceProfile.defaults());
	}

	public Map<String, VoiceProfile> profiles() { return Map.copyOf(profiles); }

	public void putProfile(AgentId agentId, VoiceProfile profile) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(profile, "profile must not be null");
		if (!profiles.containsKey(agentId.toString()) && profiles.size() >= MAX_PROFILES) throw new IllegalArgumentException("Too many voice profiles");
		profiles.put(agentId.toString(), profile);
		setDirty();
	}

	public VoiceScript script(String name) { return scripts.get(name); }
	public List<VoiceScript> scripts() { return List.copyOf(scripts.values()); }

	public void putScript(VoiceScript script) {
		Objects.requireNonNull(script, "script must not be null");
		if (!scripts.containsKey(script.name()) && scripts.size() >= MAX_SCRIPTS) throw new IllegalArgumentException("Too many voice scripts");
		scripts.put(script.name(), script);
		setDirty();
	}

	public boolean removeScript(String name) {
		if (scripts.remove(name) == null) return false;
		setDirty();
		return true;
	}
}
