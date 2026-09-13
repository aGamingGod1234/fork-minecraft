import { randomUUID } from 'node:crypto';

/** Correlates bounded read requests with the exact server sample, never a cached heartbeat. */
export class InspectionClient {
	#bridge;
	#pending = new Map();
	constructor(bridge) { this.#bridge = bridge; }
	request(record, query, { timeoutMs = 10_000, connectionEpoch } = {}) {
		const { agentId, goalRevision } = record;
		query = structuredClone(query);
		if (this.#pending.size >= 64) return Promise.reject(Object.assign(new Error('Too many pending inspections'), { code: 'INSPECTION_BUSY' }));
		const requestId = randomUUID();
		return new Promise((resolve, reject) => {
			const timer = setTimeout(() => {
				this.#pending.delete(requestId);
				reject(Object.assign(new Error('Fresh server inspection timed out'), { code: 'INSPECTION_TIMEOUT' }));
			}, timeoutMs);
			timer.unref?.();
			this.#pending.set(requestId, { agentId, goalRevision, resolve, reject, timer });
			Promise.resolve().then(() => {
				if (!this.#pending.has(requestId)) return;
				return this.#bridge.send('inspection_request', agentId, { requestId, goalRevision, query }, { connectionEpoch });
			}).catch((error) => {
				clearTimeout(timer);
				this.#pending.delete(requestId);
				reject(error);
			});
		});
	}
	accept(message) {
		const request = this.#pending.get(message.payload.requestId);
		if (!request || request.agentId !== message.agentId || request.goalRevision !== message.payload.goalRevision) return false;
		this.#pending.delete(message.payload.requestId);
		clearTimeout(request.timer);
		if (message.payload.error) request.reject(Object.assign(new Error(message.payload.error.message), { code: message.payload.error.code }));
		else request.resolve(message.payload.result);
		return true;
	}
	cancel(agentId, reason = 'INSPECTION_CANCELLED') {
		for (const [key, request] of this.#pending) {
			if (agentId !== undefined && request.agentId !== agentId) continue;
			clearTimeout(request.timer);
			this.#pending.delete(key);
			request.reject(Object.assign(new Error('Inspection authority ended'), { code: reason }));
		}
	}
}
