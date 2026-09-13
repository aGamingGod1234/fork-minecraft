import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';

import { ArenaScriptInterpreter } from '../src/arena-script/interpreter.mjs';
import { parseArenaScript } from '../src/arena-script/parser.mjs';

// Pins the outcome of fact canonicalisation - accepted trees, rejection codes, rejection
// messages and the dotted label each rejection reports - so changes to the canonicalisation
// walk cannot quietly widen what the interpreter accepts or change which limit a hostile
// fact tree trips first. Regenerate ACCEPTED/RANDOM_DIGEST only alongside a deliberate,
// documented change to the fact contract.
const SOURCE = `
	program.onUnhandledAttention("continue_and_notify");
	program.watch(() => player.state().health < 5, { mode: "boundary" }, async () => { await player.wait(1); });
	await player.wait(1);
`;
const PROGRAM = parseArenaScript(SOURCE);
const BINDINGS = Object.freeze(Object.assign(Object.create(null), {
	player: Object.freeze(Object.assign(Object.create(null), {
		navigateTo: Object.freeze(Object.assign(Object.create(null), { primitive: 'navigate_to' })),
		wait: Object.freeze(Object.assign(Object.create(null), { primitive: 'wait' })),
	})),
}));

function baseFacts() {
	return {
		player: { x: 1, y: 2, z: 3, health: 20, food: 20, yaw: 0, pitch: 0, onGround: true, name: 'a' },
		world: { items: [], entities: [], blocks: [] },
		inventory: { items: [], tagCounts: {} },
	};
}

function withPlayer(extra) {
	const facts = baseFacts();
	Object.assign(facts.player, extra);
	return facts;
}

function candidate(index) {
	return { id: `e${index}`, kind: 'entity', x: index, y: 2, z: 3, distance: index, blockId: 'minecraft:stone', tags: ['a', 'b'] };
}

const cases = [];
const add = (name, build) => cases.push({ name, build });

add('plain', baseFacts);
add('big', () => {
	const facts = baseFacts();
	facts.world.entities = Array.from({ length: 64 }, (unused, index) => candidate(index));
	facts.world.blocks = Array.from({ length: 128 }, (unused, index) => candidate(index));
	return facts;
});
add('nested-arrays', () => { const facts = baseFacts(); facts.world.entities = [{ tags: [['deep'], ['deeper']] }]; return facts; });
add('symbol-key', () => withPlayer({ [Symbol('s')]: 1 }));
add('getter-key', () => { const facts = baseFacts(); Object.defineProperty(facts.player, 'ghost', { get: () => 1, enumerable: true, configurable: true }); return facts; });
add('setter-key', () => { const facts = baseFacts(); Object.defineProperty(facts.player, 'ghost', { set: () => {}, enumerable: true, configurable: true }); return facts; });
add('non-enumerable-key', () => { const facts = baseFacts(); Object.defineProperty(facts.player, 'hidden', { value: 1, enumerable: false, configurable: true }); return facts; });
add('forbidden-proto-key', () => { const facts = baseFacts(); Object.defineProperty(facts.player, '__proto__', { value: 1, enumerable: true, configurable: true }); return facts; });
add('forbidden-constructor-key', () => withPlayer({ constructor: 1 }));
add('forbidden-prototype-key', () => withPlayer({ prototype: 1 }));
add('bad-prototype', () => { const facts = baseFacts(); facts.world.entities = [Object.create({ evil: 1 })]; return facts; });
add('null-prototype-nested', () => { const facts = baseFacts(); const node = Object.create(null); node.id = 'x'; facts.world.entities = [node]; return facts; });
add('cycle', () => { const facts = baseFacts(); const node = { id: 'x' }; node.self = node; facts.world.entities = [node]; return facts; });
add('shared-node', () => { const facts = baseFacts(); const node = { id: 'x' }; facts.world.entities = [node, node]; return facts; });
add('proxy-record', () => { const facts = baseFacts(); facts.world.entities = [new Proxy({ id: 'x' }, {})]; return facts; });
add('proxy-array', () => { const facts = baseFacts(); facts.world.entities = new Proxy([{ id: 'x' }], {}); return facts; });
add('bigint-value', () => withPlayer({ big: 1n }));
add('symbol-value', () => withPlayer({ sym: Symbol('v') }));
add('function-value', () => withPlayer({ fn: () => 1 }));
add('nan-value', () => withPlayer({ nan: Number.NaN }));
add('infinity-value', () => withPlayer({ inf: Number.POSITIVE_INFINITY }));
add('undefined-value', () => withPlayer({ maybe: undefined }));
add('date-value', () => withPlayer({ when: new Date(0) }));
add('array-hole', () => { const facts = baseFacts(); const holey = [{ id: 'a' }]; holey.length = 3; facts.world.entities = holey; return facts; });
add('array-extra-string-key', () => { const facts = baseFacts(); const list = [{ id: 'a' }]; list.extra = 'x'; facts.world.entities = list; return facts; });
add('array-noncanonical-index-key', () => { const facts = baseFacts(); const list = [{ id: 'a' }]; list['01'] = 'x'; facts.world.entities = list; return facts; });
add('array-symbol-key', () => { const facts = baseFacts(); const list = [{ id: 'a' }]; list[Symbol('s')] = 'x'; facts.world.entities = list; return facts; });
add('array-getter-index', () => {
	const facts = baseFacts();
	const list = [{ id: 'a' }, { id: 'b' }];
	Object.defineProperty(list, 1, { get: () => ({ id: 'c' }), enumerable: true, configurable: true });
	facts.world.entities = list;
	return facts;
});
add('array-getter-index-and-huge-strings', () => {
	const facts = baseFacts();
	const list = [{ id: 'x'.repeat(4096) }, { id: 'b' }];
	Object.defineProperty(list, 1, { get: () => ({ id: 'c' }), enumerable: true, configurable: true });
	facts.world.entities = list;
	return facts;
});
add('huge-string', () => withPlayer({ name: 'x'.repeat(200000) }));
add('many-keys', () => { const facts = baseFacts(); const node = {}; for (let index = 0; index < 5000; index += 1) node[`k${index}`] = index; facts.world.entities = [node]; return facts; });
add('huge-array', () => { const facts = baseFacts(); facts.world.entities = Array.from({ length: 5000 }, (unused, index) => candidate(index)); return facts; });
add('deep-nesting', () => {
	const facts = baseFacts();
	let node = { id: 'leaf' };
	for (let index = 0; index < 40; index += 1) node = { child: node };
	facts.world.entities = [node];
	return facts;
});
add('unicode-strings', () => withPlayer({ name: 'héllo — 🧱 ünicode' }));
add('numeric-string-keys', () => { const facts = baseFacts(); facts.world.entities = [{ 0: 'a', 1: 'b', length: 2 }]; return facts; });
add('missing-inventory-tagcounts', () => { const facts = baseFacts(); delete facts.inventory.tagCounts; return facts; });
add('extra-inventory-key', () => { const facts = baseFacts(); facts.inventory.extra = 1; return facts; });
add('extra-root-key', () => { const facts = baseFacts(); facts.extra = 1; return facts; });
add('missing-root-key', () => { const facts = baseFacts(); delete facts.world; return facts; });
add('facts-not-object', () => 7);
add('facts-null', () => null);
add('facts-array', () => []);
add('world-array', () => { const facts = baseFacts(); facts.world = []; return facts; });
add('items-not-array', () => { const facts = baseFacts(); facts.world.items = { nope: 1 }; return facts; });

const RANDOM_CASES = 400;
let seed = 0x5eed;
function random() {
	seed = (seed * 1103515245 + 12345) & 0x7fffffff;
	return seed / 0x7fffffff;
}
function randomValue(depth) {
	const pick = Math.floor(random() * 10);
	if (depth > 3 || pick < 4) return [1, 'text', true, null, undefined, Number.NaN, 1n, -0, 1e308, 'ü🧱'][Math.floor(random() * 10)];
	if (pick < 7) return Array.from({ length: Math.floor(random() * 5) }, () => randomValue(depth + 1));
	const node = {};
	const keys = ['a', 'b', 'id', '__proto__', 'constructor', '0', '01', 'tags'];
	for (let index = 0; index < Math.floor(random() * 4); index += 1) node[keys[Math.floor(random() * keys.length)]] = randomValue(depth + 1);
	return node;
}
for (let index = 0; index < RANDOM_CASES; index += 1) {
	add(`random-${index}`, () => {
		const facts = baseFacts();
		facts.world.entities = [randomValue(0)];
		facts.player.extra = randomValue(0);
		return facts;
	});
}

function outcome(build) {
	const facts = build();
	const interpreter = new ArenaScriptInterpreter(PROGRAM, BINDINGS);
	try {
		const started = interpreter.start(facts);
		return { ok: true, started: JSON.stringify(started ?? null), watcher: JSON.stringify(interpreter.evaluateWatcher('watcher-0', facts) ?? null) };
	} catch (error) {
		return { code: error.code ?? null, message: error.message, name: error.name };
	}
}

const YIELDED_WAIT = '{"kind":"command","stepId":"step-172-186","call":{"primitive":"wait","arguments":1},"stateToken":"arena-state-1"}';
const accepted = { ok: true, started: YIELDED_WAIT, watcher: 'false' };
const rejected = (code, message) => ({ code, message: `ArenaScript ${code}: ${message}`, name: 'ArenaScriptError' });
const invalid = (message) => rejected('INVALID_FACTS', message);
const limit = (category) => rejected('FACT_LIMIT', `canonical ${category} limit exceeded`);

const ACCEPTED = {
	'plain': accepted,
	'big': accepted,
	'nested-arrays': accepted,
	'null-prototype-nested': accepted,
	'undefined-value': accepted,
	'unicode-strings': accepted,
	'numeric-string-keys': accepted,
	'array-noncanonical-index-key': accepted,
	'deep-nesting': accepted,
	'world-array': accepted,
	'symbol-key': invalid('facts.player cannot use symbols'),
	'getter-key': invalid('facts.player.ghost must be own data'),
	'setter-key': invalid('facts.player.ghost must be own data'),
	'non-enumerable-key': invalid('facts.player.hidden must be own data'),
	'forbidden-proto-key': invalid('forbidden facts.player key'),
	'forbidden-constructor-key': invalid('forbidden facts.player key'),
	'forbidden-prototype-key': invalid('forbidden facts.player key'),
	'bad-prototype': invalid('facts.world.entities.0 has an unsafe prototype'),
	'cycle': invalid('cyclic or proxy facts.world.entities.0.self'),
	'shared-node': invalid('cyclic or proxy facts.world.entities.1'),
	'proxy-record': invalid('cyclic or proxy facts.world.entities.0'),
	'proxy-array': invalid('cyclic or proxy facts.world.entities'),
	'bigint-value': invalid('facts.player.big must be a safe data value'),
	'symbol-value': invalid('facts.player.sym must be a safe data value'),
	'function-value': invalid('facts.player.fn must be a safe data value'),
	'nan-value': invalid('facts.player.nan must be a safe data value'),
	'infinity-value': invalid('facts.player.inf must be a safe data value'),
	'date-value': invalid('facts.player.when has an unsafe prototype'),
	'array-hole': invalid('unsafe facts.world.entities'),
	'array-extra-string-key': invalid('unsafe facts.world.entities'),
	'array-symbol-key': invalid('unsafe facts.world.entities'),
	'array-getter-index': invalid('unsafe facts.world.entities'),
	'array-getter-index-and-huge-strings': invalid('unsafe facts.world.entities'),
	'missing-inventory-tagcounts': invalid('facts.inventory has an invalid schema'),
	'extra-inventory-key': invalid('facts.inventory has an invalid schema'),
	'extra-root-key': invalid('facts has an invalid schema'),
	'missing-root-key': invalid('facts has an invalid schema'),
	'facts-not-object': invalid('facts must be a plain record'),
	'facts-null': invalid('facts must be a plain record'),
	'facts-array': invalid('facts must be a plain record'),
	'items-not-array': invalid('facts.world.items must be an array'),
	'huge-string': limit('string bytes'),
	'huge-array': limit('array length'),
	'many-keys': limit('keys'),
};

// sha256 over the outcomes of the seeded random trees, in case order.
const RANDOM_DIGEST = 'f1948a81561e7eb832b511e0bc6f9c4991fa66648e07f5a65b9d8fada1880d88';

const outcomes = new Map(cases.map(({ name, build }) => [name, outcome(build)]));

test('canonicalisation accepts and rejects each hand-written fact tree exactly as pinned', () => {
	assert.equal(Object.keys(ACCEPTED).length, cases.length - RANDOM_CASES);
	for (const [name, expected] of Object.entries(ACCEPTED)) assert.deepEqual(outcomes.get(name), expected, name);
});

test('an unsafe array is reported as unsafe rather than as a limit its earlier elements trip', () => {
	assert.deepEqual(outcomes.get('array-getter-index-and-huge-strings'), invalid('unsafe facts.world.entities'));
});

test('canonicalisation outcomes for the seeded random fact trees match the pinned digest', () => {
	const digest = createHash('sha256');
	for (const { name } of cases) {
		if (!name.startsWith('random-')) continue;
		digest.update(`${name}\u0000${JSON.stringify(outcomes.get(name))}\u0000`);
	}
	assert.equal(digest.digest('hex'), RANDOM_DIGEST);
});
