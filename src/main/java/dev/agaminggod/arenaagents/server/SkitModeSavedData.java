package dev.agaminggod.arenaagents.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** World-scoped persistence for the deliberately opt-in skit workspace. */
public final class SkitModeSavedData extends SavedData {
	private static final Codec<SkitPlacement> PLACEMENT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("dimension").forGetter(SkitPlacement::dimension),
			Codec.DOUBLE.fieldOf("x").forGetter(SkitPlacement::x),
			Codec.DOUBLE.fieldOf("y").forGetter(SkitPlacement::y),
			Codec.DOUBLE.fieldOf("z").forGetter(SkitPlacement::z),
			Codec.FLOAT.fieldOf("yaw").forGetter(SkitPlacement::yaw),
			Codec.FLOAT.fieldOf("pitch").forGetter(SkitPlacement::pitch)
	).apply(instance, SkitPlacement::new));
	private static final Codec<SkitStep> STEP_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("delay_ticks").forGetter(SkitStep::delayTicks),
			PLACEMENT_CODEC.fieldOf("placement").forGetter(SkitStep::placement),
			SkitActionCodec.CODEC.listOf().optionalFieldOf("actions", List.of()).forGetter(SkitStep::actions)
	).apply(instance, SkitStep::new));
	private static final Codec<SkitScript> SCRIPT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("name").forGetter(SkitScript::name),
			Codec.STRING.fieldOf("agent").forGetter(SkitScript::agentSelector),
			STEP_CODEC.listOf().fieldOf("steps").forGetter(SkitScript::steps)
	).apply(instance, SkitScript::new));
	private static final Codec<SkitModeSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.optionalFieldOf("enabled", false).forGetter(SkitModeSavedData::enabled),
			Codec.unboundedMap(Codec.STRING, PLACEMENT_CODEC).optionalFieldOf("placements", Map.of())
					.forGetter(data -> data.placements),
			SCRIPT_CODEC.listOf().optionalFieldOf("scripts", List.of()).forGetter(data -> List.copyOf(data.scripts.values()))
	).apply(instance, SkitModeSavedData::decode));
	public static final SavedDataType<SkitModeSavedData> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("arenaagents", "skit_mode"), SkitModeSavedData::new, CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
	private static final int MAX_SCRIPTS = 128;
	private boolean enabled;
	private final Map<String, SkitPlacement> placements;
	private final Map<String, SkitScript> scripts;

	public SkitModeSavedData() {
		this(false, Map.of(), List.of());
	}

	private SkitModeSavedData(boolean enabled, Map<String, SkitPlacement> placements, List<SkitScript> scripts) {
		this.enabled = enabled;
		this.placements = new LinkedHashMap<>(Objects.requireNonNull(placements, "placements must not be null"));
		this.scripts = new LinkedHashMap<>();
		if (scripts.size() > MAX_SCRIPTS) throw new IllegalArgumentException("Too many persisted skit scripts");
		for (SkitScript script : scripts) {
			if (this.scripts.put(script.name(), script) != null) throw new IllegalArgumentException("Duplicate skit script: " + script.name());
		}
	}

	private static SkitModeSavedData decode(boolean enabled, Map<String, SkitPlacement> placements, List<SkitScript> scripts) {
		return new SkitModeSavedData(enabled, placements, scripts);
	}

	public static SkitModeSavedData get(MinecraftServer server) {
		return Objects.requireNonNull(server, "server must not be null").overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public boolean enabled() { return enabled; }

	public void setEnabled(boolean enabled) {
		if (this.enabled != enabled) { this.enabled = enabled; setDirty(); }
	}

	public Map<String, SkitPlacement> placements() { return Map.copyOf(placements); }

	public SkitPlacement placement(String agentSelector) { return placements.get(agentSelector); }

	public void putPlacement(String agentSelector, SkitPlacement placement) {
		placements.put(Objects.requireNonNull(agentSelector), Objects.requireNonNull(placement));
		setDirty();
	}

	public SkitScript script(String name) { return scripts.get(name); }

	public List<SkitScript> scripts() { return List.copyOf(scripts.values()); }

	public void putScript(SkitScript script) {
		Objects.requireNonNull(script, "script must not be null");
		if (!scripts.containsKey(script.name()) && scripts.size() >= MAX_SCRIPTS) throw new IllegalArgumentException("Too many skit scripts");
		scripts.put(script.name(), script);
		setDirty();
	}

	public boolean removeScript(String name) {
		if (scripts.remove(name) == null) return false;
		setDirty();
		return true;
	}
}
