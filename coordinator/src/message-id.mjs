class MessageIdError extends Error {
	constructor(code, message) { super(message); this.name = 'MessageIdError'; this.code = code; }
}

export class MessageIdGenerator {
	#prefix;
	#sequence = 0;

	constructor(prefix = 'coordinator') {
		if (typeof prefix !== 'string' || prefix.trim().length === 0 || prefix.length > 126) {
			throw new TypeError('message ID prefix must be nonblank and leave room within the protocol limit');
		}
		this.#prefix = prefix;
	}

	next() {
		if (this.#sequence === Number.MAX_SAFE_INTEGER) throw new MessageIdError('MESSAGE_ID_EXHAUSTED', 'Message ID sequence is exhausted');
		this.#sequence += 1;
		const value = `${this.#prefix}-${this.#sequence}`;
		if (value.length > 128) throw new MessageIdError('MESSAGE_ID_EXHAUSTED', 'Message ID exceeds the protocol limit');
		return value;
	}
}
