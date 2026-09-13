import fs from 'node:fs';
import path from 'node:path';

const values = new Map();
const argv = process.argv.slice(2);
const switches = new Set(['--retry-once', '--drift-source']);
for (let index = 0; index < argv.length; index += 1) {
	const key = argv[index];
	if (!key.startsWith('--')) {
		process.stderr.write(`invalid fixture argument: ${key}\n`);
		process.exit(64);
	}
	if (switches.has(key)) { values.set(key, 'true'); continue; }
	if (index + 1 >= argv.length || argv[index + 1].startsWith('--')) {
		process.stderr.write(`missing value for ${key}\n`);
		process.exit(64);
	}
	values.set(key, argv[++index]);
}

const get = (key, required = true) => {
	const value = values.get(key);
	if (required && (!value || value.trim().length === 0)) throw new Error(`missing ${key}`);
	return value ?? '';
};
const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const attemptPath = get('--artifact-directory');
const trialId = get('--trial-id');
const arm = get('--arm');
const expectedArm = process.env.FIXTURE_EXPECTED_ARM ?? '';
if (expectedArm && expectedArm !== arm) {
	process.stderr.write(`wrong fixture runner for ${arm}\n`);
	process.exit(65);
}
const runId = get('--run-id');
const sourceHash = get('--source-hash');
const configHash = get('--config-hash');
const planningConcurrency = Number(get('--planning-concurrency'));
const fixtureMode = get('--fixture-mode', false);
const retryOnce = values.has('--retry-once');
const sharedStatePath = get('--shared-state-path', false);
const label = get('--label', false);

function lockPathFor(filePath) { return `${filePath}.lock`; }

async function withActiveState(action) {
	if (!sharedStatePath) return action();
	fs.mkdirSync(path.dirname(sharedStatePath), { recursive: true });
	const lockPath = lockPathFor(sharedStatePath);
	for (;;) {
		try { fs.mkdirSync(lockPath); break; } catch (error) {
			if (error.code !== 'EEXIST') throw error;
			await sleep(5);
		}
	}
	let active = 0;
	try {
		try { active = Number(fs.readFileSync(sharedStatePath, 'utf8').trim() || '0'); } catch { active = 0; }
		active += 1;
		fs.writeFileSync(sharedStatePath, String(active));
		let maximum = 0;
		try { maximum = Number(fs.readFileSync(`${sharedStatePath}.max`, 'utf8').split('=')[1] || '0'); } catch { maximum = 0; }
		if (active > maximum) fs.writeFileSync(`${sharedStatePath}.max`, `maxActive=${active}`);
	} finally {
		fs.rmdirSync(lockPath);
	}
	try { return await action(); }
	finally {
		for (;;) {
			try { fs.mkdirSync(lockPath); break; } catch (error) {
				if (error.code !== 'EEXIST') throw error;
				await sleep(5);
			}
		}
		try {
			let current = 0;
			try { current = Number(fs.readFileSync(sharedStatePath, 'utf8').trim() || '0'); } catch { current = 0; }
			fs.writeFileSync(sharedStatePath, String(Math.max(0, current - 1)));
		} finally { fs.rmdirSync(lockPath); }
	}
}

function writeResult(status = 'PASSED') {
	const result = {
		status,
		metadata: { arm, runId, sourceHash, configHash, trialId, planningConcurrency },
		trials: [
			{ trialId, repetition: 1, status, durationMs: 12, cleanup: { ok: true, tempResidue: 0, backupResidue: 0 }, ...(label ? { label } : {}) },
			{ trialId, repetition: 2, status, durationMs: 18, cleanup: { ok: true, tempResidue: 0, backupResidue: 0 }, ...(label ? { label } : {}) },
		],
		cleanup: { ok: true, activeActions: 0, listeners: 0 },
		summary: 1,
	};
	process.stdout.write(`${JSON.stringify(result)}\n`);
}

async function run() {
	await withActiveState(async () => {
		if (values.has('--drift-source')) {
			fs.writeFileSync(path.join(process.cwd(), 'src', 'fixture-drift.txt'), 'drift');
		}
		if (fixtureMode === 'timeout') {
			await sleep(10_000);
			return;
		}
		if (fixtureMode === 'malformed') {
			process.stdout.write('not-json\n');
			return;
		}
		if (fixtureMode === 'cleanup-leak') fs.writeFileSync(path.join(attemptPath, 'leftover.tmp'), 'leak');
		const attempt = /attempt-(\d+)/i.exec(attemptPath)?.[1] ?? '1';
		if (retryOnce && trialId === 'pair-a' && attempt === '1') {
			process.exitCode = 17;
			return;
		}
		writeResult(fixtureMode === 'skip-live' ? 'SKIPPED' : 'PASSED');
	});
}

run().catch((error) => {
	process.stderr.write(`${error.message}\n`);
	process.exitCode = 1;
});
