import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';

const digest=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const read=file=>{const bytes=fs.readFileSync(file);return {path:file,bytes:bytes.length,sha256:digest(bytes),value:JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''))};};
const equal=(a,b)=>JSON.stringify(a)===JSON.stringify(b);

export function referenceClosure(document){
  if(!Array.isArray(document?.elements))throw Error('Raw Overpass elements required');
  const ids=new Set(),counts={node:0,way:0,relation:0};
  for(const element of document.elements){
    if(!Object.hasOwn(counts,element.type)||!Number.isSafeInteger(element.id))throw Error('Invalid typed source identity');
    const key=element.type+'/'+element.id;
    if(ids.has(key))throw Error('Duplicate typed source identity '+key);
    ids.add(key);counts[element.type]++;
    if(element.type==='node'&&(!Number.isFinite(element.lon)||!Number.isFinite(element.lat)))throw Error('Invalid node coordinates '+key);
  }
  let checkedReferences=0;const missing=[];
  for(const element of document.elements){
    const refs=element.type==='way'?(element.nodes??[]).map(id=>'node/'+id):
      element.type==='relation'?(element.members??[]).map(m=>m.type+'/'+m.ref):[];
    if(element.type==='way'&&!Array.isArray(element.nodes))throw Error('Missing way node list');
    if(element.type==='relation'&&!Array.isArray(element.members))throw Error('Missing relation member list');
    for(const ref of refs){checkedReferences++;if(!ids.has(ref))missing.push({owner:element.type+'/'+element.id,reference:ref});}
  }
  return {complete:missing.length===0,counts,checkedReferences,missingCount:missing.length,missingSample:missing.slice(0,12)};
}

export function verifyRawSource(raw,receipt,expected){
  if(raw.sha256!==expected.sourceSha256||receipt.subsetSha256!==raw.sha256||receipt.closureSha256!==raw.sha256)throw Error('Raw source hash chain mismatch');
  if(receipt.referenceComplete!==true||receipt.sourceSha256!==expected.nationalSourceSha256)throw Error('Reference-completeness or national source binding mismatch');
  const [x0,z0,x1,z1]=expected.coreBounds,h=expected.halo;
  if(!equal(receipt.boundsEPSG3414,[x0-h,60000-z1-h,x1+h,60000-z0+h]))throw Error('Source export bounds mismatch');
  const closure=referenceClosure(raw.value);
  if(!closure.complete)throw Error('Raw source references are incomplete: '+JSON.stringify(closure.missingSample));
  if(!equal(Object.entries(receipt.counts).sort(),Object.entries(closure.counts).sort()))throw Error('Raw source counts differ from frozen receipt');
  return closure;
}

function packageFile(root,inventory,relative){
  const record=inventory.find(file=>file.path===relative);
  if(!record)throw Error('Frozen package does not bind '+relative);
  const full=path.resolve(root,relative);
  if(!full.startsWith(path.resolve(root)+path.sep))throw Error('Package path escapes root');
  const data=read(full);
  if(data.sha256!==record.sha256||data.bytes!==record.length)throw Error('Frozen package input changed: '+relative);
  return data;
}

export function resolveEastSources(fullRoot){
  const cores=[],missing=[],seen=new Set(),packageBindings=[];
  for(const batch of ['minipc-east-tiles-v1','minipc-east-tiles-v2']){
    const incoming=path.join(fullRoot,'incoming',batch),exchange=path.join(fullRoot,'exchange',batch);
    const pkg=read(path.join(incoming,'package-manifest.json'));
    if(pkg.value.format!=='tile-manifest-v1'||!Array.isArray(pkg.value.files))throw Error('Unsupported incoming package');
    const binding=packageFile(incoming,pkg.value.files,'binding-receipt.json');
    packageBindings.push({batch,path:pkg.path,sha256:pkg.sha256,bindingSha256:binding.sha256});
    for(const job of binding.value.jobs){
      const id=job.jobId,bounds=job.bounds;
      if(!/^minipc-east-\d+-\d+-v1$/.test(id)||seen.has(id))throw Error('Unexpected or duplicate core ID');
      seen.add(id);
      if(!Array.isArray(bounds)||bounds.length!==4||bounds.some(n=>!Number.isSafeInteger(n)||n%256)||
        bounds[2]-bounds[0]!==256||bounds[3]-bounds[1]!==256||bounds[0]<30720||bounds[2]>31488||bounds[1]<29696||bounds[3]>30720)throw Error('Unexpected east core bounds');
      const worker=packageFile(incoming,pkg.value.files,`results/${id}/worker-receipt.json`);
      const contract=worker.value.sourceContract;
      if(worker.value.jobId!==id||contract?.source?.id!==id||!equal(contract.job.coreBounds,bounds)||!equal(contract.source.coreBoundsXZ,bounds)||contract.source.sourceReferenceComplete!==true)throw Error('Worker source/core binding mismatch');
      const sourcePath=path.join(exchange,id+'.json'),receiptPath=sourcePath+'.manifest.json';
      if(!fs.existsSync(sourcePath)||!fs.existsSync(receiptPath)){
        missing.push({coreId:id,coreBounds:bounds,reason:'original frozen raw OSM or export receipt missing'});continue;
      }
      const raw=read(sourcePath),receipt=read(receiptPath);
      const closure=verifyRawSource(raw,receipt.value,{sourceSha256:contract.source.sourceSha256,
        nationalSourceSha256:contract.job.sourceSha256,coreBounds:bounds,halo:contract.source.haloBlocks});
      cores.push({coreId:id,coreBounds:bounds,sourcePath,sourceSha256:raw.sha256,sourceBytes:raw.bytes,
        receiptPath,receiptSha256:receipt.sha256,workerReceiptPath:worker.path,workerReceiptSha256:worker.sha256,
        nationalSourceSha256:receipt.value.sourceSha256,sourceExportBoundsEN:receipt.value.boundsEPSG3414,
        haloBlocks:contract.source.haloBlocks,referenceClosure:closure,
        projectedNodes:{status:'not-prepared',reason:'Existing projected GeoJSON contains buildings only; generate full node lookup from this raw source'},
        sourceClasses:receipt.value.sourceClasses,sourceCountryMaskApplied:receipt.value.countryMaskApplied});
    }
  }
  if(seen.size!==12)throw Error('Expected exactly 12 east cores');
  if(new Set(cores.map(c=>c.coreBounds.slice(0,2).join(','))).size!==cores.length)throw Error('Duplicate east core coordinates');
  cores.sort((a,b)=>a.coreBounds[1]-b.coreBounds[1]||a.coreBounds[0]-b.coreBounds[0]);
  return {schemaVersion:1,kind:'fork-east-grow-source-resolution',status:missing.length?'needs-source-export':'all-existing-sources-verified',
    grid:{kind:'EPSG:3414',x:'easting',z:'60000-northing',blocksPerMeter:1},extentXZ:[30720,29696,31488,30720],
    cores,packageBindings,missingSources:missing,
    exportRequests:missing.length?[{reason:'Recover missing sources with complete original references',boundsEPSG3414:[30688,29248,31520,30336],halo:32,requiresParentLease:true,status:'not-started'}]:[],
    nationalCoastSupplement:{requiredByGrowAdapter:true,status:'separate-frozen-input-required'},
    dispatchStarted:false,generationStarted:false,fullWorldAccepted:false};
}

export function main(argv){
  const args={};for(let i=0;i<argv.length;i+=2){if(!argv[i].startsWith('--')||argv[i+1]===undefined)throw Error('Expected --name value');args[argv[i].slice(2)]=argv[i+1];}
  if(!args.full||!args.output)throw Error('Usage: pipeline-grow-sources.mjs --full FULL_ROOT --output NEW_MANIFEST');
  if(fs.existsSync(args.output))throw Error('Preserve existing manifests; choose a new output');
  const manifest=resolveEastSources(path.resolve(args.full));
  fs.mkdirSync(path.dirname(path.resolve(args.output)),{recursive:true});
  const bytes=JSON.stringify(manifest,null,2)+'\n';fs.writeFileSync(args.output,bytes,{flag:'wx'});
  console.log(JSON.stringify({status:manifest.status,cores:manifest.cores.length,exportRequests:manifest.exportRequests.length,sha256:digest(bytes),path:path.resolve(args.output)}));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main(process.argv.slice(2));
