import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const need = (ok, message) => { if (!ok) throw Error('Result binding: ' + message); };
const key = value => process.platform === 'win32' ? value.toLowerCase() : value;
const canonical = file => fs.realpathSync(file);
const within = (root, file) => {
  const relative = path.relative(key(root), key(file));
  return relative !== '' && relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative);
};
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const validRecord = (record, label) => need(record && Number.isSafeInteger(record.bytes) && record.bytes >= 0 &&
  typeof record.sha256 === 'string' && /^[a-f0-9]{64}$/i.test(record.sha256), label + ' requires bytes and SHA256');
const sameBounds = (left, right) => Array.isArray(left) && left.length === right.length &&
  left.every((value, i) => Number.isSafeInteger(value) && value === right[i]);

// Hash and size the same open file; memory stays bounded for multi-gigabyte runs.
function actualFile(file) {
  const actual = canonical(file), fd = fs.openSync(actual, 'r');
  try {
    const before = fs.fstatSync(fd);
    need(before.isFile(), 'expected regular file: ' + actual);
    const digest = crypto.createHash('sha256'), buffer = Buffer.alloc(1024 * 1024);
    let count;
    while ((count = fs.readSync(fd, buffer, 0, buffer.length, null)) > 0) digest.update(buffer.subarray(0, count));
    const after = fs.fstatSync(fd);
    need(before.size === after.size && before.mtimeMs === after.mtimeMs && before.ctimeMs === after.ctimeMs,
      'file changed during hashing: ' + actual);
    return {path: actual, bytes: before.size, sha256: digest.digest('hex')};
  } finally { fs.closeSync(fd); }
}
function match(record, actual, label) {
  validRecord(record, label);
  need(record.bytes === actual.bytes && record.sha256.toLowerCase() === actual.sha256, label + ' bytes/SHA256 mismatch: ' + actual.path);
}
function relativePath(root, name, label, directory = false) {
  need(typeof name === 'string' && name.length > 0 && !path.isAbsolute(name) &&
    !path.win32.isAbsolute(name) && !name.split(/[\\/]/).includes('..'), label + ' must be a contained relative path');
  const lexical = path.resolve(root, name);
  need(within(root, lexical), label + ' escapes root');
  const actual = canonical(lexical);
  need(within(root, actual), label + ' resolves outside root');
  if (directory) need(fs.statSync(actual).isDirectory(), label + ' must be a directory');
  return actual;
}
function add(map, record, label) {
  need(!map.has(key(record.path)), label + ' contains duplicate canonical path: ' + record.path);
  map.set(key(record.path), record);
}
function equalMaps(left, right, label) {
  need(left.size === right.size, label + ' file counts differ');
  for (const [name, value] of left) {
    const other = right.get(name);
    need(other && other.bytes === value.bytes && other.sha256 === value.sha256, label + ' path/bytes/SHA256 mismatch: ' + value.path);
  }
}

/** Recheck the actual files behind pinned writer/oracle evidence. Throws on any mismatch. */
export function validateResultBindings(receipt, validation, gateDirectory) {
  need(Array.isArray(receipt.coreOrigin) && receipt.coreOrigin.length === 2 && receipt.coreOrigin.every(Number.isSafeInteger) &&
    Number.isSafeInteger(receipt.coreSize) && receipt.coreSize > 0 && Number.isSafeInteger(receipt.halo) && receipt.halo >= 0,
    'integer core origin, positive core size and nonnegative halo required');
  const [x, z] = receipt.coreOrigin, size = receipt.coreSize, halo = receipt.halo;
  const coreBounds = [x, z, x + size, z + size], renderBounds = [x - halo, z - halo, x + size + halo, z + size + halo];
  need([...coreBounds, ...renderBounds].every(Number.isSafeInteger), 'bounds overflow');
  need(sameBounds(validation.comparisonBounds, coreBounds), 'comparison bounds differ from receipt core');
  need(typeof receipt.outputRoot === 'string' && path.isAbsolute(receipt.outputRoot), 'absolute measured output root required');
  const outputRoot = canonical(receipt.outputRoot);
  need(fs.statSync(outputRoot).isDirectory(), 'measured output root must be a directory');
  const evidence = validation.evidence;
  need(evidence?.writerManifest && evidence?.oracleProof && Array.isArray(evidence.sourceRuns) && evidence.sourceRuns.length > 0,
    'writer manifest, oracle proof and source run evidence required');
  const pinned = (record, label) => {
    need(record && typeof record.path === 'string' && record.path.length > 0 &&
      typeof record.sha256 === 'string' && /^[a-f0-9]{64}$/i.test(record.sha256), label + ' path/SHA256 required');
    const actual = actualFile(path.resolve(gateDirectory, record.path));
    need(actual.sha256 === record.sha256.toLowerCase(), label + ' evidence hash mismatch');
    if (record.bytes !== undefined) match(record, actual, label);
    return actual;
  };
  const manifestFile = pinned(evidence.writerManifest, 'writer manifest'), oracleFile = pinned(evidence.oracleProof, 'oracle proof');
  const manifest = read(manifestFile.path), writer = manifest.writer ?? manifest, oracle = read(oracleFile.path);
  need(writer.kind === 'global-block-run-world' && writer.schemaVersion === 1, 'recognized writer manifest required');
  need(sameBounds(writer.bounds, renderBounds), 'writer render bounds must equal exact core plus halo');
  need(oracle.status === 'PASS' && sameBounds(oracle.bounds, coreBounds) && Array.isArray(oracle.errors) && oracle.errors.length === 0,
    'oracle must PASS exact core with no errors');
  need(Array.isArray(writer.outputs) && writer.outputs.length > 0 && Array.isArray(writer.inputs) && writer.inputs.length > 0,
    'nonempty writer input/output maps required');
  need(oracle.worldSettings?.status === 'PASS' && Array.isArray(oracle.worldSettings.errors) && oracle.worldSettings.errors.length === 0 &&
    Array.isArray(oracle.worldSettings.files) && Array.isArray(oracle.worldFiles) && oracle.worldFiles.length > 0,
    'passing oracle world settings and file maps required');

  // Only the hash-pinned adapter job determines the tile and world location.
  need(receipt.jobSpecification && path.isAbsolute(receipt.jobSpecification.path ?? ''), 'absolute frozen job specification required');
  const jobFile = pinned(receipt.jobSpecification, 'job specification'), job = read(jobFile.path);
  need(Array.isArray(job.tiles) && job.tiles.length === 1, 'frozen job must contain exactly one tile');
  const tile = job.tiles[0];
  need(typeof tile.id === 'string' && /^[a-z0-9_-]+$/i.test(tile.id) &&
    sameBounds(tile.coreOrigin, receipt.coreOrigin) && tile.coreSize === size && tile.halo === halo,
    'frozen tile identity/core/halo mismatch');
  const tileRoot = relativePath(outputRoot, tile.id, 'frozen tile directory', true);
  const worldRoot = relativePath(tileRoot, 'world', 'frozen world directory', true);
  need(within(outputRoot, worldRoot), 'world root resolves outside measured output');
  need(key(manifestFile.path) === key(relativePath(tileRoot, 'writer-manifest.json', 'frozen writer manifest')),
    'writer manifest must be the frozen tile manifest');
  const writerRegions = relativePath(worldRoot, writer.regionDirectory, 'writer region directory', true);
  const oracleRegions = relativePath(worldRoot, oracle.worldSettings.regionDirectory, 'oracle region directory', true);
  need(key(writerRegions) === key(oracleRegions), 'oracle region directory differs from writer');
  const outputs = new Map();
  for (const record of writer.outputs) {
    const actual = actualFile(relativePath(worldRoot, record.path, 'writer output'));
    need(within(outputRoot, actual.path), 'writer output resolves outside measured output');
    match(record, actual, 'writer output');
    add(outputs, actual, 'writer outputs');
  }
  const actualWorldPaths = new Set();
  const visitWorld = directory => {
    for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
      const file = path.join(directory, entry.name);
      need(!entry.isSymbolicLink(), 'world files must not contain links: ' + file);
      if (entry.isDirectory()) visitWorld(file);
      else {
        need(entry.isFile(), 'world contains a non-regular file: ' + file);
        const actual = canonical(file);
        need(within(worldRoot, actual) && outputs.has(key(actual)), 'world file absent from writer outputs: ' + actual);
        need(!actualWorldPaths.has(key(actual)), 'world contains duplicate canonical file');
        actualWorldPaths.add(key(actual));
      }
    }
  };
  visitWorld(worldRoot);
  need(actualWorldPaths.size === outputs.size, 'writer/world file counts differ');
  const oracleOutputs = new Map();
  for (const record of oracle.worldFiles) {
    need(typeof record.path === 'string' && /^r\.-?\d+\.-?\d+\.mca$/.test(record.path), 'oracle region path must be an r.X.Z.mca basename');
    const actualPath = relativePath(oracleRegions, record.path, 'oracle region file');
    const actual = outputs.get(key(actualPath));
    need(actual, 'oracle region path absent from writer outputs: ' + actualPath);
    match(record, actual, 'oracle region');
    add(oracleOutputs, actual, 'oracle outputs');
  }
  for (const record of oracle.worldSettings.files) {
    const actualPath = relativePath(worldRoot, record.path, 'oracle metadata file'), actual = outputs.get(key(actualPath));
    need(actual, 'oracle metadata path absent from writer outputs: ' + actualPath);
    match(record, actual, 'oracle metadata');
    add(oracleOutputs, actual, 'oracle outputs');
  }
  equalMaps(outputs, oracleOutputs, 'oracle/writer output maps');

  const sources = new Map(), byName = new Map();
  for (const record of evidence.sourceRuns) {
    const actual = pinned(record, 'source run'), name = key(path.basename(actual.path));
    add(sources, actual, 'source evidence');
    need(!byName.has(name), 'source evidence has ambiguous duplicate input names');
    byName.set(name, actual);
  }
  const writerSources = new Map();
  for (const record of writer.inputs) {
    need(typeof record.name === 'string' && record.name.length > 0 && !/[\\/]/.test(record.name), 'writer input requires basename');
    const actual = byName.get(key(record.name));
    need(actual, 'writer input absent from source evidence: ' + record.name);
    need(key(actual.path) === key(relativePath(tileRoot, record.name, 'frozen writer input')),
      'writer input source must be the frozen tile file: ' + record.name);
    match(record, actual, 'writer input');
    add(writerSources, actual, 'writer inputs');
  }
  equalMaps(sources, writerSources, 'writer/source evidence maps');
  need(Array.isArray(oracle.sourceFiles), 'oracle source file map required');
  const oracleSources = new Map();
  for (const record of oracle.sourceFiles) {
    need(typeof record.path === 'string' && path.isAbsolute(record.path), 'oracle source requires actual absolute path');
    const actualPath = canonical(record.path), actual = sources.get(key(actualPath));
    need(actual, 'oracle source path absent from source evidence: ' + actualPath);
    match(record, actual, 'oracle source');
    add(oracleSources, actual, 'oracle sources');
  }
  equalMaps(sources, oracleSources, 'oracle/source evidence maps');
  return {worldRoot, coreBounds, renderBounds, writerManifestPath: manifestFile.path, oracleProofPath: oracleFile.path,
    outputs: [...outputs.values()], sources: [...sources.values()]};
}
