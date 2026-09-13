package dev.agaminggod.arenaagents.scenario.runtime.map;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;

/** Immutable ordered module curriculum ready for deterministic composition. */
public record ScenarioArenaPlan(
		String id,
		List<ModulePlacement> modules,
		Set<String> requiredObjectiveAnchorIds
) {
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

	public ScenarioArenaPlan {
		if (id == null || !ID_PATTERN.matcher(id).matches()) throw new IllegalArgumentException("plan id is invalid");
		modules = List.copyOf(Objects.requireNonNull(modules, "modules must not be null"));
		if (modules.isEmpty()) throw new IllegalArgumentException("plan must contain at least one module");
		HashSet<String> instanceIds = new HashSet<>();
		for (ModulePlacement module : modules) {
			if (module == null) throw new IllegalArgumentException("modules must not contain null");
			if (!instanceIds.add(module.instanceId())) {
				throw new IllegalArgumentException("duplicate module instance id: " + module.instanceId());
			}
		}
		Objects.requireNonNull(requiredObjectiveAnchorIds, "requiredObjectiveAnchorIds must not be null");
		LinkedHashSet<String> required = new LinkedHashSet<>();
		for (String anchorId : requiredObjectiveAnchorIds) {
			if (anchorId == null || !ID_PATTERN.matcher(anchorId).matches()) {
				throw new IllegalArgumentException("required objective anchor id is invalid: " + anchorId);
			}
			required.add(anchorId);
		}
		requiredObjectiveAnchorIds = Set.copyOf(required);
	}

	public record ModulePlacement(
			String instanceId,
			ScenarioArenaModule module,
			ScenarioArenaModule.AllowedTransform transform,
			BlockPos offset
	) {
		public ModulePlacement {
			if (instanceId == null || !ID_PATTERN.matcher(instanceId).matches()) {
				throw new IllegalArgumentException("module instance id is invalid");
			}
			module = Objects.requireNonNull(module, "module must not be null");
			transform = Objects.requireNonNull(transform, "transform must not be null");
			if (!module.allowedTransforms().contains(transform)) {
				throw new IllegalArgumentException("module does not allow transform " + transform.wireName());
			}
			Objects.requireNonNull(offset, "offset must not be null");
			offset = new BlockPos(offset.getX(), offset.getY(), offset.getZ());
		}
	}
}
