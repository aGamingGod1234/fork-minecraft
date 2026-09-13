import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { MAX_ACTION_ARGUMENT_BYTES, validateAction } from '../src/schema.mjs';

const cases = JSON.parse(await readFile(new URL('../../src/test/resources/player-action-schema-cases.json', import.meta.url), 'utf8'));

for (const fixture of cases) {
	test(`player action schema: ${fixture.name}`, () => {
		if (fixture.valid) assert.doesNotThrow(() => validateAction(fixture.action));
		else assert.throws(() => validateAction(fixture.action));
	});
}

test('validated conditional frames stay detached and immutable before dispatch', () => {
	const source = structuredClone(cases.find((fixture) => fixture.name === 'on_fire boolean branch').action);
	const accepted = validateAction(source);
	source.frames[0].branches[0].nextFrame = 0;
	assert.equal(accepted.frames[0].branches[0].nextFrame, 1);
	assert.throws(() => { accepted.frames[0].branches[0].nextFrame = 0; }, TypeError);
	assert.throws(() => { accepted.frames.push(accepted.frames[0]); }, TypeError);
	assert.strictEqual(validateAction(accepted), accepted);
});

test('book budget measures serialized UTF-8 arguments including JSON escaping', () => {
	const exact = cases.find((fixture) => fixture.name === 'exact serialized argument byte budget').action;
	const { type, ...argumentsOnly } = exact;
	assert.equal(Buffer.byteLength(JSON.stringify(argumentsOnly), 'utf8'), MAX_ACTION_ARGUMENT_BYTES);
	const tooLarge = cases.find((fixture) => fixture.name === 'one byte above serialized argument budget').action;
	assert.throws(() => validateAction(tooLarge), (error) => error.code === 'ACTION_ARGUMENTS_TOO_LARGE');
	const escaped = cases.find((fixture) => fixture.name === 'JSON escaping counts toward argument budget').action;
	assert.ok(escaped.pages.reduce((sum, page) => sum + page.length, 0) < MAX_ACTION_ARGUMENT_BYTES);
	assert.throws(() => validateAction(escaped), (error) => error.code === 'ACTION_ARGUMENTS_TOO_LARGE');
});
