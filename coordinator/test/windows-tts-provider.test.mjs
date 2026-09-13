import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import test from 'node:test';

import { createWindowsTtsEnvironment, WindowsTtsProvider } from '../src/voice/windows-tts-provider.mjs';

const windowsOnly = { skip: process.platform !== 'win32' };
const windowsSpeechIntegration = {
	skip: process.platform !== 'win32' || process.env.ARENA_WINDOWS_TTS_INTEGRATION !== '1',
};

test('Windows TTS subprocess receives only its Windows runtime environment and speech rate', () => {
	const environment = createWindowsTtsEnvironment({
		SystemRoot: 'C:\\Windows',
		WINDIR: 'C:\\Windows',
		TEMP: 'C:\\Users\\tester\\AppData\\Local\\Temp',
		TMP: 'C:\\Users\\tester\\AppData\\Local\\Temp',
		Path: 'C:\\Windows\\System32',
		PATHEXT: '.COM;.EXE',
		ComSpec: 'C:\\Windows\\System32\\cmd.exe',
		USERPROFILE: 'C:\\Users\\tester',
		APPDATA: 'C:\\Users\\tester\\AppData\\Roaming',
		LOCALAPPDATA: 'C:\\Users\\tester\\AppData\\Local',
		PROGRAMDATA: 'C:\\ProgramData',
		ARENA_WINDOWS_TTS_RATE: 'untrusted-rate',
		ARENA_AGENT_BRIDGE_SECRET: 'bridge-secret',
		ARENA_AGENT_BRIDGE_SECRET_FILE: 'bridge-secret-file',
		FISH_AUDIO_API_KEY: 'fish-secret',
		FISH_API_KEY: 'fish-secret-alias',
		DEEPGRAM_API_KEY: 'deepgram-secret',
		OPENAI_API_KEY: 'provider-secret',
		CUSTOM_PROVIDER_CREDENTIAL: 'custom-secret',
	}, -3);

	assert.deepEqual(environment, {
		SystemRoot: 'C:\\Windows',
		WINDIR: 'C:\\Windows',
		TEMP: 'C:\\Users\\tester\\AppData\\Local\\Temp',
		TMP: 'C:\\Users\\tester\\AppData\\Local\\Temp',
		ARENA_WINDOWS_TTS_RATE: '-3',
	});
});

test('Windows TTS safely streams text and validates PCM through the subprocess boundary', async () => {
	const invocation = {};
	const pcm = Buffer.from([1, 2, 3, 4]);
	const provider = new WindowsTtsProvider({
		executable: 'fixture-powershell.exe',
		timeoutMs: 1_000,
		spawnProcess(executable, args, options) {
			Object.assign(invocation, { executable, args, options, stdin: Buffer.alloc(0) });
			const child = new EventEmitter();
			child.stdin = new PassThrough();
			child.stdout = new PassThrough();
			child.stderr = new PassThrough();
			child.kill = () => true;
			child.stdin.on('data', (chunk) => { invocation.stdin = Buffer.concat([invocation.stdin, chunk]); });
			queueMicrotask(() => {
				child.stdout.end(pcm);
				child.stderr.end();
				child.emit('close', 0);
			});
			return child;
		},
	});
	const text = "Hello. '; throw 'injected'; $env:PATH";
	const result = await provider.synthesize({ text, speed: 2 });

	assert.equal(invocation.executable, 'fixture-powershell.exe');
	assert.deepEqual(invocation.args.slice(0, 4), ['-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand']);
	assert.match(Buffer.from(invocation.args[4], 'base64').toString('utf16le'), /Speak\(\$text\)/);
	assert.equal(invocation.stdin.toString('utf8'), text);
	assert.equal(invocation.options.env.ARENA_WINDOWS_TTS_RATE, '5');
	assert.equal(invocation.options.env.ARENA_AGENT_BRIDGE_SECRET, undefined);
	assert.equal(invocation.options.windowsHide, true);
	assert.deepEqual(result, { sampleRateHz: 16_000, channels: 1, sampleFormat: 's16le', pcm });
});

test('Windows TTS returns bounded mono signed 16-bit PCM without treating text as PowerShell', windowsSpeechIntegration, async () => {
	const provider = new WindowsTtsProvider({ timeoutMs: 10_000 });
	const result = await provider.synthesize({
		text: "Hello from Luna. 你好. '; throw 'injected'; $env:PATH",
		speed: 1,
	});

	assert.equal(result.sampleRateHz, 16_000);
	assert.equal(result.channels, 1);
	assert.equal(result.sampleFormat, 's16le');
	assert.ok(result.pcm.length > 3_200);
	assert.equal(result.pcm.length % 2, 0);
	assert.ok(result.pcm.length <= 16_000 * 2 * 20);
	assert.ok(result.pcm.some((byte) => byte !== 0));
});

test('Windows TTS stops synthesis when its caller aborts', windowsOnly, async () => {
	const provider = new WindowsTtsProvider({ timeoutMs: 10_000 });
	const controller = new AbortController();
	const pending = provider.synthesize({
		text: 'This deliberately long sentence gives the caller time to cancel speech synthesis before the process can finish reading all of it aloud.',
		speed: 0.5,
		signal: controller.signal,
	});
	controller.abort();

	await assert.rejects(pending, (error) => error?.name === 'AbortError');
});
