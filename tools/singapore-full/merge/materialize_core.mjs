import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {scanOne} from './coverage-code/pipeline-grow-east-coverage-scan.mjs';
import {preview,aggregateWater} from './coverage-code/pipeline-grow-east-coverage-materialize.mjs';
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const read=p=>{const b=fs.readFileSync(p);return {path:p,sha256:hash(b),value:JSON.parse(b.toString('utf8').replace(/^\uFEFF/,''))};};
const write=(p,v)=>{const b=JSON.stringify(v,null,2)+'\n';if(fs.existsSync(p)){if(fs.readFileSync(p,'utf8')!==b)throw Error('Immutable evidence changed: '+p);}else fs.writeFileSync(p,b,{flag:'wx'});return read(p);};
const prepared=read(process.argv[2]).value,root=path.dirname(process.argv[2]),c=prepared.core;
if(process.argv[3]==='water'){
 const coast=read(path.join(root,'coast-evidence.json')),inland=read(path.join(root,'inland-evidence.json'));
 const built=aggregateWater(c,coast,inland,path.join(root,'water-source-set.json'));
 write(path.join(root,'water-source-set.json'),built.sourceSet);write(path.join(root,'water-evidence.json'),built.evidence);
}else{
 let scan;
 const scanPath=path.join(root,'run-scan.json');
 if(fs.existsSync(scanPath))scan=read(scanPath);
 else{
  const values=await scanOne(prepared.roadRuns.path,c.coreBounds),report=read(c.reports.roads.path).value;
  if(values.runsSha256!==prepared.roadRuns.sha256||values.allRuns!==report.runCount||report.outputSha256!==values.runsSha256)throw Error('Consumed run/report mismatch');
  scan=write(scanPath,{schemaVersion:1,kind:'fork-consumed-component-run-scan',status:'PASS',synthetic:false,id:c.id,coreBounds:c.coreBounds,writerManifestSha256:c.writerManifest.sha256,runsPath:prepared.roadRuns.path,runsBytes:prepared.roadRuns.bytes,sourceSha256:c.source.sha256,sourceReportSha256:c.reports.roads.sha256,...values,sourceComplete:false,fullWorldAccepted:false});
 }
 for(const component of ['roads','water']){
  const evidence=preview(c,scan,read(prepared.audit.path),read(prepared.previewPolicy.path),component,prepared.pins);
  write(path.join(root,component==='roads'?'roads-evidence.json':'inland-evidence.json'),evidence);
 }
}
console.log(JSON.stringify({core:c.id,stage:process.argv[3]||'roads',status:'EVIDENCE_WRITTEN_AWAITING_CONSUMER'}));
