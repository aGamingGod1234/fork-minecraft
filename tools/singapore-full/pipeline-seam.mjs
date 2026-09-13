import fs from 'node:fs';
import path from 'node:path';
import {readRegion,sections,unpack} from '../fork-world/nbt-region.mjs';
const [leftPath,rightPath,outputPath]=process.argv.slice(2);
if(!leftPath||!rightPath||!outputPath)throw Error('Usage: pipeline-seam.mjs left-manifest right-manifest receipt.json');
function load(p){const m=JSON.parse(fs.readFileSync(p)),world=path.resolve(path.dirname(p),m.output.worldPath),map=new Map();
  for(const f of m.output.files.filter(f=>/^region\/.*\.mca$/.test(f.path)))for(const c of readRegion(path.join(world,f.path))){
    const sec=new Map();for(const s of sections(c)){const u=unpack(s);if(u)sec.set(s.Y.value,{pal:u.pal.map(p=>JSON.stringify(Object.fromEntries(Object.entries(p).map(([k,v])=>[k,v.value])))),blocks:u.blocks});}
    map.set(c.root.value.xPos.value+','+c.root.value.zPos.value,sec);
  }
  return {m,block(gx,y,gz){const x=gx-m.tile.renderOrigin[0],z=gz-m.tile.renderOrigin[1],c=map.get(Math.floor(x/16)+','+Math.floor(z/16));if(!c)throw Error('Missing sampled chunk '+x+','+z);const s=c.get(Math.floor(y/16));if(!s)return '{"Name":"minecraft:air"}';return s.pal[s.blocks[((y%16+16)%16)*256+((z%16+16)%16)*16+((x%16+16)%16)]];}};
}
const a=load(leftPath),b=load(rightPath);
if(JSON.stringify(a.m.grid)!==JSON.stringify(b.m.grid))throw Error('Grid mismatch');
const x0=Math.max(a.m.tile.renderOrigin[0],b.m.tile.renderOrigin[0]),x1=Math.min(a.m.tile.renderOrigin[0]+a.m.tile.renderSize,b.m.tile.renderOrigin[0]+b.m.tile.renderSize);
const z0=Math.max(a.m.tile.coreOrigin[1],b.m.tile.coreOrigin[1]),z1=Math.min(a.m.tile.coreOrigin[1]+a.m.tile.coreSize,b.m.tile.coreOrigin[1]+b.m.tile.coreSize);
let compared=0,mismatches=0,coreBoundaryMismatches=0,occupancyMismatches=0;const examples=[],byY={},byX={};
const seamX=b.m.tile.coreOrigin[0];
for(let x=x0;x<x1;x++)for(let z=z0;z<z1;z++)for(let y=-4;y<=319;y++){
 const left=a.block(x,y,z),right=b.block(x,y,z);compared++;if(left!==right){mismatches++;byY[y]=(byY[y]??0)+1;byX[x]=(byX[x]??0)+1;if(x>=seamX-8&&x<seamX+8)coreBoundaryMismatches++;if(left.includes('minecraft:air')!==right.includes('minecraft:air'))occupancyMismatches++;if(examples.length<20)examples.push({global:[x,y,z],left,right});}
}
const receipt={status:mismatches?'FAIL':'PASS',kind:'actual-shared-halo-block-comparison',left:a.m.id,right:b.m.id,region:{x:[x0,x1],z:[z0,z1],y:[-4,319]},compared,mismatches,coreBoundaryMismatches,occupancyMismatches,byY,byX,examples,assemblyAccepted:mismatches===0,createdUtc:new Date().toISOString()};
fs.writeFileSync(outputPath,JSON.stringify(receipt,null,2));console.log(JSON.stringify({status:receipt.status,compared,mismatches,coreBoundaryMismatches,occupancyMismatches,outputPath}));if(mismatches)process.exitCode=1;
