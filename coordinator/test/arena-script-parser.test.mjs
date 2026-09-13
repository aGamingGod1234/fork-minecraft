import assert from 'node:assert/strict';
import test from 'node:test';

import { parseArenaScript } from '../src/arena-script/parser.mjs';
import { FACT_DOMAIN } from '../src/arena-script/fact-domains.mjs';

test('compiles a bounded program and records its model-owned policy', () => {
	const compiled = parseArenaScript(`
		program.onUnhandledAttention("continue_and_notify");
		await program.repeatUntil(
			() => inventory.countTag("#minecraft:logs") >= 8,
			{ maxIterations: 16 },
			async () => { await player.wait(1); }
		);
	`);
	assert.equal(compiled.unhandledPolicy, 'continue_and_notify');
	assert.equal(compiled.watcherCount, 0);
	assert.ok(compiled.nodeCount > 0);
	assert.ok(compiled.stepLocations.size > 0);
	assert.ok(Object.isFrozen(compiled));
	assert.ok(Object.isFrozen(compiled.ast));
	assert.ok(Object.isFrozen(compiled.stepLocations));
});

test('admits the exact Task 2 direct action and terminal API calls', () => {
	assert.doesNotThrow(() => parseArenaScript(`
		program.onUnhandledAttention("continue_and_notify");
		const moved = await tryResult(player.navigateTo({ x: 4, y: 64, z: 2, tolerance: 1, sprint: false, timeoutMs: 5_000 }));
		if (!moved.succeeded) program.checkpoint(moved.reason);
		program.finish("arrived");
	`));
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); const move = player.navigateTo; await move({ x: 1, y: 2, z: 3 });',
		'program.onUnhandledAttention("continue_and_notify"); const finish = program.finish; finish("escaped");',
		'program.onUnhandledAttention("continue_and_notify"); const checkpoint = program.checkpoint; checkpoint("escaped");',
	]) {
		assert.throws(() => parseArenaScript(source), (error) => error.code === 'UNSUPPORTED_SYNTAX');
	}
});

test('requires exact observed target ids for attack and ranged use', () => {
	assert.doesNotThrow(() => parseArenaScript(`
		program.onUnhandledAttention("continue_and_notify");
		const target = world.nearest(world.entities());
		await player.attack({ targetId: target.stableId, timeoutMs: 5_000 });
		await player.useRanged({ targetId: target.stableId, drawDurationMs: 1_000, timeoutMs: 5_000 });
	`));
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); await player.attack({ targetSelector: "nearest_hostile", timeoutMs: 1 });',
		'program.onUnhandledAttention("continue_and_notify"); await player.useRanged({ targetSelector: "nearest_hostile", drawDurationMs: 1, timeoutMs: 1 });',
	]) {
		assert.throws(() => parseArenaScript(source), (error) => error.code === 'UNSUPPORTED_SYNTAX');
	}
});

test('rejects navigateTo coordinates derived from an observed floating item', () => {
	assert.throws(() => parseArenaScript(`
		program.onUnhandledAttention("continue_and_notify");
		const drop = world.nearest(world.items({ tag: "#minecraft:logs" }));
		if (drop !== null) await player.navigateTo({ x: drop.x, y: drop.y, z: drop.z, tolerance: 1, sprint: false, timeoutMs: 5_000 });
	`), (error) => error.code === 'UNSUPPORTED_SYNTAX' && /floating item coordinates/i.test(error.message));
});

test('preserves navigation to block, entity, and player-state coordinates', () => {
	for (const source of [
		'const target = world.nearest(world.blocks({ tag: "#minecraft:logs" }));',
		'const target = world.nearest(world.entities());',
		'const target = player.state();',
	]) {
		assert.doesNotThrow(() => parseArenaScript(`
			program.onUnhandledAttention("continue_and_notify");
			${source}
			await player.navigateTo({ x: target.x, y: target.y, z: target.z, tolerance: 1, sprint: false, timeoutMs: 5_000 });
		`));
	}
});

test('admits only factual observed-candidate queries in watcher conditions', () => {
	assert.doesNotThrow(() => parseArenaScript(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => world.nearest(world.items({ itemId: "minecraft:oak_log" })) !== null, { mode: "boundary" }, async () => {});
	`));
	assert.throws(() => parseArenaScript('program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.wait(1), { mode: "boundary" }, async () => {});'), (error) => error.code === 'UNSUPPORTED_SYNTAX');
});

test('extended fact and math reads are pure while inspection and notebook operations require continuations', () => {
	const compiled = parseArenaScript(`program.onUnhandledAttention("continue_and_notify");
		program.watch(() => world.menu() !== null, { mode: "boundary" }, async () => {});
		program.watch(() => inventory.slots({ itemId: "minecraft:diamond" }).length > 0, { mode: "boundary" }, async () => {});
		program.watch(() => math.abs(player.state().velocity.y) > 0.5, { mode: "boundary" }, async () => {});
	`);
	assert.equal(compiled.watchers[0].factDependencyMask, FACT_DOMAIN.menu);
	assert.equal(compiled.watchers[1].factDependencyMask, FACT_DOMAIN.inventoryItems);
	assert.equal(compiled.watchers[2].factDependencyMask, FACT_DOMAIN.player);
	for (const operation of ['inspect', 'remember', 'queryMemory']) {
		assert.throws(() => parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); program.watch(() => world.${operation}({}), { mode: "boundary" }, async () => {});`), (error) => error.code === 'UNSUPPORTED_SYNTAX');
	}
});

test('requires mine calls to carry the observed non-air block id', () => {
	assert.doesNotThrow(() => parseArenaScript('program.onUnhandledAttention("continue_and_notify"); await player.mine({ x: 1, y: 64, z: 0, expectedBlockId: "minecraft:stone" });'));
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); await player.mine({ x: 1, y: 64, z: 0 });',
		'program.onUnhandledAttention("continue_and_notify"); await player.mine({ x: 1, y: 64, z: 0, expectedBlockId: "minecraft:air" });',
	]) {
		assert.throws(() => parseArenaScript(source), (error) => error.code === 'INVALID_ARENA_SCRIPT_COMMAND');
	}
});

test('precomputes conservative watcher mode and fact dependency metadata', () => {
	const compiled = parseArenaScript(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => player.state().health < 10, { mode: "interrupt" }, async () => {});
		program.watch(() => world.nearest(world.items({ itemId: "minecraft:oak_log" })) !== null, { mode: "boundary" }, async () => {});
		program.watch(() => inventory.countTag("#minecraft:logs") > 0, { mode: "boundary" }, async () => {});
	`);
	assert.deepEqual(compiled.watchers.map(({ id, mode, factDependencyMask }) => ({ id, mode, factDependencyMask })), [
		{ id: 'watcher-0', mode: 'interrupt', factDependencyMask: FACT_DOMAIN.player },
		{ id: 'watcher-1', mode: 'boundary', factDependencyMask: FACT_DOMAIN.player | FACT_DOMAIN.worldItems },
		{ id: 'watcher-2', mode: 'boundary', factDependencyMask: FACT_DOMAIN.inventoryTagCounts },
	]);

	const closure = parseArenaScript(`
		program.onUnhandledAttention("continue_and_notify");
		const threshold = 10;
		program.watch(() => player.state().health < threshold, { mode: "boundary" }, async () => {});
	`);
	assert.equal(closure.watchers[0].factDependencyMask, null, 'closure state must force evaluation on every observation');
});

test('requires watchers to appear in the top-level registration prologue', () => {
	assert.throws(() => parseArenaScript(`
		program.onUnhandledAttention("continue_and_notify");
		await player.wait(1);
		program.watch(() => true, { mode: "boundary" }, async () => {});
	`), (error) => error.code === 'UNSUPPORTED_SYNTAX');
});

test('rejects side-effecting watcher and repeatUntil conditions', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); program.watch(async () => { await player.navigateTo({ x: 1 }); return false; }, { mode: "boundary" }, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => { program.finish("escaped"); return false; }, { maxIterations: 1 }, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); program.watch(() => program.checkpoint("escaped"), { mode: "boundary" }, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => program.watch(() => true, { mode: "boundary" }, async () => {}), { maxIterations: 1 }, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); program.watch(() => player.navigateTo, { mode: "boundary" }, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => player.wait, { maxIterations: 1 }, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); const move = player.navigateTo; program.watch(() => move, { mode: "boundary" }, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); const p = player; program.watch(() => p.moveTo, { mode: "boundary" }, async () => {});',
	]) assert.throws(() => parseArenaScript(source), (error) => error.code === 'UNSUPPORTED_SYNTAX');
});

test('rejects watcher speech and terminal control, including helper-mediated calls', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); program.watch(() => true, { mode: "boundary" }, async () => { await player.chat("hello"); });',
		'program.onUnhandledAttention("continue_and_notify"); program.watch(() => true, { mode: "boundary" }, async () => { program.finish("done"); });',
		'program.onUnhandledAttention("continue_and_notify"); program.watch(() => true, { mode: "boundary" }, async () => { await speak(); }); const speak = async () => { await player.chat("hello"); };',
		'program.onUnhandledAttention("continue_and_notify"); program.watch(() => true, { mode: "boundary" }, async () => { await stop(); }); const stop = async () => { program.finish("done"); };',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'UNSUPPORTED_SYNTAX' && /watcher/i.test(error.message),
		);
	}
});

test('accepts model-authored locals, conditionals, bounded for loops, and watchers', () => {
	const compiled = parseArenaScript(`
		program.onUnhandledAttention("pause_and_notify");
		const limit = 3;
		let total = 0;
		program.watch(
			() => player.state().health < 10,
			{ mode: "interrupt" },
			async () => { await player.wait(1); }
		);
		for (let index = 0; index < 3; index += 1) {
			if (index > 1) total += 1;
		}
	`);
	assert.equal(compiled.unhandledPolicy, 'pause_and_notify');
	assert.equal(compiled.watcherCount, 1);
});

test('rejects local identifiers outside their lexical lifetime', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); { const hidden = 1; } await player.wait(hidden);',
		'program.onUnhandledAttention("continue_and_notify"); for (let index = 0; index < 1; index += 1) {} await player.wait(index);',
		'program.onUnhandledAttention("continue_and_notify"); function local(value) {} await player.wait(value);',
		'program.onUnhandledAttention("continue_and_notify"); await player.wait(later); const later = 1;',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSAFE_MEMBER_ACCESS',
		);
	}
});

test('preserves hoisted functions and deferred closure references', () => {
	assert.doesNotThrow(() => parseArenaScript(`
		program.onUnhandledAttention("continue_and_notify");
		program.watch(() => true, { mode: "boundary" }, async () => { await later(); });
		const later = async () => { await player.wait(1); };
		hoisted();
		function hoisted() {}
	`));
});

test('rejects immediate local calls before their closure dependencies are initialized', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); const use = () => later; use(); const later = 1; await player.wait(1);',
		'program.onUnhandledAttention("continue_and_notify"); const first = () => second(); const second = () => later; first(); const later = 1; await player.wait(1);',
		'program.onUnhandledAttention("continue_and_notify"); const outer = () => { const use = () => later; use(); const later = 1; }; outer(); await player.wait(1);',
		'program.onUnhandledAttention("continue_and_notify"); const outer = () => { const first = () => second(); const second = () => later; first(); const later = 1; }; outer(); await player.wait(1);',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSAFE_MEMBER_ACCESS',
		);
	}
	assert.doesNotThrow(
		() => parseArenaScript('program.onUnhandledAttention("continue_and_notify"); const use = () => later; const later = 1; use(); await player.wait(1);'),
	);
});

test('rejects deferred inline callbacks that close over later local bindings', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); program.watch(() => later, { mode: "boundary" }, async () => {}); await player.wait(1); const later = true;',
		'program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => false, { maxIterations: 1 }, async () => { await player.wait(later); }); const later = 1;',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSAFE_MEMBER_ACCESS',
		);
	}
});

test('rejects source over the configured byte limit', () => {
	assert.throws(
		() => parseArenaScript('x'.repeat(20), { limits: { sourceBytes: 4 } }),
		(error) => error.code === 'SOURCE_TOO_LARGE' && error.name === 'ArenaScriptError',
	);
});

test('rejects malformed source with a stable syntax error', () => {
	assert.throws(
		() => parseArenaScript('program.onUnhandledAttention("continue_and_notify";'),
		(error) => error.code === 'SYNTAX_ERROR' && error.name === 'ArenaScriptError',
	);
});

test('rejects unsupported syntax and ASTs over the configured limit', () => {
	assert.throws(
		() => parseArenaScript('program.onUnhandledAttention("continue_and_notify"); class Secret {}'),
		(error) => error.code === 'UNSUPPORTED_SYNTAX',
	);
	assert.throws(
		() => parseArenaScript('program.onUnhandledAttention("continue_and_notify"); 1 + 2;', { limits: { astNodes: 3 } }),
		(error) => error.code === 'AST_TOO_LARGE',
	);
});

test('requires exactly one supported unhandled-attention policy', () => {
	assert.throws(
		() => parseArenaScript('const value = 1;'),
		(error) => error.code === 'MISSING_UNHANDLED_POLICY',
	);
	assert.throws(
		() => parseArenaScript(`
			program.onUnhandledAttention("continue_and_notify");
			program.onUnhandledAttention("pause_and_notify");
		`),
		(error) => error.code === 'UNSUPPORTED_SYNTAX',
	);
	assert.throws(
		() => parseArenaScript('program.onUnhandledAttention("ignore");'),
		(error) => error.code === 'UNSUPPORTED_SYNTAX',
	);
});

test('rejects computed and prototype member access', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); player["constructor"];',
		'program.onUnhandledAttention("continue_and_notify"); player.constructor;',
		'program.onUnhandledAttention("continue_and_notify"); globalThis.process.exit(0);',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'UNSAFE_MEMBER_ACCESS',
		);
	}
});

test('rejects unbounded loops and repeatUntil without a literal bound', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); while (true) {}',
		'program.onUnhandledAttention("continue_and_notify"); for (;;) {}',
		'program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => true, {}, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => true, { maxIterations: limit }, async () => {});',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'UNBOUNDED_LOOP',
		);
	}
});

test('rejects recursive local function call graphs', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); function again() { again(); } again();',
		'program.onUnhandledAttention("continue_and_notify"); const first = () => second(); const second = () => first(); first();',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'RECURSION_FORBIDDEN',
		);
	}
});

test('enforces watcher count and records source locations', () => {
	const watchers = Array.from({ length: 2 }, (_, index) => `program.watch(() => ${index === 0 ? 'true' : 'false'}, { mode: "boundary" }, async () => {});`).join('\n');
	const compiled = parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); ${watchers}`);
	assert.equal(compiled.watcherCount, 2);
	const [stepId, location] = compiled.stepLocations.entries().next().value;
	assert.match(stepId, /^step-\d+-\d+$/);
	assert.equal(typeof location.start, 'number');
	assert.equal(typeof location.end, 'number');
	assert.equal(typeof location.line, 'number');
	assert.equal(typeof location.column, 'number');

	const tooMany = Array.from({ length: 3 }, () => 'program.watch(() => true, { mode: "boundary" }, async () => {});').join('\n');
	assert.throws(
		() => parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); ${tooMany}`, { limits: { watchers: 2 } }),
		(error) => error.code === 'TOO_MANY_WATCHERS',
	);
});

for (const source of [
	'import fs from "node:fs";',
	'globalThis.process.exit(0);',
	'player["constructor"];',
	'while (true) {}',
	'function again() { again(); } again();',
]) {
	test(`rejects unsafe source: ${source}`, () => {
		assert.throws(() => parseArenaScript(source), /ArenaScript|policy|unsupported|unsafe|bounded|recursion/i);
	});
}

test('rejects aliases of special program APIs', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); const repeat = program.repeatUntil; await repeat(() => true, { maxIterations: 1 }, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); const watch = program.watch; watch(() => true, { mode: "boundary" }, async () => {});',
		'program.onUnhandledAttention("continue_and_notify"); const setPolicy = program.onUnhandledAttention; setPolicy("pause_and_notify");',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'UNSUPPORTED_SYNTAX',
		);
	}
});

test('rejects alias and parameter-mediated local function calls', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); function again() { const alias = again; alias(); } again();',
		'program.onUnhandledAttention("continue_and_notify"); function again(fn) { fn(); } again(again);',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'UNSUPPORTED_SYNTAX',
		);
	}
});

test('rejects unsafe or excessive literal for-loop bounds', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); for (let index = 9007199254740992; index <= 9007199254740992; index += 1) {}',
		'program.onUnhandledAttention("continue_and_notify"); for (let index = 0; index < 129; index += 1) {}',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.code === 'UNBOUNDED_LOOP',
		);
	}
});

test('rejects duplicate and forbidden loop option keys', () => {
	for (const options of [
		'{ maxIterations: 1, maxIterations: 2 }',
		'{ __proto__: 1, maxIterations: 1 }',
	]) {
		assert.throws(
			() => parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => true, ${options}, async () => {});`),
			(error) => error.code === 'UNBOUNDED_LOOP',
		);
	}
});

test('rejects a deeply nested AST with a stable parser error', () => {
	const source = `program.onUnhandledAttention("continue_and_notify"); ${'!'.repeat(300)}true;`;
	assert.throws(
		() => parseArenaScript(source),
		(error) => error.code === 'AST_TOO_LARGE' && error.name === 'ArenaScriptError',
	);
});

test('step locations cannot be mutated through Map.prototype', () => {
	const compiled = parseArenaScript('program.onUnhandledAttention("continue_and_notify");');
	const size = compiled.stepLocations.size;
	assert.throws(() => Map.prototype.set.call(compiled.stepLocations, 'injected', {}), TypeError);
	assert.equal(compiled.stepLocations.size, size);
	assert.equal(compiled.stepLocations.get('injected'), undefined);
});

test('rejects callable indirection that bypasses direct recursion analysis', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); function again() { again.call(); } again();',
		'program.onUnhandledAttention("continue_and_notify"); function again(box) { box.next(box); } const box = { next: again }; again(box);',
		'program.onUnhandledAttention("continue_and_notify"); function decoy() {} function again(decoy) { decoy(decoy); } again(again);',
		'program.onUnhandledAttention("continue_and_notify"); function decoy() {} function again(decoy) { return () => decoy(decoy); } again(again);',
		'program.onUnhandledAttention("continue_and_notify"); let safe = () => {}; const again = () => { safe = again; safe(); }; again();',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSUPPORTED_SYNTAX',
		);
	}
});

test('rejects watches that can execute more than once', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); for (let index = 0; index < 17; index += 1) { program.watch(() => true, { mode: "boundary" }, async () => {}); }',
		'program.onUnhandledAttention("continue_and_notify"); function install() { program.watch(() => true, { mode: "boundary" }, async () => {}); } install();',
		'program.onUnhandledAttention("continue_and_notify"); await program.repeatUntil(() => false, { maxIterations: 1 }, async () => { program.watch(() => true, { mode: "boundary" }, async () => {}); });',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSUPPORTED_SYNTAX',
		);
	}
});

test('rejects forbidden keys in every object literal', () => {
	for (const key of ['__proto__', 'constructor', 'prototype']) {
		assert.throws(
			() => parseArenaScript(`program.onUnhandledAttention("continue_and_notify"); const box = { ${key}: 1 };`),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSAFE_MEMBER_ACCESS',
		);
	}
});

test('rejects non-identifier and alias call targets that bypass recursion analysis', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); function again() { (() => again())(); } again();',
		'program.onUnhandledAttention("continue_and_notify"); function again() { (true ? again : again)(); } again();',
		'program.onUnhandledAttention("continue_and_notify"); function getAgain() { return again; } function again() { getAgain()(); } again();',
		'program.onUnhandledAttention("continue_and_notify"); function again() { ({ next: again }).next(); } again();',
		'program.onUnhandledAttention("continue_and_notify"); function decoy() {} function again() { const decoy = again; decoy(); } again();',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSUPPORTED_SYNTAX',
		);
	}
});

test('detects recursion through the external binding of a named function expression', () => {
	assert.throws(
		() => parseArenaScript('program.onUnhandledAttention("continue_and_notify"); const again = function inner() { again(); }; again();'),
		(error) => error.name === 'ArenaScriptError' && error.code === 'RECURSION_FORBIDDEN',
	);
});

test('rejects shadowed reserved capabilities and built-ins in local bindings and parameters', () => {
	for (const name of ['program', 'player', 'world', 'inventory', 'math', 'tryResult', 'undefined', 'NaN', 'Infinity']) {
		assert.throws(
			() => parseArenaScript(`const ${name} = 1; program.onUnhandledAttention("continue_and_notify");`),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSUPPORTED_SYNTAX',
		);
		assert.throws(
			() => parseArenaScript(`function local(${name}) {} program.onUnhandledAttention("continue_and_notify");`),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSUPPORTED_SYNTAX',
		);
	}
});

test('rejects supplied reserved-name shadowing escapes', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); function again() { const tryResult = again; tryResult(); } again();',
		'program.onUnhandledAttention("continue_and_notify"); function again(tryResult) { tryResult(tryResult); } again(again);',
		'program.onUnhandledAttention("continue_and_notify"); function again(player) { player.wait(player); } const box = { wait: again }; again(box);',
		'const program = { onUnhandledAttention: () => {} }; program.onUnhandledAttention("continue_and_notify");',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSUPPORTED_SYNTAX',
		);
	}
});

test('rejects every reserved capability name at every supported binding site', () => {
	assert.throws(
		() => parseArenaScript('program.onUnhandledAttention("continue_and_notify"); for (let player=0; player<1; player++){ player.wait(1); }'),
		(error) => error.name === 'ArenaScriptError' && error.code === 'UNSUPPORTED_SYNTAX',
	);
	for (const name of ['program', 'player', 'world', 'inventory', 'math', 'tryResult', 'undefined', 'NaN', 'Infinity']) {
		for (const source of [
			`program.onUnhandledAttention("continue_and_notify"); const ${name} = 0;`,
			`program.onUnhandledAttention("continue_and_notify"); for (let ${name} = 0; ${name} < 1; ${name} += 1) {}`,
			`program.onUnhandledAttention("continue_and_notify"); function ${name}() {}`,
			`program.onUnhandledAttention("continue_and_notify"); const task = function ${name}() {};`,
			`program.onUnhandledAttention("continue_and_notify"); function task(${name}) {}`,
			`program.onUnhandledAttention("continue_and_notify"); const task = (${name}) => {};`,
		]) {
			assert.throws(
				() => parseArenaScript(source),
				(error) => error.name === 'ArenaScriptError' && error.code === 'UNSUPPORTED_SYNTAX',
			);
		}
	}
});

test('rejects Annex-B blockless conditional function declarations', () => {
	for (const source of [
		'program.onUnhandledAttention("continue_and_notify"); if (true) function local() {}',
		'program.onUnhandledAttention("continue_and_notify"); if (true) function tryResult() { tryResult(); } tryResult();',
		'if (true) function program() {} program.onUnhandledAttention("continue_and_notify");',
	]) {
		assert.throws(
			() => parseArenaScript(source),
			(error) => error.name === 'ArenaScriptError' && error.code === 'UNSUPPORTED_SYNTAX',
		);
	}
});
