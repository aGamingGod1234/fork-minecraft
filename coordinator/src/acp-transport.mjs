import { EventEmitter } from 'node:events';
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';

import { DEFAULT_CHILD_STOP_TIMEOUT_MS, terminateChildProcess } from './child-process-lifecycle.mjs';
import { JsonlDecoder, encodeJsonLine } from './jsonl.mjs';
import { createProviderChildEnvironment } from './provider-environment.mjs';
import { sanitizeDiagnosticErrorMessage, sanitizeDiagnosticText } from './diagnostic-sanitizer.mjs';

const MAX_LINE_BYTES = 4 * 1_024 * 1_024;
const DEFAULT_REQUEST_TIMEOUT_MS = 15_000;

export class AcpProtocolError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'AcpProtocolError';
		this.code = code;
	}
}

export function buildAcpLaunch(provider, profile = {}, dependencies = {}) {
	const normalizedProvider = requireProvider(provider);
	const environment = createProviderChildEnvironment(
		normalizedProvider,
		dependencies.env ?? profile.environment ?? process.env,
		profile.bridgeSecretEnvironmentVariable,
	);
	if (normalizedProvider === 'kimi') environment.KIMI_MODEL_THINKING_EFFORT = requireKimiEffort(profile.reasoningEffort ?? 'high');
	const options = {
		cwd: profile.cwd,
		stdio: ['pipe', 'pipe', 'pipe'],
		windowsHide: true,
		env: environment,
	};
	if (typeof profile.command === 'string' && profile.command.trim().length > 0) {
		return { command: profile.command, args: normalizedProvider === 'gemini' ? ['--acp'] : ['acp'], options };
	}
	if (normalizedProvider === 'gemini' && (dependencies.platform ?? process.platform) === 'win32') {
		const appData = environment.APPDATA;
		if (typeof appData === 'string') {
			const entrypoint = path.join(appData, 'npm', 'node_modules', '@google', 'gemini-cli', 'bundle', 'gemini.js');
			if ((dependencies.existsSync ?? existsSync)(entrypoint)) {
				return { command: dependencies.execPath ?? process.execPath, args: [entrypoint, '--acp'], options };
			}
		}
	}
	if (normalizedProvider === 'kimi' && (dependencies.platform ?? process.platform) === 'win32') {
		const appData = environment.APPDATA;
		if (typeof appData === 'string') {
			const entrypoint = path.join(appData, 'npm', 'node_modules', '@moonshot-ai', 'kimi-code', 'dist', 'main.mjs');
			if ((dependencies.existsSync ?? existsSync)(entrypoint)) {
				return { command: dependencies.execPath ?? process.execPath, args: [entrypoint, 'acp'], options };
			}
		}
	}
	return { command: normalizedProvider, args: normalizedProvider === 'gemini' ? ['--acp'] : ['acp'], options };
}

export class AcpStdioTransport extends EventEmitter {
	#config;
	#spawn;
	#child = null;
	#decoder = null;
	#requestId = 0;
	#pending = new Map();
	#stopTimeoutMs;

	constructor(config, dependencies = {}) {
		super();
		this.#config = { ...config, provider: requireProvider(config?.provider) };
		this.#spawn = dependencies.spawn ?? spawn;
		this.#stopTimeoutMs = dependencies.stopTimeoutMs ?? DEFAULT_CHILD_STOP_TIMEOUT_MS;
	}

	async start() {
		if (this.#child !== null) return;
		this.#decoder = new JsonlDecoder({ maxBytes: MAX_LINE_BYTES });
		const launch = buildAcpLaunch(this.#config.provider, this.#config);
		let child;
		try {
			child = this.#spawn(launch.command, launch.args, launch.options);
		} catch (error) {
			throw new AcpProtocolError('SPAWN_FAILED', `Could not start ${this.#config.provider} ACP: ${sanitizeDiagnosticErrorMessage(error)}`, { cause: error });
		}
		this.#child = child;
		child.stdout.on('data', (chunk) => this.#onStdout(child, chunk));
		child.stderr.on('data', (chunk) => {
			try { this.emit('diagnostic', sanitizeDiagnosticText(chunk, { maxBytes: 4_096 })); } catch { /* diagnostics cannot interrupt provider IO */ }
		});
		child.on('exit', (code, signal) => this.#onExit(child, code, signal));
		await new Promise((resolve, reject) => {
			const cleanup = () => { child.off('spawn', onSpawn); child.off('error', onError); };
			const onSpawn = () => { cleanup(); resolve(); };
			const onError = (error) => { cleanup(); this.#child = null; reject(new AcpProtocolError('SPAWN_FAILED', `Could not start ${this.#config.provider} ACP: ${sanitizeDiagnosticErrorMessage(error)}`, { cause: error })); };
			child.once('spawn', onSpawn);
			child.once('error', onError);
			if (Number.isInteger(child.pid) && child.pid > 0) onSpawn();
		});
		this.#ownRuntimeErrors(child);
	}

	request(method, params = {}, { timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS } = {}) {
		this.#requireRunning();
		const id = this.#nextRequestId();
		return new Promise((resolve, reject) => {
			const timer = setTimeout(() => {
				this.#pending.delete(id);
				reject(new AcpProtocolError('REQUEST_TIMEOUT', `${this.#config.provider} ACP request '${method}' timed out after ${timeoutMs} ms`));
				void this.stop().catch((error) => this.emit('protocolError', error));
			}, timeoutMs);
			this.#pending.set(id, { method, resolve, reject, timer });
			try { this.#write({ jsonrpc: '2.0', id, method, params }); } catch (error) {
				clearTimeout(timer);
				this.#pending.delete(id);
				reject(error);
			}
		});
	}

	notify(method, params = {}) {
		this.#requireRunning();
		this.#write({ jsonrpc: '2.0', method, params });
	}

	async stop() {
		const child = this.#child;
		if (child === null) return;
		this.#child = null;
		this.#rejectPending(new AcpProtocolError('TRANSPORT_STOPPED', `${this.#config.provider} ACP transport stopped`));
		await terminateChildProcess(child, { timeoutMs: this.#stopTimeoutMs });
	}

	#onStdout(child, chunk) {
		if (child !== this.#child) return;
		try { for (const message of this.#decoder.push(chunk)) this.#acceptMessage(message); } catch (error) {
			this.emit('protocolError', new AcpProtocolError(error.code ?? 'INVALID_RESPONSE', error.message, { cause: error }));
			void this.stop().catch((stopError) => this.emit('protocolError', stopError));
		}
	}

	#acceptMessage(message) {
		if (Object.hasOwn(message, 'id') && typeof message.method !== 'string') {
			const pending = this.#pending.get(message.id);
			if (pending === undefined) return this.emit('protocolError', new AcpProtocolError('UNKNOWN_RESPONSE_ID', `ACP response used unknown id '${String(message.id)}'`));
			this.#pending.delete(message.id);
			clearTimeout(pending.timer);
			if (Object.hasOwn(message, 'error')) pending.reject(rpcProtocolError(pending.method, message.error));
			else if (Object.hasOwn(message, 'result')) pending.resolve(message.result);
			else pending.reject(new AcpProtocolError('INVALID_RESPONSE', `ACP response for '${pending.method}' has no result or error`));
			return;
		}
		if (typeof message.method === 'string' && Object.hasOwn(message, 'id')) return this.#answerServerRequest(message);
		if (typeof message.method === 'string') return this.emit('notification', { method: message.method, params: message.params ?? {} });
		this.emit('protocolError', new AcpProtocolError('INVALID_RESPONSE', 'ACP emitted an invalid JSON-RPC message'));
	}

	#answerServerRequest(message) {
		if (message.method === 'session/request_permission') {
			this.#write({ jsonrpc: '2.0', id: message.id, result: { outcome: { outcome: 'cancelled' } } });
			return;
		}
		this.#write({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: `Unsupported client method '${message.method}'` } });
	}

	#onExit(child, code, signal) {
		if (child !== this.#child) return;
		this.#child = null;
		const error = new AcpProtocolError('PROCESS_EXITED', `${this.#config.provider} ACP exited (code=${String(code)}, signal=${String(signal)})`);
		this.#rejectPending(error);
		this.emit('exit', error);
	}

	#ownRuntimeErrors(child) {
		const own = (source) => source?.on?.('error', (error) => this.#onRuntimeError(child, error));
		own(child);
		own(child.stdin);
		own(child.stdout);
		own(child.stderr);
	}

	#onRuntimeError(child, cause) {
		if (child !== this.#child) return;
		this.#child = null;
		const error = new AcpProtocolError(
			'PROCESS_IO_ERROR',
			`${this.#config.provider} ACP process I/O failed: ${sanitizeDiagnosticErrorMessage(cause)}`,
			{ cause },
		);
		this.#rejectPending(error);
		this.emit('exit', error);
		void terminateChildProcess(child, { timeoutMs: this.#stopTimeoutMs })
			.catch((stopError) => this.emit('protocolError', stopError));
	}

	#write(message) { this.#requireRunning(); this.#child.stdin.write(encodeJsonLine(message, { maxBytes: MAX_LINE_BYTES })); }
	#requireRunning() { if (this.#child === null || this.#child.killed) throw new AcpProtocolError('TRANSPORT_NOT_RUNNING', `${this.#config.provider} ACP transport is not running`); }
	#nextRequestId() { if (this.#requestId === Number.MAX_SAFE_INTEGER) throw new AcpProtocolError('REQUEST_ID_EXHAUSTED', 'ACP request ID sequence is exhausted'); return ++this.#requestId; }
	#rejectPending(error) { for (const pending of this.#pending.values()) { clearTimeout(pending.timer); pending.reject(error); } this.#pending.clear(); }
}

function requireProvider(value) {
	if (!['gemini', 'kimi'].includes(value)) throw new TypeError(`ACP provider must be gemini or kimi`);
	return value;
}

function requireKimiEffort(value) {
	if (!['low', 'high', 'max'].includes(value)) throw new TypeError(`Kimi reasoning effort must be low, high, or max`);
	return value;
}

function rpcProtocolError(method, value) {
	const error = new AcpProtocolError('RPC_ERROR', `${method}: ACP provider returned an RPC error`);
	error.category = 'transport';
	if (Number.isSafeInteger(value?.code)) error.rpcCode = value.code;
	const status = [value?.status, value?.statusCode, value?.httpStatusCode, value?.data?.status, value?.data?.statusCode, value?.data?.httpStatusCode]
		.find((candidate) => Number.isSafeInteger(candidate) && candidate >= 100 && candidate <= 599);
	const httpStatusCode = status ?? (Number.isSafeInteger(value?.code) && value.code >= 100 && value.code <= 599 ? value.code : null);
	if (httpStatusCode !== null) {
		error.httpStatusCode = httpStatusCode;
		error.rateLimited = httpStatusCode === 429;
	}
	return error;
}
