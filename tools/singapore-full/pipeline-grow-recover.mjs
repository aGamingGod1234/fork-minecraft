import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const owner='/root/singapore_full_coordinator';
const save=(p,v)=>{fs.writeFileSync(p+'.tmp',JSON.stringify(v,null,2)+'\n');fs.renameSync(p+'.tmp',p);};
async function hash(p){const h=crypto.createHash('sha256');for await(const b of fs.createReadStream(p))h.update(b);return h.digest('hex');}
async function bind(p,expected){const sha256=await hash(p);if(expected&&sha256!==expected.toLowerCase())throw Error('Input hash changed: '+p);return {path:path.resolve(p),bytes:fs.statSync(p).size,sha256};}
function freshPrivate(p){const root=path.resolve(p),allowed=fs.realpathSync(path.join(process.env.LOCALAPPDATA,'FORK-Tools/fork-singapore-full'));if(!root.startsWith(allowed+path.sep)||fs.existsSync(root))throw Error('A fresh private full-Singapore path is required');let a=path.dirname(root);while(!fs.existsSync(a))a=path.dirname(a);if(fs.realpathSync(a)!==a)throw Error('No output junctions');return root;}
export function validateRecovery(job){
  if(job.schemaVersion!==1||job.kind!=='fork-writer-only-recovery'||!job.runs?.length||!job.artifacts?.length||!job.provenance?.length)throw Error('Bound writer-only recovery required');
  if(!Array.isArray(job.bounds)||job.bounds.length!==4||job.bounds.some(n=>!Number.isInteger(n)||n%16)||job.bounds[2]<=job.bounds[0]||job.bounds[3]<=job.bounds[1])throw Error('Invalid global chunk bounds');
  const chunks=(job.bounds[2]-job.bounds[0])*(job.bounds[3]-job.bounds[1])/256;if(chunks>6400||job.maxChunks!==chunks)throw Error('Recovery exceeds the admitted 6400-chunk job');
  if(job.terrain?.mode!=='flat-provisional'||job.terrain.groundY!==0)throw Error('Terrain policy changed');
  if(!job.writer||!job.python||!job.levelTemplate?.path||(!job.leasePath&&!job.queueLease))throw Error('Writer, template and coordinator resource lease required');
  return job;
}
export async function prepare(config){
  const root=freshPrivate(config.outputDir),old=JSON.parse(fs.readFileSync(config.resultPath)),sourceJob=JSON.parse(fs.readFileSync(config.originalJobPath));
  if(old.status!=='failed'||old.tiles?.length!==1)throw Error('Expected the immutable failed single-tile receipt');
  const tile=old.tiles[0],last=old.stages.at(-1);
  if(last.status!=='failed'||!last.name.endsWith('-world')||old.stages.slice(0,-1).some(s=>s.status!=='passed'))throw Error('Recovery may only reuse fully completed stages preceding the writer');
  const runs=[];for(const r of [tile.runs,tile.roadRuns,tile.coastRuns].filter(Boolean))runs.push(await bind(r.path,r.sha256));
  const bounds=tile.renderBounds,writerRoot=path.resolve(config.writerRoot),originalDir=path.dirname(config.resultPath),tileDir=path.join(originalDir,tile.id);
  const provenance=[];for(const p of [config.resultPath,config.originalJobPath,sourceJob.source.path,...['buildings-manifest.json','source-quarantine.json','buildings-mask-manifest.json','roads-manifest.json','coast-manifest.json','coast-mask-manifest.json'].map(n=>path.join(tileDir,n))].filter(fs.existsSync))provenance.push(await bind(p));
  fs.mkdirSync(root,{recursive:true});fs.mkdirSync(path.join(root,'writer'));const artifacts=[];
  for(const name of fs.readdirSync(writerRoot).filter(n=>n.endsWith('.py')).sort()){const p=path.join(root,'writer',name);fs.copyFileSync(path.join(writerRoot,name),p,fs.constants.COPYFILE_EXCL);artifacts.push(await bind(p));}
  const adapter=path.join(root,'pipeline-grow-recover.mjs');fs.copyFileSync(fileURLToPath(import.meta.url),adapter);artifacts.push(await bind(adapter));
  const job=validateRecovery({schemaVersion:1,kind:'fork-writer-only-recovery',id:config.id,bounds,coreBounds:[...tile.coreOrigin,tile.coreOrigin[0]+tile.coreSize,tile.coreOrigin[1]+tile.coreSize],maxChunks:(bounds[2]-bounds[0])*(bounds[3]-bounds[1])/256,terrain:sourceJob.terrain,python:sourceJob.tools.python,writer:path.join(root,'writer','overlay.py'),adapter,runs,artifacts,producerArtifacts:sourceJob.artifacts,provenance,levelTemplate:sourceJob.levelTemplate,leasePath:config.leasePath,queueLease:config.queueLease,timeoutSeconds:900,sourceGeometryComplete:old.qualityGates.sourceGeometryComplete===true,sourceQuarantinePath:path.join(tileDir,'source-quarantine.json'),originalFailedResult:await bind(config.resultPath),originalJob:await bind(config.originalJobPath),source:await bind(sourceJob.source.path,sourceJob.source.sha256),productionAccepted:false});
  job.templateDependencies=[await bind(job.levelTemplate.path,job.levelTemplate.sha256)];const aux=path.join(path.dirname(job.levelTemplate.path),'data/minecraft/world_gen_settings.dat');job.templateDependencies.push(await bind(aux,job.levelTemplate.worldGenSettingsSha256));
  const jobPath=path.join(root,'job.json');save(jobPath,job);console.log(JSON.stringify({status:'PREPARED_NOT_DISPATCHED',job:await bind(jobPath),adapter,runBytes:runs.reduce((n,r)=>n+r.bytes,0)}));return job;
}
export async function run(jobPath,outputDir){
  const job=validateRecovery(JSON.parse(fs.readFileSync(jobPath))),root=freshPrivate(outputDir),now=new Date();
  let lease;
  if(job.queueLease){const e=process.env;if(!/^[a-f0-9]{64}$/.test(e.FORK_JOB_ID??'')||!/^\d+$/.test(e.FORK_JOB_ATTEMPT??'')||path.resolve(e.FORK_OUTPUT_DIR??'')!==root)throw Error('Queue identity missing');lease={...job.queueLease,id:e.FORK_JOB_ID+'-'+e.FORK_JOB_ATTEMPT,machine:'Desktop',outputRoot:root,startsUtc:now.toISOString(),coordinatorPid:process.pid};}
  else lease=JSON.parse(fs.readFileSync(job.leasePath));
  if(lease.approvedBy!==owner||lease.machine!=='Desktop'||!['A','B'].includes(lease.heavyJobSlot)||lease.cpuThreads!==1||lease.memoryGiB!==4||path.resolve(lease.outputRoot)!==root||new Date(lease.startsUtc)>now||new Date(lease.expiresUtc)<=now)throw Error('Resource lease does not authorize this bounded recovery');
  const bound=[...job.runs,...job.artifacts,...job.provenance,...job.templateDependencies,...job.producerArtifacts,await bind(jobPath)];for(const r of bound)await bind(r.path,r.sha256);
  fs.mkdirSync(root,{recursive:true});save(path.join(root,'job-lease.json'),lease);save(path.join(root,'job.json'),job);
  const resultPath=path.join(root,'materialize-result.json'),world=path.join(root,'world'),writerManifest=path.join(root,'writer-manifest.json');
  const result={schemaVersion:1,kind:'fork-writer-only-recovery',status:'writing',jobId:job.id,startedUtc:new Date().toISOString(),coordinatorPid:process.pid,originalFailedResult:job.originalFailedResult,originalJob:job.originalJob,source:job.source,provenance:job.provenance,runs:job.runs,writerArtifacts:job.artifacts,templateDependencies:job.templateDependencies,bounds:job.bounds,coreBounds:job.coreBounds,worldPath:world,writerManifestPath:writerManifest,sourceGeometryComplete:job.sourceGeometryComplete,sourceQuarantinePath:job.sourceQuarantinePath,terrainSurveyed:false,productionAccepted:false,runtimeAccepted:false};
  const update=()=>save(resultPath,result);update();
  const args=[job.writer,...job.runs.flatMap(r=>['--runs',r.path]),'--world',world,'--bounds',job.bounds.join(','),'--manifest',writerManifest,'--level-template',job.levelTemplate.path,'--job-lease',path.join(root,'job-lease.json'),'--max-chunks',String(job.maxChunks)];
  const fd=fs.openSync(path.join(root,'writer.log'),'wx'),started=Date.now(),child=spawn(job.python,args,{cwd:root,windowsHide:true,stdio:['ignore',fd,fd],env:{...process.env,PYTHONDONTWRITEBYTECODE:'1',OMP_NUM_THREADS:'1',OPENBLAS_NUM_THREADS:'1'}});result.writerPid=child.pid;result.argv=[job.python,...args];update();
  const timer=setTimeout(()=>{result.timedOut=true;child.kill();},job.timeoutSeconds*1000),exit=await new Promise(resolve=>{child.once('error',e=>resolve({code:null,error:String(e)}));child.once('close',(code,signal)=>resolve({code,signal}));});clearTimeout(timer);fs.closeSync(fd);result.exit=exit;result.elapsedSeconds=(Date.now()-started)/1000;
  if(exit.code!==0||result.timedOut){result.status='FAILED';update();throw Error('Writer-only recovery failed; see writer.log');}
  for(const r of bound)await bind(r.path,r.sha256);
  const manifest=JSON.parse(fs.readFileSync(writerManifest));if(manifest.status!=='WRITTEN_UNACCEPTED'||manifest.chunkCount!==job.maxChunks)throw Error('Writer receipt did not match bounded recovery');
  result.writerManifest=await bind(writerManifest);result.status='WRITTEN_AWAITING_INDEPENDENT_GATES';result.completedUtc=new Date().toISOString();result.inputsUnchanged=true;update();console.log(JSON.stringify({status:result.status,resultPath,world,writerManifest,elapsedSeconds:result.elapsedSeconds}));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){const opts={};for(let i=2;i<process.argv.length;i+=2)opts[process.argv[i].replace(/^--/,'')]=process.argv[i+1];(opts.prepare?prepare(JSON.parse(fs.readFileSync(opts.prepare))):run(opts.job,opts['attempt-root'])).catch(e=>{console.error(e.stack);process.exitCode=1;});}
