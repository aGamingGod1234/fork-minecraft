import { MAX_SUMMARY_LENGTH } from './constants.mjs';

const DIRECTIVES = new Set(['replace', 'continue', 'pause', 'finish']);
const DECISION_KEYS = new Set(['summary', 'directive', 'source']);
const MAX_SOURCE_LENGTH = 65_536;

export class DecisionError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'DecisionError';
		this.code = code;
	}
}

export function parseDecision(text) {
	if (typeof text !== 'string' || text.trim().length === 0) throw new DecisionError('EMPTY_DECISION', 'Planner decision must not be empty');
	const json = unwrapExactJson(text.trim());
	let value;
	try {
		value = JSON.parse(json);
	} catch (error) {
		throw new DecisionError('MALFORMED_DECISION', 'Planner output must contain only one JSON object', { cause: error });
	}
	assertNoDuplicateObjectKeys(json);
	if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new DecisionError('INVALID_DECISION', 'Planner decision must be a JSON object');
	if (Object.hasOwn(value, 'actions')) throw new DecisionError('INVALID_DECISION', 'Legacy action-array decisions are not supported; return ArenaScript source in the decision envelope');
	for (const key of Object.keys(value)) if (!DECISION_KEYS.has(key)) throw new DecisionError('UNKNOWN_DECISION_FIELD', `Unknown decision field '${key}'`);
	for (const key of ['summary', 'directive']) if (!Object.hasOwn(value, key)) throw new DecisionError('MISSING_DECISION_FIELD', `Decision field '${key}' is required`);
	if (typeof value.summary !== 'string' || value.summary.trim().length === 0 || value.summary.length > MAX_SUMMARY_LENGTH) throw new DecisionError('INVALID_DECISION', `Decision summary must be nonblank and at most ${MAX_SUMMARY_LENGTH} characters`);
	if (!DIRECTIVES.has(value.directive)) throw new DecisionError('INVALID_DECISION', `Unsupported directive '${String(value.directive)}'`);
	const hasSource = Object.hasOwn(value, 'source') && value.source !== null;

	if (value.directive === 'replace') {
		if (!hasSource || typeof value.source !== 'string' || value.source.trim().length === 0 || Buffer.byteLength(value.source, 'utf8') > MAX_SOURCE_LENGTH) {
			throw new DecisionError('DECISION_FIELD_MISMATCH', `replace directive requires nonblank source of at most ${MAX_SOURCE_LENGTH} UTF-8 bytes`);
		}
		return { summary: value.summary, directive: 'replace', source: value.source };
	}

	if (value.directive === 'finish') {
		if (hasSource) throw new DecisionError('DECISION_FIELD_MISMATCH', 'finish directive must not include source');
		return { summary: value.summary, directive: 'finish' };
	}

	if (hasSource) throw new DecisionError('DECISION_FIELD_MISMATCH', `${value.directive} directive must not include source`);
	return { summary: value.summary, directive: value.directive };
}

function unwrapExactJson(text) {
	if (text.startsWith('```')) {
		const match = /^```(?:json)?\s*\r?\n([\s\S]*?)\r?\n```$/i.exec(text);
		if (!match) throw new DecisionError('MALFORMED_DECISION', 'Planner output must contain only one JSON object in an optional JSON fence');
		return match[1].trim();
	}
	if (!text.startsWith('{') || !text.endsWith('}')) throw new DecisionError('MALFORMED_DECISION', 'Planner output must contain only one JSON object');
	return text;
}

function assertNoDuplicateObjectKeys(json) {
	const scanner = new DuplicateKeyScanner(json);
	scanner.parseValue();
	scanner.skipWhitespace();
	if (!scanner.done) throw new DecisionError('MALFORMED_DECISION', 'Planner output must contain only one JSON object');
}

class DuplicateKeyScanner {
	#text;
	#index = 0;

	constructor(text) { this.#text = text; }
get done() { return this.#index === this.#text.length; }

	skipWhitespace() { while (/\s/.test(this.#text[this.#index] ?? '')) this.#index += 1; }

	parseValue() {
		this.skipWhitespace();
		const next = this.#text[this.#index];
		if (next === '{') return this.parseObject();
		if (next === '[') return this.parseArray();
		if (next === '"') return this.parseString();
		while (this.#index < this.#text.length && !/[\s,}\]]/.test(this.#text[this.#index])) this.#index += 1;
	}

	parseObject() {
		this.#index += 1;
		const keys = new Set();
		this.skipWhitespace();
		if (this.#text[this.#index] === '}') { this.#index += 1; return; }
		while (true) {
			this.skipWhitespace();
			const key = this.parseString();
			if (keys.has(key)) throw new DecisionError('DUPLICATE_DECISION_FIELD', `Duplicate decision field '${key}'`);
			keys.add(key);
			this.skipWhitespace();
			this.#index += 1;
			this.parseValue();
			this.skipWhitespace();
			if (this.#text[this.#index] === '}') { this.#index += 1; return; }
			this.#index += 1;
		}
	}

	parseArray() {
		this.#index += 1;
		this.skipWhitespace();
		if (this.#text[this.#index] === ']') { this.#index += 1; return; }
		while (true) {
			this.parseValue();
			this.skipWhitespace();
			if (this.#text[this.#index] === ']') { this.#index += 1; return; }
			this.#index += 1;
		}
	}

	parseString() {
		const start = this.#index;
		this.#index += 1;
		while (this.#index < this.#text.length) {
			const character = this.#text[this.#index++];
			if (character === '\\') { this.#index += 1; continue; }
			if (character === '"') return JSON.parse(this.#text.slice(start, this.#index));
		}
		throw new DecisionError('MALFORMED_DECISION', 'Planner output must contain valid JSON strings');
	}
}
