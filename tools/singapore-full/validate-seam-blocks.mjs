import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {readRegion,sections,unpack} from '../fork-world/nbt-region.mjs';
const [leftPath,rightPath,out]=process.argv.slice(2);
if(!out)throw Error('Usage: validate-seam-blocks.mjs left-manifest right-manifest audit.json');
function plain(v){if(v&&typeof v==='object'&&'value'in v)return plain(v.value);if(Array.isArray(v))return v.map(plain);if(v&&typeof v==='object')return Object.fromEntries(Object.keys(v).sort().map(k=>[k,plain(v[k])]));return v;}
const AIR=JSON.stringify({Name:'minecraft:air'});
function load(p){
 const m=JSON.parse(fs.readFileSync(p)),world=path.resolve(path.dirname(p),m.output.worldPath),chunks=new Map(),metadataErrors=[];
 for(const f of m.output.files.filter(f=>/^region\/.*\.mca$/.test(f.path))){
  const match=f.path.match(/r\.(-?\d+)\.(-?\d+)\.mca$/),rx=Number(match[1]),rz=Number(match[2]);
  for(const c of readRegion(path.join(world,f.path))){
   const cx=c.root.value.xPos.value,cz=c.root.value.zPos.value,key=cx+','+cz;
   if(Math.floor(cx/32)!==rx||Math.floor(cz/32)!==rz)metadataErrors.push({kind:'chunk-region-coordinate',cx,cz,rx,rz});
   if(chunks.has(key))metadataErrors.push({kind:'duplicate-chunk',cx,cz});
   const map=new Map();for(const s of sections(c)){const u=unpack(s);if(u)map.set(s.Y.value,{pal:u.pal.map(p=>JSON.stringify(plain(p))),blocks:u.blocks});}
   chunks.set(key,map);
  }
 }
 return {m,metadataErrors,block(gx,y,gz){const x=gx-m.tile.renderOrigin[0],z=gz-m.tile.renderOrigin[1],c=chunks.get(Math.floor(x/16)+','+Math.floor(z/16));if(!c)throw Error('Missing sampled chunk '+x+','+z);const s=c.get(Math.floor(y/16));if(!s)return AIR;return s.pal[s.blocks[((y%16+16)%16)*256+((z%16+16)%16)*16+((x%16+16)%16)]];}};
}
const a=load(leftPath),b=load(rightPath);
if(JSON.stringify(plain(a.m.grid))!==JSON.stringify(plain(b.m.grid)))throw Error('Grid mismatch');
const x0=Math.max(a.m.tile.renderOrigin[0],b.m.tile.renderOrigin[0]),x1=Math.min(a.m.tile.renderOrigin[0]+a.m.tile.renderSize,b.m.tile.renderOrigin[0]+b.m.tile.renderSize),z0=Math.max(a.m.tile.renderOrigin[1],b.m.tile.renderOrigin[1]),z1=Math.min(a.m.tile.renderOrigin[1]+a.m.tile.renderSize,b.m.tile.renderOrigin[1]+b.m.tile.renderSize);
if(x0>=x1||z0>=z1)throw Error('Tiles have no shared rendered area');
let compared=0,mismatches=0,airVsNonAir=0,propertiesOnly=0;const byY={},pairs=new Map(),examples=[],hashA=crypto.createHash('sha256'),hashB=crypto.createHash('sha256'),nameCache=new Map();
const name=s=>{if(!nameCache.has(s))nameCache.set(s,JSON.parse(s).Name);return nameCache.get(s);};
for(let x=x0;x<x1;x++)for(let z=z0;z<z1;z++)for(let y=-64;y<=319;y++){
 const left=a.block(x,y,z),right=b.block(x,y,z);compared++;hashA.update(left+'\n');hashB.update(right+'\n');
 if(left!==right){mismatches++;byY[y]=(byY[y]??0)+1;const ln=name(left),rn=name(right),pair=ln+' => '+rn;pairs.set(pair,(pairs.get(pair)??0)+1);
 if((ln==='minecraft:air')!==(rn==='minecraft:air'))airVsNonAir++;if(ln===rn)propertiesOnly++;if(examples.length<20)examples.push({global:[x,y,z],left:JSON.parse(left),right:JSON.parse(right)});}
}
const metadataErrors=[...a.metadataErrors,...b.metadataErrors],report={status:mismatches||metadataErrors.length?'FAIL':'PASS',scope:'Canonical block-state comparison over full shared rendered halo and Minecraft Y -64..319; independent of pipeline seam comparator',left:a.m.id,right:b.m.id,region:{x:[x0,x1],z:[z0,z1],y:[-64,319]},compared,mismatches,airVsNonAir,propertiesOnly,metadataErrors,leftBlockStateSha256:hashA.digest('hex'),rightBlockStateSha256:hashB.digest('hex'),mostCommonDifferences:[...pairs.entries()].sort((a,b)=>b[1]-a[1]).slice(0,20),byY,examples,assemblyAccepted:false,createdUtc:new Date().toISOString()};fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({...report,byY:undefined,examples:undefined},null,2));if(report.status==='FAIL')process.exitCode=1;
