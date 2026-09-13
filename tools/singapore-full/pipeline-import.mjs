import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import readline from 'node:readline';
import {pathToFileURL} from 'node:url';

const DEFAULT_BOUNDS=[30720,29696,31488,30720];
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const validHash=h=>typeof h==='string'&&/^[a-f0-9]{64}$/i.test(h);
const inside=(p,r)=>p!==r&&p.startsWith(r+path.sep);
const json=bytes=>JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''));
const overlap=(a,b)=>Math.max(a[0],b[0])<Math.min(a[2],b[2])&&Math.max(a[1],b[1])<Math.min(a[3],b[3]);
// MiniPC renderer hashes sorted compact JSON joined by LF without a final LF,
// while its CLI writes spaced JSONL. Preserve that declared semantic digest.
const compactSorted=value=>{if(Array.isArray(value))return '['+value.map(compactSorted).join(',')+']';if(value&&typeof value==='object')return '{'+Object.keys(value).sort().map(k=>compactSorted(k)+':'+compactSorted(value[k])).join(',')+'}';return JSON.stringify(value).replace(/[\u007f-\uffff]/g,c=>'\\u'+c.charCodeAt(0).toString(16).padStart(4,'0'));};

export function validateCore(bounds,allowed=DEFAULT_BOUNDS){
 if(!Array.isArray(bounds)||bounds.length!==4||bounds.some(v=>!Number.isSafeInteger(v)||v%16)||bounds[2]-bounds[0]!==256||bounds[3]-bounds[1]!==256)throw Error('Expected aligned half-open256 metre core');
 if(bounds[0]<allowed[0]||bounds[1]<allowed[1]||bounds[2]>allowed[2]||bounds[3]>allowed[3])throw Error('Core escaped assigned import extent');
 return bounds;
}

export function validateBuildingRun(run,bounds,featureIds){
 if(!run||typeof run!=='object'||Array.isArray(run))throw Error('Run must be an object');
 for(const k of ['x','z','yMin','yMax'])if(!Number.isSafeInteger(run[k]))throw Error('Run '+k+' must be an integer');
 if(run.x<bounds[0]||run.x>=bounds[2]||run.z<bounds[1]||run.z>=bounds[3])throw Error('Run escaped half-open core ownership');
 if(run.yMin< -64||run.yMax>320||run.yMin>=run.yMax)throw Error('Run escaped vertical range');
 if(!['building',50].includes(run.layer))throw Error('Imported building stream contains another layer');
 if(typeof run.block!=='string'||!/^minecraft:[a-z0-9_]+$/.test(run.block))throw Error('Run has malformed Minecraft block');
 if(typeof run.geometryKind!=='string'||!run.geometryKind||typeof run.sourceClass!=='string'||!run.sourceClass)throw Error('Run lacks geometry/source provenance');
 if(typeof run.featureId!=='string'||!featureIds.has(run.featureId))throw Error('Run references unknown source feature '+run.featureId);
 if(run.properties!==undefined&&(!run.properties||Array.isArray(run.properties)||typeof run.properties!=='object'||Object.entries(run.properties).some(([k,v])=>!k||typeof v!=='string')))throw Error('Run block properties must be string pairs');
 return run;
}

export async function verifyRunFile(file,{sha256,bytes,rendererSha256,count,bounds,featureIds}){
 if(!validHash(sha256)||!validHash(rendererSha256)||!Number.isSafeInteger(bytes)||bytes<0||!Number.isSafeInteger(count)||count<0)throw Error('Missing run hash, byte length or count');
 const raw=crypto.createHash('sha256'),normalized=crypto.createHash('sha256'),compact=crypto.createHash('sha256');let rawBytes=0,lines=0,runs=0;
 const input=fs.createReadStream(file);input.on('data',chunk=>{raw.update(chunk);rawBytes+=chunk.length;});
 const reader=readline.createInterface({input,crlfDelay:Infinity});
 try{for await(const line of reader){lines++;if(Buffer.byteLength(line)>1024*1024)throw Error('Run line exceeds1MiB');normalized.update(line+'\n');if(!line.trim())continue;let run;try{run=JSON.parse(line);}catch{throw Error('Malformed run JSON at line '+lines);}validateBuildingRun(run,bounds,featureIds);compact.update((runs?'\n':'')+compactSorted(run));runs++;}}
 finally{reader.close();input.destroy();}
 const byteSha256=raw.digest('hex'),lfSha256=normalized.digest('hex'),compactSha256=compact.digest('hex');
 if(byteSha256!==sha256.toLowerCase()||rawBytes!==bytes)throw Error('Packaged run byte hash or length mismatch');
 if(![byteSha256,lfSha256,compactSha256].includes(rendererSha256.toLowerCase()))throw Error('Renderer digest matches neither raw bytes, LF-normalized nor sorted compact content');
 if(runs!==count)throw Error('Run count mismatch');
 return {path:path.resolve(file),sha256:byteSha256,bytes:rawBytes,runCount:runs,rendererSha256:rendererSha256.toLowerCase(),lfNormalizedSha256:lfSha256,compactSortedSha256:compactSha256,rendererDigestEncoding:rendererSha256.toLowerCase()===byteSha256?'raw-file-bytes':rendererSha256.toLowerCase()===lfSha256?'LF-normalized-JSONL':'sorted-compact-JSON-LF-joined-no-final-LF'};
}

export async function importBuildingPackages({packages,output,allowedBounds=DEFAULT_BOUNDS,privateRoot}={}){
 if(!Array.isArray(packages)||!packages.length)throw Error('At least one incoming package required');
 privateRoot=fs.realpathSync(privateRoot??path.join(process.env.LOCALAPPDATA,'FORK-Tools/fork-singapore-full'));
 if(output!==undefined){if(!path.isAbsolute(output))throw Error('New absolute private output path required');output=path.resolve(output);
  if(!inside(output,privateRoot)||fs.existsSync(output))throw Error('Output must be new and inside private full-Singapore workspace');
  let ancestor=path.dirname(output);while(!fs.existsSync(ancestor))ancestor=path.dirname(ancestor);if(fs.realpathSync(ancestor)!==ancestor)throw Error('Output ancestor cannot be a junction');}
 const roots=packages.map(item=>({...(typeof item==='string'?{path:item}:item)}));
 const consumed=new Map(),contexts=[],descriptors=[],coreIds=new Set();
 function tracked(file,expected,expectedBytes){
  file=path.resolve(file);if(!inside(file,privateRoot)||fs.realpathSync(file)!==file||!fs.statSync(file).isFile())throw Error('Consumed path is not a real private file: '+file);
  if(!validHash(expected))throw Error('Missing consumed-file hash: '+file);const data=fs.readFileSync(file),actual=sha(data);
  if(actual!==expected.toLowerCase()||(expectedBytes!==undefined&&data.length!==expectedBytes))throw Error('Consumed file hash or length mismatch: '+file);
  consumed.set(file,{path:file,sha256:actual,bytes:data.length});return data;
 }
 for(const item of roots){
  if(!path.isAbsolute(item.path))throw Error('Package path must be absolute');const root=path.resolve(item.path);
  if(!inside(root,privateRoot)||fs.realpathSync(root)!==root)throw Error('Incoming package escaped private workspace');
  if(output&&(inside(output,root)||inside(root,output)))throw Error('Output and incoming package paths must be disjoint');
  const manifestPath=path.join(root,'package-manifest.json'),manifestBytes=fs.readFileSync(manifestPath),manifestHash=sha(manifestBytes);
  if(item.manifestSha256&&(!validHash(item.manifestSha256)||manifestHash!==item.manifestSha256.toLowerCase()))throw Error('Package root manifest hash mismatch');
  consumed.set(manifestPath,{path:manifestPath,sha256:manifestHash,bytes:manifestBytes.length});
  const manifest=json(manifestBytes);if(manifest.format!=='tile-manifest-v1'||!Array.isArray(manifest.files))throw Error('Unknown package manifest schema');
  const entries=new Map();for(const entry of manifest.files){const rel=entry.path;if(typeof rel!=='string'||!rel||path.isAbsolute(rel)||rel.includes('\\')||rel.split('/').some(s=>!s||s==='.'||s==='..')||!validHash(entry.sha256)||!Number.isSafeInteger(entry.length)||entry.length<0||entries.has(rel))throw Error('Malformed, duplicate or escaping package entry');entries.set(rel,entry);}
  const consume=rel=>{const entry=entries.get(rel);if(!entry)throw Error('Consumed input missing from package manifest: '+rel);return tracked(path.join(root,rel),entry.sha256,entry.length);};
  const binding=json(consume('binding-receipt.json'));if(!['minipc.production-east-binding.v1','minipc.production-east-v2-binding.v1'].includes(binding.schema)||!Array.isArray(binding.jobs))throw Error('Unknown binding receipt schema');
  contexts.push({root,manifestPath,manifestHash,entries,consume,binding});
 }
 for(const c of contexts)if(c.binding.priorThreePackage){const p=c.binding.priorThreePackage,prior=contexts.find(v=>v.root===path.resolve(c.root,p.path));if(!prior||prior.manifestHash!==p.manifestSha256)throw Error('Prior package dependency missing or hash mismatch');}
 for(const c of contexts)for(const job of c.binding.jobs){
  if(typeof job.jobId!=='string'||!/^minipc-east-\d+-\d+-v\d+$/.test(job.jobId)||coreIds.has(job.jobId))throw Error('Unknown or duplicate core ID');coreIds.add(job.jobId);
  const coreBounds=validateCore(job.bounds,allowedBounds);for(const d of descriptors)if(overlap(d.coreBounds,coreBounds))throw Error('Duplicate or overlapping half-open core ownership');
  const [,idX,idZ]=job.jobId.match(/^minipc-east-(\d+)-(\d+)-v\d+$/);if(Number(idX)!==coreBounds[0]||Number(idZ)!==coreBounds[1])throw Error('Core ID disagrees with coordinate ownership');
  const resultDir='results/'+job.jobId,workerPath=resultDir+'/worker-receipt.json',buildingPath=resultDir+'/buildings.manifest.json';
  const worker=json(c.consume(workerPath)),building=json(c.consume(buildingPath));
  if(worker.jobId!==job.jobId||!same(worker.coreBounds,coreBounds)||!same(worker.sourceContract?.job?.coreBounds,coreBounds)||!same(building.tileCore,coreBounds))throw Error('Core bounds disagree across immutable receipts');
  if(worker.metrics?.exitCode!==0||worker.metrics?.timedOut||worker.failure||job.coreGeometry!=='PASS')throw Error('Incoming worker or core geometry did not pass');
  if(building.schema!=='fork-building-runs-v1'||building.coordinateSystem!=='EPSG:3414'||building.axisMapping!=='x=E,z=60000-N'||building.horizontalBlocksPerMetre!==1||building.groundY!==0||!same(building.verticalRangeInclusive,[-64,319]))throw Error('Unsupported imported grid or vertical policy');
  const contract=worker.sourceContract?.source;
  if(!contract||!validHash(contract.sourceSha256)||!validHash(contract.projectedSha256)||!validHash(contract.manifestSha256)||!validHash(worker.sourceContract?.job?.sourceSha256))throw Error('Unknown or unbound original source');
  const projectedRel='incoming/'+path.basename(c.root)+'/'+job.jobId+'.geojson',projectionRel='incoming/'+path.basename(c.root)+'/'+job.jobId+'.projection.json';
  const projected=json(c.consume(projectedRel)),projection=json(c.consume(projectionRel));
  if(c.entries.get(projectedRel).sha256!==contract.projectedSha256||c.entries.get(projectionRel).sha256!==contract.manifestSha256||projection.sourceSha256!==contract.sourceSha256||projection.excludedCount!==0)throw Error('Projected source chain hash or geometry exclusion mismatch');
  if(projected.type!=='FeatureCollection'||!Array.isArray(projected.features)||projected.coordinateSystem?.projection!=='EPSG:3414'||!same(projected.coordinateSystem?.axes,['x=easting_metres','z=60000-northing_metres'])||projected.coordinateSystem?.blocksPerMetre!==1)throw Error('Unknown projected source coordinate frame');
  const featureIds=new Set();for(const f of projected.features){const id=String(f.id??f.properties?.featureid??'');if(!/^(way|relation)\/\d+$/.test(id)||featureIds.has(id)||f.properties?.sourceClass!=='osm-mapped')throw Error('Unknown or duplicate mapped source feature');featureIds.add(id);}
  const source={sha256:contract.sourceSha256,nationalSha256:worker.sourceContract.job.sourceSha256};
  if(typeof projection.sourceFile==='string'&&path.isAbsolute(projection.sourceFile)&&fs.existsSync(projection.sourceFile)){tracked(projection.sourceFile,source.sha256);source.path=path.resolve(projection.sourceFile);}
  const runRel=job.runsFile?.path,runEntry=c.entries.get(runRel);if(runRel!==resultDir+'/buildings.runs.jsonl'||!runEntry||runEntry.sha256!==job.runsFile.sha256||runEntry.length!==job.runsFile.length||job.runs!==building.runCount)throw Error('Run bindings disagree');
  const runPath=path.join(c.root,runRel);if(fs.realpathSync(runPath)!==runPath)throw Error('Run input cannot be a junction');
  const buildingRuns=await verifyRunFile(runPath,{sha256:runEntry.sha256,bytes:runEntry.length,rendererSha256:building.runsSha256,count:job.runs,bounds:coreBounds,featureIds});consumed.set(runPath,{path:runPath,sha256:buildingRuns.sha256,bytes:buildingRuns.bytes});
  const evidence=building.evidence??[];
  descriptors.push({id:job.jobId,coreBounds:[...coreBounds],grid:{projection:'EPSG:3414',axes:'x=E,z=60000-N',blocksPerMetre:1,ownership:'half-open-core'},buildingRuns,source,projectedSource:{path:path.join(c.root,projectedRel),sha256:contract.projectedSha256,bytes:c.entries.get(projectedRel).length},originalManifest:{path:c.manifestPath,sha256:c.manifestHash},buildingManifest:consumed.get(path.join(c.root,buildingPath)),workerReceipt:consumed.get(path.join(c.root,workerPath)),sourceEvidence:{exclusions:building.exclusions??[],selectedFeatureCount:building.selectedFeatureCount,completeSourceGeometryAccepted:building.completeSourceGeometryAccepted===true,resolvedOverlappingVoxels:building.resolvedOverlappingVoxels??0,estimatedHeightCount:evidence.filter(e=>e.height?.estimated).length,heightProvenance:evidence.map(e=>({featureId:e.featureId,height:e.height,minHeight:e.minHeight,roof:e.roof})),warnings:building.warnings??[],groundY:building.groundY,groundSourceClass:building.groundSourceClass},requiresCountryMask:true,fullWorldAccepted:false});
 }
 // Re-hash all consumed bytes before publishing a success receipt; incoming is read-only.
 for(const entry of consumed.values()){const actual=crypto.createHash('sha256');let bytes=0;for await(const chunk of fs.createReadStream(entry.path)){actual.update(chunk);bytes+=chunk.length;}if(actual.digest('hex')!==entry.sha256||bytes!==entry.bytes)throw Error('Consumed input changed during import: '+entry.path);}
 descriptors.sort((a,b)=>a.id.localeCompare(b.id));
 const receipt={schemaVersion:1,status:'VERIFIED_BUILDING_RUNS_NOT_WORLD_ACCEPTED',createdUtc:new Date().toISOString(),allowedBounds,coreCount:descriptors.length,totalRuns:descriptors.reduce((n,d)=>n+d.buildingRuns.runCount,0),coreAreaSquareMetres:descriptors.length*256*256,sourceExclusions:descriptors.flatMap(d=>d.sourceEvidence.exclusions.map(e=>({coreId:d.id,...e}))),sourceGeometryAccepted:descriptors.every(d=>d.sourceEvidence.completeSourceGeometryAccepted),actualTerrainKnown:false,countryMaskApplied:false,fullWorldAccepted:false,cores:descriptors,consumed:[...consumed.values()]};
 if(output===undefined)return {descriptors,receiptData:receipt};
 fs.mkdirSync(output,{recursive:true});const p=path.join(output,'import-receipt.json'),data=JSON.stringify(receipt,null,2)+'\n';fs.writeFileSync(p,data,{flag:'wx'});const receiptSha256=sha(data);fs.writeFileSync(path.join(output,'import-receipt.sha256'),receiptSha256+'  import-receipt.json\n',{flag:'wx'});
 return {descriptors,receipt:{path:p,sha256:receiptSha256,bytes:Buffer.byteLength(data)},summary:{coreCount:receipt.coreCount,totalRuns:receipt.totalRuns,sourceExclusions:receipt.sourceExclusions.length,sourceGeometryAccepted:receipt.sourceGeometryAccepted}};
}

export async function loadImportPackages(packageRoots,options={}){const result=await importBuildingPackages({...options,packages:packageRoots,output:undefined});return {cores:result.descriptors,receipt:result.receiptData};}

export async function main(argv){const packages=[];let output;for(let i=0;i<argv.length;i+=2){if(argv[i]==='--package')packages.push(argv[i+1]);else if(argv[i]==='--output')output=argv[i+1];else throw Error('Usage: --package ABS [--package ABS] --output NEWABS');}const result=await importBuildingPackages({packages,output});console.log(JSON.stringify({receipt:result.receipt,...result.summary}));}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href)main(process.argv.slice(2)).catch(e=>{console.error(e.stack);process.exitCode=1;});
