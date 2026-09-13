import { randomUUID } from 'node:crypto';
import { chmod, mkdir, readFile, readdir, rename, rm, unlink, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import path from 'node:path';

const AGENTS_TEMPLATE = 'AGENTS.md';
const SKILL_TEMPLATE = path.join('.codex', 'skills', 'minecraft-control', 'SKILL.md');

export class MinecraftAgentWorkspace {
	#refresh = Promise.resolve();
	#codexHomeSanitized = false;
	#lastSyncedSourceAuth = undefined;

	constructor({ root, templateRoot }, dependencies = {}) {
		if (typeof root !== 'string' || root.trim().length === 0) {
			throw new TypeError('minecraft agent workspace root must be a nonblank path');
		}
		if (typeof templateRoot !== 'string' || templateRoot.trim().length === 0) {
			throw new TypeError('minecraft agent workspace template root must be a nonblank path');
		}

		this.root = path.resolve(root);
		this.templateRoot = path.resolve(templateRoot);
		this.codexHome = path.join(this.root, '.codex-home');
		this.sourceCodexHome = typeof dependencies.sourceCodexHome === 'string' && dependencies.sourceCodexHome.trim() !== ''
			? path.resolve(dependencies.sourceCodexHome)
			: typeof process.env.CODEX_HOME === 'string' && process.env.CODEX_HOME.trim() !== ''
				? path.resolve(process.env.CODEX_HOME)
				: path.join(homedir(), '.codex');
		this.fs = {
			mkdir,
			readFile,
			readdir,
			writeFile,
			rename,
			rm,
			unlink,
			chmod,
			...(dependencies.fs ?? {}),
		};
	}

	prepare({ sourceCodexHome } = {}) {
		if (sourceCodexHome !== undefined && (typeof sourceCodexHome !== 'string' || sourceCodexHome.trim() === '')) {
			throw new TypeError('sourceCodexHome must be a nonblank path when provided');
		}
		const refresh = this.#refresh.then(() => this.#prepare(sourceCodexHome));
		this.#refresh = refresh.catch(() => {});
		return refresh;
	}

	async #prepare(sourceCodexHome) {
		const skillRoot = path.join(this.root, '.codex', 'skills', 'minecraft-control');
		await this.fs.mkdir(skillRoot, { recursive: true });
		await this.fs.mkdir(this.codexHome, { recursive: true });
		await this.fs.chmod(this.codexHome, 0o700);
		if (!this.#codexHomeSanitized) {
			await this.#wipeNonAuthFiles();
			this.#codexHomeSanitized = true;
		}
		await this.#syncAuth(sourceCodexHome);
		const instructions = await this.#readTemplate(AGENTS_TEMPLATE);
		await this.#replace(AGENTS_TEMPLATE, path.join(this.root, AGENTS_TEMPLATE), instructions);
		await this.#replace(SKILL_TEMPLATE, path.join(skillRoot, 'SKILL.md'));
		return {
			cwd: this.root,
			codexHome: this.codexHome,
			instructions,
			selectedCapabilityRoots: [{
				id: 'minecraft-control',
				location: { type: 'environment', environmentId: 'local', path: skillRoot },
			}],
		};
	}

	async #wipeNonAuthFiles() {
		for (const entry of await this.fs.readdir(this.codexHome, { withFileTypes: true })) {
			if (entry.name !== 'auth.json') await this.fs.rm(path.join(this.codexHome, entry.name), { recursive: true, force: true });
		}
	}

	async #readOptionalAuth(authPath) {
		try {
			return await this.fs.readFile(authPath, 'utf8');
		} catch (error) {
			if (error?.code !== 'ENOENT') throw error;
			return null;
		}
	}

	async #syncAuth(sourceCodexHome = this.sourceCodexHome) {
		const destination = path.join(this.codexHome, 'auth.json');
		const source = path.resolve(sourceCodexHome);
		if (source === path.resolve(this.codexHome)) {
			this.#lastSyncedSourceAuth = await this.#readOptionalAuth(destination);
			return;
		}
		const sourceContent = await this.#readOptionalAuth(path.join(source, 'auth.json'));
		const isolatedContent = await this.#readOptionalAuth(destination);
		if (isolatedContent == null) {
			if (sourceContent != null) await this.#replaceContent(sourceContent, destination, 0o600);
			this.#lastSyncedSourceAuth = sourceContent;
			return;
		}
		if (sourceContent == null) {
			if (isolatedContent === this.#lastSyncedSourceAuth) await this.fs.rm(destination, { force: true });
			this.#lastSyncedSourceAuth = null;
			return;
		}
		if (sourceContent === this.#lastSyncedSourceAuth) return;
		if (this.#lastSyncedSourceAuth === undefined || isolatedContent === this.#lastSyncedSourceAuth) {
			await this.#replaceContent(sourceContent, destination, 0o600);
			this.#lastSyncedSourceAuth = sourceContent;
		}
	}

	async #readTemplate(template) {
		return this.fs.readFile(path.join(this.templateRoot, template), 'utf8');
	}

	async #replace(template, destination, content = undefined) {
		content ??= await this.#readTemplate(template);
		return this.#replaceContent(content, destination, 0o644);
	}

	async #replaceContent(content, destination, mode) {
		try {
			if (await this.fs.readFile(destination, 'utf8') === content) return;
		} catch (error) {
			if (error?.code !== 'ENOENT') throw error;
		}
		const temporary = path.join(
			path.dirname(destination),
			`.${path.basename(destination)}.${process.pid}.${randomUUID()}.tmp`,
		);
		let replaced = false;
		try {
			await this.fs.writeFile(temporary, content, 'utf8');
			await this.fs.chmod(temporary, mode);
			try {
				await this.fs.rename(temporary, destination);
			} catch (error) {
				if (!['EEXIST', 'EPERM'].includes(error?.code)) throw error;
				await this.fs.rm(destination, { force: true });
				await this.fs.rename(temporary, destination);
			}
			replaced = true;
		} finally {
			if (!replaced) await this.fs.unlink(temporary).catch(() => {});
		}
	}
}
