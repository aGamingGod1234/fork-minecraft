const DEFAULT_POLICY = Object.freeze({
	schemaVersion: 1,
	minimumSpeedup: 2,
	requiredLoads: Object.freeze([1, 8, 16]),
	requiredSessionStates: Object.freeze(['cold', 'warm']),
	minimumSamplesPerCell: 5,
	maximumTickP95Ms: 50,
	minimumFactualSuccessRate: 1,
	requireLiveProviderEvidence: true,
	requireInstrumentationComparison: true,
	maximumInstrumentationP95Ratio: 1.05,
});
const MAX_EVIDENCE_TRIALS = 10_000;

export function normalizeLatencyAcceptancePolicy(value = {}) {
	if (!isRecord(value)) throw new TypeError('latency acceptance policy must be an object');
	const minimumSpeedup = positive(value.minimumSpeedup ?? DEFAULT_POLICY.minimumSpeedup, 'minimumSpeedup');
	const requiredLoads = integerList(value.requiredLoads ?? DEFAULT_POLICY.requiredLoads, 'requiredLoads');
	const requiredSessionStates = textList(value.requiredSessionStates ?? DEFAULT_POLICY.requiredSessionStates, 'requiredSessionStates');
	const minimumSamplesPerCell = positiveInteger(value.minimumSamplesPerCell ?? DEFAULT_POLICY.minimumSamplesPerCell, 'minimumSamplesPerCell');
	const maximumTickP95Ms = positive(value.maximumTickP95Ms ?? DEFAULT_POLICY.maximumTickP95Ms, 'maximumTickP95Ms');
	const minimumFactualSuccessRate = unit(value.minimumFactualSuccessRate ?? DEFAULT_POLICY.minimumFactualSuccessRate, 'minimumFactualSuccessRate');
	const requireLiveProviderEvidence = boolean(value.requireLiveProviderEvidence ?? DEFAULT_POLICY.requireLiveProviderEvidence, 'requireLiveProviderEvidence');
	const requireInstrumentationComparison = boolean(value.requireInstrumentationComparison ?? DEFAULT_POLICY.requireInstrumentationComparison, 'requireInstrumentationComparison');
	const maximumInstrumentationP95Ratio = positive(value.maximumInstrumentationP95Ratio ?? DEFAULT_POLICY.maximumInstrumentationP95Ratio, 'maximumInstrumentationP95Ratio');
	if (minimumSpeedup <= 1) throw new TypeError('minimumSpeedup must be greater than 1');
	return Object.freeze({ schemaVersion: 1, minimumSpeedup, maximumP95Ratio: 1 / minimumSpeedup, requiredLoads: Object.freeze(requiredLoads), requiredSessionStates: Object.freeze(requiredSessionStates), minimumSamplesPerCell, maximumTickP95Ms, minimumFactualSuccessRate, requireLiveProviderEvidence, requireInstrumentationComparison, maximumInstrumentationP95Ratio });
}

export function evaluateLatencyAcceptance({ baseline, optimized, policy = {}, instrumentationComparison = null } = {}) {
	const normalizedPolicy = normalizeLatencyAcceptancePolicy(policy);
	const baselineTrials = extractTrials(baseline, 'baseline');
	const optimizedTrials = extractTrials(optimized, 'optimized');
	const baselineByKey = indexUniqueTrials(baselineTrials, 'baseline');
	const optimizedByKey = indexUniqueTrials(optimizedTrials, 'optimized');
	const checks = [];
	for (const sessionState of normalizedPolicy.requiredSessionStates) {
		for (const agentLoad of normalizedPolicy.requiredLoads) {
			const id = `${sessionState}/load-${agentLoad}`;
			const baseTrials = baselineTrials.filter((trial) => trial.sessionState === sessionState && trial.agentLoad === agentLoad);
			const optimizedCellTrials = optimizedTrials.filter((trial) => trial.sessionState === sessionState && trial.agentLoad === agentLoad);
			const pairing = compareCellPairing(baseTrials, optimizedCellTrials, baselineByKey, optimizedByKey);
			checks.push(check('WORKLOAD_PAIRING', id, pairing.missingBaseline.length === 0 && pairing.missingOptimized.length === 0 && pairing.mismatched.length === 0, pairing));
			const baseCell = summarizeCell(baseTrials);
			const optimizedCell = summarizeCell(optimizedCellTrials);
			checks.push(check('SAMPLE_COUNT', id, baseCell.sampleCount >= normalizedPolicy.minimumSamplesPerCell && optimizedCell.sampleCount >= normalizedPolicy.minimumSamplesPerCell, { baseline: baseCell.sampleCount, optimized: optimizedCell.sampleCount, requiredPerArm: normalizedPolicy.minimumSamplesPerCell }));
			checks.push(check('REQUIRED_SPAN_EVIDENCE', id,
				baseCell.tickSampleCount === baseCell.sampleCount && optimizedCell.tickSampleCount === optimizedCell.sampleCount
				&& baseCell.spanSampleCounts.action === baseCell.sampleCount && optimizedCell.spanSampleCounts.action === optimizedCell.sampleCount
				&& baseCell.spanSampleCounts.voice === baseCell.sampleCount && optimizedCell.spanSampleCounts.voice === optimizedCell.sampleCount,
				{ baseline: { samples: baseCell.sampleCount, tick: baseCell.tickSampleCount, action: baseCell.spanSampleCounts.action, voice: baseCell.spanSampleCounts.voice }, optimized: { samples: optimizedCell.sampleCount, tick: optimizedCell.tickSampleCount, action: optimizedCell.spanSampleCounts.action, voice: optimizedCell.spanSampleCounts.voice } }));
			const p95Ratio = ratio(optimizedCell.latencyP95Ms, baseCell.latencyP95Ms);
			checks.push(check('P95_SPEEDUP', id, p95Ratio !== null && p95Ratio <= normalizedPolicy.maximumP95Ratio, { baselineP95Ms: baseCell.latencyP95Ms, optimizedP95Ms: optimizedCell.latencyP95Ms, observedRatio: p95Ratio, maximumRatio: normalizedPolicy.maximumP95Ratio }));
			const factualParity = optimizedCell.factualSuccessRate !== null && baseCell.factualSuccessRate !== null && optimizedCell.factualSuccessRate >= baseCell.factualSuccessRate && optimizedCell.factualSuccessRate >= normalizedPolicy.minimumFactualSuccessRate;
			checks.push(check('FACTUAL_SUCCESS_PARITY', id, factualParity, { baselineRate: baseCell.factualSuccessRate, optimizedRate: optimizedCell.factualSuccessRate, minimumRate: normalizedPolicy.minimumFactualSuccessRate }));
			checks.push(check('TICK_BUDGET', id, optimizedCell.tickP95Ms !== null && optimizedCell.tickP95Ms <= normalizedPolicy.maximumTickP95Ms, { optimizedTickP95Ms: optimizedCell.tickP95Ms, maximumTickP95Ms: normalizedPolicy.maximumTickP95Ms }));
			for (const span of ['action', 'voice']) {
				const baselineP95Ms = baseCell.spanP95Ms[span];
				const optimizedP95Ms = optimizedCell.spanP95Ms[span];
				const spanRatio = ratio(optimizedP95Ms, baselineP95Ms);
				checks.push(check(`${span.toUpperCase()}_SPAN`, id, spanRatio !== null && spanRatio <= normalizedPolicy.maximumP95Ratio, { baselineP95Ms, optimizedP95Ms, observedRatio: spanRatio, maximumRatio: normalizedPolicy.maximumP95Ratio }));
			}
		}
	}
	const allRequired = [...baselineTrials, ...optimizedTrials].filter((trial) => normalizedPolicy.requiredLoads.includes(trial.agentLoad) && normalizedPolicy.requiredSessionStates.includes(trial.sessionState));
	if (normalizedPolicy.requireLiveProviderEvidence) checks.push(check('LIVE_PROVIDER_EVIDENCE', 'all', allRequired.length > 0 && allRequired.every((trial) => trial.synthetic === false && trial.mode === 'live'), { trialCount: allRequired.length, nonLiveCount: allRequired.filter((trial) => trial.synthetic !== false || trial.mode !== 'live').length }));
	if (normalizedPolicy.requireInstrumentationComparison) {
		const comparisonChecks = Array.isArray(instrumentationComparison?.checks) ? instrumentationComparison.checks : [];
		const overhead = comparisonChecks.find((entry) => entry?.code === 'INSTRUMENTATION_P95_OVERHEAD');
		const parity = comparisonChecks.find((entry) => entry?.code === 'INSTRUMENTATION_BEHAVIOR_PARITY');
		const samples = comparisonChecks.find((entry) => entry?.code === 'INSTRUMENTATION_SAMPLE_COUNT');
		checks.push(check('INSTRUMENTATION_COMPARISON', 'all', instrumentationComparison?.status === 'PASSED' && overhead?.status === 'PASSED' && Number.isFinite(overhead?.observedRatio) && overhead.observedRatio <= normalizedPolicy.maximumInstrumentationP95Ratio && parity?.status === 'PASSED' && samples?.status === 'PASSED', { status: instrumentationComparison?.status ?? null, observedP95Ratio: overhead?.observedRatio ?? null, maximumP95Ratio: normalizedPolicy.maximumInstrumentationP95Ratio }));
	}
	const failed = checks.filter((entry) => entry.status === 'FAILED');
	return Object.freeze({ schemaVersion: 1, status: failed.length === 0 ? 'PASSED' : 'FAILED', claimCertified: failed.length === 0, claim: { minimumSpeedup: normalizedPolicy.minimumSpeedup }, policy: normalizedPolicy, summary: { checkCount: checks.length, failedCount: failed.length }, checks: checks.map(Object.freeze) });
}

function extractTrials(value, label) {
	if (!isRecord(value)) throw new TypeError(`${label} evidence must be an object`);
	const source = Array.isArray(value.trials) ? value.trials : Array.isArray(value.results) ? value.results.map((entry) => entry?.result?.trial ?? entry?.trial ?? entry?.result ?? entry) : null;
	if (!Array.isArray(source)) throw new TypeError(`${label} evidence must contain trials or results`);
	if (source.length > MAX_EVIDENCE_TRIALS) throw new RangeError(`${label} evidence exceeds ${MAX_EVIDENCE_TRIALS} trials`);
	return source.map((trial, index) => normalizeEvidenceTrial(trial, `${label}[${index}]`));
}

function normalizeEvidenceTrial(value, label) {
	if (!isRecord(value)) throw new TypeError(`${label} must be an object`);
	const trialId = boundedText(value.trialId, `${label}.trialId`);
	const repetition = positiveInteger(value.repetition, `${label}.repetition`);
	const scenarioId = boundedText(value.scenarioId, `${label}.scenarioId`);
	const seed = safeInteger(value.seed, `${label}.seed`);
	const agentLoad = positiveInteger(value.agentLoad ?? value.rosterSize, `${label}.agentLoad`);
	const sessionState = String(value.sessionState ?? '').trim().toLowerCase();
	if (!['cold', 'warm'].includes(sessionState)) throw new TypeError(`${label}.sessionState must be cold or warm`);
	const mode = String(value.mode ?? '').trim().toLowerCase();
	if (mode !== 'live') throw new TypeError(`${label}.mode must be live claim evidence`);
	if (value.timingScope !== 'full_path') throw new TypeError(`${label}.timingScope must be full_path`);
	const providerProfile = normalizeProviderProfile(value.providerProfile, `${label}.providerProfile`);
	const evidenceSource = boundedText(value.evidenceSource, `${label}.evidenceSource`);
	const workloadConfigHash = boundedText(value.workloadConfigHash, `${label}.workloadConfigHash`);
	const sourceHash = boundedText(value.sourceHash, `${label}.sourceHash`);
	const status = String(value.status ?? '').toUpperCase();
	const factualSuccess = value.factualSuccess === true || value.correctness?.factualSuccess === true || value.debug?.scenarioPassed === true;
	const latencyMs = finite(value.latencyMs ?? value.durationMs ?? value.metrics?.durationMs);
	const tickP95Ms = finite(value.tickP95Ms ?? value.metrics?.result?.tick?.p95Ms ?? value.resources?.minecraftTick?.p95);
	const actionMs = finite(value.spans?.actionMs ?? value.actionSpanMs ?? value.metrics?.result?.firstActionCommandAcceptanceGoalWallLatencyMs);
	const voiceMs = finite(value.spans?.voiceMs ?? value.voiceFirstAudioMs ?? value.metrics?.latencyMs?.voiceFirstAudio?.p95);
	const synthetic = typeof value.synthetic === 'boolean' ? value.synthetic : value.providerIdentity?.synthetic;
	return { trialId, repetition, scenarioId, seed, agentLoad, sessionState, mode, providerProfile, evidenceSource, workloadConfigHash, sourceHash, status, factualSuccess, latencyMs, tickP95Ms, spanMs: { action: actionMs, voice: voiceMs }, synthetic };
}

function indexUniqueTrials(trials, label) {
	const indexed = new Map();
	for (const trial of trials) {
		const key = trialKey(trial);
		if (indexed.has(key)) throw new TypeError(`${label} evidence contains duplicate repetition '${printableKey(key)}'`);
		indexed.set(key, trial);
	}
	return indexed;
}

function compareCellPairing(baseline, optimized, baselineByKey, optimizedByKey) {
	const baselineKeys = new Set(baseline.map(trialKey));
	const optimizedKeys = new Set(optimized.map(trialKey));
	const missingBaseline = [...optimizedKeys].filter((key) => !baselineKeys.has(key)).map(printableKey);
	const missingOptimized = [...baselineKeys].filter((key) => !optimizedKeys.has(key)).map(printableKey);
	const mismatched = [...baselineKeys].filter((key) => optimizedKeys.has(key) && workloadFingerprint(baselineByKey.get(key)) !== workloadFingerprint(optimizedByKey.get(key))).map(printableKey);
	return { baselinePairs: baselineKeys.size, optimizedPairs: optimizedKeys.size, missingBaseline, missingOptimized, mismatched };
}

function workloadFingerprint(trial) {
	return JSON.stringify({ trialId: trial.trialId, repetition: trial.repetition, scenarioId: trial.scenarioId, seed: trial.seed, agentLoad: trial.agentLoad, sessionState: trial.sessionState, mode: trial.mode, providerProfile: trial.providerProfile, evidenceSource: trial.evidenceSource, workloadConfigHash: trial.workloadConfigHash });
}

function summarizeCell(trials) {
	const completed = trials.filter((trial) => trial.status === 'PASSED' && trial.latencyMs !== null);
	return {
		sampleCount: completed.length,
		latencyP95Ms: percentile(completed.map((trial) => trial.latencyMs)),
		factualSuccessRate: trials.length === 0 ? null : trials.filter((trial) => trial.status === 'PASSED' && trial.factualSuccess).length / trials.length,
		tickSampleCount: completed.filter((trial) => trial.tickP95Ms !== null).length,
		tickP95Ms: percentile(completed.map((trial) => trial.tickP95Ms).filter(Number.isFinite)),
		spanSampleCounts: { action: completed.filter((trial) => trial.spanMs.action !== null).length, voice: completed.filter((trial) => trial.spanMs.voice !== null).length },
		spanP95Ms: { action: percentile(completed.map((trial) => trial.spanMs.action).filter(Number.isFinite)), voice: percentile(completed.map((trial) => trial.spanMs.voice).filter(Number.isFinite)) },
	};
}

function normalizeProviderProfile(value, label) {
	if (!isRecord(value)) throw new TypeError(`${label} must be an object`);
	return Object.freeze({ provider: boundedText(value.provider, `${label}.provider`).toLowerCase(), model: boundedText(value.model, `${label}.model`), reasoningEffort: boundedText(value.reasoningEffort, `${label}.reasoningEffort`), serviceTier: boundedText(value.serviceTier, `${label}.serviceTier`) });
}

function trialKey(trial) { return `${trial.sessionState}\u0000${trial.agentLoad}\u0000${trial.trialId}\u0000${trial.repetition}`; }
function printableKey(key) { return key.split('\u0000').join('/'); }
function check(code, cell, passed, evidence) { return { code, cell, status: passed ? 'PASSED' : 'FAILED', evidence }; }
function percentile(values) { const sorted = [...values].sort((a, b) => a - b); return sorted.length === 0 ? null : sorted[Math.max(0, Math.ceil(sorted.length * 0.95) - 1)]; }
function ratio(numerator, denominator) { return numerator === null || denominator === null || denominator <= 0 ? null : numerator / denominator; }
function finite(value) { return Number.isFinite(value) && value >= 0 ? Number(value) : null; }
function boundedText(value, label) { if (typeof value !== 'string' || value.trim() === '' || value.length > 256) throw new TypeError(`${label} must be a bounded nonblank string`); return value.trim(); }
function safeInteger(value, label) { if (!Number.isSafeInteger(value)) throw new TypeError(`${label} must be a safe integer`); return value; }
function positive(value, label) { if (!Number.isFinite(value) || value <= 0) throw new TypeError(`${label} must be positive`); return Number(value); }
function unit(value, label) { if (!Number.isFinite(value) || value < 0 || value > 1) throw new TypeError(`${label} must be between 0 and 1`); return Number(value); }
function positiveInteger(value, label) { if (!Number.isSafeInteger(value) || value < 1) throw new TypeError(`${label} must be a positive integer`); return value; }
function integerList(value, label) { if (!Array.isArray(value) || value.length === 0) throw new TypeError(`${label} must be a non-empty array`); return [...new Set(value.map((entry) => positiveInteger(entry, label)))]; }
function textList(value, label) { if (!Array.isArray(value) || value.length === 0 || value.some((entry) => typeof entry !== 'string' || entry.trim() === '')) throw new TypeError(`${label} must be a non-empty string array`); return [...new Set(value.map((entry) => entry.trim().toLowerCase()))]; }
function boolean(value, label) { if (typeof value !== 'boolean') throw new TypeError(`${label} must be boolean`); return value; }
function isRecord(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
