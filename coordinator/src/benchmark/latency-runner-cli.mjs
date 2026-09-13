import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { runLatencyMatrix as defaultRunLatencyMatrix } from './latency-runner.mjs';

export const EXIT_CODES = Object.freeze({
	PASSED: 0,
	SKIPPED: 0,
	FAILED: 1,
	REQUIRED_PROVIDER: 2,
	USAGE: 64,
	INTERNAL: 70,
});

const MAX_PATH_BYTES = 4_096;
const MAX_MATRIX_BYTES = 1_048_576;
const MAX_REPLAY_BYTES = 1_048_576;
const MAX_PROMPT_BYTES = 65_536;
const MAX_METADATA_BYTES = 256;
const MAX_RESULT_BYTES = 262_144;
const MAX_PUBLIC_TRIALS = 256;
const DEFAULT_PLANNING_CONCURRENCY = 16;
const DEFAULT_PLANNING_TIMEOUT_MS = 45_000;
const MAX_PLANNING_TIMEOUT_MS = 900_000;

const FLAG_NAMES = new Map([
	['--matrix', 'matrixPath'],
	['--artifact-directory', 'artifactDirectory'],
	['--replay-recordings', 'replayRecordingsPath'],
	['--replay-prompt', 'replayPrompt'],
	['--replay-prompt-file', 'replayPromptFile'],
	['--arm', 'arm'],
	['--run-id', 'runId'],
	['--runId', 'runId'],
	['--source-hash', 'sourceHash'],
	['--sourceHash', 'sourceHash'],
	['--config-hash', 'configHash'],
	['--configHash', 'configHash'],
	['--pairing-key', 'pairingKey'],
	['--pairingKey', 'pairingKey'],
	['--planning-concurrency', 'planningConcurrency'],
	['--planning-timeout-ms', 'planningTimeoutMs'],
	['--planningTimeoutMs', 'planningTimeoutMs'],
	['--trial-id', 'trialId'],
	['--trialId', 'trialId'],
]);

/** Parse the deliberately small CLI surface without touching the filesystem. */
export function parseLatencyRunnerArgs(argv) {
	if (!Array.isArray(argv)) throw cliUsage('arguments must be an array');
	const values = Object.create(null);
	const seen = new Set();
	for (let index = 0; index < argv.length; index += 1) {
		const token = argv[index];
		if (typeof token !== 'string' || !token.startsWith('--')) throw cliUsage('positional arguments are not accepted');
		const equalsIndex = token.indexOf('=');
		const flag = equalsIndex === -1 ? token : token.slice(0, equalsIndex);
		const inlineValue = equalsIndex === -1 ? undefined : token.slice(equalsIndex + 1);
		const field = FLAG_NAMES.get(flag);
		if (!field) throw cliUsage(`unknown argument '${flag}'`);
		if (seen.has(field)) throw cliUsage(`duplicate argument '${flag}'`);
		seen.add(field);
		let value = inlineValue;
		if (value === undefined) {
			if (index + 1 >= argv.length || typeof argv[index + 1] !== 'string' || argv[index + 1].startsWith('--')) throw cliUsage(`${flag} requires a value`);
			value = argv[++index];
		}
		if (value.length === 0) throw cliUsage(`${flag} requires a nonblank value`);
		values[field] = value;
	}
	if (values.matrixPath === undefined) throw cliUsage('--matrix is required');
	if (values.artifactDirectory === undefined) throw cliUsage('--artifact-directory is required');
	const result = {
		matrixPath: absolutePath(values.matrixPath, '--matrix'),
		artifactDirectory: absolutePath(values.artifactDirectory, '--artifact-directory'),
		replayRecordingsPath: values.replayRecordingsPath === undefined ? null : absolutePath(values.replayRecordingsPath, '--replay-recordings'),
		replayPrompt: values.replayPrompt === undefined ? null : boundedPrompt(values.replayPrompt, '--replay-prompt'),
		replayPromptFile: values.replayPromptFile === undefined ? null : absolutePath(values.replayPromptFile, '--replay-prompt-file'),
		arm: values.arm === undefined ? null : boundedMetadata(values.arm, '--arm', true),
		runId: values.runId === undefined ? null : boundedMetadata(values.runId, '--run-id', true),
		sourceHash: values.sourceHash === undefined ? null : boundedMetadata(values.sourceHash, '--source-hash', true),
		configHash: values.configHash === undefined ? null : boundedMetadata(values.configHash, '--config-hash', true),
		pairingKey: values.pairingKey === undefined ? null : boundedMetadata(values.pairingKey, '--pairing-key', false),
		planningConcurrency: values.planningConcurrency === undefined
			? DEFAULT_PLANNING_CONCURRENCY
			: boundedPlanningConcurrency(values.planningConcurrency),
		planningTimeoutMs: values.planningTimeoutMs === undefined ? null : boundedPlanningTimeout(values.planningTimeoutMs),
		trialId: values.trialId === undefined ? null : boundedMetadata(values.trialId, '--trial-id', true),
	};
	if (result.replayPrompt !== null && result.replayPromptFile !== null) throw cliUsage('--replay-prompt and --replay-prompt-file cannot be combined');
	return Object.freeze(result);
}

/**
 * Run the CLI with injectable IO and runner dependencies. The function writes
 * exactly one public JSON line and returns the process exit code.
 */
export async function runLatencyRunnerCli(argv = process.argv.slice(2), dependencies = {}) {
	const output = dependencies.stdout ?? ((value) => process.stdout.write(value));
	const diagnostics = dependencies.stderr ?? ((value) => process.stderr.write(value));
	const fs = dependencies.fs ?? {};
	const read = fs.readFile ?? dependencies.readFile ?? readFile;
	const inspect = fs.stat ?? dependencies.stat ?? stat;
	let args = null;
	let selectedMatrix = null;
	try {
		args = parseLatencyRunnerArgs(argv);
		const matrix = await readJsonFile(args.matrixPath, MAX_MATRIX_BYTES, '--matrix', { read, inspect });
		if (!isPlainRecord(matrix)) throw cliUsage('--matrix must contain a JSON object');
		let replayRecordings;
		if (args.replayRecordingsPath !== null) {
			replayRecordings = await readJsonFile(args.replayRecordingsPath, MAX_REPLAY_BYTES, '--replay-recordings', { read, inspect });
			if (!Array.isArray(replayRecordings) || replayRecordings.length === 0) throw cliUsage('--replay-recordings must contain a non-empty JSON array');
		}
		let replayPrompt = args.replayPrompt;
		if (args.replayPromptFile !== null) replayPrompt = await readTextFile(args.replayPromptFile, MAX_PROMPT_BYTES, '--replay-prompt-file', { read, inspect });
		else if (args.replayPrompt !== null && path.isAbsolute(args.replayPrompt)) replayPrompt = await readPromptArgument(args.replayPrompt, { read, inspect });
		selectedMatrix = selectTrial(matrix, args.trialId);
		const liveConfig = buildLiveProviderOptions(selectedMatrix, args, path.resolve(process.cwd()));
		const runner = dependencies.runLatencyMatrix ?? defaultRunLatencyMatrix;
		if (typeof runner !== 'function') throw cliInternal('runLatencyMatrix dependency must be a function');
		const runnerOptions = {
			matrix: selectedMatrix,
			matrixPath: args.matrixPath,
			artifactDirectory: args.artifactDirectory,
			planningConcurrency: args.planningConcurrency,
			...(publicMetadataContext(args) === null ? {} : { baseContext: publicMetadataContext(args) }),
			...(liveConfig === null ? {} : { liveProviderOptions: liveConfig.options, preflightTimeoutMs: liveConfig.planningTimeoutMs }),
			...(replayRecordings === undefined ? {} : { replayRecordings }),
			...(replayPrompt === null ? {} : { replayPrompt }),
			...(args.arm === null ? {} : { arm: args.arm }),
			...(args.runId === null ? {} : { runId: args.runId }),
			...(args.sourceHash === null ? {} : { sourceHash: args.sourceHash }),
			...(args.configHash === null ? {} : { configHash: args.configHash }),
			...(args.pairingKey === null ? {} : { pairingKey: args.pairingKey }),
		};
		const result = await runner(runnerOptions);
		const publicResult = publicResultFor(result, args, null, selectedMatrix);
		writePublicResult(output, publicResult);
		const status = publicResult.status;
		if (status === 'FAILED') {
			writeDiagnostic(diagnostics, firstFailureCode(result) ?? 'RUN_FAILED');
			return isRequiredProviderError(null, result, selectedMatrix) ? EXIT_CODES.REQUIRED_PROVIDER : EXIT_CODES.FAILED;
		}
		return status === 'SKIPPED' ? EXIT_CODES.SKIPPED : EXIT_CODES.PASSED;
	} catch (error) {
		const code = classifyErrorCode(error);
		const preserved = error?.result;
		const publicResult = publicResultFor(preserved, args, code, selectedMatrix);
		writePublicResult(output, publicResult);
		writeDiagnostic(diagnostics, code);
		if (code === 'CLI_USAGE') return EXIT_CODES.USAGE;
		if (code === 'CLI_INTERNAL') return EXIT_CODES.INTERNAL;
		return isRequiredProviderError(error, preserved, selectedMatrix) ? EXIT_CODES.REQUIRED_PROVIDER : EXIT_CODES.FAILED;
	}
}

/** Conventional entrypoint, kept separate so importing this module is inert. */
export async function main(argv = process.argv.slice(2), dependencies = {}) {
	return runLatencyRunnerCli(argv, dependencies);
}

async function readJsonFile(filePath, maximumBytes, field, dependencies) {
	const text = await readBoundedFile(filePath, maximumBytes, field, dependencies);
	try {
		return JSON.parse(text);
	} catch {
		throw cliUsage(`${field} must contain valid JSON`);
	}
}

async function readTextFile(filePath, maximumBytes, field, dependencies) {
	const text = await readBoundedFile(filePath, maximumBytes, field, dependencies);
	if (text.trim().length === 0) throw cliUsage(`${field} must contain a nonblank prompt`);
	return text;
}

async function readPromptArgument(value, dependencies) {
	try {
		const details = await dependencies.inspect(value);
		if (details?.isFile?.()) return readTextFile(value, MAX_PROMPT_BYTES, '--replay-prompt', dependencies);
		throw cliUsage('--replay-prompt absolute path must name a file');
	} catch (error) {
		if (error?.code === 'CLI_USAGE') throw error;
		// A non-existent absolute value remains a bounded inline prompt. This
		// keeps the flag useful for deterministic fixtures while file paths stay private.
		return value;
	}
}

function selectTrial(matrix, trialId) {
	if (trialId === null) return matrix;
	if (!Array.isArray(matrix?.trials)) throw cliUsage('--trial-id requires a matrix trials array');
	const matches = matrix.trials.filter((trial) => trial?.id === trialId);
	if (matches.length !== 1) throw cliUsage(`--trial-id '${trialId}' must identify exactly one matrix trial`);
	const selected = structuredClone(matrix);
	selected.trials = [structuredClone(matches[0])];
	return selected;
}

function buildLiveProviderOptions(matrix, args, cwd) {
	const liveProfiles = new Map();
	for (const trial of matrix?.trials ?? []) {
		if (trial?.mode !== 'live') continue;
		const profile = trial.providerProfile;
		if (!isPlainRecord(profile) || !['codex', 'kimi'].includes(profile.provider)) throw cliUsage('live trial providerProfile is invalid');
		const normalized = {};
		for (const field of ['model', 'reasoningEffort', 'serviceTier']) {
			if (typeof profile[field] !== 'string' || profile[field].trim().length === 0 || Buffer.byteLength(profile[field], 'utf8') > 128) throw cliUsage(`live provider ${field} is invalid`);
			normalized[field] = profile[field];
		}
		const previous = liveProfiles.get(profile.provider);
		if (previous !== undefined && JSON.stringify(previous) !== JSON.stringify(normalized)) throw cliUsage(`live ${profile.provider} trials must use one exact provider profile per invocation`);
		liveProfiles.set(profile.provider, normalized);
	}
	if (liveProfiles.size === 0) return null;
	const trialBound = liveTrialPlanningTimeout(matrix);
	const planningTimeoutMs = Math.max(1, Math.min(args.planningTimeoutMs ?? DEFAULT_PLANNING_TIMEOUT_MS, trialBound));
	const config = { cwd };
	for (const [provider, profile] of liveProfiles) {
		if (provider === 'codex') {
			config.codex = { cwd, planningTimeoutMs, launchProfile: { ...profile } };
			continue;
		}
		const providerConfig = {
			cwd,
			planningTimeoutMs,
			catalogDiscovery: true,
			models: [profile.model],
			reasoningEfforts: [profile.reasoningEffort],
			modelReasoningEfforts: { [profile.model]: [profile.reasoningEffort] },
		};
		if (provider === 'kimi') providerConfig.executable = 'kimi';
		config[provider] = providerConfig;
	}
	return { planningTimeoutMs, options: { cwd, config, planningTimeoutMs } };
}

function liveTrialPlanningTimeout(matrix) {
	const limits = [];
	for (const trial of matrix?.trials ?? []) {
		if (trial?.mode !== 'live') continue;
		for (const value of [trial.turnBudgetMs, trial.trialBudgetMs]) if (Number.isFinite(value) && value > 0) limits.push(Math.floor(value));
	}
	return Math.max(1, Math.min(...(limits.length === 0 ? [DEFAULT_PLANNING_TIMEOUT_MS] : limits)));
}

async function readBoundedFile(filePath, maximumBytes, field, { read, inspect }) {
	let details;
	try { details = await inspect(filePath); }
	catch { throw cliUsage(`${field} file could not be read`); }
	if (!details?.isFile?.() || !Number.isSafeInteger(details.size) || details.size > maximumBytes) throw cliUsage(`${field} file exceeds the bounded byte limit`);
	let value;
	try { value = await read(filePath); }
	catch { throw cliUsage(`${field} file could not be read`); }
	const bytes = Buffer.byteLength(value);
	if (bytes > maximumBytes) throw cliUsage(`${field} file exceeds the bounded byte limit`);
	return Buffer.isBuffer(value) ? value.toString('utf8') : String(value);
}

function publicResultFor(result, args, errorCode = null, matrix = null) {
	const trials = Array.isArray(result?.trials) ? result.trials : [];
	const matrixTrials = Array.isArray(matrix?.trials) ? matrix.trials : [];
	const failed = errorCode !== null || result?.status === 'FAILED' || trials.some((trial) => ['FAILED', 'TIMED_OUT'].includes(trial?.status));
	const skipped = !failed && (result?.status === 'SKIPPED' || (trials.length > 0 && trials.every((trial) => trial?.status === 'SKIPPED')));
	const status = failed ? 'FAILED' : skipped ? 'SKIPPED' : 'PASSED';
	const publicResult = {
		status,
		...(errorCode ? { error: { code: safeCode(errorCode) } } : {}),
		...(result?.matrix && isPlainRecord(result.matrix) ? { matrix: pickMatrix(result.matrix) } : {}),
		metadata: publicMetadata(args),
		trials: trials.slice(0, MAX_PUBLIC_TRIALS).map((trial) => publicTrial(trial, matrixTrials.find((candidate) => candidate?.id === trial?.trialId))),
		trialsTruncated: trials.length > MAX_PUBLIC_TRIALS,
		cleanup: publicCleanup(result?.cleanup),
		summary: Number.isSafeInteger(result?.summary) && result.summary >= 0 ? result.summary : null,
	};
	return boundPublicOutput(publicResult);
}

function publicTrial(trial, matrixTrial = null) {
	if (!isPlainRecord(trial)) return { status: 'FAILED', error: { code: 'INVALID_TRIAL' } };
	const providerProfile = isPlainRecord(trial.providerProfile) ? {
		provider: boundedPublicText(trial.providerProfile.provider, 128),
		model: boundedPublicText(trial.providerProfile.model, 128),
		reasoningEffort: boundedPublicText(trial.providerProfile.reasoningEffort, 128),
		serviceTier: boundedPublicText(trial.providerProfile.serviceTier, 128),
	} : undefined;
	return {
		trialId: boundedPublicText(trial.trialId, 128),
		repetition: safeIntegerOrNull(trial.repetition),
		scenarioId: boundedPublicText(trial.scenarioId, 128),
		seed: safeIntegerOrNull(trial.seed),
		agentLoad: safeIntegerOrNull(trial.agentLoad),
		mode: boundedPublicText(trial.mode, 32),
		status: normalizeStatus(trial.status),
		providerAvailabilityRequired: matrixTrial?.providerAvailabilityRequired === true || trial.providerAvailabilityRequired === true,
		...(providerProfile ? { providerProfile } : {}),
		...(isPlainRecord(trial.providerIdentity) ? { providerIdentity: { provider: boundedPublicText(trial.providerIdentity.provider, 128), synthetic: trial.providerIdentity.synthetic === true } } : {}),
		...(typeof trial.outcomeHash === 'string' ? { outcomeHash: boundedPublicText(trial.outcomeHash, 128) } : {}),
		...(isPlainRecord(trial.error) ? { error: { code: safeCode(trial.error.code) } } : {}),
		...(isPlainRecord(trial.cleanup) ? { cleanup: publicCleanup(trial.cleanup) } : {}),
		...(isPlainRecord(trial.metrics) ? { metrics: publicMetrics(trial.metrics) } : {}),
		...(isPlainRecord(trial.systemSummary) ? { systemSummary: publicSystemSummary(trial.systemSummary) } : {}),
		timingScope: trial.timingScope === 'full_path' ? 'full_path' : null,
		durationMs: Number.isFinite(trial.durationMs) && trial.durationMs >= 0 ? Math.min(Math.round(trial.durationMs), Number.MAX_SAFE_INTEGER) : null,
		taskDurationMs: Number.isFinite(trial.taskDurationMs) && trial.taskDurationMs >= 0 ? Math.min(Math.round(trial.taskDurationMs), Number.MAX_SAFE_INTEGER) : null,
		setupDurationMs: Number.isFinite(trial.setupDurationMs) && trial.setupDurationMs >= 0 ? Math.min(Math.round(trial.setupDurationMs), Number.MAX_SAFE_INTEGER) : null,
		totalDurationMs: Number.isFinite(trial.totalDurationMs) && trial.totalDurationMs >= 0 ? Math.min(Math.round(trial.totalDurationMs), Number.MAX_SAFE_INTEGER) : null,
		...(isPlainRecord(trial.setupSpansMs) ? { setupSpansMs: numericFields(trial.setupSpansMs, ['providerStart', 'coordinatorStart']) } : {}),
	};
}

function publicMetrics(metrics) {
	const result = isPlainRecord(metrics.result) ? {} : null;
	if (result !== null) {
		for (const key of [
			'goalWallTimestampMs', 'goalVirtualTimestampMs', 'initialObservationWallTimestampMs', 'initialObservationVirtualTimestampMs',
			'firstActionCommandAcceptanceWallLatencyMs', 'firstActionCommandAcceptanceVirtualLatencyMs',
			'firstAuthoritativePhysicalDisplacementWallLatencyMs', 'firstAuthoritativePhysicalDisplacementVirtualLatencyMs',
			'taskCompletionWallDurationMs', 'taskCompletionGoalWallDurationMs', 'taskCompletionVirtualDurationMs', 'totalVirtualWorldDurationMs',
		]) if (Number.isFinite(metrics.result[key])) result[key] = metrics.result[key];
		for (const key of ['providerPlanningWait', 'tick', 'planningIdle']) {
			const source = metrics.result[key];
			if (!isPlainRecord(source)) continue;
			const projected = numericFields(source, metricNumericKeys(key));
			if (Object.keys(projected).length > 0) result[key] = projected;
		}
	}
	return {
		...(Number.isSafeInteger(metrics.version) ? { version: metrics.version } : {}),
		...(result !== null ? { result } : {}),
	};
}

function publicSystemSummary(summary) {
	const output = {};
	for (const key of ['sampleCount', 'droppedSamples', 'maxSamples']) if (Number.isSafeInteger(summary[key]) && summary[key] >= 0) output[key] = summary[key];
	const summarySeries = {
		cpu: ['userMs', 'systemMs', 'totalMs'],
		memory: ['rssBytes', 'heapUsedBytes'],
		eventLoopDelay: ['meanMs', 'p95Ms', 'maxMs'],
		scheduler: ['active', 'pending'],
	};
	for (const key of Object.keys(summarySeries)) {
		if (!isPlainRecord(summary[key])) continue;
		const projected = {};
		for (const seriesName of summarySeries[key]) {
			if (!isPlainRecord(summary[key][seriesName])) continue;
			const values = numericFields(summary[key][seriesName], ['min', 'max', 'p50', 'p95', 'p99']);
			if (Object.keys(values).length > 0) projected[seriesName] = values;
		}
		if (Object.keys(projected).length > 0) output[key] = projected;
	}
	for (const [key, fields] of Object.entries({
		cpuDelta: ['userMs', 'systemMs', 'totalMs'],
		memoryDelta: ['rssBytes', 'heapUsedBytes'],
		memoryPeak: ['rssBytes', 'heapUsedBytes'],
	})) {
		if (!isPlainRecord(summary[key])) continue;
		const projected = numericFields(summary[key], fields);
		if (Object.keys(projected).length > 0) output[key] = projected;
	}
	if (isPlainRecord(summary.childProcessCount)) output.childProcessCount = numericFields(summary.childProcessCount, ['min', 'max', 'p50', 'p95', 'p99']);
	return output;
}

function metricNumericKeys(key) {
	if (key === 'tick') return ['count', 'p50Ms', 'p95Ms', 'p99Ms', 'maxMs', 'over50MsCount'];
	if (key === 'planningIdle') return ['localActiveWallDurationMs', 'planningIdleWallDurationMs'];
	return ['count', 'schedulerWaitWallP50Ms', 'schedulerWaitWallP95Ms', 'schedulerWaitWallP99Ms', 'planningWaitWallP50Ms', 'planningWaitWallP95Ms', 'planningWaitWallP99Ms', 'providerWaitWallP50Ms', 'providerWaitWallP95Ms', 'providerWaitWallP99Ms'];
}

function numericFields(value, keys) {
	const output = {};
	for (const key of keys) if (Number.isFinite(value[key])) output[key] = value[key];
	return output;
}

function publicMetadata(args) {
	return {
		...(args?.arm ? { arm: args.arm } : {}),
		...(args?.runId ? { runId: args.runId } : {}),
		...(args?.sourceHash ? { sourceHash: args.sourceHash } : {}),
		...(args?.configHash ? { configHash: args.configHash } : {}),
		...(args?.trialId ? { trialId: args.trialId } : {}),
		planningConcurrency: args?.planningConcurrency ?? DEFAULT_PLANNING_CONCURRENCY,
	};
}

function publicMetadataContext(args) {
	const context = {};
	for (const key of ['arm', 'runId', 'sourceHash', 'configHash', 'pairingKey']) if (args?.[key]) context[key] = args[key];
	return Object.keys(context).length === 0 ? null : context;
}

function publicCleanup(cleanup) {
	if (!isPlainRecord(cleanup)) return { ok: false, activeActions: null, listeners: null };
	return {
		ok: cleanup.ok === true,
		activeActions: safeIntegerOrNull(cleanup.activeActions),
		listeners: safeIntegerOrNull(cleanup.listeners),
	};
}

function pickMatrix(matrix) {
	return {
		...(Number.isSafeInteger(matrix.version) ? { version: matrix.version } : {}),
		...(typeof matrix.benchmarkVersion === 'string' ? { benchmarkVersion: boundedPublicText(matrix.benchmarkVersion, 128) } : {}),
		...(Number.isSafeInteger(matrix.protocolVersion) ? { protocolVersion: matrix.protocolVersion } : {}),
	};
}

function boundPublicOutput(value) {
	const serialized = JSON.stringify(value);
	if (Buffer.byteLength(serialized, 'utf8') <= MAX_RESULT_BYTES) return value;
	return {
		status: value.status,
		...(value.error ? { error: value.error } : {}),
		metadata: value.metadata,
		trials: value.trials.slice(0, 16),
		trialsTruncated: true,
		cleanup: value.cleanup,
		summary: value.summary,
		bounded: true,
	};
}

function writePublicResult(write, value) {
	write(`${JSON.stringify(value)}\n`);
}

function writeDiagnostic(write, code) {
	write(`latency-runner: ${safeCode(code)}\n`);
}

function firstFailureCode(result) {
	const trial = result?.trials?.find((entry) => entry?.status === 'FAILED' || entry?.status === 'TIMED_OUT');
	return trial?.error?.code ?? (result?.status === 'FAILED' ? 'RUN_FAILED' : null);
}

function classifyErrorCode(error) {
	if (error?.code === 'CLI_USAGE' || error?.code === 'CLI_INTERNAL') return error.code;
	return safeCode(error?.code) === 'UNKNOWN' ? 'RUN_FAILED' : safeCode(error.code);
}

function isRequiredProviderError(error, result, matrix = null) {
	if (error?.code === 'PROVIDER_UNAVAILABLE' || /(?:required[._-]?provider|provider[._-]?required)/i.test(String(error?.code ?? ''))) return true;
	return result?.trials?.some((trial) => {
		const matrixTrial = matrix?.trials?.find?.((candidate) => candidate?.id === trial?.trialId);
		return (trial?.providerAvailabilityRequired === true || matrixTrial?.providerAvailabilityRequired === true) && trial?.error?.code === 'PROVIDER_UNAVAILABLE';
	}) === true;
}

function normalizeStatus(value) {
	return ['PASSED', 'SKIPPED', 'FAILED', 'TIMED_OUT'].includes(value) ? value : 'FAILED';
}

function safeIntegerOrNull(value) { return Number.isSafeInteger(value) ? value : null; }

function boundedPublicText(value, maximum) { return typeof value === 'string' ? value.slice(0, maximum) : null; }

function safeCode(value) {
	const code = typeof value === 'string' ? value : '';
	return /^[A-Z0-9][A-Z0-9_:-]{0,63}$/.test(code) ? code : 'UNKNOWN';
}

function absolutePath(value, field) {
	if (typeof value !== 'string' || value.trim().length === 0 || !path.isAbsolute(value)) throw cliUsage(`${field} must be an absolute path`);
	if (Buffer.byteLength(value, 'utf8') > MAX_PATH_BYTES) throw cliUsage(`${field} path is too long`);
	return path.normalize(value);
}

function boundedPrompt(value, field) {
	if (typeof value !== 'string' || value.trim().length === 0 || Buffer.byteLength(value, 'utf8') > MAX_PROMPT_BYTES) throw cliUsage(`${field} must be a bounded nonblank prompt`);
	return value;
}

function boundedMetadata(value, field, safeCharacters) {
	if (typeof value !== 'string' || value.trim().length === 0 || Buffer.byteLength(value, 'utf8') > MAX_METADATA_BYTES) throw cliUsage(`${field} must be bounded and nonblank`);
	if (safeCharacters && !/^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(value)) throw cliUsage(`${field} contains unsupported characters`);
	return value;
}

function boundedPlanningConcurrency(value) {
	if (!/^[0-9]+$/.test(value)) throw cliUsage('--planning-concurrency must be an integer from 1 to 16');
	const parsed = Number(value);
	if (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > 16) throw cliUsage('--planning-concurrency must be an integer from 1 to 16');
	return parsed;
}

function boundedPlanningTimeout(value) {
	if (!/^[0-9]+$/.test(value)) throw cliUsage(`--planning-timeout-ms must be an integer from 1 to ${MAX_PLANNING_TIMEOUT_MS}`);
	const parsed = Number(value);
	if (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > MAX_PLANNING_TIMEOUT_MS) throw cliUsage(`--planning-timeout-ms must be an integer from 1 to ${MAX_PLANNING_TIMEOUT_MS}`);
	return parsed;
}

function isPlainRecord(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
	const prototype = Object.getPrototypeOf(value);
	return prototype === Object.prototype || prototype === null;
}

function cliUsage(message) { return Object.assign(new Error(message), { code: 'CLI_USAGE' }); }

function cliInternal(message) { return Object.assign(new Error(message), { code: 'CLI_INTERNAL' }); }

function isEntryPoint() {
	if (!process.argv[1]) return false;
	try { return fileURLToPath(import.meta.url) === path.resolve(process.argv[1]); }
	catch { return false; }
}

if (isEntryPoint()) {
	main().then((code) => { process.exitCode = code; });
}
