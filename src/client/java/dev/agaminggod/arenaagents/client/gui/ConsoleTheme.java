package dev.agaminggod.arenaagents.client.gui;

import java.util.Locale;

/** Shared, contrast-auditable visual language for every Arena Agents screen and HUD. */
public final class ConsoleTheme {
	public static final int BACKDROP = 0xB80C1117;
	public static final int PANEL = 0xFF18202B;
	public static final int NAVIGATION = 0xFF111820;
	public static final int TRACK = 0xFF0D1319;
	public static final int SURFACE = 0xFF222B36;
	public static final int SURFACE_HOVER = 0xFF2D3947;
	public static final int SURFACE_SELECTED = 0xFF35465A;
	public static final int BORDER = 0xFF46515F;
	public static final int FOCUS = 0xFFFFFFFF;
	public static final int TEXT = 0xFFF2F5F8;
	public static final int MUTED = 0xFFAEB8C4;
	public static final int ACCENT = 0xFFF2BD58;
	public static final int ROSTER_FOCUS = 0xFFCAD2DC;
	public static final int ROSTER_SELECTED_SURFACE = 0xFF332F28;
	public static final int ROSTER_UNAVAILABLE_SURFACE = 0xFF1B222B;
	public static final int SUCCESS = 0xFF66D9A3;
	public static final int ERROR = 0xFFFF737A;
	public static final int CODEX = 0xFF42D39B;
	public static final int GEMINI = 0xFF8E86FF;
	public static final int ROSTER_GEMINI = 0xFF8E88FF;
	public static final int KIMI = 0xFFFFB45E;
	public static final int CURSOR = 0xFF70B9F2;

	private ConsoleTheme() {
	}

	public static int controlOutline(boolean selected, boolean focused, boolean hovered) {
		if (focused) return FOCUS;
		if (selected) return ACCENT;
		return BORDER;
	}

	public static int providerColor(String provider) {
		if (provider == null) return TEXT;
		return switch (provider.toLowerCase(Locale.ROOT)) {
			case "codex" -> CODEX;
			case "gemini", "antigravity" -> GEMINI;
			case "kimi" -> KIMI;
			case "cursor" -> CURSOR;
			default -> TEXT;
		};
	}

	public static int rosterProviderColor(String provider) {
		if (provider == null) return TEXT;
		return switch (provider.toLowerCase(Locale.ROOT)) {
			case "gemini", "antigravity" -> ROSTER_GEMINI;
			default -> providerColor(provider);
		};
	}

	public static double contrastRatio(int foreground, int background) {
		double light = luminance(foreground);
		double dark = luminance(background);
		if (light < dark) {
			double swap = light;
			light = dark;
			dark = swap;
		}
		return (light + 0.05D) / (dark + 0.05D);
	}

	private static double luminance(int color) {
		double red = linear((color >> 16) & 0xFF);
		double green = linear((color >> 8) & 0xFF);
		double blue = linear(color & 0xFF);
		return 0.2126D * red + 0.7152D * green + 0.0722D * blue;
	}

	private static double linear(int channel) {
		double value = channel / 255.0D;
		return value <= 0.04045D ? value / 12.92D : Math.pow((value + 0.055D) / 1.055D, 2.4D);
	}
}
