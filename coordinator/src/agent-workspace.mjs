import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const MAX_SEGMENT_LENGTH = 128;
const SAFE_SEGMENT = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

export class AgentWorkspaceManager {
	#root;
	#mkdir;

	constructor(root, dependencies = {}) {
		if (typeof root !== 'string' || root.trim().length === 0) throw new TypeError('agent workspace root must be a nonblank path');
		if (dependencies.mkdir !== undefined && typeof dependencies.mkdir !== 'function') throw new TypeError('mkdir dependency must be a function');
		this.#root = path.resolve(root);
		this.#mkdir = dependencies.mkdir ?? mkdir;
	}

	get root() {
		return this.#root;
	}

	async prepare(provider, agentId) {
		const providerSegment = requireSafeSegment(provider, 'provider');
		const agentSegment = requireSafeSegment(agentId, 'agentId');
		const workspace = path.resolve(this.#root, providerSegment, agentSegment);
		if (!isDescendant(this.#root, workspace)) throw new TypeError('agent workspace must remain beneath the configured root');
		await this.#mkdir(workspace, { recursive: true });
		return workspace;
	}
}

function requireSafeSegment(value, field) {
	if (typeof value !== 'string' || value.length === 0 || value.length > MAX_SEGMENT_LENGTH || !SAFE_SEGMENT.test(value)) {
		throw new TypeError(`${field} must be a safe path segment`);
	}
	if (value === '.' || value === '..') throw new TypeError(`${field} must be a safe path segment`);
	return value;
}

function isDescendant(root, candidate) {
	const relative = path.relative(root, candidate);
	return relative.length > 0 && relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
}
