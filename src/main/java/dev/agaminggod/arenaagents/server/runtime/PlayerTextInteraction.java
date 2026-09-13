package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.server.perception.ObservationVisibility;
import dev.agaminggod.arenaagents.server.runtime.menu.MenuStackIdentity;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.SignBlockEntity;

/** Text edits use vanilla filtering and recheck the observed target before any mutation. */
final class PlayerTextInteraction {
	private PlayerTextInteraction() { }

	static ServerTransactionAdapter.ActiveTransaction writeSign(ServerPlayer player, JsonObject args, ServerProtectionPolicy protection) {
		BlockPos position = new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
		boolean front = args.get("front").getAsBoolean();
		List<String> lines = strings(args, "lines", 4, 4, 384);
		List<String> expected = strings(args, "expectedLines", 4, 4, 384);
		Runnable preflight = () -> {
			if (!player.level().hasChunkAt(position) || !player.isWithinBlockInteractionRange(position, 0.0D)
					|| !ObservationVisibility.canSeeBlock(player.level(), player, position)) {
				throw new AgentDomainException("SIGN_NOT_REACHABLE", "Sign must remain loaded, visible, and within player reach");
			}
			if (!protection.mayInteractWithBlock(player, player.level(), position) || !player.level().mayInteract(player, position)) {
				throw new AgentDomainException("PROTECTION_DENIED", "Sign edit was denied");
			}
			SignBlockEntity sign = sign(player, position);
			if (sign.isWaxed() || !player.getUUID().equals(sign.getPlayerWhoMayEdit())
					|| sign.isFacingFrontText(player) != front) {
				throw new AgentDomainException("SIGN_NOT_EDITABLE", "Open the requested unwaxed sign side through a vanilla interaction first");
			}
			if (!signLines(sign, front, player.isTextFilteringEnabled()).equals(expected)) {
				throw new AgentDomainException("SIGN_CHANGED", "Sign text no longer matches the observed lines");
			}
		};
		preflight.run();
		return new FilteredEdit(player, lines.stream().map(ChatFormatting::stripFormatting).toList(), preflight, filtered -> {
			SignBlockEntity sign = sign(player, position);
			sign.updateSignText(player, front, filtered);
			List<String> visible = filtered.stream().map(text -> player.isTextFilteringEnabled() ? text.filteredOrEmpty() : text.raw()).toList();
			if (!signLines(sign, front, player.isTextFilteringEnabled()).equals(visible)) {
				throw new AgentDomainException("SIGN_EDIT_NOT_CONFIRMED", "Requested filtered sign text was not observed");
			}
		}, "SIGN_TEXT_CONFIRMED");
	}

	static ServerTransactionAdapter.ActiveTransaction editBook(ServerPlayer player, JsonObject args) {
		int slot = args.get("slot").getAsInt();
		if ((slot < 0 || slot > 8) && slot != 40) throw new AgentDomainException("INVALID_BOOK_SLOT", "Book editing uses a hotbar slot or offhand slot 40");
		List<String> pages = strings(args, "pages", 0, 100, 1024);
		String title = args.has("title") && !args.get("title").isJsonNull() ? args.get("title").getAsString() : null;
		if (title != null && (title.isBlank() || title.length() > 32)) throw new AgentDomainException("INVALID_BOOK_TITLE", "Signed book title must contain 1..32 characters");
		String fingerprint = args.get("expectedFingerprint").getAsString();
		Runnable preflight = () -> {
			ItemStack book = player.getInventory().getItem(slot);
			if (!book.has(DataComponents.WRITABLE_BOOK_CONTENT) || book.getCount() != 1) {
				throw new AgentDomainException("BOOK_NOT_WRITABLE", "Observed slot must contain one writable book");
			}
			if (!MenuStackIdentity.fingerprint(book, player.registryAccess()).equals(fingerprint)) {
				throw new AgentDomainException("BOOK_CHANGED", "Book no longer matches the observed item identity");
			}
		};
		preflight.run();
		List<String> messages = new ArrayList<>();
		if (title != null) messages.add(title);
		messages.addAll(pages);
		return new FilteredEdit(player, messages, preflight, filtered -> {
			ItemStack book = player.getInventory().getItem(slot);
			List<Filterable<String>> filteredPages = filtered.subList(title == null ? 0 : 1, filtered.size()).stream()
					.map(text -> outgoing(player, text)).toList();
			if (title == null) {
				WritableBookContent content = new WritableBookContent(filteredPages);
				book.set(DataComponents.WRITABLE_BOOK_CONTENT, content);
				if (!content.equals(book.get(DataComponents.WRITABLE_BOOK_CONTENT))) throw new AgentDomainException("BOOK_EDIT_NOT_CONFIRMED", "Edited book pages were not observed");
			} else {
				ItemStack signed = book.transmuteCopy(Items.WRITTEN_BOOK);
				signed.remove(DataComponents.WRITABLE_BOOK_CONTENT);
				WrittenBookContent content = new WrittenBookContent(outgoing(player, filtered.getFirst()),
						player.getPlainTextName(), 0, filteredPages.stream().map(page -> page.<Component>map(Component::literal)).toList(), true);
				signed.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
				player.getInventory().setItem(slot, signed);
				if (!content.equals(player.getInventory().getItem(slot).get(DataComponents.WRITTEN_BOOK_CONTENT))) {
					throw new AgentDomainException("BOOK_EDIT_NOT_CONFIRMED", "Signed book content was not observed");
				}
			}
			player.getInventory().setChanged();
			player.containerMenu.broadcastChanges();
		}, title == null ? "BOOK_PAGES_CONFIRMED" : "BOOK_SIGNED_CONFIRMED");
	}

	static List<String> strings(JsonObject args, String field, int minimum, int maximum, int length) {
		var values = args.getAsJsonArray(field);
		if (values.size() < minimum || values.size() > maximum) throw new AgentDomainException("INVALID_TEXT", field + " has an invalid number of entries");
		List<String> result = new ArrayList<>();
		for (var value : values) {
			String text = value.getAsString();
			if (text.length() > length) throw new AgentDomainException("INVALID_TEXT", field + " entry is too long");
			result.add(text);
		}
		return List.copyOf(result);
	}

	private static SignBlockEntity sign(ServerPlayer player, BlockPos position) {
		if (!(player.level().getBlockEntity(position) instanceof SignBlockEntity sign)) {
			throw new AgentDomainException("SIGN_MISSING", "Observed block is no longer a sign");
		}
		return sign;
	}

	private static List<String> signLines(SignBlockEntity sign, boolean front, boolean filtered) {
		return java.util.Arrays.stream(sign.getText(front).getMessages(filtered)).map(Component::getString).toList();
	}

	private static Filterable<String> outgoing(ServerPlayer player, FilteredText text) {
		return player.isTextFilteringEnabled() ? Filterable.passThrough(text.filteredOrEmpty()) : Filterable.from(text);
	}

	private static final class FilteredEdit implements ServerTransactionAdapter.ActiveTransaction {
		private final ServerPlayer player;
		private final Runnable preflight;
		private final Consumer<List<FilteredText>> commit;
		private final String resultCode;
		private final CompletableFuture<List<FilteredText>> filtered;
		private final int messageCount;
		private final ServerTransactionAdapter.TerminalGate terminal = new ServerTransactionAdapter.TerminalGate();
		private int ticks;

		FilteredEdit(ServerPlayer player, List<String> messages, Runnable preflight, Consumer<List<FilteredText>> commit, String resultCode) {
			this.player = player;
			this.preflight = preflight;
			this.commit = commit;
			this.resultCode = resultCode;
			this.messageCount = messages.size();
			this.filtered = Objects.requireNonNull(player.getTextFilter().processMessageBundle(messages));
		}

		@Override
		public ServerTransactionAdapter.TickResult tick(long now) {
			if (terminal.terminalResult() != null) return terminal.terminalResult();
			if (!player.isAlive()) return terminal.finish(ServerTransactionAdapter.TickResult.failed("AGENT_DEAD", "Player died before text editing completed"));
			if (++ticks > 600) return terminal.finish(ServerTransactionAdapter.TickResult.timedOut("TEXT_FILTER_TIMED_OUT", "Text filtering did not complete within 600 server ticks"));
			if (!filtered.isDone()) return ServerTransactionAdapter.TickResult.running();
			try {
				List<FilteredText> messages = filtered.join();
				if (messages.size() != messageCount) throw new AgentDomainException("TEXT_FILTER_SHAPE_CHANGED", "Text filter returned a different number of entries");
				preflight.run();
				commit.accept(messages);
				return terminal.finish(ServerTransactionAdapter.TickResult.succeeded(resultCode, "Requested text was filtered and the committed content was observed"));
			} catch (AgentDomainException exception) {
				return terminal.finish(ServerTransactionAdapter.TickResult.failed(exception.code(), exception.getMessage()));
			} catch (RuntimeException exception) {
				return terminal.finish(ServerTransactionAdapter.TickResult.failed("TEXT_EDIT_FAILED", "Text filtering or editing failed: " + exception.getClass().getSimpleName()));
			}
		}

		@Override public void cancel(String reason) { terminal.finish(ServerTransactionAdapter.TickResult.cancelled(reason)); cleanup(); }
		@Override public void cleanup() { terminal.cleanupOnce(() -> filtered.cancel(false)); }
	}
}
