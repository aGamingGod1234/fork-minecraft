import fs from 'node:fs';
import path from 'node:path';
import {runProcess} from './process-runner.mjs';

if (process.env.COMPUTERNAME?.toUpperCase() !== 'LAPTOP') throw Error('Laptop media lease only');
const root=process.cwd();
const card=process.argv[2] || 'end';
if (!['title','end','foundation'].includes(card)) throw Error('Select title, end or foundation');
const toolchain=JSON.parse(fs.readFileSync(path.join(process.env.LOCALAPPDATA,'FORK-Tools/toolchain.json'),'utf8').replace(/^\uFEFF/,''));
const escaped=p=>p.replaceAll('\\','/').replaceAll(':','\\:');
const text=path.join(root,`media/edit/cards/${card}.txt`);
const output=path.join(root,`.work/fork/cinematic/${card}-internal-card-preview.png`);
const filter=`drawtext=fontfile='${escaped(path.join(process.env.WINDIR,'Fonts/arial.ttf'))}':textfile='${escaped(text)}':expansion=none:fontcolor=white:fontsize=46:line_spacing=24:x=(w-tw)/2:y=(h-th)/2`;
const args=['-hide_banner','-nostdin','-n','-f','lavfi','-i','color=c=0x101820:s=1920x1080:r=30','-vf',filter,'-frames:v','1','-update','1',output];
const result=runProcess(toolchain.ffmpeg,args,{timeout:10000});
fs.writeFileSync(path.join(root,`.work/fork/cinematic/${card}-preview-check.json`),JSON.stringify({kind:'Single typographic card, not footage or film',utc:new Date().toISOString(),command:{executable:toolchain.ffmpeg,args},exit:result.status,error:result.error?.message,stderr:result.stderr},null,2));
if(result.status!==0) throw Error(result.error?.message || result.stderr);
console.log(output);
