import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveDynamicCliRuntime } from '../src/dynamic-main.mjs';

test('resolves isolated headless trace, audit, and provider-turn paths from environment', () => {
	const paths = resolveDynamicCliRuntime({
		ARENA_HEADLESS_TRACE_PATH: 'C:/runs/trace/coordinator.jsonl',
		ARENA_PROTOCOL_AUDIT_PATH: 'C:/runs/trace/protocol.jsonl',
		ARENA_PROVIDER_TURNS_PATH: 'C:/runs/trace/provider.private.jsonl',
	});
	assert.equal(paths.tracePath, 'C:/runs/trace/coordinator.jsonl');
	assert.equal(paths.diagnosticTracePath, 'C:/runs/trace/coordinator-private.jsonl');
	assert.equal(paths.protocolAuditPath, 'C:/runs/trace/protocol.jsonl');
	assert.equal(paths.providerTurnsPath, 'C:/runs/trace/provider.private.jsonl');
});
