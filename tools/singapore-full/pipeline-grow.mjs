import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const save=(p,v)=>{fs.writeFileSync(p+'.tmp',JSON.stringify(v,null,2)+'\n');fs.renameSync(p+'.tmp',p);};
const verified=p=>{if(!p?.path||!/^[a-f0-9]{64}$/i.test(p.sha256??''))throw Error('A bound file path and SHA256 are required');if(sha(p.path)!==p.sha256.toLowerCase())throw Error('Input changed: '+p.path);return p;};
const file=p=>({path:path.resolve(p),bytes:fs.statSync(p).size,sha256:sha(p)});

export function validateGrowLease(lease,root,allowed,now=new Date()){
  const starts=new Date(lease.startsUtc),expires=new Date(lease.expiresUtc);
  const parts=path.relative(allowed,root).split(path.sep);
  const queueShape=parts.length===6&&parts[0]==='queue'&&parts[1]==='jobs'&&parts[3]==='attempts'&&parts[5]==='output';
  const queueRuntimeShape=parts.length===5&&parts[0]==='queue'&&parts[1]==='jobs'&&/^attempt-\d+$/.test(parts[3])&&parts[4]==='output';
  const runShape=parts.length===5&&parts[0]==='runs'&&parts[2]==='attempts'&&parts[4]==='output';
  if(!root.startsWith(allowed+path.sep)||!(queueShape||queueRuntimeShape||runShape))throw Error('Expansion lease needs one immutable queue/run attempt');
  if(lease.machine!=='Desktop'||lease.approvedBy!=='/root/singapore_full_coordinator'||!['A','B'].includes(lease.heavyJobSlot)||lease.cpuThreads!==1||typeof lease.memoryGiB!=='number'||!(lease.memoryGiB>0&&lease.memoryGiB<=4))throw Error('Expansion lease resource contract is invalid');
  if(path.resolve(lease.outputRoot??'')!==root||!Number.isFinite(+starts)||!Number.isFinite(+expires)||starts>now||expires<=now)throw Error('Expansion lease is absent, not yet active, expired or mismatched');
  return lease;
}

export function validateGrowJob(job){
  if(job.schemaVersion!==1||job.kind!=='fork-connected-expansion'||!Array.isArray(job.cores)||!job.cores.length||job.cores.length>12)throw Error('Expected one to twelve verified east cores');
  if(job.terrain?.mode!=='flat-provisional'||job.terrain.groundY!==0)throw Error('Expansion must share the existing provisional Y0 profile');
  for(const name of ['python','nodeProjection','roads','maskRuns','coastSurface','overlay'])if(!job.tools?.[name])throw Error('Missing frozen tool '+name);
  if(!job.leasePath||!job.artifacts?.length||!job.importReceipt||!job.sourceManifest||!job.levelTemplate||!job.countryMask||!job.foreignMask||!job.coastMask)throw Error('Frozen inputs, scope masks and a coordinator lease are required');
  const seen=new Set();
  for(const c of job.cores){
    if(!/^[a-z0-9-]+$/.test(c.id)||seen.has(c.id))throw Error('Duplicate or invalid core ID');seen.add(c.id);
    const b=c.coreBounds;if(!Array.isArray(b)||b.length!==4||b.some(n=>!Number.isSafeInteger(n)||n%16)||b[2]-b[0]!==256||b[3]-b[1]!==256)throw Error('Imported cores must be half-open 256m squares');
    if(b[0]<30720||b[2]>31488||b[1]<29696||b[3]>30720||(b[0]-30720)%256||(b[1]-29696)%256)throw Error('Core escaped the approved east strip');
    if(!c.buildingRuns||!c.source||!c.sourceReceipt||!c.workerReceipt||c.referenceClosureComplete!==true)throw Error('Incomplete imported building/source provenance');
  }
  for(let i=0;i<job.cores.length;i++)for(let j=0;j<i;j++){const a=job.cores[i].coreBounds,b=job.cores[j].coreBounds;if(Math.max(a[0],b[0])<Math.min(a[2],b[2])&&Math.max(a[1],b[1])<Math.min(a[3],b[3]))throw Error('Imported core ownership overlaps');}
  return job;
}

export async function main(argv){
  const opts={};for(let i=0;i<argv.length;i+=2)opts[argv[i].replace(/^--/,'')]=argv[i+1];
  if(!opts.job||!opts['attempt-root'])throw Error('Usage --job FROZEN.json --attempt-root NEW_OUTPUT');
  const job=validateGrowJob(JSON.parse(fs.readFileSync(opts.job)));
  const allowed=fs.realpathSync(path.join(process.env.LOCALAPPDATA,'FORK-Tools/fork-singapore-full')),root=path.resolve(opts['attempt-root']);
  if(!root.startsWith(allowed+path.sep)||fs.existsSync(root))throw Error('Expansion output must be a new private attempt');
  let ancestor=path.dirname(root);while(!fs.existsSync(ancestor))ancestor=path.dirname(ancestor);if(fs.realpathSync(ancestor)!==ancestor)throw Error('Output ancestor cannot be a junction');
  const lease=JSON.parse(fs.readFileSync(job.leasePath));
  validateGrowLease(lease,root,allowed);
  const leaseInput=file(job.leasePath);
  const inputs=[leaseInput,job.importReceipt,job.sourceManifest,job.levelTemplate,job.worldGenSettings,job.countryMask,job.foreignMask,job.coastMask,...job.artifacts,...job.cores.flatMap(c=>[c.buildingRuns,c.source,c.sourceReceipt,c.workerReceipt])].filter(Boolean);
  inputs.forEach(verified);
  fs.mkdirSync(root,{recursive:true});
  const result={schemaVersion:1,kind:'fork-connected-expansion-result',status:'running',jobId:job.id,job:file(opts.job),lease:file(job.leasePath),startedUtc:new Date().toISOString(),coordinatorPid:process.pid,importReceipt:job.importReceipt,sourceManifest:job.sourceManifest,cores:[],stages:[],qualityGates:{importedBuildingRunsVerified:true,writerChecks:false,actualStructuralChecks:false,roadWaterCoverage:false,fullWorldAccepted:false}};
  const resultPath=path.join(root,'grow-result.json'),update=()=>save(resultPath,result);update();
  async function runStage(name,exe,args){
    verified(leaseInput);
    validateGrowLease(lease,root,allowed);
    const log=path.join(root,name+'.log'),fd=fs.openSync(log,'wx'),start=Date.now(),stage={name,status:'running',startedUtc:new Date().toISOString(),log:path.basename(log),argv:args};
    const proc=spawn(exe,args,{cwd:root,windowsHide:true,stdio:['ignore',fd,fd],env:{...process.env,OMP_NUM_THREADS:'1',OPENBLAS_NUM_THREADS:'1',RAYON_NUM_THREADS:'1',PYTHONDONTWRITEBYTECODE:'1'}});
    stage.pid=proc.pid;result.stages.push(stage);update();const timer=setTimeout(()=>{stage.timedOut=true;proc.kill();},Math.min(job.stageTimeoutSeconds??600,1800)*1000);
    const close=await new Promise(resolve=>{proc.once('error',error=>resolve({code:null,error:String(error)}));proc.once('close',(code,signal)=>resolve({code,signal}));});clearTimeout(timer);fs.closeSync(fd);Object.assign(stage,close,{status:close.code===0&&!stage.timedOut?'passed':'failed',elapsedSeconds:(Date.now()-start)/1000});update();if(stage.status!=='passed')throw Error('Expansion stage failed: '+name+'; '+log);
  }
  try{
    for(const c of job.cores){
      const dir=path.join(root,c.id);fs.mkdirSync(dir);const b=c.coreBounds,record={id:c.id,coreBounds:b,status:'running',source:c.source,sourceEvidence:c.sourceEvidence,importedBuildings:c.buildingRuns};result.cores.push(record);update();
      const imported=path.join(dir,'buildings.imported.runs.jsonl');fs.copyFileSync(c.buildingRuns.path,imported,fs.constants.COPYFILE_EXCL);if(sha(imported)!==c.buildingRuns.sha256)throw Error('Copied MiniPC runs changed');
      const maskedBuildings=path.join(dir,'buildings.masked.runs.jsonl'),buildingMask=path.join(dir,'buildings-mask-manifest.json');
      await runStage(c.id+'-building-mask',job.tools.python,[job.tools.maskRuns,'--runs',imported,'--country-mask',job.countryMask.path,'--foreign-exclusions',job.foreignMask.path,'--output',maskedBuildings,'--manifest',buildingMask]);
      const nodes=path.join(dir,'projected-nodes.json');await runStage(c.id+'-nodes',process.execPath,[job.tools.nodeProjection,'--sourceOverpass',c.source.path,'--output',nodes]);
      const roads=path.join(dir,'roads.runs.jsonl'),roadReport=path.join(dir,'roads-manifest.json');
      await runStage(c.id+'-roads',job.tools.python,[job.tools.roads,'--input',c.source.path,'--projected-nodes',nodes,'--tile',...b.map(String),'--output',roads,'--manifest',roadReport,'--country-mask',job.countryMask.path,'--foreign-exclusions',job.foreignMask.path,'--max-candidates',String(job.maxRoadCandidates??2000000)]);
      const coastRaw=path.join(dir,'coast.raw.runs.jsonl'),coastReport=path.join(dir,'coast-manifest.json');
      await runStage(c.id+'-coast',job.tools.python,[job.tools.coastSurface,'--mask',job.coastMask.path,'--expected-sha256',job.coastMask.sha256,'--bounds',...b.map(String),'--output',coastRaw,'--manifest',coastReport]);
      const coast=path.join(dir,'coast.masked.runs.jsonl'),coastMask=path.join(dir,'coast-mask-manifest.json');
      await runStage(c.id+'-coast-mask',job.tools.python,[job.tools.maskRuns,'--runs',coastRaw,'--country-mask',job.countryMask.path,'--foreign-exclusions',job.foreignMask.path,'--output',coast,'--manifest',coastMask]);
      const world=path.join(dir,'world'),writer=path.join(dir,'writer-manifest.json');
      await runStage(c.id+'-world',job.tools.python,[job.tools.overlay,'--runs',maskedBuildings,'--runs',roads,'--runs',coast,'--world',world,'--bounds',b.join(','),'--max-chunks','256','--level-template',job.levelTemplate.path,'--job-lease',job.leasePath,'--manifest',writer]);
      const wm=JSON.parse(fs.readFileSync(writer));if(wm.status!=='WRITTEN_UNACCEPTED'||wm.chunkCount!==256||wm.dataVersion!==4790||wm.bounds.some((v,i)=>v!==b[i]))throw Error('Unexpected per-core writer output');
      record.status='WRITTEN_UNACCEPTED';record.worldPath=world;record.writerManifest=file(writer);record.runs=[file(maskedBuildings),file(roads),file(coast)];record.rawRuns=[file(imported),file(coastRaw)];record.reports={buildingMask:file(buildingMask),roads:file(roadReport),coast:file(coastReport),coastMask:file(coastMask)};
      record.validationInputs={worldPath:world,writerManifestPath:writer,coreBounds:b,runPaths:record.runs.map(p=>p.path),sourcePath:c.source.path,countryMaskPath:job.countryMask.path,foreignExclusionsPath:job.foreignMask.path,coastMaskPath:job.coastMask.path};
      record.growSource={id:c.id,world_path:world,core_bounds:b,writer_manifest_path:writer,structural_gate_path:null,coverage:{roads:{status:'pending'},water:{status:'pending'}}};update();
    }
    inputs.forEach(verified);result.status='WRITTEN_AWAITING_INDEPENDENT_GATES';result.qualityGates.writerChecks=true;result.completedUtc=new Date().toISOString();update();console.log(JSON.stringify({status:result.status,resultPath,cores:result.cores.map(c=>({id:c.id,worldPath:c.worldPath}))}));
  }catch(error){result.status='FAILED';result.error=String(error.stack??error);result.completedUtc=new Date().toISOString();update();throw error;}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main(process.argv.slice(2)).catch(e=>{console.error(e.stack);process.exitCode=1;});
