package dev.agaminggod.arenaagents.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Locale;

/** NBT codec kept separate so the action model stays usable by command and test code. */
final class SkitActionCodec {
	private static final Codec<SkitAction.Type> TYPE_CODEC = Codec.STRING.xmap(
			value -> SkitAction.Type.valueOf(value.toUpperCase(Locale.ROOT)),
			value -> value.name().toLowerCase(Locale.ROOT));

	static final Codec<SkitAction> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			TYPE_CODEC.fieldOf("type").forGetter(SkitAction::type),
			Codec.INT.optionalFieldOf("duration_ticks", 0).forGetter(SkitAction::durationTicks),
			Codec.FLOAT.optionalFieldOf("forward", 0.0F).forGetter(SkitAction::forward),
			Codec.FLOAT.optionalFieldOf("strafe", 0.0F).forGetter(SkitAction::strafe),
			Codec.BOOL.optionalFieldOf("sprint", false).forGetter(SkitAction::sprint),
			Codec.BOOL.optionalFieldOf("sneak", false).forGetter(SkitAction::sneak),
			Codec.STRING.optionalFieldOf("item", "").forGetter(SkitAction::itemId)
	).apply(instance, SkitAction::new));

	private SkitActionCodec() {
	}
}
