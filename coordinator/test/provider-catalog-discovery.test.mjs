import assert from 'node:assert/strict';
import test from 'node:test';

import {
	discoverAntigravityCatalog,
	discoverKimiCatalog,
	parseAntigravityModelsOutput,
	parseKimiProviderCatalog,
} from '../src/provider-catalog-discovery.mjs';

test('Antigravity parser derives stable slugs and preserves CLI effort order', () => {
	assert.deepEqual(parseAntigravityModelsOutput([
		'gemini-3.6-flash-high\tGemini 3.6 Flash (High)',
		'gemini-3.6-flash-medium\tGemini 3.6 Flash (Medium)',
		'gemini-3.6-flash-low\tGemini 3.6 Flash (Low)',
		'claude-sonnet-4-6-thinking\tClaude Sonnet 4.6 (Thinking)',
		'not model metadata',
	].join('\n')), [
		{ id: 'gemini-3.6-flash', model: 'gemini-3.6-flash', displayName: 'Gemini 3.6 Flash', reasoningEfforts: ['high', 'medium', 'low'], serviceTiers: [] },
		{ id: 'claude-sonnet-4-6', model: 'claude-sonnet-4-6', displayName: 'Claude Sonnet 4.6', reasoningEfforts: ['thinking'], serviceTiers: [] },
	]);
});

test('Kimi parser uses local aliases and configured support_efforts without exposing credentials', () => {
	assert.deepEqual(parseKimiProviderCatalog({ models: {
		'kimi-code/k3-256k': { displayName: 'K3-256k', supportEfforts: ['low', 'high', 'max'], apiKey: 'secret-must-not-leak' },
		'kimi-code/kimi-for-coding-highspeed': { displayName: 'K2.7 Coding Highspeed', capabilities: ['thinking', 'always_thinking'] },
	} }), [
		{ id: 'kimi-code/k3-256k', model: 'kimi-code/k3-256k', displayName: 'K3-256k', reasoningEfforts: ['low', 'high', 'max'], serviceTiers: [] },
		{ id: 'kimi-code/kimi-for-coding-highspeed', model: 'kimi-code/kimi-for-coding-highspeed', displayName: 'K2.7 Coding Highspeed', reasoningEfforts: ['high'], serviceTiers: [] },
	]);
});

test('CLI discovery adapters pass only the catalog command, explicit environment, and parse stdout', async () => {
	const calls = [];
	const environment = { PATH: 'test', KIMI_API_KEY: 'provider-key' };
	const execFile = (command, args, options, callback) => {
		calls.push({ command, args, options });
		callback(null, 'gemini-3.1-pro-low\tGemini 3.1 Pro (Low)\n', '');
	};
	assert.deepEqual(await discoverAntigravityCatalog({ execFile, environment }), [
		{ id: 'gemini-3.1-pro', model: 'gemini-3.1-pro', displayName: 'Gemini 3.1 Pro', reasoningEfforts: ['low'], serviceTiers: [] },
	]);
	assert.deepEqual(calls[0].args, ['models']);
	assert.equal(calls[0].options.env, environment);

	const kimi = await discoverKimiCatalog({ execFile: (command, args, options, callback) => {
		calls.push({ command, args, options });
		callback(null, JSON.stringify({ providers: {}, models: { 'kimi-code/k3': { supportEfforts: ['high'] } } }), '');
	}, environment });
	assert.equal(kimi[0].id, 'kimi-code/k3');
	assert.deepEqual(calls.at(-1).args, ['provider', 'list', '--json']);
	assert.equal(calls.at(-1).options.env, environment);
});
