// Tiny synthetic binding fixtures only. These do not run the 1024-cell-volume oracle.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import test from 'node:test';
import assert from 'node:assert/strict';
import {createDirectWorldGate,loadDirectWorldGate} from './validate-direct-world-gate.mjs';

const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const record=p=>({path:p,bytes:fs.statSync(p).size,sha256:sha(fs.readFileSync(p))});
const save=(p,v)=>fs.writeFileSync(p,JSON.stringify(v,null,2)+'\n');
function fixture() {
  const base=process.platform==='win32'?path.join(process.env.LOCALAPPDATA,'FORK-Tools/fork-singapore-full'):os.tmpdir();
  fs.mkdirSync(base,{recursive:true});
  const root=fs.mkdtempSync(path.join(base,'direct-gate-tiny-'));
  const put=(n,v)=>{const p=path.join(root,n);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,typeof v==='string'?v:JSON.stringify(v,null,2)+'\n');return p;};
  const source=record(put('frozen/source.json','{"elements":[]}'));
  const python=put('frozen/python.exe','synthetic Python artifact');
  const producer=put('frozen/features.py','synthetic producer artifact');
  const template=record(put('frozen/template/level.dat','synthetic level template'));
  const aux=record(put('frozen/template/data/minecraft/world_gen_settings.dat','synthetic template settings'));
  const levelTemplate={...template,worldGenSettingsSha256:aux.sha256};
  const runs=[record(put('old/tiny/buildings.runs.jsonl','{"x":0}\n')),record(put('old/tiny/roads.runs.jsonl','{"x":1}\n'))];
  const tile={id:'tiny',coreOrigin:[0,0],coreSize:1024,halo:128},bounds=[-128,-128,1152,1152],coreBounds=[0,0,1024,1024];
  const job={schemaVersion:1,tiles:[tile],source,artifacts:[record(python),record(producer)],tools:{python},levelTemplate,terrain:{mode:'flat-provisional',groundY:0}};
  const jobPath=put('old/job.json',job);
  const old={schemaVersion:1,status:'failed',source,tiles:[{...tile,renderBounds:bounds,runs:runs[0],roadRuns:runs[1]}],
    stages:[{name:'source',status:'passed'},{name:'tiny-world',status:'failed',argv:['overlay.py',...runs.flatMap(r=>['--runs',r.path])]}]};
  const oldPath=put('old/pipeline-result.json',old);
  const writerCode=put('recovery/overlay.py','synthetic writer artifact'),adapter=put('recovery/adapter.mjs','synthetic adapter artifact');
  const settingsPath=put('fixture-settings.mjs',"import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';export function validateWorldSettings(root){const files=['level.dat','data/minecraft/world_gen_settings.dat'].map(p=>({path:p,bytes:fs.statSync(path.join(root,p)).size,sha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(root,p))).digest('hex')}));return {status:'PASS',dataVersion:4790,spawn:[0,1,0],regionDirectory:'dimensions/minecraft/overworld/region',files,errors:[]};}");
  put('attempt/world/level.dat','synthetic level');put('attempt/world/data/minecraft/world_gen_settings.dat','synthetic metadata');
  put('attempt/world/dimensions/minecraft/overworld/region/r.0.0.mca','synthetic MCA binding bytes');
  const world=path.join(root,'attempt/world'),writerPath=path.join(root,'attempt/writer-manifest.json');
  const relativeOutputs=['level.dat','data/minecraft/world_gen_settings.dat','dimensions/minecraft/overworld/region/r.0.0.mca'].map(p=>({...record(path.join(world,p)),path:p}));
  const writer={schemaVersion:1,kind:'global-block-run-world',status:'WRITTEN_UNACCEPTED',chunkCount:6400,bounds,
    coordinateFrame:{crs:'EPSG:3414',blocksPerMeter:1,x:'easting',z:'60000-northing'},
    dataVersion:4790,minecraftTarget:'26.1.2',regionDirectory:'dimensions/minecraft/overworld/region',
    inputs:runs.map(r=>({name:path.basename(r.path),bytes:r.bytes,sha256:r.sha256})),outputs:relativeOutputs};
  save(writerPath,writer);
  const recovery={schemaVersion:1,kind:'fork-writer-only-recovery',id:'tiny-recovery',maxChunks:6400,bounds,coreBounds,terrain:job.terrain,
    originalJob:record(jobPath),originalFailedResult:record(oldPath),source,runs,provenance:[record(jobPath),record(oldPath),source],
    producerArtifacts:job.artifacts,artifacts:[record(writerCode),record(adapter)],writer:writerCode,adapter,python,
    levelTemplate,templateDependencies:[template,aux]};
  const recoveryPath=put('attempt/job.json',recovery);
  const material={schemaVersion:1,kind:recovery.kind,status:'WRITTEN_AWAITING_INDEPENDENT_GATES',inputsUnchanged:true,exit:{code:0,signal:null},
    completedUtc:'2026-09-13T00:00:00Z',productionAccepted:false,runtimeAccepted:false,terrainSurveyed:false,jobId:recovery.id,
    originalJob:recovery.originalJob,originalFailedResult:recovery.originalFailedResult,source,provenance:recovery.provenance,
    runs,writerArtifacts:recovery.artifacts,templateDependencies:recovery.templateDependencies,bounds,coreBounds,
    worldPath:world,writerManifestPath:writerPath,writerManifest:record(writerPath),
    argv:[python,writerCode,...runs.flatMap(r=>['--runs',r.path]),'--world',world,'--bounds',bounds.join(','),'--manifest',writerPath,
      '--level-template',levelTemplate.path,'--job-lease',path.join(root,'attempt/job-lease.json'),'--max-chunks','6400']};
  const materialPath=put('attempt/materialize-result.json',material);
  const worldSettings={status:'PASS',dataVersion:4790,spawn:[0,1,0],regionDirectory:writer.regionDirectory,files:relativeOutputs.slice(0,2),errors:[]};
  const oracle={schemaVersion:1,kind:'independent-source-run-fast-world-oracle',status:'PASS',bounds:coreBounds,
    errors:[],inputErrors:[],metadataErrors:[],mismatchedCells:0,heightmapMismatches:0,missingColumns:0,sameLayerConflictingCells:0,
    inputErrorCount:0,metadataErrorCount:0,comparedCells:402653184,chunkCount:4096,comparedCoreChunkCount:4096,allocatedChunkCount:6400,
    spawnClear:true,spawn:[0,1,0],dataVersion:4790,expectedIntervalSha256:'a'.repeat(64),actualIntervalSha256:'a'.repeat(64),
    settingsValidatorSha256:record(settingsPath).sha256,worldSettings,
    worldFiles:[{...relativeOutputs[2],path:'r.0.0.mca'}],sourceFiles:runs,inputs:runs};
  const oraclePath=put('oracle.json',oracle);
  const options={materialize:materialPath,world,writer:writerPath,job:jobPath,oracle:oraclePath,runs:runs.map(r=>r.path),settingsValidator:settingsPath,
    expected:{materialize:record(materialPath).sha256,job:record(jobPath).sha256,oracle:record(oraclePath).sha256}};
  const refresh=()=>{save(materialPath,material);save(oraclePath,oracle);save(recoveryPath,recovery);options.expected.materialize=record(materialPath).sha256;options.expected.oracle=record(oraclePath).sha256;};
  const cleanup=()=>{assert.ok(path.resolve(root).startsWith(path.resolve(base)+path.sep+'direct-gate-tiny-'));fs.rmSync(root,{recursive:true,force:true});};
  return {root,put,job,jobPath,writer,writerPath,material,materialPath,recovery,recoveryPath,oracle,oraclePath,options,runs,refresh,cleanup};
}
async function withFixture(fn){const f=fixture();try{await fn(f);}finally{f.cleanup();}}
test('positive binding fixture creates mandatory structural schema and reloads',()=>withFixture(async f=>{
  const gate=await createDirectWorldGate(f.options);assert.equal(gate.status,'PASS');assert.equal(gate.comparedBlocks,402653184);assert.equal(gate.fullWorldAccepted,false);
  assert.deepEqual(gate.worldOutputs,f.writer.outputs);
  const p=f.put('gate.json',gate);assert.deepEqual(await loadDirectWorldGate(p,record(p).sha256),gate);
}));
test('swapped output world rejects even with identical bytes',()=>withFixture(async f=>{
  const swapped=path.join(f.root,'other-world');fs.cpSync(f.options.world,swapped,{recursive:true});f.options.world=swapped;
  await assert.rejects(createDirectWorldGate(f.options),/materialized world exact path mismatch/);
}));
test('omitted consumed input rejects',()=>withFixture(async f=>{
  f.options.runs.pop();await assert.rejects(createDirectWorldGate(f.options),/requested\/original runs file count/);
}));
test('wrong halo in pinned original job rejects',()=>withFixture(async f=>{
  f.job.tiles[0].halo=127;save(f.jobPath,f.job);f.material.originalJob=record(f.jobPath);f.recovery.originalJob=record(f.jobPath);
  f.options.expected.job=record(f.jobPath).sha256;f.refresh();
  await assert.rejects(createDirectWorldGate(f.options),/1024 core and 128 halo/);
}));
test('same-hash source relabel outside frozen original path rejects',()=>withFixture(async f=>{
  const rebound=record(f.put('rebound/buildings.runs.jsonl',fs.readFileSync(f.runs[0].path,'utf8')));
  f.material.runs=[rebound,f.runs[1]];f.recovery.runs=f.material.runs;f.options.runs=f.material.runs.map(r=>r.path);f.refresh();
  await assert.rejects(createDirectWorldGate(f.options),/materialization\/original runs path mismatch/);
}));
test('changed materialization receipt rejects old pin',()=>withFixture(async f=>{
  f.material.inputsUnchanged=false;save(f.materialPath,f.material);
  await assert.rejects(createDirectWorldGate(f.options),/materialization pin mismatch/);
}));
test('omitted writer output cannot hide a real world file',()=>withFixture(async f=>{
  f.writer.outputs.pop();save(f.writerPath,f.writer);f.material.writerManifest=record(f.writerPath);f.refresh();
  await assert.rejects(createDirectWorldGate(f.options),/unlisted actual world file/);
}));
test('changed executed recovery argv rejects',()=>withFixture(async f=>{
  f.material.argv[3]=f.runs[1].path;f.refresh();await assert.rejects(createDirectWorldGate(f.options),/executed recovery argv mismatch/);
}));
test('halo counted as compared core rejects',()=>withFixture(async f=>{
  f.oracle.comparedCells=1280*1280*384;f.refresh();await assert.rejects(createDirectWorldGate(f.options),/full 384Y core\/chunk coverage/);
}));
test('strict reload rejects dependency changes with gate bytes unchanged',()=>withFixture(async f=>{
  const gate=await createDirectWorldGate(f.options),p=f.put('gate.json',gate),pin=record(p).sha256;
  fs.appendFileSync(f.runs[0].path,'changed');
  await assert.rejects(loadDirectWorldGate(p,pin),/original completed runs bytes\/SHA256 mismatch/);
}));
test('strict reload rejects edited summary even under a new gate pin',()=>withFixture(async f=>{
  const gate=await createDirectWorldGate(f.options);gate.comparedBlocks=1;const p=f.put('gate.json',gate);
  await assert.rejects(loadDirectWorldGate(p,record(p).sha256),/gate fields differ/);
}));
