import { spawn } from 'node:child_process';
import { access } from 'node:fs/promises';
import { createInterface } from 'node:readline';

const MAX_TTS_TEXT_CODE_POINTS = 280;
const MAX_TTS_PCM_BYTES = 24_000 * 2 * 20;
const MAX_STT_PCM_BYTES = 48_000 * 2 * 20;
const MAX_RESPONSE_LINE_CHARS = 2 * 1024 * 1024;

// Keep credentials and provider-specific secrets out of the local model process.
// The worker only needs runtime paths, model/cache settings, and the local voice
// tuning knobs. Unknown variables are intentionally excluded by default.
const LOCAL_ENVIRONMENT_KEYS = Object.freeze([
	'PATH', 'Path', 'PATHEXT', 'SystemRoot', 'WINDIR', 'ComSpec',
	'HOME', 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'PROGRAMDATA',
	'TEMP', 'TMP', 'TMPDIR', 'LANG', 'LC_ALL', 'PYTHONIOENCODING',
	'PYTHONPATH', 'PYTHONHOME', 'VIRTUAL_ENV', 'CUDA_VISIBLE_DEVICES',
	'HF_HOME', 'HUGGINGFACE_HUB_CACHE', 'TRANSFORMERS_CACHE', 'TORCH_HOME',
	'ARENA_LOCAL_TTS_DEVICE', 'ARENA_LOCAL_STT_DEVICE', 'ARENA_LOCAL_STT_MODEL',
	'ARENA_LOCAL_STT_COMPUTE_TYPE', 'ARENA_LOCAL_TTS_EXAGGERATION',
	'ARENA_LOCAL_TTS_CFG_WEIGHT',
]);

export class LocalSpeechProvider {
	#executable;
	#scriptPath;
	#timeoutMs;
	#child = null;
	#pending = new Map();
	#nextId = 1;
	#closed = false;
	#generation = 0;
	#closePromise = null;
	#environment;

	constructor({ executable, scriptPath, timeoutMs = 120_000, environment = process.env } = {}) {
		if (typeof executable !== 'string' || executable.trim() === '') throw new TypeError('local speech executable must not be blank');
		if (typeof scriptPath !== 'string' || scriptPath.trim() === '') throw new TypeError('local speech scriptPath must not be blank');
		if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) throw new TypeError('timeoutMs must be positive');
		if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) {
			throw new TypeError('local speech environment must be an object');
		}
		this.#executable = executable;
		this.#scriptPath = scriptPath;
		this.#timeoutMs = timeoutMs;
		this.#environment = createLocalSpeechEnvironment(environment);
	}

	cacheNamespace() {
		return 'local-chatterbox/chatterbox-v1';
	}

	static async createIfAvailable(options = {}) {
		const signal = options.signal;
		const accessFile = options.accessFile ?? defaultAccess;
		if (typeof accessFile !== 'function') throw new TypeError('accessFile must be a function');
		if (signal?.aborted) throw abortReason(signal);
		try {
			await awaitAbortable(Promise.all(
				[options.executable, options.scriptPath].map((filePath) => Promise.resolve().then(() => accessFile(filePath, signal))),
			), signal);
		} catch (error) {
			if (signal?.aborted) throw abortReason(signal);
			if (error?.code === 'ENOENT') return null;
			throw error;
		}
		return new LocalSpeechProvider(options);
	}

	async warmup({ signal } = {}) {
		const response = await this.#request({ op: 'warmup' }, signal);
		if (typeof response.sttReady !== 'boolean' || typeof response.ttsReady !== 'boolean') {
			throw typedError('LOCAL_SPEECH_WARMUP_FAILED', 'Local speech model warmup returned invalid channel readiness');
		}
		return Object.freeze({ sttReady: response.sttReady, ttsReady: response.ttsReady });
	}

	async synthesize({ text, voiceId = 'local.default.v1', speed = 1, tone = 'neutral', signal } = {}) {
		if (typeof text !== 'string' || text.trim() === '') throw new TypeError('text must not be blank');
		if ([...text].length > MAX_TTS_TEXT_CODE_POINTS) throw new TypeError('text must be at most 280 Unicode code points');
		if (!Number.isFinite(speed) || speed < 0.5 || speed > 2) throw new TypeError('speed must be between 0.5 and 2');
		if (typeof voiceId !== 'string' || voiceId.trim() === '') throw new TypeError('voiceId must not be blank');
		if (typeof tone !== 'string' || !/^[A-Za-z0-9_.:-]{1,32}$/.test(tone)) throw new TypeError('tone is invalid');
		const response = await this.#request({ op: 'tts', text, voiceId, speed, tone }, signal);
		const sampleRateHz = response.sampleRateHz;
		const pcm = decodePcm(response.pcmBase64);
		if (!Number.isSafeInteger(sampleRateHz) || sampleRateHz !== 24_000
				|| pcm.length === 0 || pcm.length % 2 !== 0 || pcm.length > MAX_TTS_PCM_BYTES) {
			throw typedError('TTS_MALFORMED_AUDIO', 'Local TTS returned invalid 24 kHz mono signed 16-bit PCM');
		}
		return Object.freeze({
			sampleRateHz,
			channels: 1,
			sampleFormat: 's16le',
			pcm,
			provider: 'local-chatterbox',
			model: 'chatterbox-v1',
			voiceId: typeof response.voiceId === 'string' ? response.voiceId : localVoiceId(voiceId),
		});
	}

	async transcribe({ pcm, signal } = {}) {
		if (!Buffer.isBuffer(pcm) || pcm.length === 0 || pcm.length % 2 !== 0 || pcm.length > MAX_STT_PCM_BYTES) {
			throw typedError('STT_MALFORMED_AUDIO', 'STT input must be at most 20 seconds of 48 kHz mono PCM');
		}
		const response = await this.#request({ op: 'stt', pcmBase64: pcm.toString('base64') }, signal);
		if (typeof response.transcript !== 'string' || !Number.isFinite(response.confidence)) {
			throw typedError('STT_PROVIDER_RESPONSE', 'Local STT returned an invalid transcript');
		}
		return Object.freeze({
			transcript: [...response.transcript.trim()].slice(0, 512).join(''),
			confidence: Math.max(0, Math.min(1, response.confidence)),
		});
	}

	close() {
		if (this.#closePromise !== null) return this.#closePromise;
		this.#closed = true;
		const child = this.#child;
		this.#failProcess(typedError('LOCAL_SPEECH_CLOSED', 'Local speech provider is closed'));
		this.#closePromise = child === null || child.exitCode !== null ? Promise.resolve() : new Promise((resolve) => {
			const timer = setTimeout(resolve, 1_000);
			timer.unref?.();
			child.once('close', () => { clearTimeout(timer); resolve(); });
		});
		return this.#closePromise;
	}

	#request(payload, signal) {
		if (this.#closed) return Promise.reject(typedError('LOCAL_SPEECH_CLOSED', 'Local speech provider is closed'));
		if (signal?.aborted) return Promise.reject(abortError());
		const child = this.#ensureProcess();
		const generation = this.#generation;
		const id = this.#nextId++;
		return new Promise((resolve, reject) => {
			const finish = (operation) => {
				const pending = this.#pending.get(id);
				if (pending === undefined) return;
				this.#pending.delete(id);
				clearTimeout(pending.timer);
				signal?.removeEventListener('abort', pending.onAbort);
				operation();
			};
			const onAbort = () => {
				const pending = this.#pending.get(id);
				if (pending === undefined) return;
				const error = abortError();
				finish(() => reject(error));
				this.#failProcess(error, pending.child, pending.generation, true);
			};
			const timer = setTimeout(() => {
				const pending = this.#pending.get(id);
				if (pending === undefined) return;
				const error = timeoutError();
				finish(() => reject(error));
				this.#failProcess(error, pending.child, pending.generation, true);
			}, this.#timeoutMs);
			timer.unref?.();
			this.#pending.set(id, {
				id, payload: { id, ...payload }, resolve, reject, timer, onAbort, signal, child, generation,
			});
			signal?.addEventListener('abort', onAbort, { once: true });
			this.#writeRequest(child, generation, this.#pending.get(id));
		});
	}

	#ensureProcess() {
		if (this.#child !== null) return this.#child;
		const child = spawn(this.#executable, [this.#scriptPath], {
			stdio: ['pipe', 'pipe', 'pipe'],
			windowsHide: true,
			env: this.#environment,
		});
		this.#child = child;
		this.#generation += 1;
		const generation = this.#generation;
		const lines = createInterface({ input: child.stdout, crlfDelay: Infinity });
		lines.on('line', (line) => this.#acceptResponse(line, child, generation));
		child.stderr.resume();
		child.stdin.on('error', () => this.#failProcess(
			typedError('LOCAL_SPEECH_UNAVAILABLE', 'Local speech worker input failed'),
			child,
			generation,
			true,
		));
		child.once('error', () => this.#failProcess(typedError('LOCAL_SPEECH_UNAVAILABLE', 'Local speech worker could not start'), child, generation));
		child.once('close', (code) => this.#failProcess(typedError(
			'LOCAL_SPEECH_UNAVAILABLE',
			code === 0 ? 'Local speech worker stopped' : `Local speech worker exited with code ${String(code)}`,
		), child, generation));
		return child;
	}

	#acceptResponse(line, child, generation) {
		if (child !== this.#child || generation !== this.#generation) return;
		if (line.length > MAX_RESPONSE_LINE_CHARS) {
			this.#failProcess(typedError('LOCAL_SPEECH_PROTOCOL_ERROR', 'Local speech worker response exceeded its size limit'));
			return;
		}
		let response;
		try { response = JSON.parse(line); }
		catch {
			this.#failProcess(typedError('LOCAL_SPEECH_PROTOCOL_ERROR', 'Local speech worker returned invalid JSON'));
			return;
		}
		if (response === null || typeof response !== 'object' || !Number.isSafeInteger(response.id)) {
			this.#failProcess(typedError('LOCAL_SPEECH_PROTOCOL_ERROR', 'Local speech worker response is invalid'));
			return;
		}
		const pending = this.#pending.get(response.id);
		if (pending === undefined || pending.generation !== generation) return;
		this.#pending.delete(response.id);
		clearTimeout(pending.timer);
		pending.onAbort && pending.signal?.removeEventListener?.('abort', pending.onAbort);
		if (response.ok === true) {
			pending.resolve(response);
			return;
		}
		pending.reject(typedError(workerErrorCode(response.code), workerErrorMessage(response.message)));
	}

	#failProcess(error, expectedChild = this.#child, expectedGeneration = this.#generation, preservePending = false) {
		if (expectedChild !== this.#child || expectedGeneration !== this.#generation) return;
		const child = this.#child;
		this.#child = null;
		if (child !== null && child.exitCode === null && !child.killed) child.kill();
		const pending = preservePending ? [...this.#pending.values()] : [];
		for (const [id, pending] of this.#pending) {
			if (preservePending && pending.generation === expectedGeneration) continue;
			this.#pending.delete(id);
			clearTimeout(pending.timer);
			pending.signal?.removeEventListener('abort', pending.onAbort);
			pending.reject(error);
		}
		if (preservePending && pending.length > 0 && !this.#closed) this.#replayPending(pending);
	}

	#replayPending(pending) {
		let child;
		try { child = this.#ensureProcess(); }
		catch {
			for (const request of pending) this.#rejectPending(request, typedError('LOCAL_SPEECH_UNAVAILABLE', 'Local speech worker could not restart'));
			return;
		}
		for (const request of pending) {
			if (!this.#pending.has(request.id)) continue;
			if (request.signal?.aborted) {
				this.#rejectPending(request, abortError());
				continue;
			}
			request.generation = this.#generation;
			request.child = child;
			this.#writeRequest(child, this.#generation, request);
		}
	}

	#writeRequest(child, generation, pending) {
		if (pending === undefined || !this.#pending.has(pending.id)) return;
		try {
			child.stdin.write(`${JSON.stringify(pending.payload)}\n`, (error) => {
				if (error) this.#failProcess(
					typedError('LOCAL_SPEECH_UNAVAILABLE', 'Local speech worker input failed'),
					child,
					generation,
					true,
				);
			});
		} catch {
			this.#failProcess(
				typedError('LOCAL_SPEECH_UNAVAILABLE', 'Local speech worker input failed'),
				child,
				generation,
				true,
			);
		}
	}

	#rejectPending(pending, error) {
		if (!this.#pending.delete(pending.id)) return;
		clearTimeout(pending.timer);
		pending.signal?.removeEventListener('abort', pending.onAbort);
		pending.reject(error);
	}
}

export function createLocalSpeechEnvironment(environment = process.env) {
	if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) {
		throw new TypeError('local speech environment must be an object');
	}
	const childEnvironment = { PYTHONUNBUFFERED: '1' };
	for (const name of LOCAL_ENVIRONMENT_KEYS) {
		if (typeof environment[name] === 'string') childEnvironment[name] = environment[name];
	}
	return childEnvironment;
}

export function localVoiceId(sourceVoiceId) {
	if (typeof sourceVoiceId !== 'string' || sourceVoiceId.trim() === '') return 'local.chatterbox.v1.default';
	if (/^local\.chatterbox\.v1\.[0-9a-f]{8}$/i.test(sourceVoiceId)) return sourceVoiceId;
	let hash = 0x811c9dc5;
	for (const byte of Buffer.from(sourceVoiceId, 'utf8')) hash = Math.imul(hash ^ byte, 0x01000193) >>> 0;
	return `local.chatterbox.v1.${hash.toString(16).padStart(8, '0')}`;
}

function decodePcm(value) {
	if (typeof value !== 'string' || value === '' || value.length > MAX_RESPONSE_LINE_CHARS) return Buffer.alloc(0);
	return Buffer.from(value, 'base64');
}

function workerErrorCode(value) {
	return typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : 'LOCAL_SPEECH_ERROR';
}

function workerErrorMessage(value) {
	return typeof value === 'string' && value.trim() !== '' ? [...value].slice(0, 256).join('') : 'Local speech inference failed';
}

function abortError() {
	const error = new Error('Local speech request was cancelled');
	error.name = 'AbortError';
	return error;
}

function abortReason(signal) {
	if (signal?.reason instanceof Error) return signal.reason;
	return abortError();
}

function awaitAbortable(value, signal) {
	if (signal === undefined) return value;
	if (signal.aborted) return Promise.reject(abortReason(signal));
	return new Promise((resolve, reject) => {
		let settled = false;
		const finish = (operation, result) => {
			if (settled) return;
			settled = true;
			signal.removeEventListener('abort', onAbort);
			operation(result);
		};
		const onAbort = () => finish(reject, abortReason(signal));
		signal.addEventListener('abort', onAbort, { once: true });
		Promise.resolve(value).then(
			(result) => signal.aborted ? onAbort() : finish(resolve, result),
			(error) => finish(reject, error),
		);
	});
}

function defaultAccess(filePath) {
	return access(filePath);
}

function timeoutError() {
	const error = typedError('LOCAL_SPEECH_TIMEOUT', 'Local speech inference timed out');
	error.name = 'TimeoutError';
	return error;
}

function typedError(code, message) {
	const error = new Error(message);
	error.code = code;
	return error;
}
