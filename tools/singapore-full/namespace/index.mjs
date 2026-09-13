import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

const SCHEMA = 1;
const HEX = /^[0-9a-f]{64}$/i;
const LABEL = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,159}$/;
const fail = message => { throw new Error(message); };
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const plain = value => value !== null && typeof value === 'object' && (Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null);
const equal = (a, b) => canonicalJson(a) === canonicalJson(b);

/** Object keys use binary ordering; absolute host paths never enter a run descriptor. */
export function canonicalJson(value) {
  if (value === null || typeof value === 'boolean' || typeof value === 'string') return JSON.stringify(value);
  if (typeof value === 'number' && Number.isFinite(value)) return JSON.stringify(value);
  if (Array.isArray(value)) return '[' + value.map(canonicalJson).join(',') + ']';
  if (plain(value)) return '{' + Object.keys(value).sort().map(k => JSON.stringify(k) + ':' + canonicalJson(value[k])).join(',') + '}';
  fail('Identity must contain finite JSON values only');
}
export const fingerprintJson = value => sha(canonicalJson(value));
export function fingerprintFile(file) {
  const stat = fs.lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink()) fail('Fingerprint requires a regular file: ' + file);
  const hash = crypto.createHash('sha256');
  const fd = fs.openSync(file, 'r');
  try {
    const buffer = Buffer.allocUnsafe(1024 * 1024);
    let count;
    while ((count = fs.readSync(fd, buffer, 0, buffer.length, null)) > 0) hash.update(buffer.subarray(0, count));
  } finally { fs.closeSync(fd); }
  const after = fs.lstatSync(file);
  if (after.size !== stat.size || after.mtimeMs !== stat.mtimeMs || after.ino !== stat.ino) fail('File changed during hashing: ' + file);
  return hash.digest('hex');
}
function keys(value, required, label) {
  if (!plain(value) || !equal(Object.keys(value).sort(), [...required].sort())) fail(label + ' has missing or unknown fields');
}
function digest(value, label) {
  if (typeof value !== 'string' || !HEX.test(value)) fail(label + ' must be a SHA256 hex digest');
  return value.toLowerCase();
}
export function normalizeDescriptor(value) {
  keys(value, ['schema', 'inputs', 'generator', 'spatial'], 'descriptor');
  if (value.schema !== SCHEMA) fail('Unsupported descriptor schema');
  keys(value.inputs, ['source_sha256', 'mask_sha256', 'ancillary_sha256'], 'inputs');
  keys(value.generator, ['code_sha256', 'config_sha256'], 'generator');
  keys(value.spatial, ['projection_sha256', 'datum_sha256'], 'spatial');
  if (!plain(value.inputs.ancillary_sha256)) fail('ancillary_sha256 must map logical names to hashes');
  const ancillary = {};
  for (const name of Object.keys(value.inputs.ancillary_sha256).sort()) {
    if (!LABEL.test(name) || ['__proto__', 'prototype', 'constructor'].includes(name)) fail('Invalid ancillary logical name: ' + name);
    ancillary[name] = digest(value.inputs.ancillary_sha256[name], name);
  }
  return {
    schema: SCHEMA,
    inputs: { source_sha256: digest(value.inputs.source_sha256, 'source'), mask_sha256: digest(value.inputs.mask_sha256, 'mask'), ancillary_sha256: ancillary },
    generator: { code_sha256: digest(value.generator.code_sha256, 'code'), config_sha256: digest(value.generator.config_sha256, 'config') },
    spatial: { projection_sha256: digest(value.spatial.projection_sha256, 'projection'), datum_sha256: digest(value.spatial.datum_sha256, 'datum') },
  };
}
export const runIdFor = descriptor => fingerprintJson(normalizeDescriptor(descriptor));
/** Bind the queue's three fingerprints to every run input, not just the OSM bytes. */
export function queueFingerprints(input) {
  const descriptor = normalizeDescriptor(input);
  return {
    sourceSha256: descriptor.inputs.source_sha256,
    codeSha256: descriptor.generator.code_sha256,
    configSha256: fingerprintJson({ inputs: descriptor.inputs, config_sha256: descriptor.generator.config_sha256, spatial: descriptor.spatial }),
  };
}

function directory(dir, create = false) {
  if (create) fs.mkdirSync(dir, { recursive: true });
  const stat = fs.lstatSync(dir);
  if (!stat.isDirectory() || stat.isSymbolicLink()) fail('Expected an ordinary directory: ' + dir);
  return dir;
}
function readJson(file) {
  if (!fs.lstatSync(file).isFile() || fs.lstatSync(file).isSymbolicLink()) fail('Expected ordinary manifest file');
  return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
}
// Link a fully flushed temporary file into place. A competing writer can never replace it.
function immutableJson(file, value) {
  const bytes = canonicalJson(value) + '\n';
  const temporary = file + '.tmp-' + crypto.randomUUID();
  const fd = fs.openSync(temporary, 'wx');
  try { fs.writeFileSync(fd, bytes); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
  try { fs.linkSync(temporary, file); }
  catch (error) {
    if (error.code !== 'EEXIST') throw error;
    if (!equal(readJson(file), value)) fail('Immutable manifest collision: ' + file);
  } finally { fs.unlinkSync(temporary); }
}
function runPath(root, runId) {
  if (typeof runId !== 'string' || !/^[0-9a-f]{64}$/.test(runId)) fail('Invalid run ID');
  return path.join(path.resolve(root), 'run-' + runId);
}
function openRun(root, runId) {
  directory(path.resolve(root));
  const runDir = directory(runPath(root, runId));
  const manifest = readJson(path.join(runDir, 'run.json'));
  const descriptor = normalizeDescriptor(manifest.descriptor);
  if (!equal(manifest, { schema: SCHEMA, run_id: runId, descriptor }) || runIdFor(descriptor) !== runId) fail('Run identity collision or stale manifest');
  return { run_id: runId, run_dir: runDir, descriptor };
}
export function ensureRun(root, input) {
  const descriptor = normalizeDescriptor(input);
  const runId = runIdFor(descriptor);
  directory(path.resolve(root), true);
  const runDir = runPath(root, runId);
  let created = false;
  try { fs.mkdirSync(runDir); created = true; }
  catch (error) { if (error.code !== 'EEXIST') throw error; }
  directory(runDir);
  if (!created && !fs.existsSync(path.join(runDir, 'run.json'))) fail('Incomplete or foreign run directory; refusing to adopt it');
  immutableJson(path.join(runDir, 'run.json'), { schema: SCHEMA, run_id: runId, descriptor });
  const opened = openRun(root, runId);
  directory(path.join(runDir, 'attempts'), true);
  return opened;
}
function attemptPath(runDir, attemptId) {
  if (typeof attemptId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(attemptId)) fail('Invalid attempt ID');
  return path.join(runDir, 'attempts', attemptId);
}
function attemptResult(run, manifest, attemptDir) {
  return { ...manifest, run_dir: run.run_dir, attempt_dir: attemptDir, output_dir: path.join(attemptDir, 'output'), checkpoint: path.join(attemptDir, 'checkpoint.json') };
}
export function createAttempt(root, runId, jobId, options = {}) {
  const run = openRun(root, runId);
  if (typeof jobId !== 'string' || !LABEL.test(jobId)) fail('Invalid job ID');
  const dependencies = options.depends_on ?? [];
  if (!Array.isArray(dependencies) || dependencies.some(id => typeof id !== 'string' || !LABEL.test(id) || id === jobId)) fail('Invalid job dependencies');
  const attemptId = crypto.randomUUID();
  const attempts = directory(path.join(run.run_dir, 'attempts'));
  const attemptDir = path.join(attempts, attemptId);
  fs.mkdirSync(attemptDir);
  fs.mkdirSync(path.join(attemptDir, 'output'));
  const manifest = { schema: SCHEMA, run_id: runId, attempt_id: attemptId, job_id: jobId, depends_on: [...new Set(dependencies)].sort() };
  immutableJson(path.join(attemptDir, 'attempt.json'), manifest);
  return attemptResult(run, manifest, attemptDir);
}
function openAttempt(root, runId, attemptId) {
  const run = openRun(root, runId);
  directory(path.join(run.run_dir, 'attempts'));
  const attemptDir = directory(attemptPath(run.run_dir, attemptId));
  const manifest = readJson(path.join(attemptDir, 'attempt.json'));
  keys(manifest, ['schema', 'run_id', 'attempt_id', 'job_id', 'depends_on'], 'attempt');
  if (manifest.schema !== SCHEMA || manifest.run_id !== runId || manifest.attempt_id !== attemptId || !LABEL.test(manifest.job_id) || !Array.isArray(manifest.depends_on) || manifest.depends_on.some(id => typeof id !== 'string' || !LABEL.test(id) || id === manifest.job_id) || !equal(manifest.depends_on, [...new Set(manifest.depends_on)].sort())) fail('Stale or invalid attempt manifest');
  directory(path.join(attemptDir, 'output'));
  return attemptResult(run, manifest, attemptDir);
}
function scanOutput(outputDir) {
  const files = [];
  const portableNames = new Set();
  function walk(dir, relative = '') {
    for (const name of fs.readdirSync(dir).sort()) {
      if (/[\\:\x00-\x1f]/.test(name) || /[. ]$/.test(name)) fail('Non-portable output path: ' + name);
      const rel = relative ? relative + '/' + name : name;
      const portable = rel.normalize('NFC').toLowerCase();
      if (portableNames.has(portable)) fail('Case/Unicode output path collision: ' + rel);
      portableNames.add(portable);
      const absolute = path.join(dir, name);
      const stat = fs.lstatSync(absolute);
      if (stat.isSymbolicLink()) fail('Symlinks/reparse links cannot be committed: ' + rel);
      if (stat.isDirectory()) walk(absolute, rel);
      else if (stat.isFile()) files.push({ path: rel, bytes: stat.size, sha256: fingerprintFile(absolute) });
      else fail('Non-regular output entry: ' + rel);
    }
  }
  walk(directory(outputDir));
  return files.sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0);
}
export function commitAttempt(root, runId, attemptId) {
  const attempt = openAttempt(root, runId, attemptId);
  const files = scanOutput(attempt.output_dir);
  if (!files.length) fail('Cannot commit empty output');
  const result = { schema: SCHEMA, run_id: runId, attempt_id: attemptId, job_id: attempt.job_id, files, output_sha256: fingerprintJson(files) };
  immutableJson(path.join(attempt.attempt_dir, 'complete.json'), result);
  return { ...attempt, ...result };
}
export function verifyAttempt(root, runId, attemptId) {
  const attempt = openAttempt(root, runId, attemptId);
  const actual = scanOutput(attempt.output_dir);
  const committed = readJson(path.join(attempt.attempt_dir, 'complete.json'));
  const expected = { schema: SCHEMA, run_id: runId, attempt_id: attemptId, job_id: attempt.job_id, files: actual, output_sha256: fingerprintJson(actual) };
  if (!actual.length || !equal(committed, expected)) fail('Stale, modified, missing or extra output; cached attempt rejected');
  return { ...attempt, ...committed };
}
/** Reuse is a fresh exclusive copy within exactly the same run and logical job. */
export function cloneAttempt(root, runId, sourceAttemptId) {
  const source = verifyAttempt(root, runId, sourceAttemptId);
  const target = createAttempt(root, runId, source.job_id, { depends_on: source.depends_on });
  for (const file of source.files) {
    const destination = path.join(target.output_dir, ...file.path.split('/'));
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.copyFileSync(path.join(source.output_dir, ...file.path.split('/')), destination, fs.constants.COPYFILE_EXCL);
  }
  const copied = scanOutput(target.output_dir);
  if (!equal(source.files, copied)) fail('Source changed during clone; new attempt remains uncommitted');
  immutableJson(path.join(target.attempt_dir, 'reused-from.json'), { schema: SCHEMA, run_id: runId, attempt_id: sourceAttemptId, output_sha256: source.output_sha256 });
  return commitAttempt(root, runId, target.attempt_id);
}

function main(args) {
  const [command, root, a, b, c] = args;
  let result;
  if (command === 'id' && root && args.length === 2) result = { run_id: runIdFor(readJson(root)) };
  else if (command === 'init' && root && a && args.length === 3) result = ensureRun(root, readJson(a));
  else if (command === 'attempt' && root && a && b && args.length <= 5) result = createAttempt(root, a, b, c ? { depends_on: readJson(c) } : {});
  else if (command === 'commit' && root && a && b && args.length === 4) result = commitAttempt(root, a, b);
  else if (command === 'verify' && root && a && b && args.length === 4) result = verifyAttempt(root, a, b);
  else if (command === 'clone' && root && a && b && args.length === 4) result = cloneAttempt(root, a, b);
  else if (command === 'hash-file' && root && args.length === 2) result = { sha256: fingerprintFile(root) };
  else if (command === 'hash-json' && root && args.length === 2) result = { sha256: fingerprintJson(readJson(root)) };
  else fail('Usage: index.mjs id descriptor.json | init ROOT descriptor.json | attempt ROOT RUN_ID JOB_ID [dependencies.json] | commit|verify|clone ROOT RUN_ID ATTEMPT_ID | hash-file|hash-json FILE');
  process.stdout.write(JSON.stringify(result, null, 2) + '\n');
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try { main(process.argv.slice(2)); }
  catch (error) { process.stderr.write(JSON.stringify({ error: error.message }) + '\n'); process.exitCode = 1; }
}