import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
import test from 'node:test';
import { canonicalJson, fingerprintJson, fingerprintFile, runIdFor, queueFingerprints, ensureRun, createAttempt, commitAttempt, verifyAttempt, cloneAttempt } from './index.mjs';

const descriptor = () => ({
  schema: 1,
  inputs: { source_sha256: 'a'.repeat(64), mask_sha256: 'b'.repeat(64), ancillary_sha256: { palette: 'c'.repeat(64), terrain: 'd'.repeat(64) } },
  generator: { code_sha256: 'e'.repeat(64), config_sha256: 'f'.repeat(64) },
  spatial: { projection_sha256: '1'.repeat(64), datum_sha256: '2'.repeat(64) },
});
const testParent = path.resolve(process.env.FORK_NAMESPACE_TEST_ROOT || path.join(os.tmpdir(), 'fork-namespace-tests'));
fs.mkdirSync(testParent, { recursive: true });
function fixture(t) {
  const root = fs.mkdtempSync(path.join(testParent, 'case-'));
  t.after(() => {
    const rel = path.relative(testParent, path.resolve(root));
    assert.ok(rel.startsWith('case-') && !rel.includes(path.sep) && !rel.includes('..'));
    fs.rmSync(root, { recursive: true });
  });
  const run = ensureRun(root, descriptor());
  return { root, run, attempt: job => createAttempt(root, run.run_id, job) };
}
function writeOutput(attempt, text = 'nbt fixture') {
  fs.mkdirSync(path.join(attempt.output_dir, 'region'));
  fs.writeFileSync(path.join(attempt.output_dir, 'region', 'r.0.0.mca'), text);
}

test('canonical key order, digest case and host-independent file content give stable identity', t => {
  const { root } = fixture(t);
  assert.equal(canonicalJson({ z: 1, a: { b: 3, a: 2 } }), '{"a":{"a":2,"b":3},"z":1}');
  const original = descriptor();
  const reordered = { spatial: original.spatial, generator: original.generator, inputs: { ancillary_sha256: { terrain: 'D'.repeat(64), palette: 'C'.repeat(64) }, mask_sha256: 'B'.repeat(64), source_sha256: 'A'.repeat(64) }, schema: 1 };
  assert.equal(runIdFor(original), runIdFor(reordered));
  const p = path.join(root, 'machine-one.json');
  const q = path.join(root, 'machine-two.json');
  fs.writeFileSync(p, 'same bytes'); fs.writeFileSync(q, 'same bytes');
  assert.equal(fingerprintFile(p), fingerprintFile(q));
  assert.throws(() => runIdFor({ ...original, source_path: 'C:\\cache\\source.json' }), /unknown fields/);
});

test('each source, mask, ancillary, code, config, projection and datum change makes a distinct run', () => {
  const variants = [
    d => d.inputs.source_sha256 = '3'.repeat(64),
    d => d.inputs.mask_sha256 = '3'.repeat(64),
    d => d.inputs.ancillary_sha256.terrain = '3'.repeat(64),
    d => d.generator.code_sha256 = '3'.repeat(64),
    d => d.generator.config_sha256 = '3'.repeat(64),
    d => d.spatial.projection_sha256 = '3'.repeat(64),
    d => d.spatial.datum_sha256 = '3'.repeat(64),
  ];
  const ids = [runIdFor(descriptor())];
  for (const change of variants) { const d = descriptor(); change(d); ids.push(runIdFor(d)); }
  assert.equal(new Set(ids).size, 8);
});

test('reject missing fingerprints, host paths as ancillary labels and non-JSON values', () => {
  const d = descriptor(); delete d.spatial.datum_sha256;
  assert.throws(() => runIdFor(d), /missing/);
  const q = descriptor(); q.inputs.ancillary_sha256['C:/palette'] = 'a'.repeat(64);
  assert.throws(() => runIdFor(q), /logical name/);
  assert.throws(() => fingerprintJson({ n: Infinity }), /finite JSON/);
});

test('existing run reopened without replacing metadata; corrupt identity rejected', t => {
  const { root, run } = fixture(t);
  const manifest = path.join(run.run_dir, 'run.json');
  const before = fs.readFileSync(manifest);
  assert.equal(ensureRun(root, descriptor()).run_id, run.run_id);
  assert.deepEqual(fs.readFileSync(manifest), before);
  const corrupted = JSON.parse(before); corrupted.descriptor.generator.code_sha256 = '4'.repeat(64);
  fs.writeFileSync(manifest, JSON.stringify(corrupted));
  assert.throws(() => ensureRun(root, descriptor()), /collision/);
  assert.equal(JSON.parse(fs.readFileSync(manifest)).descriptor.generator.code_sha256, '4'.repeat(64));
});

test('incomplete/foreign run directory is not adopted', t => {
  const { root } = fixture(t);
  const changed = descriptor(); changed.generator.code_sha256 = '3'.repeat(64);
  const destination = path.join(root, 'run-' + runIdFor(changed));
  fs.mkdirSync(destination);
  fs.writeFileSync(path.join(destination, 'old-output.txt'), 'do not overwrite');
  assert.throws(() => ensureRun(root, changed), /Incomplete or foreign/);
  assert.equal(fs.readFileSync(path.join(destination, 'old-output.txt'), 'utf8'), 'do not overwrite');
});

test('attempts have exclusive output/checkpoint directories and canonical job dependencies', t => {
  const { root, run, attempt } = fixture(t);
  const first = attempt('tile-1');
  const second = createAttempt(root, run.run_id, 'tile-1', { depends_on: ['mask', 'source', 'mask'] });
  assert.notEqual(first.attempt_id, second.attempt_id);
  assert.notEqual(first.output_dir, second.output_dir);
  assert.notEqual(first.checkpoint, second.checkpoint);
  assert.deepEqual(second.depends_on, ['mask', 'source']);
  assert.throws(() => attempt('../escape'), /Invalid job/);
  assert.throws(() => createAttempt(root, run.run_id, 'tile-1', { depends_on: ['tile-1'] }), /dependencies/);
});

test('commit and restart from a separate Node process verify unchanged cached bytes', t => {
  const { root, run, attempt } = fixture(t);
  const a = attempt('tile-1'); writeOutput(a);
  const result = commitAttempt(root, run.run_id, a.attempt_id);
  assert.equal(commitAttempt(root, run.run_id, a.attempt_id).output_sha256, result.output_sha256);
  const cli = fileURLToPath(new URL('./index.mjs', import.meta.url));
  const restart = spawnSync(process.execPath, [cli, 'verify', root, run.run_id, a.attempt_id], { encoding: 'utf8', windowsHide: true });
  assert.equal(restart.status, 0, restart.stderr);
  assert.equal(JSON.parse(restart.stdout).output_sha256, result.output_sha256);
});

test('changed source creates a separate run and old attempts cannot be used in it', t => {
  const { root, run, attempt } = fixture(t);
  const a = attempt('tile-1'); writeOutput(a); commitAttempt(root, run.run_id, a.attempt_id);
  const d = descriptor(); d.inputs.source_sha256 = '5'.repeat(64);
  const changed = ensureRun(root, d);
  assert.notEqual(changed.run_dir, run.run_dir);
  assert.throws(() => verifyAttempt(root, changed.run_id, a.attempt_id));
  assert.equal(verifyAttempt(root, run.run_id, a.attempt_id).files.length, 1);
});

test('modified, missing and additional files all reject cache without overwriting completion', t => {
  const { root, run, attempt } = fixture(t);
  for (const kind of ['modified', 'missing', 'extra']) {
    const a = attempt(kind); writeOutput(a); commitAttempt(root, run.run_id, a.attempt_id);
    const complete = path.join(a.attempt_dir, 'complete.json'); const before = fs.readFileSync(complete);
    const output = path.join(a.output_dir, 'region', 'r.0.0.mca');
    if (kind === 'modified') fs.writeFileSync(output, 'changed fixture');
    if (kind === 'missing') fs.unlinkSync(output);
    if (kind === 'extra') fs.writeFileSync(path.join(a.output_dir, 'extra.txt'), 'unknown');
    assert.throws(() => verifyAttempt(root, run.run_id, a.attempt_id), /Stale/);
    assert.throws(() => commitAttempt(root, run.run_id, a.attempt_id), /collision|empty/);
    assert.deepEqual(fs.readFileSync(complete), before);
  }
});

test('clone verifies bytes and makes a fresh copy; source remains immutable', t => {
  const { root, run, attempt } = fixture(t);
  const a = attempt('tile-1'); writeOutput(a); const original = commitAttempt(root, run.run_id, a.attempt_id);
  const clone = cloneAttempt(root, run.run_id, a.attempt_id);
  assert.notEqual(clone.output_dir, a.output_dir);
  assert.equal(clone.output_sha256, original.output_sha256);
  assert.equal(verifyAttempt(root, run.run_id, clone.attempt_id).files.length, 1);
  fs.writeFileSync(path.join(clone.output_dir, 'region', 'r.0.0.mca'), 'changed clone');
  assert.equal(verifyAttempt(root, run.run_id, a.attempt_id).output_sha256, original.output_sha256);
  assert.throws(() => cloneAttempt(root, run.run_id, clone.attempt_id), /Stale/);
});

test('interrupted attempt cannot masquerade as complete; restart creates fresh attempt', t => {
  const { root, run, attempt } = fixture(t);
  const interrupted = attempt('tile-1'); writeOutput(interrupted);
  fs.writeFileSync(interrupted.checkpoint, '{"progress":42}');
  assert.throws(() => verifyAttempt(root, run.run_id, interrupted.attempt_id));
  const restarted = attempt('tile-1');
  assert.deepEqual(fs.readdirSync(restarted.output_dir), []);
  assert.equal(fs.existsSync(restarted.checkpoint), false);
  assert.equal(fs.readFileSync(interrupted.checkpoint, 'utf8'), '{"progress":42}');
});

test('output junction/symlink rejected instead of traversing another run', t => {
  const { root, run, attempt } = fixture(t);
  const a = attempt('tile-1');
  const outside = path.join(root, 'outside'); fs.mkdirSync(outside); fs.writeFileSync(path.join(outside, 'secret.txt'), 'foreign data');
  fs.symlinkSync(outside, path.join(a.output_dir, 'linked'), process.platform === 'win32' ? 'junction' : 'dir');
  assert.throws(() => commitAttempt(root, run.run_id, a.attempt_id), /Symlinks/);
});
test('queue binding includes mask, ancillary, projection and datum in configuration fingerprint', () => {
  const first = queueFingerprints(descriptor());
  assert.deepEqual(Object.keys(first).sort(), ['codeSha256', 'configSha256', 'sourceSha256']);
  for (const change of [
    d => d.inputs.mask_sha256 = '3'.repeat(64),
    d => d.inputs.ancillary_sha256.palette = '3'.repeat(64),
    d => d.spatial.projection_sha256 = '3'.repeat(64),
    d => d.spatial.datum_sha256 = '3'.repeat(64),
  ]) {
    const d = descriptor(); change(d);
    const next = queueFingerprints(d);
    assert.notEqual(next.configSha256, first.configSha256);
    assert.equal(next.sourceSha256, first.sourceSha256);
    assert.equal(next.codeSha256, first.codeSha256);
  }
  assert.throws(() => fingerprintJson(new Date()), /finite JSON/);
});