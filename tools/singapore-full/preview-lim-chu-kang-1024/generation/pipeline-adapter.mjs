import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {queueLease,sourceQuarantine} from './pipeline-job-policy.mjs';

const digest=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const save=(p,v)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p+'.tmp',JSON.stringify(v,null,2)+'\n');fs.renameSync(p+'.tmp',p);};
const contained=(p,r)=>p===r||p.startsWith(r+path.sep);
function args(argv){const out={};for(let i=0;i<argv.length;i+=2){if(!argv[i].startsWith('--')||argv[i+1]===undefined)throw Error('Expected --option value');out[argv[i].slice(2)]=argv[i+1];}return out;}
export function validateJob(job){
  if(job.schemaVersion!==1||!Array.isArray(job.tiles)||!job.tiles.length||job.tiles.length>4)throw Error('Job requires one to four bounded tiles');
  if(!job.source?.path||!job.source.sha256?.match(/^[a-f0-9]{64}$/i))throw Error('Source path and immutable SHA256 required');
  if(!job.tools?.python||!job.tools.features||!job.tools.buildings||!job.tools.overlay||!job.levelTemplate?.path)throw Error('Frozen tool entrypoints and a level template are required');
  if(!Array.isArray(job.artifacts)||job.artifacts.length<3||job.artifacts.some(a=>!a.path||!a.sha256?.match(/^[a-f0-9]{64}$/i)))throw Error('Frozen module dependency hash inventory required');
  if(job.roads&&(!job.tools.nodeProjection||!job.tools.roads||!job.tools.maskRuns||!job.roads.countryMask?.path||!job.roads.countryMask.sha256))throw Error('Road generation requires frozen projection, renderer and all-layer country-mask inputs');
  if(job.coast&&(!job.roads||!job.tools.coastSurface||!job.coast.mask?.path||!job.coast.mask.sha256))throw Error('Coast generation requires frozen surface renderer, classification mask and scope mask');
  if(job.terrain?.mode!=='flat-provisional'||job.terrain.groundY!==0)throw Error('This initial adapter supports explicitly provisional flat ground Y0 only');
  const ids=new Set();
  for(const t of job.tiles){if(!/^[A-Za-z0-9_-]+$/.test(t.id)||ids.has(t.id))throw Error('Tile IDs must be unique safe path names');ids.add(t.id);
    if(!Array.isArray(t.coreOrigin)||t.coreOrigin.length!==2||t.coreOrigin.some(n=>!Number.isSafeInteger(n)||n%16))throw Error('Core origins must be chunk-aligned integers');
    if(!Number.isSafeInteger(t.coreSize)||t.coreSize<16||t.coreSize>1024||t.coreSize%16)throw Error('Invalid core size');
    if(!Number.isSafeInteger(t.halo)||t.halo<0||t.halo>256||t.halo%16)throw Error('Invalid halo');
  }
  return job;
}
export function aggregateBuildingEvidence(gates,manifest){
  gates.sourceGeometryComplete=gates.sourceGeometryComplete===true&&manifest.completeSourceGeometryAccepted===true;
  gates.resolvedOverlapVoxelOccurrences=(gates.resolvedOverlapVoxelOccurrences??0)+(manifest.resolvedOverlappingVoxels??0);
  return gates;
}
function verifiedInput(p,expected){const actual=digest(p);if(expected&&expected.toLowerCase()!==actual)throw Error('Input hash mismatch: '+path.basename(p));return {path:path.resolve(p),bytes:fs.statSync(p).size,sha256:actual};}
function regularOutputs(root){return fs.readdirSync(root,{withFileTypes:true}).flatMap(e=>{const p=path.join(root,e.name);return e.isDirectory()?regularOutputs(p).map(v=>({...v,path:e.name+'/'+v.path})):[{path:e.name,bytes:fs.statSync(p).size,sha256:digest(p)}];});}

export async function main(argv){
  const o=args(argv);if(!o.job||!o['attempt-root'])throw Error('Usage: pipeline-adapter.mjs --job config.json --attempt-root NEW_QUEUE_OUTPUT [--checkpoint path]');
  const job=validateJob(JSON.parse(fs.readFileSync(o.job,'utf8')));
  const allowed=fs.realpathSync(path.join(process.env.LOCALAPPDATA,'FORK-Tools/fork-singapore-full'));
  const root=path.resolve(o['attempt-root']);if(!contained(root,allowed)||root===allowed)throw Error('Attempt root must be isolated beneath full-Singapore workspace');
  fs.mkdirSync(root,{recursive:true});if(fs.realpathSync(root)!==root)throw Error('Attempt root cannot use junctions');
  const resultPath=path.join(root,'pipeline-result.json');if(fs.existsSync(resultPath))throw Error('Attempt already has a result; scheduler must supply a fresh attempt');
  const checkpoint=o.checkpoint?path.resolve(o.checkpoint):null;if(checkpoint&&!contained(checkpoint,allowed))throw Error('Checkpoint escaped full-Singapore workspace');
  const started=Date.now(),result={schemaVersion:1,status:'running',startedUtc:new Date().toISOString(),coordinatorPid:process.pid,coordinatorPeakRssBytes:process.memoryUsage().rss,jobId:job.id??process.env.FORK_JOB_ID??'standalone',attempt:process.env.FORK_JOB_ATTEMPT??null,grid:{kind:'EPSG:3414',originEasting:0,originNorthing:60000,blocksPerMeter:1,xDirection:'east',zDirection:'south'},vertical:job.terrain,source:null,toolInputs:{},tiles:[],stages:[],qualityGates:{sourceGeometryComplete:false,writerChecks:false,actualSeam:false,terrainSurveyed:false,photographMatched:false,productionAccepted:false}};
  const update=()=>{save(resultPath,result);if(checkpoint)save(checkpoint,{schemaVersion:1,jobId:result.jobId,status:result.status,attemptRoot:root,completedTiles:result.tiles.filter(t=>t.status==='written').map(t=>t.id),updatedUtc:new Date().toISOString()});};
  // The writer's temporary flat base is a benchmark scaffold, not measured land.
  result.qualityGates.baseTerrainScopeClipped=false;
  result.qualityGates.coastlineSurfaceAccepted=false;
  async function runStage(name,exe,argv){
    const stageStart=Date.now(),log=path.join(root,name+'.log'),fd=fs.openSync(log,'wx'),stage={name,startedUtc:new Date().toISOString(),argv,log:path.basename(log),status:'running'};
    const proc=spawn(exe,argv,{cwd:root,windowsHide:true,stdio:['ignore',fd,fd],env:{...process.env,RAYON_NUM_THREADS:'1',OMP_NUM_THREADS:'1',OPENBLAS_NUM_THREADS:'1',PYTHONDONTWRITEBYTECODE:'1'}});stage.pid=proc.pid;result.stages.push(stage);update();
    const timer=setTimeout(()=>{stage.timedOut=true;proc.kill();},Math.min(job.timeoutSeconds??180,1800)*1000);
    const close=await new Promise(resolve=>{proc.once('error',error=>resolve({code:null,error:String(error)}));proc.once('close',(code,signal)=>resolve({code,signal}));});clearTimeout(timer);fs.closeSync(fd);
    Object.assign(stage,close,{status:close.code===0&&!stage.timedOut?'passed':'failed',completedUtc:new Date().toISOString(),elapsedSeconds:(Date.now()-stageStart)/1000});result.coordinatorPeakRssBytes=Math.max(result.coordinatorPeakRssBytes,process.memoryUsage().rss);update();
    if(stage.status!=='passed')throw Error(name+' failed; see '+log);
  }
  try{
    result.source=verifiedInput(job.source.path,job.source.sha256);result.codeArtifacts=job.artifacts.map(a=>verifiedInput(a.path,a.sha256));
    for(const k of ['features','buildings','overlay',...(job.roads?['nodeProjection','roads','maskRuns']:[]),...(job.coast?['coastSurface']:[])])result.toolInputs[k]=verifiedInput(job.tools[k],job.tools.sha256?.[k]);
    result.toolInputs.levelTemplate=verifiedInput(job.levelTemplate.path,job.levelTemplate.sha256);
    const worldGenSettings=path.join(path.dirname(job.levelTemplate.path),'data/minecraft/world_gen_settings.dat');
    if(fs.existsSync(worldGenSettings))result.toolInputs.worldGenSettings=verifiedInput(worldGenSettings,job.levelTemplate.worldGenSettingsSha256);
    if(job.roads){result.toolInputs.countryMask=verifiedInput(job.roads.countryMask.path,job.roads.countryMask.sha256);if(job.roads.foreignExclusions)result.toolInputs.foreignExclusions=verifiedInput(job.roads.foreignExclusions.path,job.roads.foreignExclusions.sha256);}
    if(job.coast)result.toolInputs.coastMask=verifiedInput(job.coast.mask.path,job.coast.mask.sha256);
    let leasePath=job.leasePath;
    if(job.queueLease){leasePath=path.join(root,'job-lease.json');save(leasePath,queueLease(job.queueLease,root,process.env));result.queueLease=verifiedInput(leasePath);}
    save(path.join(root,'job.json'),job);update();
    const features=path.join(root,'buildings-projected.geojson'),featureManifest=path.join(root,'features-manifest.json');
    await runStage('project-features',process.execPath,[job.tools.features,'--source',job.source.path,'--output',features,'--manifest',featureManifest]);
    const featureReceipt=JSON.parse(fs.readFileSync(featureManifest,'utf8')),projectedFeatures=JSON.parse(fs.readFileSync(features)).features;
    result.featureReceipt=featureReceipt;result.featureInput=verifiedInput(features);
    // Exclusions remain visible; a missing reference is never a silent pass.
    if(featureReceipt.missingReferences?.length||featureReceipt.excludedCount>0||featureReceipt.status==='failed')throw Error('Projected source has excluded or incomplete features; review before generation');
    result.qualityGates.sourceGeometryComplete=true;
    const projectedNodes=path.join(root,'projected-nodes.json');
    if(job.roads)await runStage('project-road-nodes',process.execPath,[job.tools.nodeProjection,'--sourceOverpass',job.source.path,'--output',projectedNodes]);
    for(const tile of job.tiles){
      const dir=path.join(root,tile.id);fs.mkdirSync(dir);const [x,z]=tile.coreOrigin,h=tile.halo,n=tile.coreSize,bounds=[x-h,z-h,x+n+h,z+n+h];
      let runs=path.join(dir,'buildings.runs.jsonl');const buildingManifest=path.join(dir,'buildings-manifest.json'),world=path.join(dir,'world'),writerManifest=path.join(dir,'writer-manifest.json');
      const record={...tile,renderBounds:bounds,status:'rendering',worldPath:tile.id+'/world',runsPath:tile.id+'/buildings.runs.jsonl',writerManifestPath:tile.id+'/writer-manifest.json'};result.tiles.push(record);update();
      const buildingArgs=[job.tools.buildings,'--input',features,'--tile',...bounds.map(String),'--ground-y','0','--ground-source-class','flat-provisional-y0-v1','--output',runs,'--manifest',buildingManifest];
      if(job.allowedInvalidFeatureIds?.length||job.sourceQuarantine)buildingArgs.push('--invalid-feature-policy','report');
      await runStage(tile.id+'-buildings',job.tools.python,buildingArgs);
      record.runs=verifiedInput(runs);record.buildings=JSON.parse(fs.readFileSync(buildingManifest,'utf8'));
      aggregateBuildingEvidence(result.qualityGates,record.buildings);
      record.sourceQuarantine=sourceQuarantine(record.buildings,projectedFeatures,job.sourceQuarantine,job.allowedInvalidFeatureIds??[]);
      save(path.join(dir,'source-quarantine.json'),record.sourceQuarantine);
      if(record.sourceQuarantine.excludedCount)result.qualityGates.sourceGeometryComplete=false;
      if(job.roads){
        const maskedRuns=path.join(dir,'buildings.masked.runs.jsonl'),maskManifest=path.join(dir,'buildings-mask-manifest.json');
        const maskArgs=[job.tools.maskRuns,'--runs',runs,'--country-mask',job.roads.countryMask.path,'--output',maskedRuns,'--manifest',maskManifest];
        if(job.roads.foreignExclusions)maskArgs.push('--foreign-exclusions',job.roads.foreignExclusions.path);
        await runStage(tile.id+'-building-mask',job.tools.python,maskArgs);
        record.unmaskedRuns=record.runs;record.mask=JSON.parse(fs.readFileSync(maskManifest));runs=maskedRuns;record.runsPath=tile.id+'/buildings.masked.runs.jsonl';record.runs=verifiedInput(runs);
      }
      const overlayArgs=[job.tools.overlay,'--runs',runs,'--world',world,'--bounds',bounds.join(','),'--manifest',writerManifest,'--level-template',job.levelTemplate.path,'--max-chunks',String(((n+2*h)/16)**2)];
      if(job.roads){
        const roadRuns=path.join(dir,'roads.runs.jsonl'),roadManifest=path.join(dir,'roads-manifest.json');
        const roadArgs=[job.tools.roads,'--input',job.source.path,'--projected-nodes',projectedNodes,'--tile',...bounds.map(String),'--output',roadRuns,'--manifest',roadManifest,'--country-mask',job.roads.countryMask.path];
        if(job.roads.foreignExclusions)roadArgs.push('--foreign-exclusions',job.roads.foreignExclusions.path);
        if(job.roads.maxCandidates)roadArgs.push('--max-candidates',String(job.roads.maxCandidates));
        await runStage(tile.id+'-roads',job.tools.python,roadArgs);record.roadRunsPath=tile.id+'/roads.runs.jsonl';record.roadRuns=verifiedInput(roadRuns);record.roads=JSON.parse(fs.readFileSync(roadManifest,'utf8'));overlayArgs.push('--runs',roadRuns);
      }
      if(job.coast){
        const raw=path.join(dir,'coast.raw.runs.jsonl'),coastManifest=path.join(dir,'coast-manifest.json'),masked=path.join(dir,'coast.masked.runs.jsonl'),maskReceipt=path.join(dir,'coast-mask-manifest.json');
        await runStage(tile.id+'-coast',job.tools.python,[job.tools.coastSurface,'--mask',job.coast.mask.path,'--bounds',...bounds.map(String),'--output',raw,'--manifest',coastManifest]);
        record.coastUnmaskedRuns=verifiedInput(raw);record.coast=JSON.parse(fs.readFileSync(coastManifest));
        const maskArgs=[job.tools.maskRuns,'--runs',raw,'--country-mask',job.roads.countryMask.path,'--output',masked,'--manifest',maskReceipt];
        if(job.roads.foreignExclusions)maskArgs.push('--foreign-exclusions',job.roads.foreignExclusions.path);
        await runStage(tile.id+'-coast-mask',job.tools.python,maskArgs);record.coastRunsPath=tile.id+'/coast.masked.runs.jsonl';record.coastRuns=verifiedInput(masked);record.coastMask=JSON.parse(fs.readFileSync(maskReceipt));overlayArgs.push('--runs',masked);
      }
      if(leasePath)overlayArgs.push('--job-lease',leasePath);
      await runStage(tile.id+'-world',job.tools.python,overlayArgs);
      record.writer=JSON.parse(fs.readFileSync(writerManifest,'utf8'));
      if(record.writer.status==='failed'||record.writer.status==='FAIL'||record.writer.conflicts?.length)throw Error('Writer reported conflicts or failed validation');
      if(!fs.existsSync(path.join(world,'level.dat'))||(!fs.existsSync(path.join(world,'region'))&&!fs.existsSync(path.join(world,'dimensions/minecraft/overworld/region'))))throw Error('Writer did not produce a Minecraft world');
      record.files=regularOutputs(world);record.status='written';update();
    }
    if(digest(job.source.path)!==result.source.sha256)throw Error('Source changed during generation');
    for(const [name,p] of Object.entries(result.toolInputs))if(digest(p.path)!==p.sha256)throw Error('Frozen input changed during generation: '+name);
    for(const p of result.codeArtifacts)if(digest(p.path)!==p.sha256)throw Error('Frozen dependency changed during generation: '+path.basename(p.path));
    result.qualityGates.writerChecks=true;result.status='generated-awaiting-independent-gates';result.completedUtc=new Date().toISOString();result.elapsedSeconds=(Date.now()-started)/1000;update();
    console.log(JSON.stringify({status:result.status,resultPath,tiles:result.tiles.map(t=>({id:t.id,status:t.status,worldPath:path.join(root,t.worldPath)}))}));
  }catch(error){result.status='failed';result.error=String(error.stack??error);result.completedUtc=new Date().toISOString();update();throw error;}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main(process.argv.slice(2)).catch(e=>{console.error(e.stack);process.exitCode=1;});
