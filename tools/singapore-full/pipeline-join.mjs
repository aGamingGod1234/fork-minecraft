import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import readline from 'node:readline';
import {spawn} from 'node:child_process';

const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const opt={};for(let i=2;i<process.argv.length;i+=2)opt[process.argv[i].replace(/^--/,'')]=process.argv[i+1];
if(!opt.result||!opt.lease)throw Error('Usage: pipeline-join.mjs --result pipeline-result.json --lease fresh-job-lease.json');
const root=path.dirname(path.resolve(opt.result)),allowed=fs.realpathSync(path.join(process.env.LOCALAPPDATA,'FORK-Tools/fork-singapore-full'));
if(!root.startsWith(allowed+path.sep)||fs.realpathSync(root)!==root)throw Error('Join input must be an isolated full-Singapore attempt');
const r=JSON.parse(fs.readFileSync(opt.result)),job=JSON.parse(fs.readFileSync(path.join(root,'job.json')));
if(r.status!=='generated-awaiting-independent-gates'||r.tiles.some(t=>t.status!=='written'))throw Error('All bounded input worlds must be written before joining');
const dir=path.join(root,'joined');if(fs.existsSync(dir))throw Error('Joined artifact already exists; no overwrite');
for(const p of r.codeArtifacts)if(sha(p.path)!==p.sha256)throw Error('Frozen dependency changed: '+p.path);
const boxes=r.tiles.map(t=>[...t.coreOrigin,t.coreOrigin[0]+t.coreSize,t.coreOrigin[1]+t.coreSize]);
const bounds=[Math.min(...boxes.map(b=>b[0])),Math.min(...boxes.map(b=>b[1])),Math.max(...boxes.map(b=>b[2])),Math.max(...boxes.map(b=>b[3]))];
for(let i=0;i<boxes.length;i++)for(let j=i+1;j<boxes.length;j++)if(Math.max(boxes[i][0],boxes[j][0])<Math.min(boxes[i][2],boxes[j][2])&&Math.max(boxes[i][1],boxes[j][1])<Math.min(boxes[i][3],boxes[j][3]))throw Error('Core ownership overlaps');
if(boxes.reduce((sum,b)=>sum+(b[2]-b[0])*(b[3]-b[1]),0)!==(bounds[2]-bounds[0])*(bounds[3]-bounds[1]))throw Error('Cores do not cover a complete rectangle');
fs.mkdirSync(dir);const runs=path.join(dir,'owned-core.runs.jsonl'),fd=fs.openSync(runs,'wx');let count=0;const inputs=[];
try{for(const t of r.tiles)for(const input of [{path:t.runsPath,sha256:t.runs.sha256},...(t.roadRunsPath?[{path:t.roadRunsPath,sha256:t.roadRuns.sha256}]:[]),...(t.coastRunsPath?[{path:t.coastRunsPath,sha256:t.coastRuns.sha256}]:[])]){const p=path.resolve(root,input.path);if(!p.startsWith(root+path.sep))throw Error('Input runs escaped the attempt');if(sha(p)!==input.sha256)throw Error('Input runs changed: '+t.id);let retained=0;
  for await(const line of readline.createInterface({input:fs.createReadStream(p),crlfDelay:Infinity})){if(!line.trim())continue;const v=JSON.parse(line),[x,z]=t.coreOrigin;if(v.x>=x&&v.x<x+t.coreSize&&v.z>=z&&v.z<z+t.coreSize){fs.writeSync(fd,line+'\n');retained++;count++;}}
  inputs.push({tileId:t.id,path:input.path,sha256:input.sha256,retainedRuns:retained});
}}finally{fs.closeSync(fd);}
const world=path.join(dir,'world'),manifest=path.join(dir,'writer-manifest.json'),log=path.join(dir,'writer.log'),logfd=fs.openSync(log,'wx');
const receipt={schemaVersion:1,status:'writing',bounds,inputs,retainedRuns:count,runsSha256:sha(runs),pipelineResultSha256:sha(opt.result),joinCodeSha256:sha(new URL(import.meta.url)),leaseSha256:sha(opt.lease),globalCoordinates:true,coreOwnership:'half-open exact; halo discarded',fullWorldAccepted:false,startedUtc:new Date().toISOString(),coordinatorPid:process.pid};
const receiptPath=path.join(dir,'join-receipt.json'),save=()=>fs.writeFileSync(receiptPath,JSON.stringify(receipt,null,2));save();
const args=[job.tools.overlay,'--runs',runs,'--world',world,'--bounds',bounds.join(','),'--manifest',manifest,'--level-template',job.levelTemplate.path,'--job-lease',opt.lease,'--max-chunks',String((bounds[2]-bounds[0])*(bounds[3]-bounds[1])/256)];
const proc=spawn(job.tools.python,args,{windowsHide:true,stdio:['ignore',logfd,logfd],env:{...process.env,PYTHONDONTWRITEBYTECODE:'1',OMP_NUM_THREADS:'1'}});receipt.writerPid=proc.pid;save();
const result=await new Promise(resolve=>{proc.once('error',error=>resolve({code:null,error:String(error)}));proc.once('close',code=>resolve({code}));});fs.closeSync(logfd);receipt.writerResult=result;receipt.status=result.code===0?'WRITTEN_AWAITING_INDEPENDENT_GATES':'FAILED';receipt.completedUtc=new Date().toISOString();if(result.code===0)receipt.writerManifestSha256=sha(manifest);save();
console.log(JSON.stringify({status:receipt.status,world,manifest,receipt:receiptPath,retainedRuns:count}));if(result.code!==0)process.exitCode=1;
