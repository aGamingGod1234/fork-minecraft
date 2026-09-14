import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {COHORTS,validateCores} from './pipeline-grow-east-coverage.mjs';
import {scanOne} from './pipeline-grow-east-coverage-scan.mjs';
import {preview,aggregateWater} from './pipeline-grow-east-coverage-materialize.mjs';
const INVENTORY_SHA='89352f351f1c41ce599a769a1dcbf6c9007e9141d62a5be27b53e78e425e614f';
const PROPOSED_ROAD_POLICY_V2='786fbec4dad726a47b00640e81da499ef383e3fc8b7440a9975526de9ad14710';
const need=(v,m)=>{if(!v)throw Error(m);};
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const read=(p,h)=>{const bytes=fs.readFileSync(p),digest=sha(bytes);if(h)need(h===digest,'Changed artifact '+p);return {path:p,sha256:digest,value:JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''))};};
const write=(p,v)=>{const bytes=JSON.stringify(v,null,2)+'\n';fs.writeFileSync(p,bytes,{flag:'wx'});return {path:p,sha256:sha(bytes)};};
export function loadRing(p){const i=read(p,INVENTORY_SHA);need(i.value.kind==='fork-ring14-coverage-preparation'&&i.value.sourceResult.sha256===COHORTS.ring14.pins.result&&i.value.job.sha256===COHORTS.ring14.pins.job,'Wrong frozen Ring cohort');validateCores(i.value.cores,'ring14');return i;}
export async function scanRing(inventoryPath){
  const i=loadRing(inventoryPath),out=path.join(path.dirname(inventoryPath),'run-scans');need(!fs.existsSync(out),'New scan output required');fs.mkdirSync(out);const records=[];
  for(const c of i.value.cores){
    const input=c.consumedRuns.find(r=>path.basename(r.path)==='roads.runs.jsonl');need(input&&fs.statSync(input.path).size===input.bytes,'Missing or changed consumed input');
    const scanned=await scanOne(input.path,c.coreBounds),report=read(c.reports.roads.path,c.reports.roads.sha256).value;
    need(scanned.runsSha256===input.sha256&&report.outputSha256===input.sha256&&report.runCount===scanned.allRuns,'Consumed byte/hash/count mismatch');
    const value={schemaVersion:1,kind:'fork-consumed-component-run-scan',status:'PASS',synthetic:false,id:c.id,coreBounds:c.coreBounds,writerManifestSha256:c.writerManifest.sha256,runsPath:input.path,runsBytes:input.bytes,sourceSha256:c.source.sha256,sourceReportSha256:c.reports.roads.sha256,...scanned,sourceComplete:false,fullWorldAccepted:false};
    const result=write(path.join(out,c.id+'.json'),value);records.push({id:c.id,...result,roadFeatureCount:scanned.roads.emittedFeatureIds.length,inlandFeatureCount:scanned.inlandWater.emittedFeatureIds.length});
  }
  return write(path.join(out,'batch.json'),{schemaVersion:1,kind:'fork-ring14-consumed-run-scans',status:'PASS',inventoryPath,inventorySha256:i.sha256,records,scannedVoxels:false,generatedGeometry:false});
}
export function previewsRing(inventoryPath,auditIndexPath){
  const i=loadRing(inventoryPath),root=path.dirname(inventoryPath),scans=read(path.join(root,'run-scans/batch.json')).value;
  need(scans.status==='PASS'&&scans.inventorySha256===i.sha256&&scans.records.length===14,'Scan batch identity mismatch');const records=[];
  const index=read(auditIndexPath);need(index.value.schema==='fork.source-omission-audit-index.v1'&&index.value.inventory.sha256===i.sha256&&index.value.coreCount===14&&index.value.readyCoreCount===14,'All fourteen exact omission audits must be ready');
  for(const c of i.value.cores){
    const scanRef=scans.records.find(r=>r.id===c.id),auditRef=index.value.cores.find(r=>r.id===c.id);need(scanRef&&auditRef&&auditRef.renderScopePolicyReady===true,'Missing actual scan or ready audit');const scan=read(scanRef.path,scanRef.sha256),audit=read(auditRef.classification.path,auditRef.classification.sha256),policy=read(auditRef.previewPolicy.path,auditRef.previewPolicy.sha256),dir=path.join(root,c.id),outputs={};
    // Exactly this frozen core received the coordinator's proposed-road policy.
    // The other thirteen remain on their original immutable approval.
    const pins=c.id==='minipc-ring-31232-30720-v1'?{...COHORTS.ring14.pins,policy:PROPOSED_ROAD_POLICY_V2}:COHORTS.ring14.pins;
    for(const component of ['roads','water']){const name=component==='roads'?'roads-evidence.json':scan.value.inlandWater.emittedFeatureIds.length?'inland-evidence.json':'inland-evidence-empty-v2.json';outputs[component]=write(path.join(dir,name),preview(c,scan,audit,policy,component,pins));}
    records.push({id:c.id,outputs,consumerValidation:'PENDING'});
  }
  return write(path.join(root,'preview-candidates.json'),{schemaVersion:1,kind:'fork-ring14-preview-candidates',status:'PENDING_CONSUMER_VALIDATION',inventorySha256:i.sha256,auditIndexPath:index.path,auditIndexSha256:index.sha256,records});
}
export async function waterRing(inventoryPath,evidenceModule){
  const i=loadRing(inventoryPath),root=path.dirname(inventoryPath),producer=await import(pathToFileURL(evidenceModule).href),records=[];
  for(const c of i.value.cores){
    const dir=path.join(root,c.id),coastPath=path.join(dir,'coast-evidence.json');await producer.produceEvidence(read(c.coastSpecPath).value,coastPath);
    const coast=read(coastPath),empty=path.join(dir,'inland-evidence-empty-v2.json'),inland=read(fs.existsSync(empty)?empty:path.join(dir,'inland-evidence.json')),sourceSetPath=path.join(dir,'water-source-set.json');
    const built=aggregateWater(c,coast,inland,sourceSetPath);write(sourceSetPath,built.sourceSet);const evidence=write(path.join(dir,'water-evidence.json'),built.evidence);
    records.push({id:c.id,...evidence,coastStatus:coast.value.status,inlandStatus:inland.value.status,consumerValidation:'PENDING'});
  }
  return write(path.join(root,'water-candidates.json'),{schemaVersion:1,kind:'fork-ring14-water-candidates',status:'PENDING_CONSUMER_VALIDATION',inventorySha256:i.sha256,records});
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const o={};for(let n=2;n<process.argv.length;n+=2)o[process.argv[n].slice(2)]=process.argv[n+1];const timer=setTimeout(()=>process.exit(124),120000);
  try{need(o.inventory&&['scan','previews','water'].includes(o.mode),'--inventory and valid --mode required');const result=o.mode==='scan'?await scanRing(o.inventory):o.mode==='previews'?(need(o['audit-index'],'--audit-index required'),previewsRing(o.inventory,o['audit-index'])):(need(o['evidence-module'],'--evidence-module required'),await waterRing(o.inventory,o['evidence-module']));console.log(JSON.stringify(result));}catch(e){console.error(e.stack);process.exitCode=1;}finally{clearTimeout(timer);}
}
