import { EventEmitter } from 'node:events';
import { ForkRunner } from './fork/runner.mjs';
import { createHash } from 'node:crypto';
import { mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { AgentPlanner } from './agent-planner.mjs';
import { AgentRegistry, AgentRegistryError, DynamicAgentState } from './agent-registry.mjs';
import { ActiveGoalSupervisor } from './active-goal-supervisor.mjs';
import { MAX_LEASE_TIMEOUT_MS } from './work-lease-supervisor.mjs';
import { AgentWorkspaceManager } from './agent-workspace.mjs';
import { MinecraftAgentWorkspace } from './minecraft-agent-workspace.mjs';
import { AcpProviderService } from './acp-service.mjs';
import { AntigravityProviderService } from './antigravity-service.mjs';
import { CodexService } from './codex-service.mjs';
import { CursorProviderService } from './cursor-service.mjs';
import { ControlLatencyRegistry } from './control-latency-registry.mjs';
import { buildCoordinatorStatus, providerRecoveryComponents } from './coordinator-status.mjs';
import { ConversationMemory } from './conversation-memory.mjs';
import { FactLedger } from './fact-ledger.mjs';
import { InspectionClient } from './inspection-client.mjs';
import { ModelNotebook } from './model-notebook.mjs';
import { RuntimeMemoryContext } from './runtime-memory-context.mjs';
import { ObservedMemoryStore } from './observed-memory-store.mjs';
import { ExplorationOccupancy } from './explore-frontier.mjs';
import { ProviderService } from './provider-service.mjs';
import { ProviderTurnRecorder } from './provider-turn-recorder.mjs';
import {
	DEFAULT_AGENT_CAP,
	DEFAULT_GOAL_QUEUE_CAP,
	DEFAULT_PLANNING_CONCURRENCY,
	DEFAULT_SERVICE_TIER,
} from './constants.mjs';
import { PlanningScheduler } from './planning-scheduler.mjs';
import { MAX_VERBOSE_MESSAGE_LENGTH, MultiplexedServerBridge, ProtocolV2Error, VERBOSE_STAGES } from './protocol-v2.mjs';
import { adaptObservation } from './observation-adapter.mjs';
import { advanceContextCursor, buildPlannerInput, createContextCursor } from './prompts.mjs';
import { profileFingerprint } from './provider-session.mjs';
import { ProviderHealthRegistry } from './provider-health-registry.mjs';
import { classifyRecoveryFailure } from './recovery-policy.mjs';
import { ReportingTransitionDeduper } from './reporting-transition-deduper.mjs';
import { NativeToolRuntime } from './native-tool-runtime.mjs';
import { classifyNativeGoalError } from './native-goal-error-policy.mjs';
import { MAX_GOAL_SPEC_CORRECTION_ATTEMPTS } from './goal-spec-translator.mjs';
import { ProgramRuntimeManager } from './program-runtime-manager.mjs';
import { createProviderChildEnvironment } from './provider-environment.mjs';
import { PROVIDER_IDS } from './provider-identity.mjs';
import { TraceWriter } from './trace-writer.mjs';
import { wireRuntimeDiagnostics } from './runtime-diagnostics.mjs';
import { RuntimeErrorReporter } from './runtime-error-reporter.mjs';
import { BestEffortDiagnosticQueue } from './best-effort-diagnostic-queue.mjs';
import { RotatingJsonlSink } from './rotating-jsonl-sink.mjs';
import { sanitizeDiagnosticCode, sanitizeDiagnosticErrorCode, sanitizeDiagnosticErrorMessage, sanitizeDiagnosticText, sanitizeDiagnosticValue } from './diagnostic-sanitizer.mjs';
import { FishTtsProvider } from './voice/fish-tts-provider.mjs';
import { DeepgramSttProvider, NoSttProvider } from './voice/deepgram-stt-provider.mjs';
import { LocalSpeechProvider } from './voice/local-speech-provider.mjs';
import { providerCacheNamespace, tagSynthesisCacheNamespace } from './voice/tts-cache-identity.mjs';
import { createVoiceHttpServer } from './voice/voice-http-server.mjs';
import { VoiceSupervisor } from './voice/voice-supervisor.mjs';
import { loadPersistentVoiceProfileStore, VoiceProfileStore } from './voice/voice-profile-store.mjs';
import { WindowsTtsProvider } from './voice/windows-tts-provider.mjs';

const SOURCE_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const COORDINATOR_DIRECTORY = path.resolve(SOURCE_DIRECTORY, '..');
const PROJECT_DIRECTORY = path.resolve(COORDINATOR_DIRECTORY, '..');
const DEFAULT_DYNAMIC_CONFIG_PATH = path.join(COORDINATOR_DIRECTORY, 'config', 'dynamic-agents.json');
const DEFAULT_INVALID_DECISION_RETRIES = 1;
const EMPTY_TURN_RETRY_DELAY_MS = 1_000;
const GOAL_SPEC_RETRY_BASE_MS = 1_000;
const GOAL_SPEC_RETRY_MAX_MS = 30_000;
const GOAL_SPEC_PROPOSAL_RETRY_MS = 5_000;
const TERMINAL_GOAL_SPEC_REJECTIONS = new Set(['UNKNOWN_GOAL_DRAFT', 'GOAL_DRAFT_AGENT_MISMATCH', 'STALE_GOAL_DRAFT']);
const QUIET_LIFECYCLE_ERRORS = new Set(['PLAN_CANCELLED', 'STALE_PLAN', 'STALE_GOAL_REVISION', 'GOAL_REVISION_COLLISION']);
const MAX_CONVERSATION_WAKE_TRANSACTIONS = 4_096;
const MAX_NATIVE_MOVEMENT_HISTORY = 12;
const MAX_NATIVE_RESOURCE_MEMORY = 512;
const DEFAULT_CONNECTION_OPERATION_CAP = 256;
const DEFAULT_AGENT_OPERATION_CAP = 32;
const MAX_PUBLIC_NARRATIVE_RAW_CHARS = 1_024;
const DEFAULT_MAX_PENDING_AGENT_OPERATIONS = 64;
const DEFAULT_MAX_PENDING_AGENT_TRANSACTIONS = 16;
const DEFAULT_VOICE_PORT = 8_766;
const DEFAULT_VOICE_MAX_CONCURRENT = 5;
const DEFAULT_VOICE_PROFILE_ASSIGNMENTS_PATH = path.join('runtime', 'voice-profile-assignments.json');
const DEFAULT_VOICE_SECRET_PATH = path.join('runtime', 'voice-secret.txt');
const DEFAULT_LOCAL_SPEECH_TIMEOUT_MS = 120_000;
const DEFAULT_FISH_FALLBACK_BASE_DELAY_MS = 30_000;
const DEFAULT_FISH_FALLBACK_MAX_DELAY_MS = 300_000;
const VOICE_WARMUP_GRACE_MS = 1_000;
const DEFAULT_FISH_API_KEY_ENVIRONMENT_VARIABLE = 'FISH_AUDIO_API_KEY';
const DEFAULT_DEEPGRAM_API_KEY_ENVIRONMENT_VARIABLE = 'DEEPGRAM_API_KEY';
const DEFAULT_LOCAL_SPEECH_PYTHON_DIRECTORY = path.join('runtime', 'local-speech', '.venv');
const STT_ONLY_PROFILE_STORE = Object.freeze({
	resolve() { throw new Error('voice profiles are unavailable without TTS'); },
});
const WINDOWS_TTS_FALLBACK_CODES = new Set([
	'LOCAL_SPEECH_ERROR',
	'LOCAL_SPEECH_UNAVAILABLE',
	'LOCAL_TTS_ERROR',
	'TTS_AUDIO_TOO_LONG',
	'TTS_AUTHENTICATION_FAILED',
	'TTS_MALFORMED_AUDIO',
	'TTS_PROVIDER_ERROR',
	'TTS_RATE_LIMITED',
	'TTS_TIMEOUT',
	'TTS_UNAVAILABLE',
]);

export class DynamicCoordinator extends EventEmitter {
	#forkRunner;
	#registry;
	#scheduler;
	#codexService;
	#planner;
	#bridge;
	#listeners = [];
	#providerListeners = [];
	#agentOperations = new Map();
	#agentOperationCounts = new Map();
	#totalAgentOperations = 0;
	#connectionOperationCap;
	#agentOperationCap;
	#providerWork = new Map();
	#pendingAttention = new Map();
	#attentionFlushes = new Map();
	#lifecycleGenerations = new Map();
	#providerRetryAfter = new Map();
	#providerProbeDeadlines = new Map();
	#deferredProviderRecovery = new Map();
	#programRuntimeEpochs = new Map();
	#nativeRuntimeEpochs = new Map();
	#verboseReporters = new Set();
	#factLedgers = new Map();
	#conversationMemories = new Map();
	#contextCursors = new Map();
	#nativeConversationSequences = new Map();
	#nativeConversationRecoveries = new Map();
	#nativeObservationSignatures = new Map();
	#nativeWorldSignals = new Map();
	#supervisedObservationRequests = new Map();
	#conversationWakeTransactions = new Map();
	#goalSpecRequests = new Map();
	#goalSpecRequestCap;
	#setGoalSpecTimeout;
	#clearGoalSpecTimeout;
	#programRuntime;
	#nativeRuntime;
	#inspections;
	#playerMemory;
	#memorySummaries = new Map();
	#goalSupervisor;
	#codexControlProtocol;
	#reconciliation = Promise.resolve();
	#started = false;
	#stopping = false;
	#closed = false;
	#stopPromise = null;
	#healthRegistry;
	#latencyRegistry;
	#controlNow;
	#epochNow;
	#disconnectedAt = null;
	#supportedAgentIds = new Set();
	#reconciledStatus = false;
	#setStatusInterval;
	#clearStatusInterval;
	#statusHandle = null;
	#serverInstanceId = null;
	#connectionEpoch = 0;
	#connected = false;
	#readyRegistry = [];
	#providerRecoveryPending = false;
	#traceWriter;
	#providerTurnRecorder;
	#verboseEnabled = false;
	#runtimeGeneration;
	#verboseTransitions = new ReportingTransitionDeduper();
	#maxPendingAgentOperations;
	#maxPendingAgentTransactions;
	#runtimeHooks;

	constructor({ registry, scheduler, codexService, planner, bridge, healthRegistry, latencyRegistry, goalSupervisor, codexControlProtocol = 'native_tools', memoryDirectory = null, runtimeSessionId, traceWriter = null, providerTurnRecorder = null, runtimeGeneration = null, runtimeHooks = {}, benchmarkRecorder = null, controlNow = () => performance.now(), epochNow = Date.now, setStatusInterval = defaultStatusInterval, clearStatusInterval = clearInterval, setGoalSpecTimeout = defaultGoalSpecTimeout, clearGoalSpecTimeout = clearTimeout, connectionOperationCap = DEFAULT_CONNECTION_OPERATION_CAP, agentOperationCap = DEFAULT_AGENT_OPERATION_CAP, goalSpecRequestCap = connectionOperationCap, maxPendingAgentOperations = DEFAULT_MAX_PENDING_AGENT_OPERATIONS, maxPendingAgentTransactions = DEFAULT_MAX_PENDING_AGENT_TRANSACTIONS }) {
		super();
		this.#registry = requireDependency(registry, 'registry');
		this.#scheduler = requireDependency(scheduler, 'scheduler');
		this.#codexService = requireDependency(codexService, 'codexService');
		this.#planner = requireDependency(planner, 'planner');
		this.#bridge = requireDependency(bridge, 'bridge');
		this.#inspections = new InspectionClient({ send: (type, agentId, payload, options) => this.#sendForEpoch(options.connectionEpoch, type, agentId, payload) });
		this.#playerMemory = new RuntimeMemoryContext({ notebook: new ModelNotebook({ directory: memoryDirectory }), sessionId: runtimeSessionId });
		this.#healthRegistry = requireDependency(healthRegistry, 'healthRegistry');
		this.#latencyRegistry = requireDependency(latencyRegistry, 'latencyRegistry');
		this.#goalSupervisor = requireDependency(goalSupervisor, 'goalSupervisor');
		if (traceWriter !== null && typeof traceWriter.write !== 'function') throw new TypeError('traceWriter.write must be a function');
		this.#traceWriter = traceWriter;
		if (providerTurnRecorder !== null && typeof providerTurnRecorder.close !== 'function') throw new TypeError('providerTurnRecorder.close must be a function');
		this.#providerTurnRecorder = providerTurnRecorder;
		this.#runtimeGeneration = runtimeGeneration;
		if (runtimeHooks === null || typeof runtimeHooks !== 'object' || Array.isArray(runtimeHooks)) throw new TypeError('runtimeHooks must be an object');
		if (runtimeHooks.onRemoved !== undefined && typeof runtimeHooks.onRemoved !== 'function') throw new TypeError('runtimeHooks.onRemoved must be a function');
		this.#runtimeHooks = runtimeHooks;
		this.#connectionOperationCap = positiveInteger(connectionOperationCap, 'connectionOperationCap');
		this.#agentOperationCap = positiveInteger(agentOperationCap, 'agentOperationCap');
		this.#goalSpecRequestCap = positiveInteger(goalSpecRequestCap, 'goalSpecRequestCap');
		if (!['arena_script', 'native_tools'].includes(codexControlProtocol)) throw new TypeError('codexControlProtocol must be arena_script or native_tools');
		this.#codexControlProtocol = codexControlProtocol;
		if (typeof controlNow !== 'function') throw new TypeError('controlNow must be a function');
		if (typeof epochNow !== 'function') throw new TypeError('epochNow must be a function');
		if (!Number.isSafeInteger(maxPendingAgentOperations) || maxPendingAgentOperations < 1) throw new TypeError('maxPendingAgentOperations must be a positive safe integer');
		if (!Number.isSafeInteger(maxPendingAgentTransactions) || maxPendingAgentTransactions < 1) throw new TypeError('maxPendingAgentTransactions must be a positive safe integer');
		this.#controlNow = controlNow;
		this.#epochNow = epochNow;
		this.#maxPendingAgentOperations = maxPendingAgentOperations;
		this.#maxPendingAgentTransactions = maxPendingAgentTransactions;
		const programBridge = {
			send: (type, agentId, payload) => this.#sendRuntimeMessage('program', type, agentId, payload),
		};
		const nativeBridge = {
			send: (type, agentId, payload) => this.#sendRuntimeMessage('native', type, agentId, payload),
		};
		this.#programRuntime = new ProgramRuntimeManager({
			sessionId: runtimeSessionId,
			memoryOperation: (record, operation) => this.#playerMemory.execute(record, operation),
			inspectObservation: async (record, query, authority = {}) => ({ state: 'SUCCEEDED', reasonCode: 'INSPECTED', ...await this.#inspections.request(record, query, { ...authority, connectionEpoch: this.#connectionEpoch }) }),
			registry: this.#registry,
			bridge: programBridge,
			planner: this.#planner,
			reportError: (agentId, error) => this.#reportAgentError(agentId, error, this.#programRuntimeEpochs.get(agentId)),
			requestRecovery: ({ record, reason, errorCode, recoveryKind, nextProbeAtEpochMs }) => {
				const connectionEpoch = this.#programRuntimeEpochs.get(record.agentId);
				if (!this.#isConnectionEpochCurrent(connectionEpoch)) return false;
				const current = this.#registry.get(record.agentId);
				if (current === null || current.goalRevision !== record.goalRevision) return false;
				const key = this.#supervisionKey(current);
				this.#goalSupervisor.activate(key);
				const details = this.#recoveryDetails(record.agentId, { errorCode, recoveryKind, nextProbeAtEpochMs });
				return this.#goalSupervisor.recover(key, { reason, ...details });
			},
			onCompletionRequested: (request) => this.#publishGoalCompleted(request, this.#programRuntimeEpochs.get(request.record.agentId)),
			latencyRegistry: this.#latencyRegistry,
			trace: (event, fields) => this.#writeTrace(event, fields),
			plannerContext: (agentId) => this.#plannerContext(agentId),
			clock: () => this.#controlNow(),
			benchmarkRecorder,
		});
		this.#nativeRuntime = new NativeToolRuntime({
			sessionId: runtimeSessionId,
			requestObservation: async (record) => {
				const result = await this.#inspections.request(record, { section: 'observation' }, { connectionEpoch: this.#connectionEpoch });
				return { ...result, observation: adaptObservation(result.observation) };
			},
			inspectObservation: (record, query, authority = {}) => this.#inspections.request(record, query, { ...authority, connectionEpoch: this.#connectionEpoch }),
			notebook: this.#playerMemory.notebook,
			memoryOperation: (record, operation) => this.#playerMemory.execute(record, operation),
			executionSettings: (record) => this.#planner.getExecutionSettings?.(record.agentId) ?? null,
			occupancy: new ExplorationOccupancy({ memoryStore: new ObservedMemoryStore({ directory: memoryDirectory }) }),
			bridge: nativeBridge,
			registry: this.#registry,
			trace: (event, fields) => this.#writeTrace(event, fields),
			onFinish: async ({ record, result, lifecycleGeneration }) => {
				const connectionEpoch = this.#nativeRuntimeEpochs.get(record.agentId);
				if (this.#stopping || this.#closed || !this.#isConnectionEpochCurrent(connectionEpoch)) return;
				const current = this.#registry.get(record.agentId);
				if (current === null || current.goalRevision !== record.goalRevision
					|| !this.#isLifecycleGenerationCurrent(record.agentId, lifecycleGeneration)) return;
				if (result.state === 'COMPLETED') {
					this.#goalSupervisor.terminate(this.#supervisionKey(current, lifecycleGeneration));
					this.#registry.setState(record.agentId, DynamicAgentState.COMPLETED, { goalRevision: record.goalRevision });
				}
			},
		});
		this.#setStatusInterval = requireDependency(setStatusInterval, 'setStatusInterval');
		this.#clearStatusInterval = requireDependency(clearStatusInterval, 'clearStatusInterval');
		this.#setGoalSpecTimeout = requireDependency(setGoalSpecTimeout, 'setGoalSpecTimeout');
		this.#clearGoalSpecTimeout = requireDependency(clearGoalSpecTimeout, 'clearGoalSpecTimeout');
	}

	get registry() { return this.#registry; }
	get bridge() { return this.#bridge; }

	requestSupervisedObservation(key) {
		if (this.#stopping || this.#closed || key === null || typeof key !== 'object') return false;
		const record = this.#registry.get(key.agentId);
		if (record === null || record.goalRevision !== key.goalRevision) return false;
		const conversationRecovery = this.#nativeConversationRecoveries.get(key.agentId);
		if (conversationRecovery !== undefined && sameSupervisionKey(conversationRecovery.supervisionKey, key)) {
			this.#nativeConversationRecoveries.delete(key.agentId);
			this.#scheduleNativeTurn(record, conversationRecovery.request);
			return true;
		}
		if (this.#usesNativeTools(record)) {
			this.#supervisedObservationRequests.set(key.agentId, {
				goalRevision: key.goalRevision,
				lifecycleGeneration: key.lifecycleGeneration,
				connectionEpoch: key.sessionEpoch,
			});
		}
		return this.#bridge.send('request_observation', key.agentId, { goalRevision: key.goalRevision });
	}

	handleLeaseExpired({ key, lease }) {
		if (this.#stopping || this.#closed || !this.#isLifecycleGenerationCurrent(key.agentId, key.lifecycleGeneration)) return;
		const connectionEpoch = this.#connectionEpoch;
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
		const record = this.#registry.get(key.agentId);
		if (record === null || record.goalRevision !== key.goalRevision) return;
		if (key.sessionEpoch !== connectionEpoch || key.profileFingerprint !== profileFingerprint(record)) return;
		this.#writeTrace('work_lease_expired', { ...key, kind: lease.kind, operationId: lease.operationId });
		this.#publishVerbose(key.agentId, key.goalRevision, 'retry', `${lease.kind} work timed out; recovering automatically.`);
		if (lease.kind === 'provider') {
			const work = this.#providerWork.get(key.agentId);
			if (work?.kind === 'native' && work.goalRevision === key.goalRevision
				&& work.lifecycleGeneration === key.lifecycleGeneration
				&& work.supervisionToken?.operationId === lease.operationId) {
				work.expired = true;
				this.#restoreNativeConversation(work.request);
				if (work.request.conversationOnly === true) {
					this.#nativeConversationRecoveries.set(work.agentId, {
						supervisionKey: work.supervisionKey,
						request: work.request,
					});
				}
				this.#providerWork.delete(key.agentId);
			}
			try {
				void Promise.resolve(this.#planner.interrupt(key.agentId, 'Provider work lease expired'))
					.catch((error) => this.#reportAgentError(key.agentId, error, connectionEpoch));
			} catch (error) {
				void this.#reportAgentError(key.agentId, error, connectionEpoch);
			}
		}
		if (['provider', 'action', 'completion'].includes(lease.kind)) {
			this.#nativeObservationSignatures.delete(key.agentId);
			void this.#nativeRuntime.dispose(key.agentId, `${lease.kind}_lease_expired`)
				.catch((error) => this.#reportAgentError(key.agentId, error, connectionEpoch));
		}
	}

	handleGoalStuck({ key, inactiveMs, history }) {
		if (this.#stopping || this.#closed || !this.#isLifecycleGenerationCurrent(key.agentId, key.lifecycleGeneration)) return;
		const record = this.#registry.get(key.agentId);
		if (record === null || record.goalRevision !== key.goalRevision) return;
		if (key.sessionEpoch !== this.#connectionEpoch || key.profileFingerprint !== profileFingerprint(record)) return;
		this.#rememberPendingAttention(key.agentId, key.goalRevision, { priority: 'urgent', trigger: 'stuck' });
		this.#writeTrace('goal_factual_progress_stuck', { ...key, inactiveMs, positionSamples: history.length });
		this.#publishVerbose(key.agentId, key.goalRevision, 'retry', 'No factual world progress for 30 seconds; reassessing without pausing the goal.');
	}

	async start() {
		if (this.#started) return;
		if (this.#closed) throw new Error('Dynamic coordinator cannot restart after it has been stopped');
		this.#stopping = false;
		try {
			this.#bindBridge();
			this.#bridge.start();
			this.#statusHandle = this.#setStatusInterval(() => {
				const connectionEpoch = this.#connectionEpoch;
				this.#requestProviderRecovery(connectionEpoch);
				this.#run(() => this.#publishStatus(connectionEpoch), connectionEpoch);
			}, 1_000);
			this.#started = true;
		} catch (error) {
			if (this.#statusHandle !== null) this.#clearStatusInterval(this.#statusHandle);
			this.#statusHandle = null;
			this.#bridge.stop();
			this.#unbindBridge();
			await this.#codexService.stop();
			throw error;
		}
	}

	stop() {
		if (this.#stopPromise !== null) return this.#stopPromise;
		this.#stopPromise = this.#stopOnce();
		return this.#stopPromise;
	}

	async #stopOnce() {
		if (this.#closed) return;
		this.#stopping = true;
		this.#inspections.cancel();
		this.#connected = false;
		this.#setVerboseEnabled(false);
		if (this.#statusHandle !== null) this.#clearStatusInterval(this.#statusHandle);
		this.#statusHandle = null;
		this.#bridge.stop();
		this.#unbindBridge();
		this.#scheduler.close('Dynamic coordinator stopped');
		this.#goalSupervisor.close();
		await Promise.allSettled([this.#reconciliation, ...[...this.#agentOperations.values()].map((queue) => queue.drainPromise)]);
		this.#agentOperations.clear();
		this.#agentOperationCounts.clear();
		this.#totalAgentOperations = 0;
		this.#providerWork.clear();
		this.#pendingAttention.clear();
		this.#attentionFlushes.clear();
		this.#lifecycleGenerations.clear();
		this.#programRuntime.disposeAll();
		await this.#nativeRuntime.disposeAll();
		await this.#playerMemory.markUnknown(undefined, 'COORDINATOR_STOPPED').catch((error) => this.#emitRuntimeError(error));
		this.#memorySummaries.clear();
		this.#providerRetryAfter.clear();
		this.#providerProbeDeadlines.clear();
		this.#deferredProviderRecovery.clear();
		this.#programRuntimeEpochs.clear();
		this.#nativeRuntimeEpochs.clear();
		this.#factLedgers.clear();
		this.#conversationMemories.clear();
		this.#contextCursors.clear();
		this.#nativeConversationSequences.clear();
		this.#nativeConversationRecoveries.clear();
		this.#nativeObservationSignatures.clear();
		this.#nativeWorldSignals.clear();
		this.#supervisedObservationRequests.clear();
		this.#conversationWakeTransactions.clear();
		this.#cancelGoalSpecRequests();
		if (this.#traceWriter !== null && typeof this.#traceWriter.close === 'function') await this.#traceWriter.close();
		if (this.#providerTurnRecorder !== null) await Promise.resolve(this.#providerTurnRecorder.close()).catch(() => {});
		await this.#codexService.stop();
		this.#started = false;
		this.#stopping = false;
		this.#closed = true;
	}

	#bindBridge() {
		this.#forkRunner = new ForkRunner(this.#codexService, (type, payload) => this.#bridge.send(type, 'server', payload));
		this.#listen('fork_request', (message) => { void this.#forkRunner.request(message.payload).catch(error => this.#emitRuntimeError(error)); });
		this.#listen('fork_cancel', () => this.#forkRunner.cancel());
		this.#listen('fork_receipt', (message) => this.#forkRunner.receipt(message.payload));
		this.#listen('disconnect', () => this.#forkRunner.cancel(), { lifecycle: true });
		this.#listen('inspection_result', (message) => { this.#inspections.accept(message); });
		this.#bindProviderRecovery();
		this.#listen('ready', (connection) => {
			const connectionEpoch = this.#acceptReadyEpoch(connection);
			if (connectionEpoch === null) return;
			const { serverInstanceId, registry } = connection;
			this.#setVerboseEnabled(false);
			if (this.#serverInstanceId !== null && serverInstanceId !== this.#serverInstanceId) {
				this.#invalidateServerInstance(connectionEpoch);
			}
			this.#serverInstanceId = serverInstanceId;
			this.#readyRegistry = structuredClone(registry);
			this.#reconciledStatus = false;
			this.#supportedAgentIds.clear();
			this.#beginReconciliation(registry, connectionEpoch);
		}, { lifecycle: true });
		this.#listen('verbose_control', (message) => {
			this.#setVerboseEnabled(message.payload.enabled);
		});
		this.#listen('catalog_request', (_message, connectionEpoch) => this.#run(async () => {
			await this.#reconciliation;
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			const catalog = await this.#codexService.catalog.refresh({ force: true });
			await this.#publishCatalog(catalog, connectionEpoch);
		}, connectionEpoch));
		this.#listen('agent_registered', (message, connectionEpoch) => this.#enqueueAgent(message.agentId, async () => {
			const record = this.#registry.register(message.payload.record === undefined
				? { ...message.payload, agentId: message.agentId }
				: { ...message.payload.record, agentId: message.agentId });
			this.#codexService.catalog.assertSupported(record.provider, record.model, record.reasoningEffort, record.serviceTier ?? DEFAULT_SERVICE_TIER);
			await this.#sendForEpoch(connectionEpoch, 'agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
			this.#supportedAgentIds.add(record.agentId);
			this.#publishVerbose(record.agentId, record.goalRevision, 'lifecycle', 'Agent registered and ready.', connectionEpoch);
			this.#prewarmNativeAgent(record);
			await this.#publishStatus(connectionEpoch);
		}, { connectionEpoch, transactional: true }));
		this.#listen('agent_removed', (message, connectionEpoch) => this.#run(async () => {
			await this.#reconciliation;
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			this.#publishVerbose(message.agentId, message.payload.goalRevision, 'lifecycle', 'Agent removed from the coordinator roster.', connectionEpoch);
			const current = this.#registry.get(message.agentId);
			if (current !== null) this.#goalSupervisor.terminate(this.#supervisionKey(current));
			this.#programRuntime.dispose(message.agentId);
			await this.#nativeRuntime.dispose(message.agentId, 'agent_removed');
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			this.#providerWork.delete(message.agentId);
			this.#verboseTransitions.clear(message.agentId);
			this.#programRuntimeEpochs.delete(message.agentId);
			this.#nativeRuntimeEpochs.delete(message.agentId);
			this.#pendingAttention.delete(message.agentId);
			this.#attentionFlushes.delete(message.agentId);
			await this.#planner.remove(message.agentId);
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			this.#lifecycleGenerations.delete(message.agentId);
			this.#providerRetryAfter.delete(message.agentId);
			this.#providerProbeDeadlines.delete(message.agentId);
			this.#deferredProviderRecovery.delete(message.agentId);
			this.#factLedgers.delete(message.agentId);
			this.#memorySummaries.delete(message.agentId);
			this.#playerMemory.forget(message.agentId);
			this.#conversationMemories.delete(message.agentId);
			this.#contextCursors.delete(message.agentId);
			this.#nativeConversationSequences.delete(message.agentId);
			this.#nativeConversationRecoveries.delete(message.agentId);
			this.#nativeObservationSignatures.delete(message.agentId);
			this.#nativeWorldSignals.delete(message.agentId);
			this.#supervisedObservationRequests.delete(message.agentId);
			this.#forgetConversationWakes(message.agentId);
			this.#cancelGoalSpecRequests(message.agentId);
			this.#supportedAgentIds.delete(message.agentId);
			try {
				void Promise.resolve(this.#runtimeHooks.onRemoved?.(message.agentId)).catch((error) => this.#emitRuntimeError(error));
			} catch (error) { this.#emitRuntimeError(error); }
			await this.#publishStatus(connectionEpoch);
		}, connectionEpoch));
		this.#listen('goal_spec_request', (message, connectionEpoch) => {
			const key = this.#goalSpecRequestKey(message.agentId, message.payload.requestId);
			const fingerprint = JSON.stringify(message.payload);
			const existing = this.#goalSpecRequests.get(key);
			if (existing !== undefined) {
				if (existing.fingerprint !== fingerprint) {
					this.#emitRuntimeError(new ProtocolV2Error('TRANSACTION_COLLISION', `Goal translation request '${message.payload.requestId}' changed during replay`));
					return;
				}
				if (existing.proposal !== null) {
					void this.#sendForEpoch(connectionEpoch, 'goal_spec_proposal', message.agentId, existing.proposal).catch((error) => this.#emitRuntimeError(error));
				}
				return existing.completion;
			}
			if (this.#goalSpecRequests.size >= this.#goalSpecRequestCap) {
				throw new ProtocolV2Error('GOAL_SPEC_REQUEST_BACKPRESSURE', 'Coordinator goal translation request capacity is full');
			}
			const releaseCapacity = this.#reserveAgentOperation(message.agentId);
			let resolveCompletion;
			const completion = new Promise((resolve) => { resolveCompletion = resolve; });
			const entry = {
				agentId: message.agentId, requestId: message.payload.requestId, request: message.payload,
				fingerprint, proposal: null, attempts: 0, rejectionAttempts: 0, correctiveFeedback: null,
				translating: false, retryHandle: null, connectionEpoch, completion, resolveCompletion, releaseCapacity,
			};
			this.#goalSpecRequests.set(key, entry);
			const initialProcessing = this.#run(() => this.#processGoalSpecRequest(key, entry), connectionEpoch);
			return Promise.all([initialProcessing, completion]);
		});
		this.#listen('goal_spec_result', (message, connectionEpoch) => {
			const key = this.#goalSpecRequestKey(message.agentId, message.payload.requestId);
			const existing = this.#goalSpecRequests.get(key);
			if (existing === undefined || existing.proposal === null) return;
			if (message.payload.status === 'accepted' || TERMINAL_GOAL_SPEC_REJECTIONS.has(message.payload.reasonCode)) {
				this.#forgetGoalSpecRequest(key, existing);
				return;
			}
			if (existing.retryHandle !== null) {
				this.#clearGoalSpecTimeout(existing.retryHandle);
				existing.retryHandle = null;
			}
			existing.rejectionAttempts += 1;
			existing.correctiveFeedback = {
				attempt: existing.rejectionAttempts,
				reasonCode: message.payload.reasonCode,
				rejectedProposal: existing.proposal,
			};
			existing.proposal = null;
			try { this.#planner.cancelGoalSpec?.(message.agentId, message.payload.requestId); } catch { /* completed translation cleanup is best effort */ }
			if (existing.rejectionAttempts <= MAX_GOAL_SPEC_CORRECTION_ATTEMPTS) {
				const delay = Math.min(GOAL_SPEC_RETRY_MAX_MS, GOAL_SPEC_RETRY_BASE_MS * (2 ** (existing.rejectionAttempts - 1)));
				this.#scheduleGoalSpecRequest(key, existing, delay);
				return;
			}
			this.#forgetGoalSpecRequest(key, existing);
			void this.#reportAgentError(message.agentId, codedRuntimeError(
				'GOAL_SPEC_TRANSLATION_REJECTED',
				`Minecraft rejected ${MAX_GOAL_SPEC_CORRECTION_ATTEMPTS + 1} goal translation proposals; the pending draft requires operator correction or cancellation`,
			), connectionEpoch);
		});
		this.#listen('goal_control', (message, connectionEpoch) => {
			let record;
			let nativeDisposal = Promise.resolve();
			let lifecycleGeneration;
			return this.#enqueueAgent(message.agentId, async () => {
				await nativeDisposal;
				const acceptedStillCurrent = () => {
					const current = this.#registry.get(message.agentId);
					return this.#isConnectionEpochCurrent(connectionEpoch)
						&& current !== null && current.goalRevision === record.goalRevision
						&& this.#isLifecycleGenerationCurrent(message.agentId, lifecycleGeneration);
				};
				if (!acceptedStillCurrent()) return;
				this.#publishVerbose(record.agentId, record.goalRevision, 'lifecycle', `Goal lifecycle operation '${message.payload.operation}' accepted.`, connectionEpoch);
				if (!['queue', 'dequeue'].includes(message.payload.operation)) {
					this.#providerRetryAfter.delete(message.agentId);
				}
				if (message.payload.operation === 'dead') {
					void this.#installDeadStatePlan(record, message.payload.death, connectionEpoch).catch((error) => this.#reportAgentError(record.agentId, error, connectionEpoch));
				}
				const resumesGoal = ['start', 'replace', 'resume', 'steer'].includes(message.payload.operation)
					|| message.payload.operation === 'respawn' && record.state === DynamicAgentState.STARTING;
				const activatesQueuedGoal = message.payload.operation === 'complete' && record.state === DynamicAgentState.STARTING;
				if (resumesGoal || activatesQueuedGoal) {
					this.#goalSupervisor.activate(this.#supervisionKey(record));
				}
				if (resumesGoal) {
					this.#rememberPendingAttention(record.agentId, record.goalRevision, {
						priority: message.payload.operation === 'steer' ? 'urgent' : 'ordinary',
						trigger: message.payload.operation,
					});
				}
				if (resumesGoal) {
					await this.#sendForEpoch(connectionEpoch, 'agent_ready', record.agentId, { goalRevision: record.goalRevision });
				}
				if (!acceptedStillCurrent()) return;
				this.emit('goalControl', record);
			}, {
				connectionEpoch,
				transactional: true,
				onAdmitted: () => {
					const previous = this.#registry.get(message.agentId);
					record = this.#registry.applyGoalControl(message.agentId, message.payload);
					const lifecycleChanged = previous !== null && !['queue', 'dequeue'].includes(message.payload.operation)
						&& (record.goalRevision > previous.goalRevision
							|| (record.goalRevision === previous.goalRevision && ['dead', 'respawn'].includes(message.payload.operation)));
					if (lifecycleChanged) {
						this.#cancelGoalSpecRequests(message.agentId);
						this.#retireGoalSupervision(previous, message.payload.operation);
						this.#invalidateAcceptedLifecycle(message.agentId);
						this.#beginGoalControlInterruption(message, connectionEpoch);
						this.#programRuntime.onGoalControl(previous, message.payload.operation);
						nativeDisposal = Promise.resolve(this.#nativeRuntime.dispose(previous.agentId, `goal_${message.payload.operation}`));
					}
					lifecycleGeneration = this.#lifecycleGeneration(message.agentId);
				},
			});
		});
		this.#listen('conversation_event', (message, connectionEpoch) => {
			return this.#enqueueAgent(message.agentId, async () => {
				this.#publishVerbose(message.agentId, message.payload.goalRevision, 'conversation', `Conversation event '${message.payload.kind}' received from '${message.payload.sourceId}'.`, connectionEpoch);
				const ingested = this.#conversationMemory(message.agentId).ingest(message.payload);
				const record = this.#registry.get(message.agentId);
				if (ingested && record !== null) {
					this.#rememberPendingAttention(record.agentId, record.goalRevision, { priority: 'urgent', trigger: 'conversation' });
					if (this.#usesNativeTools(record)) this.#scheduleNativeConversation(record, message.payload, 'conversation');
					else {
						if ([DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING, DynamicAgentState.DEAD].includes(record.state)) {
							this.#goalSupervisor.activate(this.#supervisionKey(record));
						}
						this.#schedulePendingAttentionFlush(record);
					}
				}
				this.emit('conversationEvent', message);
			}, { waitForReconciliation: false, connectionEpoch, transactional: true });
		});
		this.#listen('conversation_wake', (message, connectionEpoch) => {
			return this.#enqueueAgent(message.agentId, async () => {
				this.#publishVerbose(message.agentId, message.payload.event.goalRevision, 'conversation', `Conversation wake '${message.payload.event.kind}' received.`, connectionEpoch);
				const fingerprint = JSON.stringify({ agentId: message.agentId, event: message.payload.event, control: message.payload.control });
				const existing = this.#conversationWakeTransactions.get(message.payload.transactionId);
				if (existing !== undefined) {
					if (existing.fingerprint !== fingerprint) {
						throw new ProtocolV2Error('TRANSACTION_COLLISION', `Conversation wake '${message.payload.transactionId}' changed during replay`);
					}
					const record = this.#registry.applyConversationWake(message.agentId, message.payload.control);
					this.#goalSupervisor.activate(this.#supervisionKey(record));
					this.#providerRetryAfter.delete(message.agentId);
					await this.#sendForEpoch(connectionEpoch, 'conversation_wake_ack', message.agentId, {
						transactionId: message.payload.transactionId,
						goalRevision: record.goalRevision,
					});
					await this.#sendForEpoch(connectionEpoch, 'agent_ready', record.agentId, { goalRevision: record.goalRevision });
					return;
				}
				const previous = this.#registry.get(message.agentId);
				let record;
				try {
					record = this.#registry.applyConversationWake(message.agentId, message.payload.control);
				} catch (error) {
					if (error instanceof AgentRegistryError && ['STALE_GOAL_REVISION', 'GOAL_REVISION_COLLISION', 'INVALID_AGENT_STATE'].includes(error.code)) return;
					throw error;
				}
				if (previous !== null && record.goalRevision > previous.goalRevision) {
					this.#retireGoalSupervision(previous, 'start');
					this.#invalidateAcceptedLifecycle(message.agentId);
					this.#programRuntime.onGoalControl(previous, 'start');
					await this.#nativeRuntime.dispose(previous.agentId, 'conversation_wake');
					if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
				}
				this.#conversationMemory(message.agentId).ingest(message.payload.event);
				this.#goalSupervisor.activate(this.#supervisionKey(record));
				this.#providerRetryAfter.delete(message.agentId);
				this.#rememberPendingAttention(record.agentId, record.goalRevision, { priority: 'urgent', trigger: 'conversation_wake' });
				if (this.#usesNativeTools(record)) this.#scheduleNativeConversation(record, message.payload.event, 'conversation_wake');
				else this.#schedulePendingAttentionFlush(record);
				this.#rememberConversationWake(message.payload.transactionId, message.agentId, fingerprint);
				await this.#sendForEpoch(connectionEpoch, 'conversation_wake_ack', record.agentId, {
					transactionId: message.payload.transactionId,
					goalRevision: record.goalRevision,
				});
				await this.#sendForEpoch(connectionEpoch, 'agent_ready', record.agentId, { goalRevision: record.goalRevision });
				this.emit('conversationEvent', { ...message, payload: message.payload.event });
				this.emit('goalControl', record);
			}, { waitForReconciliation: false, connectionEpoch, transactional: true });
		});
		this.#listen('observation', (message, connectionEpoch) => {
			const receiptMonotonicMs = safeClockRead(this.#controlNow);
			const receiptEpochMs = safeClockRead(this.#epochNow);
			const lifecycleGeneration = this.#lifecycleGeneration(message.agentId);
			return this.#enqueueAgent(message.agentId, async () => {
				if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
				if (!this.#isLifecycleGenerationCurrent(message.agentId, lifecycleGeneration)) return;
				const record = this.#registry.assertCurrentRevision(message.agentId, message.payload.goalRevision);
				if (![DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING].includes(record.state)) return;
				const wireObservation = message.payload.observation ?? message.payload;
				const supervisionKey = this.#supervisionKey(record, lifecycleGeneration);
				this.#goalSupervisor.factualProgress(supervisionKey, factualProgressSignature(wireObservation), factualProgressDetails(wireObservation));
				if (this.#usesNativeTools(record)) this.#nativeRuntimeEpochs.set(record.agentId, connectionEpoch);
				const observation = adaptObservation(wireObservation);
				this.#playerMemory.observe(record, observation);
				const memoryKey = `${record.goalRevision}:${this.#playerMemory.worldId(record)}`;
				if (this.#memorySummaries.get(record.agentId)?.key !== memoryKey) {
					const unresolved = await this.#playerMemory.unresolved(record, { limit: 8 });
					if (!this.#isConnectionEpochCurrent(connectionEpoch) || !this.#isLifecycleGenerationCurrent(record.agentId, lifecycleGeneration)) return;
					this.#memorySummaries.set(record.agentId, { key: memoryKey, unresolved });
				}
				const worldSignals = this.#usesNativeTools(record)
					? this.#rememberNativeWorldSignals(record, lifecycleGeneration, connectionEpoch, observation)
					: null;
				const classified = classifyObservationTrigger(message.payload, wireObservation, worldSignals);
				const pendingAttention = this.#pendingAttention.get(record.agentId);
				const attention = pendingAttention?.goalRevision === record.goalRevision
					? mergeAttentionTrigger(classified, pendingAttention)
					: classified;
				if (pendingAttention?.goalRevision === record.goalRevision) this.#pendingAttention.delete(record.agentId);
				const ledger = this.#ledger(record.agentId);
				ledger.ingest('observation', wireObservation);
				if (this.#usesNativeTools(record)) {
					const observationSignature = nativeObservationSignature(observation);
					const previousSignature = this.#nativeObservationSignatures.get(record.agentId);
					const supervisedRequest = this.#supervisedObservationRequests.get(record.agentId);
					const forcedContinuation = supervisedRequest?.goalRevision === record.goalRevision
						&& supervisedRequest.lifecycleGeneration === lifecycleGeneration
						&& supervisedRequest.connectionEpoch === connectionEpoch;
					if (forcedContinuation) this.#supervisedObservationRequests.delete(record.agentId);
					const unchangedHeartbeat = attention.attention === false && !forcedContinuation
						&& previousSignature?.goalRevision === record.goalRevision
						&& previousSignature.lifecycleGeneration === lifecycleGeneration
						&& previousSignature.connectionEpoch === connectionEpoch
						&& previousSignature.signature === observationSignature;
					this.#nativeObservationSignatures.set(record.agentId, {
						goalRevision: record.goalRevision,
						lifecycleGeneration,
						connectionEpoch,
						signature: observationSignature,
					});
					const memory = this.#conversationMemory(record.agentId);
					const conversation = memory.history();
					if (unchangedHeartbeat) {
						if (this.#nativeRuntime.refreshObservation(record, observation, { eventSequence: message.payload.eventSequence, conversation })) return;
					}
					this.#nativeRuntime.updateObservation(record, observation, { eventSequence: message.payload.eventSequence, conversation, attention: attention.attention, priority: attention.priority, trigger: attention.trigger });
					this.#goalSupervisor.observed(supervisionKey);
					this.#scheduleNativeTurn(record, {
						agentId: record.agentId,
						goalRevision: record.goalRevision,
						observation,
						memory: this.#memorySummaries.get(record.agentId)?.unresolved,
						executionSettings: this.#planner.getExecutionSettings?.(record.agentId) ?? null,
						eventSequence: message.payload.eventSequence,
						priority: attention.priority,
						trigger: attention.trigger,
						lifecycleGeneration,
						connectionEpoch,
						nativeEvent: { event: 'observation', trigger: forcedContinuation ? 'continuation' : attention.trigger, observation },
					});
					return;
				}
				this.#goalSupervisor.observed(supervisionKey);
				const installed = await this.#programRuntime.onObservation(record, {
					observation,
					eventSequence: message.payload.eventSequence,
					attention: attention.attention,
					priority: attention.priority,
					trigger: attention.trigger,
					receiptMonotonicMs,
					receiptEpochMs,
					observedAtEpochMs: message.payload.observedAtEpochMs,
				});
				if (installed !== null) return;
				this.#scheduleInitialPlan(record, {
					agentId: record.agentId,
					goalRevision: record.goalRevision,
					observation,
					wireObservation,
					eventSequence: message.payload.eventSequence,
					receiptMonotonicMs,
					attention: attention.attention,
					priority: attention.priority,
					trigger: attention.trigger,
					preserveState: false,
					kind: 'initial',
					lifecycleGeneration,
					connectionEpoch,
					input: buildPlannerInput({
						agent: { agentId: record.agentId, provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort },
						goal: record.currentGoal,
						goalRevision: record.goalRevision,
						attentionPriority: attention.priority,
						attentionTrigger: attention.trigger,
						observation,
						memory: this.#memorySummaries.get(record.agentId)?.unresolved,
						executionSettings: this.#planner.getExecutionSettings?.(record.agentId) ?? null,
					}, {
						untrustedFacts: ledger.toPlannerFacts(),
						conversationContext: this.#conversationMemory(record.agentId).toPlannerContext(),
					}),
				});
			}, {
				connectionEpoch,
				coalesceKey: message.payload.attention === false
					&& message.payload.trigger === undefined
					&& !this.#pendingAttention.has(message.agentId)
					&& !this.#supervisedObservationRequests.has(message.agentId)
					? `quiet-observation:${connectionEpoch}:${lifecycleGeneration}:${message.payload.goalRevision}`
					: null,
			});
		});
		this.#listen('action_progress', (message, connectionEpoch) => {
			return this.#enqueueAgent(message.agentId, async () => {
				const record = this.#registry.assertCurrentRevision(message.agentId, message.payload.goalRevision);
				const nativeWork = this.#providerWork.get(message.agentId);
				if (this.#usesNativeTools(record) && nativeWork?.goalRevision === record.goalRevision) {
					this.#goalSupervisor.progress(nativeWork.supervisionToken);
					if (nativeWork.toolSupervisionToken !== null) this.#goalSupervisor.progress(nativeWork.toolSupervisionToken);
				}
				if (this.#usesNativeTools(record) && this.#nativeRuntime.onActionProgress(record, message.payload)) {
					this.emit('actionProgress', message);
					return;
				}
				if (this.#usesNativeTools(record) && this.#nativeRuntime.isActionResultStale(record, message.payload)) return;
				if (!this.#programRuntime.onActionProgress(record, message.payload)) throw new ProtocolV2Error('UNEXPECTED_ACTION_RESULT', `Agent '${message.agentId}' has no outstanding program action`);
				this.emit('actionProgress', message);
			}, { connectionEpoch });
		});
		this.#listen('action_result', (message, connectionEpoch) => {
			return this.#enqueueAgent(message.agentId, async () => {
				let acknowledge = false;
				try {
					if (message.payload.actionId.startsWith('native:')) await this.#nativeRuntime.reconcileActionReceipt(message.agentId, message.payload);
					else await this.#playerMemory.recordResult({ agentId: message.agentId, goalRevision: message.payload.goalRevision }, message.payload);
					this.#memorySummaries.delete(message.agentId);
					const current = this.#registry.get(message.agentId);
					if (current === null || message.payload.goalRevision !== current.goalRevision) {
						acknowledge = true;
						return;
					}
					const record = this.#registry.assertCurrentRevision(message.agentId, message.payload.goalRevision);
					this.#ledger(record.agentId).ingest('action_result', message.payload);
					if (this.#usesNativeTools(record) && this.#nativeRuntime.onActionResult(record, message.payload)) {
						acknowledge = true;
						this.emit('actionResult', message);
						return;
					}
					if (this.#usesNativeTools(record) && this.#nativeRuntime.isActionResultStale(record, message.payload)) {
						acknowledge = true;
						return;
					}
					if (!await this.#programRuntime.onActionResult(record, message.payload)) {
						if (this.#programRuntime.isActionResultStale(record, message.payload)) {
							acknowledge = true;
							return;
						}
						throw new ProtocolV2Error('UNEXPECTED_ACTION_RESULT', `Agent '${message.agentId}' has no outstanding program action`);
					}
					acknowledge = true;
					this.emit('actionResult', message);
				} finally {
					if (acknowledge) await this.#acknowledgeActionResult(message, connectionEpoch);
				}
			}, { connectionEpoch, terminal: true });
		});
		this.#listen('goal_completion_result', (message, connectionEpoch) => {
			return this.#enqueueAgent(message.agentId, async () => {
				const current = this.#registry.get(message.agentId);
				if (current === null || current.goalRevision !== message.payload.goalRevision) return;
				if (this.#usesNativeTools(current) && this.#nativeRuntime.onCompletionResult(current, message.payload)) return;
				const accepted = this.#programRuntime.onCompletionResult(current, message.payload);
				if (!accepted) throw new ProtocolV2Error('UNEXPECTED_COMPLETION_RESULT', `Agent '${message.agentId}' has no matching completion request`);
			}, { connectionEpoch, transactional: true });
		});
		this.#listen('disconnected', (event) => {
			const connectionEpoch = this.#eventConnectionEpoch(event);
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			this.#inspections.cancel(undefined, 'BRIDGE_DISCONNECTED');
			void this.#playerMemory.markUnknown(undefined, 'BRIDGE_DISCONNECTED').catch((error) => this.#emitRuntimeError(error));
			this.#connected = false;
			this.#setVerboseEnabled(false);
			this.#cancelGoalSpecRequests();
			for (const record of this.#registry.list()) {
				const work = this.#providerWork.get(record.agentId);
				if (work?.kind === 'native') this.#restoreNativeConversation(work.request);
				this.#nativeConversationRecoveries.delete(record.agentId);
				this.#goalSupervisor.suspend(this.#supervisionKey(record));
				this.#advanceLifecycleGeneration(record.agentId);
			}
			this.#run(async () => {
			if (this.#connectionEpoch !== connectionEpoch) return;
			this.#disconnectedAt ??= safeClockRead(this.#controlNow);
			this.#reconciledStatus = false;
			this.#supportedAgentIds.clear();
			this.#programRuntimeEpochs.clear();
			this.#nativeRuntimeEpochs.clear();
			this.#programRuntime.disposeAll();
			void this.#nativeRuntime.disposeAll('bridge_disconnected');
			this.#pendingAttention.clear();
			this.#attentionFlushes.clear();
			this.#providerRetryAfter.clear();
			this.#providerProbeDeadlines.clear();
			this.#deferredProviderRecovery.clear();
			this.#providerWork.clear();
			await Promise.allSettled(this.#registry.list().map(async (record) => {
				if ([DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING].includes(record.state)) {
					this.#registry.setState(record.agentId, DynamicAgentState.DISCONNECTED, { goalRevision: record.goalRevision });
				}
				await this.#planner.interrupt(record.agentId, 'Minecraft bridge disconnected');
			}));
			}, connectionEpoch, { requireConnected: false });
		}, { lifecycle: true });
		this.#listen('shutdown', () => {
			this.#run(async () => {
				try { this.emit('shutdown'); }
				finally { await this.stop(); }
			});
		});
		this.#listen('protocolError', (error) => this.emit('runtimeError', error), { lifecycle: true });
		this.#listen('transportError', (error) => this.emit('runtimeError', error), { lifecycle: true });
	}

	#beginReconciliation(registry, connectionEpoch) {
		let startedReconciliation;
		try {
			startedReconciliation = this.#planner.beginReconcile(registry, { recovery: true });
		} catch (error) {
			startedReconciliation = { complete: Promise.reject(error) };
		}
		const reconciliation = Promise.resolve().then(async () => {
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
			if (typeof this.#codexService.bootstrapCatalog === 'function') {
				const catalog = await this.#codexService.bootstrapCatalog(registry);
				if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
				await this.#publishCatalog(catalog, connectionEpoch);
			}
			const result = await startedReconciliation.complete;
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
			const providers = result.providers ?? result.codex;
			await this.#publishCatalog(providers.catalog, connectionEpoch);
			const deadStatePlans = [];
			for (const profile of providers.valid) {
				if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
				const record = this.#registry.get(profile.agentId);
				if (record === null) throw new ProtocolV2Error('UNKNOWN_AGENT', `Reconciled provider profile references unknown agent '${profile.agentId}'`);
				if (this.#supportedAgentIds.has(profile.agentId)) continue;
				if (record.state === DynamicAgentState.STARTING && record.currentGoal !== null) {
					this.#goalSupervisor.activate(this.#supervisionKey(record));
				}
				await this.#sendForEpoch(connectionEpoch, 'agent_ready', profile.agentId, { goalRevision: record.goalRevision, reconciled: true });
				this.#supportedAgentIds.add(profile.agentId);
				this.#publishVerbose(record.agentId, record.goalRevision, 'lifecycle', 'Agent reconciled and ready.', connectionEpoch);
				this.#prewarmNativeAgent(record);
				if (record.state === DynamicAgentState.DEAD) deadStatePlans.push(this.#installDeadStatePlan(record, record.death, connectionEpoch));
			}
			for (const invalid of providers.invalid) {
				if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
				const agentId = invalid.agentId ?? invalid.profile?.agentId;
				await this.#sendForEpoch(connectionEpoch, 'agent_error', agentId, {
					goalRevision: this.#registry.get(agentId)?.goalRevision ?? 0,
					code: sanitizeDiagnosticCode(invalid.code, { fallback: 'INVALID_PROFILE' }),
					message: sanitizeDiagnosticText(invalid.message ?? 'Provider profile is unavailable.', { maxBytes: 2_048 }),
				});
			}
			await Promise.all(deadStatePlans);
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
			this.#reconciledStatus = this.#readyRegistry.every(({ agentId }) => this.#supportedAgentIds.has(agentId));
			if (this.#disconnectedAt !== null) this.#disconnectedAt = null;
			await this.#publishStatus(connectionEpoch);
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
			this.emit('reconciled', result);
			return result;
		});
		this.#reconciliation = reconciliation.catch((error) => {
			if (this.#isConnectionEpochCurrent(connectionEpoch)) this.#emitRuntimeError(error);
			return null;
		});
	}

	#bindProviderRecovery() {
		if (typeof this.#codexService.on !== 'function' || typeof this.#codexService.off !== 'function') return;
		const listener = () => this.#requestProviderRecovery(this.#connectionEpoch, { force: true });
		this.#codexService.on('providerRestored', listener);
		this.#providerListeners.push(['providerRestored', listener]);
	}

	#requestProviderRecovery(connectionEpoch, { force = false } = {}) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch) || this.#providerRecoveryPending) return;
		if (!this.#readyRegistry.some(({ agentId }) => !this.#supportedAgentIds.has(agentId))) return;
		if (!force && !this.#providerProbeDue()) return;
		this.#providerRecoveryPending = true;
		void Promise.resolve(this.#reconciliation).finally(() => {
			this.#providerRecoveryPending = false;
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			if (!this.#readyRegistry.some(({ agentId }) => !this.#supportedAgentIds.has(agentId))) return;
			this.#beginReconciliation(structuredClone(this.#readyRegistry), connectionEpoch);
		});
	}

	#providerProbeDue() {
		if (typeof this.#codexService.recoverySnapshot !== 'function') return true;
		let recovery;
		try { recovery = this.#codexService.recoverySnapshot(); }
		catch { return true; }
		if (!Array.isArray(recovery)) return true;
		const missingProviders = new Set(this.#readyRegistry
			.filter(({ agentId }) => !this.#supportedAgentIds.has(agentId))
			.map(({ provider }) => provider ?? 'codex'));
		const now = safeClockRead(this.#epochNow);
		for (const provider of missingProviders) {
			const record = recovery.find((entry) => entry?.provider === provider);
			if (record?.state !== 'degraded' || record.nextProbeAtEpochMs === null || now === null || now >= record.nextProbeAtEpochMs) return true;
		}
		return false;
	}

	#listen(event, listener, { lifecycle = false } = {}) {
		const registered = lifecycle ? listener : (message) => {
			const connectionEpoch = this.#eventConnectionEpoch(message);
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
			const result = listener(message, connectionEpoch);
			if (typeof message?.waitUntil === 'function' && result !== undefined) message.waitUntil(result);
			return result;
		};
		this.#bridge.on(event, registered);
		this.#listeners.push([event, registered]);
	}

	#acceptReadyEpoch(connection) {
		const supplied = connection?.connectionEpoch;
		let connectionEpoch;
		if (supplied === undefined) {
			connectionEpoch = this.#connected && connection?.serverInstanceId === this.#serverInstanceId
				? this.#connectionEpoch
				: this.#connectionEpoch + 1;
		} else if (!Number.isSafeInteger(supplied) || supplied < 1) {
			this.#emitRuntimeError(new ProtocolV2Error('INVALID_CONNECTION_EPOCH', 'Bridge ready event requires a positive connection epoch'));
			return null;
		} else {
			connectionEpoch = supplied;
		}
		if (connectionEpoch <= this.#connectionEpoch) return null;
		this.#inspections.cancel(undefined, 'STALE_CONNECTION_EPOCH');
		this.#memorySummaries.clear();
		if (this.#connectionEpoch > 0) {
			for (const record of this.#registry.list()) {
				void this.#nativeRuntime.dispose(record.agentId, 'connection_replaced').catch((error) => this.#emitRuntimeError(error));
			}
		}
		this.#connectionEpoch = connectionEpoch;
		this.#connected = true;
		return connectionEpoch;
	}

	#eventConnectionEpoch(event) {
		const supplied = event?.connectionEpoch;
		return Number.isSafeInteger(supplied) && supplied >= 1 ? supplied : this.#connectionEpoch;
	}

	#isConnectionEpochCurrent(connectionEpoch) {
		return this.#connected && Number.isSafeInteger(connectionEpoch) && connectionEpoch === this.#connectionEpoch;
	}

	async #installDeadStatePlan(record, death, connectionEpoch = this.#connectionEpoch) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) return null;
		if (death === null || death === undefined) throw new ProtocolV2Error('MISSING_FIELD', `DEAD agent '${record.agentId}' requires death facts`);
		if (record.currentGoal === null) return;
		if (this.#programRuntime.hasCurrent(record)) return;
		this.#playerMemory.observe(record, { death: structuredClone(death) });
		const lifecycleGeneration = this.#lifecycleGeneration(record.agentId);
		if (this.#usesNativeTools(record)) {
			this.#nativeRuntimeEpochs.set(record.agentId, connectionEpoch);
			const observation = { death: structuredClone(death) };
			const live = this.#nativeRuntime.snapshotLive(record.agentId);
			const eventSequence = Number.isSafeInteger(live?.eventSequence) ? live.eventSequence : 0;
			this.#nativeRuntime.updateObservation(record, observation, {
				eventSequence,
				conversation: this.#conversationMemory(record.agentId).history(),
				force: true,
			});
			return this.#scheduleNativeTurn(record, {
				agentId: record.agentId,
				goalRevision: record.goalRevision,
				observation,
				eventSequence,
				priority: 'urgent',
				trigger: 'player_death',
				preserveState: true,
				lifecycleGeneration,
				connectionEpoch,
				nativeEvent: { event: 'player_death', trigger: 'player_death', observation },
			});
		}
		return this.#scheduleProviderPlan(record, {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			observation: { death },
			eventSequence: 0,
			attention: true,
			priority: 'urgent',
			trigger: 'player_death',
			preserveState: true,
			kind: 'death',
			lifecycleGeneration,
			connectionEpoch,
			input: buildPlannerInput({
				agent: { agentId: record.agentId, provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort },
				goal: record.currentGoal,
				goalRevision: record.goalRevision,
				decisionContext: 'player_death',
				attentionPriority: 'urgent',
				attentionTrigger: 'player_death',
				death,
			}),
		}, { preserveState: true, kind: 'death' });
	}

	#rememberNativeWorldSignals(record, lifecycleGeneration, connectionEpoch, observation) {
		const previous = this.#nativeWorldSignals.get(record.agentId);
		const sameLifecycle = previous?.goalRevision === record.goalRevision
			&& previous.lifecycleGeneration === lifecycleGeneration
			&& previous.connectionEpoch === connectionEpoch;
		const state = sameLifecycle
			? previous
			: { goalRevision: record.goalRevision, lifecycleGeneration, connectionEpoch, positions: [], resources: new Set() };
		const positionKey = nativeBlockPositionKey(observation?.player);
		const previousPositionKey = state.positions.at(-1);
		if (positionKey !== null && positionKey !== previousPositionKey) {
			state.positions.push(positionKey);
			if (state.positions.length > MAX_NATIVE_MOVEMENT_HISTORY) state.positions.shift();
		}
		let resourceDiscovery = false;
		for (const candidate of observedResourceCandidates(observation)) {
			if (!state.resources.has(candidate)) resourceDiscovery = true;
			state.resources.add(candidate);
		}
		while (state.resources.size > MAX_NATIVE_RESOURCE_MEMORY) state.resources.delete(state.resources.values().next().value);
		this.#nativeWorldSignals.set(record.agentId, state);
		return {
			movementLoop: detectMovementLoop(state.positions),
			resourceDiscovery,
		};
	}

	#usesNativeTools(record) {
		return this.#codexControlProtocol === 'native_tools' && record?.provider === 'codex';
	}

	#prewarmNativeAgent(record) {
		if (!this.#usesNativeTools(record) || record.state !== DynamicAgentState.IDLE || typeof this.#codexService.prewarmAgent !== 'function') return;
		void Promise.resolve(this.#codexService.prewarmAgent(record, { goalRevision: record.goalRevision })).catch((error) => {
			this.#writeTrace('native_prewarm_failed', {
				agentId: record.agentId,
				goalRevision: record.goalRevision,
				errorCode: String(error?.code ?? 'PREWARM_FAILED').slice(0, 128),
			});
		});
	}

	#scheduleNativeConversation(record, event, trigger) {
		const conversationOnly = [DynamicAgentState.IDLE, DynamicAgentState.COMPLETED, DynamicAgentState.PAUSED].includes(record.state);
		if (!conversationOnly && ![DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING].includes(record.state)) return;
		const lifecycleGeneration = this.#lifecycleGeneration(record.agentId);
		const connectionEpoch = this.#connectionEpoch;
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
		this.#nativeRuntimeEpochs.set(record.agentId, connectionEpoch);
		this.#scheduleNativeTurn(record, {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			priority: 'urgent',
			trigger,
			lifecycleGeneration,
			connectionEpoch,
			preserveState: conversationOnly,
			conversationOnly,
			nativeEvent: {
				event: event?.kind ?? 'conversation',
				trigger,
				observation: {},
				conversationOnly,
			},
		});
	}

	#scheduleNativeTurn(record, request) {
		if (!this.#isConnectionEpochCurrent(request.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration)) return;
		request = this.#afterProviderProbeDeadline(record, request);
		if (request === null) return;
		const existing = this.#providerWork.get(record.agentId);
		if (existing !== undefined) {
			if (
				existing.kind === 'native'
				&& existing.goalRevision === request.goalRevision
				&& existing.lifecycleGeneration === request.lifecycleGeneration
				&& existing.connectionEpoch === request.connectionEpoch
				&& request.priority === 'urgent'
			) {
				this.#queueNativeSteer(existing, request);
				return existing.promise;
			}
			existing.pending = mergePlannerRequest(existing.pending, request);
			return existing.promise;
		}
		const supervisionKey = this.#supervisionKey(record, request.lifecycleGeneration);
		this.#forgetNativeConversationRecovery(supervisionKey);
		this.#goalSupervisor.activate(supervisionKey);
		const work = {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			lifecycleGeneration: request.lifecycleGeneration,
			connectionEpoch: request.connectionEpoch,
			kind: 'native',
			request,
			pending: null,
			steerQueued: null,
			steerPromise: null,
			traceId: planningTraceId(record.agentId, record.goalRevision, request.lifecycleGeneration, 'native'),
			promise: null,
			supervisionKey,
			supervisionToken: this.#goalSupervisor.begin(supervisionKey, 'provider', { timeoutMs: Math.min(MAX_LEASE_TIMEOUT_MS, this.#planner.getExecutionSettings?.(record.agentId)?.limits?.nativeTurnBudgetMs ?? MAX_LEASE_TIMEOUT_MS) }),
			toolSupervisionToken: null,
			expired: false,
		};
		this.#providerWork.set(record.agentId, work);
		try {
			if (request.preserveState !== true) {
				if (record.state === DynamicAgentState.STARTING) this.#registry.setState(record.agentId, DynamicAgentState.PLANNING, { goalRevision: record.goalRevision });
				void this.#sendForEpoch(request.connectionEpoch, 'planning_state', record.agentId, { goalRevision: record.goalRevision, state: DynamicAgentState.PLANNING })
					.catch((error) => this.#reportAgentError(record.agentId, error, request.connectionEpoch));
			}
		} catch (error) {
			this.#providerWork.delete(record.agentId);
			this.#goalSupervisor.end(work.supervisionToken);
			throw error;
		}
		const verboseReporter = this.#verboseReporter(record.agentId, record.goalRevision, { allowPublicAgentMessage: true, connectionEpoch: request.connectionEpoch });
		work.promise = Promise.resolve()
			.then(() => this.#planner.requestNativeTurn({
				agentId: record.agentId,
				goalRevision: record.goalRevision,
				input: this.#nativeTurnInput(record, request),
				recoverySummary: record.lastSummary,
				priority: request.priority,
				preserveState: request.preserveState === true,
				traceId: work.traceId,
				onVerbose: verboseReporter,
				onProgress: () => {
					if (this.#providerWork.get(record.agentId) === work && this.#isConnectionEpochCurrent(request.connectionEpoch)) this.#goalSupervisor.progress(work.supervisionToken);
				},
				executeTool: (toolRequest) => this.#executeNativeTool(work, toolRequest),
			}))
			.then((result) => this.#completeNativeTurn(work, result), (error) => this.#failNativeTurn(work, error))
			.finally(() => verboseReporter.dispose());
		return work.promise;
	}

	#queueNativeSteer(work, request) {
		work.steerQueued = mergePlannerRequest(work.steerQueued, request);
		if (work.steerPromise !== null) return;
		const steering = this.#drainNativeSteering(work);
		const tracked = steering.finally(() => {
			if (work.steerPromise === tracked) work.steerPromise = null;
		});
		work.steerPromise = tracked;
	}

	async #drainNativeSteering(work) {
		while (work.steerQueued !== null) {
			const request = work.steerQueued;
			work.steerQueued = null;
			try {
				const record = this.#registry.get(work.agentId);
				if (record === null || record.goalRevision !== work.goalRevision) throw Object.assign(new Error('Native steering belongs to an obsolete goal'), { code: 'STALE_PLAN' });
				await this.#planner.steerNativeTurn({
					agentId: work.agentId,
					goalRevision: work.goalRevision,
					input: this.#nativeTurnInput(record, request),
				});
				this.#writeTrace('native_turn_steered', {
					agentId: work.agentId,
					goalRevision: work.goalRevision,
					traceId: work.traceId,
					trigger: request.trigger,
				});
			} catch (error) {
				this.#restoreNativeConversation(request);
				work.pending = mergePlannerRequest(work.pending, request);
				if (work.steerQueued !== null) work.pending = mergePlannerRequest(work.pending, work.steerQueued);
				work.steerQueued = null;
				this.#writeTrace('native_turn_steer_deferred', {
					agentId: work.agentId,
					goalRevision: work.goalRevision,
					traceId: work.traceId,
					errorCode: String(error?.code ?? 'TURN_STEER_FAILED').slice(0, 128),
				});
				return;
			}
		}
	}

	async #settleNativeSteering(work) {
		while (work.steerPromise !== null) await work.steerPromise;
	}

	async #executeNativeTool(work, toolRequest) {
		const record = this.#registry.get(work.agentId);
		if (work.expired === true || record === null || record.goalRevision !== work.goalRevision
				|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
			throw Object.assign(new Error('Native tool belongs to an obsolete goal'), { code: 'STALE_PLAN' });
		}
		if (work.request.conversationOnly === true
				&& toolRequest.tool.kind !== 'observe'
				&& !(toolRequest.tool.kind === 'action' && toolRequest.tool.actionType === 'chat')) {
			throw Object.assign(new Error('Idle conversation turns may only observe and reply with chat'), { code: 'CONVERSATION_ONLY' });
		}
		const executesBody = toolRequest.tool.kind === 'action'
			|| toolRequest.tool.kind === 'sequence'
			|| toolRequest.tool.kind === 'lookAround'
			|| toolRequest.tool.kind === 'run_program'
			|| toolRequest.tool.kind === 'start_action'
			|| toolRequest.tool.kind === 'replace_action';
		const supervisionKind = executesBody ? 'action' : toolRequest.tool.kind === 'finish' ? 'completion' : null;
		let supervisionToken = null;
		let result;
		try {
			if (executesBody && record.state === DynamicAgentState.PLANNING) {
				this.#registry.setState(record.agentId, DynamicAgentState.ACTING, { goalRevision: record.goalRevision });
			}
			supervisionToken = supervisionKind === null ? null : this.#goalSupervisor.begin(work.supervisionKey, supervisionKind);
			work.toolSupervisionToken = supervisionToken;
			this.#goalSupervisor.progress(work.supervisionToken);
			result = await this.#nativeRuntime.execute(toolRequest, record, { lifecycleGeneration: work.lifecycleGeneration });
			return result;
		} finally {
			if (supervisionToken !== null) {
				this.#goalSupervisor.end(supervisionToken, { progress: ['SUCCEEDED', 'COMPLETED'].includes(result?.state) });
			}
			if (work.toolSupervisionToken === supervisionToken) work.toolSupervisionToken = null;
			this.#goalSupervisor.progress(work.supervisionToken);
			const latest = this.#registry.get(work.agentId);
			if (!this.#stopping && !this.#closed && executesBody && latest?.goalRevision === work.goalRevision && latest.state === DynamicAgentState.ACTING
				&& this.#isConnectionEpochCurrent(work.connectionEpoch)
				&& this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
				this.#registry.setState(latest.agentId, DynamicAgentState.PLANNING, { goalRevision: latest.goalRevision });
			}
		}
	}

	async #completeNativeTurn(work, result) {
		try {
			await this.#settleNativeSteering(work);
		} finally {
			this.#goalSupervisor.end(work.supervisionToken, { progress: (result?.toolCalls ?? 0) > 0 });
			if (work.request.conversationOnly === true) this.#goalSupervisor.terminate(work.supervisionKey);
		}
		if (this.#providerWork.get(work.agentId) !== work) return null;
		this.#providerWork.delete(work.agentId);
		this.#providerRetryAfter.delete(work.agentId);
		const pending = work.pending;
		const record = this.#registry.get(work.agentId);
		if (record !== null && record.goalRevision === work.goalRevision
				&& this.#isConnectionEpochCurrent(work.connectionEpoch)
				&& this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
			this.#writeTrace('native_turn_completed', { agentId: work.agentId, goalRevision: work.goalRevision, traceId: work.traceId, toolCalls: result?.toolCalls ?? 0 });
		}
		if (work.request.conversationOnly === true && (result?.toolCalls ?? 0) === 0
				&& work.request.conversationRetry !== true && record !== null) {
			this.#scheduleNativeTurn(record, {
				...work.request,
				connectionEpoch: work.connectionEpoch,
				conversationRetry: true,
				retryInstruction: 'Your previous turn made no visible reply. Call say exactly once now.',
			});
			return result;
		}
		const rescheduled = this.#reschedulePendingNativeTurn(pending);
		if (!rescheduled && this.#isActiveNativeGoal(work)) {
			this.#goalSupervisor.ensure(work.supervisionKey, (result?.toolCalls ?? 0) === 0 ? 'zero_tool_turn' : 'turn_completed');
		}
		return result;
	}

	async #failNativeTurn(work, error) {
		let recovery = classifyRecoveryFailure(error);
		let classification = recovery.retryable ? 'recoverable' : classifyNativeGoalError(error);
		try {
			await this.#settleNativeSteering(work);
		} catch (steeringError) {
			error = steeringError;
			recovery = classifyRecoveryFailure(error);
			classification = recovery.retryable ? 'recoverable' : classifyNativeGoalError(error);
		} finally {
			this.#goalSupervisor.end(work.supervisionToken, { scheduleRecovery: classification !== 'stale' });
		}
		if (this.#providerWork.get(work.agentId) !== work) return null;
		this.#providerWork.delete(work.agentId);
		const pending = work.pending;
		const record = this.#registry.get(work.agentId);
		const staleLifecycle = record?.goalRevision !== work.goalRevision
			|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
			|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration);
		if (staleLifecycle || this.#stopping || this.#closed) {
			if (work.request.conversationOnly === true) this.#goalSupervisor.terminate(work.supervisionKey);
			this.#reschedulePendingNativeTurn(pending);
			return null;
		}
		this.#restoreNativeConversation(work.request);
		if (classification === 'stale') {
			if (work.request.conversationOnly === true) this.#goalSupervisor.terminate(work.supervisionKey);
			this.#reschedulePendingNativeTurn(pending);
			return null;
		}
		await this.#nativeRuntime.dispose(work.agentId, 'native_turn_failed');
		this.#nativeObservationSignatures.delete(work.agentId);
		if (classification === 'terminal') {
			this.#goalSupervisor.terminate(work.supervisionKey);
			if ([DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING].includes(record.state)) {
				this.#registry.setState(work.agentId, DynamicAgentState.ERROR, {
					goalRevision: work.goalRevision,
					error: { code: sanitizeDiagnosticErrorCode(error, { fallback: 'NATIVE_TURN_FAILED' }), message: sanitizeDiagnosticErrorMessage(error, { maxBytes: 2_048 }) },
				});
			}
			await this.#reportAgentError(work.agentId, error, work.connectionEpoch);
		} else {
			if (work.request.conversationOnly === true) {
				this.#nativeConversationRecoveries.set(work.agentId, {
					supervisionKey: work.supervisionKey,
					request: work.request,
				});
			}
			this.#goalSupervisor.recover(work.supervisionKey, this.#recoveryDetails(work.agentId, {
				errorCode: recovery.code,
				recoveryKind: recovery.kind,
				nextProbeAtEpochMs: recovery.nextProbeAtEpochMs,
			}));
			this.#reschedulePendingNativeTurn(pending);
		}
		return null;
	}

	#reschedulePendingNativeTurn(request) {
		if (request === null || request === undefined || this.#stopping || this.#closed) return false;
		const record = this.#registry.get(request.agentId);
		if (record === null || record.goalRevision !== request.goalRevision
				|| !this.#isConnectionEpochCurrent(request.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration)) return false;
		if (!this.#usesNativeTools(record) || ![DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING, DynamicAgentState.DEAD].includes(record.state)) return false;
		this.#scheduleNativeTurn(record, request);
		return true;
	}

	#isActiveNativeGoal(work) {
		if (this.#stopping || this.#closed) return false;
		const record = this.#registry.get(work.agentId);
		return record !== null
			&& record.goalRevision === work.goalRevision
			&& this.#isConnectionEpochCurrent(work.connectionEpoch)
			&& this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)
			&& this.#usesNativeTools(record)
			&& [DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING, DynamicAgentState.DEAD].includes(record.state);
	}

	#scheduleInitialPlan(record, request) {
		if (!this.#isConnectionEpochCurrent(request.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration)) return;
		request = this.#afterProviderProbeDeadline(record, request);
		if (request === null) return;
		const existing = this.#providerWork.get(record.agentId);
		if (existing !== undefined) {
			if (existing.goalRevision !== record.goalRevision || existing.lifecycleGeneration !== request.lifecycleGeneration) {
				existing.pending = mergePlannerRequest(existing.pending, request);
				return;
			}
			if (request.priority === 'urgent' && existing.request.priority !== 'urgent') {
				// Retain the urgent payload before cancellation settles. The scheduler
				// may reject the queued turn immediately, so interruption alone cannot
				// be the handoff mechanism for the replacement request.
				existing.pending = mergePlannerRequest(existing.pending, request);
				void Promise.resolve(this.#planner.interrupt(record.agentId, 'Urgent planning trigger')).catch(() => {});
				return;
			}
			existing.pending = mergePlannerRequest(existing.pending, request);
			return;
		}
		if (this.#scheduler.hasScheduled(record.agentId)) return;
		if (request.priority !== 'urgent' && request.receiptMonotonicMs !== null && (this.#providerRetryAfter.get(record.agentId) ?? 0) > request.receiptMonotonicMs) return;
		void this.#sendForEpoch(request.connectionEpoch, 'planning_state', record.agentId, { goalRevision: record.goalRevision, state: DynamicAgentState.PLANNING })
			.catch((error) => this.#reportAgentError(record.agentId, error, request.connectionEpoch));
		void this.#scheduleProviderPlan(record, request, { preserveState: false, kind: 'initial' });
	}

	#afterProviderProbeDeadline(record, request) {
		const probeDeadline = this.#providerProbeDeadlines.get(record.agentId);
		const now = safeClockRead(this.#epochNow);
		if (probeDeadline !== undefined && now !== null && now < probeDeadline) {
			const deferred = this.#deferredProviderRecovery.get(record.agentId);
			this.#deferredProviderRecovery.set(record.agentId, mergePlannerRequest(deferred, request));
			return null;
		}
		if (probeDeadline !== undefined) {
			this.#providerProbeDeadlines.delete(record.agentId);
			const deferred = this.#deferredProviderRecovery.get(record.agentId);
			this.#deferredProviderRecovery.delete(record.agentId);
			request = mergePlannerRequest(deferred, request);
		}
		return request;
	}

	#scheduleProviderPlan(record, request, { preserveState = false, kind = 'initial' } = {}) {
		const lifecycleGeneration = request.lifecycleGeneration ?? this.#lifecycleGeneration(record.agentId);
		const connectionEpoch = request.connectionEpoch ?? this.#connectionEpoch;
		if (!this.#isConnectionEpochCurrent(connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(record.agentId, lifecycleGeneration)) return Promise.resolve(null);
		const existing = this.#providerWork.get(record.agentId);
		if (existing !== undefined) {
			if (existing.goalRevision !== record.goalRevision || existing.lifecycleGeneration !== lifecycleGeneration
					|| existing.connectionEpoch !== connectionEpoch) {
				existing.pending = mergePlannerRequest(existing.pending, request);
				return existing.promise;
			}
			existing.pending = mergePlannerRequest(existing.pending, request);
			return existing.promise;
		}
		const work = {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			lifecycleGeneration,
			connectionEpoch,
			kind,
			preserveState,
			request,
			contextSnapshot: this.#contextSnapshot(record),
			pending: null,
			traceId: request.traceId ?? planningTraceId(record.agentId, record.goalRevision, lifecycleGeneration, kind),
			promise: null,
		};
		this.#providerWork.set(record.agentId, work);
		const verboseReporter = this.#verboseReporter(record.agentId, record.goalRevision, { connectionEpoch });
		const providerRequest = {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			preserveState,
			recoverySummary: record.lastSummary,
			input: request.input,
			traceId: work.traceId,
			planningPriority: request.priority,
			priority: request.priority,
			onVerbose: verboseReporter,
		};
		work.promise = Promise.resolve()
			.then(() => this.#planner.requestPlan(providerRequest))
			.then((decision) => this.#completeProviderPlan(work, decision), (error) => this.#failProviderPlan(work, record, error))
			.finally(() => verboseReporter.dispose());
		return work.promise;
	}

	async #completeProviderPlan(work, decision) {
		if (this.#providerWork.get(work.agentId) !== work) return null;
		const record = this.#registry.get(work.agentId);
		if (record === null || record.goalRevision !== work.goalRevision
				|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
			const pending = work.pending;
			this.#providerWork.delete(work.agentId);
			this.#reschedulePendingProviderPlan(pending);
			return null;
		}
		let runtime = null;
		try {
			this.#programRuntimeEpochs.set(record.agentId, work.connectionEpoch);
			runtime = await this.#programRuntime.installDecision(record, decision, {
				observation: work.request.observation,
				eventSequence: work.request.eventSequence,
				traceId: work.traceId,
			});
		} catch (error) {
			if (this.#providerWork.get(work.agentId) !== work) return null;
			this.#providerWork.delete(work.agentId);
			const current = this.#registry.get(work.agentId);
			const stale = current?.goalRevision !== work.goalRevision
				|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration);
			if (this.#programRuntimeEpochs.get(work.agentId) === work.connectionEpoch) this.#programRuntimeEpochs.delete(work.agentId);
			if (!stale) await this.#reportAgentError(work.agentId, error, work.connectionEpoch);
			if (stale || work.pending?.priority === 'urgent') this.#reschedulePendingProviderPlan(work.pending);
			return null;
		}
		if (this.#providerWork.get(work.agentId) !== work) return null;
		const current = this.#registry.get(work.agentId);
		if (current?.goalRevision !== work.goalRevision
				|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) {
			const pending = work.pending;
			this.#providerWork.delete(work.agentId);
			if (this.#programRuntimeEpochs.get(work.agentId) === work.connectionEpoch) {
				this.#programRuntime.dispose(work.agentId);
				this.#programRuntimeEpochs.delete(work.agentId);
			}
			this.#reschedulePendingProviderPlan(pending);
			return null;
		}
		const pending = work.pending;
		this.#providerWork.delete(work.agentId);
		this.#providerRetryAfter.delete(work.agentId);
		this.#publishVerbose(record.agentId, record.goalRevision, 'decision', verboseDecisionSummary(decision), work.connectionEpoch);
		if (runtime === null) return runtime;
		const latest = this.#registry.get(work.agentId);
		if (latest === null || latest.goalRevision !== work.goalRevision
				|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration)) return runtime;
		this.#rememberAcceptedContextCursor(latest, work.contextSnapshot);
		this.#flushPendingAttention(latest, work.connectionEpoch);
		if (pending === null) return runtime;
		try {
			if (pending.observation !== undefined) {
				await this.#programRuntime.onObservation(latest, {
					observation: pending.observation,
					eventSequence: pending.eventSequence,
					attention: pending.attention,
					priority: pending.priority,
					trigger: pending.trigger,
				});
			} else if (pending.attention) {
				this.#programRuntime.notifyAttention(latest, { priority: pending.priority, trigger: pending.trigger });
			}
		} catch (error) {
			await this.#reportAgentError(work.agentId, error, work.connectionEpoch);
		}
		return runtime;
	}

	async #failProviderPlan(work, record, error) {
		if (this.#providerWork.get(work.agentId) !== work) return null;
		const current = this.#registry.get(work.agentId);
		const stale = current?.goalRevision !== work.goalRevision
			|| !this.#isConnectionEpochCurrent(work.connectionEpoch)
			|| !this.#isLifecycleGenerationCurrent(work.agentId, work.lifecycleGeneration);
		this.#promotePendingAttention(work, current);
		const pending = work.pending;
		this.#providerWork.delete(work.agentId);
		const urgentRecovery = pending?.priority === 'urgent';
		const recovery = classifyRecoveryFailure(error);
		const quietRetry = recovery.quiet;
		if (!stale && recovery.retryable) {
			if (quietRetry) {
				const retryAt = safeClockRead(this.#controlNow);
				if (retryAt !== null) this.#providerRetryAfter.set(record.agentId, retryAt + EMPTY_TURN_RETRY_DELAY_MS);
			}
			this.#publishVerbose(record.agentId, record.goalRevision, 'retry', verboseRecoveryMessage(recovery), work.connectionEpoch, {
				component: 'provider', boundary: recovery.kind ?? 'planning', code: recovery.code ?? 'PROVIDER_RETRY', state: 'retrying',
			});
			try {
				const latest = this.#registry.get(record.agentId);
				if (latest?.state === DynamicAgentState.ERROR) this.#registry.setState(record.agentId, DynamicAgentState.STARTING, { goalRevision: record.goalRevision });
				if (this.#registry.get(record.agentId)?.state === DynamicAgentState.STARTING) this.#registry.setState(record.agentId, DynamicAgentState.PLANNING, { goalRevision: record.goalRevision });
			} catch (stateError) {
				void this.#reportAgentError(record.agentId, stateError, work.connectionEpoch);
			}
			const session = typeof this.#codexService.getAgent === 'function' ? this.#codexService.getAgent(record.agentId) : null;
			if (session !== null && typeof this.#codexService.replaceAgent === 'function') {
				try {
					await this.#codexService.replaceAgent(record, {
						recoverySummary: 'provider_plan_recovery',
						controlProtocol: 'arena_script',
						...(Number.isSafeInteger(session.sessionGeneration) ? { expectedSessionGeneration: session.sessionGeneration } : {}),
					});
				} catch (replacementError) {
					this.#writeTrace('provider_session_replacement_failed', {
						agentId: record.agentId,
						goalRevision: record.goalRevision,
						errorCode: replacementError?.code ?? 'SESSION_REPLACEMENT_FAILED',
					});
				}
			}
			const recoveryDetails = this.#recoveryDetails(record.agentId, {
				errorCode: recovery.code,
				recoveryKind: recovery.kind,
				nextProbeAtEpochMs: recovery.nextProbeAtEpochMs,
			});
			const supervisionKey = this.#supervisionKey(record, work.lifecycleGeneration);
			this.#goalSupervisor.activate(supervisionKey);
			this.#goalSupervisor.recover(supervisionKey, recoveryDetails);
			this.#reschedulePendingProviderPlan(pending);
			return null;
		}
		if (!stale && !urgentRecovery) await this.#reportAgentError(record.agentId, error, work.connectionEpoch);
		if (!stale && quietRetry && current?.state === DynamicAgentState.ERROR) {
			try {
				this.#registry.setState(record.agentId, DynamicAgentState.STARTING, { goalRevision: record.goalRevision });
			} catch (stateError) {
				void this.#reportAgentError(record.agentId, stateError, work.connectionEpoch);
				return null;
			}
		}
		if (urgentRecovery || stale) this.#reschedulePendingProviderPlan(pending);
		return null;
	}

	#promotePendingAttention(work, record) {
		const attention = this.#pendingAttention.get(work.agentId);
		if (record === null || attention?.goalRevision !== work.goalRevision) return;
		const request = work.request;
		const input = buildPlannerInput({
			agent: { agentId: record.agentId, provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort },
			goal: record.currentGoal,
			goalRevision: record.goalRevision,
			attentionPriority: 'urgent',
			attentionTrigger: attention.trigger,
			observation: request.observation,
		}, {
			untrustedFacts: this.#ledger(record.agentId).toPlannerFacts(),
			conversationContext: this.#conversationMemory(record.agentId).toPlannerContext(),
		});
		work.pending = mergePlannerRequest(work.pending, {
			...request,
			attention: true,
			priority: 'urgent',
			trigger: attention.trigger,
			preserveState: false,
			input,
		});
		this.#pendingAttention.delete(work.agentId);
	}

	#reschedulePendingProviderPlan(request) {
		if (request === null || request === undefined) return;
		const record = this.#registry.get(request.agentId);
		if (record === null || record.goalRevision !== request.goalRevision
				|| !this.#isConnectionEpochCurrent(request.connectionEpoch)
				|| !this.#isLifecycleGenerationCurrent(record.agentId, request.lifecycleGeneration)) return;
		if (record.state === DynamicAgentState.ERROR && request.preserveState !== true) {
			try {
				this.#registry.setState(record.agentId, DynamicAgentState.STARTING, { goalRevision: record.goalRevision });
			} catch (error) {
				void this.#reportAgentError(record.agentId, error, request.connectionEpoch);
				return;
			}
		}
		request = this.#afterProviderProbeDeadline(record, request);
		if (request === null) return;
		void this.#scheduleProviderPlan(record, request, { preserveState: request.preserveState === true, kind: request.kind ?? 'initial' });
	}

	#unbindBridge() {
		for (const [event, listener] of this.#listeners) this.#bridge.off(event, listener);
		this.#listeners = [];
		if (typeof this.#codexService.off === 'function') {
			for (const [event, listener] of this.#providerListeners) this.#codexService.off(event, listener);
		}
		this.#providerListeners = [];
	}

	#beginGoalControlInterruption(message, connectionEpoch) {
		let reason = null;
		if (['stop', 'disconnect', 'dead'].includes(message.payload.operation)) reason = `Goal ${message.payload.operation}`;
		if (message.payload.operation === 'steer') reason = 'Goal steered';
		if (message.payload.operation === 'replace') reason = 'Goal replaced';
		if (reason === null) return;
		try {
			Promise.resolve(this.#planner.interrupt(message.agentId, reason)).catch((error) => this.#reportAgentError(message.agentId, error, connectionEpoch));
		} catch (error) {
			void this.#reportAgentError(message.agentId, error, connectionEpoch);
		}
	}

	#lifecycleGeneration(agentId) {
		return this.#lifecycleGenerations.get(agentId) ?? 0;
	}

	#supervisionKey(record, lifecycleGeneration = this.#lifecycleGeneration(record.agentId)) {
		return {
			agentId: record.agentId,
			goalRevision: record.goalRevision,
			lifecycleGeneration,
			sessionEpoch: this.#connectionEpoch,
			profileFingerprint: profileFingerprint(record),
		};
	}

	#recoveryDetails(agentId, { errorCode, recoveryKind, nextProbeAtEpochMs }) {
		const deadline = Number.isFinite(nextProbeAtEpochMs) && nextProbeAtEpochMs >= 0 ? nextProbeAtEpochMs : null;
		if (deadline === null) this.#providerProbeDeadlines.delete(agentId);
		else this.#providerProbeDeadlines.set(agentId, deadline);
		const now = safeClockRead(this.#epochNow);
		return {
			errorCode,
			...(recoveryKind === undefined ? {} : { recoveryKind }),
			...(deadline === null ? {} : {
				nextProbeAtEpochMs: deadline,
				retryDelayMs: now === null ? 0 : Math.max(0, deadline - now),
			}),
		};
	}

	#retireGoalSupervision(record, operation) {
		const key = this.#supervisionKey(record);
		if (['disconnect', 'dead'].includes(operation)) this.#goalSupervisor.suspend(key);
		else this.#goalSupervisor.terminate(key);
	}

	#advanceLifecycleGeneration(agentId) {
		this.#inspections.cancel(agentId);
		this.#memorySummaries.delete(agentId);
		const next = this.#lifecycleGeneration(agentId) + 1;
		this.#lifecycleGenerations.set(agentId, next);
		return next;
	}

	#invalidateLifecycleWork(message) {
		if (['queue', 'dequeue'].includes(message.payload.operation)) return;
		const current = this.#registry.get(message.agentId);
		if (current !== null && message.payload.goalRevision <= current.goalRevision) return;
		this.#invalidateAcceptedLifecycle(message.agentId);
	}

	#invalidateAcceptedLifecycle(agentId) {
		this.#pendingAttention.delete(agentId);
		this.#attentionFlushes.delete(agentId);
		this.#contextCursors.delete(agentId);
		this.#nativeObservationSignatures.delete(agentId);
		this.#supervisedObservationRequests.delete(agentId);
		this.#nativeConversationRecoveries.delete(agentId);
		this.#providerProbeDeadlines.delete(agentId);
		this.#deferredProviderRecovery.delete(agentId);
		this.#nativeWorldSignals.delete(agentId);
		this.#advanceLifecycleGeneration(agentId);
	}

	#rememberPendingAttention(agentId, goalRevision, attention) {
		const previous = this.#pendingAttention.get(agentId);
		if (previous === undefined || previous.goalRevision !== goalRevision) {
			this.#pendingAttention.set(agentId, { goalRevision, ...attention });
			return;
		}
		const merged = mergeAttentionTrigger(previous, attention);
		this.#pendingAttention.set(agentId, { goalRevision, ...merged });
	}

	#schedulePendingAttentionFlush(record) {
		if (!this.#programRuntime.hasCurrent(record)) return;
		const existing = this.#attentionFlushes.get(record.agentId);
		if (existing?.goalRevision === record.goalRevision) return;
		const token = { goalRevision: record.goalRevision, connectionEpoch: this.#connectionEpoch };
		this.#attentionFlushes.set(record.agentId, token);
		setImmediate(() => {
			if (this.#attentionFlushes.get(record.agentId) !== token) return;
			this.#attentionFlushes.delete(record.agentId);
			if (this.#stopping || this.#closed || !this.#isConnectionEpochCurrent(token.connectionEpoch)) return;
			const current = this.#registry.get(record.agentId);
			const pending = this.#pendingAttention.get(record.agentId);
			if (current === null || pending?.goalRevision !== token.goalRevision || current.goalRevision !== token.goalRevision) return;
			this.#flushPendingAttention(current, token.connectionEpoch);
		});
	}

	#flushPendingAttention(record, connectionEpoch = this.#connectionEpoch) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
		const pending = this.#pendingAttention.get(record.agentId);
		if (pending?.goalRevision !== record.goalRevision || !this.#programRuntime.hasCurrent(record)) return;
		try {
			const notified = this.#programRuntime.notifyAttention(record, { priority: pending.priority, trigger: pending.trigger });
			if (notified !== null) this.#pendingAttention.delete(record.agentId);
		} catch (error) {
			void this.#reportAgentError(record.agentId, error, connectionEpoch);
		}
	}

	#isLifecycleGenerationCurrent(agentId, generation) {
		return this.#lifecycleGeneration(agentId) === generation;
	}

	#enqueueAgent(agentId, operation, { waitForReconciliation = true, connectionEpoch = this.#connectionEpoch, coalesceKey = null, terminal = false, transactional = false, onAdmitted = null } = {}) {
		const releaseCapacity = this.#reserveAgentOperation(agentId);
		let resolve;
		let reject;
		const promise = new Promise((resolveValue, rejectValue) => { resolve = resolveValue; reject = rejectValue; });
		const waiter = {
			resolve: (value) => { releaseCapacity(); resolve(value); },
			reject: (error) => { releaseCapacity(); reject(error); },
		};
		let queue = this.#agentOperations.get(agentId);
		if (queue === undefined) {
			queue = { items: [], drainPromise: null };
			this.#agentOperations.set(agentId, queue);
		}
		const tail = queue.items.at(-1);
		if (coalesceKey !== null && tail?.coalesceKey === coalesceKey) {
			for (const superseded of tail.waiters) superseded.resolve(undefined);
			tail.operation = operation;
			tail.waitForReconciliation = waitForReconciliation;
			tail.connectionEpoch = connectionEpoch;
			tail.transactional = transactional;
			tail.waiters = [waiter];
		} else {
			const pendingTerminal = queue.items.find((item) => item.terminal === true);
			if (terminal && pendingTerminal !== undefined) {
				for (const superseded of pendingTerminal.waiters) superseded.resolve(undefined);
				pendingTerminal.operation = operation;
				pendingTerminal.waitForReconciliation = waitForReconciliation;
				pendingTerminal.connectionEpoch = connectionEpoch;
				pendingTerminal.coalesceKey = coalesceKey;
				pendingTerminal.transactional = transactional;
				pendingTerminal.waiters = [waiter];
				promise.catch((error) => this.#reportAgentError(agentId, error, connectionEpoch));
				return promise;
			}
			const ordinaryPending = queue.items.filter((item) => item.terminal !== true && item.transactional !== true).length;
			const transactionalPending = queue.items.filter((item) => item.transactional === true).length;
			const capacity = transactional ? this.#maxPendingAgentTransactions : this.#maxPendingAgentOperations;
			const pending = transactional ? transactionalPending : ordinaryPending;
			if (!terminal && pending >= capacity) {
				const lane = transactional ? 'transactional' : 'ordinary';
				const error = codedRuntimeError('AGENT_EVENT_BACKPRESSURE', `Agent '${agentId}' has ${capacity} pending ${lane} coordinator events`);
				waiter.reject(error);
				promise.catch((caught) => this.#reportAgentError(agentId, caught, connectionEpoch));
				return promise;
			}
			try {
				if (onAdmitted !== null) onAdmitted();
			} catch (error) {
				waiter.reject(error);
				promise.catch((caught) => this.#reportAgentError(agentId, caught, connectionEpoch));
				if (queue.items.length === 0 && this.#agentOperations.get(agentId) === queue) this.#agentOperations.delete(agentId);
				return promise;
			}
			queue.items.push({ operation, waitForReconciliation, connectionEpoch, coalesceKey, terminal, transactional, waiters: [waiter] });
		}
		promise.catch((error) => this.#reportAgentError(agentId, error, connectionEpoch));
		if (queue.drainPromise === null) queue.drainPromise = Promise.resolve().then(() => this.#drainAgentOperations(agentId, queue));
		return promise;
	}

	async #drainAgentOperations(agentId, queue) {
		while (queue.items.length > 0) {
			const item = queue.items.shift();
			try {
				if (this.#isConnectionEpochCurrent(item.connectionEpoch) && item.waitForReconciliation) await this.#reconciliation;
				const value = this.#isConnectionEpochCurrent(item.connectionEpoch) ? await item.operation() : undefined;
				for (const waiter of item.waiters) waiter.resolve(value);
			} catch (error) {
				for (const waiter of item.waiters) waiter.reject(error);
			}
		}
		if (this.#agentOperations.get(agentId) === queue) this.#agentOperations.delete(agentId);
	}

	#reserveAgentOperation(agentId) {
		const agentCount = this.#agentOperationCounts.get(agentId) ?? 0;
		if (this.#totalAgentOperations >= this.#connectionOperationCap) {
			throw new ProtocolV2Error('CONNECTION_INBOUND_BACKPRESSURE', 'Coordinator inbound operation queue is full');
		}
		if (agentCount >= this.#agentOperationCap) {
			throw new ProtocolV2Error('AGENT_INBOUND_BACKPRESSURE', `Coordinator inbound operation queue for agent '${agentId}' is full`);
		}
		this.#totalAgentOperations++;
		this.#agentOperationCounts.set(agentId, agentCount + 1);
		let released = false;
		return () => {
			if (released) return;
			released = true;
			this.#totalAgentOperations = Math.max(0, this.#totalAgentOperations - 1);
			const pending = this.#agentOperationCounts.get(agentId) ?? 0;
			if (pending <= 1) this.#agentOperationCounts.delete(agentId);
			else this.#agentOperationCounts.set(agentId, pending - 1);
		};
	}

	#run(operation, connectionEpoch = this.#connectionEpoch, { requireConnected = true } = {}) {
		return Promise.resolve().then(() => {
			const current = requireConnected
				? this.#isConnectionEpochCurrent(connectionEpoch)
				: connectionEpoch === this.#connectionEpoch;
			return current ? operation() : undefined;
		}).catch((error) => this.emit('runtimeError', error));
	}

	async #sendForEpoch(connectionEpoch, type, agentId, payload) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) {
			throw codedRuntimeError('STALE_CONNECTION_EPOCH', `Bridge connection epoch ${connectionEpoch ?? 'unknown'} is no longer active`);
		}
		return this.#bridge.send(type, agentId, payload, { connectionEpoch });
	}

	async #acknowledgeActionResult(message, connectionEpoch) {
		try {
			if (typeof this.#bridge.acknowledgeActionResult === 'function') {
				await this.#bridge.acknowledgeActionResult(message.agentId, message.payload, { connectionEpoch });
			}
		} catch {
			// The retained Minecraft result is replayed after reconnect when this ack is lost.
		}
	}

	async #sendRuntimeMessage(kind, type, agentId, payload) {
		const epochs = kind === 'native' ? this.#nativeRuntimeEpochs : this.#programRuntimeEpochs;
		const connectionEpoch = epochs.get(agentId);
		if (type === 'action_cancel' && !this.#isConnectionEpochCurrent(connectionEpoch)) return null;
		if (kind === 'program' && type === 'action_command') {
			if (!this.#isConnectionEpochCurrent(connectionEpoch)) throw Object.assign(new Error('Action belongs to an obsolete connection'), { code: 'STALE_SESSION' });
			await this.#playerMemory.recordDispatch(this.#registry.assertCurrentRevision(agentId, payload.goalRevision), payload);
		}
		return this.#sendForEpoch(connectionEpoch, type, agentId, payload);
	}

	async #reportAgentError(agentId, error, connectionEpoch = this.#connectionEpoch) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch)) return;
		try {
			const verboseRecord = this.#registry.get(agentId);
			if (QUIET_LIFECYCLE_ERRORS.has(error?.code)) return;
			if (classifyRecoveryFailure(error).quiet) {
				// App-server transport silence is retried from the next fresh observation.
				// It is not a world-action failure that the player or agent must repair.
				if (verboseRecord !== null) this.#publishVerbose(agentId, verboseRecord.goalRevision, 'retry', 'Provider output was incomplete; retrying from the next fresh observation.', this.#connectionEpoch, {
					component: 'provider', boundary: 'planning', code: String(error?.code ?? 'EMPTY_PROVIDER_TURN'), state: 'retrying',
				});
				const retryAt = safeClockRead(this.#controlNow);
				if (retryAt === null) this.#providerRetryAfter.delete(agentId);
				else this.#providerRetryAfter.set(agentId, retryAt + EMPTY_TURN_RETRY_DELAY_MS);
				return;
			}
			if (verboseRecord !== null) this.#publishVerbose(agentId, verboseRecord.goalRevision, 'error', verboseErrorMessage(error), this.#connectionEpoch, {
				component: 'coordinator', boundary: 'agent_work', code: String(error?.code ?? 'COORDINATOR_ERROR'), state: 'degraded',
			});
			this.#emitRuntimeError(error);
			if (!this.#bridge.ready || !this.#registry.has(agentId)) return;
			const record = this.#registry.get(agentId);
			try {
				await this.#sendForEpoch(connectionEpoch, 'agent_error', agentId, {
					goalRevision: record.goalRevision,
					code: sanitizeDiagnosticErrorCode(error, { fallback: 'COORDINATOR_ERROR' }),
					message: sanitizeDiagnosticErrorMessage(error, { maxBytes: 2_048 }),
				});
			} catch (reportError) {
				if (!QUIET_LIFECYCLE_ERRORS.has(reportError?.code)) this.#emitRuntimeError(reportError);
			}
		} catch (reportFailure) {
			this.#emitRuntimeError(reportFailure);
		}
	}

	#emitRuntimeError(error) {
		try { this.emit('runtimeError', error); }
		catch { /* reporting must never reject agent control work */ }
	}

	#writeTrace(event, fields) {
		if (this.#traceWriter === null) return;
		try {
			Promise.resolve(this.#traceWriter.write(event, fields)).catch(() => {});
			if (typeof this.#traceWriter.writeDiagnostic === 'function') Promise.resolve(this.#traceWriter.writeDiagnostic(event, fields)).catch(() => {});
		} catch { /* diagnostics cannot interrupt agent control */ }
	}

	#verboseReporter(agentId, goalRevision, { allowPublicAgentMessage = false, connectionEpoch = this.#connectionEpoch } = {}) {
		let publishedAgentMessage = false;
		const reporter = (stage, message) => {
			try {
				if (!this.#verboseEnabled) return;
				if (allowPublicAgentMessage && stage === 'agent_message') {
					if (publishedAgentMessage) return;
					const publicMessage = sanitizePublicAgentMessage(message);
					if (publicMessage.length === 0) return;
					publishedAgentMessage = true;
					this.#publishVerbose(agentId, goalRevision, 'decision', publicMessage, connectionEpoch);
					return;
				}
			} catch { /* verbose reporting is observational */ }
		};
		reporter.reset = () => {};
		reporter.dispose = () => {
			this.#verboseReporters.delete(reporter);
		};
		this.#verboseReporters.add(reporter);
		return reporter;
	}

	#setVerboseEnabled(enabled) {
		this.#verboseEnabled = enabled;
		if (enabled) return;
		this.#verboseTransitions.clearAll();
		for (const reporter of this.#verboseReporters) reporter.reset();
	}

	#publishVerbose(agentId, goalRevision, stage, message, connectionEpoch = this.#connectionEpoch, transition = {}) {
		try {
			const bounded = sanitizeVerboseMessage(stage, message);
			if (bounded.length === 0) return;
			const identity = verboseTransitionIdentity(stage, bounded, transition);
			if (!this.#verboseTransitions.accept({ agentId, goalRevision, ...identity })) return;
			this.#sendVerbose(agentId, goalRevision, stage, bounded, connectionEpoch);
		} catch { /* verbose delivery is best effort */ }
	}

	#sendVerbose(agentId, goalRevision, stage, message, connectionEpoch) {
		if (!this.#verboseEnabled || !this.#isConnectionEpochCurrent(connectionEpoch) || !this.#bridge.ready || !VERBOSE_STAGES.includes(stage)) return;
		const current = this.#registry.get(agentId);
		if (current === null || current.goalRevision !== goalRevision || message.length === 0 || message.length > MAX_VERBOSE_MESSAGE_LENGTH) return;
		Promise.resolve(this.#sendForEpoch(connectionEpoch, 'verbose_event', agentId, { goalRevision, stage, message })).catch(() => {});
	}

	async #publishCatalog(snapshot, connectionEpoch = this.#connectionEpoch) {
		await this.#sendForEpoch(connectionEpoch, 'catalog_snapshot', 'server', {
			refreshedAtEpochMs: snapshot.refreshedAtEpochMs,
			models: snapshot.models,
		});
	}

	async #publishGoalCompleted({ record, goalFingerprint, traceId }, connectionEpoch = this.#connectionEpoch) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch) || !this.#bridge.ready) throw codedRuntimeError('BRIDGE_NOT_READY', 'Minecraft bridge is not ready for completion verification');
		if (!this.#supportedAgentIds.has(record.agentId)) throw codedRuntimeError('AGENT_NOT_SUPPORTED', `Agent '${record.agentId}' is not in the reconciled bridge roster`);
		const profile = {
			provider: record.provider,
			model: record.model,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? DEFAULT_SERVICE_TIER,
		};
		try {
			await this.#sendForEpoch(connectionEpoch, 'goal_completed', record.agentId, {
				goalRevision: record.goalRevision,
				goalFingerprint,
				traceId,
				profile,
			});
		} catch (error) {
			if (isTransientCompletionSendError(error)) throw error;
			throw codedRuntimeError('COMPLETION_SEND_FAILED', 'Minecraft bridge rejected completion verification', error);
		}
	}

	async #publishStatus(connectionEpoch = this.#connectionEpoch) {
		if (!this.#isConnectionEpochCurrent(connectionEpoch) || !this.#bridge.ready) return;
		const records = this.#registry.list();
		const readyStates = new Set([DynamicAgentState.IDLE, DynamicAgentState.STARTING, DynamicAgentState.PLANNING, DynamicAgentState.ACTING, DynamicAgentState.PAUSED, DynamicAgentState.COMPLETED]);
		const profiles = records.filter((record) => this.#supportedAgentIds.has(record.agentId));
		const healthIdentities = [...new Map(profiles.flatMap((profile) => ['create_agent', 'decide'].map((operation) => ({
			provider: profile.provider,
			model: profile.model,
			operation,
		}))).map((identity) => [JSON.stringify([identity.provider, identity.model, identity.operation]), identity])).values()]
			.sort((left, right) => left.provider.localeCompare(right.provider)
			|| left.model.localeCompare(right.model) || left.operation.localeCompare(right.operation));
		const pressure = this.#scheduler.pressureSnapshot;
		let providerRecovery = [];
		try { providerRecovery = this.#codexService.recoverySnapshot?.() ?? []; }
		catch { /* optional status must not affect coordinator control */ }
		const components = providerRecoveryComponents(providerRecovery);
		try {
			const diagnostics = this.#traceWriter?.statusSnapshot?.();
			if (diagnostics !== null && diagnostics !== undefined) components.push(diagnostics);
		} catch { /* optional status must not affect coordinator control */ }
		await this.#sendForEpoch(connectionEpoch, 'coordinator_status', 'server', buildCoordinatorStatus({
			reconciled: this.#reconciledStatus,
			records,
			supportedAgentIds: this.#supportedAgentIds,
			readyStates,
			pressure: {
				active: pressure.active,
				pending: pressure.pending,
				maxConcurrent: pressure.maxConcurrent,
				maxPending: pressure.maxPending,
				warning: pressure.warning,
				mode: pressure.mode,
				configuredTarget: pressure.configuredTarget,
				target: pressure.target,
				minConcurrency: pressure.minConcurrency,
				maxConcurrency: pressure.maxConcurrency,
				urgentReserve: pressure.urgentReserve,
				ordinaryActiveLimit: pressure.ordinaryActiveLimit,
				activeOrdinary: pressure.activeOrdinary,
				activeUrgent: pressure.activeUrgent,
				pendingOrdinary: pressure.pendingOrdinary,
				pendingUrgent: pressure.pendingUrgent,
				growthCount: pressure.growthCount,
				backoffCount: pressure.backoffCount,
				lastChangeReason: pressure.lastChangeReason,
				healthyCompletions: pressure.healthyCompletions,
				ordinaryReservationRejections: pressure.ordinaryReservationRejections,
				urgentReservationRejections: pressure.urgentReservationRejections,
			},
			healthSnapshots: healthIdentities.slice(0, 32).map((identity) => this.#healthRegistry.snapshot(identity)),
			latencies: this.#latencyRegistry.snapshot(),
			bridgeSessionEpoch: connectionEpoch,
			runtimeGeneration: this.#runtimeGeneration,
			components,
		}));
	}

	#ledger(agentId) {
		let ledger = this.#factLedgers.get(agentId);
		if (ledger === undefined) {
			ledger = new FactLedger();
			this.#factLedgers.set(agentId, ledger);
		}
		return ledger;
	}

	#plannerContext(agentId) {
		const ledger = this.#ledger(agentId);
		const memory = this.#conversationMemory(agentId);
		const cursor = this.#contextCursors.get(agentId);
		const record = this.#registry.get(agentId);
		if (cursor === undefined || record === null || this.#serverInstanceId === null) {
			return {
				untrustedFacts: ledger.toPlannerFacts(),
				conversationContext: memory.toPlannerContext(),
			};
		}
		const binding = this.#contextBinding(record);
		return {
			factLedger: ledger,
			conversationMemory: memory,
			contextCursor: cursor,
			contextBinding: binding,
			cursorBinding: cursor,
		};
	}

	#contextBinding(record) {
		const session = typeof this.#codexService.getAgent === 'function'
			? this.#codexService.getAgent(record.agentId)
			: null;
		let metadata = null;
		try { metadata = session?.sessionMetadata?.() ?? null; } catch { metadata = null; }
		const selectedProfile = {
			agentId: record.agentId,
			provider: record.provider,
			model: record.model,
			reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? DEFAULT_SERVICE_TIER,
		};
		const sessionFingerprint = metadata?.profileFingerprint ?? session?.profileFingerprint;
		const resolvedFingerprint = typeof sessionFingerprint === 'string' && /^sha256:[0-9a-f]{64}$/.test(sessionFingerprint)
			? sessionFingerprint
			: profileFingerprint(selectedProfile);
		const sessionGeneration = metadata?.sessionGeneration ?? session?.sessionGeneration;
		return {
			agentId: record.agentId,
			profileFingerprint: resolvedFingerprint,
			sessionGeneration: Number.isSafeInteger(sessionGeneration) && sessionGeneration >= 1 ? sessionGeneration : 1,
			goalRevision: record.goalRevision,
			serverInstanceId: this.#serverInstanceId,
		};
	}

	#contextSnapshot(record) {
		return {
			factRevision: this.#ledger(record.agentId).delta(null).nextRevision,
			conversationSequence: this.#conversationMemory(record.agentId).delta(null).nextSequence,
		};
	}

	#rememberAcceptedContextCursor(record, snapshot = null) {
		if (this.#serverInstanceId === null) return;
		const binding = this.#contextBinding(record);
		const revisions = snapshot ?? this.#contextSnapshot(record);
		const value = {
			...binding,
			factRevision: revisions.factRevision,
			conversationSequence: revisions.conversationSequence,
			providerAccepted: true,
		};
		const previous = this.#contextCursors.get(record.agentId);
		try {
			this.#contextCursors.set(record.agentId, previous === undefined ? createContextCursor(value) : advanceContextCursor(previous, value));
		} catch {
			// A replacement provider session starts from a full current baseline.
			this.#contextCursors.set(record.agentId, createContextCursor(value));
		}
	}

	#invalidateServerInstance(connectionEpoch) {
		this.#cancelGoalSpecRequests();
		this.#healthRegistry.reset();
		this.#factLedgers.clear();
		this.#conversationMemories.clear();
		this.#contextCursors.clear();
		this.#nativeConversationSequences.clear();
		this.#nativeConversationRecoveries.clear();
		this.#nativeObservationSignatures.clear();
		this.#nativeWorldSignals.clear();
		this.#supervisedObservationRequests.clear();
		this.#conversationWakeTransactions.clear();
		this.#providerWork.clear();
		this.#providerProbeDeadlines.clear();
		this.#deferredProviderRecovery.clear();
		this.#programRuntimeEpochs.clear();
		this.#nativeRuntimeEpochs.clear();
		for (const record of this.#registry.list()) {
			this.#goalSupervisor.terminate(this.#supervisionKey(record));
			this.#advanceLifecycleGeneration(record.agentId);
			this.#programRuntime.dispose(record.agentId);
			void this.#nativeRuntime.dispose(record.agentId, 'server_replaced');
			this.#pendingAttention.delete(record.agentId);
			this.#attentionFlushes.delete(record.agentId);
			this.#providerRetryAfter.delete(record.agentId);
			try {
				Promise.resolve(this.#planner.interrupt(record.agentId, 'Minecraft server instance changed'))
					.catch((error) => this.#reportAgentError(record.agentId, error, connectionEpoch));
			} catch (error) {
				void this.#reportAgentError(record.agentId, error, connectionEpoch);
			}
		}
	}

	#goalSpecRequestKey(agentId, requestId) {
		return `${this.#serverInstanceId ?? 'disconnected'}\u0000${agentId}\u0000${requestId}`;
	}

	async #processGoalSpecRequest(key, entry) {
		if (this.#goalSpecRequests.get(key) !== entry || this.#serverInstanceId === null
				|| !this.#isConnectionEpochCurrent(entry.connectionEpoch) || entry.translating) return;
		if (entry.retryHandle !== null) {
			this.#clearGoalSpecTimeout(entry.retryHandle);
			entry.retryHandle = null;
		}
		if (entry.proposal !== null) {
			try {
				await this.#sendForEpoch(entry.connectionEpoch, 'goal_spec_proposal', entry.agentId, entry.proposal);
			} catch (error) {
				if (this.#goalSpecRequests.get(key) === entry) this.#emitRuntimeError(error);
			}
			if (this.#goalSpecRequests.get(key) === entry) this.#scheduleGoalSpecRequest(key, entry, GOAL_SPEC_PROPOSAL_RETRY_MS);
			return;
		}
		entry.translating = true;
		try {
			await this.#reconciliation;
			if (this.#goalSpecRequests.get(key) !== entry || this.#serverInstanceId === null
					|| !this.#isConnectionEpochCurrent(entry.connectionEpoch)) return;
			entry.proposal = await this.#planner.requestGoalSpec({
				agentId: entry.agentId,
				request: entry.request,
				...(entry.correctiveFeedback === null ? {} : { correctiveFeedback: entry.correctiveFeedback }),
			});
			entry.attempts = 0;
		} catch (error) {
			if (this.#goalSpecRequests.get(key) !== entry) return;
			entry.attempts += 1;
			this.#emitRuntimeError(error);
			const delay = Math.min(GOAL_SPEC_RETRY_MAX_MS, GOAL_SPEC_RETRY_BASE_MS * (2 ** Math.min(entry.attempts - 1, 5)));
			this.#scheduleGoalSpecRequest(key, entry, delay);
			return;
		} finally {
			entry.translating = false;
		}
		if (this.#goalSpecRequests.get(key) === entry) await this.#processGoalSpecRequest(key, entry);
	}

	#scheduleGoalSpecRequest(key, entry, delayMs) {
		if (entry.retryHandle !== null) this.#clearGoalSpecTimeout(entry.retryHandle);
		entry.retryHandle = this.#setGoalSpecTimeout(() => {
			entry.retryHandle = null;
			this.#run(() => this.#processGoalSpecRequest(key, entry), entry.connectionEpoch);
		}, delayMs);
	}

	#forgetGoalSpecRequest(key, entry) {
		if (this.#goalSpecRequests.get(key) !== entry) return;
		this.#goalSpecRequests.delete(key);
		if (entry.retryHandle !== null) this.#clearGoalSpecTimeout(entry.retryHandle);
		try { this.#planner.cancelGoalSpec?.(entry.agentId, entry.requestId); } catch { /* cancellation is best effort */ }
		entry.releaseCapacity();
		entry.resolveCompletion();
	}

	#cancelGoalSpecRequests(agentId = null) {
		for (const [key, request] of this.#goalSpecRequests) {
			if (agentId !== null && request.agentId !== agentId) continue;
			this.#goalSpecRequests.delete(key);
			if (request.retryHandle !== null) this.#clearGoalSpecTimeout(request.retryHandle);
			try { this.#planner.cancelGoalSpec?.(request.agentId, request.requestId); } catch { /* cancellation is best effort */ }
			request.releaseCapacity();
			request.resolveCompletion();
		}
	}

	#conversationMemory(agentId) {
		let memory = this.#conversationMemories.get(agentId);
		if (memory === undefined) {
			memory = new ConversationMemory();
			this.#conversationMemories.set(agentId, memory);
		}
		return memory;
	}

	#nativeTurnInput(record, request) {
		if (request.nativeEvent === undefined) return request.input;
		const memory = this.#conversationMemory(record.agentId);
		const afterSequence = this.#nativeConversationSequences.get(record.agentId) ?? -1;
		const conversation = memory.unread(afterSequence);
		request.nativeConversationDelivery = { afterSequence, nextSequence: conversation.nextSequence };
		this.#nativeConversationSequences.set(record.agentId, conversation.nextSequence);
		const input = buildNativeEventInput(record, {
			...request.nativeEvent,
			observation: this.#nativeRuntime.decorateObservation(record, request.nativeEvent.observation ?? {}),
			conversation,
		});
		return request.retryInstruction === undefined ? input : `${input}\n${request.retryInstruction}`;
	}

	#restoreNativeConversation(request) {
		const delivery = request?.nativeConversationDelivery;
		if (delivery === undefined) return;
		const current = this.#nativeConversationSequences.get(request.agentId) ?? -1;
		if (current === delivery.nextSequence) this.#nativeConversationSequences.set(request.agentId, delivery.afterSequence);
		delete request.nativeConversationDelivery;
	}

	#forgetNativeConversationRecovery(supervisionKey) {
		const recovery = this.#nativeConversationRecoveries.get(supervisionKey.agentId);
		if (recovery !== undefined && sameSupervisionKey(recovery.supervisionKey, supervisionKey)) {
			this.#nativeConversationRecoveries.delete(supervisionKey.agentId);
		}
	}

	#rememberConversationWake(transactionId, agentId, fingerprint) {
		this.#forgetConversationWakes(agentId);
		this.#conversationWakeTransactions.set(transactionId, { agentId, fingerprint });
		while (this.#conversationWakeTransactions.size > MAX_CONVERSATION_WAKE_TRANSACTIONS) {
			this.#conversationWakeTransactions.delete(this.#conversationWakeTransactions.keys().next().value);
		}
	}

	#forgetConversationWakes(agentId) {
		for (const [transactionId, transaction] of this.#conversationWakeTransactions) {
			if (transaction.agentId === agentId) this.#conversationWakeTransactions.delete(transactionId);
		}
	}

}

export function createDynamicCoordinator(configValue, dependencies = {}) {
	const providerTurnRecorder = dependencies.providerTurnRecorder ?? null;
	const coordinatorEnvironment = dependencies.env ?? process.env;
	const config = normalizeDynamicConfig(configValue, coordinatorEnvironment);
	const providerEnvironments = Object.fromEntries(PROVIDER_IDS.map((provider) => [
		provider,
		createProviderChildEnvironment(provider, coordinatorEnvironment, config.bridge.secretEnvironmentVariable),
	]));
	const registry = dependencies.registry ?? new AgentRegistry({
		agentCap: config.limits.agentCap,
		queueCap: config.limits.goalQueueCap,
		now: dependencies.now ?? Date.now,
	});
	const scheduler = dependencies.scheduler ?? new PlanningScheduler({
		maxConcurrent: config.limits.planningConcurrency,
		maxPending: Math.max(0, config.limits.agentCap - config.limits.planningConcurrency),
		planningMode: config.limits.planningMode,
		minConcurrency: 4,
		maxConcurrency: config.limits.agentCap,
		urgentReserve: config.limits.urgentReserve,
		onPressure: (snapshot) => dependencies.onSchedulerPressure?.(snapshot),
		benchmarkRecorder: dependencies.benchmarkRecorder,
	});
	const workspaceManager = dependencies.workspaceManager ?? new AgentWorkspaceManager(config.workspaceRoot);
	const minecraftWorkspace = dependencies.minecraftWorkspace ?? new MinecraftAgentWorkspace({
		root: config.minecraftAgentRoot,
		templateRoot: config.minecraftAgentTemplateRoot,
	});
	const codexService = dependencies.providerService ?? dependencies.codexService ?? new ProviderService({
		codex: new CodexService({ ...config.codex, environment: providerEnvironments.codex, bridgeSecretEnvironmentVariable: config.bridge.secretEnvironmentVariable }, { transport: dependencies.codexTransport, now: dependencies.now ?? Date.now, workspaceManager, minecraftWorkspace }),
		gemini: new AntigravityProviderService({ ...config.gemini, environment: providerEnvironments.gemini, bridgeSecretEnvironmentVariable: config.bridge.secretEnvironmentVariable }, {
			spawn: dependencies.antigravitySpawn,
			terminate: dependencies.terminateProviderProcess,
			platform: dependencies.platform,
			workspaceManager,
		}),
		kimi: new AcpProviderService({ ...config.kimi, environment: providerEnvironments.kimi, bridgeSecretEnvironmentVariable: config.bridge.secretEnvironmentVariable }, { transportFactory: dependencies.kimiTransportFactory, workspaceManager }),
		cursor: new CursorProviderService({ ...config.cursor, environment: providerEnvironments.cursor, bridgeSecretEnvironmentVariable: config.bridge.secretEnvironmentVariable }, {
			spawn: dependencies.cursorSpawn,
			terminate: dependencies.terminateProviderProcess,
			platform: dependencies.platform,
			workspaceManager,
		}),
	}, { turnRecorder: providerTurnRecorder, now: dependencies.epochNow ?? Date.now });
	const healthRegistry = dependencies.healthRegistry ?? dependencies.planner?.healthRegistry ?? new ProviderHealthRegistry({ now: dependencies.healthNow ?? Date.now });
	const latencyRegistry = dependencies.latencyRegistry ?? new ControlLatencyRegistry();
	const planner = dependencies.planner ?? new AgentPlanner({
		registry,
		scheduler,
		codexService,
		invalidDecisionRetries: config.limits.invalidDecisionRetries,
		healthRegistry,
		latencyRegistry,
		now: dependencies.plannerNow ?? dependencies.now,
		telemetrySink: dependencies.telemetrySink,
		benchmarkRecorder: dependencies.benchmarkRecorder,
		turnRecorder: providerTurnRecorder,
	});
	const bridge = dependencies.bridge ?? new MultiplexedServerBridge(config.bridge, {
		audit: dependencies.protocolAudit,
		socketFactory: dependencies.socketFactory,
		schedule: dependencies.schedule,
		cancelSchedule: dependencies.cancelSchedule,
		scheduleDeadline: dependencies.scheduleDeadline,
		cancelDeadline: dependencies.cancelDeadline,
		currentRevision: (agentId) => registry.get(agentId)?.goalRevision ?? null,
	});
	let coordinator = null;
	const goalSupervisor = dependencies.goalSupervisor ?? new ActiveGoalSupervisor({
		requestObservation: (key, reason) => coordinator?.requestSupervisedObservation(key, reason) ?? false,
		clock: dependencies.goalClock ?? dependencies.controlNow ?? Date.now,
		schedule: dependencies.goalSchedule,
		cancelSchedule: dependencies.cancelGoalSchedule,
		stuckSchedule: dependencies.goalStuckSchedule,
		cancelStuckSchedule: dependencies.cancelGoalStuckSchedule,
		onExpire: (event) => coordinator?.handleLeaseExpired(event),
		onStuck: (event) => coordinator?.handleGoalStuck(event),
	});
	coordinator = new DynamicCoordinator({
		registry,
		scheduler,
		codexService,
		planner,
		bridge,
		healthRegistry,
		latencyRegistry,
		goalSupervisor,
		codexControlProtocol: config.codex.controlProtocol,
		memoryDirectory: Object.hasOwn(dependencies, 'memoryDirectory') ? dependencies.memoryDirectory : path.join(config.workspaceRoot, 'player-memory'),
		runtimeSessionId: dependencies.runtimeSessionId,
		traceWriter: dependencies.traceWriter,
		providerTurnRecorder,
		runtimeGeneration: dependencies.runtimeGeneration,
		runtimeHooks: dependencies.runtimeHooks,
		controlNow: dependencies.controlNow,
		epochNow: dependencies.epochNow,
		setStatusInterval: dependencies.setStatusInterval,
		clearStatusInterval: dependencies.clearStatusInterval,
		setGoalSpecTimeout: dependencies.setGoalSpecTimeout,
		clearGoalSpecTimeout: dependencies.clearGoalSpecTimeout,
		maxPendingAgentOperations: dependencies.maxPendingAgentOperations,
		maxPendingAgentTransactions: dependencies.maxPendingAgentTransactions,
		connectionOperationCap: dependencies.connectionOperationCap,
		agentOperationCap: dependencies.agentOperationCap,
		goalSpecRequestCap: dependencies.goalSpecRequestCap,
		benchmarkRecorder: dependencies.benchmarkRecorder,
	});
	return coordinator;
}

function defaultGoalSpecTimeout(callback, delayMs) {
	const handle = setTimeout(callback, delayMs);
	handle.unref?.();
	return handle;
}

export function parseDynamicCliArguments(args) {
	if (!Array.isArray(args)) throw new TypeError('CLI arguments must be an array');
	if (args.length === 0) return { configPath: DEFAULT_DYNAMIC_CONFIG_PATH };
	if (args.length !== 2 || args[0] !== '--config') throw new Error('Usage: node coordinator/src/dynamic-main.mjs [--config <absolute-path>]');
	if (!path.isAbsolute(args[1])) throw new Error('--config must be an absolute path');
	return { configPath: args[1] };
}

export function resolveDynamicCliRuntime(environment = process.env) {
	if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) throw new TypeError('runtime environment must be an object');
	const tracePath = environment.ARENA_HEADLESS_TRACE_PATH ?? path.join(PROJECT_DIRECTORY, 'runtime', 'traces', 'coordinator.jsonl');
	const separator = tracePath.includes('\\') ? '\\' : '/';
	const traceDirectory = tracePath.slice(0, Math.max(0, tracePath.lastIndexOf(separator)));
	return {
		tracePath,
		diagnosticTracePath: environment.ARENA_HEADLESS_PRIVATE_TRACE_PATH ?? `${traceDirectory}${separator}coordinator-private.jsonl`,
		protocolAuditPath: environment.ARENA_PROTOCOL_AUDIT_PATH ?? null,
		providerTurnsPath: environment.ARENA_PROVIDER_TURNS_PATH ?? null,
		runtimeGeneration: /^[0-9a-f]{64}$/.test(environment.ARENA_AGENT_COORDINATOR_RUNTIME_GENERATION ?? '')
			? environment.ARENA_AGENT_COORDINATOR_RUNTIME_GENERATION
			: null,
		runId: environment.ARENA_HEADLESS_RUN_ID ?? 'dynamic-run',
		scenarioId: environment.ARENA_HEADLESS_SCENARIO_ID ?? 'dynamic',
	};
}

export async function loadDynamicConfig(configPath = DEFAULT_DYNAMIC_CONFIG_PATH) {
	const document = JSON.parse(await readFile(configPath, 'utf8'));
	return normalizeDynamicConfig(document, process.env);
}

export function normalizeDynamicConfig(value, environment = process.env) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('dynamic coordinator config must be an object');
	value = migrateDynamicConfig(value);
	assertKnownConfigKeys(value, ['schemaVersion', 'bridge', 'voice', 'codex', 'gemini', 'kimi', 'cursor', 'limits', 'workspaceRoot', 'minecraftAgentRoot', 'minecraftAgentTemplateRoot'], 'config');
	if (value.bridge === null || typeof value.bridge !== 'object' || Array.isArray(value.bridge)) throw new TypeError('dynamic coordinator bridge config must be an object');
	if (value.codex === null || typeof value.codex !== 'object' || Array.isArray(value.codex)) throw new TypeError('dynamic coordinator Codex config must be an object');
	if (value.voice !== undefined && (value.voice === null || typeof value.voice !== 'object' || Array.isArray(value.voice))) throw new TypeError('dynamic coordinator voice config must be an object');
	assertOptionalConfigObject(value.limits, 'limits');
	assertOptionalConfigObject(value.gemini, 'gemini');
	assertOptionalConfigObject(value.kimi, 'kimi');
	assertOptionalConfigObject(value.cursor, 'cursor');
	assertKnownConfigKeys(value.bridge, ['host', 'port', 'secret', 'secretEnvironmentVariable', 'reconnectDelayMs', 'maxReconnectDelayMs', 'connectionQueueCap', 'agentQueueCap', 'inboundConnectionQueueCap', 'inboundAgentQueueCap', 'inboundDispatchBatch', 'trackedTerminalActionIdCap', 'handshakeTimeoutMs', 'heartbeatIntervalMs', 'heartbeatTimeoutMs', 'serverInstanceId', 'launchId'], 'bridge');
	assertKnownConfigKeys(value.voice ?? {}, ['port', 'maxConcurrent', 'profileAssignmentsPath', 'fishApiKeyEnvironmentVariable', 'deepgramApiKeyEnvironmentVariable', 'localSpeechTimeoutMs', 'localSpeechPythonPath', 'secret', 'secretFile'], 'voice');
	assertKnownConfigKeys(value.codex, ['cwd', 'controlProtocol', 'planningTimeoutMs', 'maxDecisionBytes', 'catalogTtlMs', 'startupTimeoutMs', 'serviceTier', 'launchProfile'], 'codex');
	if (value.codex.launchProfile !== undefined) {
		assertOptionalConfigObject(value.codex.launchProfile, 'codex.launchProfile');
		assertKnownConfigKeys(value.codex.launchProfile, ['agentId', 'model', 'reasoningEffort', 'serviceTier', 'planningTimeoutMs', 'maxDecisionBytes', 'cwd'], 'codex.launchProfile');
	}
	const providerKeys = ['provider', 'cwd', 'executable', 'models', 'reasoningEfforts', 'modelReasoningEfforts', 'catalogDiscovery', 'catalogDiscoveryTimeoutMs', 'planningTimeoutMs', 'maxDecisionBytes', 'stdoutLimitBytes', 'stderrLimitBytes', 'serviceTier'];
	for (const provider of ['gemini', 'kimi', 'cursor']) assertKnownConfigKeys(value[provider] ?? {}, providerKeys, provider);
	assertKnownConfigKeys(value.limits ?? {}, ['agentCap', 'goalQueueCap', 'planningConcurrency', 'planningMode', 'urgentReserve', 'invalidDecisionRetries'], 'limits');
	const secret = value.bridge.secret ?? environment[value.bridge.secretEnvironmentVariable ?? 'ARENA_AGENT_BRIDGE_SECRET'];
	const cwd = value.codex.cwd ?? PROJECT_DIRECTORY;
	const workspaceRoot = value.workspaceRoot === undefined
		? path.join(PROJECT_DIRECTORY, 'runtime', 'agent-workspaces')
		: path.resolve(PROJECT_DIRECTORY, value.workspaceRoot);
	const minecraftAgentRoot = value.minecraftAgentRoot === undefined
		? path.join(PROJECT_DIRECTORY, 'runtime', 'minecraft-agent')
		: path.resolve(PROJECT_DIRECTORY, value.minecraftAgentRoot);
	const agentCap = positiveInteger(value.limits?.agentCap ?? DEFAULT_AGENT_CAP, 'limits.agentCap');
	const planningConcurrency = positiveInteger(value.limits?.planningConcurrency ?? DEFAULT_PLANNING_CONCURRENCY, 'limits.planningConcurrency');
	const planningMode = value.limits?.planningMode ?? 'fixed';
	if (planningMode !== 'fixed' && planningMode !== 'adaptive') throw new TypeError("limits.planningMode must be 'fixed' or 'adaptive'");
	if (agentCap > 16) throw new TypeError('limits.agentCap must not exceed 16');
	if (planningMode === 'adaptive' && (agentCap < 4 || planningConcurrency < 4 || planningConcurrency > 16 || planningConcurrency > agentCap)) {
		throw new TypeError('adaptive planningConcurrency and agentCap must be in [4, 16]');
	}
	if (planningConcurrency > agentCap) throw new TypeError('limits.planningConcurrency must not exceed limits.agentCap');
	const urgentReserve = value.limits?.urgentReserve ?? (agentCap >= 4 ? 1 : 0);
	if (!Number.isSafeInteger(urgentReserve) || urgentReserve < 0 || urgentReserve > agentCap) throw new TypeError('limits.urgentReserve must be a non-negative safe integer within limits.agentCap');
	const voice = normalizeVoiceConfig(value.voice, environment);
	const codexControlProtocol = value.codex.controlProtocol ?? 'native_tools';
	if (!['arena_script', 'native_tools'].includes(codexControlProtocol)) throw new TypeError('codex.controlProtocol must be arena_script or native_tools');
	return {
		schemaVersion: 1,
		bridge: { ...value.bridge, secret },
		workspaceRoot,
		minecraftAgentRoot,
		minecraftAgentTemplateRoot: path.join(COORDINATOR_DIRECTORY, 'config', 'minecraft-agent'),
		voice,
		codex: {
			...value.codex,
			cwd,
			controlProtocol: codexControlProtocol,
			launchProfile: value.codex.launchProfile === undefined ? undefined : { ...value.codex.launchProfile, cwd },
		},
		gemini: {
			provider: 'gemini',
			cwd,
			catalogDiscovery: true,
			models: ['gemini-3.7-flash', 'gemini-3.1-pro', 'gemini-3.6-flash', 'gemini-3.5-flash'],
			modelReasoningEfforts: {
				'gemini-3.7-flash': ['high', 'medium', 'low'],
				'gemini-3.1-pro': ['high', 'low'],
				'gemini-3.6-flash': ['high', 'medium', 'low'],
				'gemini-3.5-flash': ['high', 'medium', 'low'],
			},
			...(value.gemini ?? {}),
		},
		kimi: {
			provider: 'kimi',
			cwd,
			catalogDiscovery: true,
			models: ['kimi-code/k3', 'kimi-code/k3-256k', 'kimi-code/kimi-for-coding', 'kimi-code/kimi-for-coding-highspeed'],
			reasoningEfforts: ['low', 'high', 'max'],
			...(value.kimi ?? {}),
		},
		cursor: {
			provider: 'cursor',
			cwd,
			executable: process.platform === 'win32' && typeof environment.LOCALAPPDATA === 'string' && environment.LOCALAPPDATA.trim() !== ''
				? path.join(environment.LOCALAPPDATA, 'cursor-agent', 'agent.ps1')
				: 'agent',
			catalogDiscovery: true,
			models: ['composer-2.5', 'grok-4.5', 'grok-4.6'],
			modelReasoningEfforts: {
				'composer-2.5': ['high'],
				'grok-4.5': ['low', 'medium', 'high'],
				'grok-4.6': ['low', 'medium', 'high', 'xhigh'],
			},
			...(value.cursor ?? {}),
		},
		limits: {
			agentCap,
			goalQueueCap: positiveInteger(value.limits?.goalQueueCap ?? DEFAULT_GOAL_QUEUE_CAP, 'limits.goalQueueCap'),
			planningConcurrency,
			planningMode,
			urgentReserve,
			invalidDecisionRetries: nonNegativeInteger(value.limits?.invalidDecisionRetries ?? DEFAULT_INVALID_DECISION_RETRIES, 'limits.invalidDecisionRetries'),
		},
	};
}

function migrateDynamicConfig(value) {
	const schemaVersion = value.schemaVersion ?? 0;
	if (!Number.isSafeInteger(schemaVersion) || schemaVersion < 0) throw new TypeError('config.schemaVersion must be a nonnegative safe integer');
	if (schemaVersion > 1) throw new TypeError(`Unsupported dynamic coordinator config schemaVersion ${schemaVersion}`);
	if (schemaVersion === 1) return value;
	const cursor = value.cursor === undefined || value.cursor === null || typeof value.cursor !== 'object' || Array.isArray(value.cursor)
		? value.cursor
		: Object.fromEntries(Object.entries(value.cursor).filter(([key]) => key !== 'serviceTiers'));
	return { ...value, schemaVersion: 1, ...(cursor === undefined ? {} : { cursor }) };
}

function assertOptionalConfigObject(value, field) {
	if (value !== undefined && (value === null || typeof value !== 'object' || Array.isArray(value))) {
		throw new TypeError(`dynamic coordinator ${field} config must be an object`);
	}
}

function assertKnownConfigKeys(value, allowed, field) {
	for (const key of Object.keys(value)) {
		if (!allowed.includes(key)) throw new TypeError(`Unknown dynamic coordinator config key '${field}.${key}'`);
	}
}

async function runCli(reporter = new RuntimeErrorReporter()) {
	const { configPath } = parseDynamicCliArguments(process.argv.slice(2));
	const config = await loadDynamicConfig(configPath);
	const runtime = resolveDynamicCliRuntime(process.env);
	const traceWriter = new TraceWriter(runtime.tracePath, { diagnosticFilePath: runtime.diagnosticTracePath });
	const protocolAudit = runtime.protocolAuditPath === null ? null : createJsonlAudit(runtime.protocolAuditPath, { runId: runtime.runId, scenarioId: runtime.scenarioId });
	if (runtime.providerTurnsPath !== null) await mkdir(path.dirname(path.resolve(runtime.providerTurnsPath)), { recursive: true });
	const providerTurnRecorder = runtime.providerTurnsPath === null ? null : new ProviderTurnRecorder({
		runId: runtime.runId,
		scenarioId: runtime.scenarioId,
		privatePath: runtime.providerTurnsPath,
	});
	const voiceSupervisor = createVoiceSupervisor(config, process.env);
	const coordinator = createDynamicCoordinator(config, {
		traceWriter, protocolAudit, providerTurnRecorder, runtimeGeneration: runtime.runtimeGeneration,
		runtimeHooks: { onRemoved: (agentId) => voiceSupervisor.removeAgent(agentId) },
	});
	const disposeDiagnostics = wireRuntimeDiagnostics(coordinator, reporter);
	try {
		await startCoordinatorControl(coordinator, voiceSupervisor);
	} catch (error) {
		await Promise.allSettled([coordinator.stop(), voiceSupervisor.close(), protocolAudit?.close()]);
		disposeDiagnostics();
		throw error;
	}
	let shutdownPromise = null;
	const shutdown = async () => {
		shutdownPromise ??= Promise.allSettled([coordinator.stop(), voiceSupervisor.close(), protocolAudit?.close()])
			.finally(disposeDiagnostics);
		await shutdownPromise;
		process.exitCode = 0;
	};
	process.once('SIGINT', shutdown);
	process.once('SIGTERM', shutdown);
}

export async function startCoordinatorControl(coordinator, voiceSupervisor) {
	if (coordinator === null || typeof coordinator?.start !== 'function') {
		throw new TypeError('coordinator.start is required');
	}
	if (voiceSupervisor === null || typeof voiceSupervisor?.start !== 'function'
			|| typeof voiceSupervisor?.close !== 'function') {
		throw new TypeError('voiceSupervisor.start and voiceSupervisor.close are required');
	}
	if (typeof coordinator.once === 'function') {
		coordinator.once('shutdown', () => { void Promise.resolve(voiceSupervisor.close()).catch(() => {}); });
	}
	await coordinator.start();
	try { Promise.resolve(voiceSupervisor.start()).catch(() => {}); }
	catch { /* optional voice startup cannot reject coordinator control */ }
}

export function createVoiceSupervisor(config, environment = process.env, dependencies = {}) {
	if (config === null || typeof config !== 'object' || Array.isArray(config)) {
		throw new TypeError('voice supervisor config must be an object');
	}
	if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) {
		throw new TypeError('voice supervisor environment must be an object');
	}
	const localSpeechTimeoutMs = config.voice?.localSpeechTimeoutMs ?? DEFAULT_LOCAL_SPEECH_TIMEOUT_MS;
	if (!Number.isSafeInteger(localSpeechTimeoutMs) || localSpeechTimeoutMs < 1 || localSpeechTimeoutMs > 600_000) {
		throw new TypeError('voice.localSpeechTimeoutMs must be between 1 and 600000');
	}
	const reportVoiceDiagnostic = dependencies.reportVoiceDiagnostic ?? writeVoiceDiagnostic;
	if (typeof reportVoiceDiagnostic !== 'function') throw new TypeError('reportVoiceDiagnostic must be a function');
	const startWorker = dependencies.startWorker
		?? (({ signal }) => startVoiceWorker(config, environment, { signal, reportVoiceDiagnostic }));
	const reportFailure = dependencies.reportFailure ?? (({ failureCode }) => {
		process.stderr.write(`[voice-supervisor] ${failureCode}: proximity speech unavailable; retrying automatically\n`);
	});
	return new VoiceSupervisor({
		startWorker,
		warmupTimeoutMs: Math.min(Number.MAX_SAFE_INTEGER, localSpeechTimeoutMs + VOICE_WARMUP_GRACE_MS),
		onFailure: reportFailure,
		...(dependencies.supervisorOptions ?? {}),
	});
}

export function createJsonlAudit(filePath, metadata, dependencies = {}) {
	if (typeof filePath !== 'string' || filePath.trim() === '') throw new TypeError('protocol audit path must be nonblank');
	const makeDirectory = dependencies.mkdir ?? mkdir;
	const queue = new BestEffortDiagnosticQueue(dependencies);
	const sink = new RotatingJsonlSink(filePath, {
		...dependencies,
		inspect: dependencies.appendFile === undefined || dependencies.stat !== undefined
			|| dependencies.maxFileBytes !== undefined || dependencies.maxFileAgeMs !== undefined,
	});
	let closed = false;
	let closePromise = null;
	const ready = Promise.resolve().then(() => makeDirectory(path.dirname(path.resolve(filePath)), { recursive: true }));
	const audit = (direction, envelope) => {
		if (closed) return Promise.resolve();
		try {
			const safeMetadata = sanitizeDiagnosticValue(metadata);
			const row = Object.assign(Object.create(null),
				safeMetadata !== null && typeof safeMetadata === 'object' && !Array.isArray(safeMetadata) ? safeMetadata : {},
				{ direction: sanitizeDiagnosticValue(direction), envelope: sanitizeDiagnosticValue(envelope) });
			const encoded = `${JSON.stringify(row)}\n`;
			queue.submit(async () => { await ready; await sink.append(encoded, { encoding: 'utf8', flag: 'a' }); });
		} catch { /* invalid audit evidence is observational */ }
		return Promise.resolve();
	};
	audit.close = () => {
		if (closePromise !== null) return closePromise;
		closed = true;
		closePromise = queue.close();
		return closePromise;
	};
	audit.statusSnapshot = () => Object.freeze({ ...queue.statusSnapshot('protocol_audit'), droppedCount: queue.droppedCount });
	return audit;
}

export async function startVoiceWorker(config, environment = process.env, dependencies = {}) {
	if (config === null || typeof config !== 'object' || Array.isArray(config)) throw new TypeError('voice worker config must be an object');
	if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) throw new TypeError('voice worker environment must be an object');
	const voice = config.voice ?? {};
	const localSpeechTimeoutMs = voice.localSpeechTimeoutMs ?? DEFAULT_LOCAL_SPEECH_TIMEOUT_MS;
	if (!Number.isSafeInteger(localSpeechTimeoutMs) || localSpeechTimeoutMs < 1 || localSpeechTimeoutMs > 600_000) {
		throw new TypeError('voice.localSpeechTimeoutMs must be between 1 and 600000');
	}
	const signal = dependencies.signal;
	const reportVoiceDiagnostic = dependencies.reportVoiceDiagnostic ?? (() => {});
	if (typeof reportVoiceDiagnostic !== 'function') throw new TypeError('reportVoiceDiagnostic must be a function');
	if (signal !== undefined && (signal === null || typeof signal !== 'object' || typeof signal.aborted !== 'boolean')) {
		throw new TypeError('voice startup signal must be an AbortSignal');
	}
	throwIfVoiceStartupAborted(signal);
	const fishApiKey = firstNonBlank(
		environment[voice.fishApiKeyEnvironmentVariable ?? DEFAULT_FISH_API_KEY_ENVIRONMENT_VARIABLE],
		environment.FISH_API_KEY,
	);
	const deepgramApiKey = firstNonBlank(
		environment[voice.deepgramApiKeyEnvironmentVariable ?? DEFAULT_DEEPGRAM_API_KEY_ENVIRONMENT_VARIABLE],
	);
	const platform = dependencies.platform ?? process.platform;
	const createLocalSpeechProvider = dependencies.createLocalSpeechProvider
		?? ((options) => LocalSpeechProvider.createIfAvailable(options));
	if (typeof createLocalSpeechProvider !== 'function') throw new TypeError('createLocalSpeechProvider must be a function');
	let localSpeechProvider = null;
	let profiles = null;
	let profileStore = null;
	let worker = null;
	try {
		localSpeechProvider = await createLocalSpeechProvider({
			executable: firstNonBlank(environment.ARENA_AGENT_SPEECH_PYTHON, voice.localSpeechPythonPath)
				?? path.resolve(PROJECT_DIRECTORY, defaultLocalSpeechPythonPath(platform)),
			scriptPath: path.join(SOURCE_DIRECTORY, 'voice', 'local-speech-worker.py'),
			timeoutMs: localSpeechTimeoutMs,
			environment,
			signal,
			accessFile: dependencies.localSpeechAccess,
		});
		throwIfVoiceStartupAborted(signal);
		if (localSpeechProvider === null && fishApiKey === null && deepgramApiKey === null && platform !== 'win32') return null;
		const voiceSecret = await resolveVoiceSecret(voice, dependencies.readVoiceSecret ?? readFile);
		throwIfVoiceStartupAborted(signal);
		const createTtsProvider = dependencies.createTtsProvider ?? ((options) => new FishTtsProvider(options));
		const createWindowsTtsProvider = dependencies.createWindowsTtsProvider ?? ((options) => new WindowsTtsProvider(options));
		const createSttProvider = dependencies.createSttProvider ?? ((options) => new DeepgramSttProvider(options));
		const createServer = dependencies.createVoiceServer ?? createVoiceHttpServer;
		if (typeof createTtsProvider !== 'function') throw new TypeError('createTtsProvider must be a function');
		if (typeof createWindowsTtsProvider !== 'function') throw new TypeError('createWindowsTtsProvider must be a function');
		if (typeof createServer !== 'function') throw new TypeError('createVoiceServer must be a function');
		const hasTtsProvider = localSpeechProvider !== null || fishApiKey !== null || platform === 'win32';
		if (hasTtsProvider) {
			const profilePath = dependencies.profilePath
				?? voice.profileAssignmentsPath
				?? path.resolve(PROJECT_DIRECTORY, DEFAULT_VOICE_PROFILE_ASSIGNMENTS_PATH);
			const loadProfileStore = dependencies.loadProfileStore ?? loadPersistentVoiceProfileStore;
			if (typeof loadProfileStore !== 'function') throw new TypeError('loadProfileStore must be a function');
			try {
				profiles = await loadProfileStore(profilePath, { ...(dependencies.voiceProfileIo ?? {}), signal });
			} catch (error) {
				if (!canUseVolatileLocalProfiles({ error, localSpeechProvider, fishApiKey, deepgramApiKey, platform })) throw error;
				profiles = { store: new VoiceProfileStore() };
			}
			throwIfVoiceStartupAborted(signal);
			if (profiles === null || typeof profiles !== 'object' || profiles.store === null || typeof profiles.store?.resolve !== 'function') {
				throw new TypeError('loadProfileStore must return a profile store');
			}
			profileStore = voiceProfileStoreWithLifecycle(profiles);
		} else {
			profileStore = voiceProfileStoreWithLifecycle({ store: STT_ONLY_PROFILE_STORE });
		}
		if (deepgramApiKey !== null && typeof createSttProvider !== 'function') throw new TypeError('createSttProvider must be a function when Deepgram is configured');
		let provider;
		let sttProvider;
		let ownedSpeechProvider = localSpeechProvider;
		if (localSpeechProvider !== null) {
			const fallback = fishApiKey !== null || deepgramApiKey !== null
				? createRemoteFirstSpeechRouting(localSpeechProvider, {
					fishApiKey,
					deepgramApiKey,
					platform,
					createTtsProvider,
					createWindowsTtsProvider,
					createSttProvider,
					fallbackCircuit: voiceFallbackCircuitOptions(dependencies),
				})
				: createLocalSpeechFailover(localSpeechProvider, {
				fishApiKey,
				deepgramApiKey,
				platform,
				createTtsProvider,
				createWindowsTtsProvider,
				createSttProvider,
				fallbackCircuit: voiceFallbackCircuitOptions(dependencies),
				});
			provider = fallback.tts;
			sttProvider = fallback.stt;
			ownedSpeechProvider = fallback;
		} else {
			provider = fishApiKey === null
				? (platform === 'win32' ? createWindowsTtsProvider({}) : null)
				: createTtsProvider({ apiKey: fishApiKey });
			if (fishApiKey !== null && platform === 'win32') {
				provider = ttsProviderWithFallback(provider, createWindowsTtsProvider({}), voiceFallbackCircuitOptions(dependencies));
			}
			sttProvider = deepgramApiKey === null ? new NoSttProvider() : createSttProvider({ apiKey: deepgramApiKey });
		}
		emitVoiceDiagnostic(reportVoiceDiagnostic, fishApiKey === null
			? {
				code: 'VOICE_TTS_REMOTE_UNCONFIGURED',
				effectiveProvider: voiceProviderNamespace(provider, 'tts/unavailable'),
				reason: 'fish_credential_missing',
			}
			: {
				code: 'VOICE_TTS_REMOTE_CONFIGURED',
				effectiveProvider: voiceProviderNamespace(provider, 'fish/s2.1-pro-free'),
				reason: 'fish_credential_configured',
			});
		worker = createServer({
			provider,
			sttProvider,
			profileStore,
			secret: voiceSecret,
			port: voice.port ?? DEFAULT_VOICE_PORT,
			maxConcurrent: voice.maxConcurrent ?? DEFAULT_VOICE_MAX_CONCURRENT,
			requestTimeoutMs: localSpeechTimeoutMs,
			onDiagnostic: reportVoiceDiagnostic,
		});
		throwIfVoiceStartupAborted(signal);
		if (worker === null || typeof worker !== 'object' || typeof worker.start !== 'function' || typeof worker.close !== 'function') {
			throw new TypeError('createVoiceServer must return a voice worker');
		}
		await worker.start({ signal });
		throwIfVoiceStartupAborted(signal);
		return localSpeechProvider === null ? worker : voiceWorkerWithOwnedProvider(worker, ownedSpeechProvider);
	} catch (error) {
		await settleVoiceBootstrapCleanup(
			[
				() => worker?.close(),
				() => profileStore === null ? closeLoadedVoiceProfiles(profiles) : profileStore.close(),
				() => localSpeechProvider?.close(),
			],
			dependencies.cleanupTimeoutMs ?? 1_000,
		);
		throw error;
	}
}

function createRemoteFirstSpeechRouting(localProvider, {
	fishApiKey,
	deepgramApiKey,
	platform,
	createTtsProvider,
	createWindowsTtsProvider,
	createSttProvider,
	fallbackCircuit,
}) {
	const owned = new Set([localProvider]);
	let provider = localProvider;
	let sttProvider = localProvider;
	let localTtsFallback = localProvider;
	if (platform === 'win32') {
		const windows = createWindowsTtsProvider({});
		owned.add(windows);
		localTtsFallback = ttsProviderWithFallback(localProvider, windows, fallbackCircuit);
	}
	if (fishApiKey !== null) {
		const fish = createTtsProvider({ apiKey: fishApiKey });
		owned.add(fish);
		provider = ttsProviderWithFallback(fish, localTtsFallback, fallbackCircuit);
	} else {
		provider = localTtsFallback;
	}
	if (deepgramApiKey !== null) {
		const deepgram = createSttProvider({ apiKey: deepgramApiKey });
		owned.add(deepgram);
		sttProvider = sttProviderWithFallback(deepgram, localProvider);
	}
	const localIsPrimary = fishApiKey === null || deepgramApiKey === null;
	let warmupPromise = null;
	return Object.freeze({
		tts: provider,
		stt: sttProvider,
		warmup({ signal } = {}) {
			if (!localIsPrimary || typeof localProvider.warmup !== 'function') return;
			warmupPromise ??= Promise.resolve(localProvider.warmup({ signal })).catch((error) => {
				if (error?.name === 'AbortError' || signal?.aborted) {
					warmupPromise = null;
					throw error;
				}
				return undefined;
			});
			return warmupPromise;
		},
		async close() {
			await Promise.allSettled([...owned].map((candidate) => candidate?.close?.()));
		},
	});
}

function sttProviderWithFallback(primary, fallback) {
	return Object.freeze({
		async transcribe(request) {
			try {
				return await primary.transcribe(request);
			} catch (error) {
				if (!shouldUseLocalSttFallback(error, request?.signal)) throw error;
				return fallback.transcribe(request);
			}
		},
	});
}

function canUseVolatileLocalProfiles({ error, localSpeechProvider, fishApiKey, deepgramApiKey, platform }) {
	if (localSpeechProvider === null || fishApiKey !== null || deepgramApiKey === null || platform === 'win32') return false;
	if (error instanceof SyntaxError) return true;
	return ['EACCES', 'EPERM', 'EISDIR', 'ENOTDIR'].includes(error?.code);
}

function voiceProfileStoreWithLifecycle(profiles) {
	const store = profiles.store;
	const flush = profiles.flush ?? store.flush;
	const close = profiles.close ?? store.close;
	if (flush !== undefined && typeof flush !== 'function') throw new TypeError('profile store flush must be a function');
	if (close !== undefined && typeof close !== 'function') throw new TypeError('profile store close must be a function');
	const flushOperation = flush === undefined
		? null
		: flush.bind(typeof profiles.flush === 'function' ? profiles : store);
	const closeOperation = close === undefined
		? null
		: close.bind(typeof profiles.close === 'function' ? profiles : store);
	let closePromise = null;
	return Object.freeze({
		resolve(agentId) { return store.resolve(agentId); },
		remove(agentId) { return store.remove?.(agentId) ?? false; },
		flush(options) { return flushOperation === null ? Promise.resolve() : flushOperation(options); },
		close() {
			closePromise ??= Promise.resolve().then(() => closeOperation === null ? flushOperation?.() : closeOperation());
			return closePromise;
		},
	});
}

function closeLoadedVoiceProfiles(profiles) {
	if (profiles === null || typeof profiles !== 'object') return undefined;
	if (typeof profiles.close === 'function') return profiles.close();
	if (typeof profiles.store?.close === 'function') return profiles.store.close();
	if (typeof profiles.flush === 'function') return profiles.flush();
	if (typeof profiles.store?.flush === 'function') return profiles.store.flush();
	return undefined;
}

async function settleVoiceBootstrapCleanup(operations, timeoutMs) {
	if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) throw new TypeError('voice cleanup timeout must be positive');
	const cleanup = Promise.allSettled(operations.map((operation) => Promise.resolve().then(operation)));
	let timer;
	await Promise.race([
		cleanup,
		new Promise((resolve) => { timer = setTimeout(resolve, timeoutMs); }),
	]);
	clearTimeout(timer);
}

function throwIfVoiceStartupAborted(signal) {
	if (!signal?.aborted) return;
	if (signal.reason instanceof Error) throw signal.reason;
	const error = new Error('Voice startup was cancelled');
	error.name = 'AbortError';
	error.code = 'VOICE_OPERATION_CANCELLED';
	throw error;
}

function voiceWorkerWithOwnedProvider(worker, provider) {
	let closePromise = null;
	return Object.freeze({
		...worker,
		start: worker.start.bind(worker),
		warmup: typeof provider.warmup === 'function'
			? ({ signal } = {}) => provider.warmup({ signal })
			: undefined,
		close() {
			closePromise ??= Promise.allSettled([worker.close(), provider.close()]).then(() => undefined);
			return closePromise;
		},
	});
}

function defaultLocalSpeechPythonPath(platform) {
	return path.join(
		DEFAULT_LOCAL_SPEECH_PYTHON_DIRECTORY,
		platform === 'win32' ? 'Scripts' : 'bin',
		platform === 'win32' ? 'python.exe' : 'python',
	);
}

function createLocalSpeechFailover(localProvider, {
	fishApiKey,
	deepgramApiKey,
	platform,
	createTtsProvider,
	createWindowsTtsProvider,
	createSttProvider,
	fallbackCircuit,
}) {
	let activeTts = localProvider;
	let activeStt = localProvider;
	let fallbackTts = null;
	let fallbackStt = null;
	let localUnavailable = false;
	let ttsTransitionPromise = null;
	let sttTransitionPromise = null;
	let warmupPromise = null;
	let localClosePromise = null;
	const closeLocal = () => {
		localClosePromise ??= Promise.resolve().then(() => localProvider.close?.()).then(() => undefined, () => undefined);
		return localClosePromise;
	};
	const closeLocalIfUnused = () => {
		if (activeTts === localProvider || activeStt === localProvider) return Promise.resolve();
		localUnavailable = true;
		return closeLocal();
	};
	const fallbackTtsFactory = () => {
		if (fallbackTts !== null) return fallbackTts;
		if (fishApiKey !== null) {
			const fish = createTtsProvider({ apiKey: fishApiKey });
			fallbackTts = platform === 'win32'
				? ttsProviderWithFallback(fish, createWindowsTtsProvider({}), fallbackCircuit)
				: fish;
			return fallbackTts;
		}
		if (platform === 'win32') fallbackTts = createWindowsTtsProvider({});
		return fallbackTts;
	};
	const fallbackSttFactory = () => {
		if (fallbackStt !== null) return fallbackStt;
		fallbackStt = deepgramApiKey === null
			? new NoSttProvider()
			: createSttProvider({ apiKey: deepgramApiKey });
		return fallbackStt;
	};
	const switchTtsToFallback = async (error, signal, allowUnavailable = false) => {
		if (!shouldUseLocalTtsFallback(error, signal)) throw error;
		if (fishApiKey === null && platform !== 'win32' && !allowUnavailable) throw error;
		if (activeTts !== localProvider) return;
		ttsTransitionPromise ??= Promise.resolve().then(async () => {
			throwIfVoiceStartupAborted(signal);
			if (activeTts === localProvider) activeTts = fallbackTtsFactory();
			await closeLocalIfUnused();
		}).catch((transitionError) => {
			ttsTransitionPromise = null;
			throw transitionError;
		});
		await ttsTransitionPromise;
	};
	const switchSttToFallback = async (error, signal, allowUnavailable = false) => {
		if (!shouldUseLocalSttFallback(error, signal)) throw error;
		if (deepgramApiKey === null && !allowUnavailable) throw error;
		if (activeStt !== localProvider) return;
		sttTransitionPromise ??= Promise.resolve().then(async () => {
			throwIfVoiceStartupAborted(signal);
			if (activeStt === localProvider) activeStt = fallbackSttFactory();
			await closeLocalIfUnused();
		}).catch((transitionError) => {
			sttTransitionPromise = null;
			throw transitionError;
		});
		await sttTransitionPromise;
	};
	const switchFailedWarmupChannels = async ({ sttReady, ttsReady }, error, signal) => {
		if (error?.name === 'AbortError' || signal?.aborted) throw error;
		const ttsUsable = ttsReady || fishApiKey !== null || platform === 'win32';
		const sttUsable = sttReady || deepgramApiKey !== null;
		if (!ttsReady && !sttReady) {
			if (!ttsUsable && !sttUsable) throw error;
			await closeLocal();
			throwIfVoiceStartupAborted(signal);
			await Promise.all([
				switchTtsToFallback(error, signal, true),
				switchSttToFallback(error, signal, true),
			]);
			return;
		}
		await Promise.all([
			ttsReady ? undefined : switchTtsToFallback(error, signal, true),
			sttReady ? undefined : switchSttToFallback(error, signal, true),
		]);
	};
	return Object.freeze({
		tts: Object.freeze({
			cacheNamespace() {
				if (activeTts === localProvider) return 'local-chatterbox/chatterbox-v1';
				if (activeTts === null) return 'tts/unavailable';
				return providerCacheNamespace(
					activeTts,
					fishApiKey !== null ? 'fish/s2.1-pro-free' : 'windows/system-speech',
				);
			},
			async synthesize(request) {
				if (activeTts !== null) {
					const attemptedProvider = activeTts;
					const attemptedNamespace = attemptedProvider === localProvider
						? 'local-chatterbox/chatterbox-v1'
						: providerCacheNamespace(
							attemptedProvider,
							fishApiKey !== null ? 'fish/s2.1-pro-free' : 'windows/system-speech',
						);
					try {
						return tagSynthesisCacheNamespace(await attemptedProvider.synthesize(request), attemptedNamespace);
					} catch (error) {
						if (attemptedProvider !== localProvider) throw error;
						await switchTtsToFallback(error, request?.signal);
						const fallbackNamespace = providerCacheNamespace(
							activeTts,
							fishApiKey !== null ? 'fish/s2.1-pro-free' : 'windows/system-speech',
						);
						return tagSynthesisCacheNamespace(await activeTts.synthesize(request), fallbackNamespace);
					}
				}
				const error = new Error('Speech synthesis is not configured');
				error.code = 'TTS_UNAVAILABLE';
				throw error;
			},
		}),
		stt: Object.freeze({
			async transcribe(request) {
				const attemptedProvider = activeStt;
				try {
					return await attemptedProvider.transcribe(request);
				} catch (error) {
					if (attemptedProvider !== localProvider) throw error;
					await switchSttToFallback(error, request?.signal);
					return activeStt.transcribe(request);
				}
			},
		}),
		warmup({ signal } = {}) {
			if (localUnavailable || typeof localProvider.warmup !== 'function') return;
			warmupPromise ??= (async () => {
				let readiness;
				try {
					readiness = await localProvider.warmup({ signal });
				} catch (error) {
					await switchFailedWarmupChannels({ sttReady: false, ttsReady: false }, error, signal);
					return;
				}
				if (readiness === undefined) return;
				if (readiness === null || typeof readiness !== 'object'
						|| typeof readiness.sttReady !== 'boolean' || typeof readiness.ttsReady !== 'boolean') {
					throw new TypeError('local speech warmup must return channel readiness');
				}
				if (readiness.sttReady && readiness.ttsReady) return;
				const retryLocalOnlyChannel = (!readiness.sttReady && deepgramApiKey === null)
						|| (!readiness.ttsReady && fishApiKey === null && platform !== 'win32');
				if (retryLocalOnlyChannel && (readiness.sttReady || readiness.ttsReady)) {
					try {
						const retried = await localProvider.warmup({ signal });
						if (retried === null || typeof retried !== 'object'
								|| typeof retried.sttReady !== 'boolean' || typeof retried.ttsReady !== 'boolean') {
							throw new TypeError('local speech warmup must return channel readiness');
						}
						readiness = retried;
					} catch (error) {
						if (error?.name === 'AbortError' || signal?.aborted) throw error;
					}
					if (readiness.sttReady && readiness.ttsReady) return;
				}
				const error = Object.assign(new Error('Local speech warmup did not initialize every channel'), {
					code: 'LOCAL_SPEECH_WARMUP_FAILED',
				});
				await switchFailedWarmupChannels(readiness, error, signal);
			})().catch((error) => {
				warmupPromise = null;
				throw error;
			});
			return warmupPromise;
		},
		async close() {
			await Promise.allSettled([
				closeLocal(),
				fallbackTts?.close?.(),
				fallbackStt?.close?.(),
			]);
		},
	});
}

function shouldUseLocalTtsFallback(error, signal) {
	if (error?.name === 'AbortError' || signal?.aborted) return false;
	if (error instanceof TypeError || error?.code === 'TTS_INVALID_REQUEST') return false;
	return true;
}

function shouldUseLocalSttFallback(error, signal) {
	if (error?.name === 'AbortError' || signal?.aborted) return false;
	if (error instanceof TypeError || ['STT_INVALID_REQUEST', 'STT_MALFORMED_AUDIO'].includes(error?.code)) return false;
	return true;
}

function voiceFallbackCircuitOptions(dependencies) {
	return {
		now: dependencies.voiceFallbackNow ?? Date.now,
		baseDelayMs: dependencies.voiceFallbackBaseDelayMs ?? DEFAULT_FISH_FALLBACK_BASE_DELAY_MS,
		maxDelayMs: dependencies.voiceFallbackMaxDelayMs ?? DEFAULT_FISH_FALLBACK_MAX_DELAY_MS,
		onDiagnostic: dependencies.reportVoiceDiagnostic ?? (() => {}),
	};
}

function ttsProviderWithFallback(primary, fallback, {
	now = Date.now,
	baseDelayMs = DEFAULT_FISH_FALLBACK_BASE_DELAY_MS,
	maxDelayMs = DEFAULT_FISH_FALLBACK_MAX_DELAY_MS,
	onDiagnostic = () => {},
} = {}) {
	if (typeof now !== 'function') throw new TypeError('Fish fallback clock must be a function');
	if (typeof onDiagnostic !== 'function') throw new TypeError('Fish fallback diagnostic reporter must be a function');
	if (!Number.isSafeInteger(baseDelayMs) || baseDelayMs < 1) throw new TypeError('Fish fallback base delay must be positive');
	if (!Number.isSafeInteger(maxDelayMs) || maxDelayMs < baseDelayMs) throw new TypeError('Fish fallback maximum delay must not be less than its base delay');
	let consecutiveFailures = 0;
	let nextProbeAt = null;
	let probePromise = null;
	const readNow = () => {
		const value = now();
		if (!Number.isSafeInteger(value) || value < 0) throw new TypeError('Fish fallback clock must return a non-negative safe integer');
		return value;
	};
	const primaryProvider = voiceProviderNamespace(primary, 'tts/primary');
	const fallbackProvider = voiceProviderNamespace(fallback, 'tts/fallback');
	const recordFailure = (error) => {
		consecutiveFailures = Math.min(consecutiveFailures + 1, 31);
		const exponent = Math.min(consecutiveFailures - 1, 30);
		const delay = Math.min(maxDelayMs, baseDelayMs * (2 ** exponent));
		nextProbeAt = Math.min(Number.MAX_SAFE_INTEGER, readNow() + delay);
		emitVoiceDiagnostic(onDiagnostic, {
			code: 'VOICE_TTS_FALLBACK_ACTIVATED',
			primaryProvider,
			effectiveProvider: fallbackProvider,
			failureCode: voiceDiagnosticFailureCode(error),
		});
	};
	const synthesizeFallback = async (request) => {
		const output = await fallback.synthesize(request);
		return tagSynthesisCacheNamespace(
			{ ...output, cacheable: false },
			providerCacheNamespace(fallback, 'windows/system-speech'),
		);
	};
	const attemptPrimary = async (request) => {
		const recovering = nextProbeAt !== null;
		try {
			const output = await primary.synthesize(request);
			consecutiveFailures = 0;
			nextProbeAt = null;
			if (recovering) emitVoiceDiagnostic(onDiagnostic, {
				code: 'VOICE_TTS_PRIMARY_RESTORED',
				primaryProvider,
				effectiveProvider: primaryProvider,
			});
			return tagSynthesisCacheNamespace(output, providerCacheNamespace(primary, 'fish/s2.1-pro-free'));
		} catch (error) {
			if (!shouldUseWindowsTtsFallback(error)) throw error;
			recordFailure(error);
			return synthesizeFallback(request);
		}
	};
	return Object.freeze({
		cacheNamespace() {
			return nextProbeAt === null
				? providerCacheNamespace(primary, 'fish/s2.1-pro-free')
				: providerCacheNamespace(fallback, 'windows/system-speech');
		},
		async synthesize(request) {
			if (nextProbeAt === null) return attemptPrimary(request);
			if (probePromise !== null || readNow() < nextProbeAt) return synthesizeFallback(request);
			probePromise = attemptPrimary(request).finally(() => { probePromise = null; });
			return probePromise;
		},
	});
}

function voiceProviderNamespace(provider, fallback) {
	try {
		const namespace = providerCacheNamespace(provider, fallback);
		return /^[a-z0-9][a-z0-9._/-]{0,127}$/.test(namespace) ? namespace : 'tts/unspecified';
	} catch {
		return 'tts/unspecified';
	}
}

function voiceDiagnosticFailureCode(error) {
	const value = error?.code;
	return typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : 'TTS_PROVIDER_ERROR';
}

function emitVoiceDiagnostic(reporter, event) {
	try { reporter(Object.freeze({ ...event })); }
	catch { /* voice diagnostics are observational */ }
}

function writeVoiceDiagnostic(event) {
	const fields = Object.entries(event)
		.filter(([key]) => key !== 'code')
		.map(([key, value]) => `${key}=${value}`)
		.join(' ');
	process.stderr.write(`[voice] ${event.code}${fields === '' ? '' : ` ${fields}`}\n`);
}

function shouldUseWindowsTtsFallback(error) {
	if (error?.name === 'AbortError') return false;
	return error?.name === 'TimeoutError' || WINDOWS_TTS_FALLBACK_CODES.has(error?.code);
}

function normalizeVoiceConfig(value, environment) {
	const source = value ?? {};
	const configuredPort = environment.ARENA_AGENT_VOICE_PORT === undefined
		? source.port ?? DEFAULT_VOICE_PORT
		: Number(environment.ARENA_AGENT_VOICE_PORT);
	if (!Number.isSafeInteger(configuredPort) || configuredPort < 1 || configuredPort > 65_535) throw new TypeError('voice.port must be an integer between 1 and 65535');
	const maxConcurrent = positiveInteger(source.maxConcurrent ?? DEFAULT_VOICE_MAX_CONCURRENT, 'voice.maxConcurrent');
	if (maxConcurrent > 5) throw new TypeError('voice.maxConcurrent must not exceed 5');
	const profileAssignmentsPath = path.resolve(PROJECT_DIRECTORY, source.profileAssignmentsPath ?? DEFAULT_VOICE_PROFILE_ASSIGNMENTS_PATH);
	const localSpeechTimeoutMs = positiveInteger(source.localSpeechTimeoutMs ?? DEFAULT_LOCAL_SPEECH_TIMEOUT_MS, 'voice.localSpeechTimeoutMs');
	if (localSpeechTimeoutMs > 600_000) throw new TypeError('voice.localSpeechTimeoutMs must not exceed 600000');
	const secret = firstNonBlank(source.secret, environment.ARENA_AGENT_VOICE_SECRET);
	const configuredSecretFile = firstNonBlank(source.secretFile, environment.ARENA_AGENT_VOICE_SECRET_FILE);
	return {
		...source,
		secret,
		secretFile: path.resolve(PROJECT_DIRECTORY, configuredSecretFile ?? DEFAULT_VOICE_SECRET_PATH),
		port: configuredPort,
		maxConcurrent,
		profileAssignmentsPath,
		localSpeechTimeoutMs,
		fishApiKeyEnvironmentVariable: requireEnvironmentVariableName(source.fishApiKeyEnvironmentVariable ?? DEFAULT_FISH_API_KEY_ENVIRONMENT_VARIABLE, 'voice.fishApiKeyEnvironmentVariable'),
		deepgramApiKeyEnvironmentVariable: requireEnvironmentVariableName(source.deepgramApiKeyEnvironmentVariable ?? DEFAULT_DEEPGRAM_API_KEY_ENVIRONMENT_VARIABLE, 'voice.deepgramApiKeyEnvironmentVariable'),
	};
}

async function resolveVoiceSecret(voice, readSecretFile) {
	let secret = firstNonBlank(voice.secret);
	if (secret === null) {
		if (typeof readSecretFile !== 'function') throw new TypeError('readVoiceSecret must be a function');
		try {
			secret = firstNonBlank(await readSecretFile(voice.secretFile, 'utf8'));
		} catch (error) {
			throw codedRuntimeError('VOICE_SECRET_UNAVAILABLE', 'Dedicated voice authentication secret is unavailable', error);
		}
	}
	if (secret !== null) secret = secret.trim();
	if (secret === null || secret.length < 16 || secret.length > 512) {
		throw codedRuntimeError('VOICE_SECRET_INVALID', 'Dedicated voice authentication secret must contain 16 to 512 characters');
	}
	return secret;
}

function firstNonBlank(...values) {
	for (const value of values) if (typeof value === 'string' && value.trim() !== '') return value;
	return null;
}

function requireEnvironmentVariableName(value, field) {
	if (typeof value !== 'string' || !/^[A-Z_][A-Z0-9_]*$/.test(value)) throw new TypeError(`${field} must be an environment variable name`);
	return value;
}

function requireDependency(value, name) {
	if (value === null || value === undefined) throw new TypeError(`${name} is required`);
	return value;
}

function defaultStatusInterval(callback, milliseconds) {
	const handle = setInterval(callback, milliseconds);
	handle.unref?.();
	return handle;
}

function positiveInteger(value, field) {
	if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError(`${field} must be a positive safe integer`);
	return value;
}

function nonNegativeInteger(value, field) {
	if (!Number.isSafeInteger(value) || value < 0) throw new TypeError(`${field} must be a non-negative safe integer`);
	return value;
}

function safeClockRead(clock) {
	try {
		const value = clock();
		return Number.isFinite(value) && value >= 0 ? value : null;
	} catch {
		return null;
	}
}

function codedRuntimeError(code, message, cause = undefined) {
	return Object.assign(new Error(message, cause === undefined ? undefined : { cause }), { code });
}

function isTransientCompletionSendError(error) {
	return ['BRIDGE_NOT_READY', 'BRIDGE_DISCONNECTED', 'CONNECTION_BACKPRESSURE', 'AGENT_BACKPRESSURE'].includes(error?.code);
}

function verboseErrorMessage(error) {
	const code = String(error?.code ?? 'COORDINATOR_ERROR').slice(0, 128);
	return `${verboseErrorScope(code)} error (${code}).`;
}

function verboseRecoveryMessage(recovery) {
	if (recovery.quiet) return 'Provider output was incomplete; retrying from the next fresh observation.';
	if (recovery.blocked) return 'Provider access is unavailable; retrying automatically from fresh state.';
	return 'Provider work failed; recovering automatically from fresh state.';
}

function sanitizeVerboseMessage(stage, message) {
	const normalized = String(message ?? '')
		.replace(/[\u0000-\u001f\u007f-\u009f]+/g, ' ')
		.replace(/\s+/g, ' ')
		.trim();
	if (stage === 'error') {
		const code = normalized.match(/\(([A-Z][A-Z0-9_]{1,127})\)/)?.[1]
			?? normalized.match(/\b[A-Z][A-Z0-9_]{2,127}\b/)?.[0]
			?? 'COORDINATOR_ERROR';
		const scope = /^(Provider|Planning|Coordinator) error \(/i.exec(normalized)?.[1] ?? verboseErrorScope(code);
		return `${scope[0].toUpperCase()}${scope.slice(1).toLowerCase()} error (${code}).`.slice(0, MAX_VERBOSE_MESSAGE_LENGTH);
	}
	return sanitizeVerboseOutput(normalized).slice(0, MAX_VERBOSE_MESSAGE_LENGTH);
}

function verboseDecisionSummary(decision) {
	return sanitizePublicNarrative(decision?.summary) || 'Plan accepted.';
}

function verboseTransitionIdentity(stage, message, value) {
	const defaults = {
		conversation: { component: 'conversation', boundary: 'message', code: 'MESSAGE_RECEIVED', state: 'ready' },
		decision: { component: 'provider', boundary: 'planning', code: 'PLAN_ACCEPTED', state: 'ready' },
		error: { component: 'coordinator', boundary: 'agent_work', code: 'COORDINATOR_ERROR', state: 'degraded' },
		lifecycle: { component: 'lifecycle', boundary: 'goal', code: 'LIFECYCLE_CHANGED', state: 'ready' },
		retry: { component: 'provider', boundary: 'planning', code: 'PROVIDER_RETRY', state: 'retrying' },
	}[stage] ?? { component: 'coordinator', boundary: stage, code: 'STATUS_CHANGED', state: 'ready' };
	return {
		component: String(value?.component ?? defaults.component).slice(0, 128),
		boundary: String(value?.boundary ?? defaults.boundary).slice(0, 128),
		code: String(value?.code ?? defaults.code).slice(0, 128),
		state: String(value?.state ?? defaults.state).slice(0, 128),
		detail: message,
	};
}

function sanitizePublicAgentMessage(message) {
	return sanitizePublicNarrative(message);
}

function sanitizePublicNarrative(message) {
	const source = typeof message === 'string' ? message : String(message ?? '');
	let raw = source.slice(0, MAX_PUBLIC_NARRATIVE_RAW_CHARS);
	if (source.length > MAX_PUBLIC_NARRATIVE_RAW_CHARS) raw = completePublicSentences(raw);
	const visible = sanitizeVerboseOutput(raw);
	if (visible.length === 0 || /^[{]/.test(visible)) return '';
	const boundary = publicNarrativeBoundary(visible);
	return visible.slice(0, boundary ?? visible.length).replace(/[\s,;:-]+$/, '').trim().slice(0, MAX_VERBOSE_MESSAGE_LENGTH);
}

function completePublicSentences(value) {
	let boundary = 0;
	for (const match of value.matchAll(/[.!?](?=\s|$)/g)) boundary = match.index + match[0].length;
	return value.slice(0, boundary);
}

function publicNarrativeBoundary(value) {
	const matches = [
		value.search(/\{/),
		value.search(/\[\s*\{/),
		value.search(/\b(?:action[_ -]?id|native[_ -]?action|action[_ -]?call|call[_ -]?id|tool[_ -]?(?:call|record|result)|trace[_ -]?id|uuid|diagnostic|program[_ -]?(?:step|compiled|replaced))\b/i),
		value.search(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/i),
		value.search(/\bnative:[a-z0-9._-]+:\d+:\d+\b/i),
		value.search(/\baction-progress-\d+\b/i),
		value.search(/\bcall_[a-z0-9]{3,}\b/i),
		value.search(/\b[a-z0-9._-]+:\d+:\d+:\d+:program-\d+-\d+:\d+:\d+:(?:arena-state|step)-\d+\b/i),
		value.search(/\bprogram-\d+-\d+:\d+:\d+:(?:arena-state|step)-\d+\b/i),
		value.search(/\btrace-[a-z0-9._:-]+-\d+-\d+-[a-z][a-z0-9_-]*\b/i),
		value.search(/\b(?:calling|executing|invoking)\s+[a-z][a-z0-9_]*\s+with\s+(?:[a-z][a-z0-9_]*\s*=|\{)/i),
	].filter((index) => index >= 0);
	return matches.length === 0 ? null : Math.min(...matches);
}

function verboseErrorScope(code) {
	if (/^(?:AUTHENTICATION_REQUIRED|PROVIDER|TURN_|REQUEST_TIMEOUT|MISSING_(?:AGENT|FINAL)_MESSAGE|SPAWN_|APP_SERVER_)/.test(code)) return 'Provider';
	if (/(?:DECISION|PLANNER|PLANNING_TIMEOUT|PLAN_|PARSE|DIRECTIVE|ARENA_SCRIPT)/.test(code)) return 'Planning';
	return 'Coordinator';
}

function sanitizeVerboseOutput(message) {
	return String(message ?? '')
		.replace(/[\u0000-\u001f\u007f-\u009f]+/g, ' ')
		.replace(/\s+/g, ' ')
		.trim()
		.replace(/\bsk-[A-Za-z0-9_-]{16,}\b/g, '[REDACTED_KEY]')
		.replace(/\bAIza[A-Za-z0-9_-]{20,}\b/g, '[REDACTED_KEY]')
		.replace(/\b(?:Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+/gi, '[REDACTED_AUTH]')
		.replace(/(\b(?:secret|token|api[-_ ]?key|password|authorization)\b["']?\s*(?:[:=]\s*|\s+))(?:"[^"]*"|'[^']*'|[^\s,;]+)/gi, '$1[REDACTED]');
}

function factualProgressSignature(observation) {
	const projection = factualProgressProjection(observation);
	return createHash('sha256').update(JSON.stringify(sortFactualValue(projection))).digest('hex');
}

function factualProgressDetails(observation) {
	const player = observation?.player ?? {};
	const position = player.position ?? player;
	return {
		position: {
			x: finiteOrNull(position?.x),
			y: finiteOrNull(position?.y),
			z: finiteOrNull(position?.z),
		},
		lastResult: observation?.lastResult?.present === true ? {
			actionType: observation.lastResult.actionType,
			state: observation.lastResult.state,
			reasonCode: observation.lastResult.reasonCode,
		} : null,
	};
}

function factualProgressProjection(observation) {
	const player = observation?.player ?? {};
	const position = player.position ?? player;
	return {
		position: [finiteOrNull(position?.x), finiteOrNull(position?.y), finiteOrNull(position?.z)],
		alive: player.dead === true ? false : player.alive ?? null,
		health: finiteOrNull(player.health),
		inventory: (observation?.inventory?.items ?? []).map((item) => ({ itemId: item?.itemId ?? item?.id ?? null, count: item?.count ?? null })),
		blocks: (observation?.blocks ?? []).map((block) => ({ x: block?.x ?? null, y: block?.y ?? null, z: block?.z ?? null, blockId: block?.blockId ?? block?.id ?? null, state: block?.state ?? block?.properties ?? null })),
		advancements: observation?.advancements ?? null,
		killEvidence: observation?.killEvidence ?? null,
	};
}

function sortFactualValue(value) {
	if (Array.isArray(value)) return value.map(sortFactualValue);
	if (value !== null && typeof value === 'object') {
		return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sortFactualValue(value[key])]));
	}
	return value;
}

function finiteOrNull(value) {
	return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

export function classifyObservationTrigger(payload, observation, signals = null) {
	const explicitTrigger = typeof payload.trigger === 'string' && payload.trigger.trim().length > 0 ? payload.trigger.trim().slice(0, 128) : null;
	const attention = payload.attention === true;
	if (explicitTrigger !== null) return { attention: true, priority: payload.priority === 'urgent' ? 'urgent' : 'ordinary', trigger: explicitTrigger };
	const changedFacts = Array.isArray(payload.changedFacts) ? payload.changedFacts : [];
	const joinedFacts = changedFacts.filter((value) => typeof value === 'string').join('|').toLowerCase();
	const player = observation?.player ?? {};
	if (joinedFacts.includes('health') || joinedFacts.includes('attacker') || joinedFacts.includes('damage')) return { attention: true, priority: 'urgent', trigger: 'damage' };
	if (joinedFacts.includes('lava')) return { attention: true, priority: 'urgent', trigger: 'lava' };
	if (joinedFacts.includes('fire') || player.fire === true) return { attention: true, priority: 'urgent', trigger: 'fire' };
	if (joinedFacts.includes('suffoc') || joinedFacts.includes('air')) return { attention: true, priority: 'urgent', trigger: 'suffocation' };
	if (joinedFacts.includes('fall')) return { attention: true, priority: 'urgent', trigger: 'fall' };
	if (Array.isArray(observation?.blocks) && observation.blocks.some((block) => typeof block?.blockId === 'string' && block.blockId.toLowerCase().includes('lava'))) return { attention: true, priority: 'urgent', trigger: 'lava' };
	if (signals?.movementLoop === true) return { attention: true, priority: 'urgent', trigger: 'movement_loop' };
	if (signals?.resourceDiscovery === true) return { attention: true, priority: 'urgent', trigger: 'resource_discovery' };
	if (!attention) return { attention: false, priority: 'ordinary', trigger: 'observation' };
	return { attention: true, priority: 'ordinary', trigger: 'attention' };
}

/** Detects a repeated two-point walk without flagging ordinary forward travel. */
export function detectMovementLoop(positionKeys) {
	if (!Array.isArray(positionKeys) || positionKeys.length < 4) return false;
	const tail = positionKeys.slice(-6);
	if (tail.length < 4) return false;
	const unique = new Set(tail);
	if (unique.size !== 2) return false;
	for (let index = 2; index < tail.length; index += 1) {
		if (tail[index] !== tail[index - 2]) return false;
	}
	return true;
}

function nativeBlockPositionKey(player) {
	if (![player?.x, player?.y, player?.z].every((value) => typeof value === 'number' && Number.isFinite(value))) return null;
	return `${Math.round(player.x)},${Math.round(player.y)},${Math.round(player.z)}`;
}

function observedResourceCandidates(observation) {
	const candidates = [];
	for (const block of observation?.blocks ?? []) {
		if (!isResourceBlock(block)) continue;
		candidates.push(`block:${block.x},${block.y},${block.z}:${block.blockId}`);
	}
	for (const landmark of observation?.landmarks ?? []) {
		if (!isResourceBlock(landmark)) continue;
		candidates.push(`landmark:${landmark.x},${landmark.y},${landmark.z}:${landmark.blockId}`);
	}
	for (const item of observation?.items ?? []) {
		if (typeof item?.stableId !== 'string' || typeof item?.itemId !== 'string') continue;
		candidates.push(`item:${item.stableId}:${item.itemId}`);
	}
	return candidates;
}

function isResourceBlock(block) {
	const blockId = typeof block?.blockId === 'string' ? block.blockId.toLowerCase() : '';
	const blockTags = Array.isArray(block?.tags) ? block.tags : [];
	return blockTags.some((tag) => typeof tag === 'string' && (
		tag === '#minecraft:logs'
		|| tag === '#minecraft:leaves'
		|| tag === '#minecraft:ores'
		|| tag === '#minecraft:crops'
		|| tag === '#minecraft:flowers'
	)) || RESOURCE_BLOCK_SUFFIXES.some((suffix) => blockId.endsWith(suffix));
}

const RESOURCE_BLOCK_SUFFIXES = Object.freeze([
	'_log', '_wood', '_ore', '_leaves', '_crop', '_crops', '_flower', '_mushroom',
	'crafting_table', 'furnace', 'chest', 'barrel', 'hay_block', 'pumpkin', 'melon',
]);

function mergeAttentionTrigger(previous, next) {
	const priority = previous?.priority === 'urgent' || next?.priority === 'urgent' ? 'urgent' : 'ordinary';
	const winner = next?.priority === priority ? next : previous;
	return {
		attention: previous?.attention === true || next?.attention !== false,
		priority,
		trigger: winner?.trigger ?? 'attention',
	};
}

function mergePlannerRequest(previous, next) {
	if (previous === null || previous === undefined) return next;
	const priority = previous.priority === 'urgent' || next.priority === 'urgent' ? 'urgent' : 'ordinary';
	const winner = next.priority === priority ? next : previous;
	return { ...next, priority, trigger: winner.trigger };
}

function sameSupervisionKey(left, right) {
	return left?.agentId === right?.agentId
		&& left?.goalRevision === right?.goalRevision
		&& left?.lifecycleGeneration === right?.lifecycleGeneration
		&& left?.sessionEpoch === right?.sessionEpoch
		&& left?.profileFingerprint === right?.profileFingerprint;
}

export function buildNativeEventInput(record, { event, trigger, observation = {}, conversation = { mode: 'unread', baseSequence: -1, nextSequence: -1, entries: [] }, conversationOnly = false } = {}) {
	const compactObservation = {
		...(observation.ready === undefined ? {} : { ready: observation.ready }),
		...(observation.status === undefined ? {} : { status: observation.status }),
		...(observation.velocity === undefined ? {} : { velocity: observation.velocity }),
		player: observation.player ?? {},
		inventory: {
			items: asArray(observation.inventory?.items).slice(0, 32),
			...(observation.inventory?.selectedItem === undefined ? {} : { selectedItem: observation.inventory.selectedItem }),
			...(observation.inventory?.tagCounts === undefined ? {} : { tagCounts: observation.inventory.tagCounts }),
		},
		items: asArray(observation.items).slice(0, 16),
		entities: asArray(observation.entities).filter((entity) => entity?.type !== 'minecraft:item').slice(0, 16),
		blocks: asArray(observation.blocks).slice(0, 32),
		...(Array.isArray(observation.landmarks) ? { landmarks: observation.landmarks.slice(0, 32) } : {}),
		...(Array.isArray(observation.nearbyContainers) ? { nearbyContainers: observation.nearbyContainers.slice(0, 16) } : {}),
		...(observation.world === undefined ? {} : { world: observation.world }),
		...(observation.currentAction === undefined ? {} : { currentAction: observation.currentAction }),
		...(observation.lastResult === undefined ? {} : { lastResult: observation.lastResult }),
		...(observation.interaction === undefined ? {} : { interaction: observation.interaction }),
		...(observation.death === undefined ? {} : { death: observation.death }),
		...(observation.recovery === undefined ? {} : { recovery: observation.recovery }),
		...(Array.isArray(observation.options) ? { options: observation.options.slice(0, 4) } : {}),
		...(observation.failureClass === undefined ? {} : { failureClass: observation.failureClass }),
		...(observation.continuity === undefined ? {} : { continuity: observation.continuity }),
		...(observation.lastLiveInventory === undefined ? {} : { lastLiveInventory: observation.lastLiveInventory }),
	};
	const unreadConversation = Array.isArray(conversation)
		? { mode: 'unread', baseSequence: null, nextSequence: conversation.at(-1)?.sequence ?? -1, entries: conversation }
		: {
			mode: 'unread',
			baseSequence: conversation?.baseSequence ?? null,
			nextSequence: conversation?.nextSequence ?? -1,
			entries: Array.isArray(conversation?.entries) ? conversation.entries : [],
		};
	const payload = {
		event: typeof event === 'string' && event.length > 0 ? event : 'observation',
		trigger: typeof trigger === 'string' && trigger.length > 0 ? trigger : 'observation',
		mode: conversationOnly === true ? 'conversation_only' : 'goal',
		goal: record?.currentGoal ?? null,
		goalSpec: record?.currentGoalSpec ?? null,
		goalRevision: record?.goalRevision ?? 0,
		observation: compactObservation,
		conversation: unreadConversation,
	};
	let json = JSON.stringify(payload);
	if (Buffer.byteLength(json, 'utf8') > 16_384) {
		json = JSON.stringify({
			...payload,
			observation: {
				player: compactObservation.player,
				inventory: { items: compactObservation.inventory.items.slice(0, 16) },
				items: [],
				entities: [],
				blocks: compactObservation.blocks.slice(0, 12),
				...(Array.isArray(compactObservation.landmarks) ? { landmarks: compactObservation.landmarks.slice(0, 12) } : {}),
				...(compactObservation.world === undefined ? {} : { world: compactWorldForEvent(compactObservation.world) }),
				...(compactObservation.lastResult === undefined ? {} : { lastResult: compactLastResultForEvent(compactObservation.lastResult) }),
				...(compactObservation.recovery === undefined ? {} : { recovery: compactRecoveryForEvent(compactObservation.recovery) }),
				...(Array.isArray(compactObservation.options) ? { options: compactObservation.options.slice(0, 2) } : {}),
				...(compactObservation.failureClass === undefined ? {} : { failureClass: compactObservation.failureClass }),
				...(compactObservation.death === undefined ? {} : { death: compactObservation.death }),
				...(compactObservation.continuity === undefined ? {} : { continuity: compactObservation.continuity }),
				...(compactObservation.lastLiveInventory === undefined ? {} : { lastLiveInventory: compactObservation.lastLiveInventory }),
			},
			conversation: { ...unreadConversation, entries: unreadConversation.entries.slice(-8) },
		});
	}
	return `Live Minecraft event. Choose and call the smallest useful tool now.\n${json}`;
}

function compactRecoveryForEvent(recovery) {
	if (recovery === null || typeof recovery !== 'object') return recovery;
	return {
		...(recovery.lastDeath === undefined ? {} : { lastDeath: recovery.lastDeath }),
		...(Array.isArray(recovery.lastLostInventory) ? { lastLostInventory: recovery.lastLostInventory.slice(0, 16) } : {}),
		...(Array.isArray(recovery.alreadyHave) ? { alreadyHave: recovery.alreadyHave.slice(-32) } : {}),
		...(Array.isArray(recovery.doNotRedo) ? { doNotRedo: recovery.doNotRedo.slice(0, 24) } : {}),
		...(typeof recovery.facts === 'string' ? { facts: recovery.facts } : {}),
	};
}

function compactLastResultForEvent(lastResult) {
	if (lastResult === null || typeof lastResult !== 'object') return lastResult;
	return {
		...(lastResult.state === undefined ? {} : { state: lastResult.state }),
		...(lastResult.reasonCode === undefined ? {} : { reasonCode: lastResult.reasonCode }),
	};
}

function compactWorldForEvent(world) {
	if (world === null || typeof world !== 'object') return world;
	return {
		...(world.dimension === undefined ? {} : { dimension: world.dimension }),
		...(world.dimensionId === undefined ? {} : { dimensionId: world.dimensionId }),
	};
}

function asArray(value) {
	return Array.isArray(value) ? value : [];
}

export function nativeObservationSignature(observation) {
	return createHash('sha256').update(JSON.stringify(sortFactualValue(nativeActionableObservationProjection(observation)))).digest('hex');
}

function nativeActionableObservationProjection(observation) {
	const projection = { ...observation };
	delete projection.recovery;
	delete projection.options;
	delete projection.failureClass;
	delete projection.continuity;
	delete projection.lastLiveInventory;
	if (observation.player !== undefined) {
		projection.player = { ...observation.player };
		if (observation.player.effects !== undefined) {
			projection.player.effects = observation.player.effects.map(({ duration, ...effect }) => effect);
		}
	}
	if (Array.isArray(observation.items)) projection.items = observation.items.map(({ distance, ...item }) => item);
	if (Array.isArray(observation.entities)) projection.entities = observation.entities.map(({ distance, ...entity }) => entity);
	if (Array.isArray(observation.nearbyContainers)) {
		projection.nearbyContainers = observation.nearbyContainers.map(({ distance, ...container }) => container);
	}
	if (observation.landmarks !== undefined) {
		projection.landmarks = observation.landmarks.map(({ distance, bearing, elevation, ...landmark }) => landmark);
	}
	if (observation.world !== undefined) {
		const { gameTime, dayTime, ...world } = observation.world;
		projection.world = world;
	}
	if (observation.interaction !== undefined) {
		const { useRemainingTicks, attackCooldown, ...interaction } = observation.interaction;
		projection.interaction = { ...interaction, attackReady: attackCooldown >= 1 };
	}
	return projection;
}

function planningTraceId(agentId, goalRevision, lifecycleGeneration, kind) {
	const identity = String(agentId).replace(/[^A-Za-z0-9._:-]/g, '_');
	return `trace-${identity}-${goalRevision}-${lifecycleGeneration}-${kind}`.slice(0, 128);
}

if (process.argv[1] !== undefined && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
	const reporter = new RuntimeErrorReporter();
	runCli(reporter).catch((error) => {
		reporter.report(error, { phase: 'startup' });
		process.exitCode = 1;
	});
}
