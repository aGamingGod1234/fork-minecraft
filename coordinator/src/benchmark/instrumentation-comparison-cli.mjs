import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { runInstrumentationComparison } from './instrumentation-comparison.mjs';

export async function main(argv = process.argv.slice(2)) {
	try {
		const args = parse(argv);
		const result = await runInstrumentationComparison(args);
		process.stdout.write(JSON.stringify({ status: result.status, orders: result.orders, artifact: path.join(args.artifactDirectory, 'instrumentation-comparison.json') }) + '\n');
		return result.status === 'PASSED' ? 0 : 1;
	} catch (error) {
		process.stderr.write(`instrumentation-comparison: ${String(error?.message ?? error).slice(0, 512)}\n`);
		return 64;
	}
}

function parse(argv) {
	const values = {};
	for (let index = 0; index < argv.length; index += 2) {
		const flag = argv[index];
		const value = argv[index + 1];
		if (!['--matrix', '--artifact-directory', '--first', '--minimum-samples', '--max-p95-ratio'].includes(flag) || !value || value.startsWith('--')) throw new TypeError('usage: --matrix FILE --artifact-directory DIR [--first enabled|disabled] [--minimum-samples N] [--max-p95-ratio RATIO]');
		if (values[flag]) throw new TypeError(`duplicate argument ${flag}`);
		values[flag] = value;
	}
	if (!values['--matrix'] || !values['--artifact-directory']) throw new TypeError('--matrix and --artifact-directory are required');
	if (values['--first'] && !['enabled', 'disabled'].includes(values['--first'])) throw new TypeError('--first must be enabled or disabled');
	const minimumSamples = values['--minimum-samples'] === undefined ? 5 : Number(values['--minimum-samples']);
	const maxP95Ratio = values['--max-p95-ratio'] === undefined ? 1.05 : Number(values['--max-p95-ratio']);
	return { matrixPath: path.resolve(values['--matrix']), artifactDirectory: path.resolve(values['--artifact-directory']), first: values['--first'] ?? 'disabled', minimumSamples, maxP95Ratio };
}

if (process.argv[1] !== undefined && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = await main();
