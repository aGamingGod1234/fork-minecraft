import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {block,height} from './read-camera-terrain.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const r=n=>Math.round(n*1e6)/1e6;
const aimed=(tick,p,t)=>({tick,x:p[0],y:p[1],z:p[2],yaw:r(Math.atan2(p[0]-t[0],t[2]-p[2])*180/Math.PI),pitch:r(-Math.atan2(t[1]-p[1],Math.hypot(t[0]-p[0],t[2]-p[2]))*180/Math.PI)});
const inputs=[
 [0,[44.5,7,40.5],[54.5,0.5,30]],
 [40,[46,7.5,40.5],[54.5,0.5,30]],
 [100,[54.5,10,40.5],[75,18,32]],
 [160,[63.5,24,40.5],[110,65,30]],
 [240,[63.5,65,40.5],[128,110,30]]
];
const frames=inputs.map(v=>aimed(...v));
const cat=(p0,p1,p2,p3,t)=>.5*(2*p1+(-p0+p2)*t+(2*p0-5*p1+4*p2-p3)*t*t+(-p0+3*p1-3*p2+p3)*t*t*t);
const collisions=[],samples=[];
for(let tick=0;tick<=240;tick+=.5){let j=1;while(j<frames.length-1&&frames[j].tick<tick)j++;const a=frames[j-1],b=frames[j],before=frames[Math.max(0,j-2)],after=frames[Math.min(frames.length-1,j+1)],t=(tick-a.tick)/(b.tick-a.tick);const p=Object.fromEntries(['x','y','z'].map(k=>[k,cat(before[k],a[k],b[k],after[k],t)]));const hit=[];for(const dx of [-.3,0,.3])for(const dy of [-.3,0,.3])for(const dz of [-.3,0,.3]){const name=block(Math.floor(p.x+dx),Math.floor(p.y+dy),Math.floor(p.z+dz));if(!/:(air|cave_air|void_air)$/.test(name))hit.push(name);}if(hit.length)collisions.push({tick,...p,blocks:[...new Set(hit)]});if(Number.isInteger(tick)&&tick%20===0)samples.push({tick,...p,ground:height(Math.floor(p.x),Math.floor(p.z))});}
const presets=JSON.parse(fs.readFileSync(path.join(root,'media/edit/camera-paths-ground0.json')));
presets.paths=presets.paths.filter(p=>!['fork_intro','fork_overview'].includes(p.name));
const openingTicks=[0,40,70,100,140];
presets.paths.unshift({name:'fork_intro',keyframes:frames.map((f,i)=>({...f,tick:openingTicks[i]}))},{name:'fork_city_crane',keyframes:frames},{name:'fork_overview',keyframes:[aimed(0,[63.5,65,40.5],[128,110,30]),aimed(160,[63.5,65,40.5],[128,110,30])]});
fs.writeFileSync(path.join(root,'media/edit/camera-paths-city-v2.json'),JSON.stringify(presets,null,2)+'\n');
const evidence={schema:'fork-opening-crane-2',utc:new Date().toISOString(),preset:'media/edit/camera-paths-city-v2.json',start:inputs[0][1],end:inputs.at(-1)[1],riseMetres:58,horizontalDisplacementMetres:19,durationSecondsAt20TPS:{fork_intro:7,fork_city_crane:12,fork_overview:8},firstTwoSeconds:'Road fork framing target [54.5,0.5,30]; first camera point supplied by World. Actual framing UNRUN',interpolation:'Existing Catmull-Rom; full crane sampled every half world tick with0.3m camera clearance box. Fast opening uses identical curve points and different tick spacing.',collisionCount:collisions.length,collisions:collisions.slice(0,12),samples,source:'Immutable preview world1151 occupancy plus World visual-camera-evidence.json eastward corridor. New court framing unrun; facade material changes retain occupied cells. Rejected diagonal through [77,41.9,63] excluded.',liveFraming:'UNRUN',loadedChunks:'UNRUN; maximum camera distance from human approximately71m; keep12-16 render chunks as requested by Main',edgeAndVoid:'UNRUN visually; camera points remain inside0..255 and face into generated district',cleanCamera:'Depends on Gameplay clean HUD/hand feature; retain actual mode in scored footage',countryTransition:'Hard cut or short graphic match transition after city shot to attributed locator; never claim island is built'};
fs.writeFileSync(path.join(root,'.work/fork/cinematic/city-crane-checks.json'),JSON.stringify(evidence,null,2));
console.log(JSON.stringify({collisionCount:collisions.length,firstCollisions:collisions.slice(0,5),riseMetres:58,samples},null,2));
if(collisions.length)process.exitCode=1;

