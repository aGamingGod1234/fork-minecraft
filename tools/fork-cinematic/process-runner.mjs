import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
let counter=0;
// Windows worker sandboxes can reject Node's default pipe handles. Ordinary
// file-backed stdio preserves the same headless executable and exact arguments.
export function runProcess(executable,args,options={}) {
  const logs=path.join(root,'.work/fork/cinematic/process-logs');
  fs.mkdirSync(logs,{recursive:true});
  const name=`${Date.now()}-${process.pid}-${++counter}`;
  const stdoutPath=path.join(logs,`${name}.stdout`), stderrPath=path.join(logs,`${name}.stderr`);
  const stdout=fs.openSync(stdoutPath,'wx'), stderr=fs.openSync(stderrPath,'wx');
  let result;
  try { result=spawnSync(executable,args,{...options,windowsHide:true,stdio:['ignore',stdout,stderr]}); }
  finally {fs.closeSync(stdout);fs.closeSync(stderr);}
  return {...result,stdout:fs.readFileSync(stdoutPath,'utf8'),stderr:fs.readFileSync(stderrPath,'utf8'),stdoutPath,stderrPath};
}
if (process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const t=JSON.parse(fs.readFileSync(path.join(process.env.LOCALAPPDATA,'FORK-Tools/toolchain.json'),'utf8').replace(/^\uFEFF/,''));
  const r=runProcess(t.ffmpeg,['-version'],{timeout:10000});
  console.log(JSON.stringify({exit:r.status,error:r.error?.message,version:r.stdout.split('\n')[0],stdoutPath:r.stdoutPath,stderrPath:r.stderrPath}));
  process.exitCode=r.status===0?0:1;
}
