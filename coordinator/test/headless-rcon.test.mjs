import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import net from 'node:net';
import test from 'node:test';
import { HeadlessRconClient, normalizeRconResult } from '../src/headless-rcon.mjs';

const packet = (id, type, text = '') => {
  const body = Buffer.from(`${text}\0`, 'utf8');
  const payload = Buffer.allocUnsafe(4 + 4 + body.length + 1);
  payload.writeInt32LE(id, 0);
  payload.writeInt32LE(type, 4);
  body.copy(payload, 8);
  payload[payload.length - 1] = 0;
  return Buffer.concat([Buffer.from([payload.length, 0, 0, 0]), payload]);
};
const readPacket = (socket, onPacket) => {
  let buffered = Buffer.alloc(0);
  socket.on('data', chunk => {
    buffered = Buffer.concat([buffered, chunk]);
    while (buffered.length >= 4) {
      const length = buffered.readInt32LE(0);
      if (buffered.length < length + 4) return;
      onPacket(buffered.subarray(4, length + 4));
      buffered = buffered.subarray(length + 4);
    }
  });
};
const startServer = handler => new Promise((resolve, reject) => {
  const server = net.createServer(socket => {
    readPacket(socket, payload => handler(socket, {
      id: payload.readInt32LE(0), type: payload.readInt32LE(4), text: payload.subarray(8, payload.length - 2).toString('utf8')
    }));
  });
  server.once('error', reject);
  server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
});
const stopServer = server => new Promise(resolve => server.close(() => resolve()));

class DeferredSocket extends EventEmitter {
  readyState = 'opening';
  destroyed = false;
  write() {}
  destroy() {
    this.destroyed = true;
    queueMicrotask(() => this.emit('close'));
  }
}

test('authenticates, frames little-endian packets, and handles fragmented responses', async () => {
  const { server, port } = await startServer((socket, request) => {
    if (request.type === 3) {
      socket.write(packet(request.id, 2, 'ok'));
    } else {
      const response = packet(request.id, 0, `result:${request.text}`);
      socket.write(response.subarray(0, 3));
      setTimeout(() => socket.write(response.subarray(3)), 1);
    }
  });
  const client = new HeadlessRconClient({ host: '127.0.0.1', port, password: 'pw' });
  try {
    await client.connect();
    const result = await client.command('list');
    assert.equal(result.text, 'result:list');
    assert.equal(result.id, 2);
    assert.equal(result.type, 0);
  } finally { await client.close(); await stopServer(server); }
});

test('rejects authentication failure and command before authentication', async () => {
  const { server, port } = await startServer((socket, request) => {
    if (request.type === 3) socket.write(packet(-1, 2));
  });
  const client = new HeadlessRconClient({ host: '127.0.0.1', port, password: 'bad' });
  await assert.rejects(() => client.command('x'), error => error.code === 'RCON_NOT_AUTHENTICATED');
  await assert.rejects(() => client.connect(), error => error.code === 'RCON_AUTH_FAILED');
  await client.close(); await stopServer(server);
});

test('times out commands and closes pending commands with typed error', async () => {
  const { server, port } = await startServer((socket, request) => {
    if (request.type === 3) socket.write(packet(request.id, 2));
  });
  const client = new HeadlessRconClient({ host: '127.0.0.1', port, password: 'pw', commandTimeoutMs: 10 });
  await client.connect();
  await assert.rejects(() => client.command('hang'), error => error.code === 'RCON_TIMEOUT');
  const pending = client.command('close');
  await client.close();
  await assert.rejects(pending, error => error.code === 'RCON_CLOSED');
  await client.close();
  await stopServer(server);
});

test('rejects malformed lengths and bounds UTF-8 response decoding', async () => {
  const { server, port } = await startServer((socket, request) => {
    if (request.type === 3) socket.write(packet(request.id, 2));
    else socket.write(Buffer.from([0xff, 0xff, 0xff, 0x7f]));
  });
  const client = new HeadlessRconClient({ host: '127.0.0.1', port, password: 'pw', maxResponseBytes: 8 });
  await client.connect();
  await assert.rejects(() => client.command('bad'), error => error.code === 'RCON_PROTOCOL');
  await client.close(); await stopServer(server);
  const normalized = normalizeRconResult({ text: '😀😀😀😀', success: true });
  assert.equal(normalized.success, true);
  assert.equal(normalized.boundedText, '😀😀😀😀');
  assert.equal(normalized.text, '😀😀😀😀');
});

test('decodes a large response into bounded UTF-8 text', async () => {
  const { server, port } = await startServer((socket, request) => {
    if (request.type === 3) socket.write(packet(request.id, 2));
    else socket.write(packet(request.id, 0, 'abcdefghijk'));
  });
  const client = new HeadlessRconClient({ host: '127.0.0.1', port, password: 'pw', maxResponseBytes: 5 });
  await client.connect();
  try { assert.equal((await client.command('bounded')).text, 'abcde'); }
  finally { await client.close(); await stopServer(server); }
});

test('close during connect rejects the connect promise and prevents reuse', async () => {
  const socket = new DeferredSocket();
  const client = new HeadlessRconClient({ port: 25575, password: 'pw', socketFactory: () => socket });
  const connecting = client.connect();
  await client.close();
  await assert.rejects(connecting, error => error.code === 'RCON_CLOSED');
  assert.equal(socket.destroyed, true);
  await assert.rejects(() => client.connect(), error => error.code === 'RCON_CLOSED');
});

test('authentication failure closes the session and cannot reconnect on its socket', async () => {
  let connections = 0;
  const { server, port } = await startServer((socket, request) => {
    if (request.type === 3) socket.write(packet(-1, 2));
  });
  server.on('connection', () => { connections += 1; });
  const client = new HeadlessRconClient({ host: '127.0.0.1', port, password: 'bad' });
  try {
    await assert.rejects(() => client.connect(), error => error.code === 'RCON_AUTH_FAILED');
    assert.equal(client.state, 'closed');
    await assert.rejects(() => client.connect(), error => error.code === 'RCON_CLOSED');
    assert.equal(connections, 1);
  } finally { await client.close(); await stopServer(server); }
});

test('synchronous socket factory failure does not leave a connecting client', async () => {
  const client = new HeadlessRconClient({ port: 25575, password: 'pw', socketFactory: () => { throw new Error('factory failed'); } });
  await assert.rejects(() => client.connect(), error => error.code === 'RCON_CONNECT_FAILED');
  assert.equal(client.state, 'closed');
  await assert.rejects(() => client.connect(), error => error.code === 'RCON_CLOSED');
});

test('rejects packets without both RCON string terminators', async () => {
  const { server, port } = await startServer((socket, request) => {
    if (request.type === 3) socket.write(packet(request.id, 2));
    else {
      const malformed = packet(request.id, 0, 'oops');
      malformed[malformed.length - 1] = 1;
      socket.write(malformed);
    }
  });
  const client = new HeadlessRconClient({ host: '127.0.0.1', port, password: 'pw' });
  try {
    await client.connect();
    await assert.rejects(() => client.command('bad-terminator'), error => error.code === 'RCON_PROTOCOL');
  } finally { await client.close(); await stopServer(server); }
});
