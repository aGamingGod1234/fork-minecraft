import { WorkLeaseSupervisor } from './work-lease-supervisor.mjs';

/** Compatibility-facing coordinator API backed by deadline-bound work leases. */
export class ActiveGoalSupervisor {
	#leases;

	constructor(options = {}) {
		this.#leases = new WorkLeaseSupervisor(options);
	}

	activate(key) { return this.#leases.activate(key); }
	begin(key, kind, options) { return this.#leases.acquire(key, kind, options); }

	end(token, { progress = false, scheduleRecovery = true } = {}) {
		if (progress) this.#leases.progress(token);
		return this.#leases.release(token, { scheduleRecovery });
	}

	progress(token) { return this.#leases.progress(token); }
	observed(key) { return this.#leases.observed(key); }
	recover(key, details) { return this.#leases.recover(key, details); }
	ensure(key) { return this.#leases.ensure(key); }
	factualProgress(key, signature, details) { return this.#leases.factualProgress(key, signature, details); }
	suspend(key) { return this.#leases.suspend(key); }
	terminate(key) { return this.#leases.terminate(key); }
	snapshot(key) { return this.#leases.snapshot(key); }
	close() { this.#leases.close(); }
}
