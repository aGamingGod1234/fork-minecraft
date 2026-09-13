import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateResultBindings} from './validate-benchmark-bindings.mjs';

const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const save = (file, value) => fs.writeFileSync(file, JSON.stringify(value));
const record = file => ({path: fs.realpathSync(file), bytes: fs.statSync(file).size, sha256: sha(file)});
export function createBindingFixture(root, label) {
  const base = path.join(root, label), output = path.join(base, 'output'), tile = path.join(output, 'tile'),
    world = path.join(tile, 'world'), regions = 'dimensions/minecraft/overworld/region';
  fs.mkdirSync(path.join(world, regions), {recursive: true});
  const worldData = {'level.dat':'level', [regions + '/r.0.0.mca']:'aaaa', [regions + '/r.0.1.mca']:'bbbb'};
  for (const [name, data] of Object.entries(worldData)) fs.writeFileSync(path.join(world, name), data);
  const source = path.join(tile, 'owned-core.runs.jsonl');
  fs.writeFileSync(source, 'source');
  const writer = {schemaVersion:1,kind:'global-block-run-world',bounds:[-128,-128,1152,1152],regionDirectory:regions,
    inputs:[{name:path.basename(source),bytes:record(source).bytes,sha256:sha(source)}],
    outputs:Object.keys(worldData).map(name=>({...record(path.join(world,name)),path:name}))};
  const oracle = {status:'PASS',bounds:[0,0,1024,1024],errors:[],
    worldFiles:writer.outputs.filter(r=>r.path.endsWith('.mca')).map(r=>({...r,path:path.basename(r.path)})),
    worldSettings:{status:'PASS',errors:[],regionDirectory:regions,files:writer.outputs.filter(r=>!r.path.endsWith('.mca'))},
    sourceFiles:[record(source)]};
  const manifestPath = path.join(tile, 'writer-manifest.json'), oraclePath = path.join(base, 'oracle.json'),
    jobPath = path.join(base, 'job.json');
  save(jobPath,{tiles:[{id:'tile',coreOrigin:[0,0],coreSize:1024,halo:128}]});
  const receipt = {coreOrigin:[0,0],coreSize:1024,halo:128,outputRoot:output,jobSpecification:record(jobPath)};
  const validation = {comparisonBounds:[0,0,1024,1024],evidence:{sourceRuns:[record(source)]}};
  const refresh = () => {
    save(manifestPath,writer); save(oraclePath,oracle);
    validation.evidence.writerManifest=record(manifestPath);
    validation.evidence.oracleProof=record(oraclePath);
  };
  refresh();
  return {base,output,tile,world,source,writer,oracle,receipt,validation,refresh,jobPath,manifestPath};
}
function runTests() {
const parent = path.dirname(fileURLToPath(import.meta.url));
const root = fs.mkdtempSync(path.join(parent, '.bindings-fixture-'));
const fixture = label => createBindingFixture(root, label);
let checks = 0;
function verify(f) { return validateResultBindings(f.receipt,f.validation,f.base); }
function rejects(label, mutate, pattern) {
  const f=fixture(label);mutate(f);f.refresh();
  assert.throws(()=>verify(f),pattern,label);checks++;
}
try {
  const good=fixture('valid'), result=verify(good);
  assert.equal(result.outputs.length,3);assert.equal(result.sources.length,1);
  assert.deepEqual(result.renderBounds,[-128,-128,1152,1152]);checks++;
  const a=fixture('output-a'), b=fixture('output-b');
  fs.writeFileSync(path.join(b.world,b.writer.outputs[1].path),'cccc');
  // Rebinding only the receipt root and manifest pin must not transplant oracle A to output B.
  b.writer.outputs[1]={...b.writer.outputs[1],...record(path.join(b.world,b.writer.outputs[1].path)),path:b.writer.outputs[1].path};
  b.refresh();
  const rebound={...a.validation,evidence:{...a.validation.evidence,writerManifest:b.validation.evidence.writerManifest}};
  assert.throws(()=>validateResultBindings(b.receipt,rebound,a.base),/oracle region.*mismatch/);checks++;
  rejects('swapped-regions',f=>{
    const [first,second]=f.oracle.worldFiles;
    [first.path,second.path]=[second.path,first.path];
  },/oracle region.*mismatch/);
  rejects('same-bytes-other-source-path',f=>{
    const copy=path.join(f.base,'owned-core.runs.jsonl');fs.copyFileSync(f.source,copy);
    f.validation.evidence.sourceRuns=[record(copy)];
  },/writer input source must be the frozen tile file/);
  rejects('oracle-same-bytes-other-source-path',f=>{
    const copy=path.join(f.base,'owned-core.runs.jsonl');fs.copyFileSync(f.source,copy);f.oracle.sourceFiles=[record(copy)];
  },/oracle source path absent/);
  rejects('extra-world-file',f=>fs.writeFileSync(path.join(f.world,'unexpected.dat'),'extra'),/world file absent from writer outputs/);
  const sameA=fixture('same-a'), sameB=fixture('same-b');
  const oldSources={...sameB.validation,evidence:{...sameB.validation.evidence,
    oracleProof:sameA.validation.evidence.oracleProof,sourceRuns:sameA.validation.evidence.sourceRuns}};
  assert.throws(()=>validateResultBindings(sameB.receipt,oldSources,sameB.base),/writer input source must be the frozen tile file/);checks++;
  rejects('changed-source',f=>fs.writeFileSync(f.source,'SOURCE'),/source run evidence hash mismatch/);
  rejects('oracle-source-hash',f=>{f.oracle.sourceFiles[0].sha256='0'.repeat(64);},/oracle source.*mismatch/);
  rejects('writer-input-hash',f=>{f.writer.inputs[0].sha256='0'.repeat(64);},/writer input.*mismatch/);
  rejects('actual-world-changed',f=>fs.writeFileSync(path.join(f.world,f.writer.outputs[1].path),'cccc'),/writer output.*mismatch/);
  for(let index=0;index<4;index++) rejects('render-bound-'+index,f=>{f.writer.bounds[index]++;},/exact core plus halo/);
  rejects('core-bound',f=>{f.oracle.bounds[0]++;},/oracle must PASS exact core/);
  rejects('duplicate-writer-output',f=>f.writer.outputs.push({...f.writer.outputs[0]}),/duplicate canonical path/);
  rejects('alias-writer-output',f=>f.writer.outputs.push({...f.writer.outputs[0],path:'.'+path.sep+f.writer.outputs[0].path}),/duplicate canonical path/);
  rejects('missing-oracle-output',f=>f.oracle.worldSettings.files.pop(),/file counts differ/);
  rejects('duplicate-oracle-region',f=>f.oracle.worldFiles.push({...f.oracle.worldFiles[0]}),/duplicate canonical path/);
  rejects('duplicate-source',f=>f.validation.evidence.sourceRuns.push({...f.validation.evidence.sourceRuns[0]}),/duplicate canonical path/);
  rejects('missing-oracle-source',f=>{f.oracle.sourceFiles=[];},/file counts differ/);
  rejects('wrong-region-directory',f=>{
    fs.mkdirSync(path.join(f.world,'other'));f.oracle.worldSettings.regionDirectory='other';
  },/region directory differs/);
  rejects('traversal-output',f=>{f.writer.outputs[0].path='../source';},/contained relative path/);
  rejects('absolute-output',f=>{f.writer.outputs[0].path=path.join(f.world,'level.dat');},/contained relative path/);
  rejects('nonbasename-region',f=>{f.oracle.worldFiles[0].path='../r.0.0.mca';},/r.X.Z.mca basename/);
  rejects('wrong-bytes',f=>{f.oracle.worldFiles[0].bytes++;},/oracle region.*mismatch/);
  rejects('job-core-mismatch',f=>{
    const job=JSON.parse(fs.readFileSync(f.jobPath));job.tiles[0].halo=127;save(f.jobPath,job);f.receipt.jobSpecification=record(f.jobPath);
  },/frozen tile identity\/core\/halo mismatch/);
  rejects('job-hash-mismatch',f=>save(f.jobPath,{tiles:[]}),/job specification evidence hash mismatch/);
  rejects('manifest-from-other-output',f=>{
    const other=fixture('foreign-manifest');f.validation.evidence.writerManifest=record(other.manifestPath);
    // refresh would overwrite the pin; retain the actual same-content foreign path after it.
    f.refresh=()=>{};
  },/writer manifest must be the frozen tile manifest/);
  const link=fixture('junction-escape'), outside=path.join(root,'outside');
  fs.mkdirSync(outside);
  const external=path.join(outside,'r.0.0.mca');fs.writeFileSync(external,'aaaa');
  const linkedRegion=path.join(link.world,'linked-region');
  fs.symlinkSync(outside,linkedRegion,process.platform==='win32'?'junction':'dir');
  link.writer.regionDirectory='linked-region';link.oracle.worldSettings.regionDirectory='linked-region';link.refresh();
  assert.throws(()=>verify(link),/resolves outside root/);checks++;
  console.log(JSON.stringify({status:'PASS',checks,syntheticFixtures:true,worldGeneration:false}));
} finally {
  const actual=fs.realpathSync(root), relative=path.relative(fs.realpathSync(parent),actual);
  assert(relative && !relative.startsWith('..') && !path.isAbsolute(relative),'fixture cleanup stays in test directory');
  fs.rmSync(actual,{recursive:true,force:true});
}

}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) runTests();
