import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const coordinator = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const project = path.resolve(coordinator, '..');

test('standard launchers use the current dynamic coordinator only', async () => {
	const packageJson = JSON.parse(await readFile(path.join(coordinator, 'package.json'), 'utf8'));
	assert.equal(packageJson.scripts.start, 'node src/dynamic-main.mjs');
	assert.equal(packageJson.engines.node, '>=22');
	const launcher = await readFile(path.join(project, 'scripts', 'start-coordinator.ps1'), 'utf8');
	assert.match(launcher, /src[\\/]dynamic-main\.mjs/);
	assert.match(launcher, /config[\\/]dynamic-agents\.json/);
	assert.doesNotMatch(launcher, /src[\\/]main\.mjs|config[\\/]agents\.json|--agent/);
	assert.match(launcher, /Node\.js 22 or newer is required/);
	assert.doesNotMatch(launcher, /Node 25 is required|\^v25/);
});
