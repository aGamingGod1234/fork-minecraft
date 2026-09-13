import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import test from 'node:test';
import {
	evaluateHeadlessAssertions,
	normalizeHeadlessScenario,
	runHeadlessScenario,
	writeHeadlessReport,
} from '../src/headless-matrix.mjs';

const scenario = (overrides = {}) => normalizeHeadlessScenario({
	id: 'runner-case', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high',
	serviceTier: 'priority', task: 'Do the bounded task', timeoutMs: 1000,
	assert: [
		{ type: 'lifecycle', state: 'COMPLETED' },
		{ type: 'chat', message: 'HEADLESS_PASS' },
		{ type: 'action', actionType: 'move', args: { x: 1 }, resultState: 'SUCCEEDED' },
		{ type: 'program', event: 'program_finished', status: 'COMPLETED' },
		{ type: 'rcon', command: 'data get entity @s Pos', match: '1.0' },
	],
	...overrides,
});

const jsonl = (rows) => rows.map((row) => JSON.stringify(row)).join('\n') + '\n';
const GENERATED_NAME_AT_100 = 'ha_runner__0002s';
const POWERSHELL_TEST_TIMEOUT_MS = 30_000;

test('PowerShell wrapper samples a fast-exit tracked runner before completion', () => {
	const wrapper = path.resolve('../scripts/run-headless-provider-matrix.ps1').replaceAll("'", "''");
	const script = `
$ErrorActionPreference = 'Stop'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile('${wrapper}', [ref] $tokens, [ref] $errors)
if ($errors.Count -gt 0) { throw $errors[0].Message }
$required = @('ConvertTo-ProcessCreationKey', 'Get-ProcessSnapshot', 'Test-ProcessIdentityMatch', 'Test-ChildCreationAfterParent', 'Add-TrackedProcessIdentity', 'Add-ProcessTreeSnapshot', 'Get-TrackedResourceSnapshot', 'Measure-RunnerResourcesUntilExit')
$definitions = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $required -contains $node.Name }, $true))
foreach ($definition in $definitions) { Invoke-Expression $definition.Extent.Text }
$script:PollMilliseconds = 10
function Get-CimInstance {
	return @([pscustomobject]@{ ProcessId = 10; ParentProcessId = 1; CreationDate = '100'; WorkingSetSize = 4096 })
}
$process = [pscustomobject]@{ Id = 10; HasExited = $false; WorkingSet64 = 4096 }
$process | Add-Member -MemberType ScriptMethod -Name Refresh -Value {}
$process | Add-Member -MemberType ScriptMethod -Name WaitForExit -Value { param($milliseconds) $this.HasExited = $true; return $true }
$identity = [pscustomobject]@{ ProcessId = 10; ParentProcessId = 1; CreationDate = '100' }
$tracked = [System.Collections.Generic.List[object]]::new()
$runner = @{ Process = $process; Identity = $identity }
$peak = Measure-RunnerResourcesUntilExit $runner @($runner) $tracked ([DateTime]::UtcNow.AddSeconds(1))
[pscustomobject]@{ processCount = $peak.processCount; peakRssBytes = $peak.peakRssBytes; exited = $process.HasExited } | ConvertTo-Json -Compress
`;
	const result = spawnSync('powershell.exe', ['-NoProfile', '-Command', script], { encoding: 'utf8', timeout: POWERSHELL_TEST_TIMEOUT_MS });
	assert.equal(result.status, 0, result.stderr || result.stdout);
	const peak = JSON.parse(result.stdout.trim());
	assert.ok(peak.processCount >= 1, result.stdout);
	assert.ok(peak.peakRssBytes > 0, result.stdout);
	assert.equal(peak.exited, true);
});

test('PowerShell resource sampling reuses one process snapshot per sampling phase', () => {
	const wrapper = path.resolve('../scripts/run-headless-provider-matrix.ps1').replaceAll("'", "''");
	const script = `
$ErrorActionPreference = 'Stop'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile('${wrapper}', [ref] $tokens, [ref] $errors)
if ($errors.Count -gt 0) { throw $errors[0].Message }
$required = @('ConvertTo-ProcessCreationKey', 'Get-ProcessSnapshot', 'Test-ProcessIdentityMatch', 'Test-ChildCreationAfterParent', 'Add-TrackedProcessIdentity', 'Add-ProcessTreeSnapshot', 'Get-TrackedResourceSnapshot', 'Measure-RunnerResourcesUntilExit')
$definitions = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $required -contains $node.Name }, $true))
foreach ($definition in $definitions) { Invoke-Expression $definition.Extent.Text }
$script:PollMilliseconds = 1
$script:snapshots = 0
function Get-CimInstance {
	$script:snapshots += 1
	return @([pscustomobject]@{ ProcessId = 10; ParentProcessId = 1; CreationDate = '100'; WorkingSetSize = 4096 })
}
$process = [pscustomobject]@{ Id = 10; HasExited = $true }
$process | Add-Member -MemberType ScriptMethod -Name WaitForExit -Value { param($milliseconds) return $true }
$identity = [pscustomobject]@{ ProcessId = 10; ParentProcessId = 1; CreationDate = '100' }
$tracked = [System.Collections.Generic.List[object]]::new()
$runner = @{ Process = $process; Identity = $identity }
$peak = Measure-RunnerResourcesUntilExit $runner @($runner) $tracked ([DateTime]::UtcNow.AddSeconds(1))
[pscustomobject]@{ snapshots = $script:snapshots; count = $peak.processCount; rss = $peak.peakRssBytes } | ConvertTo-Json -Compress
`;
	const result = spawnSync('powershell.exe', ['-NoProfile', '-Command', script], { encoding: 'utf8', timeout: POWERSHELL_TEST_TIMEOUT_MS });
	assert.equal(result.status, 0, result.stderr || result.stdout);
	assert.deepEqual(JSON.parse(result.stdout.trim()), { snapshots: 1, count: 1, rss: 4096 });
});

test('PowerShell process tracking rejects a reused PID before sampling or cleanup', () => {
	const wrapper = path.resolve('../scripts/run-headless-provider-matrix.ps1').replaceAll("'", "''");
	const script = `
$ErrorActionPreference = 'Stop'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile('${wrapper}', [ref] $tokens, [ref] $errors)
if ($errors.Count -gt 0) { throw $errors[0].Message }
$required = @('ConvertTo-ProcessCreationKey', 'Get-ProcessSnapshot', 'Test-ProcessIdentityMatch', 'Test-ChildCreationAfterParent', 'Add-ProcessTreeSnapshot', 'Get-TrackedResourceSnapshot', 'Stop-TrackedProcessIds', 'Assert-TrackedProcessIdsGone')
$definitions = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $required -contains $node.Name }, $true))
foreach ($definition in $definitions) { Invoke-Expression $definition.Extent.Text }
$script:CleanupTimeoutSeconds = 1
$script:PollMilliseconds = 1
$script:stopped = @()
$script:currentCreation = 'NEW'
$script:currentParent = 1
function Get-CimInstance { @(
	[pscustomobject]@{ ProcessId = 4242; ParentProcessId = $script:currentParent; CreationDate = $script:currentCreation; WorkingSetSize = 9999 },
	[pscustomobject]@{ ProcessId = 4243; ParentProcessId = 4242; CreationDate = '999999'; WorkingSetSize = 8888 }
) }
function Stop-Process { param([int] $Id) $script:stopped += $Id }
$tracked = [System.Collections.Generic.List[object]]::new()
$tracked.Add([pscustomobject]@{ ProcessId = 4242; ParentProcessId = 1; CreationDate = 'OLD' })
$sample = Get-TrackedResourceSnapshot $tracked
$script:currentCreation = 'OLD'
$script:currentParent = 999
$ancestrySample = Get-TrackedResourceSnapshot $tracked
Stop-TrackedProcessIds $tracked
Assert-TrackedProcessIdsGone $tracked
[pscustomobject]@{ count = $sample.processCount; rss = $sample.rssBytes; ancestryCount = $ancestrySample.processCount; tracked = @($tracked | ForEach-Object { $_.ProcessId }); stopped = @($script:stopped) } | ConvertTo-Json -Compress
`;
	const result = spawnSync('powershell.exe', ['-NoProfile', '-Command', script], { encoding: 'utf8', timeout: POWERSHELL_TEST_TIMEOUT_MS });
	assert.equal(result.status, 0, result.stderr || result.stdout);
	assert.deepEqual(JSON.parse(result.stdout.trim()), { count: 0, rss: 0, ancestryCount: 0, tracked: [4242], stopped: [] });
});

test('PowerShell final snapshot adopts and cleans an authentic late child after its parent exits', () => {
	const wrapper = path.resolve('../scripts/run-headless-provider-matrix.ps1').replaceAll("'", "''");
	const script = `
$ErrorActionPreference = 'Stop'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile('${wrapper}', [ref] $tokens, [ref] $errors)
if ($errors.Count -gt 0) { throw $errors[0].Message }
$required = @('ConvertTo-ProcessCreationKey', 'Get-ProcessSnapshot', 'Test-ProcessIdentityMatch', 'Test-ChildCreationAfterParent', 'Add-TrackedProcessIdentity', 'Add-ProcessTreeSnapshot', 'Get-TrackedResourceSnapshot', 'Measure-RunnerResourcesUntilExit', 'Stop-TrackedProcessIds')
$definitions = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $required -contains $node.Name }, $true))
foreach ($definition in $definitions) { Invoke-Expression $definition.Extent.Text }
$script:CleanupTimeoutSeconds = 1
$script:PollMilliseconds = 1
$script:childLive = $true
$script:stopped = @()
function Get-CimInstance {
	if (-not $script:childLive) { return @() }
	return @([pscustomobject]@{ ProcessId = 20; ParentProcessId = 10; CreationDate = '200'; WorkingSetSize = 4000 })
}
function Stop-Process { param([int] $Id) $script:stopped += $Id; if ($Id -eq 20) { $script:childLive = $false } }
$tracked = [System.Collections.Generic.List[object]]::new()
$root = [pscustomobject]@{ ProcessId = 10; ParentProcessId = 1; CreationDate = '100' }
$tracked.Add($root)
$runner = @{ Process = [pscustomobject]@{ HasExited = $true }; Identity = $root }
$peak = Measure-RunnerResourcesUntilExit $runner @($runner) $tracked ([DateTime]::UtcNow.AddSeconds(1))
Stop-TrackedProcessIds $tracked
[pscustomobject]@{ tracked = @($tracked | ForEach-Object { $_.ProcessId }); count = $peak.processCount; rss = $peak.peakRssBytes; stopped = @($script:stopped) } | ConvertTo-Json -Compress
`;
	const result = spawnSync('powershell.exe', ['-NoProfile', '-Command', script], { encoding: 'utf8', timeout: POWERSHELL_TEST_TIMEOUT_MS });
	assert.equal(result.status, 0, result.stderr || result.stdout);
	assert.deepEqual(JSON.parse(result.stdout.trim()), { tracked: [10, 20], count: 1, rss: 4000, stopped: [20] });
});

test('runs a real-provider scenario with exact RCON sequence and injected evidence', async () => {
	const commands = [];
	let statusReads = 0;
	let clock = 100;
	let recorderClosed = 0;
	const files = new Map([
		['protocol.jsonl', jsonl([
			{ direction: 'outbound', envelope: { type: 'coordinator_status', payload: {
				circuits: [{ provider: 'codex', model: 'gpt-5.6-sol', operation: 'decide', count: 2, p50Ms: 321, p95Ms: 654, failureRate: 0, circuit: 'closed' }],
				latencies: [{ operation: 'observation_to_plan', count: 1, p50Ms: 700, p95Ms: 700 }],
			} } },
			{ direction: 'outbound', envelope: { type: 'action_command', payload: { actionId: 'move-1', actionType: 'move', arguments: { x: 1, y: 0, z: 0 } } } },
			{ direction: 'inbound', envelope: { type: 'action_result', payload: { actionId: 'move-1', actionType: 'move', state: 'SUCCEEDED', reasonCode: 'DESTINATION_REACHED' } } },
		])],
		['coordinator.jsonl', jsonl([
			{ event: 'program_step', actionType: 'chat', arguments: { message: 'HEADLESS_PASS' }, result: null },
			{ event: 'program_step', actionType: 'chat', arguments: { message: 'HEADLESS_PASS' }, result: { state: 'SUCCEEDED', reasonCode: 'DONE' } },
			{ event: 'program_finished', status: 'COMPLETED' },
		])],
		['server.log', 'agent chat: HEADLESS_PASS\n'],
	]);
	const rcon = {
		command: async (command) => {
			commands.push(command);
			if (command.includes('forceload ')) return { text: 'OK' };
			if (command.includes(' run fill ')) return { text: 'Successfully filled blocks' };
			if (command.includes('codex summon-configured ')) return { text: 'Created runner-case-agent. It is ready for a task.' };
			if (command.startsWith('codex start ')) return { text: 'Goal started.' };
			if (command.startsWith('codex status ')) return { text: statusReads++ === 0 ? 'state=RUNNING' : 'state=COMPLETED' };
			if (command === 'data get entity @s Pos') return { text: '[1.0d, 64.0d, 1.0d]' };
			if (command.startsWith('codex remove ')) return { text: 'Removed runner-case-agent.' };
			throw new Error(`unexpected command: ${command}`);
		},
		close: async () => {},
	};
	const report = await runHeadlessScenario({
		scenario: scenario(), runDirectory: 'C:/runs/runner-case', rcon,
		now: () => ++clock, readFile: async (file) => files.get(String(file).split(/[\\/]/).pop()) ?? '',
		providerTurnRecorder: { record: async () => { throw new Error('must not be called'); }, close: async () => { recorderClosed += 1; } },
		poll: async () => {},
	});

	assert.equal(report.status, 'PASSED');
	assert.equal(report.classification, 'PASSED');
	assert.equal(commands[0], 'execute in minecraft:overworld run forceload add 0 0');
	assert.equal(commands[1], 'execute in minecraft:overworld run fill -8 200 -8 8 200 8 minecraft:stone');
	assert.equal(commands[2], 'execute in minecraft:overworld run fill -8 201 -8 8 204 8 minecraft:air');
	const generatedName = commands[3].split(' ').at(-1);
	assert.ok(generatedName.length <= 16);
	assert.equal(commands[4], 'execute in minecraft:overworld run forceload remove 0 0');
	assert.equal(commands[5], `codex start ${generatedName} Do the bounded task`);
	assert.equal(commands[6], `codex status ${generatedName}`);
	assert.ok(commands.indexOf('data get entity @s Pos') < commands.indexOf(`codex remove ${generatedName}`));
	assert.equal(commands.at(-1), `codex remove ${generatedName}`);
	assert.equal(commands.some((command) => command.includes('action_result')), false);
	assert.equal(recorderClosed, 1);
	assert.equal(report.assertions.every((result) => result.passed), true);
	assert.deepEqual(report.assertions.find((result) => result.type === 'chat').actual, ['HEADLESS_PASS']);
	assert.ok(report.evidence.paths.protocol);
	assert.deepEqual(report.timings.health, [{ operation: 'decide', count: 2, p50Ms: 321, p95Ms: 654, failureRate: 0, circuit: 'closed' }]);
	assert.deepEqual(report.timings.control, [{ operation: 'observation_to_plan', count: 1, p50Ms: 700, p95Ms: 700 }]);
});

test('summons, starts, and polls an eight-agent exact-profile roster concurrently with isolated evidence', async () => {
	const commands = [];
	const factualCommands = [];
	const names = [];
	const activeByPhase = new Map();
	const maxActiveByPhase = new Map();
	const pendingByPhase = new Map();
	const phaseBarrier = async (phase, expected) => {
		const active = (activeByPhase.get(phase) ?? 0) + 1;
		activeByPhase.set(phase, active);
		maxActiveByPhase.set(phase, Math.max(maxActiveByPhase.get(phase) ?? 0, active));
		if (!pendingByPhase.has(phase)) pendingByPhase.set(phase, []);
		await new Promise((resolve) => {
			pendingByPhase.get(phase).push(resolve);
			if (pendingByPhase.get(phase).length === expected) {
				for (const release of pendingByPhase.get(phase)) release();
			}
		});
		activeByPhase.set(phase, activeByPhase.get(phase) - 1);
	};
	const rcon = {
		command: async (command) => {
			commands.push(command);
			if (command.includes('summon-configured')) {
				const name = command.split(' ').at(-1);
				names.push(name);
				await phaseBarrier('summon', 8);
				return { text: `Created ${name}. It is ready for a task.` };
			}
			if (command.startsWith('codex start ')) { await phaseBarrier('start', 8); return { text: 'started' }; }
			if (command.startsWith('codex status ')) { await phaseBarrier('status', 8); return { text: 'state=COMPLETED' }; }
			if (command.startsWith('data get entity ')) {
				assert.doesNotMatch(command, /\{agent\}/);
				factualCommands.push(command);
				return { text: 'minecraft:wooden_pickaxe' };
			}
			return { text: 'ok' };
		},
		close: async () => {},
	};
	const report = await runHeadlessScenario({
		scenario: scenario({ rosterSize: 8, assert: [
			{ type: 'lifecycle', state: 'COMPLETED' },
			{ type: 'chat', message: 'HEADLESS_PASS' },
			{ type: 'rcon', command: 'data get entity {agent} Inventory', match: 'minecraft:wooden_pickaxe' },
		] }),
		runDirectory: 'C:/runs/eight-agents', rcon, now: () => 100,
		readFile: async (file) => {
			if (String(file).endsWith('protocol.jsonl')) return jsonl(names.map((name, index) => ({
				direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId: `agent-id-${index + 1}`, payload: {
					agentId: `agent-id-${index + 1}`, name, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
				} },
			})));
			if (String(file).endsWith('coordinator.jsonl')) return jsonl(names.map((_name, index) => ({ agentId: `agent-id-${index + 1}`, event: 'chat', message: 'HEADLESS_PASS' })));
			return '';
		},
		writeFile: async () => {}, poll: async () => {},
	});

	assert.equal(report.status, 'PASSED');
	assert.equal(report.rosterSize, 8);
	assert.equal(report.agents.length, 8);
	assert.equal(report.factualSuccess, true);
	assert.equal(factualCommands.length, 8);
	assert.equal(new Set(factualCommands).size, 8);
	assert.ok(factualCommands.every((command) => names.some((name) => command.includes(name))));
	assert.deepEqual(report.agents.map((agent) => agent.agentId), Array.from({ length: 8 }, (_value, index) => `agent-id-${index + 1}`));
	assert.ok(report.agents.every((agent) => agent.assertions.every((assertion) => assertion.passed)));
	assert.equal(maxActiveByPhase.get('summon'), 8);
	assert.equal(maxActiveByPhase.get('start'), 8);
	assert.equal(maxActiveByPhase.get('status'), 8);
	const summons = commands.filter((command) => command.includes('summon-configured'));
	const removals = commands.filter((command) => command.startsWith('codex remove '));
	assert.equal(summons.length, 8);
	assert.equal(removals.length, 8);
	assert.deepEqual(new Set(removals.map((command) => command.split(' ').at(-1))), new Set(names));
	assert.ok(summons.every((command) => command.includes(' codex gpt-5.6-sol high priority survival ')));
	assert.equal(new Set(summons.map((command) => command.match(/positioned ([^ ]+ [^ ]+ [^ ]+)/)?.[1])).size, 8);
});

test('does not let one agent satisfy another agent evidence assertion', async () => {
	const names = [];
	const report = await runHeadlessScenario({
		scenario: scenario({ rosterSize: 8, timeoutMs: 1, assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'chat', message: 'ONLY_ONE' }] }),
		runDirectory: 'C:/runs/evidence-isolation',
		rcon: {
			command: async (command) => {
				if (command.includes('summon-configured')) { const name = command.split(' ').at(-1); names.push(name); return { text: `Created ${name}. It is ready for a task.` }; }
				return { text: command.startsWith('codex status ') ? 'state=COMPLETED' : 'ok' };
			},
			close: async () => {},
		},
		now: () => 10,
		readFile: async (file) => {
			if (String(file).endsWith('protocol.jsonl')) return jsonl(names.map((name, index) => ({
				direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId: `isolated-${index + 1}`, payload: {
					agentId: `isolated-${index + 1}`, name, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
				} },
			})));
			if (String(file).endsWith('coordinator.jsonl') && names.length > 0) return jsonl([{ agentId: 'isolated-1', event: 'chat', message: 'ONLY_ONE' }]);
			return '';
		},
		writeFile: async () => {}, poll: async () => {},
	});
	assert.equal(report.classification, 'ASSERTION_MISMATCH');
	assert.equal(report.agents.filter((agent) => agent.assertions.every((assertion) => assertion.passed)).length, 1);
});

test('fails only the roster member whose authoritative identity or status command fails', async () => {
	const names = [];
	const started = [];
	const report = await runHeadlessScenario({
		scenario: scenario({ rosterSize: 8, timeoutMs: 1, assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }),
		runDirectory: 'C:/runs/partial-roster-failure',
		rcon: {
			command: async (command) => {
				if (command.includes('summon-configured')) { const name = command.split(' ').at(-1); names.push(name); return { text: `Created ${name}. It is ready for a task.` }; }
				if (command.startsWith('codex start ')) { started.push(command.split(' ')[2]); return { text: 'started' }; }
				if (command.startsWith('codex status ') && command.endsWith(names[3])) throw new Error('one status read failed');
				return { text: command.startsWith('codex status ') ? 'state=COMPLETED' : 'ok' };
			},
			close: async () => {},
		},
		now: () => 10,
		readFile: async (file) => String(file).endsWith('protocol.jsonl') ? jsonl(names.slice(0, 7).map((name, index) => ({
			direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId: `partial-${index + 1}`, payload: {
				agentId: `partial-${index + 1}`, name, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
			} },
		}))) : '',
		writeFile: async () => {}, poll: async () => {},
	});
	assert.equal(report.status, 'FAILED');
	assert.equal(report.agents.length, 8);
	assert.equal(report.agents.filter((agent) => agent.classification === 'ERROR').length, 2);
	assert.equal(report.agents.filter((agent) => agent.lifecycle === 'COMPLETED').length, 6);
	assert.equal(started.length, 7, 'the member without an authoritative ID is not started');
});

test('keeps a sixteen-agent report bounded with one isolated lifecycle per authoritative ID', async () => {
	const names = [];
	const report = await runHeadlessScenario({
		scenario: scenario({ rosterSize: 16, assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }),
		runDirectory: 'C:/runs/sixteen-agents',
		rcon: {
			command: async (command) => {
				if (command.includes('summon-configured')) { const name = command.split(' ').at(-1); names.push(name); return { text: `Created ${name}. It is ready for a task.` }; }
				return { text: command.startsWith('codex status ') ? 'state=COMPLETED' : 'ok' };
			},
			close: async () => {},
		},
		now: () => 100,
		readFile: async (file) => String(file).endsWith('protocol.jsonl') ? jsonl(names.map((name, index) => ({
			direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId: `sixteen-${index + 1}`, payload: {
				agentId: `sixteen-${index + 1}`, name, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
			} },
		}))) : '',
		writeFile: async () => {}, poll: async () => {},
	});
	assert.equal(report.status, 'PASSED');
	assert.equal(report.agents.length, 16);
	assert.ok(report.agents.every((agent) => agent.agentId?.startsWith('sixteen-') && agent.lifecycle === 'COMPLETED'));
	assert.ok(Buffer.byteLength(JSON.stringify(report), 'utf8') < 262_144);
});

test('reports bounded p50 p95 p99 metrics and null provider-native token categories', async () => {
	const agentId = GENERATED_NAME_AT_100;
	const providerRows = [
		{ agentId, provider: 'codex', model: 'gpt-5.6-sol', retry: false, outcome: 'success', timing: { queueWaitMs: 1, durationMs: 10, apiDurationMs: 8 }, tokens: { input: 10, output: 2, reasoning: 1, cached: 3, cacheWrite: null } },
		{ agentId, provider: 'codex', model: 'gpt-5.6-sol', retry: true, outcome: 'error', error: { code: 'RATE_LIMITED', message: 'bounded' }, rateLimited: true, compaction: true, timing: { queueWaitMs: 9, durationMs: 90, apiDurationMs: 80 }, tokens: { input: 20, output: 4, reasoning: 2, cached: 6, cacheWrite: null } },
	];
	const protocolRows = [{ direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId, payload: {
		agentId, name: agentId, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
	} } }, ...[1, 5, 9].flatMap((value, index) => [
		{ timestamp: index * 100, direction: 'server_to_coordinator', envelope: { type: 'observation', agentId, payload: { metrics: { collectionMs: value } } } },
		{ timestamp: index * 100 + 20, direction: 'server_to_coordinator', envelope: { type: 'action_result', agentId, payload: {} } },
		{ timestamp: index * 100 + 20 + value, direction: 'server_to_coordinator', envelope: { type: 'observation', agentId, payload: {} } },
	])];
	const files = new Map([
		['provider.jsonl', jsonl(providerRows)],
		['protocol.jsonl', jsonl(protocolRows)],
	]);
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }), runDirectory: 'C:/runs/metrics',
		rcon: { command: async (command) => ({ text: command.includes('summon-configured') ? 'Created agent. It is ready for a task.' : command.startsWith('codex status') ? 'state=COMPLETED' : command === 'tick query' ? 'The server averages 4.25 ms per tick' : 'ok' }), close: async () => {} },
		now: () => 100, providerTurnsPath: 'C:/provider.jsonl', protocolAudit: 'C:/protocol.jsonl',
		readFile: async (file) => files.get(String(file).replace('C:/', '')) ?? '', writeFile: async () => {}, poll: async () => {},
	});
	assert.deepEqual(report.metrics.latencyMs.queue, { count: 2, p50: 1, p95: 9, p99: 9 });
	assert.deepEqual(report.metrics.latencyMs.inference, { count: 2, p50: 8, p95: 80, p99: 80 });
	assert.deepEqual(report.metrics.latencyMs.observation, { count: 3, p50: 5, p95: 9, p99: 9 });
	assert.deepEqual(report.metrics.latencyMs.result, { count: 3, p50: 5, p95: 9, p99: 9 });
	assert.deepEqual(report.metrics.tokens, { input: 30, output: 6, reasoning: 3, cached: 9, cacheWrite: null });
	assert.equal(report.metrics.retries, 1);
	assert.equal(report.metrics.rateLimits, 1);
	assert.equal(report.metrics.compactions, 1);
	assert.deepEqual(report.metrics.resources, { processCount: null, peakRssBytes: null, minecraftMspt: 4.25 });
	assert.ok(Buffer.byteLength(JSON.stringify(report.metrics), 'utf8') < 16_384);
});

test('accepts the production summon response and binds metrics to the authoritative registration ID', async () => {
	const generatedName = GENERATED_NAME_AT_100;
	const files = new Map([
		['protocol.jsonl', jsonl([{ direction: 'server_to_coordinator', envelope: { type: 'agent_registered', agentId: 'authoritative-single', payload: {
			agentId: 'authoritative-single', name: generatedName, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
		} } }])],
		['provider.jsonl', jsonl([
			{ agentId: 'unrelated', provider: 'codex', model: 'gpt-5.6-sol', timing: { durationMs: 900, apiDurationMs: 900 } },
			{ agentId: 'authoritative-single', provider: 'codex', model: 'gpt-5.6-sol', timing: { durationMs: 12, apiDurationMs: 10 } },
		])],
	]);
	let statusReads = 0;
	const commands = [];
	const report = await runHeadlessScenario({
		scenario: scenario({ setupBlocks: [{ x: 2, y: 201, z: 0, blockId: 'minecraft:oak_log' }], assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }), runDirectory: 'C:/runs/single-authoritative',
		rcon: { command: async (command) => { commands.push(command); return { text: command.includes('summon-configured') ? `Creating ${generatedName}. It will be ready when its player joins.` : command.startsWith('codex status') ? statusReads++ === 0 ? `${generatedName} | Ready. Current task: none. Queued tasks: 0.` : 'state=COMPLETED' : 'ok' }; }, close: async () => {} },
		now: () => 100, providerTurnsPath: 'C:/provider.jsonl', protocolAudit: 'C:/protocol.jsonl',
		readFile: async (file) => files.get(String(file).replace('C:/', '')) ?? '', writeFile: async () => {}, poll: async () => {},
	});
	assert.deepEqual(report.metrics.latencyMs.inference, { count: 1, p50: 10, p95: 10, p99: 10 });
	assert.ok(commands.indexOf('execute in minecraft:overworld run setblock 2 201 0 minecraft:oak_log') < commands.findIndex((command) => command.includes('summon-configured')));
});

test('evaluates single-agent assertions only after exact authoritative identity isolation', async () => {
	const generatedName = GENERATED_NAME_AT_100;
	const files = new Map([
		['protocol.jsonl', jsonl([
			{ direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId: 'authoritative', payload: { agentId: 'authoritative', name: generatedName, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' } } },
			{ direction: 'server_to_coordinator', envelope: { type: 'chat', agentId: 'unrelated', payload: { agentId: 'unrelated', message: 'UNRELATED_ONLY' } } },
		])],
		['coordinator.jsonl', jsonl([{ agentId: 'unrelated', event: 'chat', message: 'UNRELATED_ONLY' }])],
		['server.log', 'unrelated chat: UNRELATED_ONLY\n'],
	]);
	const report = await runHeadlessScenario({
		scenario: scenario({ timeoutMs: 1, assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'chat', message: 'UNRELATED_ONLY' }] }),
		runDirectory: 'C:/runs/single-assertion-isolation',
		rcon: { command: async (command) => ({ text: command.includes('summon-configured') ? `Created ${generatedName}. It is ready for a task.` : command.startsWith('codex status') ? 'state=COMPLETED' : 'ok' }), close: async () => {} },
		now: () => 100, protocolAudit: 'C:/protocol.jsonl',
		readFile: async (file) => files.get(String(file).replace('C:/', '')) ?? '', writeFile: async () => {}, poll: async () => {},
	});
	assert.equal(report.classification, 'ASSERTION_MISMATCH');
	assert.deepEqual(report.assertions.find((entry) => entry.type === 'chat').actual, []);
	assert.doesNotMatch(JSON.stringify(report.evidence), /UNRELATED_ONLY/);
});

test('aggregates concurrent metrics from exact member IDs and preserves per-agent metrics', async () => {
	const names = [];
	const providerRows = Array.from({ length: 8 }, (_value, index) => ({
		agentId: `metric-agent-${index + 1}`, provider: 'codex', model: 'gpt-5.6-sol', retry: index === 1,
		rateLimited: index === 2, compaction: index === 3,
		timing: { queueWaitMs: index + 1, durationMs: 20 + index, apiDurationMs: 10 + index },
		tokens: { input: index + 1, output: 1, reasoning: 0, cached: 0, cacheWrite: 0 },
	}));
	const report = await runHeadlessScenario({
		scenario: scenario({ rosterSize: 8, assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }), runDirectory: 'C:/runs/concurrent-metrics',
		rcon: { command: async (command) => {
			if (command.includes('summon-configured')) { const name = command.split(' ').at(-1); names.push(name); return { text: `Created ${name}. It is ready for a task.` }; }
			return { text: command.startsWith('codex status') ? 'state=COMPLETED' : 'ok' };
		}, close: async () => {} },
		now: () => 100, providerTurnsPath: 'C:/provider.jsonl', protocolAudit: 'C:/protocol.jsonl',
		readFile: async (file) => {
			if (String(file).endsWith('provider.jsonl')) return jsonl(providerRows);
			if (String(file).endsWith('protocol.jsonl')) return jsonl(names.map((name, index) => ({ direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId: `metric-agent-${index + 1}`, payload: { agentId: `metric-agent-${index + 1}`, name, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' } } })));
			return '';
		}, writeFile: async () => {}, poll: async () => {},
	});
	assert.deepEqual(report.metrics.latencyMs.queue, { count: 8, p50: 4, p95: 8, p99: 8 });
	assert.deepEqual(report.metrics.tokens, { input: 36, output: 8, reasoning: 0, cached: 0, cacheWrite: 0 });
	assert.deepEqual({ retries: report.metrics.retries, rateLimits: report.metrics.rateLimits, compactions: report.metrics.compactions }, { retries: 1, rateLimits: 1, compactions: 1 });
	assert.ok(report.agents.every((agent) => agent.metrics.latencyMs.queue.count === 1 && agent.metrics.tokens.output === 1));
});

test('provider turn summaries expose only allowlisted structured errors', async () => {
	const generatedName = GENERATED_NAME_AT_100;
	const secret = 'ARBITRARY_MODEL_SECRET_TEXT';
	const files = new Map([
		['protocol.jsonl', jsonl([{ direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId: 'summary-agent', payload: { agentId: 'summary-agent', name: generatedName, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' } } }])],
		['provider.jsonl', jsonl([{ agentId: 'summary-agent', provider: 'codex', model: 'gpt-5.6-sol', outcome: 'error', error: { code: 'MALFORMED_DECISION', category: 'decision_parse', message: secret, data: { prompt: secret } }, timing: { durationMs: 5, apiDurationMs: 4 } }])],
	]);
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }), runDirectory: 'C:/runs/summary-error',
		rcon: { command: async (command) => ({ text: command.includes('summon-configured') ? `Created ${generatedName}. It is ready for a task.` : command.startsWith('codex status') ? 'state=COMPLETED' : 'ok' }), close: async () => {} },
		now: () => 100, providerTurnsPath: 'C:/provider.jsonl', protocolAudit: 'C:/protocol.jsonl', readFile: async (file) => files.get(String(file).replace('C:/', '')) ?? '', writeFile: async () => {}, poll: async () => {},
	});
	assert.doesNotMatch(JSON.stringify(report), new RegExp(secret));
	assert.deepEqual(report.timings.turns[0].error, { code: 'MALFORMED_DECISION', category: 'decision_parse' });
});

test('fails closed when single-agent authoritative snapshot identity is missing', async () => {
	const generatedName = GENERATED_NAME_AT_100;
	const files = new Map([
		['protocol.jsonl', jsonl([{ direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId: 'unrelated', payload: {
			agentId: 'unrelated', name: 'someone-else', provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
		} } }])],
		['provider.jsonl', jsonl([{ agentId: 'unrelated', provider: 'codex', model: 'gpt-5.6-sol', timing: { durationMs: 900, apiDurationMs: 900 } }])],
	]);
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }), runDirectory: 'C:/runs/single-missing',
		rcon: { command: async (command) => ({ text: command.includes('summon-configured') ? `Created ${generatedName}. It is ready for a task.` : command.startsWith('codex status') ? 'state=COMPLETED' : 'ok' }), close: async () => {} },
		now: () => 100, providerTurnsPath: 'C:/provider.jsonl', protocolAudit: 'C:/protocol.jsonl',
		readFile: async (file) => files.get(String(file).replace('C:/', '')) ?? '', writeFile: async () => {}, poll: async () => {},
	});
	assert.equal(report.status, 'FAILED');
	assert.match(report.diagnostics, /authoritative.*unavailable|snapshot.*identity/i);
	assert.equal(report.metrics.latencyMs.inference.count, 0, 'labelled unrelated turns are never legacy evidence');
});

test('fails closed when single-agent authoritative snapshot identity is ambiguous', async () => {
	const generatedName = GENERATED_NAME_AT_100;
	const snapshots = ['first-id', 'second-id'].map((agentId) => ({ direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId, payload: {
		agentId, name: generatedName, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority',
	} } }));
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }), runDirectory: 'C:/runs/single-ambiguous',
		rcon: { command: async (command) => ({ text: command.includes('summon-configured') ? `Created ${generatedName}. It is ready for a task.` : command.startsWith('codex status') ? 'state=COMPLETED' : 'ok' }), close: async () => {} },
		now: () => 100, protocolAudit: 'C:/protocol.jsonl', readFile: async (file) => String(file).endsWith('protocol.jsonl') ? jsonl(snapshots) : '', writeFile: async () => {}, poll: async () => {},
	});
	assert.equal(report.status, 'FAILED');
	assert.match(report.diagnostics, /ambiguous/i);
});

test('redacts arbitrary task text and secrets from public command records', async () => {
	const secretTask = 'Find obsidian with phrase ULTRA_PRIVATE_PROMPT and api_key=sk-arbitrary-secret';
	const report = await runHeadlessScenario({
		scenario: scenario({ task: secretTask, assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }), runDirectory: 'C:/runs/public-command-redaction',
		rcon: { command: async (command) => ({ text: command.includes('summon-configured') ? 'Created agent. It is ready for a task.' : command.startsWith('codex status') ? 'state=COMPLETED' : 'ok' }), close: async () => {} },
		now: () => 100, readFile: async () => '', writeFile: async () => {}, poll: async () => {},
	});
	const publicText = JSON.stringify(report);
	assert.equal(publicText.includes('ULTRA_PRIVATE_PROMPT'), false);
	assert.equal(publicText.includes('sk-arbitrary-secret'), false);
	assert.ok(report.commands.some((entry) => entry.operation === 'agent_start'));
});

test('does not pass a roster scenario when any requested profile member is skipped', async () => {
	const names = [];
	const report = await runHeadlessScenario({
		scenario: scenario({ rosterSize: 8, assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }), runDirectory: 'C:/runs/partial-profile-skip',
		rcon: { command: async (command) => {
			if (command.includes('summon-configured')) { const name = command.split(' ').at(-1); names.push(name); return { text: names.length === 8 ? 'profile unavailable' : `Created ${name}. It is ready for a task.` }; }
			return { text: command.startsWith('codex status') ? 'state=COMPLETED' : 'ok' };
		}, close: async () => {} },
		now: () => 100, readFile: async (file) => String(file).endsWith('protocol.jsonl') ? jsonl(names.slice(0, 7).map((name, index) => ({
			direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId: `launched-${index}`, payload: { agentId: `launched-${index}`, name, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' } },
		}))) : '', writeFile: async () => {}, poll: async () => {},
	});
	assert.equal(report.status, 'FAILED');
	const skipped = report.agents.find((agent) => agent.classification === 'SKIPPED_PROFILE');
	assert.equal(skipped.status, 'SKIPPED');
	assert.match(skipped.diagnostics, /unavailable/i);
});

test('isolates early agent evidence before applying row bounds across more than 300 rows', async () => {
	const names = [];
	const report = await runHeadlessScenario({
		scenario: scenario({ rosterSize: 16, assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'chat', message: 'EARLY_EVIDENCE' }] }), runDirectory: 'C:/runs/many-evidence-rows',
		rcon: { command: async (command) => {
			if (command.includes('summon-configured')) { const name = command.split(' ').at(-1); names.push(name); return { text: `Created ${name}. It is ready for a task.` }; }
			return { text: command.startsWith('codex status') ? 'state=COMPLETED' : 'ok' };
		}, close: async () => {} },
		now: () => 100, readFile: async (file) => {
			if (!String(file).endsWith('protocol.jsonl')) return '';
			return jsonl(names.flatMap((name, index) => [
				{ direction: 'server_to_coordinator', envelope: { type: 'agent_snapshot', agentId: `many-${index}`, payload: { agentId: `many-${index}`, name, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' } } },
				{ direction: 'server_to_coordinator', envelope: { type: 'chat', agentId: `many-${index}`, payload: { agentId: `many-${index}`, message: 'EARLY_EVIDENCE' } } },
				...Array.from({ length: 18 }, (_value, row) => ({ direction: 'server_to_coordinator', envelope: { type: 'observation', agentId: `many-${index}`, payload: { agentId: `many-${index}`, eventSequence: row } } })),
			]));
		}, writeFile: async () => {}, poll: async () => {},
	});
	assert.equal(report.status, 'PASSED');
	assert.ok(report.agents.every((agent) => agent.assertions.every((assertion) => assertion.passed)));
});

test('retains authoritative action and chat evidence ahead of hundreds of later observations', async () => {
	const generatedName = GENERATED_NAME_AT_100;
	const agentId = 'durable-evidence-agent';
	const protocol = jsonl([
		{ envelope: { type: 'agent_registered', agentId, payload: { agentId, name: generatedName, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' } } },
		{ envelope: { type: 'action_command', agentId, payload: { actionId: 'chat-1', actionType: 'chat', arguments: { message: 'HEADLESS_PASS' } } } },
		{ envelope: { type: 'action_result', agentId, payload: { actionId: 'chat-1', actionType: 'chat', state: 'SUCCEEDED' } } },
		{ envelope: { type: 'action_command', agentId, payload: { actionId: 'move-1', actionType: 'move', arguments: { x: 1 } } } },
		{ envelope: { type: 'action_result', agentId, payload: { actionId: 'move-1', actionType: 'move', state: 'SUCCEEDED' } } },
		...Array.from({ length: 400 }, (_value, eventSequence) => ({ envelope: { type: 'observation', agentId, payload: { eventSequence } } })),
	]);
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [
			{ type: 'lifecycle', state: 'COMPLETED' },
			{ type: 'chat', message: 'HEADLESS_PASS' },
			{ type: 'action', actionType: 'move', args: { x: 1 }, resultState: 'SUCCEEDED' },
		] }), runDirectory: 'C:/runs/durable-evidence', now: () => 100,
		rcon: { command: async (command) => ({ text: command.includes('summon-configured') ? `Created ${generatedName}. It is ready for a task.` : command.startsWith('codex status') ? 'state=COMPLETED' : 'ok' }), close: async () => {} },
		readFile: async (file) => String(file).endsWith('protocol.jsonl') ? protocol : '', writeFile: async () => {}, poll: async () => {},
	});
	assert.equal(report.status, 'PASSED');
	assert.ok(report.assertions.every((assertion) => assertion.passed));
});

test('releases the temporary spawn chunk when summon fails', async () => {
	const commands = [];
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }),
		runDirectory: 'C:/runs/summon-failure',
		rcon: {
			command: async (command) => {
				commands.push(command);
				return { text: command.includes('summon-configured') ? 'ERROR: summon failed' : 'ok' };
			},
			close: async () => {},
		},
		now: () => 1,
		readFile: async () => '',
		poll: async () => {},
		writeFile: async () => {},
	});
	assert.equal(report.classification, 'ERROR');
	assert.deepEqual(commands.slice(0, 5), [
		'execute in minecraft:overworld run forceload add 0 0',
		'execute in minecraft:overworld run fill -8 200 -8 8 200 8 minecraft:stone',
		'execute in minecraft:overworld run fill -8 201 -8 8 204 8 minecraft:air',
		commands[3],
		'execute in minecraft:overworld run forceload remove 0 0',
	]);
	assert.match(commands[3], /summon-configured/);
	assert.equal(commands.some((command) => command.startsWith('codex start ')), false);
});

test('bounds generated agent selectors to the Java profile limit', async () => {
	const commands = [];
	const report = await runHeadlessScenario({
		scenario: scenario({ id: 'this-is-an-intentionally-very-long-headless-scenario-identifier', assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }),
		runDirectory: 'C:/runs/bounded-name', now: () => 1,
		rcon: {
			command: async (command) => {
				commands.push(command);
				if (command.includes('summon-configured')) return { text: 'Created bounded agent. It is ready for a task.' };
				return { text: command.startsWith('codex status') ? 'state=COMPLETED' : 'ok' };
			},
			close: async () => {},
		},
		readFile: async () => '', writeFile: async () => {}, poll: async () => {},
	});
	const summon = commands.find((command) => command.includes('summon-configured'));
	const generatedName = summon.split(' ').at(-1);
	assert.ok(generatedName.length <= 32, generatedName);
	assert.equal(report.status, 'PASSED');
});

test('fails immediately when summon lacks the exact Java success response', async () => {
	const commands = [];
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }), runDirectory: 'C:/runs/summon-validation',
		rcon: { command: async (command) => { commands.push(command); return { text: command.includes('summon-configured') ? 'name must be at most 32 characters' : 'ok' }; }, close: async () => {} },
		readFile: async () => '', writeFile: async () => {}, poll: async () => {},
	});
	assert.equal(report.classification, 'ERROR');
	assert.equal(commands.some((command) => command.startsWith('codex start')), false);
});

test('parses the exact Java codex status lifecycle strings', async () => {
	for (const [statusText, expected] of [
		['runner | Task complete. Goal finished.', 'PASSED'],
		['runner | Needs attention. Provider failed.', 'ERROR'],
		['runner | Dead - awaiting model.', 'DEAD'],
	]) {
		const commands = [];
		const report = await runHeadlessScenario({
			scenario: scenario({ assert: [{ type: 'lifecycle', state: expected === 'PASSED' ? 'COMPLETED' : expected }] }),
			runDirectory: 'C:/runs/status-shapes',
			rcon: {
				command: async (command) => { commands.push(command); return { text: command.includes('summon-configured') ? 'Created test agent. It is ready for a task.' : command.startsWith('codex status') ? statusText : 'ok' }; },
				close: async () => {},
			},
			now: () => 1,
			readFile: async () => '', poll: async () => {}, writeFile: async () => {},
		});
		assert.equal(report.classification, expected);
		assert.equal(commands.filter((command) => command.includes('codex status')).length, 1);
	}
});

test('polls beyond the old 256-attempt cap until a long-deadline terminal state', async () => {
	let clock = 0;
	let statusReads = 0;
	const report = await runHeadlessScenario({
		scenario: scenario({ timeoutMs: 20_000, assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }),
		runDirectory: 'C:/runs/long-poll',
		rcon: {
		command: async (command) => ({ text: command.includes('summon-configured') ? 'Created test agent. It is ready for a task.' : command.startsWith('codex status') ? (++statusReads > 300 ? 'runner | Task complete. Goal finished.' : 'runner | Working.') : 'ok' }),
		close: async () => {},
	},
	now: () => clock,
	readFile: async () => '',
	poll: async ({ phase }) => { if (phase === 'status') clock += 50; },
	writeFile: async () => {},
	});
	assert.equal(report.status, 'PASSED');
	assert.ok(statusReads > 256);
});

test('continues evidence polling for late markers and reads bounded tails', async () => {
	let evidenceReady = false;
	const padded = 'x'.repeat(20_000) + 'agent chat: LATE_PASS\n';
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'chat', message: 'LATE_PASS' }] }),
		runDirectory: 'C:/runs/late-evidence',
		rcon: {
		command: async (command) => ({ text: command.includes('summon-configured') ? 'Created test agent. It is ready for a task.' : command.startsWith('codex status') ? 'runner | Task complete. Goal finished.' : 'ok' }),
		close: async () => {},
	},
	now: () => 1,
	readFile: async () => evidenceReady ? 'agent chat: LATE_PASS\n' : '',
		readTail: async () => evidenceReady ? padded : '',
	poll: async ({ phase }) => { if (phase === 'evidence') evidenceReady = true; },
	writeFile: async () => {},
	});
	assert.equal(report.status, 'PASSED');
});

test('redacts secret-bearing diagnostics and RCON evidence from serialized reports', async () => {
	let writes = [];
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'rcon', command: 'list', match: 'missing' }] }),
		runDirectory: 'C:/runs/redaction',
		rcon: {
		command: async (command) => command === 'list' ? { text: '{"password":"shh-secret", "token":"tok-secret"}' } : { text: command.includes('summon-configured') ? 'Created test agent. It is ready for a task.' : command.startsWith('codex status') ? 'runner | Task complete. Goal finished.' : 'ok' },
		close: async () => {},
	},
	 now: () => 1, readFile: async () => '', poll: async () => {},
	writeFile: async (_file, content) => { writes.push(content); },
	});
	assert.equal(report.classification, 'ASSERTION_MISMATCH');
	assert.equal(writes.length, 1);
	assert.doesNotMatch(writes[0], /shh-secret|tok-secret/);
});

test('rejects mutation-capable RCON assertion commands using a conservative allowlist', async () => {
	for (const unsafe of ['scoreboard players set @s x 1', '/give @s diamond', 'weather thunder', 'time set day', 'gamemode creative', 'function foo', 'item replace entity @s weapon.mainhand stone', 'tag @s add admin', 'execute as @s run give @s diamond']) {
		let forwarded = false;
		const report = await runHeadlessScenario({
			scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }, { type: 'rcon', command: unsafe, match: 'never' }] }),
			runDirectory: 'C:/runs/rcon-deny',
			rcon: { command: async (command) => { forwarded = forwarded || command === unsafe; return { text: command.includes('summon-configured') ? 'Created test agent. It is ready for a task.' : command.startsWith('codex status') ? 'runner | Task complete. Goal finished.' : 'ok' }; }, close: async () => {} },
			now: () => 1, readFile: async () => '', poll: async () => {}, writeFile: async () => {},
		});
		assert.equal(forwarded, false);
		assert.equal(report.classification, 'ERROR');
	}
});

test('allows a bounded block predicate to guard a read-only entity query', async () => {
	let forwarded = false;
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [
			{ type: 'lifecycle', state: 'COMPLETED' },
			{ type: 'rcon', command: 'execute if block 2 201 0 minecraft:air run data get entity {agent} Pos', match: 'entity data' },
		] }),
		runDirectory: 'C:/runs/rcon-block-predicate',
		rcon: { command: async (command) => {
			if (command.startsWith('execute if block')) { forwarded = true; return { text: 'test_agent has the following entity data' }; }
			return { text: command.includes('summon-configured') ? 'Created test_agent. It is ready for a task.' : command.startsWith('codex status') ? 'runner | Task complete. Goal finished.' : 'ok' };
		}, close: async () => {} },
		now: () => 1, readFile: async () => '', poll: async () => {}, writeFile: async () => {},
	});
	assert.equal(forwarded, true);
	assert.equal(report.status, 'PASSED');
});

test('returns cleanup failure even when cleanup report writing also fails', async () => {
	const report = await runHeadlessScenario({
		scenario: scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }] }),
		runDirectory: 'C:/runs/cleanup-write',
		rcon: { command: async (command) => ({ text: command.includes('summon-configured') ? 'Created test agent. It is ready for a task.' : command.startsWith('codex status') ? 'runner | Task complete. Goal finished.' : 'ok' }), close: async () => { throw new Error('port still open password=secret'); } },
		now: () => 1, readFile: async () => '', poll: async () => {}, writeFile: async () => { throw new Error('disk unavailable token=secret'); },
	});
	assert.equal(report.classification, 'CLEANUP_FAILURE');
	assert.equal(report.status, 'FAILED');
});

test('evaluates exact chat, action arguments, program, lifecycle, and read-only RCON assertions', () => {
	const result = evaluateHeadlessAssertions([
		{ type: 'lifecycle', state: 'COMPLETED' },
		{ type: 'chat', message: 'hello' },
		{ type: 'action', actionType: 'place_block', args: { x: 2, face: 'up' }, resultState: 'SUCCEEDED' },
		{ type: 'program', event: 'program_finished', status: 'COMPLETED' },
		{ type: 'rcon', command: 'list', match: 'There are 1' },
	], {
		lifecycle: 'COMPLETED', chats: ['hello'],
		actions: [{ actionType: 'place_block', arguments: { x: 2, face: 'up', extra: true }, result: { state: 'SUCCEEDED' } }],
		program: [{ event: 'program_finished', status: 'COMPLETED' }],
		rcon: [{ command: 'list', text: 'There are 1 of a max of 20 players online' }],
	});
	assert.equal(result.passed, true);
	assert.equal(result.results.length, 5);
});

test('classifies timeout, terminal ERROR/DEAD, skipped profiles, assertion mismatch, and cleanup failure', async (t) => {
	const make = async (statusText, overrides = {}) => {
		let clock = 0;
		const rcon = {
			command: async (command) => command.includes('codex summon-configured') ? { text: overrides.summonText ?? 'Created test agent. It is ready for a task.' } : command.startsWith('codex start') ? { text: 'started' } : { text: statusText },
			close: overrides.close ?? (async () => {}),
		};
		const selectedScenario = overrides.scenario ? { ...scenario(), ...overrides.scenario } : scenario({ assert: [{ type: 'lifecycle', state: 'COMPLETED' }] });
		return runHeadlessScenario({
			runDirectory: 'C:/runs/classifications', rcon, now: () => clock++, readFile: async () => '', poll: async () => {},
			...overrides,
			scenario: selectedScenario,
		});
	};
	assert.equal((await make('still running', { now: () => 2_000 })).classification, 'TIMEOUT');
	assert.equal((await make('state=ERROR')).classification, 'ERROR');
	assert.equal((await make('state=DEAD')).classification, 'DEAD');
	assert.equal((await make('state=COMPLETED', { scenario: { ...scenario(), skip: true, skipReason: 'profile unavailable' } })).status, 'SKIPPED');
	const catalogRejected = await make('state=COMPLETED', { summonText: 'Coordinator catalog rejected codex/m/high (provider profiles: 0)' });
	assert.equal(catalogRejected.status, 'SKIPPED');
	assert.equal(catalogRejected.classification, 'SKIPPED_PROFILE');
	assert.equal((await make('state=COMPLETED', { scenario: { assert: [{ type: 'chat', message: 'missing' }] } })).classification, 'ASSERTION_MISMATCH');
	assert.equal((await make('state=COMPLETED', { close: async () => { throw new Error('port still open'); } })).classification, 'CLEANUP_FAILURE');
	await t.test('reports remain serializable', () => assert.doesNotThrow(() => JSON.stringify({ status: 'PASSED' })));
});

test('writes a bounded plain JSON report to the scenario directory', async () => {
	const writes = [];
	await writeHeadlessReport('C:/runs/write-case', { status: 'PASSED', diagnostics: 'x'.repeat(10000) }, async (file, content, options) => writes.push({ file, content, options }));
	assert.equal(writes.length, 1);
	assert.match(writes[0].file, /write-case[\\/]report\.json$/);
	assert.equal(writes[0].options.encoding, 'utf8');
	assert.ok(JSON.parse(writes[0].content).diagnostics.length <= 4096);
});
