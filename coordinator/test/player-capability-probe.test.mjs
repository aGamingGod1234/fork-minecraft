import assert from 'node:assert/strict';
import test from 'node:test';
import { fixtureAction, fixtureSetupCommands, inputFrame, menuClickArguments, observedHandArguments, parseProbeArguments, probeAuditEntry, probeFailureMessage } from '../src/player-capability-probe.mjs';
import { validateProtocolV2Payload } from '../src/protocol-v2.mjs';

const args = ['--rcon-port', '25579', '--bridge-port', '25580', '--rcon-password-file', 'private/rcon.txt', '--bridge-secret-file', 'private/bridge.txt', '--run-directory', 'run'];
const record = { goalRevision: 2, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' };

test('mechanics CLI restricts connections to loopback and accepts secret paths without inline secrets', () => {
	const config = parseProbeArguments(args, {});
	assert.equal(config.host, '127.0.0.1');
	assert.equal(config.rconPort, 25579);
	assert.equal(config.bridgePort, 25580);
	assert.equal(config.agentName, 'CapabilityProbe');
	assert.equal(config.secret, undefined);
	assert.equal(config.password, undefined);
	assert.throws(() => parseProbeArguments([...args, '--rcon-host', '10.0.0.1'], {}), /LOOPBACK/);
	assert.throws(() => parseProbeArguments([...args, '--secret', 'not-allowed'], {}), /ARGUMENT/);
	assert.throws(() => parseProbeArguments([...args, '--agent-name', '@a'], {}), /AGENT_NAME/);
	assert.throws(() => parseProbeArguments([...args, '--bridge-port', '25581'], {}), /ARGUMENT/);
});

test('scripted mechanics actions validate against the production bridge contract and declare fixture authorship', () => {
	const payload = fixtureAction(record, { eventSequence: 9 }, 1, 'control_sequence', {
		frames: [inputFrame({ forward: 1, branches: [{ condition: 'on_ground', value: true, nextFrame: 1 }] }), inputFrame({ jump: true })], maxTicks: 40,
	});
	assert.deepEqual(validateProtocolV2Payload('action_command', payload), payload);
	assert.equal(payload.provenance.programId, 'provider-free-mechanics-fixture');
	assert.equal(payload.provenance.model, record.model);
	assert.equal(payload.provenance.eventSequence, 9);
	assert.equal(payload.goalRevision, 2);
	assert.throws(() => fixtureAction(record, { eventSequence: 9 }, 2, 'control_sequence', { frames: [inputFrame({ ticks: 201 })], maxTicks: 40 }));
});

test('menu fixture clicks use exact inspected container, revision and target stack', () => {
	const page = { menu: { type: 'minecraft:generic_9x3', containerId: 4, stateId: 7, cursor: { itemId: 'minecraft:air', count: 0 } }, entries: [{ slot: 0, itemId: 'minecraft:stone', count: 4, fingerprint: 'stack-component-fingerprint' }] };
	const click = menuClickArguments(page, 0);
	assert.equal(click.expectedItemId, 'minecraft:stone');
	assert.equal(click.expectedCount, 4);
	assert.equal(click.expectedFingerprint, 'stack-component-fingerprint');
	assert.equal(click.containerId, 4);
	assert.equal(click.stateId, 7);
	assert.doesNotThrow(() => fixtureAction(record, { eventSequence: 9 }, 2, 'menu_click', click));
	assert.throws(() => menuClickArguments(page, 99), /INSPECTION_REQUIRED/);
});

test('interaction fixtures bind expected hand items to the same fresh observation as action provenance', () => {
	const beforeOperatorMove = { ready: true, eventSequence: 91, interaction: { mainHandItemId: 'minecraft:air', offHandItemId: 'minecraft:air' } };
	const afterPickup = { ...beforeOperatorMove, eventSequence: 92, interaction: { mainHandItemId: 'minecraft:leaf_litter', offHandItemId: 'minecraft:stick' } };
	const args = (fresh) => ({ targetId: '1d934a02-f281-4d5d-a1f5-d36ccf2136f8', ...observedHandArguments(fresh) });
	const payload = fixtureAction(record, afterPickup, 13, 'interact_entity', args);
	assert.equal(payload.arguments.expectedItemId, 'minecraft:leaf_litter');
	assert.equal(payload.arguments.hand, 'main');
	assert.equal(payload.provenance.eventSequence, 92);
	assert.equal(fixtureAction(record, beforeOperatorMove, 14, 'interact_entity', args).arguments.expectedItemId, 'minecraft:air');
	assert.deepEqual(observedHandArguments(afterPickup, 'off'), { hand: 'off', expectedItemId: 'minecraft:stick' });
	assert.throws(() => observedHandArguments({ ready: true, inventory: { selectedItem: 'minecraft:air' } }), /HAND_OBSERVATION_REQUIRED/);
	assert.throws(() => observedHandArguments({ ...afterPickup, ready: false }), /HAND_OBSERVATION_REQUIRED/);
});

test('recipe, mechanics and vehicle fixtures use production query and action contracts', () => {
	for (const query of [{ section: 'recipes', limit: 4 }, { section: 'recipes', recipeId: 'minecraft:oak_planks', limit: 4 }, { section: 'mechanics' }]) {
		assert.doesNotThrow(() => validateProtocolV2Payload('inspection_request', { requestId: 'fixture-knowledge', goalRevision: 2, query }));
	}
	assert.doesNotThrow(() => fixtureAction(record, { eventSequence: 9 }, 3, 'interact_entity', { targetId: '1d934a02-f281-4d5d-a1f5-d36ccf2136f8', hand: 'main', expectedItemId: 'minecraft:air' }));
	assert.doesNotThrow(() => fixtureAction(record, { eventSequence: 10 }, 4, 'control_sequence', { frames: [inputFrame({ forward: 1, ticks: 20 })], maxTicks: 25 }));
	assert.doesNotThrow(() => fixtureAction(record, { eventSequence: 11 }, 5, 'dismount', {}));
});

test('probe diagnostics keep rejected field names while removing credentials and all payload values', () => {
	const secret = 'private-bridge-value';
	assert.equal(probeFailureMessage(new Error(`Invalid field player.extra ${secret}\ncontinued`), [secret]), 'Invalid field player.extra [REDACTED] continued');
	assert.ok(probeFailureMessage(new Error('a'.repeat(2000))).length <= 1024);
	assert.deepEqual(probeAuditEntry('server_to_coordinator', { type: 'auth_response', payload: { proof: secret } }, 'received_before_validation', [secret]), { direction: 'server_to_coordinator', phase: 'received_before_validation', type: 'auth_response', payloadRedacted: true });
	const audit = probeAuditEntry('server_to_coordinator', { type: 'observation', payload: { player: { extra: secret, health: 20 }, blocks: [{ blockId: 'minecraft:stone' }] } }, 'received_before_validation', [secret]);
	assert.ok(audit.fieldPaths.includes('payload.player.extra'));
	assert.ok(audit.fieldPaths.includes('payload.blocks[0].blockId'));
	assert.doesNotMatch(JSON.stringify(audit), /private-bridge-value|minecraft:stone|20/);
});

test('fixture mutations remain small explicit terrain operations and never grant advancement success', () => {
	const commands = fixtureSetupCommands();
	assert.ok(commands.some((entry) => entry.includes('minecraft:ladder')));
	assert.ok(commands.some((entry) => entry.includes('minecraft:water')));
	assert.ok(commands.some((entry) => entry.includes('minecraft:chest')));
	assert.equal(commands.some((entry) => /advancement|function|datapack|execute.*run.*tp/.test(entry)), false);
	for (const command of commands.filter((entry) => entry.startsWith('fill '))) {
		const coordinates = command.split(' ').slice(1, 7).map(Number);
		const volume = [0, 1, 2].reduce((value, index) => value * (Math.abs(coordinates[index] - coordinates[index + 3]) + 1), 1);
		assert.ok(volume <= 32768);
	}
});
