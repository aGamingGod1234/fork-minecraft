import fs from 'node:fs';
import {validateWorldSettings} from './validate-world-settings.mjs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import {readRegion,sections,unpack,decode} from '../fork-world/nbt-region.mjs';
const args=process.argv.slice(2),opts={runs:[]};
for(let i=0;i<args.length;i+=2){if(!args[i+1])throw Error('Expected flag value');if(args[i]==='--runs')opts.runs.push(args[i+1]);else opts[args[i].replace(/^--/,'')]=args[i+1];}
if(!opts.world||!opts.bounds||!opts.out||!opts.runs.length)throw Error('Usage: validate-feature-world.mjs --world DIR --bounds xMin,zMin,xMax,zMax --runs FILE [--runs FILE] --out FILE');
const bounds=opts.bounds.split(',').map(Number),[x0,z0,x1,z1]=bounds;
if(bounds.length!==4||!bounds.every(Number.isSafeInteger)||x0>=x1||z0>=z1||bounds.some(n=>n%16))throw Error('Nonempty chunk-aligned half-open bounds required');
if((x1-x0)*(z1-z0)>1048576)throw Error('Independent audit limited to 1,048,576 columns; use bounded pilot regions');
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
function plain(v){if(v&&typeof v==='object'&&'value'in v)return plain(v.value);if(Array.isArray(v))return v.map(plain);if(v&&typeof v==='object')return Object.fromEntries(Object.keys(v).sort().map(k=>[k,plain(v[k])]));return v;}
const state=(block,props)=>JSON.stringify({Name:block,...(props&&Object.keys(props).length?{Properties:Object.fromEntries(Object.entries(props).sort(([a],[b])=>a.localeCompare(b)))}:{})});
const air=state('minecraft:air'),stone=state('minecraft:bedrock'),dirt=state('minecraft:dirt'),grass=state('minecraft:grass_block');
const columns=new Map(),inputErrors=[],inputs=[],layers={},classes={};let runs=0,retainedRuns=0;
for(const p of opts.runs){inputs.push({path:path.resolve(p),sha256:hash(p),bytes:fs.statSync(p).size});let line=0;for(const text of fs.readFileSync(p,'utf8').split(/\r?\n/)){line++;if(!text.trim())continue;let r;try{r=JSON.parse(text);}catch{inputErrors.push({file:p,line,error:'invalid JSON'});continue;}runs++; if(typeof r.layer==='string')r.layer=({terrain:10,landcover:20,water:30,road:40,building:50,bridge:60})[r.layer];
 const ints=[r.x,r.z,r.yMin,r.yMax,r.layer];
 if(!ints.every(Number.isSafeInteger)||![10,20,30,40,50,60].includes(r.layer)||r.yMin>=r.yMax||r.yMin< -64||r.yMax>320||!/^minecraft:[a-z0-9_]+$/.test(r.block??'')){inputErrors.push({file:p,line,error:'invalid coordinate, height, layer or block'});continue;}
 if(!r.featureId||!r.geometryKind||!r.sourceClass){inputErrors.push({file:p,line,error:'missing source provenance fields'});continue;}
 layers[r.layer]=(layers[r.layer]??0)+1;classes[r.sourceClass]=(classes[r.sourceClass]??0)+1;
 if(r.x<x0||r.x>=x1||r.z<z0||r.z>=z1)continue;retainedRuns++;const key=r.x+','+r.z;
 if(!columns.has(key))columns.set(key,[]);columns.get(key).push({...r,state:state(r.block,r.properties)});
}}
const chunks=new Map(),heightmaps=new Map(),metadataErrors=[],worldFiles=[];
const level=decode(zlib.gunzipSync(fs.readFileSync(path.join(opts.world,'level.dat')))).value.Data.value;
const dataVersion=level.DataVersion.value,worldSettings=validateWorldSettings(opts.world),regionRoot=path.join(opts.world,worldSettings.regionDirectory);
if(worldSettings.status!=='PASS'){const failure={status:'FAIL',scope:'Active world settings/layout guard failed before block comparison',worldSettings};fs.writeFileSync(opts.out,JSON.stringify(failure,null,2)+'\n');console.log(JSON.stringify(failure,null,2));process.exit(1);}
for(const name of fs.readdirSync(regionRoot)){const match=name.match(/^r\.(-?\d+)\.(-?\d+)\.mca$/);if(!match)continue;const rx=+match[1],rz=+match[2];if((rx+1)*512<=x0||rx*512>=x1||(rz+1)*512<=z0||rz*512>=z1)continue;
 const p=path.join(regionRoot,name);worldFiles.push({path:name,sha256:hash(p),bytes:fs.statSync(p).size});
 for(const c of readRegion(p)){const cx=c.root.value.xPos?.value,cz=c.root.value.zPos?.value;if(!Number.isInteger(cx)||!Number.isInteger(cz))throw Error('Missing global chunk coordinates');
 if(Math.floor(cx/32)!==rx||Math.floor(cz/32)!==rz)metadataErrors.push({kind:'wrong global region',cx,cz,rx,rz});
 if(c.slot!==((cz%32+32)%32)*32+((cx%32+32)%32))metadataErrors.push({kind:'wrong region header slot',cx,cz,slot:c.slot});
 if(c.root.value.DataVersion?.value!==dataVersion)metadataErrors.push({kind:'chunk/level DataVersion mismatch',cx,cz});
 const key=cx+','+cz;heightmaps.set(key,c.root.value.Heightmaps?.value.WORLD_SURFACE?.value);if(chunks.has(key))metadataErrors.push({kind:'duplicate global chunk',cx,cz});const ss=new Map();
 for(const s of sections(c)){const u=unpack(s);if(u)ss.set(s.Y.value,{pal:u.pal.map(p=>JSON.stringify(plain(p))),blocks:u.blocks});}chunks.set(key,ss);}
}
const actual=(x,y,z)=>{const c=chunks.get(Math.floor(x/16)+','+Math.floor(z/16));if(!c)return null;const s=c.get(Math.floor(y/16));return s?s.pal[s.blocks[((y%16+16)%16)*256+((z%16+16)%16)*16+((x%16+16)%16)]]:air;};
let compared=0,mismatches=0,conflicts=0,occludedConflicts=0,missingColumns=0,heightmapMismatches=0;
const airNames=new Set(['minecraft:air','minecraft:cave_air','minecraft:void_air']),nameCache=new Map();const blockName=s=>{if(!nameCache.has(s))nameCache.set(s,JSON.parse(s).Name);return nameCache.get(s);};const examples=[],conflictExamples=[],expectedHash=crypto.createHash('sha256'),actualHash=crypto.createHash('sha256'),byY={};
for(let x=x0;x<x1;x++)for(let z=z0;z<z1;z++){
 const expected=Array(384).fill(air),priority=new Int16Array(384).fill(-32768);
 expected[-4+64]=stone;for(let y=-3;y<0;y++)expected[y+64]=dirt;expected[64]=grass;
 const source=columns.get(x+','+z)??[], layerStates=new Map(), collisions=[];
 for(const r of source)for(let y=r.yMin;y<r.yMax;y++){const i=y+64;if(!layerStates.has(r.layer))layerStates.set(r.layer,Array(384));const prior=layerStates.get(r.layer)[i];if(prior!==undefined&&r.state!==prior){collisions.push({x,y,z,featureId:r.featureId,left:JSON.parse(prior),right:JSON.parse(r.state),layer:r.layer});}
 layerStates.get(r.layer)[i]??=r.state; if(r.layer>priority[i]){expected[i]=r.state;priority[i]=r.layer;}}
 for(const collision of collisions){if(priority[collision.y+64]===collision.layer){conflicts++;if(conflictExamples.length<20)conflictExamples.push(collision);}else occludedConflicts++;}
 if(!chunks.has(Math.floor(x/16)+','+Math.floor(z/16))){missingColumns++;continue;}
 let expectedSurface=0;
 for(let y=-64;y<320;y++){const e=expected[y+64],a=actual(x,y,z);if(!airNames.has(blockName(e)))expectedSurface=y+65;compared++;expectedHash.update(e+'\n');actualHash.update(a+'\n');if(a!==e){mismatches++;byY[y]=(byY[y]??0)+1;if(examples.length<20)examples.push({global:[x,y,z],expected:JSON.parse(e),actual:JSON.parse(a)});}}
 const hm=heightmaps.get(Math.floor(x/16)+','+Math.floor(z/16)),hi=((z%16+16)%16)*16+((x%16+16)%16);
 if(!hm||hm.length!==37||Number((BigInt.asUintN(64,hm[Math.floor(hi/7)])>>BigInt(hi%7*9))&511n)!==expectedSurface)heightmapMismatches++;
}
const spawn=worldSettings.spawn;
let spawnClear=false;if(spawn.every(Number.isInteger)&&spawn[0]>=x0&&spawn[0]<x1&&spawn[2]>=z0&&spawn[2]<z1&&spawn[1]>=-63&&spawn[1]<319){const feet=actual(...spawn),head=actual(spawn[0],spawn[1]+1,spawn[2]),floor=actual(spawn[0],spawn[1]-1,spawn[2]);spawnClear=feet&&head&&floor&&airNames.has(blockName(feet))&&airNames.has(blockName(head))&&!airNames.has(blockName(floor))&&!['minecraft:water','minecraft:lava'].includes(blockName(floor));}
const pass=worldSettings.status==='PASS'&&spawnClear&&!heightmapMismatches&&!inputErrors.length&&!metadataErrors.length&&!conflicts&&!missingColumns&&!mismatches;
const report={status:pass?'PASS':'FAIL',scope:'Independent global JSONL run oracle versus decoded Minecraft blocks, including all default-background cells',bounds,inputs,worldFiles,runs,retainedRuns,sourceColumns:columns.size,layers,sourceClasses:classes,comparedCells:compared,mismatchedCells:mismatches,sameLayerConflictingCells:conflicts,occludedLowerLayerConflicts:occludedConflicts,missingColumns,heightmapMismatches,spawn,spawnClear,dataVersion,worldSettings,inputErrors,metadataErrors,expectedBlockSha256:expectedHash.digest('hex'),actualBlockSha256:actualHash.digest('hex'),byY,examples,conflictExamples,joinedStripBlockAccepted:pass,runtimeLoadAccepted:false,terrainAccepted:false,facadeAccuracyAccepted:false,fullWorldAccepted:false,createdUtc:new Date().toISOString()};
fs.writeFileSync(opts.out,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({...report,inputs:undefined,worldFiles:undefined,byY:undefined,examples:undefined,conflictExamples:undefined},null,2));if(!pass)process.exitCode=1;
