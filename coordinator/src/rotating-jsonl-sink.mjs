import { appendFile, rename, stat, unlink } from 'node:fs/promises';

const DEFAULT_MAX_BYTES = 64 * 1_024 * 1_024;
const DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1_000;
const DEFAULT_GENERATIONS = 3;

export class RotatingJsonlSink {
	#filePath;
	#append;
	#stat;
	#rename;
	#unlink;
	#maxBytes;
	#maxAgeMs;
	#generations;
	#now;
	#inspect;
	#activeSince = null;

	constructor(filePath, dependencies = {}) {
		if (typeof filePath !== 'string' || filePath.trim() === '') throw new TypeError('rotating JSONL file path must be nonblank');
		this.#filePath = filePath;
		this.#append = dependencies.appendFile ?? appendFile;
		this.#stat = dependencies.stat ?? stat;
		this.#rename = dependencies.rename ?? rename;
		this.#unlink = dependencies.unlink ?? unlink;
		this.#maxBytes = positiveInteger(dependencies.maxFileBytes ?? DEFAULT_MAX_BYTES, 'maxFileBytes');
		this.#maxAgeMs = positiveInteger(dependencies.maxFileAgeMs ?? DEFAULT_MAX_AGE_MS, 'maxFileAgeMs');
		this.#generations = positiveInteger(dependencies.retainedGenerations ?? DEFAULT_GENERATIONS, 'retainedGenerations');
		this.#now = dependencies.now ?? Date.now;
		this.#inspect = dependencies.inspect !== false;
		for (const [field, operation] of Object.entries({ appendFile: this.#append, stat: this.#stat, rename: this.#rename, unlink: this.#unlink, now: this.#now })) {
			if (typeof operation !== 'function') throw new TypeError(`${field} must be a function`);
		}
	}

	async append(encoded, options) {
		if (!this.#inspect) {
			await this.#append(this.#filePath, encoded, options);
			return;
		}
		let metadata = null;
		try { metadata = await this.#stat(this.#filePath); }
		catch (error) { if (error?.code !== 'ENOENT') throw error; }
		if (metadata !== null && this.#activeSince === null) {
			this.#activeSince = Number.isFinite(metadata.birthtimeMs) && metadata.birthtimeMs > 0
				? metadata.birthtimeMs : metadata.mtimeMs;
		}
		if (metadata !== null && (metadata.size + Buffer.byteLength(encoded, 'utf8') > this.#maxBytes
				|| this.#now() - this.#activeSince >= this.#maxAgeMs)) await this.#rotate();
		await this.#append(this.#filePath, encoded, options);
		this.#activeSince ??= this.#now();
	}

	async #rotate() {
		await ignoreMissing(() => this.#unlink(`${this.#filePath}.${this.#generations}`));
		for (let generation = this.#generations - 1; generation >= 1; generation -= 1) {
			await ignoreMissing(() => this.#rename(`${this.#filePath}.${generation}`, `${this.#filePath}.${generation + 1}`));
		}
		await ignoreMissing(() => this.#rename(this.#filePath, `${this.#filePath}.1`));
		this.#activeSince = this.#now();
	}
}

async function ignoreMissing(operation) {
	try { await operation(); }
	catch (error) { if (error?.code !== 'ENOENT') throw error; }
}

function positiveInteger(value, field) {
	if (!Number.isSafeInteger(value) || value < 1) throw new TypeError(`${field} must be a positive safe integer`);
	return value;
}
