/** Keeps bounded transition histories independently per agent and reporting boundary. */
export class ReportingTransitionDeduper {
	#maximumAgents;
	#maximumTransitionsPerAgent;
	#last = new Map();

	constructor({ maximumAgents = 16, maximumTransitionsPerAgent = 16 } = {}) {
		if (!Number.isSafeInteger(maximumAgents) || maximumAgents < 1 || maximumAgents > 16) throw new TypeError('maximumAgents must be in [1, 16]');
		if (!Number.isSafeInteger(maximumTransitionsPerAgent) || maximumTransitionsPerAgent < 1 || maximumTransitionsPerAgent > 64) {
			throw new TypeError('maximumTransitionsPerAgent must be in [1, 64]');
		}
		this.#maximumAgents = maximumAgents;
		this.#maximumTransitionsPerAgent = maximumTransitionsPerAgent;
	}

	get size() { return this.#last.size; }

	accept({ agentId, goalRevision, component, boundary, code, state, detail = null }) {
		let agent = this.#last.get(agentId);
		if (agent === undefined || agent.goalRevision !== goalRevision) {
			agent = { goalRevision, transitions: new Map() };
		} else {
			this.#last.delete(agentId);
		}
		this.#last.set(agentId, agent);
		while (this.#last.size > this.#maximumAgents) this.#last.delete(this.#last.keys().next().value);
		const boundaryKey = JSON.stringify([component, boundary]);
		const identity = JSON.stringify([code, state, detail]);
		if (agent.transitions.get(boundaryKey) === identity) return false;
		agent.transitions.delete(boundaryKey);
		agent.transitions.set(boundaryKey, identity);
		while (agent.transitions.size > this.#maximumTransitionsPerAgent) {
			agent.transitions.delete(agent.transitions.keys().next().value);
		}
		return true;
	}

	clear(agentId) { this.#last.delete(agentId); }
	clearAll() { this.#last.clear(); }
}
