import assert from 'node:assert/strict';
import test from 'node:test';

import { sanitizeDiagnosticErrorCode, sanitizeDiagnosticErrorMessage, sanitizeDiagnosticErrorStack, sanitizeDiagnosticText, sanitizeDiagnosticValue } from '../src/diagnostic-sanitizer.mjs';

test('shared diagnostic sanitizer redacts credentials and absolute paths', () => {
	const source = [
		'{"api_key":"JSON SECRET", "password":"PASS SECRET"}',
		'Authorization: Bearer bearer-secret',
		'C:\\Users\\lucas\\private\\token.json',
		'/home/lucas/private/token.json',
		'\\\\server\\share\\private\\token.json',
	].join(' ');
	const result = sanitizeDiagnosticText(source, { maxBytes: 2_048 });
	for (const secret of ['JSON SECRET', 'PASS SECRET', 'bearer-secret', 'lucas', 'server', 'share']) {
		assert.equal(result.includes(secret), false, `leaked ${secret}`);
	}
});

test('shared diagnostic sanitizer contains proxies and accessors and bounds strings', () => {
	const value = Object.create(null, {
		password: { enumerable: true, value: 'secret' },
		message: { enumerable: true, value: 'x'.repeat(20_000) },
		hostile: { enumerable: true, get() { throw new Error('must not run'); } },
	});
	assert.doesNotThrow(() => sanitizeDiagnosticValue(value));
	const safe = sanitizeDiagnosticValue(value, { maxStringBytes: 64 });
	assert.equal(safe.password, '[REDACTED]');
	assert.ok(Buffer.byteLength(safe.message, 'utf8') <= 64);
	assert.equal(Object.hasOwn(safe, 'hostile'), false);
	assert.equal(sanitizeDiagnosticValue(new Proxy({}, { ownKeys() { throw new Error('hostile proxy'); } })), '[UNSAFE_OBJECT]');
});

test('credential grammar redacts quoted assignments and authorization schemes', () => {
	const cases = [
		['api_key = "super secret"', 'super secret'],
		["'client_secret' = 'client secret value'", 'client secret value'],
		['password: "space rich password"', 'space rich password'],
		['Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==', 'QWxhZGRpbjpvcGVuIHNlc2FtZQ=='],
		['authorization = Bearer sk-live_realistic.credential-value', 'sk-live_realistic.credential-value'],
		['github_token=ghp_0123456789abcdefghijklmnopqrstuvwxyz', 'ghp_0123456789abcdefghijklmnopqrstuvwxyz'],
		['raw prompt: mine the nearby tree\noperation=decide', 'mine the nearby tree'],
	];
	for (const [source, secret] of cases) {
		const sanitized = sanitizeDiagnosticText(source);
		assert.equal(sanitized.includes(secret), false, `leaked credential from ${source}`);
		assert.match(sanitized, /\[REDACTED\]/);
	}
});

test('sanitizer preserves URLs and operational token metrics while redacting only absolute filesystem paths', () => {
	const preserved = [
		'https://example.com/v1/token?count=2',
		'http://127.0.0.1:8766/v1/tts',
		'inputTokens=123 output_token_count=45 tokenCount:8 cached_tokens=9',
		'token_bucket=4 token_latency_ms=12',
		'src/token/worker.js',
	].join(' ');
	assert.equal(sanitizeDiagnosticText(preserved), preserved);
	for (const absolutePath of [
		'C:\\Users\\lucas\\Arena Agents\\secret.json',
		'/var/lib/arena agents/secret.json',
		'\\\\server\\share\\Arena Agents\\secret.json',
	]) {
		const sanitized = sanitizeDiagnosticText(`failure at "${absolutePath}"`);
		assert.equal(sanitized.includes(absolutePath), false);
		assert.match(sanitized, /\[location redacted\]/);
	}
});

test('structured token metrics remain visible but actual token credentials are redacted', () => {
	const sanitized = sanitizeDiagnosticValue({
		inputTokens: 123,
		output_token_count: 45,
		tokenCount: 8,
		tokens: { input: 123, output: 45, reasoning: 8, cached: 3, cacheWrite: null },
		access_token: 'secret-access-token',
		github_token: 'secret-github-token',
	});
	assert.equal(sanitized.inputTokens, 123);
	assert.equal(sanitized.output_token_count, 45);
	assert.equal(sanitized.tokenCount, 8);
	assert.deepEqual({ ...sanitized.tokens }, { input: 123, output: 45, reasoning: 8, cached: 3, cacheWrite: null });
	assert.equal(sanitized.access_token, '[REDACTED]');
	assert.equal(sanitized.github_token, '[REDACTED]');
	assert.equal(sanitizeDiagnosticValue({ tokens: 'credential-shaped-secret' }).tokens, '[REDACTED]');
});

test('every operational token alias requires a field-appropriate numeric metric', () => {
	const aliases = [
		['inputTokens', 12],
		['output_token_count', 12],
		['tokenCount', 12],
		['tokens_count', 12],
		['cached_tokens', 12],
		['token_bucket', 12],
		['token_latency_ms', 12.5],
		['token_budget', 12],
		['token_limit', 12],
		['token_usage', 12],
		['token_remaining', 12],
		['Input-Tokens', 12],
		['TOKEN LATENCY MS', 12.5],
	];
	for (const [alias, metric] of aliases) {
		assert.equal(sanitizeDiagnosticValue({ [alias]: metric })[alias], metric, `${alias} rejected a valid metric`);
		for (const invalid of ['12', -1, Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1, {}, new Proxy({}, {})]) {
			assert.equal(sanitizeDiagnosticValue({ [alias]: invalid })[alias], '[REDACTED]', `${alias} accepted ${String(invalid)}`);
		}
		if (alias.toLowerCase().replace(/[ -]/g, '_') !== 'token_latency_ms') {
			assert.equal(sanitizeDiagnosticValue({ [alias]: 1.5 })[alias], '[REDACTED]', `${alias} accepted a fractional count`);
		}
		if (!alias.includes(' ')) {
			assert.equal(sanitizeDiagnosticText(`${alias}=${metric}`), `${alias}=${metric}`, `${alias} text rejected a valid metric`);
			const credential = `${alias}-credential`;
			const text = sanitizeDiagnosticText(`${alias}=${credential}`);
			assert.equal(text.includes(credential), false, `${alias} text leaked a credential-shaped value`);
		}
	}
});

test('operational token aliases never execute accessors and preserve only numeric text literals', () => {
	let getterCalls = 0;
	const hostile = Object.create(null, {
		inputTokens: { enumerable: true, get() { getterCalls += 1; return 42; } },
	});
	const sanitized = sanitizeDiagnosticValue(hostile);
	assert.equal(getterCalls, 0);
	assert.equal(Object.hasOwn(sanitized, 'inputTokens'), false);

	for (const source of ['inputTokens=12', 'token_latency_ms:12.5', 'TOKEN-LIMIT = 0']) {
		assert.equal(sanitizeDiagnosticText(source), source);
	}
	for (const [source, secret] of [
		['inputTokens="12"', '12'],
		['output_token_count=-1', '-1'],
		['tokenCount=1.5', '1.5'],
		['cached_tokens=provider-secret', 'provider-secret'],
		['token_latency_ms=Infinity', 'Infinity'],
		['token_budget=9007199254740992', '9007199254740992'],
	]) {
		const output = sanitizeDiagnosticText(source);
		assert.equal(output.includes(secret), false, `${source} leaked a non-metric value`);
		assert.match(output, /\[REDACTED\]/);
	}
});

test('tokens object requires exact own data metrics and preserves the validated object', () => {
	const exact = Object.create(null, {
		input: { enumerable: true, value: 12 },
		output: { enumerable: true, value: 4 },
		reasoning: { enumerable: true, value: 2 },
		cached: { enumerable: true, value: 1 },
		cacheWrite: { enumerable: true, value: null },
	});
	assert.deepEqual({ ...sanitizeDiagnosticValue({ tokens: exact }).tokens }, { input: 12, output: 4, reasoning: 2, cached: 1, cacheWrite: null });
	for (const invalid of [
		{ input: '12' },
		{ input: -1 },
		{ input: 1.5 },
		{ input: Number.POSITIVE_INFINITY },
		{ input: 1, credential: 'secret' },
		new Proxy({ input: 1 }, {}),
	]) assert.equal(sanitizeDiagnosticValue({ tokens: invalid }).tokens, '[REDACTED]');
	let getterCalls = 0;
	const accessor = Object.create(null, { input: { enumerable: true, get() { getterCalls += 1; return 12; } } });
	assert.equal(sanitizeDiagnosticValue({ tokens: accessor }).tokens, '[REDACTED]');
	assert.equal(getterCalls, 0);
});

test('error helpers read only own data fields and sanitize messages, stacks, and codes', () => {
	const error = Object.assign(new Error('authorization=secret-message'), { code: 'PROVIDER_FAILED' });
	error.stack = 'Error: authorization=secret-stack\n at C:\\private\\agent.mjs:1:2';
	assert.equal(sanitizeDiagnosticErrorCode(error), 'PROVIDER_FAILED');
	assert.doesNotMatch(sanitizeDiagnosticErrorMessage(error), /secret-message/);
	assert.doesNotMatch(sanitizeDiagnosticErrorStack(error), /secret-stack|private/);

	let getterCalls = 0;
	const accessor = Object.create(null, {
		code: { enumerable: true, get() { getterCalls += 1; return 'LEAKED_CODE'; } },
		message: { enumerable: true, get() { getterCalls += 1; return 'secret-message'; } },
		stack: { enumerable: true, get() { getterCalls += 1; return 'secret-stack'; } },
	});
	assert.equal(sanitizeDiagnosticErrorCode(accessor), 'UNKNOWN');
	assert.equal(sanitizeDiagnosticErrorMessage(accessor), 'unknown error');
	assert.equal(sanitizeDiagnosticErrorStack(accessor), 'unknown error');
	assert.equal(getterCalls, 0);
	const proxy = new Proxy({}, { get() { getterCalls += 1; throw new Error('must not read proxy'); } });
	assert.equal(sanitizeDiagnosticErrorMessage(proxy), 'unknown error');
	assert.equal(getterCalls, 0);
});

test('launcher and account credential aliases redact in text and structured diagnostics', () => {
	const aliases = ['launcherAccount', 'launcher_account', 'launcher-account', 'launcheraccount', 'accountData', 'account_data', 'account-data', 'accountdata'];
	for (const [index, alias] of aliases.entries()) {
		const secret = `private-value-${index}`;
		const text = sanitizeDiagnosticText(`${alias} = "${secret}"`);
		assert.equal(text.includes(secret), false, `${alias} text assignment leaked`);
		assert.match(text, /\[REDACTED\]/);
		const structured = sanitizeDiagnosticValue({ [alias]: secret });
		assert.equal(structured[alias], '[REDACTED]', `${alias} structured field leaked`);
	}
});

test('file absolute URIs redact without changing network URLs or relative paths', () => {
	for (const location of [
		'file:///C:/Users/lucas/Arena%20Agents/secret.json',
		'file:///var/lib/arena%20agents/secret.json',
		'file://server/share/Arena%20Agents/secret.json',
	]) {
		const sanitized = sanitizeDiagnosticText(`failure at ${location}`);
		assert.equal(sanitized.includes(location), false, `${location} leaked`);
		assert.match(sanitized, /\[location redacted\]/);
	}
	const safe = 'https://example.com/file:///docs http://127.0.0.1:8766/v1/tts relative/file.txt inputTokens=4';
	assert.equal(sanitizeDiagnosticText(safe), safe);
});
