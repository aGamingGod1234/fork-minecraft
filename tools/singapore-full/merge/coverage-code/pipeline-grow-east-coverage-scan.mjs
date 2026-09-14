import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import readline from 'node:readline';
import {fileURLToPath} from 'node:url';
const need=(x,m)=>{if(!x)throw Error(m);};
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
export async function scanOne(file,core){
  const h=crypto.createHash('sha256'),input=fs.createReadStream(file);input.on('data',b=>h.update(b));
  const layers={},roadIds=new Set(),waterIds=new Set();let allRuns=0,coreRuns=0,roadRuns=0,waterRuns=0,roadVoxels=0,waterVoxels=0;
  for await(const line of readline.createInterface({input,crlfDelay:Infinity})){
    if(!line.trim())continue;const r=JSON.parse(line.replace(/^\uFEFF/,''));
    need(['x','z','yMin','yMax','layer'].every(k=>Number.isSafeInteger(r[k]))&&r.yMin>=-64&&r.yMax<=320&&r.yMin<r.yMax,'Invalid run coordinates');
    need([10,20,30,40,50,60].includes(r.layer)&&typeof r.featureId==='string'&&r.featureId.length>0,'Invalid run identity/layer');allRuns++;
    if(r.x<core[0]||r.x>=core[2]||r.z<core[1]||r.z>=core[3])continue;coreRuns++;layers[r.layer]=(layers[r.layer]??0)+1;
    if([40,60].includes(r.layer)){roadIds.add(r.featureId);roadRuns++;roadVoxels+=r.yMax-r.yMin;}
    if(r.layer===30){waterIds.add(r.featureId);waterRuns++;waterVoxels+=r.yMax-r.yMin;}
  }
  return {runsSha256:h.digest('hex'),allRuns,coreRuns,layers,roads:{emittedFeatureIds:[...roadIds].sort(),componentRuns:roadRuns,componentVoxels:roadVoxels},inlandWater:{emittedFeatureIds:[...waterIds].sort(),componentRuns:waterRuns,componentVoxels:waterVoxels}};
}
export async function scanInventory(inventoryPath,outputRoot){
  need(!fs.existsSync(outputRoot),'New output directory required');const inventoryBytes=fs.readFileSync(inventoryPath),i=JSON.parse(inventoryBytes);
  need(i.kind==='fork-east12-coverage-preparation'&&i.cores.length===12&&i.scannedRuns===false,'Prepared East12 inventory required');
  const records=[],startedUtc=new Date().toISOString();fs.mkdirSync(outputRoot,{recursive:true});
  for(const c of i.cores){
    const run=c.consumedRuns.find(r=>path.basename(r.path)==='roads.runs.jsonl');need(run,'Road input missing');need(fs.statSync(run.path).size===run.bytes,'Road bytes changed');
    const scan=await scanOne(run.path,c.coreBounds);need(scan.runsSha256===run.sha256,'Road run hash changed');
    const reportBytes=fs.readFileSync(c.reports.roads.path);need(sha(reportBytes)===c.reports.roads.sha256,'Road report changed');const report=JSON.parse(reportBytes);need(report.outputSha256===scan.runsSha256&&report.runCount===scan.allRuns,'Road report run binding mismatch');
    const result={schemaVersion:1,kind:'fork-consumed-component-run-scan',status:'PASS',synthetic:false,id:c.id,coreBounds:c.coreBounds,writerManifestSha256:c.writerManifest.sha256,runsPath:run.path,runsBytes:run.bytes,sourceSha256:c.source.sha256,sourceReportSha256:c.reports.roads.sha256,...scan,sourceComplete:false,fullWorldAccepted:false};
    const p=path.join(outputRoot,c.id+'.json'),bytes=JSON.stringify(result,null,2)+'\n';fs.writeFileSync(p,bytes,{flag:'wx'});const record={id:c.id,path:p,sha256:sha(bytes),roadFeatureCount:scan.roads.emittedFeatureIds.length,inlandFeatureCount:scan.inlandWater.emittedFeatureIds.length};records.push(record);console.log(JSON.stringify(record));
  }
  const batch={schemaVersion:1,kind:'fork-east12-consumed-run-scans',status:'PASS',inventoryPath,inventorySha256:sha(inventoryBytes),startedUtc,completedUtc:new Date().toISOString(),records,scannedVoxels:false,generatedGeometry:false};
  const p=path.join(outputRoot,'batch.json'),bytes=JSON.stringify(batch,null,2)+'\n';fs.writeFileSync(p,bytes,{flag:'wx'});return {path:p,sha256:sha(bytes),cores:records.length};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const o={};for(let n=2;n<process.argv.length;n+=2)o[process.argv[n].slice(2)]=process.argv[n+1];
  const timer=setTimeout(()=>{console.error('Bounded scan exceeded 120s');process.exit(124);},120000);
  try{need(o.inventory&&o.output,'--inventory and --output required');console.log(JSON.stringify(await scanInventory(o.inventory,path.resolve(o.output))));}catch(e){console.error(e.stack);process.exitCode=1;}finally{clearTimeout(timer);}
}
