import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { evaluateLatencyAcceptance } from './latency-acceptance.mjs';

export async function main(argv = process.argv.slice(2)) {
	try {
		const args = parse(argv);
		const [baseline, optimized, policy, instrumentationComparison] = await Promise.all([
			readJson(args.baseline), readJson(args.optimized), readJson(args.policy), args.instrumentation ? readJson(args.instrumentation) : null,
		]);
		const result = evaluateLatencyAcceptance({ baseline, optimized, policy, instrumentationComparison });
		const json = `${JSON.stringify(result, null, 2)}\n`;
		if (args.output) await writeFile(args.output, json, 'utf8');
		process.stdout.write(JSON.stringify({ status: result.status, claimCertified: result.claimCertified, failedChecks: result.summary.failedCount, output: args.output ?? null }) + '\n');
		return result.claimCertified ? 0 : 1;
	} catch (error) {
		process.stderr.write(`latency-acceptance: ${String(error?.message ?? error).slice(0, 512)}\n`);
		return 64;
	}
}

function parse(argv) {
	const values = {};
	for (let index = 0; index < argv.length; index += 2) {
		const flag = argv[index];
		const value = argv[index + 1];
		if (!['--baseline', '--optimized', '--policy', '--instrumentation', '--output'].includes(flag) || !value || value.startsWith('--')) throw new TypeError('usage: --baseline FILE --optimized FILE --policy FILE [--instrumentation FILE] [--output FILE]');
		if (values[flag]) throw new TypeError(`duplicate argument ${flag}`);
		values[flag] = path.resolve(value);
	}
	for (const required of ['--baseline', '--optimized', '--policy']) if (!values[required]) throw new TypeError(`${required} is required`);
	return { baseline: values['--baseline'], optimized: values['--optimized'], policy: values['--policy'], instrumentation: values['--instrumentation'] ?? null, output: values['--output'] ?? null };
}

async function readJson(file) { return JSON.parse(await readFile(file, 'utf8')); }

if (process.argv[1] !== undefined && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = await main();
