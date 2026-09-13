package dev.agaminggod.arenaagents.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.client.gui.scenario.ScenarioSetupScreen;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleCycleButton;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;

public final class LocalizationVerification {
	private LocalizationVerification() {
	}

	public static int verify() {
		String source;
		try (InputStream stream = LocalizationVerification.class.getResourceAsStream(
				"/assets/arenaagents/lang/en_us.json")) {
			if (stream == null) throw new AssertionError("English language resource is missing");
			source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new AssertionError("English language resource could not be read", exception);
		}
		JsonObject language = JsonParser.parseString(source).getAsJsonObject();
		Map<String, String> exactCopy = new LinkedHashMap<>();
		exactCopy.put("screen.arenaagents.roster.search", "Search agents");
		exactCopy.put("screen.arenaagents.roster.search_hint", "Name, provider, or model");
		exactCopy.put("screen.arenaagents.roster.filter.provider", "Provider");
		exactCopy.put("screen.arenaagents.roster.filter.status", "Status");
		exactCopy.put("screen.arenaagents.roster.filter.all", "All");
		exactCopy.put("screen.arenaagents.roster.filter.all_providers", "All providers");
		exactCopy.put("screen.arenaagents.roster.filter.all_statuses", "All statuses");
		exactCopy.put("screen.arenaagents.roster.scope.selected", "%s selected");
		exactCopy.put("screen.arenaagents.roster.scope.hidden", "%s selected, %s hidden");
		exactCopy.put("screen.arenaagents.roster.unavailable", "Unavailable: %s");
		exactCopy.put("screen.arenaagents.roster.page", "Agents %s to %s of %s");
		exactCopy.put("screen.arenaagents.roster.event_capacity", "Event team %s of %s");
		exactCopy.put("screen.arenaagents.control.selected", "Selected");
		exactCopy.put("screen.arenaagents.control.cycle_hint", "Use Left and Right to change the value");
		exactCopy.put("screen.arenaagents.controls.title", "Arena agent control center");
		exactCopy.put("screen.arenaagents.speed_unavailable", "Speed mode, not available");
		exactCopy.put("screen.arenaagents.name", "Agent name");
		exactCopy.put("screen.arenaagents.game_mode", "Game mode");
		exactCopy.put("screen.arenaagents.summon", "Summon agent");
		exactCopy.put("screen.arenaagents.prompt", "Goal prompt");
		exactCopy.put("screen.arenaagents.command_sent", "Command queued, awaiting server update");
		exactCopy.put("screen.arenaagents.setup.title", "Arena Agents setup");
		exactCopy.put("screen.arenaagents.results.title", "Arena Agents match results");
		exactCopy.put("screen.arenaagents.navigation.agents", "Agent roster");
		exactCopy.put("screen.arenaagents.navigation.group", "Group prompt");
		exactCopy.put("screen.arenaagents.navigation.live", "Live arena");
		exactCopy.put("screen.arenaagents.navigation.build", "Build arena");
		exactCopy.put("screen.arenaagents.build.location", "Build location");
		exactCopy.put("screen.arenaagents.roster.model_speed", "Model and speed");
		exactCopy.put("screen.arenaagents.roster.identity_game", "Identity and game");
		exactCopy.put("screen.arenaagents.results.return", "Return to world");
		for (Map.Entry<String, String> entry : exactCopy.entrySet()) {
			assertTrue(language.has(entry.getKey()), "missing English copy for " + entry.getKey());
			String actual = language.get(entry.getKey()).getAsString();
			assertTrue(actual.equals(entry.getValue()), "exact English copy for " + entry.getKey());
			assertTrue(!actual.isBlank() && actual.equals(actual.strip()),
					"English copy is nonblank and trimmed for " + entry.getKey());
			assertTrue(placeholderCount(actual) == placeholderCount(entry.getValue()),
					"placeholder count remains exact for " + entry.getKey());
			assertTrue(actual.indexOf('\u2014') < 0 && actual.indexOf('|') < 0,
					"English copy avoids decorative separators for " + entry.getKey());
			assertTrue(!ordinaryAllCaps(actual), "ordinary English copy uses sentence case for " + entry.getKey());
			assertTrue(!hasMojibake(actual), "English copy avoids mojibake for " + entry.getKey());
		}
		List<String> required = List.of(
				"screen.arenaagents.controls.title",
				"screen.arenaagents.setup.title",
				"screen.arenaagents.results.title",
				"screen.arenaagents.navigation.agents",
				"screen.arenaagents.navigation.group",
				"screen.arenaagents.navigation.live",
				"screen.arenaagents.navigation.build",
				"screen.arenaagents.build.location",
				"screen.arenaagents.build.processing",
				"screen.arenaagents.roster.current",
				"screen.arenaagents.live.health",
				"screen.arenaagents.results.return"
		);
		for (String key : required) {
			if (!language.has(key) || language.get(key).getAsString().isBlank()) {
				throw new AssertionError("Missing readable English copy for " + key);
			}
		}
		if (source.indexOf('\uFFFD') >= 0 || source.indexOf('\u00C2') >= 0 || source.indexOf('\u00E2') >= 0) {
			throw new AssertionError("English copy contains mojibake markers");
		}
		if (source.indexOf('·') >= 0 || source.indexOf('—') >= 0 || source.indexOf('…') >= 0) {
			throw new AssertionError("English copy uses ambiguous decorative separators");
		}
		assertResource("/assets/arenaagents/font/console.json", "custom console font definition");
		assertResource("/assets/arenaagents/font/roboto_regular.ttf", "custom console font asset");
		verifyFontProviderAssets();
		verifyNarrationOverrides();
		verifyControlPresentation();
		return required.size() + exactCopy.size() * 7 + 30;
	}

	private static void verifyNarrationOverrides() {
		assertNarrationOverride(AgentControlScreen.class);
		assertNarrationOverride(ScenarioSetupScreen.class);
	}

	private static void assertNarrationOverride(Class<?> screenType) {
		try {
			Method method = screenType.getDeclaredMethod("getNarrationMessage");
			assertTrue(method.getReturnType() == Component.class,
					screenType.getSimpleName() + " uses the mapped Component narration override");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(screenType.getSimpleName() + " must declare getNarrationMessage", exception);
		}
	}

	private static void verifyControlPresentation() {
		try {
			Method buttonPresentation = ConsoleButton.class.getDeclaredMethod(
					"presentation", ConsoleButton.Tone.class, boolean.class, boolean.class,
					boolean.class, boolean.class, int.class);
			Object quiet = buttonPresentation.invoke(null, ConsoleButton.Tone.SECONDARY,
					true, false, false, false, ConsoleTheme.ACCENT);
			Object selected = buttonPresentation.invoke(null, ConsoleButton.Tone.SECONDARY,
					true, true, false, false, ConsoleTheme.ACCENT);
			Object selectedCustomAccent = buttonPresentation.invoke(null, ConsoleButton.Tone.SECONDARY,
					true, true, false, false, ConsoleTheme.CODEX);
			Object focused = buttonPresentation.invoke(null, ConsoleButton.Tone.SECONDARY,
					true, true, true, false, ConsoleTheme.ACCENT);
			Object primary = buttonPresentation.invoke(null, ConsoleButton.Tone.PRIMARY,
					true, false, false, false, ConsoleTheme.ACCENT);
			Object disabled = buttonPresentation.invoke(null, ConsoleButton.Tone.PRIMARY,
					false, false, true, true, ConsoleTheme.ACCENT);
			assertInt(quiet, "border", ConsoleTheme.BORDER, "secondary button uses the quiet boundary");
			assertInt(quiet, "fill", ConsoleTheme.SURFACE, "secondary button uses the quiet surface");
			assertInt(selected, "border", ConsoleTheme.ACCENT, "selection keeps the amber outline");
			assertInt(selectedCustomAccent, "border", ConsoleTheme.ACCENT,
					"selection remains amber instead of inheriting decorative identity color");
			assertInt(selected, "fill", ConsoleTheme.ROSTER_SELECTED_SURFACE,
					"selection uses a quiet selected surface");
			assertInt(focused, "border", ConsoleTheme.FOCUS,
					"keyboard focus stays neutral when selected");
			assertInt(primary, "fill", ConsoleTheme.ROSTER_SELECTED_SURFACE,
					"primary action avoids a bright amber slab");
			assertInt(primary, "text", ConsoleTheme.ACCENT, "primary action uses amber text");
			assertInt(primary, "inset", 1, "button always has one one-pixel boundary");
			assertInt(disabled, "border", ConsoleTheme.BORDER, "disabled button uses a quiet boundary");
			assertInt(disabled, "fill", ConsoleTheme.TRACK, "disabled button uses the track surface");
			assertInt(disabled, "text", ConsoleTheme.MUTED, "disabled button uses muted text");
			Method selectionKey = ConsoleButton.class.getDeclaredMethod("selectionNarrationKey", boolean.class);
			assertTrue(selectionKey.invoke(null, true).equals("screen.arenaagents.control.selected"),
					"selected button narration uses the localized hint");
			assertTrue(selectionKey.invoke(null, false).equals(""),
					"unselected button narration stays concise");

			Method cyclePresentation = ConsoleCycleButton.class.getDeclaredMethod(
					"presentation", boolean.class, boolean.class, boolean.class);
			Object cycleQuiet = cyclePresentation.invoke(null, true, false, false);
			Object cycleHover = cyclePresentation.invoke(null, true, false, true);
			Object cycleFocus = cyclePresentation.invoke(null, true, true, false);
			Object cycleDisabled = cyclePresentation.invoke(null, false, true, true);
			assertInt(cycleQuiet, "border", ConsoleTheme.BORDER, "cycle rests on a quiet boundary");
			assertInt(cycleQuiet, "arrow", ConsoleTheme.MUTED, "cycle arrows are muted at rest");
			assertInt(cycleHover, "border", ConsoleTheme.ROSTER_FOCUS, "cycle hover uses a neutral outline");
			assertInt(cycleHover, "arrow", ConsoleTheme.TEXT, "cycle arrows brighten on hover");
			assertInt(cycleFocus, "border", ConsoleTheme.FOCUS, "cycle focus uses the neutral focus token");
			assertInt(cycleFocus, "inset", 1, "cycle always has one one-pixel boundary");
			assertInt(cycleDisabled, "border", ConsoleTheme.BORDER,
					"disabled cycle ignores hover and focus decoration");
			assertInt(cycleDisabled, "fill", ConsoleTheme.TRACK, "disabled cycle uses the track surface");
			Method cycleKey = ConsoleCycleButton.class.getDeclaredMethod("cycleNarrationKey");
			assertTrue(cycleKey.invoke(null).equals("screen.arenaagents.control.cycle_hint"),
					"cycle narration uses the localized input hint");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("softened control presentation contract is missing", exception);
		} catch (IllegalAccessException | InvocationTargetException exception) {
			throw new AssertionError("softened control presentation contract could not be evaluated", exception);
		}
	}

	private static void assertInt(Object value, String accessor, int expected, String label)
			throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
		int actual = (int) value.getClass().getMethod(accessor).invoke(value);
		assertTrue(actual == expected, label);
	}

	private static int placeholderCount(String value) {
		int count = 0;
		for (int index = 0; index + 1 < value.length(); index++) {
			if (value.charAt(index) == '%' && value.charAt(index + 1) == 's') count++;
		}
		return count;
	}

	private static boolean ordinaryAllCaps(String value) {
		boolean hasLetter = value.chars().anyMatch(Character::isLetter);
		return hasLetter && value.equals(value.toUpperCase()) && !value.equals(value.toLowerCase());
	}

	private static boolean hasMojibake(String value) {
		return value.indexOf('\uFFFD') >= 0 || value.indexOf('\u00C2') >= 0 || value.indexOf('\u00E2') >= 0;
	}

	private static void verifyFontProviderAssets() {
		JsonObject definition;
		try (InputStream stream = LocalizationVerification.class.getResourceAsStream(
				"/assets/arenaagents/font/console.json")) {
			if (stream == null) throw new AssertionError("custom console font definition is missing");
			definition = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
					.getAsJsonObject();
		} catch (IOException exception) {
			throw new AssertionError("custom console font definition could not be read", exception);
		}

		int ttfProviders = 0;
		for (var provider : definition.getAsJsonArray("providers")) {
			JsonObject value = provider.getAsJsonObject();
			if (!"ttf".equals(value.get("type").getAsString())) continue;
			ttfProviders++;
			if (!value.has("oversample") || value.get("oversample").getAsDouble() < 4.0D) {
				throw new AssertionError("custom console TTF must use at least 4x oversampling for sharp GUI-scale text");
			}
			String identifier = value.get("file").getAsString();
			int separator = identifier.indexOf(':');
			if (separator <= 0 || separator == identifier.length() - 1) {
				throw new AssertionError("TTF provider file must use a namespaced identifier: " + identifier);
			}
			String resolved = "/assets/" + identifier.substring(0, separator) + "/font/"
					+ identifier.substring(separator + 1);
			assertResource(resolved, "TTF provider target " + identifier);
		}
		if (ttfProviders == 0) throw new AssertionError("custom console font has no TTF provider");
	}

	private static void assertResource(String path, String label) {
		try (InputStream stream = LocalizationVerification.class.getResourceAsStream(path)) {
			if (stream == null || stream.read() < 0) throw new AssertionError(label + " is missing or empty");
		} catch (IOException exception) {
			throw new AssertionError(label + " could not be read", exception);
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
