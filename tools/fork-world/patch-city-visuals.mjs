import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import assert from 'node:assert/strict';
import {readRegion,writeRegion,sections,unpack,repack} from './nbt-region.mjs';
const source='assets/fork-world/artifacts/compact-ground0/world-output/Arnis World 1';
const target='assets/fork-world/artifacts/visual-1210/world-output/FORK Market Street Visual';
const artifacts='assets/fork-world/artifacts/visual-1210';
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
if(fs.existsSync(target))throw Error('Fresh stopped copy only. Never modify an installed or previously patched world.');
const sourceRegion=source+'/region/r.0.0.mca',sourceHash=hash(sourceRegion);
const expected=JSON.parse(fs.readFileSync('data/fork-world/compact-ground0-result.json')).structure.files.find(f=>f.path.endsWith('region/r.0.0.mca')).sha256;
assert.equal(sourceHash,expected,'Pinned immutable ground0 world');
const chunks=readRegion(sourceRegion),lookup=new Map();
for(const c of chunks)for(const s of sections(c)){const a=unpack(s);if(a)lookup.set([c.root.value.xPos.value,s.Y.value,c.root.value.zPos.value].join(','),{...a,s,c,dirty:false});}
const at=(x,y,z)=>{const a=lookup.get([Math.floor(x/16),Math.floor(y/16),Math.floor(z/16)].join(','));return a?{a,i:((y%16+16)%16)*256+((z%16+16)%16)*16+(x%16+16)%16}:null;};
const get=(x,y,z)=>{const p=at(x,y,z);return p?p.a.pal[p.a.blocks[p.i]].Name.value:'minecraft:air';};
const isAir=n=>['minecraft:air','minecraft:cave_air','minecraft:void_air'].includes(n);
const changes=[],protectedCells=new Set(JSON.parse(fs.readFileSync('data/fork-world/court-geographic-ground0.json')).outsideSentinels.map(([x,y,z])=>[46+x,y,22+z].join(',')));
function put(x,y,z,name,reason){assert(x>=0&&x<256&&z>=0&&z<256);assert(!(x>=46&&x<62&&z>=22&&z<38&&y>=0&&y<16),'Static patch entered court');assert(!protectedCells.has([x,y,z].join(',')),'Outside sentinel');const p=at(x,y,z);assert(p);const before=get(x,y,z),after='minecraft:'+name;if(before===after)return;assert(!isAir(before)&&!isAir(after),'Material-only patch preserves occupancy and source heights');let idx=p.a.pal.findIndex(v=>v.Name.value===after&&!v.Properties);if(idx<0){idx=p.a.pal.length;p.a.pal.push({Name:{type:8,value:after}});}p.a.blocks[p.i]=idx;p.a.dirty=true;p.a.c.dirty=true;changes.push({x,y,z,before,after,reason});}
const features=JSON.parse(fs.readFileSync('data/fork-world/compact-ground0-result.json')).features;
function inside(x,z,poly){let yes=false;for(let i=0,j=poly.length-1;i<poly.length;j=i++){const [xi,zi]=poly[i],[xj,zj]=poly[j];if(((zi>z)!==(zj>z))&&(x<(xj-xi)*(z-zi)/(zj-zi)+xi))yes=!yes;}return yes;}
function nearFootprint(x,z,poly){for(let dx=-1;dx<=1;dx++)for(let dz=-1;dz<=1;dz++)if(inside(x+dx,z+dz,poly))return true;return false;}
const spring=features.find(f=>f.osmWay===1157548672).projectedFootprint,green=features.find(f=>f.osmWay===393100853).projectedFootprint;
// Replace existing wall materials only. No newly occupied column or block, moved footprint,
// invented upper floor, geometry expansion, or new roof height is possible through put().
for(const [poly,id,maxY] of [[spring,'capitaspring',280],[green,'capitagreen',242]]){
 const xmin=Math.max(0,Math.min(...poly.map(p=>p[0]))-1),xmax=Math.min(255,Math.max(...poly.map(p=>p[0]))+1),zmin=Math.max(0,Math.min(...poly.map(p=>p[1]))-1),zmax=Math.min(255,Math.max(...poly.map(p=>p[1]))+1);
 for(let x=xmin;x<=xmax;x++)for(let z=zmin;z<=zmax;z++){
  if(!nearFootprint(x,z,poly))continue;
  for(let y=20;y<=maxY;y++){
   const n=get(x,y,z);if(isAir(n))continue;
   const wall=[[x-1,z],[x+1,z],[x,z-1],[x,z+1]].some(([a,b])=>isAir(get(a,y,b)));
   if(!wall)continue;
   if(!/glass|concrete|andesite|terracotta|mud_bricks|stone_bricks/.test(n))continue;
   const u=Math.round(x*0.866+z*0.5);
   if(id==='capitaspring'){
    // Owner confirms aluminium fins, Green Oasis at 100 m spanning 35 m, roof garden.
    const oasis=y>=100&&y<135;
    const wave=oasis?Math.round(2*Math.sin((y-100)/35*Math.PI)):0;
    const fin=((u+wave)%5+5)%5===0;
    put(x,y,z,fin?'smooth_quartz':oasis?((y%9<3)?'green_terracotta':'green_stained_glass'):'light_gray_stained_glass',id+(oasis?'-green-oasis': '-aluminium-fins'));
   }else{
    // Only the in-bounds part is dressed. The clipped crown is deliberately not invented.
    const pocket=(u%11+11)%11<3&&y%30>=7&&y%30<=23;
    put(x,y,z,pocket?'green_terracotta':(u%7===0?'smooth_quartz':'light_gray_stained_glass'),id+'-glass-and-vertical-planting');
   }
  }
  // A roof garden surface within the EXISTING top roof; no new roof silhouette.
  if(id==='capitaspring')for(let y=279;y<=282;y++){const n=get(x,y,z);if(/grass_block|green_concrete|green_terracotta/.test(n)&&isAir(get(x,y+1,z)))put(x,y,z,(x+z)%7<2?'polished_andesite':'moss_block','capitaspring-existing-roof-garden-surface');}
 }
}
// Find an existing paved road edge from the court's east pavement. The connection is a
// designed access path, explicitly not claimed to be an OSM-mapped footway.
const start=[62,29],queue=[start],parents=new Map([[start.join(','),null]]);let end;
for(let qi=0;qi<queue.length;qi++){const [x,z]=queue[qi];const n=get(x,0,z);if(/concrete|stone|andesite|gravel/.test(n)&&qi>0){end=[x,z];break;}for(const [a,b] of [[x+1,z],[x,z+1],[x,z-1],[x-1,z]]){const k=[a,b].join(',');if(a<62||a>90||b<20||b>45||parents.has(k)||!isAir(get(a,1,b))||!isAir(get(a,2,b))||!isAir(get(a,3,b)))continue;if(!/grass_block|dirt|concrete|stone|andesite|gravel/.test(get(a,0,b)))continue;parents.set(k,[x,z]);queue.push([a,b]);}}
assert(end,'No verified connection to existing paving');const route=[];for(let p=end;p;p=parents.get(p.join(',')))route.push(p);route.reverse();
for(const [x,z] of route)for(let dz=-1;dz<=1;dz++){if(protectedCells.has([x,0,z+dz].join(',')))continue;if(/grass_block|dirt/.test(get(x,0,z+dz))&&[1,2,3].every(y=>isAir(get(x,y,z+dz))))put(x,0,z+dz,dz===0?'smooth_stone':'stone_bricks','designed-court-access-to-existing-paving');}
for(const a of lookup.values())if(a.dirty){repack(a.s,a.pal,a.blocks);a.c.root.value.isLightOn={type:1,value:0};for(const s of sections(a.c)){delete s.BlockLight;delete s.SkyLight;}}
fs.mkdirSync(path.dirname(target),{recursive:true});fs.cpSync(source,target,{recursive:true,errorOnExist:true,force:false});writeRegion(target+'/region/r.0.0.mca',chunks);
fs.copyFileSync('data/fork-world/court-market-street-v2.json',target+'/fork-court.json');
assert.equal(hash(sourceRegion),sourceHash,'Original region mutated');
const destination={id:'market-street-view',label:'Market Street district / CapitaSpring view',feet:[end[0]+0.5,1,end[1]+0.5],lookAt:[128,110,30],evidence:'Solid existing pavement at Y0; three air blocks above, verified in patched NBT. Main must validate loaded safe arrival in26.1.2.',travelState:'Human-only at round0/6; preserve scenario; Return remains available'};
const manifest={schema:'fork-visual-patch-1',createdUtc:new Date().toISOString(),sourceWorld:source,sourceRegionSha256:sourceHash,targetWorld:target,targetRegionSha256:hash(target+'/region/r.0.0.mca'),courtFile:'data/fork-world/court-market-street-v2.json',courtSha256:hash('data/fork-world/court-market-street-v2.json'),courtOrigin:[46,0,22],bbox:[0,0,255,255],geometryPolicy:'Every changed cell was nonair before and after. Occupancy, footprints, all column top heights and source geometry remain unchanged. Material interpretation is approximate.',lightingPolicy:'Changed chunks marked isLightOn=false and cached lighting removed; Main upgrade/reopen must relight.',staticPatchExcludesCourt:true,outsideSentinelsPreserved:true,route,destination,changes,limitations:['Partial buildings at bbox edges; no region expansion','No authentic shop floorplan; designed gameplay frontage','CapitaGreen crown clipped by coverage and not reconstructed','Facade colours, fin pitch and planting shapes are approximations guided by references; no exact photographic match claimed','No shader or skin package in World scope','Installed session and LiveR1 unchanged; NEW session INITIAL required']};
fs.writeFileSync(artifacts+'/patch-manifest.json',JSON.stringify(manifest,null,2)+'\n');fs.writeFileSync('data/fork-world/city-view-destination.json',JSON.stringify(destination,null,2)+'\n');
console.log(JSON.stringify({target,changes:changes.length,chunks:chunks.filter(c=>c.dirty).length,route,destination}));
