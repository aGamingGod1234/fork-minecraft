import { TextDecoder } from 'node:util';

import { MAX_LINE_BYTES } from './constants.mjs';

const NEWLINE = 0x0a;
const CARRIAGE_RETURN = 0x0d;
const EMPTY = Buffer.alloc(0);
const UTF8 = new TextDecoder('utf-8', { fatal: true });
const QUOTE = 0x22;
const COMMA = 0x2c;
const COLON = 0x3a;
const OPEN_BRACKET = 0x5b;
const BACKSLASH = 0x5c;
const CLOSE_BRACKET = 0x5d;
const OPEN_BRACE = 0x7b;
const CLOSE_BRACE = 0x7d;

// The set matched by the /\s/u character class. Only the first four are legal JSON
// whitespace; the rest keep the scanner from rejecting a frame before JSON.parse does.
function isWhitespace(code) {
	if (code === 0x20) return true;
	if (code >= 0x09 && code <= 0x0d) return true;
	if (code < 0xa0) return false;
	return code === 0xa0 || code === 0x1680 || (code >= 0x2000 && code <= 0x200a)
		|| code === 0x2028 || code === 0x2029 || code === 0x202f || code === 0x205f
		|| code === 0x3000 || code === 0xfeff;
}

export class JsonlError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'JsonlError';
		this.code = code;
	}
}

export class JsonlDecoder {
	#segments = [];
	#segmentIndex = 0;
	#segmentOffset = 0;
	#bufferedBytes = 0;
	#consumedBytes = 0;
	#appendedBytes = 0;
	#newlineOffsets = [];
	#newlineIndex = 0;
	#maxBytes;

	constructor({ maxBytes = MAX_LINE_BYTES } = {}) {
		if (!Number.isSafeInteger(maxBytes) || maxBytes < 1) {
			throw new TypeError('maxBytes must be a positive safe integer');
		}
		this.#maxBytes = maxBytes;
	}

	push(chunk) {
		if (typeof chunk !== 'string' && !ArrayBuffer.isView(chunk)) {
			throw new TypeError('JSONL chunk must be a string or byte view');
		}
		const bytes = typeof chunk === 'string' ? Buffer.from(chunk, 'utf8') : Buffer.from(chunk.buffer, chunk.byteOffset, chunk.byteLength);
		const appendedSegmentIndex = this.#segments.length;
		this.#append(bytes, typeof chunk !== 'string');
		const messages = [];
		try {
			while (true) {
				const newlineIndex = this.#nextNewlineOffset();
				if (newlineIndex === -1) break;
				if (newlineIndex > this.#maxBytes) this.#throwOversized(newlineIndex);
				let line = this.#read(newlineIndex);
				this.#consume(1);
				if (line.at(-1) === CARRIAGE_RETURN) line = line.subarray(0, -1);
				messages.push(parseObject(line));
			}
			if (this.#bufferedBytes > this.#maxBytes) this.#throwOversized(this.#bufferedBytes);
		} finally {
			this.#detachBorrowedSegments(appendedSegmentIndex);
			this.#compactStorage();
		}
		return messages;
	}

	finish() {
		if (this.#bufferedBytes !== 0) {
			throw new JsonlError('INCOMPLETE_FRAME', 'Incomplete JSONL frame at end of stream');
		}
	}

	#throwOversized(actualBytes) {
		this.#clear();
		throw new JsonlError('LINE_TOO_LARGE', `JSONL frame is ${actualBytes} UTF-8 bytes and exceeds ${this.#maxBytes} UTF-8 bytes`);
	}

	#append(bytes, borrowed) {
		if (bytes.length === 0) return;
		const start = this.#appendedBytes;
		for (let offset = bytes.indexOf(NEWLINE); offset !== -1; offset = bytes.indexOf(NEWLINE, offset + 1)) {
			this.#newlineOffsets.push(start + offset);
		}
		this.#appendedBytes += bytes.length;
		this.#bufferedBytes += bytes.length;
		this.#segments.push({ bytes, borrowed });
	}

	#nextNewlineOffset() {
		while (this.#newlineIndex < this.#newlineOffsets.length && this.#newlineOffsets[this.#newlineIndex] < this.#consumedBytes) {
			this.#newlineIndex += 1;
		}
		if (this.#newlineIndex === this.#newlineOffsets.length) return -1;
		return this.#newlineOffsets[this.#newlineIndex] - this.#consumedBytes;
	}

	#read(length) {
		if (length === 0) return EMPTY;
		const current = this.#segments[this.#segmentIndex];
		const available = current.bytes.length - this.#segmentOffset;
		if (available >= length) {
			const line = current.bytes.subarray(this.#segmentOffset, this.#segmentOffset + length);
			this.#consume(length);
			return line;
		}

		const line = Buffer.allocUnsafe(length);
		let copied = 0;
		while (copied < length) {
			const segment = this.#segments[this.#segmentIndex];
			const segmentAvailable = segment.bytes.length - this.#segmentOffset;
			const amount = Math.min(segmentAvailable, length - copied);
			segment.bytes.copy(line, copied, this.#segmentOffset, this.#segmentOffset + amount);
			this.#consume(amount);
			copied += amount;
		}
		return line;
	}

	#consume(length) {
		this.#consumedBytes += length;
		this.#bufferedBytes -= length;
		while (length > 0) {
			const segment = this.#segments[this.#segmentIndex];
			const available = segment.bytes.length - this.#segmentOffset;
			const amount = Math.min(available, length);
			this.#segmentOffset += amount;
			length -= amount;
			if (this.#segmentOffset === segment.bytes.length) {
				this.#segmentIndex += 1;
				this.#segmentOffset = 0;
			}
		}
	}

	#detachBorrowedSegments(appendedSegmentIndex) {
		for (let index = Math.max(this.#segmentIndex, appendedSegmentIndex); index < this.#segments.length; index += 1) {
			const segment = this.#segments[index];
			if (!segment.borrowed) continue;
			segment.bytes = Buffer.from(segment.bytes.subarray(index === this.#segmentIndex ? this.#segmentOffset : 0));
			segment.borrowed = false;
			if (index === this.#segmentIndex) this.#segmentOffset = 0;
		}
	}

	#compactStorage() {
		if (this.#bufferedBytes === 0) {
			this.#clear();
			return;
		}
		if (this.#segmentIndex >= 64 && this.#segmentIndex * 2 >= this.#segments.length) {
			this.#segments = this.#segments.slice(this.#segmentIndex);
			this.#segmentIndex = 0;
		}
		if (this.#newlineIndex >= 64 && this.#newlineIndex * 2 >= this.#newlineOffsets.length) {
			this.#newlineOffsets = this.#newlineOffsets.slice(this.#newlineIndex);
			this.#newlineIndex = 0;
		}
	}

	#clear() {
		this.#segments = [];
		this.#segmentIndex = 0;
		this.#segmentOffset = 0;
		this.#bufferedBytes = 0;
		this.#consumedBytes = 0;
		this.#appendedBytes = 0;
		this.#newlineOffsets = [];
		this.#newlineIndex = 0;
	}
}

export function encodeJsonLine(value, { maxBytes = MAX_LINE_BYTES } = {}) {
	if (!isPlainObject(value)) throw new JsonlError('INVALID_FRAME', 'JSONL value must be an object');
	let encoded;
	try {
		encoded = JSON.stringify(value);
	} catch (error) {
		throw new JsonlError('ENCODING_FAILED', `Could not encode JSONL object: ${error.message}`, { cause: error });
	}
	if (encoded === undefined) throw new JsonlError('ENCODING_FAILED', 'Could not encode JSONL object');
	const bytes = Buffer.byteLength(encoded, 'utf8');
	if (bytes > maxBytes) throw new JsonlError('LINE_TOO_LARGE', `JSONL frame is ${bytes} UTF-8 bytes and exceeds ${maxBytes} UTF-8 bytes`);
	return `${encoded}\n`;
}

function parseObject(bytes) {
	if (bytes.length === 0) throw new JsonlError('MALFORMED_JSON', 'JSONL frame must not be blank');
	let text;
	try {
		text = UTF8.decode(bytes);
	} catch (error) {
		throw new JsonlError('INVALID_ENCODING', 'JSONL frame must be valid UTF-8', { cause: error });
	}
	let value;
	try {
		assertNoDuplicateKeys(text);
		value = JSON.parse(text);
	} catch (error) {
		throw new JsonlError('MALFORMED_JSON', `Malformed JSONL frame: ${error.message}`, { cause: error });
	}
	if (!isPlainObject(value)) throw new JsonlError('INVALID_FRAME', 'JSONL frame must be an object');
	return value;
}

function isPlainObject(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
	const prototype = Object.getPrototypeOf(value);
	return prototype === Object.prototype || prototype === null;
}

// JSON.parse deliberately accepts duplicate names by retaining the last one. The
// bridge treats those as ambiguous identities, so scan the already-valid JSON
// grammar before materialising the object.
function assertNoDuplicateKeys(text) {
	const length = text.length;
	let index = 0;
	const whitespace = () => { while (isWhitespace(text.charCodeAt(index))) index += 1; };
	const string = () => {
		const start = index;
		if (text.charCodeAt(index++) !== QUOTE) throw new SyntaxError('Expected JSON string');
		let literalName = true;
		while (index < length) {
			const code = text.charCodeAt(index++);
			if (code === BACKSLASH) { literalName = false; index += 1; continue; }
			if (code < 0x20) literalName = false;
			if (code === QUOTE) {
				const raw = text.slice(start + 1, index - 1);
				// An unescaped, control-free name already equals its decoded form, so the
				// escape-decoding parse is only needed for the rest.
				return literalName ? raw : JSON.parse(text.slice(start, index));
			}
		}
		throw new SyntaxError('Unterminated JSON string');
	};
	const literal = () => {
		while (index < length) {
			const code = text.charCodeAt(index);
			if (code === COMMA || code === CLOSE_BRACKET || code === CLOSE_BRACE || isWhitespace(code)) break;
			index += 1;
		}
	};
	const value = () => {
		whitespace();
		if (text.charCodeAt(index) === OPEN_BRACE) {
			index += 1;
			const names = new Set();
			whitespace();
			if (text.charCodeAt(index) === CLOSE_BRACE) { index += 1; return; }
			while (true) {
				whitespace();
				const name = string();
				if (names.has(name)) throw new SyntaxError(`Duplicate JSON object key '${name}'`);
				names.add(name);
				whitespace();
				if (text.charCodeAt(index++) !== COLON) throw new SyntaxError('Expected colon');
				value();
				whitespace();
				if (text.charCodeAt(index) === CLOSE_BRACE) { index += 1; return; }
				if (text.charCodeAt(index++) !== COMMA) throw new SyntaxError('Expected comma');
			}
		}
		if (text.charCodeAt(index) === OPEN_BRACKET) {
			index += 1;
			whitespace();
			if (text.charCodeAt(index) === CLOSE_BRACKET) { index += 1; return; }
			while (true) {
				value();
				whitespace();
				if (text.charCodeAt(index) === CLOSE_BRACKET) { index += 1; return; }
				if (text.charCodeAt(index++) !== COMMA) throw new SyntaxError('Expected comma');
			}
		}
		if (text.charCodeAt(index) === QUOTE) { string(); return; }
		literal();
	};
	value();
	whitespace();
	if (index !== text.length) throw new SyntaxError('Unexpected trailing JSON content');
}
