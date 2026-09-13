import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const p=JSON.parse(fs.readFileSync(path.join(root,'media/edit/camera-paths-ground0.json')));
const names=['fork_intro','fork_clinic','fork_workshop','fork_courier','fork_overview','fork_comparison'];
if(p.paths.length!==6 || p.paths.some((v,i)=>v.name!==names[i]))throw Error('Expected six exact preset names');
const report=[];
for(const preset of p.paths){
  if(preset.keyframes.length!==2 || preset.keyframes[0].tick!==0 || preset.keyframes[1].tick<=0)throw Error('Two sorted endpoint keyframes required');
  const [a,b]=preset.keyframes;
  for(const f of preset.keyframes){
    if(Object.keys(f).sort().join()!=='pitch,tick,x,y,yaw,z')throw Error('Wrong CameraDirector schema');
    if(!Object.values(f).every(Number.isFinite)||!Number.isInteger(f.tick)||Math.abs(f.pitch)>90)throw Error('Invalid camera values');
    if(f.x<46||f.x>62||f.y<1||f.y>15||f.z<22||f.z>38)throw Error('Camera outside supplied court bounds');
  }
  const displacement=Math.hypot(a.x-b.x,a.y-b.y,a.z-b.z);
  if(displacement>0.8)throw Error('Preset motion exceeds gentle 0.8-metre bound');
  if(preset.name==='fork_comparison' && (displacement!==0||a.yaw!==b.yaw||a.pitch!==b.pitch||b.tick<240))throw Error('Comparison must hold still for at least 12 seconds');
  if(preset.name!=='fork_comparison' && (b.tick<60||b.tick>160))throw Error('Moving preset must be 3 to 8 seconds');
  report.push({name:preset.name,secondsAt20TPS:b.tick/20,displacementMetres:displacement,maxDistanceFromHumanMetres:Math.max(...preset.keyframes.map(f=>Math.hypot(f.x-54.5,f.y-1,f.z-29.5)))});
}
const evidence={utc:new Date().toISOString(),passed:true,checks:'Schema, finite angles, exact names, corrected court bounds, motion limit, stationary comparison',presets:report,liveCollisionFramingAndLoadedChunks:'UNRUN',buildsRunOnLaptop:0};
fs.writeFileSync(path.join(root,'.work/fork/cinematic/camera-static-checks.json'),JSON.stringify(evidence,null,2));
console.log(JSON.stringify(evidence,null,2));
