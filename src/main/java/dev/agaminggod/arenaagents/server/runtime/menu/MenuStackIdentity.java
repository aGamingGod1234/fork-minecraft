package dev.agaminggod.arenaagents.server.runtime.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** An opaque identity for an observed item variant, independent of stack count. */
public final class MenuStackIdentity {
	private static final Map<ItemStack, Cached> CACHE = new WeakHashMap<>();
	private static final int MAX_CACHE_ENTRIES = 2_048;
	private MenuStackIdentity() {
	}

	public static synchronized String fingerprint(ItemStack stack, HolderLookup.Provider registries) {
		if (stack.isEmpty()) return "";
		DataComponentPatch patch = stack.getComponentsPatch();
		Cached cached = CACHE.get(stack);
		if (cached != null && cached.item() == stack.getItem() && cached.registries() == registries && cached.patch().equals(patch)) {
			return cached.fingerprint();
		}
		JsonElement encoded = ItemStack.CODEC.encodeStart(
				RegistryOps.create(JsonOps.INSTANCE, registries), stack.copyWithCount(1)).getOrThrow();
		try {
			String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical(encoded).toString().getBytes(StandardCharsets.UTF_8)));
			if (CACHE.size() >= MAX_CACHE_ENTRIES) CACHE.clear();
			CACHE.put(stack, new Cached(stack.getItem(), patch, registries, fingerprint));
			return fingerprint;
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private record Cached(Item item, DataComponentPatch patch, HolderLookup.Provider registries, String fingerprint) {
	}

	private static JsonElement canonical(JsonElement value) {
		if (value.isJsonObject()) {
			JsonObject sorted = new JsonObject();
			value.getAsJsonObject().keySet().stream().sorted()
					.forEach(key -> sorted.add(key, canonical(value.getAsJsonObject().get(key))));
			return sorted;
		}
		if (value.isJsonArray()) {
			JsonArray array = new JsonArray();
			value.getAsJsonArray().forEach(element -> array.add(canonical(element)));
			return array;
		}
		return value;
	}
}
