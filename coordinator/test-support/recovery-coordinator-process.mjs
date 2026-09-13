import { EventEmitter } from 'node:events';

import { AgentRegistry } from '../src/agent-registry.mjs';
import { createDynamicCoordinator } from '../src/dynamic-main.mjs';

const port = Number.parseInt(process.env.ARENA_RECOVERY_SMOKE_PORT ?? '', 10);
const secret = process.env.ARENA_RECOVERY_SMOKE_SECRET ?? '';
const registry = new AgentRegistry({ agentCap: 1, queueCap: 1 });

class EmptyProvider extends EventEmitter {
	catalog = { stale: false, refresh: async () => ({ refreshedAtEpochMs: Date.now(), models: [] }), assertSupported() {} };
	async bootstrapCatalog() { return this.catalog.refresh(); }
	async stop() {}
	recoverySnapshot() { return []; }
}

class EmptyPlanner {
	beginReconcile(records) {
		const reconciled = registry.reconcile(records, { recovery: true });
		return {
			registry: reconciled,
			complete: Promise.resolve({
				registry: reconciled,
				providers: { valid: reconciled.records, invalid: [], catalog: { refreshedAtEpochMs: Date.now(), models: [] } },
			}),
		};
	}
	async requestPlan() { throw new Error('empty-roster smoke must not plan'); }
	async interrupt() {}
	async remove() {}
}

const provider = new EmptyProvider();
const coordinator = createDynamicCoordinator({
	bridge: {
		port,
		secret,
		handshakeTimeoutMs: 250,
		heartbeatIntervalMs: 100,
		heartbeatTimeoutMs: 300,
		reconnectDelayMs: 10,
		maxReconnectDelayMs: 20,
	},
	codex: { controlProtocol: 'native_tools' },
	limits: { agentCap: 1, planningConcurrency: 1, goalQueueCap: 1 },
}, {
	registry,
	planner: new EmptyPlanner(),
	providerService: provider,
});

const runtimeErrors = [];
coordinator.on('runtimeError', (error) => {
	runtimeErrors.push(error?.code ?? 'RUNTIME_ERROR');
});
coordinator.on('reconciled', () => {
	process.send?.({ type: 'healthy', connectionEpoch: coordinator.bridge.connectionEpoch, runtimeErrors: [...runtimeErrors] });
});

let stopping = null;
async function stop() {
	stopping ??= coordinator.stop().then(() => {
		process.send?.({ type: 'stopped', runtimeErrors: [...runtimeErrors] });
	});
	await stopping;
}

process.on('message', (message) => {
	if (message?.type === 'stop') void stop();
});
process.once('SIGTERM', () => { void stop(); });
process.once('SIGINT', () => { void stop(); });

await coordinator.start();
process.send?.({ type: 'started' });
