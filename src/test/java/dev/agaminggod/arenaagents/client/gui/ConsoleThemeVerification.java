package dev.agaminggod.arenaagents.client.gui;

import dev.agaminggod.arenaagents.client.gui.widget.ConsoleCycleButton;

public final class ConsoleThemeVerification {
	private ConsoleThemeVerification() {
	}

	public static int verify() {
		assertTrue(ConsoleTheme.controlOutline(false, false, true) == ConsoleTheme.BORDER,
				"pointer hover does not impersonate keyboard focus");
		assertTrue(ConsoleTheme.controlOutline(false, true, false) == ConsoleTheme.FOCUS,
				"keyboard focus has a dedicated high-contrast outline");
		assertTrue(ConsoleTheme.controlOutline(true, false, false) == ConsoleTheme.ACCENT,
				"selected controls keep the product accent");
		assertTrue(ConsoleTheme.contrastRatio(ConsoleTheme.TEXT, ConsoleTheme.SURFACE) >= 4.5D,
				"body text meets normal-text contrast");
		assertTrue(ConsoleTheme.contrastRatio(ConsoleTheme.MUTED, ConsoleTheme.PANEL) >= 4.5D,
				"secondary text remains readable");
		assertTrue(ConsoleTheme.contrastRatio(ConsoleTheme.ACCENT, ConsoleTheme.TRACK) >= 4.5D,
				"primary controls keep readable text contrast");
		assertTrue(ConsoleTheme.providerColor("codex") == ConsoleTheme.CODEX,
				"Codex uses the shared provider color");
		assertTrue(ConsoleTheme.providerColor("gemini") == ConsoleTheme.GEMINI,
				"Gemini uses the shared provider color");
		assertTrue(ConsoleTheme.providerColor("kimi") == ConsoleTheme.KIMI,
				"Kimi uses the shared provider color");
		assertTrue(ConsoleTheme.ROSTER_FOCUS != ConsoleTheme.ACCENT,
				"roster focus remains visually distinct from amber selection");
		assertTrue(ConsoleTheme.ROSTER_SELECTED_SURFACE != ConsoleTheme.ROSTER_UNAVAILABLE_SURFACE,
				"selected and unavailable roster surfaces remain distinct");
		assertTrue(ConsoleTheme.contrastRatio(ConsoleTheme.TEXT, ConsoleTheme.ROSTER_SELECTED_SURFACE) >= 4.5D,
				"selected roster surface preserves normal-text contrast");
		assertTrue(ConsoleTheme.contrastRatio(ConsoleTheme.TEXT, ConsoleTheme.ROSTER_UNAVAILABLE_SURFACE) >= 4.5D,
				"unavailable roster surface preserves normal-text contrast");
		assertTrue(ConsoleTheme.providerColor("cursor") == ConsoleTheme.CURSOR,
				"Cursor uses the shared provider color");
		assertTrue(ConsoleTheme.CURSOR != ConsoleTheme.CODEX
				&& ConsoleTheme.CURSOR != ConsoleTheme.GEMINI
				&& ConsoleTheme.CURSOR != ConsoleTheme.KIMI,
				"Cursor provider color is distinct from every other provider");
		int[] providerColors = {
				ConsoleTheme.rosterProviderColor("codex"),
				ConsoleTheme.rosterProviderColor("gemini"),
				ConsoleTheme.rosterProviderColor("kimi"),
				ConsoleTheme.rosterProviderColor("cursor")
		};
		int[] rosterSurfaces = {
				ConsoleTheme.SURFACE,
				ConsoleTheme.ROSTER_SELECTED_SURFACE,
				ConsoleTheme.ROSTER_UNAVAILABLE_SURFACE
		};
		for (int providerColor : providerColors) {
			for (int rosterSurface : rosterSurfaces) {
				assertTrue(ConsoleTheme.contrastRatio(providerColor, rosterSurface) >= 4.5D,
						"provider identity text clears normal-text contrast on every roster surface");
			}
		}
		assertTrue(ConsoleFocusIdentity.normalize("Provider: Codex").equals("provider"),
				"cycle controls keep focus when their value changes");
		assertTrue(ConsoleFocusIdentity.normalize("Selected: Builder").equals("builder"),
				"selection-state wording does not erase row focus identity");
		assertTrue(ConsoleFocusIdentity.normalize("In group: Scout").equals("scout"),
				"group selection keeps row focus identity");
		assertTrue(ConsoleFocusIdentity.normalize("Selected: Builder | Codex | Ready").equals("builder"),
				"accessible row metadata does not destabilize focus identity");
		assertTrue(ConsoleCycleButton.clickDirection(110.0D, 100, 120) == -1,
				"the visible left arrow cycles backward");
		assertTrue(ConsoleCycleButton.clickDirection(150.0D, 100, 120) == 1,
				"the value and visible right arrow cycle forward");
		return 33;
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
