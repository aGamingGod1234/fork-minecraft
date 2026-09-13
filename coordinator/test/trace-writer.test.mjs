import assert from 'node:assert/strict';
import test from 'node:test';

import { observationHash, redact, TraceWriter } from '../src/trace-writer.mjs';
import { RotatingJsonlSink } from '../src/rotating-jsonl-sink.mjs';

test('rotating JSONL sinks bound active size, age, and retained generations', async () => {
	let active = { size: 20, mtimeMs: 0 };
	const operations = [];
	const sink = new RotatingJsonlSink('trace.jsonl', {
		maxFileBytes: 24,
		maxFileAgeMs: 10,
		retainedGenerations: 2,
		now: () => 20,
		stat: async () => active,
		unlink: async (file) => { operations.push(['unlink', file]); },
		rename: async (from, to) => { operations.push(['rename', from, to]); if (from === 'trace.jsonl') active = null; },
		appendFile: async (file, encoded) => { operations.push(['append', file, encoded]); active = { size: Buffer.byteLength(encoded), mtimeMs: 20 }; },
	});
	await sink.append('{"event":"new"}\n', { encoding: 'utf8' });
	assert.deepEqual(operations.slice(0, 4), [
		['unlink', 'trace.jsonl.2'],
		['rename', 'trace.jsonl.1', 'trace.jsonl.2'],
		['rename', 'trace.jsonl', 'trace.jsonl.1'],
		['append', 'trace.jsonl', '{"event":"new"}\n'],
	]);
});

test('prepares both trace artifacts privately before appending', async () => {
	const prepared = [];
	const writes = [];
	const writer = new TraceWriter('trace.jsonl', {
		diagnosticFilePath: 'private.jsonl',
		mkdir: async () => {},
		preparePrivateArtifact: async (filePath) => { prepared.push(filePath); },
		appendFile: async (_filePath, _text, options) => { writes.push(options); },
	});
	await writer.write('public');
	await writer.writeDiagnostic('private');
	await writer.close();
	assert.deepEqual(prepared.map((filePath) => filePath.split(/[\\/]/).at(-1)).sort(), ['private.jsonl', 'trace.jsonl']);
	assert.ok(writes.every((options) => options.mode === 0o600));
});

test('appends redacted JSONL rows in order', async () => {
	const chunks = [];
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		mkdir: async () => {},
		appendFile: async (_path, value) => { chunks.push(value); },
	});
	await Promise.all([
		writer.write({ event: 'one', authorization: 'Bearer private', nested: { apiKey: 'secret' } }),
		writer.write({ event: 'two', message: 'Bearer abc.def' }),
	]);
	await writer.close();
	assert.equal(JSON.parse(chunks[0]).authorization, '[REDACTED]');
	assert.equal(JSON.parse(chunks[0]).nested.apiKey, '[REDACTED]');
	assert.equal(JSON.parse(chunks[1]).message, 'Bearer [REDACTED]');
});

test('hashes identical observations deterministically', () => {
	assert.equal(observationHash({ ready: true, x: 1 }), observationHash({ ready: true, x: 1 }));
	assert.notEqual(observationHash({ ready: true, x: 1 }), observationHash({ ready: true, x: 2 }));
});

test('writes typed program events without leaking tokens or unbounded source', async () => {
	const chunks = [];
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		mkdir: async () => {},
		appendFile: async (_path, value) => { chunks.push(value); },
	});
	await writer.write('program_step', {
		programId: 'program-1-1',
		sourceStepId: 'step-1-9',
		token: 'secret-value',
		source: 'program.onUnhandledAttention("continue_and_notify");',
	});
	await writer.close();
	const line = chunks.join('');
	assert.match(line, /program_step/);
	assert.match(line, /program-1-1/);
	assert.match(line, /sourceHash/);
	assert.doesNotMatch(line, /secret-value/);
	assert.doesNotMatch(line, /onUnhandledAttention/);
});

test('keeps private source bounded while bounding other diagnostics more tightly', async () => {
	const rows = [];
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		diagnosticFilePath: 'C:\\runtime\\private.jsonl',
		mkdir: async () => {},
		appendFile: async (filePath, value) => { rows.push({ filePath, row: JSON.parse(value) }); },
	});
	const source = 's'.repeat(70_000);
	await writer.writeDiagnostic('program_compiled', { source, message: 'm'.repeat(3_000) });
	await writer.close();
	const row = rows[0].row;
	assert.equal(row.source.length, 65_536);
	assert.equal(row.message.length, 2_048);
});

test('does not invoke accessors or proxy traps and emits canonical bounded records', async () => {
	let getterCalled = false;
	const accessor = {};
	Object.defineProperty(accessor, 'secret', { enumerable: true, get() { getterCalled = true; throw new Error('must not run'); } });
	const proxy = new Proxy({ value: 'hidden' }, {
		ownKeys() { throw new Error('must not run'); },
		getOwnPropertyDescriptor() { throw new Error('must not run'); },
	});
	const custom = Object.create({ inherited: 'must not copy' });
	custom.own = 'kept';
	const input = { accessor, proxy, custom };
	input.circular = input;
	const result = redact(input);
	assert.equal(getterCalled, false);
	assert.equal(Object.getPrototypeOf(result), null);
	assert.equal(Object.getPrototypeOf(result.custom), null);
	assert.equal(result.accessor.secret, undefined);
	assert.equal(result.proxy, '[UNSAFE_OBJECT]');
	assert.equal(result.custom.inherited, undefined);
	assert.equal(result.circular, '[CIRCULAR]');

	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', { mkdir: async () => {}, appendFile: async () => {} });
	await assert.doesNotReject(writer.write('safe', { accessor, proxy }));
	await writer.close();
});

test('redacts textual credential patterns from public and private strings', async () => {
	const rows = [];
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		diagnosticFilePath: 'C:\\runtime\\private.jsonl',
		mkdir: async () => {},
		appendFile: async (filePath, value) => rows.push({ filePath, value }),
	});
	const source = 'program.chat({message: "api_key=abc token=def secret=ghi credential=jkl oauth=mno Bearer qrs"}); await player.wait(1);';
	await writer.write('public', { message: 'api_key=abc token=def secret=ghi oauth=mno Bearer qrs', source });
	await writer.writeDiagnostic('private', { source });
	await writer.close();
	assert.ok(rows.every(({ value }) => !/(api_key|token|secret|credential|oauth)=?(abc|def|ghi|jkl|mno)|Bearer qrs/i.test(value)));
	const privateRow = JSON.parse(rows.find(({ filePath }) => filePath.endsWith('private.jsonl')).value);
	assert.match(privateRow.source, /program\.chat/);
	assert.doesNotMatch(privateRow.source, /api_key=abc|token=def|secret=ghi|oauth=mno|Bearer qrs/i);
});

test('caps serialized rows including huge keys and unsupported non-string values', async () => {
	const rows = [];
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		mkdir: async () => {},
		appendFile: async (_path, value) => rows.push(value),
	});
	const hugeKey = 'k'.repeat(400_000);
	await assert.doesNotReject(writer.write('bounded', { [hugeKey]: 42, count: 7, bigint: 1n, symbol: Symbol('private') }));
	await writer.close();
	assert.ok(Buffer.byteLength(rows[0], 'utf8') <= 262_145);
	const row = JSON.parse(rows[0]);
	assert.equal(row.event, 'bounded');
	assert.equal(row.count, 7);
	assert.doesNotMatch(rows[0], /k{100}/);
});

test('reserves truncation-marker bytes at the serialized line boundary', async () => {
	const lines = [];
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		mkdir: async () => {},
		appendFile: async (_path, value) => lines.push(value),
	});
	const fields = Object.fromEntries(Array.from({ length: 64 }, (_, index) => [
		`${String(index).padStart(2, '0')}-${'k'.repeat(4_200)}`, `🙂${index}`,
	]));
	await writer.write('near-boundary', fields);
	await writer.close();
	assert.ok(Buffer.byteLength(lines[0], 'utf8') >= 250_000, 'fixture must exercise the near-boundary path');
	assert.ok(Buffer.byteLength(lines[0], 'utf8') <= 262_144, 'JSONL line including newline must fit the trace cap');
	const row = JSON.parse(lines[0]);
	assert.equal(row.event, 'near-boundary');
	assert.equal(row.truncated, '[BOUNDED]');
	assert.doesNotMatch(lines[0], /\uD800|\uDFFF/);
});

test('trace writes never reject when mkdir or append sinks fail', async () => {
	let appendCalls = 0;
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		mkdir: async () => { throw new Error('mkdir failed'); },
		appendFile: async () => { appendCalls += 1; throw new Error('append failed'); },
	});
	await assert.doesNotReject(writer.write('first', { token: 'private' }));
	await assert.doesNotReject(writer.write('second'));
	await assert.doesNotReject(writer.close());
	assert.equal(appendCalls, 0, 'a failed readiness sink is observed without attempting append');
	assert.equal(writer.statusSnapshot().failureCode, 'DIAGNOSTIC_SINK_FAILED');
	await assert.doesNotReject(writer.write('after-close'));
});

test('trace writer bounds hung sinks, drops overflow, and closes idempotently', async () => {
	let appendCalls = 0;
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		mkdir: async () => {},
		appendFile: async () => { appendCalls += 1; return new Promise(() => {}); },
		maxPending: 2,
		operationTimeoutMs: 20,
		closeTimeoutMs: 10,
	});
	for (let index = 0; index < 100; index += 1) await assert.doesNotReject(writer.write('event', { index }));
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(appendCalls, 1);
	const closing = writer.close();
	assert.strictEqual(writer.close(), closing);
	await closing;
});

test('trace writer recovers after an asynchronous append rejection', async () => {
	const rows = [];
	let calls = 0;
	const writer = new TraceWriter('C:\\runtime\\trace.jsonl', {
		mkdir: async () => {},
		appendFile: async (_file, row) => {
			calls += 1;
			if (calls === 1) throw new Error('temporary trace failure');
			rows.push(JSON.parse(row));
		},
	});
	await writer.write('first');
	await writer.write('second');
	await writer.close();
	assert.deepEqual(rows.map(({ event }) => event), ['second']);
});
