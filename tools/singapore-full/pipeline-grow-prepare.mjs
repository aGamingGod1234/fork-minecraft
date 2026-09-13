import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {validateGrowJob} from './pipeline-grow.mjs';

const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const record=p=>({path:path.resolve(p),bytes:fs.statSync(p).size,sha256:sha(p)});
export function combineVerifiedCores(imported,resolved){
  const map=new Map(resolved.cores.map(c=>[c.coreId,c]));
  if(map.size!==resolved.cores.length)throw Error('Duplicate road source IDs');
  return imported.cores.map(c=>{const s=map.get(c.id);if(!s||s.sourceSha256!==c.source.sha256||s.referenceClosure?.complete!==true||JSON.stringify(s.coreBounds)!==JSON.stringify(c.coreBounds))throw Error('Imported geometry and road source disagree: '+c.id);
    return {id:c.id,coreBounds:c.coreBounds,buildingRuns:c.buildingRuns,source:{path:s.sourcePath,sha256:s.sourceSha256,bytes:s.sourceBytes},sourceReceipt:{path:s.receiptPath,sha256:s.receiptSha256},workerReceipt:c.workerReceipt,sourceEvidence:c.sourceEvidence,referenceClosureComplete:true,projectedSource:c.projectedSource,buildingManifest:c.buildingManifest,originalManifest:c.originalManifest};});
}
export function prepareGrowth(options){
  const allowed=fs.realpathSync(path.join(process.env.LOCALAPPDATA,'FORK-Tools/fork-singapore-full')),output=path.resolve(options.output);
  if(!output.startsWith(allowed+path.sep)||fs.existsSync(output))throw Error('Growth inputs require a new private directory');
  const imported=read(options.import),resolved=read(options.sources),base=read(options['tools-job']);
  const allCores=combineVerifiedCores(imported,resolved),selected=options.cores?new Set(options.cores.split(',')):null;
  const cores=allCores.filter(c=>!selected||selected.has(c.id));if(selected&&cores.length!==selected.size)throw Error('Unknown requested core');
  const specs=[],add=(p,relative,expected)=>{const r=record(p);if(expected&&r.sha256!==expected.toLowerCase())throw Error('Source changed before growth freeze: '+p);specs.push({...r,relative});};
  add(options.import,'evidence/import-receipt.json');add(options.sources,'evidence/source-manifest.json');add(options['tools-job'],'evidence/base-tools-job.json');
  add(options['coast-mask'],'masks/national-coast-world.geojson',options['coast-sha256']);
  const moduleRoot=path.join(path.resolve(options.workspace),'tools/singapore-full');
  add(path.join(moduleRoot,'pipeline-grow.mjs'),'tools/singapore-full/pipeline-grow.mjs');
  add(path.join(moduleRoot,'pipeline-roads.py'),'tools/singapore-full/pipeline-roads.py');
  function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,e.name);if(e.isDirectory())walk(p);else if(e.isFile()&&e.name.endsWith('.py'))add(p,'tools/singapore-full/'+path.relative(moduleRoot,p).replaceAll('\\','/'));}}
  walk(path.join(moduleRoot,'roads'));
  // Freeze the corrected writer and every local Python dependency together.
  walk(path.join(moduleRoot,'merge'));
  for(const c of cores)for(const [key,p] of Object.entries({buildingRuns:c.buildingRuns,source:c.source,sourceReceipt:c.sourceReceipt,workerReceipt:c.workerReceipt,projectedSource:c.projectedSource,buildingManifest:c.buildingManifest,originalManifest:c.originalManifest}))add(p.path,'cores/'+c.id+'/'+key+path.extname(p.path),p.sha256);
  for(const p of base.artifacts)if(sha(p.path)!==p.sha256)throw Error('Frozen base tool input changed: '+p.path);
  fs.mkdirSync(output,{recursive:true});if(fs.realpathSync(output)!==output)throw Error('Input root cannot be a junction');
  const frozen=[];for(const s of specs){const target=path.join(output,s.relative);fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(s.path,target,fs.constants.COPYFILE_EXCL);if(sha(target)!==s.sha256)throw Error('Copied input hash mismatch');frozen.push({...record(target),originalPath:s.path,relative:s.relative});}
  const at=relative=>{const p=frozen.find(p=>p.relative===relative);if(!p)throw Error('Missing frozen entry '+relative);return {path:p.path,bytes:p.bytes,sha256:p.sha256};};
  const job={schemaVersion:1,kind:'fork-connected-expansion',id:options.id??'grow-east12-v1',terrain:{mode:'flat-provisional',groundY:0},importReceipt:at('evidence/import-receipt.json'),sourceManifest:at('evidence/source-manifest.json'),coastMask:at('masks/national-coast-world.geojson'),countryMask:base.roads.countryMask,foreignMask:base.roads.foreignExclusions,levelTemplate:base.levelTemplate,worldGenSettings:base.artifacts.find(p=>p.path===path.join(path.dirname(base.levelTemplate.path),'data/minecraft/world_gen_settings.dat')),tools:{...base.tools,overlay:at('tools/singapore-full/merge/overlay.py').path,roads:at('tools/singapore-full/pipeline-roads.py').path,grow:at('tools/singapore-full/pipeline-grow.mjs').path},artifacts:[...base.artifacts,...frozen.map(({path,bytes,sha256})=>({path,bytes,sha256}))],leasePath:path.resolve(options.lease),stageTimeoutSeconds:600,maxRoadCandidates:2000000,cores:cores.map(c=>({...c,...Object.fromEntries(['buildingRuns','source','sourceReceipt','workerReceipt','projectedSource','buildingManifest','originalManifest'].map(key=>[key,at('cores/'+c.id+'/'+key+path.extname(c[key].path))]))}))};
  validateGrowJob(job);const jobPath=path.join(output,'job.json');fs.writeFileSync(jobPath,JSON.stringify(job,null,2)+'\n',{flag:'wx'});
  const manifest={schemaVersion:1,status:'PREPARED_NOT_DISPATCHED',job:record(jobPath),coreCount:cores.length,fullWorldAccepted:false,heavyGenerationStarted:false,files:frozen};fs.writeFileSync(path.join(output,'manifest.json'),JSON.stringify(manifest,null,2)+'\n',{flag:'wx'});return manifest;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){const options={};for(let i=2;i<process.argv.length;i+=2)options[process.argv[i].replace(/^--/,'')]=process.argv[i+1];try{console.log(JSON.stringify(prepareGrowth(options)));}catch(e){console.error(e.stack);process.exitCode=1;}}
