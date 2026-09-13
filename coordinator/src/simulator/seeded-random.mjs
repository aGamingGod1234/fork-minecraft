const UINT32_RANGE = 0x1_0000_0000;

/** Small deterministic PRNG used only by scenario-declared random events. */
export class SeededRandom {
	#state;

	constructor(seed = 0) {
		this.#state = seedToUint32(seed);
	}

	next() {
		let value = (this.#state += 0x6D2B79F5) >>> 0;
		value = Math.imul(value ^ (value >>> 15), value | 1);
		value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
		return ((value ^ (value >>> 14)) >>> 0) / UINT32_RANGE;
	}

	nextInt(maxExclusive) {
		if (!Number.isSafeInteger(maxExclusive) || maxExclusive <= 0) throw new RangeError('maxExclusive must be a positive safe integer');
		return Math.floor(this.next() * maxExclusive);
	}

	pick(values) {
		if (!Array.isArray(values) || values.length === 0) throw new RangeError('values must be a non-empty array');
		return values[this.nextInt(values.length)];
	}
}

export function seedToUint32(seed) {
	if (typeof seed === 'number' && Number.isFinite(seed)) return Math.trunc(seed) >>> 0;
	const text = typeof seed === 'string' ? seed : JSON.stringify(seed ?? null);
	let hash = 0x811C9DC5;
	for (const character of text) {
		hash ^= character.codePointAt(0);
		hash = Math.imul(hash, 0x01000193);
	}
	return hash >>> 0;
}
