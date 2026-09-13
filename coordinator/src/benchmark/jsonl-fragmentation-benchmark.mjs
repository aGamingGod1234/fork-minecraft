import { performance } from 'node:perf_hooks';

import { JsonlDecoder } from '../jsonl.mjs';

const PAYLOAD_BYTES = 65_000;
const RUNS = 5;
const frame = Buffer.from(`${JSON.stringify({ payload: 'x'.repeat(PAYLOAD_BYTES) })}\n`, 'utf8');

function run(chunkBytes, borrowed) {
	const decoder = new JsonlDecoder();
	const startedAt = performance.now();
	for (let offset = 0; offset < frame.length; offset += chunkBytes) {
		const chunk = frame.subarray(offset, Math.min(offset + chunkBytes, frame.length));
		decoder.push(borrowed ? chunk : chunk.toString('utf8'));
	}
	return performance.now() - startedAt;
}

function median(values) {
	const ordered = values.toSorted((left, right) => left - right);
	return ordered[Math.floor(ordered.length / 2)];
}

const results = [];
for (const borrowed of [true, false]) {
	for (const chunkBytes of [1, 16, 64]) {
		run(chunkBytes, borrowed);
		const samples = Array.from({ length: RUNS }, () => run(chunkBytes, borrowed));
		results.push({
			input: borrowed ? 'buffer' : 'string',
			chunkBytes,
			medianMs: Number(median(samples).toFixed(3)),
		});
	}
}

console.log(JSON.stringify({
	benchmark: 'jsonl-fragmentation',
	node: process.version,
	payloadBytes: PAYLOAD_BYTES,
	runs: RUNS,
	results,
}, null, 2));
