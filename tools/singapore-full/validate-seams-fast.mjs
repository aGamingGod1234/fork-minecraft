import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import {readRegion,sections,unpack} from '../fork-world/nbt-region.mjs';
const [file,out]=process.argv.slice(2);if(!out)throw Error('Usage: validate-seams-fast.mjs pipeline-result.json audit.json');
const root=path.dirname(path.resolve(file)),pipeline=JSON.parse(fs.readFileSync(file)),hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
function plain(v){if(v&&typeof v==='object'&&'value'in v)return plain(v.value);if(Array.isArray(v))return v.map(plain);if(v&&typeof v==='object')return Object.fromEntries(Object.keys(v).sort().map(k=>[k,plain(v[k])]));return v;}
const air=JSON.stringify({Name:'minecraft:air'}),metadataErrors=[],hashErrors=[],inputs=[];
function load(t){
 const world=path.resolve(root,t.worldPath),mp=path.resolve(root,t.writerManifestPath),m=JSON.parse(fs.readFileSync(mp)),chunks=new Map();inputs.push({tile:t.id,manifestSha256:hash(mp),runsSha256:hash(path.resolve(root,t.runsPath))});
 if(inputs.at(-1).runsSha256!==t.runs.sha256)hashErrors.push(t.id+' run hash');
 for(const f of m.outputs){const p=path.join(world,f.path);if(fs.statSync(p).size!==f.bytes||hash(p)!==f.sha256)hashErrors.push(t.id+'/'+f.path);}
 for(const f of m.outputs.filter(f=>f.path.startsWith((m.regionDirectory??'region')+'/')&&/\.mca$/.test(f.path))){
 const match=f.path.match(/r\.(-?\d+)\.(-?\d+)\.mca$/),rx=+match[1],rz=+match[2];
 for(const c of readRegion(path.join(world,f.path))){const cx=c.root.value.xPos.value,cz=c.root.value.zPos.value,key=cx+','+cz;
 if(Math.floor(cx/32)!==rx||Math.floor(cz/32)!==rz||c.slot!==((cz%32+32)%32)*32+((cx%32+32)%32))metadataErrors.push({tile:t.id,cx,cz,region:[rx,rz],slot:c.slot});if(chunks.has(key))metadataErrors.push({tile:t.id,duplicateChunk:key});
 const ss=new Map();for(const s of sections(c)){const u=unpack(s);if(u&&(u.pal.length>4096||u.blocks.some(v=>v>=u.pal.length)))throw Error('Invalid section palette/index');if(u)ss.set(s.Y.value,{pal:u.pal.map(p=>JSON.stringify(plain(p))),blocks:u.blocks});}chunks.set(key,ss);}
 }
 return {t,column(x,z){
 const c=chunks.get(Math.floor(x/16)+','+Math.floor(z/16));if(!c)throw Error('Missing actual shared global chunk');
 const spans=[],append=(lo,hi,state)=>{const last=spans.at(-1);if(last&&last[2]===state&&last[1]===lo)last[1]=hi;else spans.push([lo,hi,state]);};
 for(let sy=-4;sy<20;sy++){const s=c.get(sy),lo=sy*16;if(!s){append(lo,lo+16,air);continue;}if(s.pal.length===1){append(lo,lo+16,s.pal[0]);continue;}
 for(let ly=0;ly<16;ly++)append(lo+ly,lo+ly+1,s.pal[s.blocks[ly*256+((z%16+16)%16)*16+((x%16+16)%16)]]);}
 return spans;},block(x,y,z){const c=chunks.get(Math.floor(x/16)+','+Math.floor(z/16));if(!c)throw Error('Missing actual shared global chunk');const s=c.get(Math.floor(y/16));return s?s.pal[s.blocks[((y%16+16)%16)*256+((z%16+16)%16)*16+((x%16+16)%16)]]:air;}};
}
const tiles=pipeline.tiles.map(load),pairs=[];let comparedCells=0,mismatchedCells=0;
for(let i=0;i<tiles.length;i++)for(let j=i+1;j<tiles.length;j++){
 const a=tiles[i],b=tiles[j],ab=a.t.renderBounds,bb=b.t.renderBounds,bounds=[Math.max(ab[0],bb[0]),Math.max(ab[1],bb[1]),Math.min(ab[2],bb[2]),Math.min(ab[3],bb[3])];if(bounds[0]>=bounds[2]||bounds[1]>=bounds[3])continue;
 let compared=0,mismatch=0;const examples=[],lh=crypto.createHash('sha256'),rh=crypto.createHash('sha256');
 for(let x=bounds[0];x<bounds[2];x++)for(let z=bounds[1];z<bounds[3];z++){
 const left=a.column(x,z),right=b.column(x,z);compared+=384;lh.update(JSON.stringify(left)+'\n');rh.update(JSON.stringify(right)+'\n');
 let li=0,ri=0,y=-64;while(y<320){const l=left[li],r=right[ri],end=Math.min(l[1],r[1]);if(l[2]!==r[2]){mismatch+=end-y;if(examples.length<5)examples.push({global:[x,y,z],left:JSON.parse(l[2]),right:JSON.parse(r[2]),verticalLength:end-y});}y=end;if(y===l[1])li++;if(y===r[1])ri++;}
}
 comparedCells+=compared;mismatchedCells+=mismatch;pairs.push({left:a.t.id,right:b.t.id,bounds,comparedCells:compared,mismatchedCells:mismatch,leftSha256:lh.digest('hex'),rightSha256:rh.digest('hex'),examples});
}
const report={status:comparedCells>0&&!mismatchedCells&&!metadataErrors.length&&!hashErrors.length?'PASS':'FAIL',kind:'independent-fast-actual-seam-oracle',digestFormat:'fork.seam-column-spans.v1',scope:'Independent canonical actual block comparison across every shared 3D halo including diagonal corner overlaps, Y -64..319',pipelineSha256:hash(file),sourceSha256:pipeline.source.sha256,inputs,pairs,comparedCells,mismatchedCells,metadataErrors,hashErrors,sourceRunOracleAccepted:false,runtimeLoadAccepted:false,fullWorldAccepted:false,createdUtc:new Date().toISOString()};fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));if(report.status==='FAIL')process.exitCode=1;
