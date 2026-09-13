import path from 'node:path';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { sanitizeDiagnosticErrorMessage, sanitizeDiagnosticText } from './diagnostic-sanitizer.mjs';
import { runNativeToolCli } from './native-tool-cli-boundary.mjs';

async function main({ progress }) {
const [baselineService, currentService, outputPath, repetitionsText = '10'] = process.argv.slice(2);
if (!baselineService || !currentService || !outputPath) throw new TypeError('baseline service, current service, and output path are required');
const repetitions = Number.parseInt(repetitionsText, 10);
if (!Number.isSafeInteger(repetitions) || repetitions < 2 || repetitions > 30) throw new TypeError('repetitions must be between 2 and 30');

const trialScript = fileURLToPath(new URL('./native-tool-ab-trial.mjs', import.meta.url));
const rows = [];
for (let trial = 1; trial <= repetitions; trial += 1) {
	const order = trial % 2 === 1 ? ['baseline', 'current'] : ['current', 'baseline'];
	for (const variant of order) {
		const modulePath = variant === 'baseline' ? baselineService : currentService;
		const row = await runTrial(modulePath, variant, trial);
		rows.push(row);
		progress({ progress: rows.length, total: repetitions * 2, variant, trial, trialStatus: row.status, warmDmFirstMs: row.warmDm?.firstToolMs ?? null });
	}
}

const metrics = {
	initializationMs: (row) => row.initializationMs,
	coldDmFirstMs: (row) => row.coldDm.firstToolMs,
	coldDmTotalMs: (row) => row.coldDm.totalMs,
	warmDmFirstMs: (row) => row.warmDm.firstToolMs,
	warmDmTotalMs: (row) => row.warmDm.totalMs,
	moveFirstMs: (row) => row.moveMine.firstToolMs,
	mineMs: (row) => row.moveMine.lastToolMs,
	moveMineTotalMs: (row) => row.moveMine.totalMs,
	craftFirstMs: (row) => row.craft.firstToolMs,
	craftTotalMs: (row) => row.craft.totalMs,
};
const passed = rows.filter((row) => row.status === 'PASSED');
const summary = Object.fromEntries(['baseline', 'current'].map((variant) => {
	const variantRows = passed.filter((row) => row.variant === variant);
	return [variant, Object.fromEntries(Object.entries(metrics).map(([name, pick]) => [name, summarize(variantRows.map(pick))]))];
}));
const paired = Object.fromEntries(Object.entries(metrics).map(([name, pick]) => {
	const deltas = [];
	for (let trial = 1; trial <= repetitions; trial += 1) {
		const baseline = passed.find((row) => row.variant === 'baseline' && row.trial === trial);
		const current = passed.find((row) => row.variant === 'current' && row.trial === trial);
		if (baseline && current) deltas.push(pick(current) - pick(baseline));
	}
	return [name, summarize(deltas)];
}));
const result = {
	schemaVersion: 1,
	capturedAt: new Date().toISOString(),
	design: { repetitions, ordering: 'alternating paired baseline/current', model: 'gpt-5.6-luna', reasoningEffort: 'xhigh', serviceTier: 'fast' },
	passed: passed.length,
	failed: rows.length - passed.length,
	summary,
	pairedCurrentMinusBaseline: paired,
	rows,
};
const resolvedOutputPath = path.resolve(outputPath);
await mkdir(path.dirname(resolvedOutputPath), { recursive: true });
await writeFile(resolvedOutputPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
return { status: result.failed === 0 ? 'PASSED' : 'FAILED', outputPath: publicArtifactName(resolvedOutputPath), summary, paired };

function runTrial(modulePath, variant, trial) {
	return new Promise((resolve, reject) => {
		const child = spawn(process.execPath, [trialScript, modulePath, variant, String(trial), 'gpt-5.6-luna', 'xhigh', 'fast'], {
			cwd: path.dirname(trialScript),
			stdio: ['ignore', 'pipe', 'pipe'],
			windowsHide: true,
		});
		let stdout = '';
		let stderr = '';
		const timeout = setTimeout(() => child.kill(), 360_000);
		child.stdout.on('data', (chunk) => { stdout = boundedAppend(stdout, chunk, 64 * 1_024); });
		child.stderr.on('data', (chunk) => { stderr = boundedAppend(stderr, chunk, 16 * 1_024); });
		child.on('error', (error) => { clearTimeout(timeout); reject(error); });
		child.on('close', (code) => {
			clearTimeout(timeout);
			try {
				const lines = stdout.trim().split(/\r?\n/).filter(Boolean);
				const row = JSON.parse(lines.at(-1));
				if (code !== 0 && row.status !== 'FAILED') throw new Error(`trial exited ${code}: ${sanitizeDiagnosticText(stderr, { maxBytes: 1_024 })}`);
				resolve(row);
			} catch (error) {
				reject(new Error(`Could not parse ${variant} trial ${trial}: ${sanitizeDiagnosticErrorMessage(error, { maxBytes: 512 })}; stderr=${sanitizeDiagnosticText(stderr, { maxBytes: 1_024 })}`));
			}
		});
	});
}

function summarize(values) {
	const finite = values.filter(Number.isFinite).sort((left, right) => left - right);
	if (finite.length === 0) return { count: 0, p50: null, p95: null, min: null, max: null };
	return {
		count: finite.length,
		p50: percentile(finite, 0.5),
		p95: percentile(finite, 0.95),
		min: finite[0],
		max: finite.at(-1),
	};
}

function percentile(sorted, fraction) {
	return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}

function boundedAppend(current, chunk, limit) {
	const next = current + chunk.toString('utf8');
	return next.length <= limit ? next : next.slice(-limit);
}

function publicArtifactName(outputPath) {
	const basename = path.basename(outputPath);
	if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(basename)) return '[REDACTED]';
	if (/(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|password|token|credential|oauth)/i.test(basename)) return '[REDACTED]';
	return basename;
}
}

process.exitCode = await runNativeToolCli(main);
