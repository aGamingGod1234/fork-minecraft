import { createInterface } from 'node:readline';

const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
let inferenceBlocked = false;
for await (const line of lines) {
	const request = JSON.parse(line);
	if (inferenceBlocked) continue;
	if (request.op === 'warmup') {
		process.stdout.write(`${JSON.stringify({ id: request.id, ok: true, sttReady: true, ttsReady: true })}\n`);
		continue;
	}
	if (request.op === 'tts') {
		if (request.text === 'block-worker') {
			inferenceBlocked = true;
			continue;
		}
		const pcmBase64 = request.text === 'malformed'
			? Buffer.from([1]).toString('base64')
			: Buffer.from([1, 0, 2, 0]).toString('base64');
		process.stdout.write(`${JSON.stringify({ id: request.id, ok: true, sampleRateHz: 24_000, pcmBase64 })}\n`);
		continue;
	}
	if (request.op === 'stt') {
		process.stdout.write(`${JSON.stringify({ id: request.id, ok: true, transcript: 'I can hear you.', confidence: 0.87 })}\n`);
		continue;
	}
	process.stdout.write(`${JSON.stringify({ id: request.id, ok: false, code: 'UNKNOWN_OPERATION', message: 'Unknown operation' })}\n`);
}
