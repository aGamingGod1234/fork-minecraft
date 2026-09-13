import { spawn } from 'node:child_process';
import path from 'node:path';

const SAMPLE_RATE_HZ = 16_000;
const MAX_PCM_BYTES = SAMPLE_RATE_HZ * 2 * 20;
const WINDOWS_TTS_ENVIRONMENT_KEYS = Object.freeze([
	'SystemRoot', 'WINDIR', 'TEMP', 'TMP',
]);
const POWERSHELL_SCRIPT = String.raw`
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Speech

[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
$text = [Console]::In.ReadToEnd()
$rate = 0
if (-not [int]::TryParse($env:ARENA_WINDOWS_TTS_RATE, [ref]$rate)) {
	throw 'Invalid speech rate'
}

$synthesizer = [System.Speech.Synthesis.SpeechSynthesizer]::new()
$stream = [IO.MemoryStream]::new()
try {
	$format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(
		16000,
		[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
		[System.Speech.AudioFormat.AudioChannel]::Mono
	)
	$synthesizer.Rate = $rate
	$synthesizer.SetOutputToAudioStream($stream, $format)
	$synthesizer.Speak($text)
	$bytes = $stream.ToArray()
	if ($bytes.Length -gt 640000) {
		throw 'Speech exceeded the audio limit'
	}
	$output = [Console]::OpenStandardOutput()
	$output.Write($bytes, 0, $bytes.Length)
	$output.Flush()
} finally {
	$synthesizer.Dispose()
	$stream.Dispose()
}
`;
const ENCODED_SCRIPT = Buffer.from(POWERSHELL_SCRIPT, 'utf16le').toString('base64');

export class WindowsTtsProvider {
	#executable;
	#timeoutMs;
	#spawnProcess;

	constructor({ executable = defaultPowerShellExecutable(), timeoutMs = 15_000, spawnProcess = spawn } = {}) {
		if (typeof executable !== 'string' || executable.trim() === '') throw new TypeError('PowerShell executable must not be blank');
		if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) throw new TypeError('timeoutMs must be positive');
		if (typeof spawnProcess !== 'function') throw new TypeError('spawnProcess must be a function');
		this.#executable = executable;
		this.#timeoutMs = timeoutMs;
		this.#spawnProcess = spawnProcess;
	}

	cacheNamespace() {
		return 'windows/system-speech';
	}

	async synthesize({ text, speed = 1, signal } = {}) {
		requireText(text);
		if ([...text].length > 280) throw new TypeError('text must be at most 280 Unicode code points');
		if (!Number.isFinite(speed) || speed < 0.5 || speed > 2) throw new TypeError('speed must be between 0.5 and 2');
		if (signal?.aborted) throw abortError();

		const pcm = await runPowerShell({
			executable: this.#executable,
			text,
			rate: Math.round(Math.log2(speed) * 5),
			timeoutMs: this.#timeoutMs,
			signal,
			spawnProcess: this.#spawnProcess,
		});
		if (pcm.length === 0 || pcm.length % 2 !== 0) {
			throw typedError('TTS_MALFORMED_AUDIO', 'Windows TTS returned invalid mono signed 16-bit PCM');
		}
		return Object.freeze({ sampleRateHz: SAMPLE_RATE_HZ, channels: 1, sampleFormat: 's16le', pcm });
	}
}

function runPowerShell({ executable, text, rate, timeoutMs, signal, spawnProcess }) {
	return new Promise((resolve, reject) => {
		const child = spawnProcess(executable, [
			'-NoLogo',
			'-NoProfile',
			'-NonInteractive',
			'-EncodedCommand',
			ENCODED_SCRIPT,
		], {
			env: createWindowsTtsEnvironment(process.env, rate),
			stdio: ['pipe', 'pipe', 'pipe'],
			windowsHide: true,
		});
		const chunks = [];
		let byteLength = 0;
		let settled = false;
		const finish = (operation) => {
			if (settled) return;
			settled = true;
			clearTimeout(timer);
			signal?.removeEventListener('abort', onAbort);
			operation();
		};
		const stop = () => child.kill();
		const onAbort = () => {
			stop();
			finish(() => reject(abortError()));
		};
		const timer = setTimeout(() => {
			stop();
			finish(() => reject(timeoutError()));
		}, timeoutMs);
		timer.unref?.();
		signal?.addEventListener('abort', onAbort, { once: true });

		child.once('error', () => {
			finish(() => reject(typedError('TTS_UNAVAILABLE', 'Windows speech synthesis could not start')));
		});
		child.stdout.on('data', (chunk) => {
			byteLength += chunk.length;
			if (byteLength > MAX_PCM_BYTES) {
				stop();
				finish(() => reject(typedError('TTS_AUDIO_TOO_LONG', 'Windows TTS output exceeds the 20 second PCM limit')));
				return;
			}
			chunks.push(chunk);
		});
		child.stderr.resume();
		child.once('close', (code) => {
			if (code !== 0) {
				finish(() => reject(typedError('TTS_PROVIDER_ERROR', 'Windows speech synthesis failed')));
				return;
			}
			finish(() => resolve(Buffer.concat(chunks, byteLength)));
		});
		child.stdin.on('error', () => {});
		child.stdin.end(text, 'utf8');
	});
}

export function createWindowsTtsEnvironment(environment = process.env, rate = 0) {
	if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) {
		throw new TypeError('Windows TTS environment must be an object');
	}
	const childEnvironment = {};
	for (const name of WINDOWS_TTS_ENVIRONMENT_KEYS) {
		if (typeof environment[name] === 'string') childEnvironment[name] = environment[name];
	}
	childEnvironment.ARENA_WINDOWS_TTS_RATE = String(rate);
	return childEnvironment;
}

function defaultPowerShellExecutable() {
	const systemRoot = process.env.SystemRoot;
	return typeof systemRoot === 'string' && systemRoot !== ''
		? path.join(systemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
		: 'powershell.exe';
}

function requireText(value) {
	if (typeof value !== 'string' || value.trim() === '') throw new TypeError('text must not be blank');
}

function abortError() {
	const error = new Error('Windows speech synthesis was cancelled');
	error.name = 'AbortError';
	return error;
}

function timeoutError() {
	const error = typedError('TTS_TIMEOUT', 'Windows speech synthesis timed out');
	error.name = 'TimeoutError';
	return error;
}

function typedError(code, message) {
	const error = new Error(message);
	error.code = code;
	return error;
}
