import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const root=path.resolve(process.argv[2]??'');
const allowed=fs.realpathSync(path.join(process.env.LOCALAPPDATA,'FORK-Tools/fork-singapore-full'));
if(!root.startsWith(allowed+path.sep)||fs.realpathSync(root)!==root)throw Error('Snapshot requires an isolated real attempt path');
const resultPath=path.join(root,'output/pipeline-result.json');
const result=JSON.parse(fs.readFileSync(resultPath));
const destination=path.join(root,'inputs');
if(fs.existsSync(destination))throw Error('Snapshot exists; never overwrite frozen inputs');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const entries=new Map();
for(const p of [...result.codeArtifacts??[],...Object.values(result.toolInputs??{}),result.source].filter(Boolean))entries.set(p.path,p);
for(const name of ['job.json','job-lease.json']){const p=path.join(root,name);if(fs.existsSync(p))entries.set(p,{path:p,sha256:sha(fs.readFileSync(p))});}
// Validate every original byte before creating a partial snapshot.
for(const p of entries.values())if(sha(fs.readFileSync(p.path))!==p.sha256)throw Error('Frozen input changed before snapshot: '+p.path);
fs.mkdirSync(path.join(destination,'files'),{recursive:true});
let index=0;const files=[];
for(const p of entries.values()){
  const bytes=fs.readFileSync(p.path),rel='files/'+String(index++).padStart(3,'0')+'-'+path.basename(p.path);
  fs.writeFileSync(path.join(destination,rel),bytes,{flag:'wx'});
  if(sha(fs.readFileSync(path.join(destination,rel)))!==p.sha256)throw Error('Snapshot verification failed');
  files.push({originalPath:p.path,snapshotPath:rel,bytes:bytes.length,sha256:p.sha256});
}
const manifest={schemaVersion:1,status:'FROZEN_INPUT_BYTES_VERIFIED',originalStatus:result.status,createdUtc:new Date().toISOString(),pipelineResultSha256:sha(fs.readFileSync(resultPath)),files};
fs.writeFileSync(path.join(destination,'manifest.json'),JSON.stringify(manifest,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({status:manifest.status,inputCount:files.length,manifest:path.join(destination,'manifest.json')}));
