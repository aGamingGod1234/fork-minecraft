import { createHash } from 'node:crypto';

export class TtsCache {
	#entries = new Map();
	#bytes = 0;
	#maxBytes;

	constructor({ maxBytes = 64 * 1024 * 1024 } = {}) {
		if (!Number.isSafeInteger(maxBytes) || maxBytes < 1) throw new TypeError('maxBytes must be positive');
		this.#maxBytes = maxBytes;
	}

	get(key) {
		const entry = this.#entries.get(key);
		if (entry === undefined) return null;
		this.#entries.delete(key);
		this.#entries.set(key, entry);
		return Buffer.from(entry);
	}

	set(key, value) {
		if (typeof key !== 'string' || key === '') throw new TypeError('cache key must not be blank');
		if (!Buffer.isBuffer(value)) throw new TypeError('cache value must be a Buffer');
		const previous = this.#entries.get(key);
		if (previous !== undefined) this.#bytes -= previous.length;
		this.#entries.delete(key);
		if (value.length > this.#maxBytes) return;
		const copy = Buffer.from(value);
		this.#entries.set(key, copy);
		this.#bytes += copy.length;
		while (this.#bytes > this.#maxBytes) {
			const oldestKey = this.#entries.keys().next().value;
			const removed = this.#entries.get(oldestKey);
			this.#entries.delete(oldestKey);
			this.#bytes -= removed.length;
		}
	}

	static key(parts) {
		return createHash('sha256').update(JSON.stringify(parts)).digest('hex');
	}
}
