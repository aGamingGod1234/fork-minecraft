import assert from 'node:assert/strict';
import test from 'node:test';

import { createJsonlAudit } from '../src/dynamic-main.mjs';

test('protocol audit owns hung writes without delaying control and bounds close', async () => {
	const audit = createJsonlAudit('protocol.jsonl', { runId: 'run' }, {
		mkdir: async () => {}, appendFile: () => new Promise(() => {}),
		maxPending: 2, operationTimeoutMs: 20, closeTimeoutMs: 30,
	});
	for (let index = 0; index < 100; index += 1) await audit('out', { sequence: index });
	assert.ok(audit.statusSnapshot().droppedCount > 0);
	const first = audit.close();
	assert.strictEqual(audit.close(), first);
	await first;
});

test('protocol audit uses shared redaction and survives rejected writes', async () => {
	const rows = [];
	let calls = 0;
	const audit = createJsonlAudit('protocol.jsonl', { authorization: 'Bearer metadata-secret' }, {
		mkdir: async () => {},
		appendFile: async (_path, text) => {
			calls += 1;
			if (calls === 1) throw new Error('temporary failure');
			rows.push(JSON.parse(text));
		},
	});
	await audit('out', { password: 'payload-secret', path: 'C:\\Users\\lucas\\secret.json' });
	await audit('out', { ok: true });
	await audit.close();
	assert.equal(rows.length, 1);
	assert.equal(JSON.stringify(rows).includes('metadata-secret'), false);
	assert.equal(JSON.stringify(rows).includes('lucas'), false);
});
