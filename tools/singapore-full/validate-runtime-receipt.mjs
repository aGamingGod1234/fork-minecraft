import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import zlib from 'node:zlib';
import {decode,sections,unpack} from '../fork-world/nbt-region.mjs';

const [runtimeRoot, writerPath, gatePath, outPath] = process.argv.slice(2);
if (!outPath) throw new Error('Usage: node validate-runtime-receipt.mjs RUNTIME_ROOT WRITER_MANIFEST STRUCTURAL_GATE OUTPUT');
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const json = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const same = (a, b, label) => assert.equal(a?.toLowerCase(), b?.toLowerCase(), label);
const receiptPath = path.join(runtimeRoot, 'runtime-receipt.json');
const statePath = path.join(runtimeRoot, 'runtime-state.json');
const r = json(receiptPath), s = json(statePath), m = json(writerPath), g = json(gatePath);
const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value==='object' ? Object.fromEntries(Object.keys(value).sort().map(k=>[k,canonical(value[k])])) : value;
const unsignedReceipt = {...r}; delete unsignedReceipt.manifest_sha256;
same(r.manifest_sha256, crypto.createHash('sha256').update(JSON.stringify(canonical(unsignedReceipt))).digest('hex'), 'canonical completed receipt digest');
assert.equal(r.schema, 'fork.singapore.runtime-load.v1');
assert.equal(r.runtimeLoadAccepted, true);
assert.deepEqual(r.issues, []);
assert.deepEqual(r.log_errors, []);
assert.equal(g.kind, 'independent-joined-strip-structural-gate');
assert.equal(g.status, 'PASS');
same(g.writerManifestSha256, hash(writerPath), 'gate must bind exact writer');
same(s.gate_sha256, hash(gatePath), 'runtime must bind exact structural gate');
assert.equal(r.minecraft_version, '26.1.2');
assert.equal(s.minecraft_version, r.minecraft_version);
assert.equal(m.dataVersion, 4790);
assert.equal(m.minecraftTarget, r.minecraft_version);
assert.equal(path.resolve(r.candidate_path), path.resolve(s.candidate_path));
assert.notEqual(path.resolve(r.candidate_path), path.resolve(r.copied_world_path));
assert.equal(r.candidateUnchanged, true);
assert.equal(s.candidate_unchanged, true);
assert.deepEqual(r.process, s.process);
assert.equal(r.process.exit_code, 0);
assert.equal(r.process.observed_alive, false);
assert.equal(s.process_alive, false);
same(r.jar.sha256, s.expected_jar_sha256, 'runtime jar identity');
assert.equal(r.jar.bytes, fs.statSync(r.jar.path).size);
same(r.jar.sha256, hash(r.jar.path), 'actual server jar hash');
assert.equal(r.log.bytes, fs.statSync(r.log.path).size);
same(r.log.sha256, hash(r.log.path), 'runtime console hash');
const log = fs.readFileSync(r.log.path, 'utf8');
for (const pattern of [/Starting minecraft server version 26\.1\.2/, /Done \(/, /Stopping server/, /All dimensions are saved/]) {
  assert.match(log, pattern);
}
const outputs = Object.fromEntries(m.outputs.map(o => [o.path, o.sha256.toLowerCase()]));
assert.deepEqual(Object.keys(outputs).sort(), Object.keys(r.candidate_before).sort());
assert.deepEqual(Object.keys(outputs).sort(), Object.keys(s.candidate_before).sort());
assert.deepEqual(Object.keys(outputs).sort(), Object.keys(r.candidate_after.files).sort());
for (const o of m.outputs) {
  const file = path.resolve(r.candidate_path, o.path);
  assert.ok(file.startsWith(path.resolve(r.candidate_path) + path.sep));
  assert.equal(fs.statSync(file).size, o.bytes);
  same(hash(file), o.sha256, 'current immutable candidate');
  same(r.candidate_before[o.path], o.sha256, 'receipt before copy');
  same(s.candidate_before[o.path], o.sha256, 'launcher before copy');
  same(r.candidate_after.files[o.path].sha256, o.sha256, 'receipt after copy');
}
assert.ok(m.inputs.some(i => i.sha256.toLowerCase() === s.sentinel_runs_sha256.toLowerCase()));
const [x0,z0,x1,z1] = m.bounds;
assert.deepEqual(m.bounds, g.bounds);
const chunkMarkers = [];
for (let z=z0/16;z<z1/16;z++) for(let x=x0/16;x<x1/16;x++)
  chunkMarkers.push('FORK_RUNTIME_CHUNK_'+x+'_'+z+'_OK');
assert.equal(chunkMarkers.length, g.chunkCount);
const markers = (gate, expected) => {
  assert.equal(new Set(expected).size, expected.length);
  assert.deepEqual([...gate.expected].sort(), [...expected].sort());
  assert.deepEqual([...gate.found].sort(), [...expected].sort());
  assert.deepEqual(gate.missing, []);
  for (const marker of expected) {
    assert.match(marker, /^[A-Z0-9_]+$/);
    assert.match(log, new RegExp('\\[Server thread/INFO\\]: .*\\[Server\\] '+marker+'\\r?$','m'));
  }
};
markers(r.chunk_load_gate, chunkMarkers);
const candidateRegions=new Map(), candidateChunks=new Map();
function blockAt(x,y,z){
  const cx=Math.floor(x/16),cz=Math.floor(z/16),key=cx+','+cz;
  if(!candidateChunks.has(key)){
    const file=path.join(r.candidate_path,m.regionDirectory,'r.'+Math.floor(cx/32)+'.'+Math.floor(cz/32)+'.mca');
    if(!candidateRegions.has(file)) candidateRegions.set(file,fs.readFileSync(file));
    const bytes=candidateRegions.get(file),slot=((cx%32+32)%32)+32*((cz%32+32)%32),off=(bytes.readUInt32BE(slot*4)>>>8)*4096;
    assert.ok(off>=8192,'sentinel chunk must exist');
    assert.equal(bytes[off+4],2,'sentinel compression');
    const len=bytes.readUInt32BE(off),root=decode(zlib.inflateSync(bytes.subarray(off+5,off+4+len)));
    assert.equal(root.value.xPos.value,cx); assert.equal(root.value.zPos.value,cz);
    candidateChunks.set(key,{root});
  }
  const section=sections(candidateChunks.get(key)).find(s=>s.Y.value===Math.floor(y/16));
  if(!section)return 'minecraft:air';
  const states=unpack(section); if(!states)return 'minecraft:air';
  const palette=states.pal[states.blocks[((y%16+16)%16)*256+((z%16+16)%16)*16+((x%16+16)%16)]];
  const props=palette.Properties?.value;
  return palette.Name.value+(props&&Object.keys(props).length?'['+Object.keys(props).sort().map(k=>k+'='+props[k].value).join(',')+']':'');
}
assert.equal(s.block_sentinels.length, 16, 'this preview requires twelve building and four seam sentinels');
for (const sentinel of s.block_sentinels) {
  const [x,y,z] = sentinel.pos;
  assert.ok([x,y,z].every(Number.isInteger));
  assert.ok(x>=x0 && x<x1 && z>=z0 && z<z1 && y>0 && y<320);
  assert.match(sentinel.state, /^minecraft:/);
  assert.equal(blockAt(x,y,z),sentinel.state,'sentinel state must match independently decoded immutable candidate');
  assert.ok(!['minecraft:air','minecraft:grass_block','minecraft:dirt','minecraft:bedrock'].includes(sentinel.state));
}
markers(r.block_sentinel_gate, s.block_sentinels.map(p => p.marker));
const evidence = {
  schemaVersion:1, kind:'independent-runtime-receipt-binding', status:'PASS',
  checkedUtc:new Date().toISOString(), writerManifestSha256:hash(writerPath),
  structuralGateSha256:hash(gatePath), runtimeReceiptSha256:hash(receiptPath),
  runtimeStateSha256:hash(statePath), consoleSha256:hash(r.log.path),
  runtimeReceiptManifestSha256:r.manifest_sha256,
  minecraftVersion:r.minecraft_version, bounds:m.bounds, chunkCount:chunkMarkers.length,
  matchedNonGroundSentinels:s.block_sentinels.length, independentlyDecodedSentinels:s.block_sentinels.length, sentinelRunsSha256:s.sentinel_runs_sha256,
  sentinels:s.block_sentinels, candidateFileHashes:outputs, candidateUnchanged:true,
  exitCode:0, processObservedAlive:false, elapsedJavaSeconds:s.elapsed_java_seconds,
  maximumObservedOsPeakWorkingSetBytes:s.peak_rss_bytes, runtimeLoadAccepted:true,
  visualAccepted:false, aiAccepted:false, actualTerrainAccepted:false, fullWorldAccepted:false,
  scope:'Binds the completed runtime checker to the exact independently validated candidate, source run identity, actual console markers, independently decoded candidate block states and unchanged source files. The launcher state predates final acceptance; the completed receipt is authoritative.',
};
fs.writeFileSync(outPath, JSON.stringify(evidence,null,2)+'\n');
console.log(JSON.stringify({status:evidence.status,chunkCount:evidence.chunkCount,sentinels:evidence.matchedNonGroundSentinels,outputSha256:hash(outPath)}));
