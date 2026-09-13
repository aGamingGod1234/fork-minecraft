import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { AgentWorkspaceManager } from '../src/agent-workspace.mjs';
import { MinecraftAgentWorkspace } from '../src/minecraft-agent-workspace.mjs';

test('creates a stable provider-scoped directory for each agent', async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'arena-agent-workspaces-'));
	try {
		const manager = new AgentWorkspaceManager(root);
		const first = await manager.prepare('codex', 'agent-1');
		const second = await manager.prepare('codex', 'agent-2');
		const otherProvider = await manager.prepare('kimi', 'agent-1');

		assert.equal(first, path.join(root, 'codex', 'agent-1'));
		assert.notEqual(first, second);
		assert.notEqual(first, otherProvider);
		assert.equal((await stat(first)).isDirectory(), true);
		assert.equal(await manager.prepare('codex', 'agent-1'), first);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test('rejects traversal and unsafe workspace segments', async () => {
	const manager = new AgentWorkspaceManager(path.join(os.tmpdir(), 'arena-agent-workspaces-safe'));
	await assert.rejects(() => manager.prepare('../codex', 'agent-1'), /safe path segment/);
	await assert.rejects(() => manager.prepare('codex', '..'), /safe path segment/);
	await assert.rejects(() => manager.prepare('codex', 'agent/escape'), /safe path segment/);
});

test('refreshes one shared native Minecraft workspace from bundled templates', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-workspace-'));
	const templateRoot = path.join(root, 'templates');
	t.after(() => rm(root, { recursive: true, force: true }));
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# verified completion\n', 'utf8');
	await writeFile(
		path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'),
		'# native Minecraft control\n',
		'utf8',
	);

	const workspace = new MinecraftAgentWorkspace({
		root: path.join(root, 'runtime', 'minecraft-agent'),
		templateRoot,
	});
	const first = await workspace.prepare();
	assert.deepEqual(first, {
		cwd: path.join(root, 'runtime', 'minecraft-agent'),
		codexHome: path.join(root, 'runtime', 'minecraft-agent', '.codex-home'),
		instructions: '# verified completion\n',
		selectedCapabilityRoots: [{
			id: 'minecraft-control',
			location: {
				type: 'environment',
				environmentId: 'local',
				path: path.join(root, 'runtime', 'minecraft-agent', '.codex', 'skills', 'minecraft-control'),
			},
		}],
	});
	assert.equal(await readFile(path.join(first.cwd, 'AGENTS.md'), 'utf8'), '# verified completion\n');
	assert.equal(
		await readFile(path.join(first.cwd, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), 'utf8'),
		'# native Minecraft control\n',
	);

	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# refreshed verified completion\n', 'utf8');
	const second = await workspace.prepare();
	assert.equal(second.cwd, first.cwd);
	assert.deepEqual(second.selectedCapabilityRoots, first.selectedCapabilityRoots);
	assert.notEqual(second.instructions, first.instructions);
	assert.equal(await readFile(path.join(second.cwd, 'AGENTS.md'), 'utf8'), '# refreshed verified completion\n');
	assert.equal(second.instructions, '# refreshed verified completion\n');
});

test('does not rewrite unchanged shared Minecraft templates', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-workspace-cache-'));
	const templateRoot = path.join(root, 'templates');
	t.after(() => rm(root, { recursive: true, force: true }));
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# agents\n', 'utf8');
	await writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# skill\n', 'utf8');

	let writes = 0;
	const workspace = new MinecraftAgentWorkspace({ root: path.join(root, 'runtime'), templateRoot }, {
		sourceCodexHome: path.join(root, 'missing-user-codex-home'),
		fs: {
			async writeFile(...args) {
				writes += 1;
				return writeFile(...args);
			},
		},
	});
	await workspace.prepare();
	assert.equal(writes, 2);
	await workspace.prepare();
	assert.equal(writes, 2);

	await writeFile(path.join(workspace.root, 'AGENTS.md'), '# externally changed\n', 'utf8');
	await workspace.prepare();
	assert.equal(writes, 3);
	assert.equal(await readFile(path.join(workspace.root, 'AGENTS.md'), 'utf8'), '# agents\n');
});

test('synchronizes only auth into the isolated Codex home', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-codex-home-'));
	t.after(() => rm(root, { recursive: true, force: true }));
	const sourceCodexHome = path.join(root, 'user-codex-home');
	const templateRoot = path.join(root, 'templates');
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await mkdir(sourceCodexHome, { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# Minecraft instructions\n', 'utf8');
	await writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# Minecraft skill\n', 'utf8');
	await writeFile(path.join(sourceCodexHome, 'auth.json'), '{"tokens":"preserve"}\n', 'utf8');
	await writeFile(path.join(sourceCodexHome, 'AGENTS.md'), '# Lucas identity must not cross the boundary\n', 'utf8');
	await writeFile(path.join(sourceCodexHome, 'config.toml'), 'model = "user-model"\n', 'utf8');
	const staleCodexHome = path.join(root, 'runtime', 'minecraft-agent', '.codex-home');
	await mkdir(path.join(staleCodexHome, 'memories'), { recursive: true });
	await writeFile(path.join(staleCodexHome, 'AGENTS.md'), '# stale identity\n', 'utf8');
	await writeFile(path.join(staleCodexHome, 'config.toml'), 'model = "stale-model"\n', 'utf8');
	await writeFile(path.join(staleCodexHome, 'memories', 'old.md'), 'stale\n', 'utf8');

	const workspace = new MinecraftAgentWorkspace({
		root: path.join(root, 'runtime', 'minecraft-agent'),
		templateRoot,
	}, { sourceCodexHome });
	const prepared = await workspace.prepare();
	assert.equal(await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), '{"tokens":"preserve"}\n');
	await assert.rejects(() => readFile(path.join(prepared.codexHome, 'AGENTS.md'), 'utf8'), { code: 'ENOENT' });
	await assert.rejects(() => readFile(path.join(prepared.codexHome, 'config.toml'), 'utf8'), { code: 'ENOENT' });
	await assert.rejects(() => readFile(path.join(prepared.codexHome, 'memories', 'old.md'), 'utf8'), { code: 'ENOENT' });
	await writeFile(path.join(prepared.codexHome, 'state.sqlite'), 'runtime state\n', 'utf8');
	await workspace.prepare();
	assert.equal(await readFile(path.join(prepared.codexHome, 'state.sqlite'), 'utf8'), 'runtime state\n');
	assert.equal(await readFile(path.join(prepared.cwd, 'AGENTS.md'), 'utf8'), '# Minecraft instructions\n');
});

test('preserves auth when the configured source is the isolated Codex home', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-codex-same-home-'));
	t.after(() => rm(root, { recursive: true, force: true }));
	const workspaceRoot = path.join(root, 'runtime', 'minecraft-agent');
	const templateRoot = path.join(root, 'templates');
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# Minecraft instructions\n', 'utf8');
	await writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# Minecraft skill\n', 'utf8');
	await mkdir(path.join(workspaceRoot, '.codex-home'), { recursive: true });
	await writeFile(path.join(workspaceRoot, '.codex-home', 'auth.json'), '{"tokens":"keep"}\n', 'utf8');

	const workspace = new MinecraftAgentWorkspace({ root: workspaceRoot, templateRoot }, {
		sourceCodexHome: path.join(workspaceRoot, '.codex-home'),
	});
	const prepared = await workspace.prepare();
	assert.equal(await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), '{"tokens":"keep"}\n');
});

test('re-syncs Codex auth when isolated credentials are missing or source rotates', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-codex-auth-resync-'));
	t.after(() => rm(root, { recursive: true, force: true }));
	const sourceCodexHome = path.join(root, 'user-codex-home');
	const templateRoot = path.join(root, 'templates');
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await mkdir(sourceCodexHome, { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# Minecraft instructions\n', 'utf8');
	await writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# Minecraft skill\n', 'utf8');

	const workspace = new MinecraftAgentWorkspace({
		root: path.join(root, 'runtime', 'minecraft-agent'),
		templateRoot,
	}, { sourceCodexHome });
	const prepared = await workspace.prepare();
	await assert.rejects(() => readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), { code: 'ENOENT' });

	await writeFile(path.join(sourceCodexHome, 'auth.json'), '{"tokens":"first"}\n', 'utf8');
	await workspace.prepare();
	assert.equal(await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), '{"tokens":"first"}\n');

	await rm(path.join(prepared.codexHome, 'auth.json'));
	await workspace.prepare();
	assert.equal(await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), '{"tokens":"first"}\n');

	await writeFile(path.join(sourceCodexHome, 'auth.json'), '{"tokens":"rotated"}\n', 'utf8');
	await workspace.prepare();
	assert.equal(await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), '{"tokens":"rotated"}\n');

	await writeFile(path.join(prepared.codexHome, 'auth.json'), '{"tokens":"isolated-refresh"}\n', 'utf8');
	await writeFile(path.join(sourceCodexHome, 'auth.json'), '{"tokens":"source-again"}\n', 'utf8');
	await workspace.prepare();
	assert.equal(
		await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'),
		'{"tokens":"isolated-refresh"}\n',
	);
});

test('uses the platform Codex home for auth when CODEX_HOME is unset', async (t) => {
	const root = await mkdtemp(path.join(os.tmpdir(), 'minecraft-agent-default-codex-home-'));
	t.after(() => rm(root, { recursive: true, force: true }));
	const templateRoot = path.join(root, 'templates');
	await mkdir(path.join(templateRoot, '.codex', 'skills', 'minecraft-control'), { recursive: true });
	await writeFile(path.join(templateRoot, 'AGENTS.md'), '# Minecraft instructions\n', 'utf8');
	await writeFile(path.join(templateRoot, '.codex', 'skills', 'minecraft-control', 'SKILL.md'), '# Minecraft skill\n', 'utf8');
	const previousCodexHome = process.env.CODEX_HOME;
	delete process.env.CODEX_HOME;
	try {
		const workspace = new MinecraftAgentWorkspace({ root: path.join(root, 'runtime'), templateRoot });
		const prepared = await workspace.prepare();
		const sourceAuth = path.join(os.homedir(), '.codex', 'auth.json');
		try {
			assert.equal(await readFile(path.join(prepared.codexHome, 'auth.json'), 'utf8'), await readFile(sourceAuth, 'utf8'));
		} catch (error) {
			if (error?.code !== 'ENOENT') throw error;
		}
		assert.equal(await readFile(path.join(prepared.cwd, 'AGENTS.md'), 'utf8'), '# Minecraft instructions\n');
	} finally {
		if (previousCodexHome === undefined) delete process.env.CODEX_HOME;
		else process.env.CODEX_HOME = previousCodexHome;
	}
});
