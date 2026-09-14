import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';

// Metadata-only preparation. Coverage remains pending until its independent
// source proof and the existing consumer validate the actual component.
export const PINS = Object.freeze({
  result: '10c950bdf4c9f81655ecaf491dbe7f5f883df893ae557c4225952ba0735d9119',
  job: 'eff5dd78a486860424409b992c1ae9a27fb1098322ac865b94751a8d7f0e1b3d',
  policy: 'b2de7df7459b7eb855eca520065e272dc1d7021f19be98c273a41f642b6e5846',
  roleBatch: '330e7854be0846dcf4c94f96a55588cc6a4d9956ae3c32014bc21ab0bd53d1ca',
});
export const COHORTS = Object.freeze({
  east12:{pins:PINS,resultJob:'e229b7e05e556e5e85ce32011459517983c816c808d94c0a6beaf3037610b0f8',inputDirectory:'grow-east12-inputs-20260913-1815',xs:[30720,30976,31232],zs:[29696,29952,30208,30464],idPrefix:'minipc-east'},
  ring14:{pins:{result:'e7e3a7adfc89e78eb91ec77a69c4f93b2869674e20c87a154901c42a2a702890',job:'db77ce3ea2650d5061136f29b3bb4224293a38a802a3896444ee934bb91bc3cc',policy:PINS.policy,roleBatch:'604e2860acde6809501943d1f0487a65ff5a82ec6707e4fe0879429ce9116931'},resultJob:'668843a9093b6b79164374b5203cfe3b4a417d8d0831705e130f004bc18d16fa',inputDirectory:'grow-ring14-inputs-20260913-1830',xs:[29696,29952,30208,30464,30720,30976,31232],zs:[29440,30720],idPrefix:'minipc-ring'},
});
const need=(v,m)=>{if(!v)throw Error(m);};
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
function read(p,expected){const bytes=fs.readFileSync(p),h=sha(bytes);if(expected)need(h===expected.toLowerCase(),'Frozen hash mismatch: '+p);return {path:fs.realpathSync(p),bytes:bytes.length,sha256:h,value:JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''))};}
const ref=r=>({path:r.path,bytes:r.bytes,sha256:r.sha256});
function checkedArtifact(a){need(a&&typeof a.path==='string'&&Number.isSafeInteger(a.bytes),'Artifact missing');const s=fs.statSync(a.path);need(s.size===a.bytes,'Artifact size changed: '+a.path);return read(a.path,a.sha256);}
export function validateCores(cores,cohort='east12'){
  const config=COHORTS[cohort];need(config,'Unapproved cohort');
  need(Array.isArray(cores)&&cores.length===config.xs.length*config.zs.length,'Exact approved cohort core count required');
  const ids=new Set();
  for(const c of cores){const b=c.coreBounds;need(Array.isArray(b)&&b.length===4&&config.xs.includes(b[0])&&config.zs.includes(b[1])&&b[2]===b[0]+256&&b[3]===b[1]+256,'Unexpected cohort core');need(c.id===`${config.idPrefix}-${b[0]}-${b[1]}-v1`&&!ids.has(c.id),'Duplicate or mismatched cohort core ID');ids.add(c.id);}
  return cores;
}
export function coastSpec(core,job,gate,oracleRoot){
  const dir=path.dirname(core.writerManifest.path),base=path.join(oracleRoot,core.id);
  return {schemaVersion:1,component:'water',coreBounds:core.coreBounds,
    writerManifestPath:core.writerManifest.path,worldPath:core.worldPath,structuralGatePath:gate.path,
    sourceReportPath:core.reports.coast.path,sourcePath:job.coastMask.path,
    runsPath:path.join(dir,'coast.masked.runs.jsonl'),rawRunsPath:path.join(dir,'coast.raw.runs.jsonl'),
    maskManifestPath:core.reports.coastMask.path,countryMaskPath:job.countryMask.path,foreignExclusionsPath:job.foreignMask.path,
    componentOraclePath:path.join(base,'water-oracle.json'),sourceCoveragePath:path.join(base,'source-coverage.json')};
}
export function subsetSpec(core,job,gate,component,auditRoot){
  const dir=path.dirname(core.writerManifest.path),audit=path.join(auditRoot,core.id);
  return {schemaVersion:1,kind:'fork-east-component-preview-request',component,
    runSource:component==='roads'?'roads':'inland-water',coreBounds:core.coreBounds,renderBounds:core.coreBounds,
    worldPath:core.worldPath,writerManifestPath:core.writerManifest.path,structuralGatePath:gate.path,
    sourcePath:core.source.path,sourceReportPath:core.reports.roads.path,runsPath:path.join(dir,'roads.runs.jsonl'),
    projectedNodesPath:path.join(dir,'projected-nodes.json'),countryMaskPath:job.countryMask.path,foreignExclusionsPath:job.foreignMask.path,
    classificationPath:path.join(audit,'classification.json'),policyPath:path.join(audit,'preview-policy.json'),
    requiredConsumer:'grow_preview.validate_preview',requestOnly:true,sourceComplete:false,routeComplete:false,fullFidelity:false};
}
export function prepare({fullRoot,worktreesRoot,outputRoot,waterProofRoot,cohort='east12'}){
  const config=COHORTS[cohort];need(config,'Unapproved cohort');const pins=config.pins,count=config.xs.length*config.zs.length;
  fullRoot=path.resolve(fullRoot);outputRoot=path.resolve(outputRoot);
  need(!fs.existsSync(outputRoot),'Output directory must be NEW');
  const resultPath=path.join(fullRoot,`queue/jobs/${config.resultJob}/attempt-1/output/grow-result.json`);
  const result=read(resultPath,pins.result),job=read(path.join(fullRoot,config.inputDirectory,'job.json'),pins.job);
  const policy=read(path.join(fullRoot,'approved-national-source-subset-policy-v1.json'),pins.policy);
  need(policy.value.status==='APPROVED_FOR_QUALIFIED_PREVIEW_JOBS'&&policy.value.acceptedByCoordinator===true,'Subset policy is not approved');
  const roleBatch=read(path.join(worktreesRoot,`full-singapore-fidelity/.work/fidelity/${cohort}-role-gates-1/batch.json`),pins.roleBatch);
  const roles=new Map(roleBatch.value.records.map(r=>[r.id,r]));need(roles.size===count,'Role proof count mismatch');
  const masks=Object.fromEntries(['coastMask','countryMask','foreignMask'].map(k=>[k,ref(checkedArtifact(job.value[k]))]));
  const auditRoot=path.join(fullRoot,`roads/${cohort}-omission-audit-v1`);
  const selected=validateCores(result.value.cores,cohort),pending=[];
  for(const c of selected){
    const role=roles.get(c.id);need(role&&same(role.bounds,c.coreBounds),'Role bounds mismatch');
    const gate=read(role.path,role.sha256),writer=checkedArtifact(c.writerManifest),source=checkedArtifact(c.source);
    need(gate.value.kind==='actual-world-structural-validation'&&gate.value.status==='PASS'&&gate.value.synthetic===false&&gate.value.writerManifestSha256===writer.sha256&&same(gate.value.coreBounds,c.coreBounds)&&gate.value.mismatches===0,'Actual component gate mismatch');
    need(gate.value.role==='assembly-component'&&gate.value.finalAssembledSafeSpawnRequired===true,'Assembly role proof missing');
    const reports=Object.fromEntries(['roads','coast','coastMask'].map(k=>[k,checkedArtifact(c.reports[k])]));
    need(reports.roads.value.sourceSha256===source.sha256,'Road source mismatch');
    need(reports.coast.value.maskSha256===masks.coastMask.sha256,'Coast source mismatch');
    const nodes=read(path.join(path.dirname(writer.path),'projected-nodes.json'));
    const dir=path.join(outputRoot,c.id),coast=coastSpec(c,job.value,gate,waterProofRoot),roads=subsetSpec(c,job.value,gate,'roads',auditRoot),inland=subsetSpec(c,job.value,gate,'water',auditRoot);
    pending.push({id:c.id,coreBounds:c.coreBounds,worldPath:c.worldPath,writerManifest:ref(writer),structuralGate:ref(gate),role:role.role,standaloneStatus:role.standaloneStatus,source:ref(source),projectedNodes:ref(nodes),reports:Object.fromEntries(Object.entries(reports).map(([k,v])=>[k,ref(v)])),
      // These immutable run descriptors are receipts, not a new semantic scan.
      consumedRuns:c.runs,rawRuns:c.rawRuns,blockedRoadDiagnostics:reports.roads.value.blockedDiagnostics,
      coverage:{roads:{status:'pending',requestPath:path.join(dir,'roads-request.json')},water:{status:'pending',requestPath:path.join(dir,'water-aggregate-request.json')}},
      coastSpecPath:path.join(dir,'coast-spec.json'),inlandRequestPath:path.join(dir,'inland-request.json'),
      blockers:['exact approved omission classification','consumed component run scan and preview consumer validation','independent coast mask-to-runs oracle and source coverage','strict coast consumer and multi-source water consumer validation'],
      artifactsToWrite:[['coast-spec.json',coast],['roads-request.json',roads],['inland-request.json',inland],['water-aggregate-request.json',{schemaVersion:1,kind:'fork-multi-source-component-preview',component:'water',requestOnly:true,coreBounds:c.coreBounds,writerManifestPath:writer.path,sourceSetPath:path.join(dir,'water-source-set.json'),requiredContributors:['coast-water','inland-water'],contributors:[{id:'coast-water',specPath:path.join(dir,'coast-spec.json'),evidencePath:path.join(dir,'coast-evidence.json')},{id:'inland-water',specPath:path.join(dir,'inland-request.json'),evidencePath:path.join(dir,'inland-evidence.json')}],sourceCoverageComplete:false,sourceComplete:false,routeComplete:false,fullFidelity:false,fullWorldAccepted:false}]]});
  }
  const chain=Object.fromEntries(['coast-receipt.json','independent-normalization.json'].map(n=>[n,ref(read(path.join(fullRoot,'data/coast-mask/national-v2',n)))]));
  chain.priorAudit=ref(read(path.join(fullRoot,'data/coast-mask/national-v1/independent-audit.json'),'13f3455568cfdc3fd38bfc5e56264621133a46fefad2d2e4eaad854ea5402f17'));
  fs.mkdirSync(outputRoot,{recursive:true});
  for(const c of pending){fs.mkdirSync(path.join(outputRoot,c.id));for(const [name,v] of c.artifactsToWrite)fs.writeFileSync(path.join(outputRoot,c.id,name),JSON.stringify(v,null,2)+'\n',{flag:'wx'});delete c.artifactsToWrite;}
  const inventory={schemaVersion:1,kind:`fork-${cohort}-coverage-preparation`,status:'AWAITING_INDEPENDENT_COMPONENT_PROOFS',metadataOnly:true,sourceResult:ref(result),job:ref(job),nationalSubsetPolicy:ref(policy),roleBatch:ref(roleBatch),masks,nationalCoastProofChain:chain,cores:pending,fullWorldAccepted:false,runtimeAccepted:false,generatedGeometry:false,scannedVoxels:false,scannedRuns:false,finalAssembledSafeSpawnRequired:true};
  const p=path.join(outputRoot,'inventory.json');fs.writeFileSync(p,JSON.stringify(inventory,null,2)+'\n',{flag:'wx'});return {...ref(read(p)),coreCount:pending.length};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const o={};for(let i=2;i<process.argv.length;i+=2)o[process.argv[i].slice(2)]=process.argv[i+1];
  try{need(o.full&&o.worktrees&&o.output&&o['water-proofs'],'--full --worktrees --output --water-proofs required');console.log(JSON.stringify(prepare({fullRoot:o.full,worktreesRoot:o.worktrees,outputRoot:o.output,waterProofRoot:o['water-proofs'],cohort:o.cohort??'east12'})));}catch(e){console.error(e.stack);process.exitCode=1;}
}
