import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {createBindingFixture} from './validate-benchmark-bindings.test.mjs';
import {measure, inventory, estimate, validateConfig, validateAdmission, administrativeScenarios, loadIndependentlyValidatedReceipt} from './validate-benchmark.mjs';
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'fork-benchmark-fixture-'));
try {
  const fixture = path.join(root, 'fake.mjs');
  fs.writeFileSync(fixture, [
    "import fs from 'node:fs'; import {spawn} from 'node:child_process';",
    "const [mode,output]=process.argv.slice(2);",
    "if(mode==='parent') { spawn(process.execPath,[import.meta.filename,'child',output],{stdio:'inherit',detached:true}).unref(); }",
    "else if(mode==='child') { const until=Date.now()+650; while(Date.now()<until) Math.sqrt(Date.now()); fs.writeFileSync(output,Buffer.alloc(4096,5)); }",
    "else if(mode==='timeout') { spawn(process.execPath,[import.meta.filename,'delayed',output],{stdio:'inherit',detached:true}).unref(); }",
    "else if(mode==='delayed') { setTimeout(()=>fs.writeFileSync(output,'escaped'),2500); }",
    "else if(mode==='fail') process.exit(7);"
  ].join('\n'));
  const request = mode => ({argv: [process.execPath, fixture, mode, path.join(root, mode + '.out')],
    cwd: root, timeoutSeconds: 5, maxCores: 1, maxMemoryBytes: 1024 ** 3, sampleIntervalMs: 20});
  const run = mode => measure(request(mode), path.join(root, mode + '.json'), path.join(root, mode + '.stdout'), path.join(root, mode + '.stderr'));
  const normal = run('parent');
  assert.equal(normal.exitCode, 0);
  assert.equal(normal.timedOut, false);
  assert(normal.totalProcesses >= 2, 'Job includes child after root exit');
  assert(normal.cpuSeconds > 0.2, 'Job includes CPU of exited busy child');
  assert(normal.wallSeconds >= 0.5, 'Waits until child exits');
  assert(normal.peakSampledRssBytes > 0);
  assert(normal.peakJobCommitBytes > 0);
  assert.equal(normal.processorOffset,2);
  assert.equal(normal.affinityMask,'0x4');
  const bytes = inventory([path.join(root, 'parent.out'), path.join(root, 'parent.out')]);
  assert.equal(bytes.bytes, 4096, 'Deduplicates shared source paths');
  assert.equal(bytes.files, 1);
  const sourceHash = inventory([path.join(root, 'parent.out')], true).inventorySha256;
  fs.writeFileSync(path.join(root, 'parent.out'), Buffer.alloc(4096, 6));
  assert.notEqual(inventory([path.join(root, 'parent.out')], true).inventorySha256, sourceHash, 'Source content changes invalidate binding even at the same length');
  const offset = measure({...request('parent'),processorOffset:4}, path.join(root, 'offset.json'),
    path.join(root, 'offset.stdout'),path.join(root, 'offset.stderr'));
  assert.equal(offset.processorOffset,4);
  assert.equal(offset.affinityMask,'0x10');
  const failed = run('fail');
  assert.equal(failed.exitCode, 7);
  const timeout = measure({...request('timeout'), timeoutSeconds: 1}, path.join(root, 'timeout.json'),
    path.join(root, 'timeout.stdout'), path.join(root, 'timeout.stderr'));
  assert.equal(timeout.timedOut, true);
  assert(timeout.wallSeconds < 3, 'Bounded timeout');
  assert(!fs.existsSync(path.join(root, 'timeout.out')));
  assert.throws(() => measure({...request('missing'), argv: [path.join(root, 'missing.exe')]},
    path.join(root, 'missing.json'), path.join(root, 'missing.stdout'), path.join(root, 'missing.stderr')), /helper failed/);
  fs.writeFileSync(path.join(root, 'seam.json'), JSON.stringify({status:'FAIL',comparedCells:10,mismatchedCells:1}));
  assert.throws(() => validateConfig({schemaVersion:1,mode:'real',cases:[{}],seamEvidence:'seam.json'},root), /Seam gate/);
  assert.throws(() => validateConfig({schemaVersion:1,mode:'fake',cases:[]},root), /Only mode real/);
  const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  const proofPath = path.join(root, 'proof.json'), pilotPath = path.join(root, 'pilot.json');
  fs.writeFileSync(proofPath, JSON.stringify({status:'PASS',syntheticFixture:true}));
  const gate = {schemaVersion:1,kind:'fork-experimental-benchmark-admission',scope:'pilot-policy',
    status:'PASS',acceptedByCoordinator:true,approvedBy:'synthetic-test',independentValidationRequired:true,
    productionAccepted:false,caseBindings:[{id:'dense-fixture'}],
    qualityProofs:{roadsV2:{path:'proof.json',sha256:sha(proofPath)},streamingEquivalence:{path:'proof.json',sha256:sha(proofPath)}}};
  const admit = value => { fs.writeFileSync(pilotPath,JSON.stringify(value));
    return validateAdmission({admissionEvidence:pilotPath,admissionEvidenceSha256:sha(pilotPath)},root); };
  assert.equal(admit(gate).scope,'pilot-policy');
  assert.throws(()=>admit({...gate,acceptedByCoordinator:false}),/coordinator-approved/);
  assert.throws(()=>admit({...gate,productionAccepted:true}),/cannot confer/);
  assert.throws(()=>admit({...gate,caseBindings:[1,2,3,4].map(id=>({id}))}),/at most three/);
  fs.writeFileSync(proofPath,JSON.stringify({status:'FAIL'}));
  assert.throws(()=>admit(gate),/proof must match and PASS/);
  const experimentalPath=path.join(root,'experimental.json'),validationPath=path.join(root,'validation.json');
  const generatedRoot=path.join(root,'generated');fs.mkdirSync(generatedRoot);fs.writeFileSync(path.join(generatedRoot,'fixture.mca'),'fake');
  const experiment={mode:'real',status:'WRITTEN_UNACCEPTED',outputRoot:generatedRoot,output:inventory([generatedRoot],true),
    coreOrigin:[0,0],coreSize:1024,coreAreaM2:1024**2};
  const oraclePath=path.join(root,'oracle.json'),oracle={status:'PASS',bounds:[0,0,1024,1024],comparedCells:1024**2*384,
    mismatchedCells:0,errors:[],heightmapMismatches:0,missingColumns:0,inputErrorCount:0,metadataErrorCount:0,
    sameLayerConflictingCells:0,chunkCount:4096,comparedCoreChunkCount:4096};
  fs.writeFileSync(oraclePath,JSON.stringify(oracle));
  fs.writeFileSync(experimentalPath,JSON.stringify(experiment));
  fs.writeFileSync(validationPath,JSON.stringify({status:'PASS',benchmarkReceiptSha256:sha(experimentalPath)}));
  assert.throws(()=>loadIndependentlyValidatedReceipt(experimentalPath,validationPath),/Typed independent/);
  const accepted={schemaVersion:1,kind:'independent-benchmark-result-gate',status:'PASS',
    benchmarkReceiptSha256:sha(experimentalPath),outputInventorySha256:experiment.output.inventorySha256,
    comparisonScope:'full-volume',comparisonBounds:[0,0,1024,1024],comparedCells:1024**2*384,mismatchedCells:0,
    checks:{globalChunkCoordinates:true,metadata:true,heightmaps:true},fileHashErrors:[],
    evidence:{writerManifest:{path:proofPath,sha256:sha(proofPath)},sourceRuns:[{path:proofPath,sha256:sha(proofPath)}],
      oracleProof:{path:oraclePath,sha256:sha(oraclePath)}}};
  const accept=value=>{fs.writeFileSync(validationPath,JSON.stringify(value));return loadIndependentlyValidatedReceipt(experimentalPath,validationPath);};
  assert.throws(()=>accept(accepted),/Result binding:/,'Typed gate alone cannot substitute for complete writer/source/world bindings');
  assert.throws(()=>accept({...accepted,comparedCells:0}),/all 384 Y/);
  assert.throws(()=>accept({...accepted,comparisonBounds:[0,0,1024,1025]}),/exact measured owned core/);
  assert.throws(()=>accept({...accepted,comparisonScope:'sample'}),/full-volume/);
  assert.throws(()=>accept({...accepted,fileHashErrors:['tampered']}),/file hashes/);
  assert.throws(()=>accept({...accepted,checks:{...accepted.checks,heightmaps:false}}),/heightmaps/);
  assert.throws(()=>accept({...accepted,evidence:{...accepted.evidence,sourceRuns:[]}}),/source runs/);
  fs.writeFileSync(oraclePath,JSON.stringify({...oracle,heightmapMismatches:1}));
  assert.throws(()=>accept({...accepted,evidence:{...accepted.evidence,oracleProof:{path:oraclePath,sha256:sha(oraclePath)}}}),/Actual oracle metadata/);
  fs.writeFileSync(oraclePath,JSON.stringify(oracle));
  fs.writeFileSync(path.join(generatedRoot,'fixture.mca'),'FAKE');
  assert.throws(()=>accept(accepted),/output content inventory/);
  const complete=createBindingFixture(root,'complete-loader-binding');
  Object.assign(complete.oracle,{comparedCells:1024**2*384,mismatchedCells:0,heightmapMismatches:0,missingColumns:0,
    inputErrorCount:0,metadataErrorCount:0,sameLayerConflictingCells:0,chunkCount:4096,comparedCoreChunkCount:4096});
  complete.refresh();
  const completeReceipt={...complete.receipt,mode:'real',status:'WRITTEN_UNACCEPTED',coreAreaM2:1024**2,output:inventory([complete.output],true)};
  const completeReceiptPath=path.join(root,'complete-receipt.json'),completeGatePath=path.join(root,'complete-gate.json');
  fs.writeFileSync(completeReceiptPath,JSON.stringify(completeReceipt));
  fs.writeFileSync(completeGatePath,JSON.stringify({...accepted,...complete.validation,benchmarkReceiptSha256:sha(completeReceiptPath),
    outputInventorySha256:completeReceipt.output.inventorySha256}));
  const loaded=loadIndependentlyValidatedReceipt(completeReceiptPath,completeGatePath);
  assert.equal(loaded.status,'PASS');assert.equal(loaded.productionAccepted,false);
  const rows = ['dense','rural','coast'].map((c,i)=>({mode:'real',status:'PASS',class:c,representative:true,
    coreAreaM2:100,settingsSha256:'same',executableSha256:'same',generatorArtifactsSha256:'same',
    metrics:{wallSeconds:i+1,cpuSeconds:0.5,outputLogicalBytes:1000}}));
  const target = {dense:1000,rural:2000,coast:3000};
  assert.equal(estimate([],target).available,false);
  assert.equal(estimate(rows.map(r=>({...r,mode:'synthetic'})),target).available,false);
  assert.equal(estimate(rows.map(r=>({...r,representative:false})),target).available,false);
  assert.equal(estimate(rows.slice(0,2),target).available,false);
  assert.equal(estimate(rows.map((r,i)=>({...r,settingsSha256:String(i)})),target).available,false);
  assert.equal(estimate(rows,target).serialWallSeconds,140);
  assert.equal(estimate(rows.map(r=>({...r,status:'WRITTEN_UNACCEPTED'})),target).available,false);
  assert.equal(administrativeScenarios(rows).available,false);
  const scenario=administrativeScenarios(rows.map(r=>({...r,coreSize:1024,halo:128,coreAreaM2:1024**2})));
  assert.equal(scenario.scenarioEnvelope.wallSeconds.minimum,1716);
  assert.equal(scenario.scenarioEnvelope.wallSeconds.maximum,5148);
  assert.equal(scenario.landEstimate.available,false);
  await new Promise(resolve=>setTimeout(resolve,1700));
  assert(!fs.existsSync(path.join(root, 'timeout.out')), 'Timed-out descendant cannot write later');
  console.log(JSON.stringify({status:'PASS',mode:'synthetic-fixture-only',checks:[
    'descendant lifetime and CPU included','sampled RSS and peak job commit present','processor offsets2/4 record distinct affinity masks','exit code retained',
    'owned child tree terminated on timeout','missing executable fails','failed seam blocks real run',
    'source bytes deduplicated','synthetic/smoke/missing/mixed measurements cannot yield estimates',
    'class-weighted arithmetic uses measured inputs','pilot admission requires coordinator approval and matching focused proofs',
    'pilot scope rejects production acceptance and excess cases','typed independent validation binds exact receipt and unchanged output content',
    'full owned-core volume and actual oracle metadata/heightmaps required',
    'administrative scenarios do not infer land totals'],
    fixtureMetrics:{normal,timeout,nonzeroExit:failed.exitCode},
    wholeSingaporeEstimate:{available:false,reason:'No representative generator runs performed'}},null,2));
} catch(error) {
  for(const file of fs.readdirSync(root).filter(f=>f.endsWith('.stderr')||f==='parent.json')) console.error(fs.readFileSync(path.join(root,file),'utf8'));
  throw error;
} finally {
  const resolved=path.resolve(root), base=path.resolve(os.tmpdir())+path.sep;
  if(!resolved.startsWith(base)||!path.basename(resolved).startsWith('fork-benchmark-fixture-')) throw Error('Unsafe fixture cleanup path');
  fs.rmSync(resolved,{recursive:true,force:true});
}
