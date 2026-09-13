import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath,pathToFileURL} from 'node:url';

const COORDINATOR='/root/singapore_full_coordinator';
const AUX_SHA='7e34d9a9d3f06443bfc78a27626d65d5a84ae2838bc9680eaf3838b3a560941c';
const hash=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const digest=p=>hash(fs.readFileSync(p));
const readJson=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const within=(p,r)=>p!==r&&p.startsWith(r+path.sep);
const absolute=(p,name)=>{if(typeof p!=='string'||!path.isAbsolute(p))throw Error(name+' must be an absolute path');return path.resolve(p);};

export function validatePreparation(prep){
 if(prep.schemaVersion!==1||!Array.isArray(prep.benchmarks)||prep.benchmarks.length!==3)throw Error('Expected exactly three bound benchmark exports');
 const ids=new Set();
 for(const b of prep.benchmarks){
  if(!/^[a-z0-9][a-z0-9-]*$/.test(b.id)||ids.has(b.id))throw Error('Benchmark IDs must be unique safe names');ids.add(b.id);
  if(b.tile?.id!==b.id||b.tile.coreSize!==1024||b.tile.halo!==128||!Array.isArray(b.tile.coreOrigin)||b.tile.coreOrigin.length!==2||b.tile.coreOrigin.some(v=>!Number.isSafeInteger(v)||v%16))throw Error('Each benchmark needs one aligned1024core/128halo tile');
  absolute(b.sourceExport?.outputPath,'Source export');absolute(b.sourceExport?.receiptPath,'Source receipt');
  for(const k of ['sha256','receiptSha256'])if(!/^[a-f0-9]{64}$/i.test(b.sourceExport[k]??''))throw Error('Bound source and receipt SHA256 required');
 }
 return prep;
}

export function benchmarkJob(benchmark,{source,tools,artifacts,levelTemplate,countryMask,foreignMask,coastMask,prepReceipt,sourceExportReceipt,revision='v1',allowedInvalidFeatureIds=[]}){
 if(!/^[a-z0-9-]+$/.test(revision)||allowedInvalidFeatureIds.some(id=>!/^\d+$/.test(String(id))))throw Error('Invalid revision or explicit feature ID');
 const job={schemaVersion:1,id:`benchmark-${benchmark.id}-1024-${revision}`,label:benchmark.label??benchmark.id,allowedInvalidFeatureIds,
  source,sourceExportReceipt,prepReceipt,tools,artifacts,levelTemplate,
  tiles:[{id:benchmark.id,coreOrigin:[...benchmark.tile.coreOrigin],coreSize:1024,halo:128}],
  terrain:{mode:'flat-provisional',groundY:0},
  sourceQuarantine:{mode:'invalid-building-parts-report',approvedBy:COORDINATOR,maxFraction:0.1},
  queueLease:{approvedBy:COORDINATOR,heavyJobSlot:'A',cpuThreads:1,memoryGiB:4,expiresUtc:'2026-09-13T11:50:00Z'},
  timeoutSeconds:1800,roads:{countryMask,foreignExclusions:foreignMask,maxCandidates:8000000},
  qualityGates:{actualBenchmark:false,sourceGeometryAccepted:false,sourceWaterAccepted:false,sourceCompleteAccepted:false,terrainSurveyed:false,photographMatched:false,productionAccepted:false},
  generation:{status:'prepared-not-dispatched',requiresRoadsV2Gate:true,requiresStreamingWriterFreeze:true,requiresIndependentBenchmarkLease:true}};
 if(coastMask&&benchmark.id==='coast-changi')job.coast={mask:coastMask,verticalPolicy:'sea-surface-y0-depth-provisional'};
 return job;
}

function listFiles(root,accept){
 const files=[];
 function visit(dir){for(const entry of fs.readdirSync(dir,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))){const p=path.join(dir,entry.name);if(entry.isSymbolicLink())throw Error('Input dependency symlinks are not permitted: '+p);if(entry.isDirectory())visit(p);else if(entry.isFile()&&accept(p))files.push(p);}}
 visit(root);return files;
}

// Preparation only: copies/hashes immutable inputs, never spawns a renderer or queue.
export function makeBenchmarkJobs({workspace,prep,countryMask,foreignMask,coastMask,output,levelTemplate,revision='v1',allowedInvalidFeatureIds=[]}={}){
 workspace=absolute(workspace,'Workspace');prep=absolute(prep,'Preparation');countryMask=absolute(countryMask,'Country mask');foreignMask=absolute(foreignMask,'Foreign mask');output=absolute(output,'Output');
 if(coastMask!==undefined)coastMask=absolute(coastMask,'Coast mask');
 const local=process.env.LOCALAPPDATA;if(!local)throw Error('LOCALAPPDATA required for the existing Desktop tool routes');
 const privateRoot=fs.realpathSync(path.join(local,'FORK-Tools/fork-singapore-full'));
 if(!within(output,privateRoot)||fs.existsSync(output))throw Error('Output must be a NEW directory below the private full-Singapore root');
 // Resolve the nearest existing ancestor before creating anything through it.
 let ancestor=path.dirname(output);while(!fs.existsSync(ancestor))ancestor=path.dirname(ancestor);
 if(fs.realpathSync(ancestor)!==ancestor)throw Error('Output ancestor must not be a junction');
 const document=validatePreparation(readJson(prep)),moduleRoot=path.join(workspace,'tools/singapore-full');
 const tooling=path.join(privateRoot,'pipeline-tooling/node_modules');
 const python=path.join(privateRoot,'data/.venv/Scripts/python.exe');
 levelTemplate=levelTemplate?absolute(levelTemplate,'Level template'):path.join(local,'FORK-Tools/fork-build-20260913/expanded-recipient-1344/FORK Singapore 1024/level.dat');
 const aux=path.join(path.dirname(levelTemplate),'data/minecraft/world_gen_settings.dat');
 const toolPaths={features:'pipeline-features.mjs',buildings:'buildings/stream_renderer.py',overlay:'merge/overlay.py',nodeProjection:'pipeline-node-projection.mjs',roads:'pipeline-roads.py',maskRuns:'pipeline-mask-runs.py'};
 if(coastMask)toolPaths.coastSurface='terrain/coast_surface.py';
 const specifications=[];
 const add=(original,relative,expected,runtimeBound=false)=>specifications.push({original:path.resolve(original),relative,expected,runtimeBound});
 for(const entry of fs.readdirSync(moduleRoot,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name)))if(entry.isFile()&&/^pipeline.*\.(?:mjs|py|json)$/.test(entry.name))add(path.join(moduleRoot,entry.name),'frozen/tools/singapore-full/'+entry.name);
 for(const name of ['buildings','roads','merge',...(coastMask?['terrain']:[])])for(const p of listFiles(path.join(moduleRoot,name),p=>p.endsWith('.py')))add(p,'frozen/tools/singapore-full/'+path.relative(moduleRoot,p).replaceAll('\\','/'));
 // pipeline.mjs resolves this already-isolated installation by LOCALAPPDATA.
 // Bind those actual runtime files as well as preserving immutable byte copies.
 for(const p of listFiles(tooling,p=>/\.(?:js|mjs|cjs|json)$/.test(p)))add(p,'frozen/tooling/node_modules/'+path.relative(tooling,p).replaceAll('\\','/'),undefined,true);
 const sitePackages=path.join(privateRoot,'data/.venv/Lib/site-packages');
 for(const packageName of ['shapely','shapely.libs','shapely-2.1.2.dist-info','numpy','numpy.libs','numpy-2.4.6.dist-info']){
  const packageRoot=path.join(sitePackages,packageName),allMetadata=packageName.endsWith('.dist-info');
  for(const p of listFiles(packageRoot,p=>allMetadata||/\.(?:py|pyd|dll)$/i.test(p)))add(p,'frozen/python/site-packages/'+path.relative(sitePackages,p).replaceAll('\\','/'),undefined,true);
 }
 const venvConfig=path.join(privateRoot,'data/.venv/pyvenv.cfg');
 if(fs.existsSync(venvConfig)){
  add(venvConfig,'frozen/python/pyvenv.cfg',undefined,true);
  const home=fs.readFileSync(venvConfig,'utf8').match(/^home\s*=\s*(.+)$/m)?.[1]?.trim();
  if(home&&fs.existsSync(home))for(const entry of fs.readdirSync(home,{withFileTypes:true}))if(entry.isFile()&&/^python(?:\d+)?(?:_d)?\.(?:dll|exe)$/i.test(entry.name))add(path.join(home,entry.name),'frozen/python/base/'+entry.name,undefined,true);
 }
 add(prep,'frozen/preparation.json');add(countryMask,'frozen/masks/country-world.geojson');add(foreignMask,'frozen/masks/foreign-exclusions-world.geojson');
 if(coastMask)add(coastMask,'frozen/masks/coast-world.geojson');
 add(levelTemplate,'frozen/template/level.dat');add(aux,'frozen/template/data/minecraft/world_gen_settings.dat',AUX_SHA);
 add(path.join(privateRoot,'buildings/stream-proof-1720/receipt.json'),'frozen/proofs/building-stream-equivalence.json');
 add(path.join(privateRoot,'queue/jobs/p256/attempt-2/proof.json'),'frozen/proofs/writer-stream-equivalence.json');
 if(revision!=='v1')add(path.join(privateRoot,'buildings/elevated-defaults-1738-receipt.json'),'frozen/proofs/elevated-defaults-repair.json');
 if(coastMask)for(const name of ['coast-receipt.json','coast-source.json','independent-audit.json'])add(path.join(path.dirname(coastMask),name),'frozen/coast-provenance/'+name);
 for(const b of document.benchmarks){add(b.sourceExport.outputPath,`frozen/sources/${b.id}.json`,b.sourceExport.sha256);add(b.sourceExport.receiptPath,`frozen/sources/${b.id}.receipt.json`,b.sourceExport.receiptSha256);}
 const seen=new Set();for(const s of specifications){if(seen.has(s.relative))throw Error('Duplicate frozen path: '+s.relative);seen.add(s.relative);if(!fs.statSync(s.original).isFile()||fs.realpathSync(s.original)!==s.original)throw Error('Expected a real regular input file: '+s.original);s.sha256=digest(s.original);if(s.expected&&s.sha256!==s.expected.toLowerCase())throw Error('Input hash mismatch: '+s.original);}
 for(const rel of Object.values(toolPaths))if(!seen.has('frozen/tools/singapore-full/'+rel))throw Error('Missing called module '+rel);
 const runtimes=[python,process.execPath].map(p=>({path:path.resolve(p),bytes:fs.statSync(p).size,sha256:digest(p),binding:'external-runtime-hash-bound'}));
 // Source validation completes before the new output directory is created.
 fs.mkdirSync(output,{recursive:true});if(fs.realpathSync(output)!==output)throw Error('Output must not resolve through a junction');
 const frozen=[];
 for(const s of specifications){const target=path.join(output,s.relative);fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(s.original,target,fs.constants.COPYFILE_EXCL);if(digest(target)!==s.sha256||digest(s.original)!==s.sha256)throw Error('Input changed while freezing: '+s.original);frozen.push({originalPath:s.original,path:target,relative:s.relative,bytes:fs.statSync(target).size,sha256:s.sha256,runtimeBound:s.runtimeBound});}
 const at=relative=>{const f=frozen.find(f=>f.relative===relative);if(!f)throw Error('Missing frozen input '+relative);return {path:f.path,bytes:f.bytes,sha256:f.sha256};};
 const tools={python,adapter:path.join(output,'frozen/tools/singapore-full/pipeline-adapter.mjs'),sha256:{}};
 for(const [name,rel] of Object.entries(toolPaths)){const entry=at('frozen/tools/singapore-full/'+rel);tools[name]=entry.path;tools.sha256[name]=entry.sha256;}
 // Include original runtime dependency bindings because the unchanged projection
 // loader calls the isolated installed proj4 path, not its preserved copy.
 const commonArtifacts=[...frozen.map(({path,bytes,sha256})=>({path,bytes,sha256})),...frozen.filter(f=>f.runtimeBound).map(f=>({path:f.originalPath,bytes:f.bytes,sha256:f.sha256})),...runtimes];
 const template={...at('frozen/template/level.dat'),worldGenSettingsSha256:at('frozen/template/data/minecraft/world_gen_settings.dat').sha256};
 const jobs=[];
 for(const b of document.benchmarks){const job=benchmarkJob(b,{source:at(`frozen/sources/${b.id}.json`),sourceExportReceipt:at(`frozen/sources/${b.id}.receipt.json`),prepReceipt:at('frozen/preparation.json'),tools,artifacts:commonArtifacts,levelTemplate:template,countryMask:at('frozen/masks/country-world.geojson'),foreignMask:at('frozen/masks/foreign-exclusions-world.geojson'),coastMask:coastMask?at('frozen/masks/coast-world.geojson'):undefined,revision,allowedInvalidFeatureIds});const p=path.join(output,`job-${b.id}.json`);fs.writeFileSync(p,JSON.stringify(job,null,2)+'\n',{flag:'wx'});jobs.push({id:job.id,path:p,sha256:digest(p),coreOrigin:b.tile.coreOrigin,coreSize:1024,halo:128,renderChunkCount:6400});}
 for(const f of frozen)if(digest(f.originalPath)!==f.sha256||digest(f.path)!==f.sha256)throw Error('Dependency changed before completion: '+f.originalPath);
 const manifest={schemaVersion:1,kind:'fork-immutable-benchmark-innerjobs',status:'prepared-not-dispatched',createdUtc:new Date().toISOString(),workspace,jobs,files:frozen,runtimes,dependencyBinding:'frozen-source-files-and-hash-bound-existing-projection-runtime',qualityGates:{actualBenchmark:false,sourceGeometryAccepted:false,sourceWaterAccepted:false,sourceCompleteAccepted:false,productionAccepted:false},requiresBeforeDispatch:['roads V2 accepted gate','streaming writer freeze','foreign-mask acceptance','active queue resource lease']};
 fs.writeFileSync(path.join(output,'manifest.json'),JSON.stringify(manifest,null,2)+'\n',{flag:'wx'});return manifest;
}

export function main(argv){const o={};for(let i=0;i<argv.length;i+=2){if(!argv[i]?.startsWith('--')||argv[i+1]===undefined)throw Error('Expected --workspace ABS --prep ABS --country-mask ABS --foreign-mask ABS --output NEWABS [--coast-mask ABS]');o[argv[i].slice(2)]=argv[i+1];}const result=makeBenchmarkJobs({workspace:o.workspace,prep:o.prep,countryMask:o['country-mask'],foreignMask:o['foreign-mask'],coastMask:o['coast-mask'],output:o.output,revision:o.revision??'v1',allowedInvalidFeatureIds:o['allow-invalid-feature-ids']?.split(',')??[]});console.log(JSON.stringify({status:result.status,jobs:result.jobs,manifest:path.join(o.output,'manifest.json')}));}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href){try{main(process.argv.slice(2));}catch(e){console.error(e.stack);process.exitCode=1;}}
