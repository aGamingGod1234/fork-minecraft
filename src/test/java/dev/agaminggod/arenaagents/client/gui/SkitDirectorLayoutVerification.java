package dev.agaminggod.arenaagents.client.gui;

import dev.agaminggod.arenaagents.client.gui.widget.ConsoleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleEditBox;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Exercises real director widgets without creating a window, renderer, or game loop. */
public final class SkitDirectorLayoutVerification {
	private SkitDirectorLayoutVerification() {}

	public static int verify() throws Exception {
		List<AgentControlModelOption> previous = AgentControlCatalog.currentOptions();
		try {
			AgentControlCatalog.resetRuntimeCatalog();
			SkitDirectorScreen screen = fixture();
			int assertions = 0;
			for (int tab = 0; tab < 4; tab++) {
				((ConsoleButton) screen.children().get(tab)).onClick(null, false);
				int expected = screen.children().size() - 5;
				Set<String> reached = new HashSet<>();
				do {
					AbstractWidget tabButton = (AbstractWidget) screen.children().get(tab);
					AbstractWidget back = (AbstractWidget) screen.children().getLast();
					for (int index = 4; index < screen.children().size() - 1; index++) {
						AbstractWidget widget = (AbstractWidget) screen.children().get(index);
						if (!widget.visible) continue;
						check(widget.getY() >= tabButton.getBottom() + 6 && widget.getBottom() <= back.getY() - 6,
								"short-window content stays between tabs and footer");
						check(widget.isMouseOver(widget.getX() + 1, widget.getY() + 1),
								"visible short-window widgets remain hit-testable");
						reached.add(((ConsoleFocusTarget) widget).consoleFocusIdentity());
						assertions += 2;
					}
				} while (screen.mouseScrolled(160, 65, 0, -1));
				check(reached.size() == expected, "scrolling reaches every content control on tab " + tab);
				assertions++;
			}

			((ConsoleButton) screen.children().getFirst()).onClick(null, false);
			edit(screen, "actorName").setValue("Draft actor");
			edit(screen, "actorSelector").setValue("Alex");
			((ConsoleButton) screen.children().get(2)).onClick(null, false);
			check(edit(screen, "actorSelector").getValue().equals("Alex"), "actor selection survives tab rebuilds");
			edit(screen, "voiceText").setValue("A drafted line");
			screen.acceptCatalogUpdate();
			check(edit(screen, "voiceText").getValue().equals("A drafted line"), "catalog rebuild preserves voice drafts");
			((ConsoleButton) screen.children().getFirst()).onClick(null, false);
			set(screen, SkitDirectorScreen.class, "provider", "claude");
			set(screen, SkitDirectorScreen.class, "model", "claude-sonnet-4-6");
			screen.acceptCatalogUpdate();
			AgentControlCatalog.installRuntimeCatalog(List.of(new AgentControlModelOption(
					"cursor", "replacement-model", "Replacement model", List.of("high"), List.of("priority"))));
			screen.acceptCatalogUpdate();
			check(get(screen, "provider").equals("cursor") && get(screen, "model").equals("replacement-model"),
					"removing a Claude alias selects an available provider and model");
			check(edit(screen, "actorName").getValue().equals("Draft actor"), "catalog replacement preserves actor drafts");
			check(((AbstractWidget) screen.children().get(5)).getMessage().getString().equals("Model: Replacement model"),
					"rebuilt model widget formats against the replacement catalog");
			return assertions + 5;
		} finally {
			AgentControlCatalog.installRuntimeCatalog(previous);
		}
	}

	private static SkitDirectorScreen fixture() throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		Minecraft client = allocate(Minecraft.class);
		set(client, Minecraft.class, "lastInputType", InputType.MOUSE);
		SkitDirectorScreen screen = allocate(SkitDirectorScreen.class);
		set(screen, Screen.class, "minecraft", client);
		set(screen, Screen.class, "font", new HeadlessFont());
		set(screen, Screen.class, "title", Component.literal("Skit Director"));
		for (String field : List.of("children", "renderables", "narratables")) {
			set(screen, Screen.class, field, new ArrayList<>());
		}
		set(screen, SkitDirectorScreen.class, "drafts", new HashMap<String, String>());
		Field tab = SkitDirectorScreen.class.getDeclaredField("tab");
		tab.setAccessible(true);
		tab.set(screen, tab.getType().getEnumConstants()[0]);
		for (String[] entry : new String[][] {
				{"provider", "codex"}, {"model", ""}, {"selectedAction", "move"}, {"voice", "voice.auto.v1"},
				{"tone", "neutral"}, {"speed", "1.0"}, {"radius", "48"}, {"feedback", ""}}) {
			set(screen, SkitDirectorScreen.class, entry[0], entry[1]);
		}
		screen.width = 320;
		screen.height = 120;
		screen.init();
		return screen;
	}

	private static ConsoleEditBox edit(SkitDirectorScreen screen, String name) throws Exception {
		return (ConsoleEditBox) get(screen, name);
	}

	private static Object get(SkitDirectorScreen screen, String name) throws Exception {
		Field field = SkitDirectorScreen.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(screen);
	}

	private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static <T> T allocate(Class<T> type) throws Exception {
		Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class HeadlessFont extends Font {
		private HeadlessFont() { super(null); }
		@Override public int width(String text) { return text.length() * 6; }
		@Override public String plainSubstrByWidth(String text, int width) {
			return text.substring(0, Math.min(text.length(), Math.max(0, width / 6)));
		}
		@Override public String plainSubstrByWidth(String text, int width, boolean reverse) {
			int count = Math.min(text.length(), Math.max(0, width / 6));
			return reverse ? text.substring(text.length() - count) : text.substring(0, count);
		}
	}
}
