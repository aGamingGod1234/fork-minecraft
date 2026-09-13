import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import {readRegion,sections,unpack} from '../fork-world/nbt-region.mjs';
const [file,out]=process.argv.slice(2);if(!out)throw Error('Usage: validate-feature-seams.mjs pipeline-result.json audit.json');
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
 const ss=new Map();for(const s of sections(c)){const u=unpack(s);if(u)ss.set(s.Y.value,{pal:u.pal.map(p=>JSON.stringify(plain(p))),blocks:u.blocks});}chunks.set(key,ss);}
 }
 return {t,block(x,y,z){const c=chunks.get(Math.floor(x/16)+','+Math.floor(z/16));if(!c)throw Error('Missing actual shared global chunk');const s=c.get(Math.floor(y/16));return s?s.pal[s.blocks[((y%16+16)%16)*256+((z%16+16)%16)*16+((x%16+16)%16)]]:air;}};
}
const tiles=pipeline.tiles.map(load),pairs=[];let comparedCells=0,mismatchedCells=0;
for(let i=0;i<tiles.length;i++)for(let j=i+1;j<tiles.length;j++){
 const a=tiles[i],b=tiles[j],ab=a.t.renderBounds,bb=b.t.renderBounds,bounds=[Math.max(ab[0],bb[0]),Math.max(ab[1],bb[1]),Math.min(ab[2],bb[2]),Math.min(ab[3],bb[3])];if(bounds[0]>=bounds[2]||bounds[1]>=bounds[3])continue;
 let compared=0,mismatch=0;const examples=[],lh=crypto.createHash('sha256'),rh=crypto.createHash('sha256');
 for(let x=bounds[0];x<bounds[2];x++)for(let z=bounds[1];z<bounds[3];z++)for(let y=-64;y<320;y++){const l=a.block(x,y,z),r=b.block(x,y,z);compared++;lh.update(l+'\n');rh.update(r+'\n');if(l!==r){mismatch++;if(examples.length<5)examples.push({global:[x,y,z],left:JSON.parse(l),right:JSON.parse(r)});}}
 comparedCells+=compared;mismatchedCells+=mismatch;pairs.push({left:a.t.id,right:b.t.id,bounds,comparedCells:compared,mismatchedCells:mismatch,leftSha256:lh.digest('hex'),rightSha256:rh.digest('hex'),examples});
}
const report={status:comparedCells>0&&!mismatchedCells&&!metadataErrors.length&&!hashErrors.length?'PASS':'FAIL',scope:'Independent canonical actual block comparison across every shared 3D halo including diagonal corner overlaps, Y -64..319',pipelineSha256:hash(file),sourceSha256:pipeline.source.sha256,inputs,pairs,comparedCells,mismatchedCells,metadataErrors,hashErrors,sourceRunOracleAccepted:false,runtimeLoadAccepted:false,fullWorldAccepted:false,createdUtc:new Date().toISOString()};fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));if(report.status==='FAIL')process.exitCode=1;
