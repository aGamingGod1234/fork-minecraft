import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import os from 'node:os';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {validateResultBindings} from './validate-benchmark-bindings.mjs';

const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const sorted = value => value && typeof value === 'object' ? Array.isArray(value) ? value.map(sorted) :
  Object.fromEntries(Object.keys(value).sort().map(k => [k, sorted(value[k])])) : value;
export const canonicalJson = value => JSON.stringify(sorted(value));
const stable = canonicalJson;
const need = (ok, message) => { if (!ok) throw Error(message); };
const classes = ['dense', 'rural', 'coast'];
const GiB = 1024 ** 3;
const fileHash = file => {
  const digest = crypto.createHash('sha256'), fd = fs.openSync(file, 'r'), buffer = Buffer.alloc(1024 * 1024);
  try { let count; while ((count = fs.readSync(fd, buffer, 0, buffer.length, null)) > 0) digest.update(buffer.subarray(0, count)); }
  finally { fs.closeSync(fd); }
  return digest.digest('hex');
};
export function inventory(input, hashContents = false) {
  const files = [], visited = new Set();
  const walk = file => {
    const real = fs.realpathSync(file);
    if (visited.has(real)) return;
    visited.add(real);
    const stat = fs.lstatSync(file);
    need(!stat.isSymbolicLink(), 'Source/output inventories reject links: ' + file);
    if (stat.isDirectory()) for (const child of fs.readdirSync(file).sort()) walk(path.join(file, child));
    else if (stat.isFile()) files.push({path: real, bytes: stat.size, ...(hashContents ? {sha256: fileHash(file)} : {})});
  };
  for (const file of input) walk(file);
  return {bytes: files.reduce((sum, f) => sum + f.bytes, 0), files: files.length, contentHashed: hashContents,
    inventorySha256: hash(stable(files))};
}
export function validateAdmission(config, root) {
  if (config.admissionEvidence) {
    const gatePath = path.resolve(root, config.admissionEvidence), gate = read(gatePath);
    need(config.admissionEvidenceSha256 === fileHash(gatePath), 'Admission evidence hash mismatch');
    need(gate.schemaVersion === 1 && gate.kind === 'fork-experimental-benchmark-admission' &&
      gate.scope === 'pilot-policy' && gate.status === 'PASS' && gate.approvedBy && gate.acceptedByCoordinator === true,
      'Explicit coordinator-approved pilot-policy admission required');
    need(gate.independentValidationRequired === true && gate.productionAccepted === false,
      'Pilot admission cannot confer production or per-case acceptance');
    need(Array.isArray(gate.caseBindings) && gate.caseBindings.length > 0 && gate.caseBindings.length <= 3 &&
      new Set(gate.caseBindings.map(b => b.id)).size === gate.caseBindings.length, 'Pilot scope allows at most three unique case IDs');
    for (const name of ['roadsV2', 'streamingEquivalence']) {
      const proof = gate.qualityProofs?.[name];
      need(proof?.path && proof.sha256, 'Missing focused policy proof: ' + name);
      const proofPath = path.resolve(path.dirname(gatePath), proof.path);
      need(fileHash(proofPath) === proof.sha256 && read(proofPath).status === 'PASS', 'Focused policy proof must match and PASS: ' + name);
    }
    return {scope: 'pilot-policy', gate, gateSha256: config.admissionEvidenceSha256};
  }
  const gatePath = path.resolve(root, config.seamEvidence ?? ''), gate = read(gatePath);
  need(gate.status === 'PASS' && gate.comparedCells > 0 && gate.mismatchedCells === 0,
    'Seam gate must PASS with comparedCells > 0 and mismatchedCells = 0');
  need(config.seamEvidenceSha256 === fileHash(gatePath), 'Seam evidence hash mismatch');
  return {scope: 'same-source-seam', gate, gateSha256: config.seamEvidenceSha256};
}
export function validateConfig(config, root, now = Date.now()) {
  need(config.schemaVersion === 1, 'schemaVersion must be 1');
  need(config.mode === 'real', 'Only mode real is accepted; fake processes are used by the test directly');
  need(Array.isArray(config.cases) && config.cases.length > 0, 'At least one case is required');
  const admission = validateAdmission(config, root), gate = admission.gate;
  const lease = config.resourceLease;
  need(lease?.machine?.toLowerCase() === 'desktop' && lease.approvedBy && lease.id && lease.heavyJobSlot,
    'Explicit Desktop resource lease required');
  need(Date.parse(lease.startsUtc) <= now && Date.parse(lease.expiresUtc) > now, 'Resource lease is not active');
  need(lease.reservedCores >= 2 && lease.reservedMemoryBytes >= 6 * GiB, 'Reserve at least 2 cores and 6 GiB');
  need(Number.isSafeInteger(lease.maxCores) && lease.maxCores > 0 &&
    lease.maxCores <= os.availableParallelism() - lease.reservedCores, 'Lease core budget exceeds host headroom');
  const processorOffset = lease.processorOffset ?? 2;
  need(Number.isSafeInteger(processorOffset) && processorOffset >= 2 && processorOffset + lease.maxCores <= os.availableParallelism(),
    'Processor offset must reserve logical CPUs 0 and 1 and fit the host CPU count');
  need(Number.isSafeInteger(lease.maxMemoryBytes) && lease.maxMemoryBytes > 0 &&
    lease.maxMemoryBytes <= os.freemem() - lease.reservedMemoryBytes, 'Lease memory budget exceeds free headroom');
  const ids = new Set();
  for (const c of config.cases) {
    need(/^[a-z0-9_-]+$/i.test(c.id ?? '') && !ids.has(c.id), 'Unique simple case IDs required'); ids.add(c.id);
    need(classes.includes(c.class), 'Case class must be dense, rural or coast');
    need(c.approvedCommand === true, 'Each command must be approved');
    need(Array.isArray(c.argv) && c.argv.length > 0 && c.argv.every(x => typeof x === 'string'), 'argv must be a string array');
    need(path.isAbsolute(c.argv[0]) && fs.statSync(c.argv[0]).isFile(), 'Executable must be an absolute existing file');
    need(c.executableSha256 === hash(fs.readFileSync(c.argv[0])), 'Executable hash mismatch');
    need(Number.isSafeInteger(c.timeoutSeconds) && c.timeoutSeconds > 0 && c.timeoutSeconds <= 86400,
      'Bounded timeoutSeconds required, maximum 86400');
    need(c.timeoutSeconds * 1000 <= Date.parse(lease.expiresUtc) - now, 'Case timeout exceeds resource lease');
    need(Number.isFinite(c.coreAreaM2) && c.coreAreaM2 > 0, 'Positive core-only area required');
    need(c.settings && typeof c.settings === 'object' && !Array.isArray(c.settings), 'Settings object required');
    need(c.settingsSha256 === hash(stable(c.settings)), 'Canonical settings hash mismatch');
    need(c.generatorArtifacts?.length > 0, 'Generator artifact paths and SHA256 hashes required (including scripts)');
    for (const artifact of c.generatorArtifacts) need(artifact.sha256 === hash(fs.readFileSync(path.resolve(root, artifact.path))), 'Generator artifact hash mismatch');
    need(Array.isArray(c.sourcePaths) && c.sourcePaths.length > 0, 'Source paths required');
    need(c.representative !== true || typeof c.representativeEvidence === 'string' && c.representativeEvidence.length > 0,
      'Representative cases require a selection rationale/evidence');
    const output = path.resolve(root, c.outputDir);
    need(!fs.existsSync(output) || fs.readdirSync(output).length === 0, 'Output directory must be absent or empty');
    const source = inventory(c.sourcePaths.map(p => path.resolve(root, p)), true);
    need(source.inventorySha256 === c.sourceInventorySha256, 'Source content inventory hash mismatch');
    const bindingMatches = b => b.sourceInventorySha256 === c.sourceInventorySha256 &&
      b.settingsSha256 === c.settingsSha256 && b.generatorArtifactsSha256 === hash(stable(c.generatorArtifacts));
    if (admission.scope === 'pilot-policy') {
      need(lease.heavyJobSlot === 'A', 'Experimental generation may use only Slot A');
      need(c.coreSize === 1024 && c.halo === 128 && c.coreAreaM2 === 1024 ** 2 &&
        Array.isArray(c.coreOrigin) && c.coreOrigin.length === 2 && c.coreOrigin.every(Number.isSafeInteger),
        'Pilot benchmarks require integer core origin, 1024 m core and 128 m halo');
      need(c.jobSpecification?.path && c.jobSpecification.sha256 === fileHash(path.resolve(root, c.jobSpecification.path)),
        'Frozen adapter job specification and hash required');
      need(gate.caseBindings.some(b => b.id === c.id && bindingMatches(b) && b.commandSha256 === hash(stable(c.argv)) &&
        b.jobSpecificationSha256 === c.jobSpecification.sha256 && (b.processorOffset ?? 2) === processorOffset),
        'Pilot gate must allowlist the exact case, command, adapter job, source, settings and generator');
    } else need(gate.benchmarkBindings?.some(bindingMatches),
      'Seam PASS must bind the same source content inventory, settings and generator artifacts');
  }
  return {scope: admission.scope, gateSha256: admission.gateSha256, lease};
}
export function estimate(receipts, target) {
  if (!target) return {available: false, reason: 'No target area distribution supplied'};
  const selected = receipts.filter(r => r.mode === 'real' && r.status === 'PASS' && r.representative === true &&
    r.metrics?.wallSeconds > 0 && Number.isFinite(r.metrics.cpuSeconds) && r.metrics.cpuSeconds >= 0 &&
    Number.isFinite(r.metrics.outputLogicalBytes) && r.metrics.outputLogicalBytes >= 0 && r.coreAreaM2 > 0 && r.generatorArtifactsSha256);
  if (classes.some(c => !(target[c] >= 0 && Number.isFinite(target[c]))))
    return {available: false, reason: 'Target must give nonnegative core square meters for dense, rural and coast'};
  const needed = classes.filter(c => target[c] > 0);
  if (needed.some(c => !selected.some(r => r.class === c)))
    return {available: false, reason: 'Missing successful measured representative tiles for one or more target classes'};
  if (!needed.length) return {available: false, reason: 'Target core area is empty'};
  const settings = new Set(selected.filter(r => needed.includes(r.class)).map(r => r.settingsSha256));
  const executables = new Set(selected.filter(r => needed.includes(r.class)).map(r => r.executableSha256));
  const artifacts = new Set(selected.filter(r => needed.includes(r.class)).map(r => r.generatorArtifactsSha256));
  if (settings.size !== 1 || executables.size !== 1 || artifacts.size !== 1)
    return {available: false, reason: 'Mixed settings or executable hashes cannot be extrapolated together'};
  const byClass = needed.map(c => {
    const rows = selected.filter(r => r.class === c);
    const area = rows.reduce((n, r) => n + r.coreAreaM2, 0);
    const metric = key => rows.reduce((n, r) => n + r.metrics[key], 0) / area * target[c];
    return {class: c, measuredCases: rows.length, measuredCoreAreaM2: area, targetCoreAreaM2: target[c],
      serialWallSeconds: metric('wallSeconds'), cpuSeconds: metric('cpuSeconds'), outputLogicalBytes: metric('outputLogicalBytes')};
  });
  return {available: true, kind: 'Measured area-weighted serial extrapolation; not a completion ETA',
    exclusions: ['No parallel speedup assumed', 'No source download, global assembly or validation cost modeled',
      'Logical file sizes exclude filesystem allocation overhead', 'Halo and padded chunks excluded from core area',
      'Class selection is a declared assumption; within-class density, source size and tile size may differ'],
    byClass, serialWallSeconds: byClass.reduce((n, c) => n + c.serialWallSeconds, 0),
    cpuSeconds: byClass.reduce((n, c) => n + c.cpuSeconds, 0),
    outputLogicalBytes: byClass.reduce((n, c) => n + c.outputLogicalBytes, 0)};
}
export function loadIndependentlyValidatedReceipt(receiptPath, validationPath) {
  const receipt = read(receiptPath), validation = read(validationPath), root = path.dirname(path.resolve(validationPath));
  need(receipt.mode === 'real' && receipt.status === 'WRITTEN_UNACCEPTED', 'Expected a real experimental measurement receipt');
  need(validation.schemaVersion === 1 && validation.kind === 'independent-benchmark-result-gate' &&
    validation.status === 'PASS' && validation.benchmarkReceiptSha256 === fileHash(receiptPath),
    'Typed independent validation must PASS and bind the exact measurement receipt hash');
  need(receipt.output?.contentHashed === true && path.isAbsolute(receipt.outputRoot ?? '') &&
    validation.outputInventorySha256 === receipt.output.inventorySha256 &&
    inventory([receipt.outputRoot], true).inventorySha256 === receipt.output.inventorySha256,
    'Independent gate must bind the exact measured output content inventory, still unchanged');
  const bounds = validation.comparisonBounds;
  need(Array.isArray(bounds) && bounds.length === 4 && bounds.every(Number.isSafeInteger) &&
    bounds[2] > bounds[0] && bounds[3] > bounds[1], 'Independent comparison bounds must define an integer core rectangle');
  const area = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1]);
  need(area === receipt.coreAreaM2 && receipt.coreSize === 1024 &&
    bounds[2] - bounds[0] === receipt.coreSize && bounds[3] - bounds[1] === receipt.coreSize &&
    bounds[0] === receipt.coreOrigin?.[0] && bounds[1] === receipt.coreOrigin?.[1],
    'Comparison bounds must match the exact measured owned core, excluding halo');
  need(validation.comparisonScope === 'full-volume' && Number.isSafeInteger(validation.comparedCells) &&
    validation.comparedCells === area * 384 && validation.mismatchedCells === 0,
    'Independent full-volume oracle must compare every owned-core cell across all 384 Y levels with zero mismatches');
  need(validation.checks?.globalChunkCoordinates === true && validation.checks?.metadata === true &&
    validation.checks?.heightmaps === true && Array.isArray(validation.fileHashErrors) && validation.fileHashErrors.length === 0,
    'Independent coordinates, metadata, heightmaps and file hashes must pass');
  need(validation.evidence?.writerManifest && validation.evidence?.oracleProof &&
    Array.isArray(validation.evidence?.sourceRuns) && validation.evidence.sourceRuns.length > 0,
    'Writer manifest, source runs and actual oracle proof evidence required');
  const proofs = [validation.evidence.writerManifest, ...validation.evidence.sourceRuns, validation.evidence.oracleProof];
  for (const proof of proofs) need(proof.path && proof.sha256 === fileHash(path.resolve(root, proof.path)),
    'Independent evidence file hash mismatch');
  const oracle = read(path.resolve(root, validation.evidence.oracleProof.path));
  need(oracle.status === 'PASS' && stable(oracle.bounds) === stable(bounds) &&
    oracle.comparedCells === validation.comparedCells && oracle.mismatchedCells === validation.mismatchedCells &&
    Array.isArray(oracle.errors) && oracle.errors.length === 0,
    'Actual oracle proof must PASS with matching full-volume bounds/counts and no errors');
  need(['heightmapMismatches','missingColumns','inputErrorCount','metadataErrorCount','sameLayerConflictingCells'].every(k => oracle[k] === 0) &&
    oracle.chunkCount === area / 256 && oracle.comparedCoreChunkCount === area / 256,
    'Actual oracle metadata, heightmaps, source cells and global owned chunks must pass');
  const resultBindings = validateResultBindings(receipt, validation, root);
  return {...receipt, status: 'PASS', independentValidationRequired: false, resultBindings,
    independentValidation: {path: path.resolve(validationPath), sha256: fileHash(validationPath)},
    productionAccepted: false};
}
export function administrativeScenarios(receipts, jobCount = 1716) {
  need(Number.isSafeInteger(jobCount) && jobCount > 0, 'Positive administrative job count required');
  const base = {jobUniverse: {kind: 'administrative-mask', jobCount, landIntersectingJobs: null,
      waterOnlyJobs: null, classCountsVerified: false},
    landEstimate: {available: false, reason: 'Administrative-mask jobs include water; land counts and water policy are unverified'},
    scope: 'Conditional repeated-sample scenarios, not whole-island bounds or a completion ETA',
    exclusions: ['No water-only benchmark or verified skip policy', 'No class confidence intervals from one sample',
      'No measured parallel speedup', 'Source preparation, final merge and validation excluded',
      'Output bytes are generated tile bytes, not final merged-world storage']};
  const selected = receipts.filter(r => r.mode === 'real' && r.status === 'PASS' && r.representative === true &&
    r.coreSize === 1024 && r.halo === 128 && r.coreAreaM2 === 1024 ** 2 &&
    r.metrics?.wallSeconds > 0 && r.metrics?.cpuSeconds >= 0 && r.metrics?.outputLogicalBytes >= 0 &&
    (r.authorization?.scope !== 'pilot-policy' || r.independentValidation?.sha256));
  if (classes.some(c => !selected.some(r => r.class === c)))
    return {...base, available: false, reason: 'Need independently accepted measured 1024 m dense, rural and coast samples'};
  const compatibility = selected.map(r => stable([r.settingsSha256,r.executableSha256,r.generatorArtifactsSha256,
    r.machine,r.authorization?.lease?.maxCores,r.authorization?.lease?.maxMemoryBytes]));
  if (new Set(compatibility).size !== 1)
    return {...base, available: false, reason: 'Generator/settings or measurement resource budgets differ'};
  const keys = ['wallSeconds','cpuSeconds','outputLogicalBytes'];
  const samples = selected.map(r => ({id:r.id,class:r.class,
    measured:Object.fromEntries(keys.map(k=>[k,r.metrics[k]])),
    allAdministrativeJobsLikeThisSample:Object.fromEntries(keys.map(k=>[k,r.metrics[k]*jobCount]))}));
  const scenarioEnvelope = Object.fromEntries(keys.map(k=>[k,{
    minimum:Math.min(...samples.map(s=>s.allAdministrativeJobsLikeThisSample[k])),
    maximum:Math.max(...samples.map(s=>s.allAdministrativeJobsLikeThisSample[k]))}]));
  return {...base, available:true, samples, scenarioEnvelope,
    rangeMeaning:'Minimum and maximum across hypothetical repeated-sample totals; not validated island lower/upper bounds'};
}
export function measure(request, reportFile, stdoutFile, stderrFile) {
  need(process.platform === 'win32', 'Windows Desktop measurement backend required');
  const helper = path.join(path.dirname(fileURLToPath(import.meta.url)), 'validate-benchmark.windows.ps1');
  const requestFile = reportFile + '.request.json';
  fs.writeFileSync(requestFile, JSON.stringify({...request, processorOffset: request.processorOffset ?? 2, parentPid: process.pid}));
  const out = fs.openSync(stdoutFile, 'wx'), err = fs.openSync(stderrFile, 'wx');
  let child;
  try {
    child = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', helper, '-RequestFile', requestFile, '-ReportFile', reportFile],
      {stdio: ['ignore', out, err], windowsHide: true});
  } finally { fs.closeSync(out); fs.closeSync(err); }
  need(child.status === 0 && fs.existsSync(reportFile), 'Measurement helper failed; inspect ' + stderrFile);
  return read(reportFile);
}
export function run(configFile, reportDir) {
  const config = read(configFile), root = path.dirname(path.resolve(configFile));
  const authorization = validateConfig(config, root);
  need(os.hostname().toLowerCase() === 'desktop', 'Real execution is Desktop-only');
  const reportRoot = path.resolve(reportDir);
  need(!fs.existsSync(reportRoot), 'Report directory must be new');
  for (const c of config.cases) {
    const output = path.resolve(root, c.outputDir).toLowerCase(), report = reportRoot.toLowerCase();
    need(output !== report && !report.startsWith(output + path.sep) && !output.startsWith(report + path.sep),
      'Measurement reports and generated output must be disjoint sibling trees');
  }
  fs.mkdirSync(reportRoot, {recursive: true});
  const receipts = [], successfulStatus = authorization.scope === 'pilot-policy' ? 'WRITTEN_UNACCEPTED' : 'PASS';
  for (const c of config.cases) {
    validateConfig({...config, cases: [c]}, root);
    const outDir = path.resolve(root, c.outputDir);
    const source = inventory(c.sourcePaths.map(p => path.resolve(root, p)), true);
    const file = path.join(reportRoot, c.id);
    const metrics = measure({argv: c.argv, cwd: path.resolve(root, c.cwd ?? '.'),
      timeoutSeconds: c.timeoutSeconds, maxCores: authorization.lease.maxCores,
      maxMemoryBytes: authorization.lease.maxMemoryBytes, processorOffset: authorization.lease.processorOffset ?? 2, sampleIntervalMs: 50},
      file + '.process.json', file + '.stdout.log', file + '.stderr.log');
    const output = fs.existsSync(outDir) ? inventory([outDir], true) :
      {bytes: 0, files: 0, contentHashed: true, inventorySha256: hash(stable([])), missing: true};
    const receipt = {schemaVersion: 1, mode: 'real', id: c.id, class: c.class,
      status: metrics.exitCode === 0 && !metrics.timedOut && !metrics.parentExited && output.files > 0 ? successfulStatus : 'FAIL',
      independentValidationRequired: authorization.scope === 'pilot-policy', productionAccepted: false,
      coreSize: c.coreSize ?? null, coreOrigin: c.coreOrigin ?? null, halo: c.halo ?? null, jobSpecification: c.jobSpecification ?? null,
      measuredUtc: new Date().toISOString(), machine: os.hostname(), command: c.argv,
      cwd: path.resolve(root, c.cwd ?? '.'), coreAreaM2: c.coreAreaM2,
      representative: c.representative === true, representativeEvidence: c.representativeEvidence ?? null,
      settings: c.settings, settingsSha256: c.settingsSha256, executableSha256: c.executableSha256,
      commandSha256: hash(stable(c.argv)), generatorArtifacts: c.generatorArtifacts,
      generatorArtifactsSha256: hash(stable(c.generatorArtifacts)),
      source, output, outputRoot: outDir, authorization, metrics: {...metrics, sourceLogicalBytes: source.bytes, outputLogicalBytes: output.bytes}};
    fs.writeFileSync(file + '.receipt.json', JSON.stringify(receipt, null, 2) + '\n', {flag: 'wx'});
    receipts.push(receipt);
    if (receipt.status === 'FAIL') break;
  }
  const summary = {schemaVersion: 1, status: receipts.length === config.cases.length && receipts.every(r => r.status === successfulStatus) ? successfulStatus : 'FAIL',
    receipts, estimate: estimate(receipts, config.targetCoreAreaM2ByClass),
    administrativeScenarios: administrativeScenarios(receipts, config.administrativeJobCount ?? 1716)};
  fs.writeFileSync(path.join(reportRoot, 'summary.json'), JSON.stringify(summary, null, 2) + '\n', {flag: 'wx'});
  return summary;
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [command, input, out] = process.argv.slice(2);
    need(command === 'run' && input && out, 'Usage: node validate-benchmark.mjs run CONFIG.json NEW_REPORT_DIR');
    const result = run(input, out);
    console.log(JSON.stringify({status: result.status, estimate: result.estimate}, null, 2));
    if (!['PASS', 'WRITTEN_UNACCEPTED'].includes(result.status)) process.exitCode = 1;
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
