import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {runProcess} from './process-runner.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const check=(ok,message)=>{if(!ok)throw Error(message);};
const digest=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const owned=p=>{check(p&&!path.isAbsolute(p),'Use a relative delivered locator path');const resolved=path.resolve(root,p),rel=path.relative(root,resolved).replaceAll('\\','/');check(['.work/fork/cinematic/','media/source/'].some(prefix=>rel.startsWith(prefix))&&!rel.includes('../'),'Locator input must be in the Cinematic delivery/source area');return resolved;};
function main(){
  check(process.env.COMPUTERNAME?.toUpperCase()==='LAPTOP','Laptop media only');
  const [svgRelative,creditsRelative,expectedSha]=process.argv.slice(2);
  const svg=owned(svgRelative),credits=owned(creditsRelative);
  check(/^[a-f0-9]{64}$/i.test(expectedSha),'Supply Main/World locator SHA256');
  check(digest(svg)===expectedSha.toLowerCase(),'Locator hash mismatch');
  const svgText=fs.readFileSync(svg,'utf8'),creditText=fs.readFileSync(credits,'utf8');
  check(svgText.includes('<svg') && creditText.trim().length>30,'Need actual SVG and source/license credits');
  check(!/<(?:script|foreignObject)\b|(?:href|src)\s*=\s*["'](?:https?:|file:|\/\/)|<!ENTITY/i.test(svgText),'Locator must be self-contained passive vector artwork');
  const destination=path.join(root,'media/source',`locator-${expectedSha.slice(0,12).toLowerCase()}.png`);
  fs.mkdirSync(path.dirname(destination),{recursive:true});
  const toolchain=JSON.parse(fs.readFileSync(path.join(process.env.LOCALAPPDATA,'FORK-Tools/toolchain.json'),'utf8').replace(/^\uFEFF/,''));
  const args=['-hide_banner','-nostdin','-n','-i',svg,'-vf','scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x101820','-frames:v','1','-update','1',destination];
  const result=runProcess(toolchain.ffmpeg,args,{timeout:30000});
  const evidence={schema:'fork-locator-film-1',utc:new Date().toISOString(),source:svgRelative,sourceSha256:expectedSha,creditsSource:creditsRelative,creditsSha256:digest(credits),credits:creditText,command:{executable:toolchain.ffmpeg,args},exit:result.status,error:result.error?.message,stderr:result.stderr,output:path.relative(root,destination).replaceAll('\\','/'),outputSha256:result.status===0?digest(destination):null,visuallyReviewed:false,coverageStatement:'Singapore overview locator. Scored court is fictional; map outline is not walkable generated coverage.',shot:4};
  fs.writeFileSync(path.join(root,'.work/fork/cinematic/locator-film-evidence.json'),JSON.stringify(evidence,null,2));
  check(result.status===0,'Locator decode failed; retain original and escalate to Main, no new renderer');
  console.log(JSON.stringify(evidence,null,2));
}
try{main();}catch(e){console.error(e.message);process.exitCode=1;}
