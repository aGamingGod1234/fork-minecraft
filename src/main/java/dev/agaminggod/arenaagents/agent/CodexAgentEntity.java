package dev.agaminggod.arenaagents.agent;

import java.util.Optional;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class CodexAgentEntity extends PathfinderMob {
	private static final String DEFAULT_AGENT_ID = "";
	private static final String DEFAULT_PROVIDER = "codex";
	private static final String DEFAULT_MODEL = "unconfigured";
	private static final String DEFAULT_REASONING = "unknown";
	private static final String DEFAULT_GAME_MODE = "survival";
	private static final String SAVE_AGENT_ID = "codex_agent_id";
	private static final String SAVE_PROVIDER = "agent_provider";
	private static final String SAVE_MODEL = "codex_model";
	private static final String SAVE_REASONING = "codex_reasoning";
	private static final String SAVE_GAME_MODE = "agent_game_mode";
	private static final String SAVE_SKIN = "codex_skin_variant";
	private static final String SAVE_INVENTORY = "codex_inventory";
	private static final int INVENTORY_SIZE = 36;
	private static final EntityDataAccessor<String> AGENT_ID = SynchedEntityData.defineId(
			CodexAgentEntity.class,
			EntityDataSerializers.STRING
	);
	private static final EntityDataAccessor<String> MODEL = SynchedEntityData.defineId(
			CodexAgentEntity.class,
			EntityDataSerializers.STRING
	);
	private static final EntityDataAccessor<String> PROVIDER = SynchedEntityData.defineId(
			CodexAgentEntity.class,
			EntityDataSerializers.STRING
	);
	private static final EntityDataAccessor<String> REASONING = SynchedEntityData.defineId(
			CodexAgentEntity.class,
			EntityDataSerializers.STRING
	);
	private static final EntityDataAccessor<String> GAME_MODE = SynchedEntityData.defineId(
			CodexAgentEntity.class,
			EntityDataSerializers.STRING
	);
	private static final EntityDataAccessor<Integer> SKIN_VARIANT = SynchedEntityData.defineId(
			CodexAgentEntity.class,
			EntityDataSerializers.INT
	);
	private final SimpleContainer agentInventory = new SimpleContainer(INVENTORY_SIZE);

	public CodexAgentEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
		super(entityType, level);
		setPersistenceRequired();
	}

	public static AttributeSupplier.Builder createAttributes() {
		return createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0D)
				.add(Attributes.MOVEMENT_SPEED, 0.25D)
				.add(Attributes.ATTACK_DAMAGE, 1.0D)
				.add(Attributes.FOLLOW_RANGE, 32.0D);
	}

	public void initialize(AgentRecord record) {
		if (record == null) {
			throw new IllegalArgumentException("record must not be null");
		}
		entityData.set(AGENT_ID, record.agentId().toString());
		setProfile(record.profile());
	}

	public Optional<AgentId> getAgentId() {
		String value = entityData.get(AGENT_ID);
		if (value.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(AgentId.parse(value));
		} catch (AgentDomainException exception) {
			return Optional.empty();
		}
	}

	public String getModelName() {
		return entityData.get(MODEL);
	}

	public String getProvider() {
		return entityData.get(PROVIDER);
	}

	public String getReasoningEffort() {
		return entityData.get(REASONING);
	}

	public AgentGameMode getAgentGameMode() {
		return AgentGameMode.parse(entityData.get(GAME_MODE));
	}

	public int getSkinVariant() {
		return AgentVisualIdentity.normalizedVariant(entityData.get(SKIN_VARIANT));
	}

	public SimpleContainer getAgentInventory() {
		return agentInventory;
	}

	@Override
	protected void registerGoals() {
		// The server action engine owns movement and interaction. No autonomous mob goals are registered.
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(AGENT_ID, DEFAULT_AGENT_ID);
		builder.define(PROVIDER, DEFAULT_PROVIDER);
		builder.define(MODEL, DEFAULT_MODEL);
		builder.define(REASONING, DEFAULT_REASONING);
		builder.define(GAME_MODE, DEFAULT_GAME_MODE);
		builder.define(SKIN_VARIANT, 0);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString(SAVE_AGENT_ID, entityData.get(AGENT_ID));
		output.putString(SAVE_PROVIDER, getProvider());
		output.putString(SAVE_MODEL, getModelName());
		output.putString(SAVE_REASONING, getReasoningEffort());
		output.putString(SAVE_GAME_MODE, getAgentGameMode().wireName());
		output.putInt(SAVE_SKIN, getSkinVariant());
		ValueOutput.TypedOutputList<ItemStack> inventory = output.list(SAVE_INVENTORY, ItemStack.OPTIONAL_CODEC);
		for (int slot = 0; slot < agentInventory.getContainerSize(); slot++) {
			inventory.add(agentInventory.getItem(slot));
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		// Older builds persisted NoAI while idle, which also froze normal mob physics.
		setNoAi(false);
		entityData.set(AGENT_ID, input.getStringOr(SAVE_AGENT_ID, DEFAULT_AGENT_ID));
		entityData.set(PROVIDER, input.getStringOr(SAVE_PROVIDER, DEFAULT_PROVIDER));
		entityData.set(MODEL, input.getStringOr(SAVE_MODEL, DEFAULT_MODEL));
		entityData.set(REASONING, input.getStringOr(SAVE_REASONING, DEFAULT_REASONING));
		entityData.set(GAME_MODE, input.getStringOr(SAVE_GAME_MODE, DEFAULT_GAME_MODE));
		entityData.set(SKIN_VARIANT, AgentVisualIdentity.normalizedVariant(input.getIntOr(SAVE_SKIN, 0)));
		agentInventory.clearContent();
		int slot = 0;
		for (ItemStack stack : input.listOrEmpty(SAVE_INVENTORY, ItemStack.OPTIONAL_CODEC)) {
			if (slot >= agentInventory.getContainerSize()) {
				break;
			}
			agentInventory.setItem(slot++, stack);
		}
		applyGameModeCapabilities();
		refreshNameTag();
	}

	private void setProfile(AgentProfile profile) {
		entityData.set(PROVIDER, profile.provider());
		entityData.set(MODEL, profile.model());
		entityData.set(REASONING, profile.reasoning());
		entityData.set(GAME_MODE, profile.gameMode().wireName());
		entityData.set(SKIN_VARIANT, AgentVisualIdentity.normalizedVariant(profile.skinVariant()));
		applyGameModeCapabilities();
		refreshNameTag();
	}

	private void applyGameModeCapabilities() {
		boolean creative = getAgentGameMode() == AgentGameMode.CREATIVE;
		setNoGravity(creative);
		setInvulnerable(creative);
	}

	private void refreshNameTag() {
		setCustomName(null);
		setCustomNameVisible(false);
	}
}
