import assert from 'node:assert/strict';
import test from 'node:test';

import { RuntimeErrorReporter } from '../src/runtime-error-reporter.mjs';

test('repeated bridge refusal writes one error and one recovery summary', async () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	for (let count = 0; count < 100; count += 1) {
		reporter.report(Object.assign(new Error('connect ECONNREFUSED 127.0.0.1:25570'), { code: 'ECONNREFUSED' }));
	}
	await new Promise((resolve) => setImmediate(resolve));

	assert.equal(writes.length, 1);
	assert.match(writes[0], /ECONNREFUSED/);

	reporter.recovered();
	await reporter.close();

	assert.equal(writes.length, 2);
	assert.match(writes[1], /100 refused connection attempts/);
});

test('recovery summary resets refusal aggregation for the next outage', async () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	const refusal = Object.assign(new Error('connect ECONNREFUSED'), { code: 'ECONNREFUSED' });

	reporter.report(refusal);
	reporter.report(refusal);
	reporter.recovered();
	reporter.recovered();
	reporter.report(refusal);
	await reporter.close();

	assert.equal(writes.length, 3);
	assert.match(writes[1], /2 refused connection attempts/);
	assert.match(writes[2], /ECONNREFUSED/);
});

test('unexpected errors retain bounded code, message, context, and one redacted stack', async () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	const error = Object.assign(new Error('bad frame token=provider-secret raw prompt: build a shelter'), {
		code: 'INVALID_FRAME',
		stack: 'Error: bad frame token=provider-secret\n    at bridge (C:\\private\\prompt.js:1:1)\n    at bridge (C:\\private\\prompt.js:2:1)',
		prompt: 'raw prompt should never be logged',
		secret: 'bridge-secret-value',
	});

	reporter.report(error, {
		agentId: 'luna',
		goalRevision: 4,
		lifecycleGeneration: 2,
		activeWorkKind: 'provider',
		prompt: 'raw context prompt must never be logged',
		secret: 'context-secret-value',
	});
	await reporter.close();

	assert.equal(writes.length, 1);
	assert.match(writes[0], /INVALID_FRAME/);
	assert.match(writes[0], /goalRevision=4/);
	assert.match(writes[0], /Error: bad frame/);
	assert.doesNotMatch(writes[0], /provider-secret|bridge-secret-value|raw prompt|prompt\.js|context-secret-value/);
	assert.equal((writes[0].match(/Error:/g) ?? []).length, 1);
});

test('unexpected diagnostics redact authorization credentials in messages and stacks', async () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	const error = Object.assign(new Error('Authorization: authorization-secret'), {
		code: 'UNAUTHORIZED',
		stack: 'Error: Authorization: authorization-secret',
	});

	reporter.report(error);
	await reporter.close();

	assert.equal(writes.length, 1);
	assert.doesNotMatch(writes[0], /authorization-secret/);
});

test('hostile error getters cannot escape report()', async () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	const error = new Proxy({}, {
		get() { throw new Error('private error getter'); },
	});

	assert.doesNotThrow(() => reporter.report(error));
	await reporter.close();
	assert.equal(writes.length, 1);
	assert.doesNotMatch(writes[0], /private error getter/);
});

test('hostile context proxies cannot escape report()', async () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	const context = new Proxy({}, {
		getOwnPropertyDescriptor() { throw new Error('private context getter'); },
	});

	assert.doesNotThrow(() => reporter.report(new Error('bad frame'), context));
	await reporter.close();
	assert.equal(writes.length, 1);
	assert.doesNotMatch(writes[0], /private context getter/);
});

test('huge stack diagnostics use a bounded line extraction and output', async () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	const hugeStack = Array.from({ length: 100_000 }, (_, index) => `    at frame-${index}`).join('\n');
	const error = Object.assign(new Error('huge diagnostic'), { stack: hugeStack });
	const originalSplit = String.prototype.split;
	let stackSplitLimit;
	String.prototype.split = function patchedSplit(separator, limit) {
		if (separator instanceof RegExp && separator.source === '\\r?\\n') stackSplitLimit = limit;
		return originalSplit.call(this, separator, limit);
	};
	try {
		reporter.report(error);
	} finally {
		String.prototype.split = originalSplit;
	}
	await reporter.close();

	assert.equal(stackSplitLimit, 16);
	assert.ok(Buffer.byteLength(writes[0], 'utf8') <= 5_000);
});

test('diagnostic reporter observes rejected writes and continues with later output', async () => {
	const writes = [];
	let calls = 0;
	const reporter = new RuntimeErrorReporter({
		write(line) {
			calls += 1;
			if (calls === 1) return Promise.reject(new Error('stderr unavailable'));
			writes.push(line);
		},
	});
	reporter.report(Object.assign(new Error('first'), { code: 'FIRST' }));
	reporter.report(Object.assign(new Error('second'), { code: 'SECOND' }));
	await reporter.close();

	assert.equal(calls, 2);
	assert.equal(writes.length, 1);
	assert.match(writes[0], /SECOND/);
});

test('diagnostic reporter bounds a hung write and coalesces queue overflow', async () => {
	let calls = 0;
	const reporter = new RuntimeErrorReporter({
		write() { calls += 1; return new Promise(() => {}); },
		maxPending: 2,
		operationTimeoutMs: 20,
		closeTimeoutMs: 10,
	});
	for (let index = 0; index < 100; index += 1) {
		reporter.report(Object.assign(new Error(`failure ${index}`), { code: `CODE_${index}` }));
	}
	await new Promise((resolve) => setImmediate(resolve));
	assert.ok(calls >= 1 && calls <= 2, 'the sink never owns more work than the configured bound');
	const closing = reporter.close();
	assert.strictEqual(reporter.close(), closing);
	await closing;
});

test('identical unexpected incidents emit one stack and one bounded recovery summary', async () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	for (let count = 0; count < 50; count += 1) {
		reporter.report(Object.assign(new Error('provider failed'), { code: 'PROVIDER_FAILED' }), { phase: 'planning' });
	}
	reporter.recovered();
	await reporter.close();
	await reporter.close();

	assert.equal(writes.length, 2);
	assert.match(writes[0], /PROVIDER_FAILED/);
	assert.match(writes[1], /49 repeated diagnostics suppressed/);
});

test('diagnostics redact absolute paths in messages and allowed context values', async () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	reporter.report(
		Object.assign(new Error('failed at C:\\Users\\lucas\\private\\token.json and /home/lucas/private/config.json'), { code: 'PATH_FAILURE' }),
		{ operation: 'file:///C:/Users/lucas/private/operation.mjs:4:2' },
	);
	await reporter.close();

	assert.equal(writes.length, 1);
	assert.doesNotMatch(writes[0], /Users\\lucas|\/home\/lucas|file:\/\/\/C:/i);
	assert.match(writes[0], /location redacted/i);
});

test('diagnostics redact quoted absolute paths containing spaces', async () => {
	const writes = [];
	const reporter = new RuntimeErrorReporter({ write: (line) => writes.push(line) });
	reporter.report(new Error('failed at "C:\\Users\\lucas\\agents arena\\private token.json"'));
	await reporter.close();

	assert.equal(writes.length, 1);
	assert.doesNotMatch(writes[0], /agents arena|private token/i);
	assert.match(writes[0], /location redacted/i);
});
