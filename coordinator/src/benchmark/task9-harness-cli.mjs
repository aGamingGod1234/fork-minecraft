import { readFile } from 'node:fs/promises';
import path from 'node:path';

import { runTask9SimulatorMatrix } from './task9-harness.mjs';

function parseArgs(argv) {
	const result = {};
	for (let index = 0; index < argv.length; index += 1) {
		const flag = argv[index];
		if (!flag.startsWith('--') || index + 1 >= argv.length) throw new TypeError(`invalid Task 9 argument '${flag}'`);
		const key = { '--matrix': 'matrixPath', '--artifact-directory': 'artifactDirectory', '--arm': 'arm', '--run-id': 'runId', '--source-commit': 'sourceCommit', '--source-hash': 'sourceHash', '--config-hash': 'configHash', '--pairing-key': 'pairingKey', '--planning-concurrency': 'planningConcurrency', '--replay-recordings': 'replayRecordingsPath', '--replay-prompt-file': 'replayPromptPath' }[flag];
		if (!key) throw new TypeError(`unknown Task 9 argument '${flag}'`);
		result[key] = argv[++index];
	}
	if (!result.matrixPath || !result.artifactDirectory) throw new TypeError('--matrix and --artifact-directory are required');
	return result;
}

const args = parseArgs(process.argv.slice(2));
const matrix = JSON.parse(await readFile(path.resolve(args.matrixPath), 'utf8'));
const replayRecordings = args.replayRecordingsPath ? JSON.parse(await readFile(path.resolve(args.replayRecordingsPath), 'utf8')) : undefined;
const replayPrompt = args.replayPromptPath ? await readFile(path.resolve(args.replayPromptPath), 'utf8') : undefined;
const result = await runTask9SimulatorMatrix({
	matrix, artifactDirectory: path.resolve(args.artifactDirectory), runId: args.runId, arm: args.arm, sourceCommit: args.sourceCommit,
	sourceHash: args.sourceHash, configHash: args.configHash, pairingKey: args.pairingKey, planningConcurrency: args.planningConcurrency ? Number(args.planningConcurrency) : undefined,
	replayRecordings, replayPrompt,
});
process.stdout.write(`${JSON.stringify(result)}\n`);
