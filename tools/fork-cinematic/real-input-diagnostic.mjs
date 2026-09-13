import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {runProcess} from './process-runner.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const check=(ok,message)=>{if(!ok)throw Error(message);};
const json=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const hash=async p=>{const h=crypto.createHash('sha256');for await(const c of fs.createReadStream(p))h.update(c);return h.digest('hex');};
const escape=p=>p.replaceAll('\\','/').replaceAll(':','\\:');
async function main(){
  check(process.env.COMPUTERNAME?.toUpperCase()==='LAPTOP','Laptop media only');
  const deadline=Date.parse('2026-09-13T03:35:00Z');
  check(Date.now()<deadline,'11:35 real-input gate has closed; report unrun and await Main media assignment');
  const relative=process.argv[2];
  check(relative && !path.isAbsolute(relative),'Pass an ingested media/source filename');
  const source=path.resolve(root,relative), rel=path.relative(root,source).replaceAll('\\','/');
  check(rel.startsWith('media/source/') && !rel.includes('../'),'Use an ingested media/source file');
  const t=json(path.join(process.env.LOCALAPPDATA,'FORK-Tools/toolchain.json'));
  const probed=runProcess(t.ffprobe,['-v','error','-show_streams','-show_format','-of','json',source],{timeout:10000});
  check(probed.status===0,probed.stderr||probed.error?.message);
  const inputProbe=JSON.parse(probed.stdout), duration=Number(inputProbe.format.duration);
  check(duration>=15,'Need at least 15 seconds of stopped real capture; do not pad with invented media');
  check(inputProbe.streams.some(s=>s.codec_type==='video') && inputProbe.streams.some(s=>s.codec_type==='audio'),'Real capture must contain picture and recorded audio');
  const inputHash=await hash(source), runId='real-input-'+new Date().toISOString().replaceAll(/[:.]/g,'-');
  const out=path.join(root,'media/output',runId);fs.mkdirSync(out,{recursive:true});
  const evidence={schema:'fork-real-input-diagnostic-1',kind:'INTERNAL REAL INPUT DIAGNOSTIC - FIXTURE NOT APPROVED FOR SUBMISSION',utc:new Date().toISOString(),device:'Laptop',source:rel,sourceSha256:inputHash,inputProbe,sourceTrimsSeconds:[[0,7.5],[7.5,15]],outputDirectory:path.relative(root,out).replaceAll('\\','/'),humanVoiceConfirmed:false,gameViewConfirmed:false,humanPlaybackConfirmed:false,finalFilm:false,commands:[],jobs:[]};
  const save=()=>fs.writeFileSync(path.join(out,'diagnostic-manifest.json'),JSON.stringify(evidence,null,2));
  const run=(name,args)=>{
    check(Date.now()<deadline,'11:35 gate reached; stop this diagnostic slice');
    const command={name,executable:t.ffmpeg,args:['-hide_banner','-nostdin','-n',...args],startedUtc:new Date().toISOString()};
    evidence.commands.push(command); evidence.jobs=[{pid:process.pid,step:name,startedUtc:command.startedUtc}];save();
    const started=Date.now(),r=runProcess(t.ffmpeg,command.args,{timeout:Math.max(1,Math.min(120000,deadline-Date.now()))});
    command.seconds=(Date.now()-started)/1000;command.exit=r.status;command.childPid=r.pid;
    fs.writeFileSync(path.join(out,name+'.log'),r.stderr||r.error?.message||'');evidence.jobs=[];save();
    check(r.status===0,`${name} failed: ${r.error?.message||r.stderr.slice(-500)}`);return r;
  };
  // Preserve the captured human sentence and game ambience together. OBS's
  // prepared one-track profile cannot prove isolated voice/music stems.
  const graph="[0:v]split=2[va][vb];[va]trim=start=0:end=7.5,setpts=PTS-STARTPTS[a];[vb]trim=start=7.5:end=15,setpts=PTS-STARTPTS[b];[a][b]concat=n=2:v=1:a=0,fps=30,scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,setsar=1,drawbox=x=0:y=0:w=iw:h=130:color=black@0.8:t=fill,drawtext=fontfile='"+escape(path.join(process.env.WINDIR,'Fonts/arial.ttf'))+"':text='FORK - INTERNAL REAL INPUT CHECK':fontcolor=white:fontsize=36:x=48:y=24,drawtext=fontfile='"+escape(path.join(process.env.WINDIR,'Fonts/arial.ttf'))+"':text='Fixture diagnostic only - not the final film':fontcolor=white:fontsize=28:x=48:y=76,format=yuv420p[v];[0:a]asplit=2[aa][ab];[aa]atrim=start=0:end=7.5,asetpts=PTS-STARTPTS[ac];[ab]atrim=start=7.5:end=15,asetpts=PTS-STARTPTS[ad];[ac][ad]concat=n=2:v=0:a=1,aresample=48000,loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000[aout]";
  fs.writeFileSync(path.join(out,'filter-graph.txt'),graph);
  run('encode',['-i',source,'-filter_complex',graph,'-map','[v]','-map','[aout]','-frames:v','450','-t','15','-c:v','libx264','-preset','veryfast','-crf','20','-threads','4','-c:a','aac','-b:a','192k','-ar','48000','-ac','2','-movflags','+faststart',path.join(out,'fork-real-input-15s.mp4')]);
  run('captured-audio',['-i',source,'-t','15','-vn','-c:a','pcm_s16le','-ar','48000',path.join(out,'captured-voice-and-ambient.wav')]);
  run('decode-and-audio-measure',['-xerror','-i',path.join(out,'fork-real-input-15s.mp4'),'-af','loudnorm=I=-16:TP=-1:LRA=11:print_format=json','-f','null','-']);
  const outputProbe=runProcess(t.ffprobe,['-v','error','-count_frames','-show_streams','-show_format','-of','json',path.join(out,'fork-real-input-15s.mp4')],{timeout:15000});
  check(outputProbe.status===0,'Output probe failed');evidence.outputProbe=JSON.parse(outputProbe.stdout);
  const v=evidence.outputProbe.streams.find(s=>s.codec_type==='video'),a=evidence.outputProbe.streams.find(s=>s.codec_type==='audio');
  check(v?.codec_name==='h264' && v.width===1920 && v.height===1080 && v.r_frame_rate==='30/1' && Number(v.nb_read_frames)===450,'450-frame 1080p30 check failed');
  check(a?.codec_name==='aac' && a.sample_rate==='48000' && Math.abs(Number(evidence.outputProbe.format.duration)-15)<0.05,'AAC/15-second check failed');
  run('review-frames',['-i',path.join(out,'fork-real-input-15s.mp4'),'-vf','select=eq(n\,30)+eq(n\,225)+eq(n\,420),scale=960:540,tile=3x1','-frames:v','1','-update','1',path.join(out,'review-strip.png')]);
  check(await hash(source)===inputHash,'Source changed during diagnostic');
  evidence.outputSha256=await hash(path.join(out,'fork-real-input-15s.mp4'));evidence.technicalCheckPassed=true;evidence.completedUtc=new Date().toISOString();save();
  console.log(JSON.stringify({output:evidence.outputDirectory+'/fork-real-input-15s.mp4',review:evidence.outputDirectory+'/review-strip.png',technicalCheckPassed:true,humanVoiceAndPlaybackPending:true},null,2));
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
