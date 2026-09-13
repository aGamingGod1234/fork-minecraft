import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const digest=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const o={};for(let i=2;i<process.argv.length;i+=2)o[process.argv[i].replace(/^--/,'')]=process.argv[i+1];
if(!o.job||!o['attempt-root'])throw Error('Usage: pipeline-materialize.mjs --job immutable.json --attempt-root NEW_OUTPUT');
const job=JSON.parse(fs.readFileSync(o.job)),root=path.resolve(o['attempt-root']),allowed=fs.realpathSync(path.join(process.env.LOCALAPPDATA,'FORK-Tools/fork-singapore-full'));
if(!root.startsWith(allowed+path.sep)||fs.existsSync(root))throw Error('A fresh isolated attempt output is required');
if(!Array.isArray(job.runs)||!job.runs.length||!Array.isArray(job.bounds)||job.bounds.length!==4)throw Error('Input runs and global bounds are required');
fs.mkdirSync(root,{recursive:true});if(fs.realpathSync(root)!==root)throw Error('No attempt junctions');
const inputs=path.join(root,'inputs');fs.mkdirSync(inputs);const entries=[];
function freeze(source,relative,expected){const target=path.join(inputs,relative),hash=digest(source);if(expected&&hash!==expected.toLowerCase())throw Error('Input changed: '+source);fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(source,target,fs.constants.COPYFILE_EXCL);if(digest(target)!==hash)throw Error('Frozen copy hash mismatch');entries.push({sourcePath:path.resolve(source),snapshotPath:'inputs/'+relative.replaceAll('\\','/'),bytes:fs.statSync(target).size,sha256:hash});return target;}
const runs=job.runs.map((r,i)=>freeze(r.path,'runs/'+i+'-'+path.basename(r.path),r.sha256));
const writerDir=path.dirname(job.writer);
for(const entry of fs.readdirSync(writerDir,{withFileTypes:true}))if(entry.isFile()&&entry.name.endsWith('.py'))freeze(path.join(writerDir,entry.name),'writer/'+entry.name,job.writerArtifacts?.[entry.name]);
const writer=path.join(inputs,'writer',path.basename(job.writer));
const template=freeze(job.levelTemplate,'template/level.dat',job.levelTemplateSha256);
const external=path.join(path.dirname(job.levelTemplate),'data/minecraft/world_gen_settings.dat');
if(fs.existsSync(external))freeze(external,'template/data/minecraft/world_gen_settings.dat',job.worldGenSettingsSha256);
const lease=freeze(job.leasePath,'job-lease.json');freeze(o.job,'job.json');freeze(fileURLToPath(import.meta.url),'pipeline-materialize.mjs');
fs.writeFileSync(path.join(inputs,'manifest.json'),JSON.stringify({schemaVersion:1,status:'FROZEN',files:entries},null,2));
const world=path.join(root,'world'),manifest=path.join(root,'writer-manifest.json'),log=path.join(root,'writer.log');
const receipt={schemaVersion:1,status:'writing',startedUtc:new Date().toISOString(),coordinatorPid:process.pid,jobId:job.id,bounds:job.bounds,inputsManifestSha256:digest(path.join(inputs,'manifest.json')),inputDecision:job.inputDecision??null,fullWorldAccepted:false,runtimeAccepted:false};
const receiptPath=path.join(root,'materialize-result.json'),save=()=>fs.writeFileSync(receiptPath,JSON.stringify(receipt,null,2));save();
const args=[writer,...runs.flatMap(p=>['--runs',p]),'--world',world,'--bounds',job.bounds.join(','),'--manifest',manifest,'--level-template',template,'--job-lease',lease];
const fd=fs.openSync(log,'wx'),proc=spawn(job.python,args,{cwd:root,windowsHide:true,stdio:['ignore',fd,fd],env:{...process.env,PYTHONDONTWRITEBYTECODE:'1',OMP_NUM_THREADS:'1'}});receipt.writerPid=proc.pid;save();
let timedOut=false;const timer=setTimeout(()=>{timedOut=true;proc.kill();},120000);
const status=await new Promise(resolve=>{proc.once('error',error=>resolve({code:null,error:String(error)}));proc.once('close',code=>resolve({code}));});clearTimeout(timer);fs.closeSync(fd);receipt.writerResult={...status,timedOut};
receipt.status=status.code===0&&!timedOut?'WRITTEN_AWAITING_INDEPENDENT_GATES':'FAILED';receipt.completedUtc=new Date().toISOString();
if(receipt.status.startsWith('WRITTEN')){receipt.writerManifestSha256=digest(manifest);receipt.outputs=JSON.parse(fs.readFileSync(manifest)).outputs;}
save();console.log(JSON.stringify({status:receipt.status,world,manifest,receipt:receiptPath}));if(receipt.status==='FAILED')process.exitCode=1;
