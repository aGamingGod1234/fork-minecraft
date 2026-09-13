import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {runProcess} from './process-runner.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const files=[['assets/fork-world/locator/singapore-locator.svg','singapore-locator.svg','1915b3dffc084c49d75bf84b823cae001ecfc55e165a591692b63b6600225085'],['docs/fork-world/LOCATOR-CREDITS.txt','LOCATOR-CREDITS.txt',null],['data/fork-world/locator/source-manifest.json','locator-source-manifest.json',null]];
const out=path.join(root,'.work/fork/cinematic/world-locator-4800463');fs.mkdirSync(out,{recursive:true});
for(const [source,name,expected] of files){
  const r=runProcess('git',['show',`4800463d27410aa5e248b21c54b230cc32469714:${source}`],{cwd:root,timeout:10000});
  if(r.status!==0)throw Error(r.error?.message||r.stderr);
  const hash=crypto.createHash('sha256').update(r.stdout,'utf8').digest('hex');
  if(expected&&hash!==expected)throw Error('Locator bytes differ from accepted World source manifest');
  fs.writeFileSync(path.join(out,name),r.stdout,{flag:'wx'});console.log(`${name} ${hash}`);
}
