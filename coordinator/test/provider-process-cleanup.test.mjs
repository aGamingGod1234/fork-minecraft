import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { AcpStdioTransport } from '../src/acp-transport.mjs';
import { terminateChildProcess } from '../src/child-process-lifecycle.mjs';
import { CodexStdioTransport } from '../src/codex-app-server.mjs';

const FAST_STOP_TIMEOUT_MS = 5;
const REQUEST_TIMEOUT_MS = 5;
const SETTLE_TIMEOUT_MS = 100;

class UncooperativeChild extends EventEmitter {
	constructor() {
		super();
		this.stdout = new EventEmitter();
		this.stderr = new EventEmitter();
		this.stdin = new EventEmitter();
		this.stdin.write = () => {};
		this.killed = false;
		this.exitCode = null;
		this.signalCode = null;
		this.signals = [];
	}

	kill(signal = 'SIGTERM') {
		this.killed = true;
		this.signals.push(signal);
		return true;
	}
}

test('provider transports own late stdio EPIPE errors and reject pending work once', async () => {
	for (const [name, create] of [
		['Codex', (child) => new CodexStdioTransport(
			{ model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
			{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
		)],
		['ACP', (child) => new AcpStdioTransport(
			{ provider: 'kimi', cwd: process.cwd() },
			{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
		)],
	]) {
		const child = new UncooperativeChild();
		const transport = create(child);
		const exits = [];
		transport.on('exit', (error) => exits.push(error));
		await transport.start();
		const pending = transport.request('pending/request', {}, { timeoutMs: SETTLE_TIMEOUT_MS });
		child.stdin.emit('error', Object.assign(new Error('broken pipe'), { code: 'EPIPE' }));
		await assert.rejects(pending, (error) => error?.code === 'PROCESS_IO_ERROR');
		assert.equal(exits.length, 1, `${name} reports one owned transport loss`);
		assert.throws(() => transport.notify('after/error'), (error) => error?.code === 'TRANSPORT_NOT_RUNNING');
		await transport.stop();
	}
});

function spawnUncooperativeChild(child) {
	return () => {
		queueMicrotask(() => child.emit('spawn'));
		return child;
	};
}

function spawnAlreadyRunningChild(child) {
	return () => {
		child.pid = 12345;
		child.emit('spawn');
		return child;
	};
}

async function within(promise, timeoutMs = SETTLE_TIMEOUT_MS) {
	let timer;
	try {
		return await Promise.race([
			promise,
			new Promise((_, reject) => {
				timer = setTimeout(() => reject(new Error(`operation did not settle within ${timeoutMs} ms`)), timeoutMs);
			}),
		]);
	} finally {
		clearTimeout(timer);
	}
}

function transportCases() {
	return [
		{
			name: 'Codex',
			create(child) {
				return new CodexStdioTransport(
					{ model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
					{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
				);
			},
		},
		{
			name: 'Gemini ACP',
			create(child) {
				return new AcpStdioTransport(
					{ provider: 'gemini' },
					{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
				);
			},
		},
		{
			name: 'Kimi ACP',
			create(child) {
				return new AcpStdioTransport(
					{ provider: 'kimi', reasoningEffort: 'high' },
					{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
				);
			},
		},
	];
}

function createWithSpawn(entry, child, spawn) {
	if (entry.name === 'Codex') {
		return new CodexStdioTransport(
			{ model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
			{ spawn, stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
		);
	}
	return new AcpStdioTransport(
		{ provider: entry.name === 'Gemini ACP' ? 'gemini' : 'kimi', reasoningEffort: 'high' },
		{ spawn, stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
	);
}

for (const entry of transportCases()) {
	test(`${entry.name} starts when the child is already running before listeners attach`, async () => {
		const child = new UncooperativeChild();
		const transport = createWithSpawn(entry, child, spawnAlreadyRunningChild(child));

		await within(transport.start());
		child.pid = undefined;
		await within(transport.stop());
	});

	test(`${entry.name} shutdown force-kills a child that ignores graceful termination`, async () => {
		const child = new UncooperativeChild();
		const transport = entry.create(child);
		await transport.start();

		await within(transport.stop());

		assert.deepEqual(child.signals, ['SIGTERM', 'SIGKILL']);
	});

	if (entry.name === 'Codex') continue;
	test(`${entry.name} request timeout tears down its unresponsive child`, async () => {
		const child = new UncooperativeChild();
		const transport = entry.create(child);
		await transport.start();

		await assert.rejects(
			transport.request('unresponsive/request', {}, { timeoutMs: REQUEST_TIMEOUT_MS }),
			(error) => error?.code === 'REQUEST_TIMEOUT',
		);
		await within(new Promise((resolve) => setTimeout(resolve, FAST_STOP_TIMEOUT_MS * 3)));

		assert.deepEqual(child.signals, ['SIGTERM', 'SIGKILL']);
		assert.throws(
			() => transport.notify('after/timeout'),
			(error) => error?.code === 'TRANSPORT_NOT_RUNNING',
		);
	});
}

test('Codex request timeout preserves the shared transport and unrelated requests', async () => {
	const child = new UncooperativeChild();
	const requests = [];
	child.stdin.write = (line) => requests.push(JSON.parse(String(line).trim()));
	const transport = new CodexStdioTransport(
		{ model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
		{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
	);
	const protocolErrors = [];
	transport.on('protocolError', (error) => protocolErrors.push(error.code));
	await transport.start();

	const timedOut = transport.request('slow/request', {}, { timeoutMs: REQUEST_TIMEOUT_MS });
	const survivor = transport.request('fast/request', {}, { timeoutMs: SETTLE_TIMEOUT_MS });
	await assert.rejects(timedOut, (error) => error?.code === 'REQUEST_TIMEOUT');
	child.stdout.emit('data', `${JSON.stringify({ id: requests[0].id, result: { late: true } })}\n`);
	child.stdout.emit('data', `${JSON.stringify({ id: requests[1].id, result: { ok: true } })}\n`);

	assert.deepEqual(await within(survivor), { ok: true });
	assert.deepEqual(protocolErrors, []);
	assert.deepEqual(child.signals, []);
	assert.doesNotThrow(() => transport.notify('still/running'));
	await transport.stop();
});

test('Codex transport surfaces server tool requests and sends their JSON-RPC result', async () => {
	const child = new UncooperativeChild();
	const written = [];
	child.stdin.write = (line) => written.push(JSON.parse(String(line).trim()));
	const transport = new CodexStdioTransport(
		{ model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' },
		{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
	);
	await transport.start();

	const request = new Promise((resolve) => transport.once('serverRequest', resolve));
	child.stdout.emit('data', `${JSON.stringify({
		id: 91,
		method: 'item/tool/call',
		params: { threadId: 'thread-1', turnId: 'turn-1', callId: 'call-1', tool: 'observe', arguments: {} },
	})}\n`);
	assert.deepEqual(await within(request), {
		id: 91,
		method: 'item/tool/call',
		params: { threadId: 'thread-1', turnId: 'turn-1', callId: 'call-1', tool: 'observe', arguments: {} },
	});

	transport.respond(91, { success: true, contentItems: [{ type: 'inputText', text: '{"ok":true}' }] });
	assert.deepEqual(written.at(-1), {
		id: 91,
		result: { success: true, contentItems: [{ type: 'inputText', text: '{"ok":true}' }] },
	});
	await transport.stop();
});

test('Windows cleanup terminates the complete provider process tree', async () => {
	const child = new UncooperativeChild();
	child.pid = 4_242;
	const calls = [];
	const execute = (file, args, options, callback) => {
		calls.push({ file, args, options });
		callback(null, '', '');
	};

	await terminateChildProcess(child, {
		timeoutMs: 50,
		platform: 'win32',
		execFile: execute,
	});

	assert.deepEqual(calls.map((call) => call.args), [
		['/PID', '4242', '/T'],
		['/PID', '4242', '/T', '/F'],
	]);
});

test('Windows cleanup accepts a late exit when forced taskkill reports an already-gone process', async () => {
	const child = new UncooperativeChild();
	child.pid = 4_243;
	let calls = 0;
	const execute = (_file, _args, _options, callback) => {
		calls += 1;
		if (calls === 1) {
			callback(null, '', '');
			return;
		}
		callback(new Error('process not found'), '', '');
		setImmediate(() => {
			child.exitCode = 0;
			child.emit('exit', 0, null);
		});
	};

	await terminateChildProcess(child, {
		// This assertion exercises the late-exit path, not the minimum timeout.
		// Leave enough headroom for a loaded Windows CI runner to schedule the
		// setImmediate callback after the graceful taskkill wait.
		timeoutMs: 250,
		platform: 'win32',
		execFile: execute,
	});
	assert.equal(calls, 2);
});

test('Windows cleanup has one outer deadline when taskkill never settles', async () => {
	const child = new UncooperativeChild();
	child.pid = 4_244;
	let calls = 0;
	const startedAt = Date.now();

	await assert.rejects(
		terminateChildProcess(child, {
			timeoutMs: 20,
			platform: 'win32',
			execFile: () => { calls += 1; },
		}),
		/before the 20ms deadline/,
	);

	assert.equal(calls, 1);
	assert.ok(Date.now() - startedAt < 250);
	assert.deepEqual(child.signals, ['SIGKILL']);
});

test('Windows cleanup does not launch another helper after its timer expires before the wall clock deadline', async (t) => {
	t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 1_000 });
	const child = new UncooperativeChild();
	child.pid = 4_245;
	let calls = 0;
	const cleanup = terminateChildProcess(child, {
		timeoutMs: 20,
		platform: 'win32',
		execFile: () => { calls += 1; },
	});
	const rejected = assert.rejects(cleanup, /before the 20ms deadline/);
	t.mock.timers.tick(20);
	// Timers and Date.now() need not agree at the millisecond boundary.
	t.mock.timers.setTime(1_019);
	for (let step = 0; step < 3; step++) {
		await Promise.resolve();
		t.mock.timers.tick(1);
	}
	await rejected;
	assert.equal(calls, 1);
	assert.deepEqual(child.signals, ['SIGKILL']);
});


test('Gemini ACP preserves bounded structured 429 metadata without retaining provider data', async () => {
	const child = new UncooperativeChild();
	const requests = [];
	child.stdin.write = (line) => requests.push(JSON.parse(String(line).trim()));
	const transport = new AcpStdioTransport(
		{ provider: 'gemini' },
		{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
	);
	await transport.start();

	const request = transport.request('session/prompt', {}, { timeoutMs: SETTLE_TIMEOUT_MS });
	child.stdout.emit('data', `${JSON.stringify({
		id: requests[0].id,
		error: { code: -32_000, message: 'quota exhausted', data: { httpStatusCode: 429, prompt: 'arbitrary-secret-prompt' } },
	})}\n`);

	await assert.rejects(request, (error) => {
		assert.equal(error.code, 'RPC_ERROR');
		assert.equal(error.rpcCode, -32_000);
		assert.equal(error.httpStatusCode, 429);
		assert.equal(error.rateLimited, true);
		assert.equal(Object.hasOwn(error, 'data'), false);
		assert.doesNotMatch(JSON.stringify(error), /arbitrary-secret-prompt/);
		return true;
	});
	await transport.stop();
});

test('ACP RPC fallback never stringifies provider error data', async () => {
	const child = new UncooperativeChild();
	const requests = [];
	child.stdin.write = (line) => requests.push(JSON.parse(String(line).trim()));
	const transport = new AcpStdioTransport(
		{ provider: 'gemini' },
		{ spawn: spawnUncooperativeChild(child), stopTimeoutMs: FAST_STOP_TIMEOUT_MS },
	);
	await transport.start();

	const request = transport.request('session/prompt', {}, { timeoutMs: SETTLE_TIMEOUT_MS });
	child.stdout.emit('data', `${JSON.stringify({ id: requests[0].id, error: {
		code: 429, data: { prompt: 'ARBITRARY_RPC_PROMPT_SECRET', httpStatusCode: 429 },
	} })}\n`);

	await assert.rejects(request, (error) => {
		assert.doesNotMatch(error.message, /ARBITRARY_RPC_PROMPT_SECRET|httpStatusCode|\{"code"/);
		assert.deepEqual({ rpcCode: error.rpcCode, httpStatusCode: error.httpStatusCode, rateLimited: error.rateLimited }, { rpcCode: 429, httpStatusCode: 429, rateLimited: true });
		return true;
	});
	await transport.stop();
});
