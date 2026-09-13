import { readFile } from 'node:fs/promises';
import { normalizeHeadlessMatrix } from './headless-matrix.mjs';

try {
	if (process.argv.length !== 3) throw new Error('Usage: node headless-config.mjs <matrix.json>');
	const source = JSON.parse(await readFile(process.argv[2], 'utf8'));
	const normalized = normalizeHeadlessMatrix(source);
	process.stdout.write(JSON.stringify({ version: 1, scenarios: source.scenarios.map((scenario, index) => ({ ...scenario, world: normalized.scenarios[index].world })) }));
} catch (error) {
	process.stderr.write(`Invalid headless matrix: ${error.message}\n`);
	process.exitCode = 1;
}
