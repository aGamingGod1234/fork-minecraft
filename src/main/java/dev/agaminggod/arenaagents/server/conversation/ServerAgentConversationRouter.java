package dev.agaminggod.arenaagents.server.conversation;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.GoalControl;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import dev.agaminggod.arenaagents.server.goal.DraftIntent;
import dev.agaminggod.arenaagents.server.goal.GoalCompilation;
import dev.agaminggod.arenaagents.server.goal.GoalCompiler;
import dev.agaminggod.arenaagents.server.goal.PendingGoalDraft;
import dev.agaminggod.arenaagents.server.goal.GoalSpecRequestSink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public final class ServerAgentConversationRouter implements AgentConversationRouter {
	public static final double DEFAULT_PROXIMITY_RANGE = 48.0D;

	private final CodexAgentManager manager;
	private final ConversationEventSink eventSink;
	private final GoalSpecRequestSink goalSpecRequestSink;
	private final GoalCompiler goalCompiler = new GoalCompiler();
	private final Map<AgentId, Long> sequences = new LinkedHashMap<>();

	public ServerAgentConversationRouter(CodexAgentManager manager, ConversationEventSink eventSink) {
		this(manager, eventSink, draft -> { });
	}

	public ServerAgentConversationRouter(CodexAgentManager manager, ConversationEventSink eventSink, GoalSpecRequestSink goalSpecRequestSink) {
		this.manager = Objects.requireNonNull(manager, "manager must not be null");
		this.eventSink = Objects.requireNonNull(eventSink, "eventSink must not be null");
		this.goalSpecRequestSink = Objects.requireNonNull(goalSpecRequestSink, "goalSpecRequestSink must not be null");
	}

	public DeliveryReceipt deliverAgentMessage(
			AgentId sourceAgentId,
			ConversationAudience audience,
			String recipientId,
			String text
	) {
		return deliverAgentMessage(sourceAgentId, audience, recipientId, text, true, true);
	}

	public DeliveryReceipt deliverAgentMessageToAgents(
			AgentId sourceAgentId,
			ConversationAudience audience,
			String recipientId,
			String text
	) {
		return deliverAgentMessage(sourceAgentId, audience, recipientId, text, false, true);
	}

	public DeliveryReceipt deliverAgentMessageToPlayers(
			AgentId sourceAgentId,
			ConversationAudience audience,
			String recipientId,
			String text
	) {
		return deliverAgentMessage(sourceAgentId, audience, recipientId, text, true, false);
	}

	private DeliveryReceipt deliverAgentMessage(
			AgentId sourceAgentId,
			ConversationAudience audience,
			String recipientId,
			String text,
			boolean deliverToPlayers,
			boolean deliverToAgents
	) {
		Objects.requireNonNull(sourceAgentId, "sourceAgentId must not be null");
		ServerPlayer source = manager.findAgentPlayer(sourceAgentId).orElseThrow(
				() -> new AgentDomainException("AGENT_PLAYER_MISSING", "Agent player is not loaded")
		);
		String canonicalRecipient = audience == ConversationAudience.DIRECT
				? resolveRecipient(recipientId).id()
				: "";
		AgentRecord sourceRecord = manager.registry().require(sourceAgentId);
		return deliverFrom(
				source,
				manager.displayName(sourceRecord),
				new ConversationEvent(
						sourceAgentId,
						sourceAgentId.toString(),
						canonicalRecipient,
						audience,
						ConversationKind.AGENT_MESSAGE,
						text,
						sourceRecord.goalRevision(),
						System.currentTimeMillis(),
						0L,
						dimensionId(source)
				),
				deliverToPlayers,
				deliverToAgents
		);
	}

	public DeliveryReceipt deliverPlayerMessage(ServerPlayer source, AgentId recipientAgentId, String text) {
		return deliverPlayerMessage(source, recipientAgentId, text, false);
	}

	public DeliveryReceipt deliverPlayerMessageFromNativeWhisper(
			ServerPlayer source,
			AgentId recipientAgentId,
			String text
	) {
		return deliverPlayerMessage(source, recipientAgentId, text, true);
	}

	private DeliveryReceipt deliverPlayerMessage(
			ServerPlayer source,
			AgentId recipientAgentId,
			String text,
			boolean vanillaAlreadyEchoed
	) {
		Objects.requireNonNull(source, "source must not be null");
		AgentRecord recipientRecord = manager.registry().require(
				Objects.requireNonNull(recipientAgentId, "recipientAgentId must not be null")
		);
		OnlineParticipant recipient = resolveRecipient(recipientAgentId.toString());
		ConversationEvent event = new ConversationEvent(
				recipientAgentId,
				source.getUUID().toString(),
				recipient.id(),
				ConversationAudience.DIRECT,
				ConversationKind.PLAYER_MESSAGE,
				text,
				recipientRecord.goalRevision(),
				System.currentTimeMillis(),
				0L,
				dimensionId(source)
		);
		DeliveryReceipt receipt = deliverFrom(
				source,
				source.getScoreboardName(),
				event,
				true,
				true,
				vanillaAlreadyEchoed ? Set.of(source.getUUID().toString()) : Set.of()
		);
		if (!vanillaAlreadyEchoed && !receipt.deliveredIds().contains(source.getUUID().toString())) {
			source.sendSystemMessage(directComponent(source.getScoreboardName(), recipient.displayName(), event.text(), false));
		}
		return receipt;
	}

	public DeliveryReceipt deliverPlayerProximitySpeech(ServerPlayer source, String text, double range) {
		return deliverPlayerProximitySpeech(capturePlayerProximitySpeechAudience(source, range), text);
	}

	public ProximitySpeechAudience capturePlayerProximitySpeechAudience(ServerPlayer source, double range) {
		Objects.requireNonNull(source, "source must not be null");
		if (!Double.isFinite(range) || range <= 0.0D || range > 128.0D) {
			throw new IllegalArgumentException("Speech range must be between 0 and 128 blocks");
		}
		ArrayList<AgentId> recipients = new ArrayList<>();
		double rangeSquared = range * range;
		for (OnlineParticipant participant : onlineParticipants(source)) {
			AgentRecord target = participant.agentRecord();
			if (target == null || participant.player() == source) continue;
			if (participant.player().level() != source.level()
					|| source.distanceToSqr(participant.player()) > rangeSquared) continue;
			recipients.add(target.agentId());
		}
		return new ProximitySpeechAudience(source.getUUID(), source.level().dimension(), recipients);
	}

	public DeliveryReceipt deliverPlayerProximitySpeech(ProximitySpeechAudience audience, String text) {
		Objects.requireNonNull(audience, "audience must not be null");
		ServerLevel sourceLevel = manager.server().getLevel(audience.sourceDimension());
		if (sourceLevel == null) return new DeliveryReceipt(List.of(), List.of());
		Map<AgentId, AgentRecord> currentRecords = new LinkedHashMap<>();
		for (AgentRecord record : manager.records()) currentRecords.put(record.agentId(), record);
		ArrayList<String> delivered = new ArrayList<>();
		for (AgentId recipientId : audience.recipientAgentIds()) {
			AgentRecord target = currentRecords.get(recipientId);
			if (target == null) continue;
			publishToAgent(target, new ConversationEvent(
					target.agentId(),
					audience.sourcePlayerId().toString(),
					target.agentId().toString(),
					ConversationAudience.PROXIMITY,
					ConversationKind.PROXIMITY_SPEECH,
					text,
					target.goalRevision(),
					System.currentTimeMillis(),
					0L,
					audience.sourceDimension().identifier().toString()
			), sourceLevel);
			delivered.add(target.agentId().toString());
		}
		return new DeliveryReceipt(List.copyOf(delivered), List.of());
	}

	public record ProximitySpeechAudience(
			UUID sourcePlayerId,
			ResourceKey<Level> sourceDimension,
			List<AgentId> recipientAgentIds
	) {
		public ProximitySpeechAudience {
			Objects.requireNonNull(sourcePlayerId, "sourcePlayerId must not be null");
			Objects.requireNonNull(sourceDimension, "sourceDimension must not be null");
			recipientAgentIds = List.copyOf(Objects.requireNonNull(
					recipientAgentIds, "recipientAgentIds must not be null"
			));
		}
	}

	public void removeAgent(AgentId agentId) {
		sequences.remove(Objects.requireNonNull(agentId, "agentId must not be null"));
	}

	@Override
	public DeliveryReceipt deliver(ConversationEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		AgentId sourceAgentId = AgentId.parse(event.sourceId());
		ServerPlayer source = manager.findAgentPlayer(sourceAgentId).orElseThrow(
				() -> new AgentDomainException("AGENT_PLAYER_MISSING", "Agent player is not loaded")
		);
		return deliverFrom(source, manager.displayName(manager.registry().require(sourceAgentId)), event, true, true);
	}

	private DeliveryReceipt deliverFrom(
			ServerPlayer source,
			String sourceName,
			ConversationEvent event,
			boolean deliverToPlayers,
			boolean deliverToAgents
	) {
		return deliverFrom(source, sourceName, event, deliverToPlayers, deliverToAgents, Set.of());
	}

	private DeliveryReceipt deliverFrom(
			ServerPlayer source,
			String sourceName,
			ConversationEvent event,
			boolean deliverToPlayers,
			boolean deliverToAgents,
			Set<String> suppressedPlayerIds
	) {
		List<OnlineParticipant> online = onlineParticipants(source);
		DeliveryReceipt receipt = ConversationDeliveryPolicy.plan(
				event,
				online.stream().map(OnlineParticipant::policy).toList(),
				DEFAULT_PROXIMITY_RANGE
		);
		Map<String, OnlineParticipant> byId = new LinkedHashMap<>();
		for (OnlineParticipant participant : online) byId.putIfAbsent(participant.id(), participant);
		String recipientName = Optional.ofNullable(byId.get(event.recipientId()))
				.map(OnlineParticipant::displayName)
				.orElse(event.recipientId());
		for (String deliveredId : receipt.deliveredIds()) {
			OnlineParticipant participant = byId.get(deliveredId);
			if (participant == null) continue;
			boolean mirror = receipt.mirroredOperatorIds().contains(deliveredId);
			if (deliverToPlayers && !suppressedPlayerIds.contains(participant.player().getUUID().toString())) {
				if (event.audience() == ConversationAudience.DIRECT
						&& !mirror
						&& participant.agentRecord() == null) {
					MinecraftWhisperDelivery.send(source, sourceName, participant.player(), event.text());
				} else {
					participant.player().sendSystemMessage(component(event, sourceName, recipientName, mirror));
				}
			}
			if (deliverToAgents && participant.agentRecord() != null
					&& !event.sourceId().equals(participant.agentRecord().agentId().toString())) {
				publishToAgent(participant.agentRecord(), event, source.level());
			}
		}
		return receipt;
	}

	private void publishToAgent(AgentRecord target, ConversationEvent source, ServerLevel sourceLevel) {
		if ((source.kind() == ConversationKind.PLAYER_MESSAGE || source.kind() == ConversationKind.PROXIMITY_SPEECH)
				&& target.state() == dev.agaminggod.arenaagents.agent.AgentLifecycleState.PAUSED
				&& isSteeringPhrase(source.text())) {
			target = manager.resume(target.agentId().toString()).after();
		}
		long sequence = sequences.merge(target.agentId(), 1L, Long::sum);
		ConversationEvent delivered = new ConversationEvent(
				target.agentId(),
				source.sourceId(),
				target.agentId().toString(),
				source.audience(),
				source.kind(),
				source.text(),
				target.goalRevision(),
				System.currentTimeMillis(),
				sequence,
				source.dimensionId()
		);
		GoalRoute route = routePlayerGoal(target, delivered, sourceLevel);
		if (route.publish()) eventSink.publish(delivered, route.wakeSpec());
	}

	private GoalRoute routePlayerGoal(AgentRecord target, ConversationEvent event, ServerLevel sourceLevel) {
		if (event.kind() != ConversationKind.PLAYER_MESSAGE && event.kind() != ConversationKind.PROXIMITY_SPEECH) {
			return GoalRoute.EVENT_ONLY;
		}
		if (!sourceLevel.dimension().identifier().toString().equals(event.dimensionId())) {
			throw new AgentDomainException("GOAL_SOURCE_DIMENSION_CHANGED",
					"The requester's live dimension changed before the goal could be compiled");
		}
		// Replace/queue is an explicit /agent goal choice, not inferred from live speech.
		if (!GoalCompiler.consumePlayerSpeechAsGoal(
				!ConversationWakePolicy.mayInstallNewGoalFromSpeech(target.state()), event.text(), false)) {
			return GoalRoute.EVENT_ONLY;
		}

		GoalCompilation compilation = goalCompiler.compile(
				event.text(), manager.server().registryAccess(), manager.server().getTickCount(),
				id -> manager.server().getAdvancements().get(net.minecraft.resources.Identifier.parse(id)) != null,
				Objects.requireNonNull(sourceLevel, "sourceLevel must not be null")
		);

		return routeCompiledSpeechGoal(
				target.state(), event.kind(), compilation,
				() -> {
					DraftIntent intent = GoalCompiler.isDeterministicTranslation(event.text())
							? DraftIntent.TRANSLATE_START : DraftIntent.CONFIRM_TRANSLATION;
					PendingGoalDraft draft = draft(
							target, event, sourceLevel, Optional.empty(), intent);
					manager.stageGoalDraft(draft);
					goalSpecRequestSink.publish(draft);
					if (intent == DraftIntent.TRANSLATE_START) {
						notifyRequester(draft, "I understood the goal and will start it after validation.");
					} else {
						notifyRequester(draft, compilation.playerMessage()
								+ " Draft " + draft.draftId() + " is waiting for clarification.");
					}
				},
				message -> notifyRequester(requestingPlayerId(event), message)
		);
	}

	static GoalRoute routeCompiledSpeechGoal(
			dev.agaminggod.arenaagents.agent.AgentLifecycleState state,
			ConversationKind kind,
			GoalCompilation compilation,
			Runnable stageTranslation,
			Consumer<String> reportRejection
	) {
		Objects.requireNonNull(compilation, "compilation must not be null");
		Objects.requireNonNull(stageTranslation, "stageTranslation must not be null");
		Objects.requireNonNull(reportRejection, "reportRejection must not be null");
		if (!ConversationWakePolicy.shouldStartGoal(state, kind)) return GoalRoute.EVENT_ONLY;
		return switch (compilation.kind()) {
			case ACCEPTED -> new GoalRoute(true, compilation.acceptedSpec());
			case NEEDS_TRANSLATION -> {
				stageTranslation.run();
				yield GoalRoute.CONSUMED;
			}
			case REJECTED -> {
				reportRejection.accept(compilation.playerMessage());
				yield GoalRoute.CONSUMED;
			}
		};
	}

	private PendingGoalDraft draft(
			AgentRecord target,
			ConversationEvent event,
			ServerLevel sourceLevel,
			Optional<dev.agaminggod.arenaagents.agent.goal.GoalPredicate> proposed,
			DraftIntent intent
	) {
		UUID playerId = requestingPlayerId(event);
		return new PendingGoalDraft(
				UUID.randomUUID(), target.agentId(), playerId, event.text(),
				sourceLevel.dimension().identifier().toString(),
				goalCompiler.candidateIdsFor(event.text(), manager.server().registryAccess(), liveAdvancementTitles()),
				goalCompiler.translationConstraintFor(event.text(), manager.server().registryAccess()), proposed, intent,
				manager.server().getTickCount(), target.goalRevision(), PendingGoalDraft.expectedGoalIdFor(target)
		);
	}

	private static UUID requestingPlayerId(ConversationEvent event) {
		try {
			return UUID.fromString(event.sourceId());
		} catch (IllegalArgumentException exception) {
			throw new AgentDomainException("INVALID_GOAL_REQUESTER", "Goal clarification requires a player identity");
		}
	}

	private Map<String, String> liveAdvancementTitles() {
		LinkedHashMap<String, String> titles = new LinkedHashMap<>();
		for (var advancement : manager.server().getAdvancements().getAllAdvancements()) {
			titles.put(
					advancement.id().toString(),
					advancement.value().display().map(display -> display.getTitle().getString()).orElse("")
			);
		}
		return Map.copyOf(titles);
	}

	private void notifyRequester(PendingGoalDraft draft, String message) {
		notifyRequester(draft.requestingPlayerId(), message);
	}

	private void notifyRequester(UUID playerId, String message) {
		ServerPlayer player = manager.server().getPlayerList().getPlayer(playerId);
		if (player != null) player.sendSystemMessage(Component.literal(message));
	}

	private static boolean isSteeringPhrase(String text) {
		String normalized = text.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
		return normalized.equals("continue")
				|| normalized.equals("keep going")
				|| normalized.equals("watch out")
				|| normalized.equals("try another route")
				|| normalized.equals("retry")
				|| normalized.equals("resume");
	}

	static record GoalRoute(boolean publish, Optional<GoalSpec> wakeSpec) {
		private static final GoalRoute EVENT_ONLY = new GoalRoute(true, Optional.empty());
		private static final GoalRoute CONSUMED = new GoalRoute(false, Optional.empty());

		GoalRoute {
			Objects.requireNonNull(wakeSpec, "wakeSpec must not be null");
		}
	}

	private OnlineParticipant resolveRecipient(String recipientId) {
		if (recipientId == null || recipientId.isBlank()) {
			throw new AgentDomainException("MISSING_RECIPIENT", "Direct conversations require a recipient");
		}
		for (OnlineParticipant participant : onlineParticipants(null)) {
			if (participant.id().equals(recipientId)
					|| participant.player().getUUID().toString().equals(recipientId)) return participant;
		}
		throw new AgentDomainException("RECIPIENT_OFFLINE", "Direct-message recipient is not online");
	}

	private List<OnlineParticipant> onlineParticipants(ServerPlayer source) {
		Map<UUID, AgentRecord> agentsByEntity = new LinkedHashMap<>();
		for (AgentRecord record : manager.records()) record.entityUuid().ifPresent(uuid -> agentsByEntity.put(uuid, record));
		ArrayList<OnlineParticipant> result = new ArrayList<>();
		for (ServerPlayer player : manager.server().getPlayerList().getPlayers()) {
			AgentRecord agent = agentsByEntity.get(player.getUUID());
			String id = agent == null ? player.getUUID().toString() : agent.agentId().toString();
			boolean operator = GoalControl.mayControl(player.createCommandSourceStack());
			double distanceSquared = source == null || source.level() != player.level()
					? Double.MAX_VALUE
					: source.distanceToSqr(player);
			String displayName = agent == null ? player.getScoreboardName() : manager.displayName(agent);
			result.add(new OnlineParticipant(
					new ConversationParticipant(id, operator, true, dimensionId(player), distanceSquared),
					player,
					agent,
					displayName
			));
		}
		return List.copyOf(result);
	}

	private static Component component(ConversationEvent event, String sourceName, String recipientName, boolean mirror) {
		return switch (event.audience()) {
			case PUBLIC -> Component.literal("<" + sourceName + "> " + event.text());
			case DIRECT -> directComponent(sourceName, recipientName, event.text(), mirror);
			case PROXIMITY -> Component.literal("[Nearby] <" + sourceName + "> " + event.text());
		};
	}

	private static Component directComponent(String sourceName, String recipientName, String text, boolean mirror) {
		return Component.literal((mirror ? "[DM spy] " : "[DM] ") + sourceName + " -> " + recipientName + ": " + text);
	}

	private static String dimensionId(ServerPlayer player) {
		return player.level().dimension().identifier().toString();
	}

	private record OnlineParticipant(
			ConversationParticipant policy,
			ServerPlayer player,
			AgentRecord agentRecord,
			String displayName
	) {
		String id() {
			return policy.id();
		}
	}
}
