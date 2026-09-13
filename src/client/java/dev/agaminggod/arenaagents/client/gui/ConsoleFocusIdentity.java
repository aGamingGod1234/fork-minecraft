package dev.agaminggod.arenaagents.client.gui;

import java.util.Locale;
import net.minecraft.client.gui.components.AbstractWidget;

/** Stable focus keys survive selected/value text changes during widget rebuilds. */
public final class ConsoleFocusIdentity {
	private ConsoleFocusIdentity() {
	}

	public static String of(AbstractWidget widget) {
		if (widget instanceof ConsoleFocusTarget target) return normalize(target.consoleFocusIdentity());
		return widget == null ? "" : normalize(widget.getMessage().getString());
	}

	public static String normalize(String message) {
		if (message == null) return "";
		String value = message.strip();
		if (value.regionMatches(true, 0, "Selected: ", 0, 10)) value = value.substring(10);
		if (value.regionMatches(true, 0, "In group: ", 0, 10)) value = value.substring(10);
		int detailSeparator = value.indexOf('|');
		if (detailSeparator > 0) value = value.substring(0, detailSeparator);
		int valueSeparator = value.indexOf(':');
		if (valueSeparator > 0) value = value.substring(0, valueSeparator);
		return value.strip().toLowerCase(Locale.ROOT);
	}
}
