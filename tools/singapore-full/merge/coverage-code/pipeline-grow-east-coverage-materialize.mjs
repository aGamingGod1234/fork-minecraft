import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {PINS} from './pipeline-grow-east-coverage.mjs';
const need=(v,m)=>{if(!v)throw Error(m);};
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const read=(p,h)=>{const b=fs.readFileSync(p),s=sha(b);if(h)need(h===s,'Changed artifact '+p);return {path:p,sha256:s,value:JSON.parse(b.toString('utf8').replace(/^\uFEFF/,''))};};
function write(p,v){const b=JSON.stringify(v,null,2)+'\n';if(fs.existsSync(p)){need(fs.readFileSync(p,'utf8')===b,'Existing immutable output differs '+p);}else fs.writeFileSync(p,b,{flag:'wx'});return {path:p,sha256:sha(b)};}
export function preview(core,scan,audit,policy,component,pins=PINS){
  need(['roads','water'].includes(component),'Unsupported component');
  need(scan.value.status==='PASS'&&same(scan.value.coreBounds,core.coreBounds)&&scan.value.writerManifestSha256===core.writerManifest.sha256&&scan.value.sourceSha256===core.source.sha256&&scan.value.sourceReportSha256===core.reports.roads.sha256,'Scan binding mismatch');
  need(audit.value.renderScopePolicyReady===true&&policy.value.classificationReady===true&&policy.value.renderScopePolicyReady===true,'Omission classification is not ready');
  need(policy.value.classificationSha256===audit.sha256&&same(audit.value.core,core.coreBounds)&&audit.value.render[0]<=core.coreBounds[0]&&audit.value.render[1]<=core.coreBounds[1]&&audit.value.render[2]>=core.coreBounds[2]&&audit.value.render[3]>=core.coreBounds[3],'Classification core/receipt mismatch');
  for(const v of [audit.value,policy.value])need(v.bindings?.nationalPolicy?.sha256===pins.policy&&v.bindings?.growResult?.sha256===pins.result&&v.bindings?.job?.sha256===pins.job,'Policy/job/result binding missing');
  need(audit.value.integrity?.fatalErrors?.length===0&&audit.value.gates?.sourceComplete===false&&audit.value.gates?.routeComplete===false&&audit.value.gates?.fullFidelity===false,'Fatal or unqualified source audit');
  const selected=component==='roads'?scan.value.roads:scan.value.inlandWater,ids=selected.emittedFeatureIds;
  need(Array.isArray(ids)&&new Set(ids).size===ids.length,'Invalid scanned feature IDs');
  const empty=component==='water'&&ids.length===0;
  return {schemaVersion:1,kind:'fork-rendered-source-subset-preview',status:empty?'EMPTY_RENDERED_SUBSET':'RENDERED_SUBSET',component,runSource:component==='roads'?'roads':'inland-water',synthetic:false,coreBounds:core.coreBounds,
    writerManifestSha256:core.writerManifest.sha256,sourceSha256:core.source.sha256,featureCount:ids.length,emittedFeatureCount:ids.length,emittedFeatureIds:ids,
    featureCountBasis:'distinct emitted source IDs from hash-bound exact-core consumed-run scan',componentRuns:selected.componentRuns,componentVoxels:selected.componentVoxels,
    reportPath:core.reports.roads.path,reportSha256:core.reports.roads.sha256,sourceReportPath:core.reports.roads.path,sourceReportSha256:core.reports.roads.sha256,
    runsPath:scan.value.runsPath,runsSha256:scan.value.runsSha256,classificationPath:audit.path,classificationSha256:audit.sha256,policyPath:policy.path,policySha256:policy.sha256,
    blockedDiagnostics:audit.value.counts.blocked,omissions:audit.value.omissions,quarantinedSourceFeatures:audit.value.quarantinedSourceFeatures??[],globalSourceGeometryComplete:audit.value.globalSourceGeometryComplete,
    runScanPath:scan.path,runScanSha256:scan.sha256,structuralGatePath:core.structuralGate.path,structuralGateSha256:core.structuralGate.sha256,
    ...(empty?{emptyInlandSubset:true,sourceWaterAbsenceProven:false,emptyScanPath:scan.path,emptyScanSha256:scan.sha256}:{}),
    sourceCoverageComplete:false,unmappedSourceCount:null,sourceComplete:false,routeComplete:false,fullFidelity:false,fullWorldAccepted:false,coastWaterAccepted:false,runtimeAccepted:false};
}
export function aggregateWater(core,coast,inland,sourceSetPath){
  need(['PASS','NO_FEATURES'].includes(coast.value.status)&&coast.value.maskEvidence,'Strict coast proof required');
  need(inland.value.kind==='fork-rendered-source-subset-preview'&&['RENDERED_SUBSET','EMPTY_RENDERED_SUBSET'].includes(inland.value.status),'Qualified inland child required');
  const children=[{id:'coast-water',...coast},{id:'inland-water',...inland}];
  for(const c of children)need(same(c.value.coreBounds,core.coreBounds)&&c.value.writerManifestSha256===core.writerManifest.sha256,'Water child core/writer mismatch');
  const contributors=children.map(c=>({id:c.id,evidencePath:c.path,evidenceSha256:c.sha256,sourceSha256:c.value.sourceSha256,runsSha256:c.value.runsSha256,sourceReportSha256:c.value.sourceReportSha256,status:c.value.status,featureCount:c.value.featureCount}));
  const sourceSet={schemaVersion:1,kind:'fork-component-source-set',component:'water',coreBounds:core.coreBounds,writerManifestSha256:core.writerManifest.sha256,contributors:contributors.map(({evidencePath,status,featureCount,...r})=>r)};
  const sourceSha256=sha(JSON.stringify(sourceSet,null,2)+'\n');
  const ids=[...new Set(children.flatMap(c=>c.value.emittedFeatureIds.map(id=>c.value.sourceSha256+':'+id)))].sort();
  const sum=key=>children.every(c=>Number.isSafeInteger(c.value[key])&&c.value[key]>=0)?children.reduce((v,c)=>v+c.value[key],0):null;
  return {sourceSet,evidence:{schemaVersion:1,kind:'fork-multi-source-component-preview',status:'RENDERED_SUBSET',component:'water',synthetic:false,coreBounds:core.coreBounds,writerManifestSha256:core.writerManifest.sha256,
    sourceSetPath,sourceSha256,requiredContributors:['coast-water','inland-water'],contributors,featureCount:ids.length,emittedFeatureCount:ids.length,emittedFeatureIds:ids,
    blockedDiagnostics:sum('blockedDiagnostics'),unmappedSourceCount:sum('unmappedSourceCount'),omissions:inland.value.omissions,quarantinedSourceFeatures:inland.value.quarantinedSourceFeatures??[],globalSourceGeometryComplete:inland.value.globalSourceGeometryComplete,
    ...(inland.value.status==='EMPTY_RENDERED_SUBSET'?{emptyInlandSubset:true,sourceWaterAbsenceProven:false}:{}),
    sourceCoverageComplete:false,sourceComplete:false,routeComplete:false,fullFidelity:false,fullWorldAccepted:false,runtimeAccepted:false}};
}
export function materializePreviews(inventoryPath,{coreId}={}){
  const inventory=read(inventoryPath),root=path.dirname(inventoryPath),batch=read(path.join(root,'run-scans/batch.json'));
  need(batch.value.status==='PASS'&&batch.value.inventorySha256===inventory.sha256,'Scan batch mismatch');
  const results=[];
  for(const c of inventory.value.cores.filter(c=>!coreId||c.id===coreId)){
    const scanRef=batch.value.records.find(r=>r.id===c.id);need(scanRef,'Missing scan');const scan=read(scanRef.path,scanRef.sha256);
    const request=read(c.coverage.roads.requestPath).value,audit=read(request.classificationPath),policy=read(request.policyPath);
    const outputs={};
    for(const component of ['roads','water']){const name=component==='roads'?'roads-evidence.json':scan.value.inlandWater.emittedFeatureIds.length?'inland-evidence.json':'inland-evidence-empty-v2.json';outputs[component]=write(path.join(root,c.id,name),preview(c,scan,audit,policy,component));}
    results.push({id:c.id,coreBounds:c.coreBounds,writerManifest:c.writerManifest,outputs,consumerValidation:'PENDING'});
  }
  need(results.length>0,'Unknown core');return results;
}
export async function materializeWater(inventoryPath,evidenceModulePath,{coreId}={}){
  const inventory=read(inventoryPath),root=path.dirname(inventoryPath),producer=await import(pathToFileURL(evidenceModulePath).href),results=[];
  for(const c of inventory.value.cores.filter(c=>!coreId||c.id===coreId)){
    const dir=path.join(root,c.id),coastPath=path.join(dir,'coast-evidence.json'),spec=read(c.coastSpecPath).value;
    // The strict producer rebinds the actual per-core mask oracle and structural
    // proof, including independently established absence for empty coastal input.
    if(fs.existsSync(coastPath))await producer.produceEvidence(spec,coastPath,{verifyOnly:true});
    else await producer.produceEvidence(spec,coastPath);
    const coast=read(coastPath),emptyPath=path.join(dir,'inland-evidence-empty-v2.json'),inland=read(fs.existsSync(emptyPath)?emptyPath:path.join(dir,'inland-evidence.json'));
    const sourceSetPath=path.join(dir,'water-source-set.json'),built=aggregateWater(c,coast,inland,sourceSetPath);write(sourceSetPath,built.sourceSet);
    const evidence=write(path.join(dir,'water-evidence.json'),built.evidence);
    results.push({id:c.id,coastStatus:coast.value.status,coastFeatureCount:coast.value.featureCount,inlandStatus:inland.value.status,waterEvidence:evidence,consumerValidation:'PENDING'});
  }
  need(results.length>0,'Unknown core');return results;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const o={};for(let n=2;n<process.argv.length;n+=2)o[process.argv[n].slice(2)]=process.argv[n+1];
  try{need(o.inventory,'--inventory required');const result=o.mode==='water'?(need(o['evidence-module'],'--evidence-module required'),await materializeWater(o.inventory,o['evidence-module'],{coreId:o.core})):materializePreviews(o.inventory,{coreId:o.core});console.log(JSON.stringify(result));}catch(e){console.error(e.stack);process.exitCode=1;}
}
