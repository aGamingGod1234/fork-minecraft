import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import readline from 'node:readline';
import {fileURLToPath,pathToFileURL} from 'node:url';

// Read-only admission adapter. This binds existing proofs; it never runs or
// invents an oracle, changes source bytes, or accepts whole-world fidelity.
const FRAME={crs:'EPSG:3414',x:'easting',z:'60000-northing',blocksPerMeter:1};
const REGION='dimensions/minecraft/overworld/region';
const layerNames={terrain:10,landcover:20,water:30,road:40,building:50,bridge:60};
const need=(ok,message)=>{if(!ok)throw Error(message);};
const shaValue=(value,label)=>{need(typeof value==='string'&&/^[a-f\d]{64}$/i.test(value),'Invalid SHA-256: '+label);return value.toLowerCase();};
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const canonical=v=>Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;
const object=p=>{const v=JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));need(v&&typeof v==='object'&&!Array.isArray(v),'Expected JSON object '+p);return v;};
export async function digest(p){const h=crypto.createHash('sha256');for await(const b of fs.createReadStream(p))h.update(b);return h.digest('hex');}
function bounds(v,label){need(Array.isArray(v)&&v.length===4&&v.every(n=>Number.isSafeInteger(n)&&n%16===0)&&v[0]<v[2]&&v[1]<v[3],'Invalid half-open 16-aligned bounds: '+label);return v;}
const contains=(a,b)=>a[0]<=b[0]&&a[1]<=b[1]&&a[2]>=b[2]&&a[3]>=b[3];
const inCore=(r,b)=>r.x>=b[0]&&r.z>=b[1]&&r.x<b[2]&&r.z<b[3];
function clean(report,label){need(report.synthetic!==true,label+' is synthetic');for(const key of ['errors','fileHashErrors','blockErrors','coordinateErrors'])if(key in report)need(Array.isArray(report[key])&&report[key].length===0,label+' reports '+key);for(const key of ['mismatches','blockMismatches','occupancyMismatches','mismatchedCells','seamMismatchedCells','heightmapMismatches'])if(key in report)need(report[key]===0,label+' reports '+key);}
const normalizedOutputs=rows=>rows.map(r=>({path:r.path,bytes:r.bytes,sha256:shaValue(r.sha256,r.path)})).sort((a,b)=>a.path.localeCompare(b.path));

export async function scanRuns(p,core,component){
  const ids=new Set(),layers={},counts={allRuns:0,coreRuns:0,componentRuns:0,componentVoxels:0};
  for await(const line of readline.createInterface({input:fs.createReadStream(p),crlfDelay:Infinity})){
    if(!line.trim())continue;const r=JSON.parse(line.replace(/^\uFEFF/,''));
    need(r&&typeof r==='object'&&['x','z','yMin','yMax'].every(k=>Number.isSafeInteger(r[k]))&&r.yMin>=-64&&r.yMax<=320&&r.yMin<r.yMax,'Invalid vertical run');
    const layer=typeof r.layer==='number'?r.layer:layerNames[r.layer];need(Object.values(layerNames).includes(layer),'Unknown run layer');
    need(typeof r.featureId==='string'&&r.featureId.length>0,'Run requires source featureId');counts.allRuns++;
    if(!inCore(r,core))continue;counts.coreRuns++;layers[layer]=(layers[layer]??0)+1;
    if(component==='roads'?[40,60].includes(layer):layer===30){ids.add(r.featureId);counts.componentRuns++;counts.componentVoxels+=r.yMax-r.yMin;}
  }
  return {...counts,layers,emittedFeatureCount:ids.size,emittedFeatureIds:[...ids].sort()};
}

async function structural(spec,writer,writerHash,core,bind){
  need(writer.kind==='global-block-run-world','Unrecognized writer kind');
  need(Object.keys(writer.coordinateFrame??{}).length===Object.keys(FRAME).length&&Object.entries(FRAME).every(([k,v])=>writer.coordinateFrame?.[k]===v),'Writer grid mismatch');
  need(writer.dataVersion===4790&&writer.minecraftTarget==='26.1.2'&&writer.regionDirectory===REGION,'MC26 writer required');
  need(contains(bounds(writer.bounds,'writer'),core),'Core outside writer');
  const root=fs.realpathSync(spec.worldPath);need(Array.isArray(writer.outputs)&&writer.outputs.length>0,'Writer outputs missing');
  const outputs=normalizedOutputs(writer.outputs),names=new Set();
  for(const out of outputs){
    need(typeof out.path==='string'&&!out.path.includes('\\')&&!path.isAbsolute(out.path)&&!out.path.split('/').some(s=>!s||s==='.'||s==='..'||s.includes(':')),'Unsafe world output path');
    need(!names.has(out.path),'Duplicate writer output');names.add(out.path);
    need(out.path==='level.dat'||out.path==='data/minecraft/world_gen_settings.dat'||/^dimensions\/minecraft\/overworld\/region\/r\.-?\d+\.-?\d+\.mca$/.test(out.path),'Unsupported world artifact');
    const p=fs.realpathSync(path.join(root,out.path));need(p.startsWith(root+path.sep),'World output escapes root');
    need(Number.isSafeInteger(out.bytes)&&out.bytes>=0&&fs.statSync(p).size===out.bytes,'Output byte size changed');await bind(p,out.sha256);
  }
  need(names.has('level.dat')&&names.has('data/minecraft/world_gen_settings.dat')&&[...names].some(n=>n.startsWith(REGION+'/')),'Incomplete MC26 output inventory');
  const gate=object(spec.structuralGatePath);await bind(spec.structuralGatePath);
  need(gate.status==='PASS','Independent structural PASS required');clean(gate,'Structural gate');
  const required=(core[2]-core[0])*(core[3]-core[1])*384;
  if(gate.kind==='independent-benchmark-result-gate'){
    need(typeof spec.benchmarkValidatorPath==='string','Typed benchmark validator required');
    const summaryPath=spec.benchmarkSummaryPath??path.join(path.dirname(spec.structuralGatePath),'summary.json');
    const summary=object(summaryPath);await bind(summaryPath);
    need(summary.kind==='measured-independent-benchmark-validation'&&summary.status==='PASS'&&summary.metrics?.exitCode===0&&summary.metrics?.timedOut===false&&summary.metrics?.parentExited===false,'Measured benchmark validation did not pass');
    need(await bind(spec.structuralGatePath)===shaValue(summary.gateSha256,'summary gate'),'Benchmark summary gate changed');
    const receiptPath=spec.benchmarkReceiptPath??summary.receiptPath;
    need(fs.realpathSync(receiptPath)===fs.realpathSync(summary.receiptPath),'Benchmark receipt path mismatch');
    const receiptHash=await bind(receiptPath,summary.benchmarkReceiptSha256);need(receiptHash===shaValue(gate.benchmarkReceiptSha256,'gate receipt'),'Benchmark receipt differs from gate');
    need(shaValue(summary.oracleSha256,'measured oracle')===shaValue(gate.evidence?.oracleProof?.sha256,'gate oracle'),'Summary and gate oracle differ');
    need(path.basename(spec.benchmarkValidatorPath)==='validate-benchmark.mjs','Existing typed benchmark module required');
    for(const name of ['validate-benchmark.mjs','validate-benchmark-bindings.mjs'])await bind(path.join(path.dirname(spec.benchmarkValidatorPath),name),shaValue(summary.validatorCodeHashes?.[name],'measured validator '+name));
    const validator=await import(pathToFileURL(fs.realpathSync(spec.benchmarkValidatorPath)).href);
    const verified=validator.loadIndependentlyValidatedReceipt(receiptPath,spec.structuralGatePath),binding=verified.resultBindings;
    need(verified.status==='PASS'&&fs.realpathSync(binding.worldRoot)===root&&fs.realpathSync(binding.writerManifestPath)===fs.realpathSync(spec.writerManifestPath)&&same(binding.coreBounds,core),'Verified benchmark identity mismatch');
    need(same(normalizedOutputs(binding.outputs.map(o=>({...o,path:path.relative(root,fs.realpathSync(o.path)).replaceAll('\\','/')}))),outputs),'Verified benchmark outputs mismatch');
    need(shaValue(gate.evidence?.writerManifest?.sha256,'benchmark writer')===writerHash&&same(gate.comparisonBounds,core)&&gate.comparedCells===required,'Benchmark writer/core binding mismatch');
    return gate;
  }
  need(shaValue(gate.writerManifestSha256,'gate writer')===writerHash,'Structural writer binding changed');
  if(gate.kind==='actual-world-structural-validation'){
    need(gate.schemaVersion===1&&gate.synthetic===false,'Actual schema-1 oracle required');
    need(Number.isSafeInteger(gate.comparedBlocks)&&gate.comparedBlocks>=required&&gate.mismatches===0,'Incomplete/failed full-core block comparison');
    need(same(normalizedOutputs(gate.worldOutputs??[]),outputs),'Structural output inventory mismatch');
  }else if(gate.kind==='independent-joined-strip-structural-gate'){
    need(contains(bounds(gate.bounds,'gate'),core)&&Number.isSafeInteger(gate.chunkCount)&&gate.chunkCount>=(core[2]-core[0])*(core[3]-core[1])/256,'Structural core not covered');
    need(Number.isSafeInteger(gate.comparedCells)&&gate.comparedCells>=required,'Incomplete structural cell comparison');
    need(gate.mismatchedCells===0||gate.mismatches===0,'Explicit zero structural mismatches required');
    if('fileHashErrors' in gate)need(Array.isArray(gate.fileHashErrors)&&gate.fileHashErrors.length===0,'Output hashes failed');
    else{
      need(gate.regionDirectory===REGION&&Array.isArray(gate.errors)&&!gate.errors.length,'Rebound MC26 proof missing');
      shaValue(gate.priorGeometryOracleSha256,'prior oracle');shaValue(gate.metadataProofSha256,'metadata proof');
      const actual=(gate.regionIdentity??[]).map(r=>[r.newPath,shaValue(r.sha256,'region')]).sort();
      need(same(actual,outputs.filter(r=>r.path.startsWith(REGION+'/')).map(r=>[r.path,r.sha256]).sort()),'Rebound region identity mismatch');
    }
  }else throw Error('Unrecognized actual structural gate');
  return gate;
}

export async function produceEvidence(spec,outputPath,{verifyOnly=false}={}){
  need(spec.schemaVersion===1&&['roads','water'].includes(spec.component),'Schema-1 roads/water spec required');
  outputPath=path.resolve(outputPath);need(verifyOnly||!fs.existsSync(outputPath),'Output must be a NEW file');
  const core=bounds(spec.coreBounds,'core'),bindings=new Map();
  async function bind(p,expected){p=fs.realpathSync(p);const hash=await digest(p);if(expected)need(hash===shaValue(expected,p),'Hash mismatch: '+p);if(bindings.has(p))need(bindings.get(p)===hash,'Input changed during validation');bindings.set(p,hash);return hash;}
  for(const key of ['writerManifestPath','structuralGatePath','sourceReportPath','sourcePath','runsPath'])need(typeof spec[key]==='string','Missing '+key);
  const writerHash=await bind(spec.writerManifestPath),writer=object(spec.writerManifestPath);
  const gate=await structural(spec,writer,writerHash,core,bind);
  const sourceHash=await bind(spec.sourcePath),reportHash=await bind(spec.sourceReportPath),report=object(spec.sourceReportPath),runsHash=await bind(spec.runsPath);
  clean(report,'Source report');need(contains(bounds(report.tile??report.bounds,'source report'),core),'Source report does not cover core');
  need(Array.isArray(writer.inputs)&&writer.inputs.some(i=>shaValue(i.sha256,'writer consumed input')===runsHash&&i.bytes===fs.statSync(spec.runsPath).size),'Supplied run bytes were not consumed by this writer');
  const scanned=await scanRuns(spec.runsPath,core,spec.component);
  let wrappedReportPath=spec.sourceReportPath,wrappedReportHash=reportHash,maskEvidence=null;
  if(report.schema==='fork.roads-runs.v1'){
    need(report.blockedDiagnostics===0,'Road source has blocked or unreported diagnostics');
    need(shaValue(report.sourceSha256,'roads source')===sourceHash,'Road source hash mismatch');
    need(shaValue(report.outputSha256,'raw roads bytes')===runsHash,'Road raw output hash mismatch (semantic runSha256 is not accepted)');
    need(Number.isSafeInteger(report.runCount)&&report.runCount===scanned.allRuns,'Road raw run count mismatch');
  }else if(report.schema==='fork.coast-surface.v1'){
    need(spec.component==='water'&&report.status==='emitted-unclipped','Coast producer is not actual clipped water evidence');
    need(shaValue(report.maskSha256,'coast source mask')===sourceHash,'Coast classification source hash mismatch');
    for(const key of ['rawRunsPath','maskManifestPath','countryMaskPath','foreignExclusionsPath','componentOraclePath'])need(typeof spec[key]==='string','Masked coast requires '+key);
    const rawHash=await bind(spec.rawRunsPath,report.outputSha256),maskHash=await bind(spec.maskManifestPath),masked=object(spec.maskManifestPath);
    need(masked.schema==='fork.masked-runs.v1'&&masked.allLayersFiltered===true&&masked.sourcesUnchanged===true,'Actual all-layer mask receipt required');
    need(shaValue(masked.outputSha256,'masked output')===runsHash,'Masked run hash mismatch');
    need(Array.isArray(masked.inputs)&&masked.inputs.some(i=>shaValue(i.sha256,'unclipped mask input')===rawHash),'Mask receipt does not bind raw coast output');
    const countryHash=await bind(spec.countryMaskPath,masked.countryMaskSha256),foreignHash=await bind(spec.foreignExclusionsPath,masked.foreignExclusionsSha256);
    need(masked.retainedRuns===scanned.allRuns,'Masked output run count mismatch');
    const oracleHash=await bind(spec.componentOraclePath),oracle=object(spec.componentOraclePath);clean(oracle,'Coast oracle');
    need(oracle.status==='PASS'&&oracle.synthetic===false&&oracle.component==='water','Actual water oracle PASS required');
    need(same(bounds(oracle.coreBounds,'water oracle'),core)&&shaValue(oracle.writerManifestSha256,'water writer')===writerHash,'Water oracle core/writer mismatch');
    for(const [key,want] of Object.entries({sourceSha256:sourceHash,runsSha256:runsHash,countryMaskSha256:countryHash,foreignExclusionsSha256:foreignHash}))need(shaValue(oracle[key],key)===want,'Water oracle '+key+' mismatch');
    need(Number.isSafeInteger(oracle.comparedBlocks)&&oracle.comparedBlocks>0&&oracle.mismatches===0,'Water source-run oracle missing/failed');
    need(report.statistics?.unknownCells===0,'Coast source classification has unknown cells');
    wrappedReportPath=spec.componentOraclePath;wrappedReportHash=oracleHash;
    maskEvidence={maskManifestPath:fs.realpathSync(spec.maskManifestPath),maskManifestSha256:maskHash,rawRunsPath:fs.realpathSync(spec.rawRunsPath),rawRunsSha256:rawHash,countryMaskPath:fs.realpathSync(spec.countryMaskPath),countryMaskSha256:countryHash,foreignExclusionsPath:fs.realpathSync(spec.foreignExclusionsPath),foreignExclusionsSha256:foreignHash};
  }else throw Error('Unrecognized source report schema; no PASS invented');
  let sourceCoverage=null;
  if(spec.sourceCoveragePath){
    const coverageHash=await bind(spec.sourceCoveragePath),c=object(spec.sourceCoveragePath);clean(c,'Source coverage');
    need(['PASS','NO_FEATURES'].includes(c.status)&&c.synthetic===false&&c.component===spec.component,'Actual typed source coverage required');
    need(same(bounds(c.coreBounds,'source coverage'),core)&&shaValue(c.sourceSha256,'coverage source')===sourceHash,'Source coverage core/hash mismatch');
    need(Number.isSafeInteger(c.featureCount)&&c.featureCount>=scanned.emittedFeatureCount,'Source feature count contradicts emission');
    sourceCoverage={...c,path:fs.realpathSync(spec.sourceCoveragePath),sha256:coverageHash};
  }
  let status='PASS';
  if(scanned.emittedFeatureCount===0){
    need(sourceCoverage?.featureCount===0&&sourceCoverage.sourceCoverageComplete===true&&sourceCoverage.blockedDiagnostics===0&&sourceCoverage.unmappedSourceCount===0,'Zero emission is not NO_FEATURES: complete, unblocked, zero-mapped source proof required');
    status='NO_FEATURES';
  }else if(sourceCoverage){
    need(sourceCoverage.status==='PASS'&&sourceCoverage.blockedDiagnostics===0&&sourceCoverage.unmappedSourceCount===0,'Source coverage is blocked or unmapped');
  }
  const result={schemaVersion:1,component:spec.component,status,synthetic:false,coreBounds:core,writerManifestSha256:writerHash,sourceSha256:sourceHash,
    featureCount:status==='NO_FEATURES'?0:scanned.emittedFeatureCount,
    featureCountBasis:'distinct emitted component source IDs in exact core; never total mixed-layer source features',
    mappedFeatureCount:sourceCoverage?.featureCount??null,...scanned,
    sourceCoverageComplete:sourceCoverage?.sourceCoverageComplete===true,blockedDiagnostics:sourceCoverage?.blockedDiagnostics??report.blockedDiagnostics??null,unmappedSourceCount:sourceCoverage?.unmappedSourceCount??null,
    reportPath:fs.realpathSync(wrappedReportPath),reportSha256:wrappedReportHash,runsPath:fs.realpathSync(spec.runsPath),runsSha256:runsHash,
    sourceReportPath:fs.realpathSync(spec.sourceReportPath),sourceReportSha256:reportHash,
    structuralGatePath:fs.realpathSync(spec.structuralGatePath),structuralGateSha256:bindings.get(fs.realpathSync(spec.structuralGatePath)),structuralKind:gate.kind,
    sourceCoverageEvidence:sourceCoverage?{path:sourceCoverage.path,sha256:sourceCoverage.sha256}:null,maskEvidence,
    actualTerrainAccepted:false,waterHeightAccepted:false,fullWorldAccepted:false,runtimeAccepted:false};
  for(const [p,hash] of bindings)need(await digest(p)===hash,'Input changed before evidence publication: '+p);
  if(!verifyOnly){fs.mkdirSync(path.dirname(outputPath),{recursive:true});fs.writeFileSync(outputPath,JSON.stringify(result,null,2)+'\n',{flag:'wx'});}
  return {evidence:result,coverage:verifyOnly?null:{status:status==='NO_FEATURES'?'no_features':'included',evidence_path:outputPath,evidence_sha256:await digest(outputPath)}};
}

export async function produceMultiWaterEvidence(spec,outputPath){
  const kind='fork-multi-source-component-evidence',requiredContributors=['coast-water','inland-water'];
  need(spec.schemaVersion===1&&spec.kind===kind&&spec.component==='water','Typed multi-source water spec required');
  const core=bounds(spec.coreBounds,'aggregate core');
  need(typeof spec.writerManifestPath==='string'&&typeof spec.sourceSetPath==='string','Aggregate writer and NEW source-set paths required');
  outputPath=path.resolve(outputPath);const sourceSetPath=path.resolve(spec.sourceSetPath);
  need(outputPath!==sourceSetPath&&!fs.existsSync(outputPath)&&!fs.existsSync(sourceSetPath),'Aggregate outputs must be distinct NEW files');
  need(Array.isArray(spec.contributors)&&spec.contributors.length===2&&same(spec.contributors.map(c=>c.id).sort(),requiredContributors),'Both coast-water and inland-water contributors required exactly once');
  const writerHash=await digest(spec.writerManifestPath),writer=object(spec.writerManifestPath),children=[],inputBindings=new Map();
  inputBindings.set(fs.realpathSync(spec.writerManifestPath),writerHash);
  for(const input of [...spec.contributors].sort((a,b)=>a.id.localeCompare(b.id))){
    need(typeof input.specPath==='string'&&typeof input.evidencePath==='string','Contributor spec and evidence paths required');
    const evidencePath=fs.realpathSync(input.evidencePath),specPath=fs.realpathSync(input.specPath);
    need(!children.some(c=>c.evidencePath===evidencePath),'Duplicate child evidence path');
    const evidenceHash=await digest(evidencePath);need(evidenceHash===shaValue(input.evidenceSha256,'child evidence'),'Child evidence changed');
    inputBindings.set(evidencePath,evidenceHash);inputBindings.set(specPath,await digest(specPath));
    const childSpec=object(specPath),child=object(evidencePath);
    need(childSpec.component==='water'&&!childSpec.kind,'Every contributor must be a single-source water spec');
    const validated=(await produceEvidence(childSpec,evidencePath,{verifyOnly:true})).evidence;
    need(same(canonical(validated),canonical(child)),'Child evidence does not match independently rebound source proof');
    need(child.component==='water'&&child.synthetic===false&&same(child.coreBounds,core)&&child.writerManifestSha256===writerHash,'Child component/core/writer mismatch');
    need(child.status==='PASS'||child.status==='NO_FEATURES','Every water contributor must pass');
    const sourceReport=object(child.sourceReportPath);
    need(input.id==='inland-water'?sourceReport.schema==='fork.roads-runs.v1':sourceReport.schema==='fork.coast-surface.v1','Contributor source schema differs from its required role');
    const runsHash=await digest(child.runsPath),runsBytes=fs.statSync(child.runsPath).size;
    need(runsHash===child.runsSha256&&writer.inputs.some(r=>r.sha256.toLowerCase()===runsHash&&r.bytes===runsBytes&&r.name===path.basename(child.runsPath)),'Child run name/bytes/hash not present in writer inputs');
    need(Array.isArray(child.emittedFeatureIds)&&new Set(child.emittedFeatureIds).size===child.emittedFeatureCount&&child.emittedFeatureCount===child.featureCount,'Child feature count mismatch');
    if(child.status==='NO_FEATURES')need(child.featureCount===0&&child.sourceCoverageComplete===true&&child.blockedDiagnostics===0&&child.unmappedSourceCount===0,'Incomplete child source absence');
    children.push({id:input.id,evidencePath,evidenceSha256:evidenceHash,child});
  }
  const sourceSet={schemaVersion:1,kind:'fork-component-source-set',component:'water',coreBounds:core,writerManifestSha256:writerHash,
    contributors:children.map(c=>({id:c.id,sourceSha256:c.child.sourceSha256,runsSha256:c.child.runsSha256,sourceReportSha256:c.child.sourceReportSha256,evidenceSha256:c.evidenceSha256}))};
  const sourceSetBytes=JSON.stringify(sourceSet,null,2)+'\n',sourceHash=crypto.createHash('sha256').update(sourceSetBytes).digest('hex');
  const ids=[...new Set(children.flatMap(c=>c.child.emittedFeatureIds.map(id=>c.child.sourceSha256+':'+id)))].sort();
  const sumIfKnown=key=>children.every(c=>Number.isSafeInteger(c.child[key])&&c.child[key]>=0)?children.reduce((n,c)=>n+c.child[key],0):null;
  const status=ids.length?'PASS':'NO_FEATURES';
  if(status==='NO_FEATURES')need(children.every(c=>c.child.status==='NO_FEATURES'),'Zero aggregate emission is not source absence');
  const result={schemaVersion:1,kind,component:'water',status,synthetic:false,coreBounds:core,writerManifestSha256:writerHash,
    sourceSetPath,sourceSha256:sourceHash,featureCount:ids.length,emittedFeatureCount:ids.length,emittedFeatureIds:ids,
    featureCountBasis:'union of distinct emitted sourceSha256:featureId identities from every required contributor',
    sourceCoverageComplete:children.every(c=>c.child.sourceCoverageComplete===true),blockedDiagnostics:sumIfKnown('blockedDiagnostics'),unmappedSourceCount:sumIfKnown('unmappedSourceCount'),
    requiredContributors,contributors:children.map(c=>({id:c.id,evidencePath:c.evidencePath,evidenceSha256:c.evidenceSha256,sourceSha256:c.child.sourceSha256,runsSha256:c.child.runsSha256,sourceReportSha256:c.child.sourceReportSha256,status:c.child.status,featureCount:c.child.featureCount})),
    actualTerrainAccepted:false,waterHeightAccepted:false,fullWorldAccepted:false,runtimeAccepted:false};
  for(const [p,hash] of inputBindings)need(await digest(p)===hash,'Aggregate input changed during validation');
  fs.mkdirSync(path.dirname(sourceSetPath),{recursive:true});fs.mkdirSync(path.dirname(outputPath),{recursive:true});
  fs.writeFileSync(sourceSetPath,sourceSetBytes,{flag:'wx'});fs.writeFileSync(outputPath,JSON.stringify(result,null,2)+'\n',{flag:'wx'});
  return {evidence:result,coverage:{status:status==='NO_FEATURES'?'no_features':'included',evidence_path:outputPath,evidence_sha256:await digest(outputPath)}};
}

async function main(){const opts={};for(let i=2;i<process.argv.length;i+=2){need(process.argv[i].startsWith('--')&&process.argv[i+1],'Expected --name value');opts[process.argv[i].slice(2)]=process.argv[i+1];}need(opts.spec&&opts.output,'Usage: pipeline-grow-evidence.mjs --spec spec.json --output NEW.json');const spec=object(opts.spec),result=spec.kind==='fork-multi-source-component-evidence'?await produceMultiWaterEvidence(spec,opts.output):await produceEvidence(spec,opts.output);console.log(JSON.stringify(result.coverage));}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(e=>{console.error(e.stack);process.exitCode=1;});
