import { createHash } from 'node:crypto';

const PROFILE_FIELDS = Object.freeze(['agentId', 'provider', 'model', 'reasoningEffort', 'serviceTier']);
const SESSION_STATES = new Set(['cold', 'warm', 'reset']);
const CONTINUATIONS = new Set(['durable', 'best_effort']);

/** Return one bounded, credential-free fingerprint for the selected provider profile. */
export function profileFingerprint(profile) {
	if (profile === null || typeof profile !== 'object' || Array.isArray(profile)) throw new TypeError('provider profile must be an object');
	const tuple = {};
	for (const field of PROFILE_FIELDS) {
		if (typeof profile[field] !== 'string' || profile[field].trim().length === 0) throw new TypeError(`provider profile ${field} must be nonblank`);
		tuple[field] = profile[field].trim();
	}
	const canonical = JSON.stringify(tuple);
	return `sha256:${createHash('sha256').update(canonical, 'utf8').digest('hex')}`;
}

export function createSessionMetadata(profile, {
	sessionGeneration = 1,
	sessionState = 'cold',
	continuation = 'durable',
	durability = continuation === 'durable' ? 'proven' : 'unverified',
	resetReason = null,
} = {}) {
	if (!Number.isSafeInteger(sessionGeneration) || sessionGeneration < 1) throw new TypeError('sessionGeneration must be a positive safe integer');
	if (!SESSION_STATES.has(sessionState)) throw new TypeError('sessionState is invalid');
	if (!CONTINUATIONS.has(continuation)) throw new TypeError('continuation is invalid');
	if (typeof durability !== 'string' || durability.trim().length === 0 || durability.length > 32) throw new TypeError('durability must be bounded text');
	if (resetReason !== null && (typeof resetReason !== 'string' || resetReason.length > 128)) throw new TypeError('resetReason must be null or bounded text');
	return Object.freeze({
		agentId: typeof profile.agentId === 'string' ? profile.agentId : null,
		profileFingerprint: profileFingerprint(profile),
		sessionGeneration,
		sessionState,
		sessionReuse: sessionState === 'warm',
		continuation,
		durability,
		resetReason,
	});
}

export function sameProfileFingerprint(left, right) {
	return profileFingerprint(left) === profileFingerprint(right);
}
