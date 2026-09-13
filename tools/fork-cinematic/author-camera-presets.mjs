import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const round=n=>Math.round(n*1e6)/1e6;
const frame=(tick,p,target)=>{const dx=target[0]-p[0],dy=target[1]-p[1],dz=target[2]-p[2];return {tick,x:p[0],y:p[1],z:p[2],yaw:round(Math.atan2(-dx,dz)*180/Math.PI),pitch:round(-Math.atan2(dy,Math.hypot(dx,dz))*180/Math.PI)};};
const definitions=[
  ['fork_intro',120,[54.5,7,34.5],[54.5,6.7,34],[54.5,0,28.5],'Two power routes; elevated gentle push'],
  ['fork_clinic',100,[49.5,2.6,29.5],[49.5,2.6,29],[49.5,2.6,25.5],'Clinic approach and medic; verify doorway clearance'],
  ['fork_workshop',100,[57.5,2.6,29.5],[57.65,2.6,29],[58.5,2.6,25.5],'Engineer and workshop; verify repair indicator readability'],
  ['fork_courier',100,[51.5,2.6,35.5],[51.05,2.6,35.3],[49.5,2.6,34.5],'Courier and spare; short oblique push'],
  ['fork_overview',160,[54.5,10,36.5],[55.1,10,36.1],[54,1,27],'Elevated court overview; first framing test'],
  ['fork_comparison',400,[54.5,10,36.5],[54.5,10,36.5],[54,1,27],'Stationary 20-second hold for actual result/compare UI']
];
const presets={paths:definitions.map(([name,ticks,a,b,target])=>({name,keyframes:[frame(0,a,target),frame(ticks,b,target)]}))};
const output=path.join(root,'media/edit/camera-paths-ground0.json');
fs.writeFileSync(output,JSON.stringify(presets,null,2)+'\n');
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const binding={schema:'fork-cinematic-camera-binding-1',createdUtc:new Date().toISOString(),status:'PRESETS AUTHORED - LIVE FRAMING UNRUN',origin:[46,0,22],dimension:'minecraft:overworld',humanFeet:[54.5,1,29.5],humanYaw:180,humanPitch:0,geography:'Market Street CBD 256m candidate, 1 block/metre, pending final visual acceptance',worldSha256:null,acceptedByMain:false,inputAuthority:'Main corrected-ground0 camera/anchor packet, 13 September 2026',cameraPathSourceSha256:hash(path.join(root,'src/client/java/dev/agaminggod/arenaagents/client/camera/CameraPath.java')),presetFile:'media/edit/camera-paths-ground0.json',presetSha256:hash(output),installation:'Main installs into the dedicated client profile config/arenaagents/camera-paths.json before launch; Cinematic does not write profile/world/mod',interpolation:'Existing two-endpoint Catmull-Rom position; smoothstep pitch and shortest-turn yaw; no new camera code',safety:{twoKeyframesAvoidIntermediateOvershoot:true,playerPositionUnchanged:true,teleportOrServerMutation:false,loadedChunksVerified:false,visualCollisionVerified:false,actualWorldBindingVerified:false,interiorWalkProof:false},shots:definitions.map(([name,ticks,a,b,target,purpose])=>({name,command:`/camera path play ${name}`,durationTicks:ticks,nominalSecondsAt20TPS:ticks/20,positionStart:a,positionEnd:b,lookAt:target,purpose,liveFraming:'UNRUN'})),stopCommand:'/camera path stop-playback',stopRules:'Stop before rewind, travel, world copy or scene replacement. Existing playback returns to the player at its end; a long frozen world tick can extend wall time.'};
fs.writeFileSync(path.join(root,'media/edit/camera-binding-ground0.json'),JSON.stringify(binding,null,2)+'\n');
console.log(JSON.stringify({presetFile:binding.presetFile,sha256:binding.presetSha256,paths:presets.paths.map(p=>p.name),liveFraming:'UNRUN'},null,2));
