import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';

// One shared metric grid. This first pass preserves mapped geometry; it does
// not claim surveyed terrain, interiors or photograph-matched facades.
export const GRID = Object.freeze({kind:'EPSG:3414',originEasting:0,originNorthing:60000,blocksPerMeter:1,xDirection:'east',zDirection:'south',definition:'+proj=tmerc +lat_0=1.366666666666667 +lon_0=103.83333333333333 +k=1 +x_0=28001.642 +y_0=38744.572 +ellps=WGS84 +units=m +no_defs'});
const require=createRequire(import.meta.url);
const proj4=require(path.join(process.env.LOCALAPPDATA,'FORK-Tools/fork-singapore-full/pipeline-tooling/node_modules/proj4'));
const transformer=proj4('EPSG:4326',GRID.definition);
const RAD=Math.PI/180, ARNIS_RADIUS=6371000, MZ=ARNIS_RADIUS*RAD;
export const project=(lon,lat)=>{const [e,n]=transformer.forward([lon,lat]);return [e-GRID.originEasting,GRID.originNorthing-n];};
export const inverse=(x,z)=>transformer.inverse([GRID.originEasting+x,GRID.originNorthing-z]);
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const save=(p,v)=>{fs.writeFileSync(p+'.tmp',JSON.stringify(v,null,2)+'\n');fs.renameSync(p+'.tmp',p);};
const inside=(p,r)=>p===r||p.startsWith(r+path.sep);
function parse(argv){const o={};for(let i=0;i<argv.length;i++){const a=argv[i];if(a==='--run'){o.run=true;continue;}if(!a.startsWith('--')||argv[i+1]===undefined)throw Error('Expected --name value');o[a.slice(2)]=argv[++i];}return o;}
function boundedInteger(v,fallback,min,max){const n=Number(v??fallback);if(!Number.isSafeInteger(n)||n<min||n>max)throw Error('Invalid bounded integer '+v);return n;}

export function selectElements(data,bounds){
  if(!Array.isArray(data.elements))throw Error('Expected reference-complete Overpass JSON elements');
  const nodes=new Map(data.elements.filter(e=>e.type==='node').map(e=>[e.id,e]));
  const ways=new Map(data.elements.filter(e=>e.type==='way').map(e=>[e.id,e]));
  const chosen=new Set(), wantedNodes=new Set();
  const [south,west,north,east]=bounds;
  const overlaps=points=>points.length&&Math.min(...points.map(p=>p.lon))<=east&&Math.max(...points.map(p=>p.lon))>=west&&Math.min(...points.map(p=>p.lat))<=north&&Math.max(...points.map(p=>p.lat))>=south;
  for(const w of ways.values()){
    const points=w.nodes.map(id=>nodes.get(id));
    if(points.some(p=>!p))throw Error('Incomplete way '+w.id);
    if(overlaps(points))chosen.add('way/'+w.id);
  }
  const omittedRelations=[];
  for(const r of data.elements.filter(e=>e.type==='relation')){
    if(!r.members?.some(m=>chosen.has(m.type+'/'+m.ref)))continue;
    if(!['multipolygon','building'].includes(r.tags?.type)){omittedRelations.push({id:r.id,reason:'non-rendered relation type'});continue;}
    if(r.members.some(m=>m.type!=='way'||!ways.has(m.ref))){omittedRelations.push({id:r.id,reason:'incomplete or nested relation; requires later source repair'});continue;}
    chosen.add('relation/'+r.id);for(const m of r.members)chosen.add('way/'+m.ref);
  }
  for(const w of ways.values())if(chosen.has('way/'+w.id))for(const id of w.nodes)wantedNodes.add(id);
  for(const n of nodes.values())if(wantedNodes.has(n.id)||(n.lon>=west&&n.lon<=east&&n.lat>=south&&n.lat<=north))chosen.add('node/'+n.id);
  return {elements:data.elements.filter(e=>chosen.has(e.type+'/'+e.id)),omittedRelations};
}

// Arnis local mode floors the geographic span, then truncates interpolated
// positions. Normalize the adapter bbox to a span whose floor is EXACTLY the
// desired integer size, and quantize once in the global grid. Independent
// tile origins can therefore never change a shared source node's block.
export function adapter(renderOrigin,renderSize){
  const [lon,lat]=inverse(...renderOrigin), span=renderSize+0.5;
  const south=lat-span/MZ,north=lat;
  const mean=(north+south)/2*RAD;
  const dlon=2*Math.asin(Math.sin(span/(2*ARNIS_RADIUS))/Math.cos(mean))/RAD;
  const west=lon,east=lon+dlon;
  return {bbox:[south,west,north,east],map(n){
    const [gx,gz]=project(n.lon,n.lat).map(Math.round),x=gx-renderOrigin[0],z=gz-renderOrigin[1];
    const sx=x+(x>=0?0.25:-0.25),sz=z+(z>=0?0.25:-0.25);
    return {...n,lon:west+sx/renderSize*(east-west),lat:north-sz/renderSize*(north-south)};
  },local(n){return [Math.trunc((n.lon-west)/(east-west)*renderSize),Math.trunc((north-n.lat)/(north-south)*renderSize)];}};
}
function listFiles(root,at=root){return fs.readdirSync(at,{withFileTypes:true}).flatMap(e=>{const p=path.join(at,e.name);return e.isDirectory()?listFiles(root,p):[{path:path.relative(root,p).replaceAll('\\','/'),bytes:fs.statSync(p).size,sha256:sha(p)}];});}
function chunks(root){let count=0,regions=0;for(const p of listFiles(root).filter(x=>/^region\/r\.-?\d+\.-?\d+\.mca$/.test(x.path))){regions++;const f=fs.openSync(path.join(root,p.path),'r'),b=Buffer.alloc(4096);fs.readSync(f,b,0,4096,0);fs.closeSync(f);for(let i=0;i<1024;i++)if(b.readUInt32BE(i*4)>>>8)count++;}return {regionCount:regions,chunkCount:count};}

export async function main(argv){
  const o=parse(argv),size=boundedInteger(o.size,1024,32,2048),halo=boundedInteger(o.halo,128,16,256);
  if(size%16||halo%16)throw Error('Core and halo must align to 16-block chunks');
  const x=boundedInteger(o.x,0,-100000,100000),z=boundedInteger(o.z,0,-100000,100000);
  if(x%16||z%16)throw Error('Global origin must be chunk aligned');
  const maxSeconds=boundedInteger(o['max-seconds'],300,10,1800);
  const allowed=path.resolve(process.env.LOCALAPPDATA??'.','FORK-Tools/fork-singapore-full/tiles');
  fs.mkdirSync(allowed,{recursive:true});const root=fs.realpathSync(allowed);
  if(o.root&&fs.realpathSync(o.root)!==root)throw Error('Only the isolated full-Singapore tiles root is permitted');
  const id=`svy21_x${x}_z${z}_s${size}_h${halo}`,sourcePath=path.resolve(o.source??'');
  if(!o.source||!fs.statSync(sourcePath).isFile())throw Error('--source reference-complete JSON required');
  const sourceHash=sha(sourcePath),exe=path.resolve(process.env.LOCALAPPDATA,'FORK-Tools/arnis-3.2.0/arnis.exe');
  const exeHash=sha(exe);if(exeHash!=='79ccb74c9c0af38b025a24678d301cb4f7036dd49784e8d35bb0d5b4c15a32d6')throw Error('Unreviewed Arnis executable');
  const generationSettings={grid:GRID,sourceHash,exeHash,pipelineSha256:sha(fileURLToPath(import.meta.url)),terrain:'geo-only-ground0',threads:1};
  const generationRevision=crypto.createHash('sha256').update(JSON.stringify(generationSettings)).digest('hex');
  const configurationHash=crypto.createHash('sha256').update(JSON.stringify({...generationSettings,x,z,size,halo})).digest('hex');
  const dir=path.join(root,'revisions',generationRevision.slice(0,20),id);if(!inside(dir,root))throw Error('Escaped tiles root');
  fs.mkdirSync(dir,{recursive:true});if(fs.realpathSync(dir)!==dir)throw Error('Tile junctions are not permitted');
  const manifestPath=path.join(dir,'tile-manifest.json');
  if(fs.existsSync(manifestPath)){
    const old=JSON.parse(fs.readFileSync(manifestPath));
    if(old.source.sha256!==sourceHash)throw Error('Source changed; use a new versioned tile root/design');
    if(old.configurationHash!==configurationHash)throw Error('Pipeline or generation configuration changed; previous tile cannot be resumed');
    if(old.status==='generated'){
      for(const f of old.output.files)if(sha(path.join(dir,old.output.worldPath,f.path))!==f.sha256)throw Error('Generated output hash changed');
      console.log(JSON.stringify({status:'resumed-verified',manifestPath}));return;
    }
  }
  const lock=path.join(root,'.pipeline.lock');let lockfd;try{lockfd=fs.openSync(lock,'wx');}catch{throw Error('Another generator owns the single-worker lease; inspect lock before recovery');}
  fs.writeSync(lockfd,JSON.stringify({pid:process.pid,startedUtc:new Date().toISOString(),id}));
  try{
    const origin=[x-halo,z-halo],renderSize=size+2*halo;
    const corners=[[0,0],[renderSize,0],[0,renderSize],[renderSize,renderSize]].map(([dx,dz])=>inverse(origin[0]+dx,origin[1]+dz));
    const west=Math.min(...corners.map(p=>p[0])),east=Math.max(...corners.map(p=>p[0])),north=Math.max(...corners.map(p=>p[1])),south=Math.min(...corners.map(p=>p[1])),bounds=[south,west,north,east];
    const selected=selectElements(JSON.parse(fs.readFileSync(sourcePath,'utf8')),bounds),a=adapter(origin,renderSize);
    if(!selected.elements.length)throw Error('No source objects intersect this tile');
    const original={version:0.6,generator:'FORK source subset; coordinates unmodified',elements:selected.elements};
    const adapted={version:0.6,generator:'FORK global-grid adapter for Arnis local projection',elements:selected.elements.map(n=>n.type==='node'?a.map(n):n)};
    const nodeSamples=selected.elements.filter(n=>n.type==='node');
    for(const n of nodeSamples){const actual=a.local(a.map(n)),expected=project(n.lon,n.lat).map(Math.round).map((v,i)=>v-origin[i]);if(actual.some((v,i)=>v!==expected[i]))throw Error('Coordinate adapter mismatch '+n.id);}
    const controlPoints=[...[[west,north],[east,north],[west,south],[east,south]].map(([lon,lat])=>({lon,lat})),...nodeSamples.filter((_,i)=>i%Math.max(1,Math.floor(nodeSamples.length/12))===0).slice(0,12)].map(n=>{const [globalX,globalZ]=project(n.lon,n.lat).map(Math.round);return {sourceLon:n.lon,sourceLat:n.lat,globalX,globalZ,renderX:globalX-origin[0],renderZ:globalZ-origin[1]};});
    const attempt='attempt-'+new Date().toISOString().replace(/[:.]/g,'-'),work=path.join(dir,attempt),world=path.join(work,'world');
    fs.mkdirSync(work);fs.mkdirSync(world);save(path.join(work,'source.json'),original);save(path.join(work,'render.json'),adapted);
    const args=['--body','earth','--mode','geo-only','--projection','local','--scale','1','--rotation','0','--ground-level','0','--overture','false','--canopy-height','false','--no-3d','--legacy-trees','--mapillary-facades','false','--signage','none','--map-item','false','--interior','false','--world-time','6000','--bbox',a.bbox.join(','),'--file',path.join(work,'render.json'),'--output-dir',world];
    const manifest={schemaVersion:1,id,generationRevision,configurationHash,status:o.run?'running':'prepared',startedUtc:new Date().toISOString(),source:{path:sourcePath,bytes:fs.statSync(sourcePath).size,sha256:sourceHash,subsetPath:attempt+'/source.json',subsetSha256:sha(path.join(work,'source.json')),license:'ODbL-1.0',attribution:'OpenStreetMap contributors',counts:Object.fromEntries(['node','way','relation'].map(t=>[t,selected.elements.filter(e=>e.type===t).length])),nodeSampleCount:nodeSamples.length,omittedRelations:selected.omittedRelations},grid:GRID,tile:{coreOrigin:[x,z],coreSize:size,halo,renderOrigin:origin,renderSize,boundsWgs84:bounds},vertical:{groundY:0,mode:'flat-provisional',surveyed:false},generator:{version:'3.2.0',sha256:exeHash,args,threads:1,maxSeconds,landCover:'Arnis ESA WorldCover; ancillary cache not yet frozen'},mapping:{adapter:'global-grid-to-Arnis-local',sourceCoordinatesPreservedInRaw:true,renderSourcePath:attempt+'/render.json',renderSourceSha256:sha(path.join(work,'render.json')),renderBbox:a.bbox,quantization:'nearest global block; <=0.5 block per axis',controlPoints},qualityGates:{coordinateAgreement:true,actualTerrain:false,facadeMatch:false,assembly:false},output:{worldPath:attempt+'/world',coordinateFrame:'Arnis-local; add tile.renderOrigin for future assembly',files:[],regionCount:0,chunkCount:0}};
    save(manifestPath,manifest);console.log(JSON.stringify({status:manifest.status,manifestPath,counts:manifest.source.counts}));if(!o.run)return;
    const out=fs.openSync(path.join(work,'stdout.log'),'wx'),err=fs.openSync(path.join(work,'stderr.log'),'wx');
    const child=spawn(exe,args,{cwd:work,windowsHide:true,stdio:['ignore',out,err],env:{...process.env,RAYON_NUM_THREADS:'1',OMP_NUM_THREADS:'1'}});
    manifest.generator.pid=child.pid;save(manifestPath,manifest);
    let timeout=false;const timer=setTimeout(()=>{timeout=true;child.kill();},maxSeconds*1000);
    const result=await new Promise(resolve=>{child.once('error',e=>resolve({code:null,error:String(e)}));child.once('close',(code,signal)=>resolve({code,signal}));});clearTimeout(timer);fs.closeSync(out);fs.closeSync(err);
    manifest.completedUtc=new Date().toISOString();manifest.generator.result={...result,timeout};
    const worlds=fs.readdirSync(world,{withFileTypes:true}).filter(e=>e.isDirectory()&&fs.existsSync(path.join(world,e.name,'level.dat')));
    const actualWorld=fs.existsSync(path.join(world,'level.dat'))?world:worlds.length===1?path.join(world,worlds[0].name):world;
    manifest.output={...manifest.output,worldPath:path.relative(dir,actualWorld).replaceAll('\\','/'),files:listFiles(actualWorld),...chunks(actualWorld)};
    manifest.status=result.code===0&&!timeout&&manifest.output.chunkCount>0?'generated':'failed';
    save(manifestPath,manifest);console.log(JSON.stringify({status:manifest.status,manifestPath,regionCount:manifest.output.regionCount,chunkCount:manifest.output.chunkCount}));
    if(manifest.status!=='generated')throw Error('Tile generation failed; logs and partial output preserved');
  }finally{fs.closeSync(lockfd);fs.unlinkSync(lock);}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main(process.argv.slice(2)).catch(e=>{console.error(e.stack);process.exitCode=1;});
