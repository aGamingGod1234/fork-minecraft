package dev.agaminggod.arenaagents.control;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AgentRosterViewState {
	private List<AgentRosterEntry> roster = List.of();
	private final Set<String> selectedIds = new HashSet<>();
	private AgentRosterFilter filter = AgentRosterFilter.all();
	private String focusedId = "";
	private String rangeAnchorId = "";
	private int currentPage = 1;

	public void reconcile(List<AgentRosterEntry> entries) {
		List<AgentRosterEntry> candidateRoster = List.copyOf(Objects.requireNonNull(entries, "entries"));
		Set<String> candidateIds = new HashSet<>();
		for (AgentRosterEntry entry : candidateRoster) {
			if (!candidateIds.add(entry.id())) {
				throw new IllegalArgumentException("Duplicate agent roster ID: " + entry.id());
			}
		}
		roster = candidateRoster;
		selectedIds.removeIf(id -> {
			AgentRosterEntry entry = entry(id);
			return entry == null || !entry.selectable();
		});
		if (entryIndex(focusedId, roster) >= 0) {
			return;
		}
		List<AgentRosterEntry> filtered = filteredEntries();
		focusedId = !filtered.isEmpty()
				? filtered.getFirst().id()
				: roster.isEmpty() ? "" : roster.getFirst().id();
	}

	public void focus(String id) {
		if (entryIndex(id, roster) >= 0) {
			focusedId = id;
		}
	}

	public String focusedId() {
		return focusedId;
	}

	public void moveFocus(int columns, int rowDelta, int columnDelta) {
		if (columns < 1) {
			throw new IllegalArgumentException("Roster columns must be positive");
		}
		List<AgentRosterEntry> filtered = filteredEntries();
		if (filtered.isEmpty()) {
			return;
		}
		int currentIndex = entryIndex(focusedId, filtered);
		if (currentIndex < 0) {
			focusedId = filtered.getFirst().id();
			return;
		}
		int currentRow = currentIndex / columns;
		int currentColumn = currentIndex % columns;
		int maximumRow = (filtered.size() - 1) / columns;
		int targetRow = clamp(currentRow + rowDelta, 0, maximumRow);
		int targetColumn = clamp(currentColumn + columnDelta, 0, columns - 1);
		int targetIndex = Math.min(targetRow * columns + targetColumn, filtered.size() - 1);
		focusedId = filtered.get(targetIndex).id();
	}

	public void toggle(String id) {
		AgentRosterEntry entry = entry(id);
		if (entry == null) {
			return;
		}
		rangeAnchorId = id;
		if (!entry.selectable()) {
			selectedIds.remove(id);
			return;
		}
		if (!selectedIds.add(id)) {
			selectedIds.remove(id);
		}
	}

	public void selectRangeTo(String id) {
		List<AgentRosterEntry> filtered = filteredEntries();
		int endpoint = entryIndex(id, filtered);
		if (endpoint < 0) {
			return;
		}
		int anchor = entryIndex(rangeAnchorId, filtered);
		if (anchor < 0) {
			anchor = endpoint;
		}
		int first = Math.min(anchor, endpoint);
		int last = Math.max(anchor, endpoint);
		for (int index = first; index <= last; index++) {
			AgentRosterEntry entry = filtered.get(index);
			if (entry.selectable()) {
				selectedIds.add(entry.id());
			}
		}
		rangeAnchorId = id;
	}

	public void selectAll() {
		for (AgentRosterEntry entry : filteredEntries()) {
			if (entry.selectable()) {
				selectedIds.add(entry.id());
			}
		}
	}

	public void clearSelection() {
		selectedIds.clear();
		rangeAnchorId = "";
	}

	public Set<String> selectedIds() {
		LinkedHashSet<String> ordered = new LinkedHashSet<>();
		for (AgentRosterEntry entry : roster) {
			if (selectedIds.contains(entry.id())) {
				ordered.add(entry.id());
			}
		}
		return Collections.unmodifiableSet(ordered);
	}

	public void setFilter(AgentRosterFilter filter) {
		this.filter = Objects.requireNonNull(filter, "filter");
	}

	public AgentRosterPage page(int pageSize) {
		if (pageSize < 1) {
			throw new IllegalArgumentException("Roster page size must be positive");
		}
		List<AgentRosterEntry> filtered = filteredEntries();
		int pageCount = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
		currentPage = clamp(currentPage, 1, pageCount);
		int fromIndex = Math.min((currentPage - 1) * pageSize, filtered.size());
		int toIndex = Math.min(fromIndex + pageSize, filtered.size());
		List<AgentRosterEntry> pageEntries = filtered.subList(fromIndex, toIndex);
		int firstIndex = pageEntries.isEmpty() ? 0 : fromIndex + 1;
		int lastIndex = pageEntries.isEmpty() ? 0 : toIndex;

		Set<String> visibleIds = new HashSet<>();
		for (AgentRosterEntry entry : filtered) {
			visibleIds.add(entry.id());
		}
		int hiddenSelectedCount = 0;
		for (String selectedId : selectedIds) {
			if (!visibleIds.contains(selectedId)) {
				hiddenSelectedCount++;
			}
		}
		return new AgentRosterPage(
				pageEntries,
				firstIndex,
				lastIndex,
				filtered.size(),
				currentPage,
				pageCount,
				hiddenSelectedCount
		);
	}

	public void setPage(int page, int pageSize) {
		currentPage = page;
		page(pageSize);
	}

	public void nextPage(int pageSize) {
		currentPage++;
		page(pageSize);
	}

	public void previousPage(int pageSize) {
		currentPage--;
		page(pageSize);
	}

	private AgentRosterEntry entry(String id) {
		int index = entryIndex(id, roster);
		return index < 0 ? null : roster.get(index);
	}

	private List<AgentRosterEntry> filteredEntries() {
		return roster.stream().filter(filter::matches).toList();
	}

	private static int entryIndex(String id, List<AgentRosterEntry> entries) {
		if (id == null || id.isEmpty()) {
			return -1;
		}
		for (int index = 0; index < entries.size(); index++) {
			if (entries.get(index).id().equals(id)) {
				return index;
			}
		}
		return -1;
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(value, maximum));
	}
}
