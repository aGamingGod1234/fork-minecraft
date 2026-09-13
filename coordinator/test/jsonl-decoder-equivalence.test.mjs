import assert from 'node:assert/strict';
import test from 'node:test';

import { JsonlDecoder } from '../src/jsonl.mjs';

// Independent reference scanner written the way the decoder used to scan: regex
// whitespace classes, per-character string slicing, and JSON.parse for every name.
function referenceDuplicateKeyError(text) {
	let index = 0;
	const whitespace = () => { while (/\s/u.test(text[index] ?? '')) index += 1; };
	const string = () => {
		const start = index;
		if (text[index++] !== '"') throw new SyntaxError('Expected JSON string');
		let escaped = false;
		while (index < text.length) {
			const character = text[index++];
			if (escaped) { escaped = false; continue; }
			if (character === '\\') { escaped = true; continue; }
			if (character === '"') return JSON.parse(text.slice(start, index));
		}
		throw new SyntaxError('Unterminated JSON string');
	};
	const literal = () => {
		while (index < text.length && !/[\s,\]}]/u.test(text[index])) index += 1;
	};
	const value = () => {
		whitespace();
		if (text[index] === '{') {
			index += 1;
			const names = new Set();
			whitespace();
			if (text[index] === '}') { index += 1; return; }
			while (true) {
				whitespace();
				const name = string();
				if (names.has(name)) throw new SyntaxError(`Duplicate JSON object key '${name}'`);
				names.add(name);
				whitespace();
				if (text[index++] !== ':') throw new SyntaxError('Expected colon');
				value();
				whitespace();
				if (text[index] === '}') { index += 1; return; }
				if (text[index++] !== ',') throw new SyntaxError('Expected comma');
			}
		}
		if (text[index] === '[') {
			index += 1;
			whitespace();
			if (text[index] === ']') { index += 1; return; }
			while (true) {
				value();
				whitespace();
				if (text[index] === ']') { index += 1; return; }
				if (text[index++] !== ',') throw new SyntaxError('Expected comma');
			}
		}
		if (text[index] === '"') { string(); return; }
		literal();
	};
	value();
	whitespace();
	if (index !== text.length) throw new SyntaxError('Unexpected trailing JSON content');
}

function referenceOutcome(line) {
	try {
		referenceDuplicateKeyError(line);
		const value = JSON.parse(line);
		if (value === null || typeof value !== 'object' || Array.isArray(value)) return { code: 'INVALID_FRAME' };
		return { value };
	} catch {
		return { code: 'MALFORMED_JSON' };
	}
}

function decoderOutcome(line) {
	try {
		return { value: new JsonlDecoder().push(`${line}\n`)[0] };
	} catch (error) {
		return { code: error.code };
	}
}

const CASES = [
	'{"a":1}',
	'{}',
	'{"a":{},"b":[],"c":[{"d":1},{"d":2}]}',
	'{"a":"\\u0041\\n\\\\","b":"plain"}',
	'{"nested":{"same":1,"same":2}}',
	'{"a":1,"a":1}',
	'{"list":[1,2,{"k":true},null,-1.5e10]}',
	'{"unicode":"héllo 💥","tag":"minecraft:stone"}',
	'{"escaped\\"name":1,"escaped\\"name":2}',
	'{"a" : 1 ,\t"b"\r: 2}',
	'{"a":1} ',
	'{"a":1}{"b":2}',
	'{"a":',
	'{"a":1,}',
	'{"a"1}',
	'{bad}',
	'[1,2]',
	'42',
	'"text"',
	'{"a":\u00a01}',
	'{"\u00a0":1,"\u00a0":2}',
	'{"control":"a\u0001b"}',
	'{"a":tru}',
	'{"deep":{"deep":{"deep":{"k":1,"k":2}}}}',
];

test('the optimised scanner matches the reference scanner outcome for every frame', () => {
	for (const line of CASES) {
		const expected = referenceOutcome(line);
		const actual = decoderOutcome(line);
		if (expected.code) {
			assert.equal(actual.code, expected.code, `frame ${JSON.stringify(line)} rejection code`);
			continue;
		}
		assert.deepEqual(actual.value, expected.value, `frame ${JSON.stringify(line)} parsed value`);
	}
});

test('randomised protocol-shaped frames decode identically to the reference scanner', () => {
	let seed = 0x2f6e2b1;
	const random = () => {
		seed = (seed * 1_103_515_245 + 12_345) & 0x7fff_ffff;
		return seed / 0x8000_0000;
	};
	const names = ['agentId', 'seq', 'kind', 'payload', 'a"b', 'ünïcode', 'x\\y', 'blockId'];
	const scalars = ['1', '-2.5', 'true', 'false', 'null', '"minecraft:stone"', '"\\u00e9"', '""'];
	const buildObject = (depth) => {
		const entries = [];
		const count = 1 + Math.floor(random() * 4);
		for (let index = 0; index < count; index += 1) {
			const name = names[Math.floor(random() * names.length)];
			const body = depth > 0 && random() < 0.35
				? (random() < 0.5 ? buildObject(depth - 1) : `[${buildObject(depth - 1)},${scalars[Math.floor(random() * scalars.length)]}]`)
				: scalars[Math.floor(random() * scalars.length)];
			entries.push(`${JSON.stringify(name)}:${body}`);
		}
		return `{${entries.join(',')}}`;
	};

	let compared = 0;
	for (let iteration = 0; iteration < 2000; iteration += 1) {
		const line = buildObject(3);
		const expected = referenceOutcome(line);
		const actual = decoderOutcome(line);
		if (expected.code) assert.equal(actual.code, expected.code, line);
		else assert.deepEqual(actual.value, expected.value, line);
		compared += 1;
	}
	assert.equal(compared, 2000);
});

test('duplicate names are still rejected after the name-slicing fast path', () => {
	for (const line of ['{"a":1,"a":2}', '{"a\\u0062":1,"ab":2}', '{"p":{"q":1,"q":2}}', '{"l":[{"m":1,"m":2}]}']) {
		assert.throws(() => new JsonlDecoder().push(`${line}\n`), (error) => error.code === 'MALFORMED_JSON' && /Duplicate/.test(error.message), line);
	}
});

test('the shared UTF-8 decoder stays usable after an invalid frame', () => {
	const decoder = new JsonlDecoder();
	assert.throws(() => decoder.push(Buffer.from([0xff, 0x0a])), (error) => error.code === 'INVALID_ENCODING');
	assert.deepEqual(decoder.push('{"after":"invalid"}\n'), [{ after: 'invalid' }]);
});

test('a borrowed byte view is copied before the caller reuses it', () => {
	const decoder = new JsonlDecoder();
	const scratch = Buffer.from('{"partial":1}\n{"tail"', 'utf8');
	assert.deepEqual(decoder.push(scratch.subarray(0, 21)), [{ partial: 1 }]);
	scratch.fill(0x20, 14);
	assert.deepEqual(decoder.push(':2}\n'), [{ tail: 2 }]);
});

test('a rejected frame leaves no borrowed bytes behind', () => {
	const decoder = new JsonlDecoder();
	const scratch = Buffer.from('{"a":1,"a":2}\n{"next"', 'utf8');
	assert.throws(() => decoder.push(scratch), (error) => error.code === 'MALFORMED_JSON');
	scratch.fill(0x20, 14);
	assert.deepEqual(decoder.push(':3}\n'), [{ next: 3 }]);
});
