import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {runProcess} from './process-runner.mjs';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const expectedFrames = [60,60,90,90,90,90,90,120,120,120,120,90,120,120,120,90,90,120,300,120,180,180,120];
const shaPattern = /^[a-f0-9]{64}$/i;
const readJson = p => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''));
const assert = (ok, message) => { if (!ok) throw Error(message); };
const local = (p, prefixes = ['media/source/', 'media/edit/', '.work/fork/cinematic/']) => {
  assert(typeof p === 'string' && !path.isAbsolute(p), 'Use a worktree-relative path');
  const rel = path.relative(root, path.resolve(root, p)).replaceAll('\\', '/');
  assert(prefixes.some(prefix => rel.startsWith(prefix)) && !rel.includes('../'), `Unleased media path: ${p}`);
  return path.join(root, rel);
};
const hash = async p => {
  const digest = crypto.createHash('sha256');
  for await (const chunk of fs.createReadStream(p)) digest.update(chunk);
  return digest.digest('hex');
};
const writeJson = (p, value) => fs.writeFileSync(p, JSON.stringify(value, null, 2) + '\n');
const toolchain = () => readJson(path.join(process.env.LOCALAPPDATA, 'FORK-Tools/toolchain.json'));
function probe(p, count = false) {
  const args = ['-v', 'error', ...(count ? ['-count_frames'] : []), '-show_streams', '-show_format', '-of', 'json', p];
  const result = runProcess(toolchain().ffprobe, args, {timeout:60000});
  assert(result.status === 0, `ffprobe failed: ${result.stderr || result.error}`);
  return JSON.parse(result.stdout);
}
export function validateStructure(m) {
  assert(m.schema === 'fork-film-2', 'Wrong film schema');
  assert(m.output?.width === 1920 && m.output?.height === 1080 && m.output?.fps === 30 && m.output?.frames === 2700, 'Film must be 1920x1080 / 30 fps / 2700 frames');
  assert(m.acceptance?.acceptedByMain === true, 'Main has not accepted release/world/mode');
  const a = m.acceptance;
  assert(/^[a-f0-9]{40}$/i.test(a.releaseCommit), 'Missing accepted release commit');
  for (const key of ['packageSha256','worldSha256','cameraBindingSha256']) assert(shaPattern.test(a[key]), `Missing ${key}`);
  assert(['live','fixture','recorded'].includes(a.mode), 'Actual mode must be live, fixture or recorded');
  assert(a.mode !== 'fixture' || a.fixtureApprovedByLucas, 'Fixture requires Lucas scope acceptance');
  for (const key of ['rewindMethod','actualCoverage','newDevelopmentCredit','developmentEvidence']) assert(typeof a[key] === 'string' && a[key].trim().length > 5, `Missing actual ${key}`);
  assert(m.repository === 'https://github.com/aGamingGod1234/fork-minecraft', 'Wrong repository route');
  assert(m.music?.decision === 'captured-game-ambient-only', 'This pipeline uses the selected game-ambient fallback');
  assert(m.credits?.length >= 4 && m.credits.every(c => typeof c === 'string' && c.trim().length > 5), 'Supply final game/map/development/audio credits');
  for (const key of ['sameModeThroughout','actualOutcomesMatchNarration','locatorAttributionAccepted','sourcePrivacyReviewed','comparisonEqualSixRounds','comparisonFinalSixStill']) assert(m.checks?.[key] === true, `Unverified: ${key}`);
  assert(typeof m.checks.threeRestoresEvidence === 'string' && m.checks.threeRestoresEvidence.length > 5, 'Missing three-restores evidence');
  assert(m.shots?.length === 23, 'Need all 23 shots');
  let cursor = 0;
  for (const [index, shot] of m.shots.entries()) {
    assert(shot.id === index + 1 && shot.startFrame === cursor && shot.frames === expectedFrames[index], `Shot ${index + 1}: wrong timeline`);
    assert(shot.reviewed && typeof shot.evidence === 'string' && shot.evidence.length > 5, `Shot ${shot.id}: missing real evidence/review`);
    assert(shot.segments?.length > 0 && shot.segments.reduce((n,s) => n + s.frames, 0) === shot.frames, `Shot ${shot.id}: segments must fill exact slot`);
    for (const segment of shot.segments) {
      assert(Number.isInteger(segment.frames) && segment.frames > 0, 'Segment requires positive integer frames');
      assert(['clip','freeze','card','image'].includes(segment.kind), 'Unknown segment kind');
      if (segment.kind === 'card') {
        assert([2,22,23].includes(shot.id) && segment.reviewed, 'Cards only in accepted title/credit/end slots');
        local(segment.textFile, ['media/edit/', '.work/fork/cinematic/']);
      } else {
        assert(shaPattern.test(segment.sha256) && Number.isFinite(segment.in) && segment.in >= 0 && segment.captionSafeReviewed, 'Clip requires hash, in point and caption-safe review');
        local(segment.source);
        if (segment.kind === 'image') assert(shot.id === 4 && segment.in === 0 && !segment.audio, 'Static locator image only in shot 4, without fabricated audio');
        if (segment.kind === 'clip') assert(Number.isFinite(segment.out) && Math.abs(segment.out - segment.in - segment.frames / 30) <= 1 / 30, 'Clip time must match allocated frames at normal speed');
        if (segment.audio) assert(Number.isFinite(segment.audioGainDb) && segment.audioGainDb >= -60 && segment.audioGainDb <= 0, 'Set captured audio gain between -60 and 0 dB');
      }
      if (segment.overlayTextFile) local(segment.overlayTextFile, ['media/edit/', '.work/fork/cinematic/']);
    }
    cursor += shot.frames;
  }
  assert(m.shots[21].segments.length === 2 && m.shots[21].segments.every(s => s.kind === 'card' && s.frames === 90), 'Development and foundation need separate 3-second cards');
  assert(m.voice?.length > 0, 'Human voice is required');
  let voiceEnd = 0;
  for (const v of m.voice) {
    assert(v.humanConfirmed && v.transcriptConfirmed && shaPattern.test(v.sha256), 'Voice requires confirmed human take, transcript and hash');
    assert(typeof v.text === 'string' && v.text.trim() && v.text.length <= 300, 'Caption text must match a short actual take');
    assert(Number.isFinite(v.in) && Number.isFinite(v.out) && v.in >= 0 && v.out > v.in, 'Invalid voice source times');
    const end = v.at + v.out - v.in;
    assert(Number.isFinite(v.at) && v.at >= voiceEnd && end <= v.windowEnd && v.windowEnd <= 90, 'Voice overlap or timing overflow');
    assert(!(v.at < 70 && end > 68), 'Comparison 68–70 seconds must be narration-free');
    local(v.source);
    voiceEnd = end;
  }
  return {frames: cursor, seconds: cursor / 30, shots: m.shots.length};
}
async function validateSources(m) {
  const sources = new Map();
  for (const item of [...m.shots.flatMap(s => s.segments).filter(s => s.kind !== 'card'), ...m.voice]) {
    const p = local(item.source);
    if (!sources.has(p)) sources.set(p, {sha256: await hash(p), probe: probe(p)});
    const data = sources.get(p);
    assert(data.sha256 === item.sha256.toLowerCase(), `Changed media: ${item.source}`);
    if (item.kind !== 'image') assert(Number(data.probe.format.duration) >= (item.out ?? item.in + 1 / 30) - 0.01, `Source too short: ${item.source}`);
    const isVoice = m.voice.includes(item);
    assert(data.probe.streams.some(s => s.codec_type === (isVoice ? 'audio' : 'video')), `Missing stream: ${item.source}`);
    if (item.audio) assert(data.probe.streams.some(s => s.codec_type === 'audio'), `No captured audio: ${item.source}`);
  }
  for (const s of m.shots.flatMap(s => s.segments)) {
    for (const file of [s.textFile, s.overlayTextFile].filter(Boolean)) {
      const content = fs.readFileSync(local(file), 'utf8');
      assert(content.trim() && !/\b(TODO|TBD|placeholder|INTERNAL PREVIEW)\b/i.test(content), `Unfinished card/cue: ${file}`);
    }
  }
  return [...sources].map(([p, info]) => ({source: path.relative(root, p).replaceAll('\\','/'), ...info}));
}
const filterPath = p => p.replaceAll('\\','/').replaceAll(':','\\:').replaceAll("'", "'\\''");
const stamp = t => {
  const ms = Math.round(t * 1000);
  return `${String(Math.floor(ms / 3600000)).padStart(2,'0')}:${String(Math.floor(ms / 60000) % 60).padStart(2,'0')}:${String(Math.floor(ms / 1000) % 60).padStart(2,'0')},${String(ms % 1000).padStart(3,'0')}`;
};
export function captions(voice) {
  return voice.map((v,i) => `${i+1}\n${stamp(v.at)} --> ${stamp(v.at + v.out - v.in)}\n${v.text}\n`).join('\n');
}
function normalizer(log) {
  const blocks = log.match(/\{\s*"input_i"[\s\S]*?\}/g);
  assert(blocks?.length, 'Missing loudness measurement');
  const data = JSON.parse(blocks.at(-1));
  assert(Number.isFinite(Number(data.input_i)), 'Audio is silent or invalid');
  return data;
}
async function render(m, manifestPath) {
  assert(process.env.COMPUTERNAME?.toUpperCase() === 'LAPTOP', 'All media work must run on Laptop');
  validateStructure(m);
  const sources = await validateSources(m);
  const deadline = Date.parse(m.mediaLeaseDeadlineUtc);
  assert(Number.isFinite(deadline) && Date.now() < deadline, 'Laptop media lease expired or missing; reconcile with Main');
  const runId = new Date().toISOString().replaceAll(/[:.]/g,'-');
  const out = path.join(root, 'media/output', runId);
  const cache = path.join(root, 'media/edit/cache', runId);
  fs.mkdirSync(out, {recursive: true}); fs.mkdirSync(cache, {recursive: true});
  const commands = [];
  const jobFile = path.join(root, '.work/fork/cinematic/media-job.json');
  const job = {owner:'Cinematic',device:'Laptop',runId,pid:process.pid,startedUtc:new Date().toISOString(),inputManifestSha256:await hash(manifestPath),output:path.relative(root,out),deadlineUtc:m.mediaLeaseDeadlineUtc,status:'RUNNING',child:null};
  fs.mkdirSync(path.dirname(jobFile),{recursive:true}); writeJson(jobFile,job);
  const run = (name, args) => {
    assert(Date.now() < deadline, 'Laptop media lease expired; stop and reconcile');
    const started = Date.now();
    const command = {name, executable: toolchain().ffmpeg, args: ['-hide_banner','-nostdin','-n', ...args], cwd: cache, startedUtc: new Date().toISOString()};
    commands.push(command); writeJson(path.join(out, 'render-commands.json'), commands);
    job.child = {step:name,status:'RUNNING',startedUtc:command.startedUtc,pid:null}; writeJson(jobFile,job);
    const result = runProcess(command.executable, command.args, {cwd: cache, timeout: Math.min(deadline-Date.now(),600000)});
    command.elapsedSeconds = (Date.now() - started) / 1000; command.exit = result.status;
    fs.writeFileSync(path.join(cache, name + '.log'), (result.stdout || '') + (result.stderr || ''));
    writeJson(path.join(out, 'render-commands.json'), commands);
    job.child = {step:name,status:result.status===0?'COMPLETE':'FAILED',pid:result.pid,exit:result.status,finishedUtc:new Date().toISOString()};
    if (result.status !== 0) job.status='FAILED';
    writeJson(jobFile,job);
    assert(result.status === 0, `${name} failed; inspect cache log. ${result.error || ''}`);
    return result.stderr;
  };
  const encode = ['-c:v','libx264','-preset','veryfast','-crf','18','-threads','4','-pix_fmt','yuv420p','-c:a','pcm_s16le','-ar','48000','-ac','2'];
  const parts = [];
  const font = filterPath(path.join(process.env.WINDIR, 'Fonts/arial.ttf'));
  let n = 0;
  for (const shot of m.shots) for (const s of shot.segments) {
    const duration = s.frames / 30;
    const file = `segment-${String(++n).padStart(2,'0')}.mkv`;
    const args = [];
    let vf;
    if (s.kind === 'card') {
      args.push('-f','lavfi','-i',`color=c=0x101820:s=1920x1080:r=30:d=${duration}`);
      vf = `drawtext=fontfile='${font}':textfile='${filterPath(local(s.textFile))}':expansion=none:fontcolor=white:fontsize=46:line_spacing=24:x=(w-tw)/2:y=(h-th)/2`;
    } else {
      if (s.kind === 'image') args.push('-loop','1','-framerate','30','-i',local(s.source));
      else args.push('-ss',String(s.in),'-i',local(s.source));
      vf = 'setpts=PTS-STARTPTS,fps=30,scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,setsar=1';
      if (s.kind === 'freeze') vf += `,trim=end_frame=1,tpad=stop_mode=clone:stop_duration=${duration}`;
    }
    if (s.overlayTextFile) vf += `,drawtext=fontfile='${font}':textfile='${filterPath(local(s.overlayTextFile))}':expansion=none:fontcolor=white:fontsize=30:box=1:boxcolor=black@0.8:x=48:y=40`;
    args.push('-f','lavfi','-i',`anullsrc=r=48000:cl=stereo:d=${duration}`);
    args.push('-map','0:v:0','-map',s.kind === 'clip' && s.audio ? '0:a:0' : '1:a:0','-vf',vf,'-af',`asetpts=PTS-STARTPTS,aresample=48000,apad,atrim=duration=${duration},volume=${s.audio ? s.audioGainDb : 0}dB`, '-frames:v',String(s.frames),'-t',String(duration), ...encode, path.join(cache,file));
    run(`segment-${n}`, args); parts.push(file);
  }
  fs.writeFileSync(path.join(cache,'segments.ffconcat'), 'ffconcat version 1.0\n' + parts.map(p => `file '${p}'`).join('\n') + '\n');
  run('join', ['-f','concat','-safe','1','-i','segments.ffconcat','-c','copy',path.join(cache,'picture.mkv')]);
  const voiceArgs = [], graph = [];
  m.voice.forEach((v,i) => {
    voiceArgs.push('-ss',String(v.in),'-t',String(v.out-v.in),'-i',local(v.source));
    graph.push(`[${i}:a]aresample=48000,aformat=channel_layouts=stereo,asetpts=PTS-STARTPTS,adelay=${Math.round(v.at*1000)}:all=1[v${i}]`);
  });
  graph.push(m.voice.map((_,i) => `[v${i}]`).join('') + `amix=inputs=${m.voice.length}:normalize=0:duration=longest,apad,atrim=duration=90[voice]`);
  run('voice-assemble',[...voiceArgs,'-filter_complex',graph.join(';'),'-map','[voice]','-c:a','pcm_s24le','-ar','48000',path.join(cache,'voice-raw.wav')]);
  const voiceMeasure = normalizer(run('voice-measure',['-i',path.join(cache,'voice-raw.wav'),'-af','loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json','-f','null','-']));
  const norm = d => `loudnorm=I=-16:TP=-1.5:LRA=11:measured_I=${d.input_i}:measured_TP=${d.input_tp}:measured_LRA=${d.input_lra}:measured_thresh=${d.input_thresh}:offset=${d.target_offset}:linear=true:print_format=json,aresample=48000`;
  run('voice-final',['-i',path.join(cache,'voice-raw.wav'),'-af',norm(voiceMeasure),'-c:a','pcm_s24le','-ar','48000',path.join(out,'voice-final.wav')]);
  run('mix',['-i',path.join(cache,'picture.mkv'),'-i',path.join(out,'voice-final.wav'),'-filter_complex','[0:a][1:a]amix=inputs=2:normalize=0:duration=longest,atrim=duration=90[a]','-map','[a]','-c:a','pcm_s24le',path.join(cache,'mix.wav')]);
  const mixMeasure = normalizer(run('mix-measure',['-i',path.join(cache,'mix.wav'),'-af','loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json','-f','null','-']));
  fs.writeFileSync(path.join(out,'fork-demo.srt'), captions(m.voice));
  run('final',['-i',path.join(cache,'picture.mkv'),'-i',path.join(cache,'mix.wav'),'-map','0:v:0','-map','1:a:0','-vf',`subtitles='${filterPath(path.join(out,'fork-demo.srt'))}':force_style='Fontname=Arial,Fontsize=22,Outline=2,Alignment=2,MarginV=24'`,'-af',norm(mixMeasure),'-frames:v','2700','-t','90','-c:v','libx264','-preset','veryfast','-crf','18','-threads','4','-pix_fmt','yuv420p','-c:a','aac','-b:a','192k','-ar','48000','-ac','2','-movflags','+faststart',path.join(out,'fork-demo-1080p30.mp4')]);
  fs.writeFileSync(path.join(out,'credits.txt'), m.credits.join('\n\n') + '\n\n' + m.repository + '\n');
  fs.copyFileSync(manifestPath, path.join(out,'edit-manifest.json'));
  const manifest = {...m, status:'ENCODED - HUMAN VIEWING PENDING', createdUtc:new Date().toISOString(), device:'Laptop', sourceEvidence:sources, renderCommands:'render-commands.json', outputHashes:{}, technicalVerification:null};
  for (const source of sources) assert(await hash(local(source.source)) === source.sha256, `Source changed during rendering: ${source.source}`);
  manifest.cardHashes={};
  for (const segment of m.shots.flatMap(s=>s.segments)) for (const file of [segment.textFile,segment.overlayTextFile].filter(Boolean)) manifest.cardHashes[file]=await hash(local(file));
  for (const name of ['fork-demo-1080p30.mp4','fork-demo.srt','voice-final.wav','credits.txt','edit-manifest.json','render-commands.json']) manifest.outputHashes[name] = await hash(path.join(out,name));
  writeJson(path.join(out,'film-manifest.json'),manifest);
  await verify(out);
  job.status='COMPLETE'; job.finishedUtc=new Date().toISOString(); writeJson(jobFile,job);
  console.log(`Encoded to ${path.relative(root,out)}. Full human viewing remains required.`);
}
async function verify(out) {
  const p = path.join(out,'fork-demo-1080p30.mp4');
  const manifest = readJson(path.join(out,'film-manifest.json'));
  const info = probe(p, true);
  const video = info.streams.find(s => s.codec_type === 'video'), audio = info.streams.find(s => s.codec_type === 'audio');
  assert(video?.codec_name === 'h264' && video.width === 1920 && video.height === 1080 && video.r_frame_rate === '30/1' && Number(video.nb_read_frames) === 2700, 'Video format/frame check failed');
  assert(audio?.codec_name === 'aac' && Number(audio.sample_rate) === 48000 && Math.abs(Number(info.format.duration)-90) < 0.05, 'Audio format/duration check failed');
  const started = Date.now();
  const args = ['-hide_banner','-nostdin','-v','info','-xerror','-i',p,'-map','0:v:0','-map','0:a:0','-af','loudnorm=I=-16:TP=-1:LRA=11:print_format=json','-f','null','-'];
  const result = runProcess(toolchain().ffmpeg,args,{timeout:600000});
  fs.writeFileSync(path.join(out,'verification.log'), result.stderr || '');
  assert(result.status === 0, 'Full decode failed');
  const loudness = normalizer(result.stderr);
  assert(Math.abs(Number(loudness.input_i)+16) <= 0.5 && Number(loudness.input_tp) <= -1, `Loudness failed: ${loudness.input_i} LUFS / ${loudness.input_tp} dBTP`);
  for (const [name, digest] of Object.entries(manifest.outputHashes)) assert(await hash(path.join(out,name)) === digest, `Changed output: ${name}`);
  manifest.technicalVerification = {passed:true,checkedUtc:new Date().toISOString(),elapsedSeconds:(Date.now()-started)/1000,probe:info,loudness,decodeExit:result.status,command:{executable:toolchain().ffmpeg,args},humanViewingPassed:false};
  writeJson(path.join(out,'film-manifest.json'),manifest);
  console.log('PASS: 2700 frames, H.264/AAC, 1080p30, 48 kHz, full decode and measured loudness. Human viewing unrun.');
}
async function main() {
  const [command, name] = process.argv.slice(2);
  assert(['inspect','validate','render','verify'].includes(command) && name, 'Usage: film.mjs inspect <source> | validate <edit.json> | render <edit.json> | verify <output-folder>');
  if (command === 'inspect') { const p = local(name); console.log(JSON.stringify({source:name,sha256:await hash(p),probe:probe(p)},null,2)); return; }
  if (command === 'verify') { await verify(local(name,['media/output/'])); return; }
  const p = local(name,['media/edit/','.work/fork/cinematic/']);
  const m = readJson(p);
  if (command === 'validate') { const summary = validateStructure(m); const sources = await validateSources(m); console.log(JSON.stringify({passed:true,...summary,sources:sources.length})); }
  else await render(m,p);
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(e => {
  const jobFile=path.join(root,'.work/fork/cinematic/media-job.json');
  if(fs.existsSync(jobFile)) {
    const job=readJson(jobFile);
    if(job.pid===process.pid) {job.status='FAILED';job.finishedUtc=new Date().toISOString();job.error=e.message;writeJson(jobFile,job);}
  }
  console.error(e.message); process.exitCode = 1;
});
