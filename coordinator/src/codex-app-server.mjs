import { EventEmitter } from 'node:events';
import { spawn, spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readdirSync, renameSync, statSync, unlinkSync } from 'node:fs';
import path from 'node:path';

import { DEFAULT_CHILD_STOP_TIMEOUT_MS, terminateChildProcess } from './child-process-lifecycle.mjs';
import { JsonlDecoder, encodeJsonLine } from './jsonl.mjs';
import { parseDecision } from './decision-parser.mjs';
import { PLANNER_OUTPUT_SCHEMA, PLANNER_SYSTEM_PROMPT } from './prompts.mjs';
import { createProviderChildEnvironment } from './provider-environment.mjs';
import { sanitizeDiagnosticErrorCode, sanitizeDiagnosticErrorMessage, sanitizeDiagnosticText } from './diagnostic-sanitizer.mjs';

const APP_SERVER_MAX_LINE_BYTES = 4 * 1_024 * 1_024;
const DEFAULT_REQUEST_TIMEOUT_MS = 15_000;
const DEFAULT_PLANNING_TIMEOUT_MS = 45_000;
const DEFAULT_MAX_DECISION_BYTES = 256 * 1_024;
const MAX_TIMED_OUT_REQUEST_IDS = 1_024;
const PROFILE_VALUE_PATTERN = /^[A-Za-z0-9._-]+$/;
const DESKTOP_RUNTIME_EXECUTABLES = Object.freeze([
	'codex.exe',
	'codex-code-mode-host.exe',
	'codex-command-runner.exe',
	'codex-windows-sandbox-setup.exe',
]);
const CLIENT_INFO = Object.freeze({ name: 'arena-agents-coordinator', title: 'Minecraft Arena Agents', version: '1.0.0' });
const CLIENT_CAPABILITIES = Object.freeze({ experimentalApi: true, requestAttestation: false });

export class CodexProtocolError extends Error {
	constructor(code, message, options) {
		super(message, options);
		this.name = 'CodexProtocolError';
		this.code = code;
	}
}

export function buildCodexArgs(config) {
	const model = profileValue(config.model, 'model');
	const effort = profileValue(config.reasoningEffort, 'reasoningEffort');
	const serviceTier = profileValue(config.serviceTier, 'serviceTier');
	return [
		'app-server', '--stdio',
		'-c', `model="${model}"`,
		'-c', `model_reasoning_effort="${effort}"`,
		'-c', `service_tier="${serviceTier}"`,
		'-c', 'features.fast_mode=true',
		'-c', 'mcp_servers={}',
		'-c', 'features.apps=false',
		'-c', 'features.browser_use=false',
		'-c', 'features.computer_use=false',
		'-c', 'features.goals=false',
		'-c', 'features.hooks=false',
		'-c', 'features.image_generation=false',
		'-c', 'features.multi_agent=false',
		'-c', 'features.plugins=false',
		'-c', 'features.skill_search=false',
		'-c', 'features.shell_tool=false',
		'-c', 'features.unified_exec=false',
		'-c', 'features.view_image=false',
	];
}

export function resolveCodexLaunch(config, dependencies = {}) {
	const platform = dependencies.platform ?? process.platform;
	const environment = createProviderChildEnvironment(
		'codex',
		dependencies.env ?? config.environment ?? process.env,
		config.bridgeSecretEnvironmentVariable,
	);
	const nodeExecutable = dependencies.execPath ?? process.execPath;
	const pathExists = dependencies.existsSync ?? existsSync;
	const appData = windowsEnvironmentValue(environment, 'APPDATA');
	if (platform === 'win32' && appData !== null) {
		const entrypoint = path.join(appData, 'npm', 'node_modules', '@openai', 'codex', 'bin', 'codex.js');
		if (pathExists(entrypoint)) return { command: nodeExecutable, args: [entrypoint, ...buildCodexArgs(config)], environment };
	}
	if (platform === 'win32') {
		const desktopCli = cacheInstalledCodexDesktopCli(environment, dependencies);
		if (desktopCli !== null) {
			return { command: desktopCli, args: buildCodexArgs(config), environment };
		}
	}
	return { command: 'codex', args: buildCodexArgs(config), environment };
}

function cacheInstalledCodexDesktopCli(environment, dependencies = {}) {
	const programFiles = windowsEnvironmentValue(environment, 'ProgramFiles')
		?? windowsEnvironmentValue(environment, 'ProgramW6432')
		?? inferredProgramFiles(environment);
	const localAppDataRoots = candidateLocalAppDataRoots(environment);
	if (programFiles === null || localAppDataRoots.length === 0) {
		try { console.error('[codex] Installed desktop CLI discovery skipped: Windows profile directories are unavailable'); } catch { /* discovery diagnostics are best effort */ }
		return null;
	}
	for (const localAppData of localAppDataRoots) {
		const cachedCli = findCompleteCachedDesktopCli(localAppData);
		if (cachedCli !== null) return cachedCli;
	}
	const localAppData = localAppDataRoots[0];
	const windowsApps = path.join(programFiles, 'WindowsApps');
	try {
		const registeredLocations = dependencies.windowsPackageLocations
			?? discoverWindowsPackageLocations(environment, dependencies.spawnSync ?? spawnSync);
		const registeredCandidates = registeredLocations
			.map(candidateFromPackageLocation)
			.filter((candidate) => candidate !== null);
		const scannedCandidates = registeredCandidates.length > 0 ? [] : readdirSync(windowsApps, { withFileTypes: true })
			.filter((entry) => entry.isDirectory() && /^OpenAI\.Codex_[0-9]+(?:\.[0-9]+){3}_x64__2p2nqsd0c76g0$/.test(entry.name))
			.map((entry) => candidateFromPackageLocation(path.join(windowsApps, entry.name)))
			.filter((candidate) => candidate !== null);
		const candidates = [...registeredCandidates, ...scannedCandidates]
			.filter((candidate) => DESKTOP_RUNTIME_EXECUTABLES.every((name) => existsSync(path.join(candidate.resources, name))))
			.sort((left, right) => compareNumericVersions(right.version, left.version));
		for (const candidate of candidates) {
			const sources = DESKTOP_RUNTIME_EXECUTABLES.map((name) => ({
				name,
				path: path.join(candidate.resources, name),
				size: statSync(path.join(candidate.resources, name)).size,
			}));
			if (sources.some(({ size }) => size <= 0)) continue;
			const cacheDirectory = path.join(localAppData, 'ArenaAgents', 'codex-runtime', candidate.version);
			const cachedCli = path.join(cacheDirectory, 'codex.exe');
			mkdirSync(cacheDirectory, { recursive: true });
			for (const source of sources) {
				const cached = path.join(cacheDirectory, source.name);
				if (existsSync(cached) && statSync(cached).size === source.size) continue;
				const staging = `${cached}.staging-${process.pid}`;
				if (existsSync(staging)) unlinkSync(staging);
				copyFileSync(source.path, staging);
				if (existsSync(cached)) unlinkSync(cached);
				renameSync(staging, cached);
			}
			return cachedCli;
		}
	} catch (error) {
		const code = sanitizeDiagnosticErrorCode(error, { fallback: 'FILESYSTEM_ERROR', maxBytes: 64 });
		const message = sanitizeDiagnosticErrorMessage(error, { fallback: 'unknown filesystem error', maxBytes: 3_840 });
		try { console.error(`[codex] Installed desktop CLI discovery failed (${code}): ${message}`); } catch { /* discovery diagnostics are best effort */ }
		return null;
	}
	return null;
}

function findCompleteCachedDesktopCli(localAppData) {
	const cacheRoot = path.join(localAppData, 'ArenaAgents', 'codex-runtime');
	try {
		const versions = readdirSync(cacheRoot, { withFileTypes: true })
			.filter((entry) => entry.isDirectory() && /^[0-9]+(?:\.[0-9]+){3}$/.test(entry.name))
			.map((entry) => entry.name)
			.sort(compareNumericVersions)
			.reverse();
		for (const version of versions) {
			const directory = path.join(cacheRoot, version);
			const complete = DESKTOP_RUNTIME_EXECUTABLES.every((name) => {
				const executable = path.join(directory, name);
				return existsSync(executable) && statSync(executable).size > 0;
			});
			if (complete) return path.join(directory, 'codex.exe');
		}
	} catch {
		// A missing or unreadable cache simply falls through to registered-package discovery.
	}
	return null;
}

function candidateFromPackageLocation(packageLocation) {
	if (typeof packageLocation !== 'string' || !path.isAbsolute(packageLocation)) return null;
	const normalized = path.normalize(packageLocation);
	const match = /^OpenAI\.Codex_([0-9]+(?:\.[0-9]+){3})_x64__2p2nqsd0c76g0$/.exec(path.basename(normalized));
	if (match === null) return null;
	return { version: match[1], resources: path.join(normalized, 'app', 'resources') };
}

function discoverWindowsPackageLocations(environment, run) {
	const command = "$package = Get-AppxPackage -Name OpenAI.Codex | Sort-Object Version -Descending | Select-Object -First 1; if ($package) { [Console]::Out.Write($package.InstallLocation) }";
	try {
		const result = run('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], {
			encoding: 'utf8',
			env: environment,
			maxBuffer: 64 * 1_024,
			timeout: 5_000,
			windowsHide: true,
		});
		if (result.error || result.status !== 0 || typeof result.stdout !== 'string') return [];
		const location = result.stdout.trim();
		return location.length === 0 ? [] : [location];
	} catch {
		return [];
	}
}

function inferredProgramFiles(environment) {
	const systemDrive = windowsEnvironmentValue(environment, 'SystemDrive');
	if (systemDrive !== null) {
		const root = path.isAbsolute(systemDrive) ? systemDrive : `${systemDrive}${path.sep}`;
		return path.join(root, 'Program Files');
	}
	const localAppData = windowsEnvironmentValue(environment, 'LOCALAPPDATA') ?? inferredLocalAppData(environment);
	return localAppData === null ? null : path.join(path.parse(localAppData).root, 'Program Files');
}

function inferredLocalAppData(environment) {
	const appData = windowsEnvironmentValue(environment, 'APPDATA');
	if (appData !== null) return path.join(path.dirname(appData), 'Local');
	const userProfile = windowsEnvironmentValue(environment, 'USERPROFILE');
	return userProfile === null ? null : path.join(userProfile, 'AppData', 'Local');
}

function candidateLocalAppDataRoots(environment) {
	const userProfile = windowsEnvironmentValue(environment, 'USERPROFILE');
	const appData = windowsEnvironmentValue(environment, 'APPDATA');
	const candidates = [
		userProfile === null ? null : path.join(userProfile, 'AppData', 'Local'),
		appData === null ? null : path.join(path.dirname(appData), 'Local'),
		windowsEnvironmentValue(environment, 'LOCALAPPDATA'),
	].filter((value) => value !== null);
	return [...new Set(candidates.map((value) => path.normalize(value)))];
}

function windowsEnvironmentValue(environment, name) {
	const match = Object.entries(environment).find(([key, value]) => key.toLowerCase() === name.toLowerCase()
		&& typeof value === 'string' && value.trim().length > 0);
	return match?.[1] ?? null;
}

function compareNumericVersions(left, right) {
	const leftParts = left.split('.').map(Number);
	const rightParts = right.split('.').map(Number);
	for (let index = 0; index < Math.max(leftParts.length, rightParts.length); index += 1) {
		const difference = (leftParts[index] ?? 0) - (rightParts[index] ?? 0);
		if (difference !== 0) return difference;
	}
	return 0;
}

export class CodexStdioTransport extends EventEmitter {
	#config;
	#spawn;
	#child = null;
	#decoder = null;
	#requestId = 0;
	#pending = new Map();
	#timedOutRequestIds = new Set();
	#stopTimeoutMs;

	constructor(config, dependencies = {}) {
		super();
		this.#config = config;
		this.#spawn = dependencies.spawn ?? spawn;
		this.#stopTimeoutMs = dependencies.stopTimeoutMs ?? DEFAULT_CHILD_STOP_TIMEOUT_MS;
	}

	setEnvironment(environment) {
		if (this.#child !== null) throw new CodexProtocolError('TRANSPORT_RUNNING', 'Codex environment cannot change after startup');
		if (environment === null || typeof environment !== 'object' || Array.isArray(environment)) throw new TypeError('Codex launch environment must be an object');
		this.#config = { ...this.#config, environment: { ...environment } };
	}

	async start() {
		if (this.#child !== null) return;
		this.#decoder = new JsonlDecoder({ maxBytes: APP_SERVER_MAX_LINE_BYTES });
		let child;
		try {
			const launch = resolveCodexLaunch(this.#config);
			child = this.#spawn(launch.command, launch.args, {
				cwd: this.#config.cwd,
				env: launch.environment,
				stdio: ['pipe', 'pipe', 'pipe'],
				windowsHide: true,
			});
		} catch (error) {
			throw new CodexProtocolError('SPAWN_FAILED', `Could not start Codex app-server: ${sanitizeDiagnosticErrorMessage(error)}`, { cause: error });
		}
		this.#child = child;
		child.stdout.on('data', (chunk) => this.#onStdout(child, chunk));
		child.stderr.on('data', (chunk) => {
			try { this.emit('diagnostic', sanitizeDiagnosticText(chunk, { maxBytes: 4_096 })); } catch { /* diagnostics cannot interrupt provider IO */ }
		});
		child.on('exit', (code, signal) => this.#onExit(child, code, signal));
		await new Promise((resolve, reject) => {
			const onSpawn = () => { cleanup(); resolve(); };
			const onError = (error) => {
				cleanup();
				if (child === this.#child) this.#child = null;
				reject(new CodexProtocolError('SPAWN_FAILED', `Could not start Codex app-server: ${sanitizeDiagnosticErrorMessage(error)}`, { cause: error }));
			};
			const cleanup = () => { child.off('spawn', onSpawn); child.off('error', onError); };
			child.once('spawn', onSpawn);
			child.once('error', onError);
			if (Number.isInteger(child.pid) && child.pid > 0) onSpawn();
		});
		this.#ownRuntimeErrors(child);
	}

	request(method, params = {}, { timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS, signal } = {}) {
		this.#requireRunning();
		if (signal?.aborted) return Promise.reject(new CodexProtocolError('REQUEST_ABORTED', `Codex request '${method}' was aborted`));
		const id = this.#nextRequestId();
		return new Promise((resolve, reject) => {
			const onAbort = () => {
				if (!this.#pending.delete(id)) return;
				clearTimeout(timer);
				this.#rememberTimedOutRequest(id);
				reject(new CodexProtocolError('REQUEST_ABORTED', `Codex request '${method}' was aborted`));
			};
			const timer = setTimeout(() => {
				this.#pending.delete(id);
				signal?.removeEventListener?.('abort', onAbort);
				this.#rememberTimedOutRequest(id);
				reject(new CodexProtocolError('REQUEST_TIMEOUT', `Codex request '${method}' timed out after ${timeoutMs} ms`));
			}, timeoutMs);
			this.#pending.set(id, { method, resolve, reject, timer, signal, onAbort });
			signal?.addEventListener?.('abort', onAbort, { once: true });
			if (signal?.aborted) {
				onAbort();
				return;
			}
			try {
				this.#write({ id, method, params });
			} catch (error) {
				clearTimeout(timer);
				this.#pending.delete(id);
				signal?.removeEventListener?.('abort', onAbort);
				reject(error);
			}
		});
	}

	notify(method, params = {}) {
		this.#requireRunning();
		this.#write({ method, params });
	}

	respond(id, result) {
		this.#requireRunning();
		if ((typeof id !== 'string' || id.length === 0) && !Number.isSafeInteger(id)) {
			throw new TypeError('Codex server request id must be a nonblank string or safe integer');
		}
		this.#write({ id, result });
	}

	async stop() {
		const child = this.#child;
		if (child === null) return;
		this.#child = null;
		this.#rejectPending(new CodexProtocolError('TRANSPORT_STOPPED', 'Codex app-server transport stopped'));
		await terminateChildProcess(child, { timeoutMs: this.#stopTimeoutMs });
	}

	#onStdout(child, chunk) {
		if (child !== this.#child) return;
		try {
			for (const message of this.#decoder.push(chunk)) this.#acceptMessage(message);
		} catch (error) {
			this.emit('protocolError', new CodexProtocolError(error.code ?? 'INVALID_RESPONSE', error.message, { cause: error }));
			void this.stop().catch((stopError) => this.emit('protocolError', stopError));
		}
	}

	#acceptMessage(message) {
		if (Object.hasOwn(message, 'id') && typeof message.method === 'string') {
			this.emit('serverRequest', { id: message.id, method: message.method, params: message.params ?? {} });
			return;
		}
		if (Object.hasOwn(message, 'id')) {
			const pending = this.#pending.get(message.id);
			if (pending === undefined) {
				if (this.#timedOutRequestIds.delete(message.id)) return;
				this.emit('protocolError', new CodexProtocolError('UNKNOWN_RESPONSE_ID', `Codex response used unknown id '${String(message.id)}'`));
				return;
			}
			this.#pending.delete(message.id);
			clearTimeout(pending.timer);
			pending.signal?.removeEventListener?.('abort', pending.onAbort);
			if (Object.hasOwn(message, 'error')) pending.reject(new CodexProtocolError('RPC_ERROR', `${pending.method}: ${rpcErrorMessage(message.error)}`));
			else if (Object.hasOwn(message, 'result')) pending.resolve(message.result);
			else pending.reject(new CodexProtocolError('INVALID_RESPONSE', `Codex response for '${pending.method}' has no result or error`));
			return;
		}
		if (typeof message.method === 'string' && !Object.hasOwn(message, 'id')) {
			this.emit('notification', { method: message.method, params: message.params ?? {} });
			return;
		}
		this.emit('protocolError', new CodexProtocolError('INVALID_RESPONSE', 'Codex app-server emitted an invalid JSON-RPC message'));
	}

	#onExit(child, code, signal) {
		if (child !== this.#child) return;
		this.#child = null;
		const error = new CodexProtocolError('PROCESS_EXITED', `Codex app-server exited (code=${String(code)}, signal=${String(signal)})`);
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
		const error = new CodexProtocolError(
			'PROCESS_IO_ERROR',
			`Codex app-server process I/O failed: ${sanitizeDiagnosticErrorMessage(cause)}`,
			{ cause },
		);
		this.#rejectPending(error);
		this.emit('exit', error);
		void terminateChildProcess(child, { timeoutMs: this.#stopTimeoutMs })
			.catch((stopError) => this.emit('protocolError', stopError));
	}

	#write(message) {
		this.#requireRunning();
		this.#child.stdin.write(encodeJsonLine(message, { maxBytes: APP_SERVER_MAX_LINE_BYTES }));
	}

	#requireRunning() {
		if (this.#child === null || this.#child.killed) throw new CodexProtocolError('TRANSPORT_NOT_RUNNING', 'Codex app-server transport is not running');
	}

	#nextRequestId() {
		if (this.#requestId === Number.MAX_SAFE_INTEGER) throw new CodexProtocolError('REQUEST_ID_EXHAUSTED', 'Codex request ID sequence is exhausted');
		this.#requestId += 1;
		return this.#requestId;
	}

	#rememberTimedOutRequest(id) {
		this.#timedOutRequestIds.add(id);
		if (this.#timedOutRequestIds.size <= MAX_TIMED_OUT_REQUEST_IDS) return;
		this.#timedOutRequestIds.delete(this.#timedOutRequestIds.values().next().value);
	}

	#rejectPending(error) {
		for (const pending of this.#pending.values()) {
			clearTimeout(pending.timer);
			pending.signal?.removeEventListener?.('abort', pending.onAbort);
			pending.reject(error);
		}
		this.#pending.clear();
	}
}

export async function listCodexModels(transport, {
	signal,
	maxPages = 32,
	maxModels = 512,
	yieldControl = () => new Promise((resolve) => setImmediate(resolve)),
} = {}) {
	const models = [];
	const seenCursors = new Set();
	let cursor = null;
	for (let page = 0; page < maxPages; page += 1) {
		throwIfCatalogAborted(signal);
		const response = await transport.request(
			'model/list',
			{ cursor, limit: 100, includeHidden: false },
			{ signal },
		);
		throwIfCatalogAborted(signal);
		if (!Array.isArray(response?.data)) throw new CodexProtocolError('INVALID_CATALOG', 'model/list response must contain a data array');
		if (models.length + response.data.length > maxModels) {
			throw new CodexProtocolError('CATALOG_MODEL_LIMIT', `Codex model catalog exceeds ${maxModels} entries`);
		}
		models.push(...response.data);
		const nextCursor = response.nextCursor ?? null;
		if (nextCursor === null) return models;
		if (typeof nextCursor !== 'string' || nextCursor.trim().length === 0 || nextCursor.length > 1_024) {
			throw new CodexProtocolError('INVALID_CATALOG_CURSOR', 'model/list nextCursor must be a bounded nonblank string or null');
		}
		if (seenCursors.has(nextCursor)) throw new CodexProtocolError('CATALOG_CURSOR_LOOP', 'model/list repeated a pagination cursor');
		seenCursors.add(nextCursor);
		cursor = nextCursor;
		if (page + 1 >= maxPages) throw new CodexProtocolError('CATALOG_PAGE_LIMIT', `Codex model catalog exceeds ${maxPages} pages`);
		await yieldControl();
	}
	throw new CodexProtocolError('CATALOG_PAGE_LIMIT', `Codex model catalog exceeds ${maxPages} pages`);
}

function throwIfCatalogAborted(signal) {
	if (signal?.aborted) throw new CodexProtocolError('REQUEST_ABORTED', 'Codex model catalog refresh was aborted');
}

export class CodexAgent {
	#config;
	#transport;
	#started = false;
	#threadId = null;
	#activeTurnId = null;

	constructor(config, transport = new CodexStdioTransport(config)) {
		this.#config = validateAgentConfig(config);
		this.#transport = transport;
	}

	get model() { return this.#config.model; }
	get reasoningEffort() { return this.#config.reasoningEffort; }
	get serviceTier() { return this.#config.serviceTier; }

	async start() {
		if (this.#started) return;
		await this.#transport.start();
		try {
			await this.#transport.request('initialize', { clientInfo: CLIENT_INFO, capabilities: CLIENT_CAPABILITIES });
			this.#transport.notify('initialized', {});
			const models = await this.#listModels();
			verifyModelProfile(models, this.#config);
			const response = await this.#transport.request('thread/start', {
				model: this.#config.model,
				serviceTier: this.#config.serviceTier,
				cwd: this.#config.cwd,
				allowProviderModelFallback: false,
				runtimeWorkspaceRoots: [this.#config.cwd],
				selectedCapabilityRoots: [],
				approvalPolicy: 'never',
				sandbox: 'read-only',
				dynamicTools: [],
				environments: [],
				ephemeral: true,
				baseInstructions: PLANNER_SYSTEM_PROMPT,
				developerInstructions: 'Return only the validated Minecraft decision object. Never call tools.',
			});
			this.#threadId = requireNestedId(response, 'thread', 'thread/start');
			this.#started = true;
		} catch (error) {
			await this.#transport.stop();
			throw error;
		}
	}

	async decide(input) {
		if (!this.#started || this.#threadId === null) throw new CodexProtocolError('AGENT_NOT_STARTED', 'Codex agent has not started');
		if (this.#activeTurnId !== null) throw new CodexProtocolError('TURN_IN_PROGRESS', 'Only one Codex turn may run at a time');
		if (typeof input !== 'string' || input.trim().length === 0) throw new TypeError('planner input must be a nonblank string');
		const collector = this.#collectTurn();
		try {
			const response = await this.#transport.request('turn/start', {
				threadId: this.#threadId,
				input: [{ type: 'text', text: input }],
				model: this.#config.model,
				effort: this.#config.reasoningEffort,
				serviceTier: this.#config.serviceTier,
				approvalPolicy: 'never',
				environments: [],
				outputSchema: PLANNER_OUTPUT_SCHEMA,
			});
			this.#activeTurnId = requireNestedId(response, 'turn', 'turn/start');
			collector.setTurnId(this.#activeTurnId);
			const text = await withTimeout(collector.promise, this.#config.planningTimeoutMs, async () => {
				await this.interrupt();
			});
			return parseDecision(text);
		} catch (error) {
			if (error?.code === 'TIMEOUT') throw new CodexProtocolError('PLANNING_TIMEOUT', `Codex planning exceeded ${this.#config.planningTimeoutMs} ms`, { cause: error });
			if (error?.code === 'TURN_OUTPUT_LIMIT') {
				try { await this.interrupt(); } catch { /* the bounded failure remains authoritative */ }
			}
			throw error;
		} finally {
			collector.dispose();
			this.#activeTurnId = null;
		}
	}

	async interrupt() {
		if (!this.#started || this.#threadId === null || this.#activeTurnId === null) return;
		await this.#transport.request('turn/interrupt', { threadId: this.#threadId, turnId: this.#activeTurnId });
	}

	async restart() {
		await this.stop();
		await this.start();
	}

	async stop() {
		if (this.#activeTurnId !== null) {
			try { await this.interrupt(); } catch { /* teardown continues */ }
		}
		this.#started = false;
		this.#threadId = null;
		this.#activeTurnId = null;
		await this.#transport.stop();
	}

	async #listModels() {
		return listCodexModels(this.#transport);
	}

	#collectTurn() {
		let expectedTurnId = null;
		let lastMessage = null;
		let streamedMessage = '';
		let streamedMessageBytes = 0;
		let outputLimitError = null;
		let resolvePromise;
		let rejectPromise;
		const promise = new Promise((resolve, reject) => { resolvePromise = resolve; rejectPromise = reject; });
		const onNotification = ({ method, params }) => {
			if (params?.threadId !== this.#threadId) return;
			if (expectedTurnId !== null && turnIdOf(method, params) !== expectedTurnId) return;
			if (outputLimitError !== null) return;
			if (method === 'item/agentMessage/delta' && typeof params?.delta === 'string') {
				const deltaBytes = Buffer.byteLength(params.delta, 'utf8');
				if (streamedMessageBytes + deltaBytes > this.#config.maxDecisionBytes) {
					outputLimitError = new CodexProtocolError('TURN_OUTPUT_LIMIT', `Codex planner output exceeded ${this.#config.maxDecisionBytes} bytes`);
					rejectPromise(outputLimitError);
					return;
				}
				streamedMessageBytes += deltaBytes;
				streamedMessage += params.delta;
			}
			if (method === 'item/completed' && params.item?.type === 'agentMessage' && typeof params.item.text === 'string') {
				if (Buffer.byteLength(params.item.text, 'utf8') > this.#config.maxDecisionBytes) {
					outputLimitError = new CodexProtocolError('TURN_OUTPUT_LIMIT', `Codex planner output exceeded ${this.#config.maxDecisionBytes} bytes`);
					rejectPromise(outputLimitError);
					return;
				}
				lastMessage = params.item.text;
				streamedMessage = '';
				streamedMessageBytes = 0;
			}
			if (method === 'turn/completed') {
				if (params.turn?.status !== 'completed') rejectPromise(new CodexProtocolError('TURN_FAILED', `Codex turn ended with status '${String(params.turn?.status)}'`));
				else if (lastMessage === null && streamedMessage.length === 0) rejectPromise(new CodexProtocolError('MISSING_FINAL_MESSAGE', 'Codex turn completed without an agent message'));
				else resolvePromise(lastMessage ?? streamedMessage);
			}
		};
		this.#transport.on('notification', onNotification);
		return {
			promise,
			setTurnId: (turnId) => { expectedTurnId = turnId; },
			dispose: () => this.#transport.off('notification', onNotification),
		};
	}
}

export async function checkCodexModelProfile(configValue, transport = new CodexStdioTransport(configValue)) {
	const config = validateAgentConfig(configValue);
	await transport.start();
	try {
		await transport.request('initialize', { clientInfo: CLIENT_INFO, capabilities: CLIENT_CAPABILITIES });
		transport.notify('initialized', {});
		const models = await listCodexModels(transport);
		return verifyModelProfile(models, config);
	} finally {
		await transport.stop();
	}
}

export function verifyModelProfile(models, config) {
	const model = models.find((candidate) => candidate?.model === config.model || candidate?.id === config.model);
	if (model === undefined) throw new CodexProtocolError('MODEL_PROFILE_UNAVAILABLE', `Model '${config.model}' is absent from the Codex catalog`);
	const efforts = Array.isArray(model.supportedReasoningEfforts)
		? model.supportedReasoningEfforts.map((entry) => typeof entry === 'string' ? entry : entry?.reasoningEffort)
		: [];
	const tiers = [
		...(Array.isArray(model.serviceTiers) ? model.serviceTiers.map((entry) => typeof entry === 'string' ? entry : entry?.id) : []),
		...(Array.isArray(model.additionalSpeedTiers) ? model.additionalSpeedTiers : []),
	];
	if (!efforts.includes(config.reasoningEffort) || !tiers.includes(config.serviceTier)) throw new CodexProtocolError('MODEL_PROFILE_UNAVAILABLE', `Model '${config.model}' does not advertise effort '${config.reasoningEffort}' with tier '${config.serviceTier}'`);
	return model;
}

function validateAgentConfig(config) {
	if (config === null || typeof config !== 'object') throw new TypeError('Codex agent config must be an object');
	for (const field of ['agentId', 'model', 'reasoningEffort', 'serviceTier']) profileValue(config[field], field);
	if (config.serviceTier !== 'fast') throw new TypeError("serviceTier must be exactly 'fast'");
	if (typeof config.cwd !== 'string' || config.cwd.trim().length === 0) throw new TypeError('cwd must be a nonblank path');
	const planningTimeoutMs = config.planningTimeoutMs ?? DEFAULT_PLANNING_TIMEOUT_MS;
	if (!Number.isSafeInteger(planningTimeoutMs) || planningTimeoutMs <= 0) throw new TypeError('planningTimeoutMs must be a positive safe integer');
	const maxDecisionBytes = config.maxDecisionBytes ?? DEFAULT_MAX_DECISION_BYTES;
	if (!Number.isSafeInteger(maxDecisionBytes) || maxDecisionBytes <= 0) throw new TypeError('maxDecisionBytes must be a positive safe integer');
	return { ...config, planningTimeoutMs, maxDecisionBytes };
}

function profileValue(value, field) {
	if (typeof value !== 'string' || !PROFILE_VALUE_PATTERN.test(value)) throw new TypeError(`${field} contains an unsupported profile value`);
	return value;
}

function requireNestedId(response, field, method) {
	const id = response?.[field]?.id;
	if (typeof id !== 'string' || id.length === 0) throw new CodexProtocolError('INVALID_RESPONSE', `${method} response is missing ${field}.id`);
	return id;
}

function turnIdOf(method, params) {
	return method === 'turn/completed' ? params.turn?.id : params.turnId;
}

function withTimeout(promise, timeoutMs, onTimeout) {
	return new Promise((resolve, reject) => {
		const timer = setTimeout(async () => {
			try { await onTimeout(); } catch { /* primary timeout wins */ }
			const error = new Error('operation timed out');
			error.code = 'TIMEOUT';
			reject(error);
		}, timeoutMs);
		promise.then((value) => { clearTimeout(timer); resolve(value); }, (error) => { clearTimeout(timer); reject(error); });
	});
}

function rpcErrorMessage(error) {
	if (error && typeof error.message === 'string') return redact(error.message);
	return 'unknown JSON-RPC error';
}

function redact(value) {
	return sanitizeDiagnosticText(value, { maxBytes: 4_096 });
}
