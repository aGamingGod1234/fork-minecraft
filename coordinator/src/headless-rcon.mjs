import net from 'node:net';

const DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
const DEFAULT_COMMAND_TIMEOUT_MS = 10_000;
const DEFAULT_MAX_RESPONSE_BYTES = 64 * 1024;
const MAX_PACKET_BYTES = 1024 * 1024;

class RconError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'RconError';
    this.code = code;
  }
}

function boundedUtf8(value, maxBytes) {
  const text = String(value ?? '');
  const bytes = Buffer.from(text, 'utf8');
  if (bytes.length <= maxBytes) return text;
  return bytes.subarray(0, maxBytes).toString('utf8');
}

function frame(id, type, text) {
  const body = Buffer.from(String(text ?? ''), 'utf8');
  const payload = Buffer.allocUnsafe(10 + body.length);
  payload.writeInt32LE(id, 0);
  payload.writeInt32LE(type, 4);
  body.copy(payload, 8);
  payload[8 + body.length] = 0;
  payload[9 + body.length] = 0;
  const packet = Buffer.allocUnsafe(payload.length + 4);
  packet.writeInt32LE(payload.length, 0);
  payload.copy(packet, 4);
  return packet;
}

export class HeadlessRconClient {
  constructor({ host = '127.0.0.1', port, password, connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS,
    commandTimeoutMs = DEFAULT_COMMAND_TIMEOUT_MS, maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES,
    socketFactory = null } = {}) {
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new TypeError('RCON port must be valid');
    this.host = host;
    this.port = port;
    this.password = String(password ?? '');
    this.connectTimeoutMs = connectTimeoutMs;
    this.commandTimeoutMs = commandTimeoutMs;
    this.maxResponseBytes = maxResponseBytes;
    this.socketFactory = socketFactory;
    this.socket = null;
    this.state = 'new';
    this.nextId = 1;
    this.pending = new Map();
    this.buffer = Buffer.alloc(0);
    this.connectPromise = null;
    this.connectReject = null;
  }

  async connect() {
    if (this.state === 'authenticated') return this;
    if (this.connectPromise) return this.connectPromise;
    if (this.state === 'closed') throw new RconError('RCON_CLOSED', 'RCON client is closed');
    this.state = 'connecting';
    this.connectPromise = new Promise((resolve, reject) => {
      let settled = false;
      let timer = null;
      const fail = error => {
        if (settled) return;
        settled = true;
        if (timer) clearTimeout(timer);
        if (this.connectReject === fail) this.connectReject = null;
        reject(error);
      };
      const succeed = () => {
        if (settled) return;
        settled = true;
        if (timer) clearTimeout(timer);
        if (this.connectReject === fail) this.connectReject = null;
        resolve(this);
      };
      this.connectReject = fail;
      let socket;
      try {
        socket = this.socketFactory ? this.socketFactory() : net.createConnection({ host: this.host, port: this.port });
      } catch (error) {
        this.state = 'closed';
        fail(new RconError('RCON_CONNECT_FAILED', error.message || 'RCON socket creation failed'));
        return;
      }
      this.socket = socket;
      timer = setTimeout(() => {
        const error = new RconError('RCON_TIMEOUT', 'RCON connection timed out');
        this.state = 'closed';
        fail(error);
        this._rejectAll(error);
        this._shutdownSocket();
      }, this.connectTimeoutMs);
      const onConnect = async () => {
        if (this.state === 'closed') return;
        try {
          const response = await this._request(3, this.password);
          if (response.id === -1) throw new RconError('RCON_AUTH_FAILED', 'RCON authentication failed');
          if (this.state === 'closed') throw new RconError('RCON_CLOSED', 'RCON client closed');
          this.state = 'authenticated';
          succeed();
        } catch (error) {
          if (this.state !== 'closed') {
            this.state = 'closed';
            this._shutdownSocket();
          }
          fail(error);
        }
      };
      socket.on('connect', onConnect);
      socket.on('data', chunk => this._receive(chunk));
      socket.on('error', error => {
        const wrapped = new RconError('RCON_CLOSED', error.message || 'RCON socket error');
        this._rejectAll(wrapped);
        this.state = 'closed';
        fail(wrapped);
        this._shutdownSocket();
      });
      socket.on('close', () => {
        const error = new RconError('RCON_CLOSED', 'RCON socket closed');
        this._rejectAll(error);
        this.state = 'closed';
        fail(error);
      });
      if (socket.readyState === 'open') onConnect();
    }).finally(() => { this.connectPromise = null; });
    return this.connectPromise;
  }

  command(text) {
    if (this.state !== 'authenticated') return Promise.reject(new RconError('RCON_NOT_AUTHENTICATED', 'RCON client is not authenticated'));
    return this._request(2, text).then(response => ({ id: response.id, type: response.type, text: response.text }));
  }

  async close() {
    if (this.state === 'closed' && !this.socket && !this.connectReject) return;
    const error = new RconError('RCON_CLOSED', 'RCON client closed');
    this.state = 'closed';
    this._rejectAll(error);
    if (this.connectReject) this.connectReject(error);
    this._shutdownSocket();
  }

  _request(type, text) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new RconError('RCON_TIMEOUT', 'RCON command timed out'));
      }, this.commandTimeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      try { this.socket.write(frame(id, type, text)); } catch (error) {
        clearTimeout(timer); this.pending.delete(id); reject(error);
      }
    });
  }

  _receive(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 4) {
      const length = this.buffer.readInt32LE(0);
      if (length < 10 || length > MAX_PACKET_BYTES) {
        this._rejectAll(new RconError('RCON_PROTOCOL', 'Malformed RCON packet length'));
        this.close();
        return;
      }
      if (this.buffer.length < length + 4) return;
      const payload = this.buffer.subarray(4, length + 4);
      this.buffer = this.buffer.subarray(length + 4);
      const id = payload.readInt32LE(0);
      const type = payload.readInt32LE(4);
      if (payload[payload.length - 1] !== 0 || payload[payload.length - 2] !== 0) {
        this._rejectAll(new RconError('RCON_PROTOCOL', 'Malformed RCON string terminators'));
        this.close();
        return;
      }
      const text = boundedUtf8(payload.subarray(8, payload.length - 2).toString('utf8'), this.maxResponseBytes);
      const pending = this.pending.get(id) ?? (id === -1 && this.pending.size === 1 ? this.pending.values().next().value : null);
      if (pending) {
        for (const [pendingId, value] of this.pending) {
          if (value === pending) this.pending.delete(pendingId);
        }
        clearTimeout(pending.timer);
        pending.resolve({ id, type, text });
      }
    }
  }

  _rejectAll(error) {
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(error);
      this.pending.delete(id);
    }
  }

  _shutdownSocket() {
    const socket = this.socket;
    this.socket = null;
    if (socket) {
      socket.removeAllListeners('data');
      socket.destroy();
    }
  }
}

export function normalizeRconResult(result, maxBytes = DEFAULT_MAX_RESPONSE_BYTES) {
  const text = String(result?.text ?? '');
  return { text, success: result?.success !== false, boundedText: boundedUtf8(text, maxBytes) };
}

export { RconError };
