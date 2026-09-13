import { readBoundedResponseBody } from './bounded-response-body.mjs';

const DEFAULT_ENDPOINT = 'https://api.deepgram.com/v1/listen?model=nova-3&smart_format=true&language=en&encoding=linear16&sample_rate=48000&channels=1';
const MAX_PCM_BYTES = 48_000 * 2 * 20;
const MAX_RESPONSE_BYTES = 256 * 1_024;

export class DeepgramSttProvider {
	#apiKey;
	#fetch;
	#endpoint;
	#timeoutMs;

	constructor({ apiKey, fetchImpl = globalThis.fetch, endpoint = DEFAULT_ENDPOINT, timeoutMs = 30_000 } = {}) {
		if (typeof apiKey !== 'string' || apiKey.trim() === '') throw new TypeError('Deepgram API key must not be blank');
		if (typeof fetchImpl !== 'function') throw new TypeError('fetchImpl must be a function');
		this.#apiKey = apiKey;
		this.#fetch = fetchImpl;
		this.#endpoint = endpoint;
		this.#timeoutMs = timeoutMs;
	}

	async transcribe({ pcm, signal } = {}) {
		if (!Buffer.isBuffer(pcm) || pcm.length === 0 || pcm.length % 2 !== 0 || pcm.length > MAX_PCM_BYTES) {
			throw typedError('STT_MALFORMED_AUDIO', 'STT input must be at most 20 seconds of 48 kHz mono PCM');
		}
		const timeoutSignal = AbortSignal.timeout(this.#timeoutMs);
		const responseController = new AbortController();
		const combinedSignal = AbortSignal.any([responseController.signal, timeoutSignal, ...(signal === undefined ? [] : [signal])]);
		let response;
		try {
			response = await this.#fetch(this.#endpoint, {
				method: 'POST',
				headers: {
					Authorization: `Token ${this.#apiKey}`,
					'Content-Type': 'audio/l16;rate=48000;channels=1',
				},
				body: pcm,
				signal: combinedSignal,
			});
		} catch (error) {
			if (error?.name === 'AbortError' || signal?.aborted) throw error;
			if (error instanceof TypeError) throw typedError('STT_PROVIDER_ERROR', 'Deepgram STT transport failed');
			throw error;
		}
		if (!response.ok) {
			const error = typedError(
				response.status === 429 ? 'STT_RATE_LIMITED' : 'STT_PROVIDER_ERROR',
				`Deepgram STT failed with HTTP ${response.status}`,
			);
			if (response.status === 429) error.retryAfter = response.headers?.get?.('retry-after');
			throw error;
		}
		const body = await readBoundedResponseBody(
			response,
			MAX_RESPONSE_BYTES,
			() => typedError('STT_RESPONSE_TOO_LARGE', 'Deepgram STT response exceeds the 256 KiB limit'),
			{ onLimit: (error) => responseController.abort(error) },
		);
		let document;
		try { document = JSON.parse(body.toString('utf8')); }
		catch { throw typedError('STT_PROVIDER_ERROR', 'Deepgram STT returned malformed JSON'); }
		const alternative = document?.results?.channels?.[0]?.alternatives?.[0];
		const transcript = typeof alternative?.transcript === 'string' ? alternative.transcript.trim() : '';
		if (transcript === '') return Object.freeze({ transcript: '', confidence: 0 });
		return Object.freeze({
			transcript: [...transcript].slice(0, 512).join(''),
			confidence: Number.isFinite(alternative.confidence) ? Math.max(0, Math.min(1, alternative.confidence)) : 0,
		});
	}
}

export class NoSttProvider {
	async transcribe() {
		throw typedError('STT_UNAVAILABLE', 'Speech recognition is not configured');
	}
}

function typedError(code, message) {
	const error = new Error(message);
	error.code = code;
	return error;
}
