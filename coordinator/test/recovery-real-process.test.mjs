import assert from 'node:assert/strict';
import { fork } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { once } from 'node:events';
import net from 'node:net';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { createBridgeAuthenticationProof } from '../src/protocol-v2.mjs';

const coordinatorRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const fixture = path.join(coordinatorRoot, 'test-support', 'recovery-coordinator-process.mjs');
const secret = 'recovery-smoke-secret-32-bytes-ok';

test('real coordinator process reconnects to a restored loopback bridge without credentials or Minecraft', { timeout: 5_000 }, async () => {
	const sockets = new Set();
	let maxOpenSockets = 0;
	let connections = 0;
	const server = net.createServer((socket) => {
		connections += 1;
		sockets.add(socket);
		maxOpenSockets = Math.max(maxOpenSockets, sockets.size);
		socket.on('close', () => sockets.delete(socket));
		let buffered = '';
		let serverNonce = null;
		socket.on('data', (chunk) => {
			buffered += chunk.toString('utf8');
			while (true) {
				const boundary = buffered.indexOf('\n');
				if (boundary < 0) return;
				const message = JSON.parse(buffered.slice(0, boundary));
				buffered = buffered.slice(boundary + 1);
				if (message.type === 'auth_challenge') {
					serverNonce = randomBytes(32).toString('base64url');
					socket.write(`${JSON.stringify({
						protocolVersion: 2,
						serverInstanceId: 'recovery-smoke-server',
						agentId: 'server',
						type: 'auth_response',
						messageId: `smoke-auth-${connections}`,
						payload: {
							replyTo: message.messageId,
							clientNonce: message.payload.clientNonce,
							serverNonce,
							proof: createBridgeAuthenticationProof(secret, 'server', {
								clientNonce: message.payload.clientNonce,
								serverNonce,
								serverInstanceId: 'recovery-smoke-server',
							}),
						},
					})}\n`);
					continue;
				}
				if (message.type === 'hello') {
					assert.equal(message.payload.serverNonce, serverNonce);
					socket.write(`${JSON.stringify({
						protocolVersion: 2,
						serverInstanceId: 'recovery-smoke-server',
						agentId: 'server',
						type: 'hello_ack',
						messageId: `smoke-ack-${connections}`,
						payload: { replyTo: message.messageId, authenticated: true, registry: [] },
					})}\n`);
				}
			}
		});
	});
	await new Promise((resolve, reject) => {
		server.once('error', reject);
		server.listen(0, '127.0.0.1', resolve);
	});
	const port = server.address().port;
	const child = fork(fixture, [], {
		cwd: coordinatorRoot,
		env: { ...process.env, ARENA_RECOVERY_SMOKE_PORT: String(port), ARENA_RECOVERY_SMOKE_SECRET: secret },
		stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
	});
	const stdout = [];
	const stderr = [];
	child.stdout.on('data', (chunk) => stdout.push(chunk.toString('utf8')));
	child.stderr.on('data', (chunk) => stderr.push(chunk.toString('utf8')));
	try {
		await waitForMessage(child, (message) => message?.type === 'started', 2_000, () => stderr.join(''));
		const first = await waitForMessage(child, (message) => message?.type === 'healthy', 2_000, () => stderr.join(''));
		assert.equal(first.connectionEpoch, 1);
		assert.deepEqual(first.runtimeErrors, []);
		assert.equal(connections, 1);

		for (const socket of sockets) socket.destroy();
		const second = await waitForMessage(child, (message) => message?.type === 'healthy' && message.connectionEpoch > first.connectionEpoch);
		assert.equal(second.connectionEpoch, 2);
		assert.deepEqual(second.runtimeErrors, []);
		assert.equal(connections, 2);

		child.send({ type: 'stop' });
		const stopped = await waitForMessage(child, (message) => message?.type === 'stopped');
		assert.deepEqual(stopped.runtimeErrors, []);
		child.disconnect();
		const [code, signal] = await once(child, 'exit');
		assert.equal(code, 0);
		assert.equal(signal, null);
		assert.equal(sockets.size, 0);
		assert.equal(maxOpenSockets, 1, 'reconnect never overlaps owned bridge sockets');
		assert.equal(await openConnectionCount(server), 0);
		assert.equal(child.connected, false);
		assert.equal(child.listenerCount('message'), 0);
		assert.equal(child.listenerCount('exit'), 0);
		assert.equal(child.stdout.readableEnded, true);
		assert.equal(child.stderr.readableEnded, true);
		assert.equal(stdout.join(''), '');
		assert.equal(stderr.join(''), '');
	} finally {
		if (child.exitCode === null && child.signalCode === null) child.kill();
		for (const socket of sockets) socket.destroy();
		await new Promise((resolve) => server.close(resolve));
	}
});

function waitForMessage(child, predicate, timeoutMs = 2_000, diagnostics = () => '') {
	return new Promise((resolve, reject) => {
		const timer = setTimeout(() => finish(new Error('coordinator child did not reach the expected boundary')), timeoutMs);
		const onMessage = (message) => {
			if (predicate(message)) finish(null, message);
		};
		const onExit = (code, signal) => finish(new Error(`coordinator child exited early (${code ?? signal}): ${diagnostics()}`));
		const finish = (error, value) => {
			clearTimeout(timer);
			child.off('message', onMessage);
			child.off('exit', onExit);
			if (error) reject(error);
			else resolve(value);
		};
		child.on('message', onMessage);
		child.once('exit', onExit);
	});
}

function openConnectionCount(server) {
	return new Promise((resolve, reject) => server.getConnections((error, count) => error ? reject(error) : resolve(count)));
}
