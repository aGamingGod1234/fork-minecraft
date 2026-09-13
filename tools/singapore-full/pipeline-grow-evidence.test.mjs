import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {produceEvidence,produceMultiWaterEvidence,digest} from './pipeline-grow-evidence.mjs';

// Tiny fabricated byte fixtures test the adapter contract only. No real world,
// generation, source-completeness scan or actual oracle is performed here.
const parent=path.resolve(process.env.LOCALAPPDATA,'FORK-Tools/fork-singapore-full/seam-c/grow-evidence-tests');
fs.mkdirSync(parent,{recursive:true});const root=fs.mkdtempSync(path.join(parent,'fixtures-'));
const write=(p,v)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,typeof v==='string'?v:JSON.stringify(v));return p;};
const core=[1008,1008,1024,1024];
const run=(layer,id='same-road',x=1010)=>({x,z:1010,yMin:0,yMax:1,block:'minecraft:stone',featureId:id,layer});
let n=0,passed=0;
async function fixture(rows=[run(20,'land'),run(30,'water'),run(40),run(60)]){
  const dir=path.join(root,String(++n));fs.mkdirSync(dir);const sourcePath=write(path.join(dir,'source.json'),{fixtureOnly:true});
  const runsPath=write(path.join(dir,'runs.jsonl'),rows.map(r=>JSON.stringify(r)+'\n').join(''));
  const worldPath=path.join(dir,'world'),outputs=[];
  for(const name of ['level.dat','data/minecraft/world_gen_settings.dat','dimensions/minecraft/overworld/region/r.1.1.mca']){
    const p=write(path.join(worldPath,name),'fixture '+name);outputs.push({path:name,bytes:fs.statSync(p).size,sha256:await digest(p)});
  }
  const writer={kind:'global-block-run-world',status:'WRITTEN_UNACCEPTED',bounds:core,coordinateFrame:{crs:'EPSG:3414',x:'easting',z:'60000-northing',blocksPerMeter:1},dataVersion:4790,minecraftTarget:'26.1.2',regionDirectory:'dimensions/minecraft/overworld/region',inputs:[{name:'runs.jsonl',bytes:fs.statSync(runsPath).size,sha256:await digest(runsPath)}],outputs};
  const writerManifestPath=write(path.join(dir,'writer.json'),writer);
  const gate={kind:'actual-world-structural-validation',schemaVersion:1,status:'PASS',synthetic:false,writerManifestSha256:await digest(writerManifestPath),comparedBlocks:256*384,mismatches:0,worldOutputs:outputs};
  const structuralGatePath=write(path.join(dir,'gate.json'),gate);
  const report={schema:'fork.roads-runs.v1',tile:core,blockedDiagnostics:0,sourceSha256:await digest(sourcePath),runCount:rows.length,outputSha256:await digest(runsPath),runSha256:'f'.repeat(64)};
  const sourceReportPath=write(path.join(dir,'report.json'),report);
  return {dir,writer,gate,report,spec:{schemaVersion:1,component:'roads',coreBounds:core,worldPath,writerManifestPath,structuralGatePath,sourceReportPath,sourcePath,runsPath},output:path.join(dir,'evidence.json')};
}
async function reject(f,pattern){await assert.rejects(()=>produceEvidence(f.spec,f.output),pattern);assert.equal(fs.existsSync(f.output),false);passed++;}

{
  const f=await fixture(),r=await produceEvidence(f.spec,f.output);
  assert.equal(r.evidence.featureCount,1);assert.equal(r.evidence.componentRuns,2);assert.equal(r.evidence.allRuns,4);
  assert.equal(r.evidence.mappedFeatureCount,null);assert.equal(r.evidence.sourceCoverageComplete,false);
  assert.equal(r.coverage.status,'included');assert.equal(r.evidence.runsSha256,f.report.outputSha256);passed++;
}
{
  const f=await fixture();f.gate.status='FAILED';write(f.spec.structuralGatePath,f.gate);await reject(f,/structural PASS/);
}
{
  const f=await fixture();f.gate.synthetic=true;write(f.spec.structuralGatePath,f.gate);await reject(f,/synthetic/);
}
{
  const f=await fixture();f.gate.comparedBlocks=1;write(f.spec.structuralGatePath,f.gate);await reject(f,/full-core/);
}
{
  const f=await fixture();f.gate.kind='independent-benchmark-result-gate';write(f.spec.structuralGatePath,f.gate);await reject(f,/Typed benchmark validator required/);
}
{
  const f=await fixture();f.report.outputSha256=f.report.runSha256;write(f.spec.sourceReportPath,f.report);await reject(f,/raw output hash/);
}
{
  const f=await fixture();f.spec.runsPath=write(path.join(f.dir,'other.jsonl'),JSON.stringify(run(40))+'\n');await reject(f,/not consumed/);
}
{
  const f=await fixture([run(20,'land'),run(30,'water')]);await reject(f,/Zero emission/);
}
for(const change of [{featureCount:1},{sourceCoverageComplete:false},{blockedDiagnostics:1},{unmappedSourceCount:1}]){
  const f=await fixture([run(20,'land')]);
  f.spec.sourceCoveragePath=write(path.join(f.dir,'source-coverage.json'),{status:'NO_FEATURES',synthetic:false,component:'roads',coreBounds:core,sourceSha256:await digest(f.spec.sourcePath),featureCount:0,sourceCoverageComplete:true,blockedDiagnostics:0,unmappedSourceCount:0,...change});
  await reject(f,/Zero emission/);
}
{
  const f=await fixture([run(20,'land')]);
  f.spec.sourceCoveragePath=write(path.join(f.dir,'source-coverage.json'),{status:'NO_FEATURES',synthetic:false,component:'roads',coreBounds:core,sourceSha256:await digest(f.spec.sourcePath),featureCount:0,sourceCoverageComplete:true,blockedDiagnostics:0,unmappedSourceCount:0});
  const r=await produceEvidence(f.spec,f.output);assert.equal(r.evidence.status,'NO_FEATURES');assert.equal(r.evidence.featureCount,0);assert.equal(r.coverage.status,'no_features');passed++;
}
async function coast(){
  const f=await fixture([run(30,'coast-face/1')]);f.spec.component='water';
  const rawRunsPath=write(path.join(f.dir,'raw.jsonl'),[run(30,'coast-face/1'),run(30,'coast-face/1',1050)].map(r=>JSON.stringify(r)+'\n').join(''));
  f.report={schema:'fork.coast-surface.v1',status:'emitted-unclipped',bounds:core,maskSha256:await digest(f.spec.sourcePath),outputSha256:await digest(rawRunsPath),statistics:{seaCells:2,unknownCells:0}};write(f.spec.sourceReportPath,f.report);
  f.spec.countryMaskPath=write(path.join(f.dir,'country.json'),{fixture:'country'});f.spec.foreignExclusionsPath=write(path.join(f.dir,'foreign.json'),{fixture:'foreign'});
  f.mask={schema:'fork.masked-runs.v1',allLayersFiltered:true,sourcesUnchanged:true,inputs:[{sha256:await digest(rawRunsPath)}],outputSha256:await digest(f.spec.runsPath),retainedRuns:1,countryMaskSha256:await digest(f.spec.countryMaskPath),foreignExclusionsSha256:await digest(f.spec.foreignExclusionsPath)};
  f.spec.maskManifestPath=write(path.join(f.dir,'mask.json'),f.mask);f.spec.rawRunsPath=rawRunsPath;
  f.oracle={status:'PASS',synthetic:false,component:'water',coreBounds:core,writerManifestSha256:await digest(f.spec.writerManifestPath),sourceSha256:await digest(f.spec.sourcePath),runsSha256:await digest(f.spec.runsPath),countryMaskSha256:f.mask.countryMaskSha256,foreignExclusionsSha256:f.mask.foreignExclusionsSha256,comparedBlocks:256,mismatches:0};
  f.spec.componentOraclePath=write(path.join(f.dir,'water-oracle.json'),f.oracle);return f;
}
{
  const f=await coast(),r=await produceEvidence(f.spec,f.output);assert.equal(r.evidence.featureCount,1);assert.equal(r.evidence.reportPath,fs.realpathSync(f.spec.componentOraclePath));assert.equal(r.evidence.sourceReportSha256,await digest(f.spec.sourceReportPath));passed++;
}
for(const key of ['countryMaskSha256','foreignExclusionsSha256','outputSha256']){
  const f=await coast();f.mask[key]='a'.repeat(64);write(f.spec.maskManifestPath,f.mask);await reject(f,/Hash mismatch|Masked run hash/);
}
{
  const f=await coast();f.mask.inputs[0].sha256='a'.repeat(64);write(f.spec.maskManifestPath,f.mask);await reject(f,/raw coast/);
}
{
  const f=await coast();f.oracle.status='PENDING';write(f.spec.componentOraclePath,f.oracle);await reject(f,/oracle PASS/);
}
{
  const f=await coast();delete f.spec.componentOraclePath;await reject(f,/componentOraclePath/);
}
async function multiFixture(){
  const f=await coast(),inlandRuns=write(path.join(f.dir,'inland.runs.jsonl'),JSON.stringify(run(30,'coast-face/1'))+'\n');
  f.writer.inputs.push({name:path.basename(inlandRuns),bytes:fs.statSync(inlandRuns).size,sha256:await digest(inlandRuns)});
  write(f.spec.writerManifestPath,f.writer);f.gate.writerManifestSha256=await digest(f.spec.writerManifestPath);write(f.spec.structuralGatePath,f.gate);
  f.oracle.writerManifestSha256=f.gate.writerManifestSha256;write(f.spec.componentOraclePath,f.oracle);
  const coastEvidence=await produceEvidence(f.spec,f.output),coastSpec=write(path.join(f.dir,'coast-spec.json'),f.spec);
  const inlandSource=write(path.join(f.dir,'inland-source.json'),{fixtureOnly:'different mapped source'});
  const inlandReport=write(path.join(f.dir,'inland-report.json'),{schema:'fork.roads-runs.v1',tile:core,blockedDiagnostics:0,sourceSha256:await digest(inlandSource),runCount:1,outputSha256:await digest(inlandRuns)});
  const inlandSpec={...f.spec,sourcePath:inlandSource,sourceReportPath:inlandReport,runsPath:inlandRuns};
  const inlandSpecPath=write(path.join(f.dir,'inland-spec.json'),inlandSpec),inlandEvidencePath=path.join(f.dir,'inland-evidence.json');
  const inlandEvidence=await produceEvidence(inlandSpec,inlandEvidencePath);
  const spec={schemaVersion:1,kind:'fork-multi-source-component-evidence',component:'water',coreBounds:core,writerManifestPath:f.spec.writerManifestPath,sourceSetPath:path.join(f.dir,'source-set.json'),contributors:[
    {id:'inland-water',specPath:inlandSpecPath,evidencePath:inlandEvidencePath,evidenceSha256:inlandEvidence.coverage.evidence_sha256},
    {id:'coast-water',specPath:coastSpec,evidencePath:f.output,evidenceSha256:coastEvidence.coverage.evidence_sha256}]};
  return {...f,multiSpec:spec,multiOutput:path.join(f.dir,'aggregate.json')};
}
async function rejectMulti(f,pattern){await assert.rejects(()=>produceMultiWaterEvidence(f.multiSpec,f.multiOutput),pattern);assert.equal(fs.existsSync(f.multiOutput),false);assert.equal(fs.existsSync(f.multiSpec.sourceSetPath),false);passed++;}
{
  const f=await multiFixture(),result=await produceMultiWaterEvidence(f.multiSpec,f.multiOutput);
  assert.equal(result.evidence.featureCount,2);assert.equal(result.evidence.requiredContributors.join(','),'coast-water,inland-water');
  assert.equal(result.evidence.sourceSha256,await digest(f.multiSpec.sourceSetPath));assert.equal(result.evidence.fullWorldAccepted,false);
  assert.equal(result.evidence.sourceCoverageComplete,false);passed++;
}
{
  const f=await multiFixture();f.multiSpec.contributors.pop();await rejectMulti(f,/Both coast-water/);
}
{
  const f=await multiFixture();f.multiSpec.contributors[0].id='coast-water';await rejectMulti(f,/Both coast-water/);
}
{
  const f=await multiFixture();f.multiSpec.contributors[0].evidenceSha256='a'.repeat(64);await rejectMulti(f,/Child evidence changed/);
}
{
  const f=await multiFixture(),child=f.multiSpec.contributors[0],e=JSON.parse(fs.readFileSync(child.evidencePath));e.featureCount=99;write(child.evidencePath,e);child.evidenceSha256=await digest(child.evidencePath);await rejectMulti(f,/independently rebound/);
}
{
  const f=await multiFixture();write(f.spec.componentOraclePath,{...f.oracle,status:'PENDING'});await rejectMulti(f,/oracle PASS/);
}
{
  const f=await multiFixture();const [a,b]=f.multiSpec.contributors;[a.id,b.id]=[b.id,a.id];await rejectMulti(f,/source schema differs/);
}
console.log(JSON.stringify({status:'PASS',tests:passed,fixtureDirectory:root,actualWorldsScanned:0}));
