import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {createInterface} from 'node:readline';
import {fileURLToPath,pathToFileURL} from 'node:url';
import zlib from 'node:zlib';
import {decode,sections,unpack} from '../fork-world/nbt-region.mjs';

const Y_MIN=-64,Y_MAX=320,LAYERS={terrain:10,landcover:20,water:30,road:40,building:50,bridge:60};
const AIR_NAMES=new Set(['minecraft:air','minecraft:cave_air','minecraft:void_air']);
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
async function streamHash(file){const digest=crypto.createHash('sha256');for await(const chunk of fs.createReadStream(file))digest.update(chunk);return digest.digest('hex');}
const mod=(n,d)=>(n%d+d)%d;
const canonical=(name,properties={})=>JSON.stringify({Name:name,...(Object.keys(properties).length?
  {Properties:Object.fromEntries(Object.entries(properties).sort(([a],[b])=>a.localeCompare(b)))}:{})});

const RUN_PAGE=65536,NONE=0xffffffff;
class CompactColumns {
  constructor(count){this.head=new Uint32Array(count).fill(NONE);this.tail=new Uint32Array(count).fill(NONE);
    this.pages=[];this.count=0;this.columnCount=0;}
  get bytes(){return this.head.byteLength+this.tail.byteLength+this.pages.length*RUN_PAGE*12;}
  page(i){return this.pages[Math.floor(i/RUN_PAGE)];}
  add(column,lo,hi,layer,state) {
    const i=this.count++,pageIndex=Math.floor(i/RUN_PAGE),offset=i%RUN_PAGE*3;
    if(!this.pages[pageIndex]){
      if(this.bytes+RUN_PAGE*12>512*1024*1024)throw Error('Compact source-run memory cap exceeded (512 MiB)');
      this.pages.push(new Uint32Array(RUN_PAGE*3));
    }
    const p=this.pages[pageIndex];p[offset]=(lo+64)|((hi+64)<<9)|((layer/10)<<18);p[offset+1]=state;p[offset+2]=NONE;
    if(this.head[column]===NONE){this.head[column]=i;this.columnCount++;}
    else this.page(this.tail[column])[(this.tail[column]%RUN_PAGE)*3+2]=i;
    this.tail[column]=i;
  }
  get(column) {
    const rows=[];
    for(let i=this.head[column];i!==NONE;) {
      const p=this.page(i),offset=i%RUN_PAGE*3,packed=p[offset];
      rows.push({lo:(packed&511)-64,hi:((packed>>>9)&511)-64,layer:(packed>>>18)*10,id:p[offset+1]});i=p[offset+2];
    }
    return rows;
  }
}
function* streamRegion(file) {
  const fd=fs.openSync(file,'r'),size=fs.fstatSync(fd).size,header=Buffer.alloc(8192);
  try {
    if(size<8192||fs.readSync(fd,header,0,8192,0)!==8192)throw Error('Incomplete region header');
    for(let slot=0;slot<1024;slot++) {
      const entry=header.readUInt32BE(slot*4),sector=entry>>>8,count=entry&255;
      if(!entry)continue;
      if(sector<2||!count||(sector+count)*4096>size)throw Error('Invalid region sector extent at slot '+slot);
      const prefix=Buffer.alloc(5);fs.readSync(fd,prefix,0,5,sector*4096);
      const length=prefix.readUInt32BE(0);
      if(length<2||length+4>count*4096||prefix[4]!==2)throw Error('Unsupported or invalid compressed chunk at slot '+slot);
      const packed=Buffer.alloc(length-1);
      if(fs.readSync(fd,packed,0,packed.length,sector*4096+5)!==packed.length)throw Error('Truncated compressed chunk');
      yield {slot,root:decode(zlib.inflateSync(packed,{maxOutputLength:64*1024*1024}))};
    }
  } finally {fs.closeSync(fd);}
}

export class StatePool {
  constructor(){this.estimatedBytes=0;this.ids=new Map();this.texts=[];this.names=[];this.air=this.id('minecraft:air');
    this.bedrock=this.id('minecraft:bedrock');this.dirt=this.id('minecraft:dirt');this.grass=this.id('minecraft:grass_block');}
  id(name,properties={}) {
    const text=canonical(name,properties);
    if(!this.ids.has(text)){
      const bytes=2*text.length+2*name.length+128;
      if(this.ids.size>=65536||this.estimatedBytes+bytes>64*1024*1024)throw Error('Distinct block-state budget exceeded (65536 states / 64 MiB estimate)');
      this.estimatedBytes+=bytes;this.ids.set(text,this.texts.length);this.texts.push(text);this.names.push(name);}
    return this.ids.get(text);
  }
  fromPalette(p) {
    const name=p.Name?.value,props=Object.fromEntries(Object.entries(p.Properties?.value??{}).map(([k,v])=>[k,v.value]));
    if(typeof name!=='string'||Object.values(props).some(v=>typeof v!=='string'))throw Error('Invalid NBT palette state');
    return this.id(name,props);
  }
}
function append(out,lo,hi,id) {
  if(lo>=hi)return;
  const last=out.at(-1);if(last&&last[1]===lo&&last[2]===id)last[1]=hi;else out.push([lo,hi,id]);
}
const background=(y,pool)=>y===-4?pool.bedrock:y>=-3&&y<0?pool.dirt:y===0?pool.grass:pool.air;
export function resolveColumn(runs,pool) {
  if(!runs?.length)return {spans:[[-64,-4,pool.air],[-4,-3,pool.bedrock],[-3,0,pool.dirt],[0,1,pool.grass],[1,320,pool.air]],conflicts:0,occluded:0,conflictSpans:[]};
  const events=new Map([[-64,[]],[-4,[]],[-3,[]],[0,[]],[1,[]],[320,[]]]);
  for(let i=0;i<runs.length;i++)for(const [y,add] of [[runs[i].lo,true],[runs[i].hi,false]]) {
    if(!events.has(y))events.set(y,[]);events.get(y).push({i,add});
  }
  const points=[...events.keys()].sort((a,b)=>a-b),active=new Map(),spans=[],conflictSpans=[];let conflicts=0,occluded=0;
  for(let index=0;index<points.length-1;index++) {
    const lo=points[index],hi=points[index+1];
    for(const event of events.get(lo)){if(event.add)active.set(event.i,runs[event.i]);else active.delete(event.i);}
    let highest=-Infinity,id=background(lo,pool);const groups=new Map();
    for(const [i,r] of active){const group=groups.get(r.layer)??[];group.push({i,...r});groups.set(r.layer,group);highest=Math.max(highest,r.layer);}
    for(const [layer,group] of groups) {
      let first=group[0];for(const r of group)if(r.i<first.i)first=r;
      const count=group.filter(r=>r.id!==first.id).length;
      if(layer===highest){id=first.id;conflicts+=count*(hi-lo);if(count)conflictSpans.push({lo,hi,layer});}
      else occluded+=count*(hi-lo);
    }
    append(spans,lo,hi,id);
  }
  return {spans,conflicts,occluded,conflictSpans};
}
export function compareSpans(expected,actual) {
  let i=0,j=0,mismatches=0;const differences=[];
  while(i<expected.length&&j<actual.length) {
    const e=expected[i],a=actual[j],lo=Math.max(e[0],a[0]),hi=Math.min(e[1],a[1]);
    if(lo<hi&&e[2]!==a[2]){mismatches+=hi-lo;differences.push([lo,hi,e[2],a[2]]);}
    if(e[1]<=a[1])i++;if(a[1]<=e[1])j++;
  }
  return {mismatches,differences};
}
function actualColumn(decoded,lx,lz,pool) {
  const spans=[],offset=lz*16+lx;
  for(let sy=-4;sy<20;sy++) {
    const section=decoded.get(sy);
    if(!section){append(spans,sy*16,sy*16+16,pool.air);continue;}
    if(section.uniform!==null){append(spans,sy*16,sy*16+16,section.uniform);continue;}
    for(let ly=0;ly<16;ly++)append(spans,sy*16+ly,sy*16+ly+1,section.ids[section.blocks[ly*256+offset]]);
  }
  return spans;
}
function stateAt(spans,y){const span=spans.find(s=>s[0]<=y&&y<s[1]);return span?.[2];}
function surface(spans,pool) {
  for(let i=spans.length-1;i>=0;i--)if(!AIR_NAMES.has(pool.names[spans[i][2]]))return spans[i][1]+64;
  return 0;
}
export async function auditWorld(options) {
  const started=performance.now(),bounds=Array.isArray(options.bounds)?options.bounds:options.bounds.split(',').map(Number);
  const [x0,z0,x1,z1]=bounds,width=x1-x0,depth=z1-z0;
  if(bounds.length!==4||!bounds.every(Number.isSafeInteger)||width<=0||depth<=0||bounds.some(n=>n%16)||width*depth>1048576)
    throw Error('Nonempty chunk-aligned bounds, at most 1,048,576 columns, required');
  if(options.legacyHashes&&width*depth>256)throw Error('Legacy expanded hashes are restricted to tiny <=256-column equivalence fixtures');
  const settingsPath=options.settingsValidator??fileURLToPath(new URL('./validate-world-settings.mjs',import.meta.url));
  if(!fs.existsSync(settingsPath))throw Error('Modern settings guard missing; supply --settings-validator PATH');
  const {validateWorldSettings}=await import(pathToFileURL(path.resolve(settingsPath)).href);
  const worldSettings=validateWorldSettings(options.world);
  const report={schemaVersion:1,kind:'independent-source-run-fast-world-oracle',status:'FAIL',
    scope:'Exact interval comparison of every block in bounds and [-64,320), including sparse air and default background',
    bounds,inputs:[],worldFiles:[],runs:0,retainedRuns:0,sourceColumns:0,layers:{},sourceClasses:{},comparedCells:0,
    mismatchedCells:0,sameLayerConflictingCells:0,occludedLowerLayerConflicts:0,missingColumns:0,heightmapMismatches:0,
    chunkCount:width*depth/256,comparedCoreChunkCount:0,allocatedChunkCount:0,sourceFiles:[],errors:[],
    inputErrorCount:0,metadataErrorCount:0,inputErrors:[],metadataErrors:[],examples:[],conflictExamples:[],byY:{},
    worldSettings,settingsValidatorSha256:sha(settingsPath),dataVersion:worldSettings.dataVersion,spawn:worldSettings.spawn,
    spawnClear:false,joinedStripBlockAccepted:false,runtimeLoadAccepted:false,terrainAccepted:false,facadeAccuracyAccepted:false,fullWorldAccepted:false};
  if(!Number.isInteger(worldSettings.dataVersion)){worldSettings.status='FAIL';worldSettings.errors.push('Missing integer DataVersion');}
  if(worldSettings.status!=='PASS'){report.errors=worldSettings.errors.map(error=>({kind:'world settings',error}));return report;}
  const pool=new StatePool(),columns=new CompactColumns(width*depth);
  const inputError=(file,line,error)=>{report.inputErrorCount++;if(report.inputErrors.length<30)report.inputErrors.push({file,line,error});};
  const metadataError=error=>{report.metadataErrorCount++;if(report.metadataErrors.length<30)report.metadataErrors.push(error);};
  for(const file of options.runs) {
    const input=fs.createReadStream(file),digest=crypto.createHash('sha256');let bytes=0,line=0;
    input.on('data',chunk=>{bytes+=chunk.length;digest.update(chunk);});
    for await(const body of createInterface({input,crlfDelay:Infinity})) {
      line++;if(!body.trim())continue;report.runs++;
      if(body.length>1048576){inputError(file,line,'Source record exceeds 1 MiB character budget');continue;}
      let r;try{r=JSON.parse(body.replace(/^\uFEFF/,''));}catch{inputError(file,line,'Invalid JSON');continue;}
      if(!r||typeof r!=='object'||Array.isArray(r)){inputError(file,line,'Expected run object');continue;}
      const layer=typeof r.layer==='string'?LAYERS[r.layer]:r.layer;
      if(![r.x,r.z,r.yMin,r.yMax].every(Number.isSafeInteger)||!Object.values(LAYERS).includes(layer)||
        r.yMin>=r.yMax||r.yMin<Y_MIN||r.yMax>Y_MAX||!/^minecraft:[a-z0-9_]+$/.test(r.block??'')) {
        inputError(file,line,'Invalid coordinate, height, explicit layer or block');continue;
      }
      if(!['featureId','geometryKind','sourceClass'].every(k=>typeof r[k]==='string'&&r[k].length>0)||
        (r.properties!==undefined&&(!r.properties||typeof r.properties!=='object'||Array.isArray(r.properties)||Object.values(r.properties).some(v=>typeof v!=='string')))) {
        inputError(file,line,'Invalid source provenance or properties');continue;
      }
      report.layers[layer]=(report.layers[layer]??0)+1;report.sourceClasses[r.sourceClass]=(report.sourceClasses[r.sourceClass]??0)+1;
      if(r.x<x0||r.x>=x1||r.z<z0||r.z>=z1)continue;
      report.retainedRuns++;const key=(r.x-x0)*depth+(r.z-z0);
      columns.add(key,r.yMin,r.yMax,layer,pool.id(r.block,r.properties));
    }
    report.inputs.push({path:path.resolve(file),sha256:digest.digest('hex'),bytes});
  }
  report.sourceColumns=columns.columnCount;report.compactRunStorageBytes=columns.bytes;report.sourceFiles=report.inputs;
  const regionRoot=path.join(options.world,worldSettings.regionDirectory),seen=new Set(),expectedDigest=crypto.createHash('sha256'),actualDigest=crypto.createHash('sha256');
  const legacyExpected=options.legacyHashes?crypto.createHash('sha256'):null,legacyActual=options.legacyHashes?crypto.createHash('sha256'):null;
  const files=fs.readdirSync(regionRoot).map(name=>({name,match:name.match(/^r\.(-?\d+)\.(-?\d+)\.mca$/)}))
    .filter(f=>f.match).map(f=>({...f,rx:Number(f.match[1]),rz:Number(f.match[2])}))
    .sort((a,b)=>a.rx-b.rx||a.rz-b.rz);
  for(const file of files) {
    const p=path.join(regionRoot,file.name),beforeHash=await streamHash(p);report.worldFiles.push({path:file.name,sha256:beforeHash,bytes:fs.statSync(p).size});
    try {for(const chunk of streamRegion(p)) {report.allocatedChunkCount++;
      const root=chunk.root.value,cx=root.xPos?.value,cz=root.zPos?.value;
      if(!Number.isInteger(cx)||!Number.isInteger(cz)){metadataError({kind:'missing global chunk coordinates',file:file.name,slot:chunk.slot});continue;}
      if(Math.floor(cx/32)!==file.rx||Math.floor(cz/32)!==file.rz)metadataError({kind:'wrong global region',cx,cz,rx:file.rx,rz:file.rz});
      if(chunk.slot!==mod(cz,32)*32+mod(cx,32))metadataError({kind:'wrong region header slot',cx,cz,slot:chunk.slot});
      if(root.DataVersion?.value!==report.dataVersion)metadataError({kind:'chunk/level DataVersion mismatch',cx,cz});
      const chunkKey=cx+','+cz;if(seen.has(chunkKey))metadataError({kind:'duplicate global chunk',cx,cz});seen.add(chunkKey);
      if(cx*16<x0||cx*16>=x1||cz*16<z0||cz*16>=z1)continue;
      report.comparedCoreChunkCount++;const decoded=new Map();
      try {
        for(const section of sections(chunk)) {
          const sy=section.Y?.value;if(!Number.isInteger(sy)||sy< -4||sy>=20){metadataError({kind:'section Y outside [-4,20)',cx,cz,sy});continue;}
          if(decoded.has(sy)){metadataError({kind:'duplicate section Y',cx,cz,sy});continue;}
          const bs=section.block_states?.value;
          if(bs){const palette=bs.palette?.value.items;
            if(!Array.isArray(palette)||palette.length<1||palette.length>4096)throw Error('Section palette must contain 1..4096 entries before unpacking');
            if(palette.length>1){const bits=Math.max(4,Math.ceil(Math.log2(palette.length))),per=Math.floor(64/bits);
              if(!Array.isArray(bs.data?.value)||bs.data.value.length!==Math.ceil(4096/per))throw Error('Wrong packed section data length');}}
          const u=unpack(section);if(!u){decoded.set(sy,{uniform:pool.air});continue;}
          if(!u.pal.length)throw Error('Empty section palette');
          const ids=u.pal.map(p=>pool.fromPalette(p));let uniform=ids[u.blocks[0]];
          for(let i=0;i<4096;i++){if(u.blocks[i]>=ids.length)throw Error('Palette index out of range');if(ids[u.blocks[i]]!==uniform)uniform=null;}
          decoded.set(sy,{ids,blocks:u.blocks,uniform});
        }
      } catch(error){metadataError({kind:'invalid section data',cx,cz,error:error.message});}
      const hm=root.Heightmaps?.value.WORLD_SURFACE?.value;
      for(let lx=0;lx<16;lx++)for(let lz=0;lz<16;lz++) {
        const x=cx*16+lx,z=cz*16+lz,key=(x-x0)*depth+(z-z0),resolved=resolveColumn(columns.get(key),pool),expected=resolved.spans,actual=actualColumn(decoded,lx,lz,pool);
        report.sameLayerConflictingCells+=resolved.conflicts;report.occludedLowerLayerConflicts+=resolved.occluded;
        for(const conflict of resolved.conflictSpans)if(report.conflictExamples.length<20)report.conflictExamples.push({x,z,...conflict});
        const compared=compareSpans(expected,actual);report.comparedCells+=384;report.mismatchedCells+=compared.mismatches;
        for(const [lo,hi,e,a] of compared.differences) {
          for(let y=lo;y<hi;y++)report.byY[y]=(report.byY[y]??0)+1;
          if(report.examples.length<20)report.examples.push({global:[x,lo,z],yMaxExclusive:hi,expected:JSON.parse(pool.texts[e]),actual:JSON.parse(pool.texts[a])});
        }
        const format=spans=>x+','+z+'|'+spans.map(([lo,hi,id])=>lo+','+hi+':'+pool.texts[id]).join('|')+'\n';
        expectedDigest.update(format(expected));actualDigest.update(format(actual));
        if(legacyExpected) {
          for(const [lo,hi,id] of expected)legacyExpected.update((pool.texts[id]+'\n').repeat(hi-lo));
          for(const [lo,hi,id] of actual)legacyActual.update((pool.texts[id]+'\n').repeat(hi-lo));
        }
        const hi=lz*16+lx,expectedSurface=surface(expected,pool);
        if(!hm||hm.length!==37||Number((BigInt.asUintN(64,hm[Math.floor(hi/7)])>>BigInt(hi%7*9))&511n)!==expectedSurface)report.heightmapMismatches++;
        const spawn=report.spawn;
        if(Array.isArray(spawn)&&spawn[0]===x&&spawn[2]===z&&Number.isInteger(spawn[1])&&spawn[1]>=-63&&spawn[1]<319) {
          const feet=pool.names[stateAt(actual,spawn[1])],head=pool.names[stateAt(actual,spawn[1]+1)],floor=pool.names[stateAt(actual,spawn[1]-1)];
          report.spawnClear=AIR_NAMES.has(feet)&&AIR_NAMES.has(head)&&!!floor&&!AIR_NAMES.has(floor)&&!['minecraft:water','minecraft:lava'].includes(floor);
        }
      }
    }} catch(error){metadataError({kind:'unreadable region',file:file.name,error:error.message});}
    if(await streamHash(p)!==beforeHash)metadataError({kind:'region changed during validation',file:file.name});
  }
  for(let cx=x0/16;cx<x1/16;cx++)for(let cz=z0/16;cz<z1/16;cz++)if(!seen.has(cx+','+cz))report.missingColumns+=256;
  report.digestFormat='fork.column-intervals.v1: numeric region X,Z then region-header slot then local X,Z; maximal [yMin,yMax,state] spans';
  report.expectedIntervalSha256=expectedDigest.digest('hex');report.actualIntervalSha256=actualDigest.digest('hex');
  if(legacyExpected){report.expectedBlockSha256=legacyExpected.digest('hex');report.actualBlockSha256=legacyActual.digest('hex');}
  report.status=report.spawnClear&&!report.inputErrorCount&&!report.metadataErrorCount&&!report.sameLayerConflictingCells&&
    !report.missingColumns&&!report.mismatchedCells&&!report.heightmapMismatches?'PASS':'FAIL';
  report.errors=[...report.inputErrors,...report.metadataErrors];
  for(const key of ['inputErrorCount','metadataErrorCount','sameLayerConflictingCells','missingColumns','mismatchedCells','heightmapMismatches'])if(report[key])report.errors.push({kind:key,count:report[key]});
  if(!report.spawnClear)report.errors.push({kind:'spawn is not clear and supported'});
  report.estimatedStatePoolBytes=pool.estimatedBytes;report.stateCount=pool.ids.size;
  report.joinedStripBlockAccepted=report.status==='PASS';report.peakRssBytes=process.resourceUsage().maxRSS*1024;report.elapsedMs=Math.round(performance.now()-started);report.createdUtc=new Date().toISOString();
  return report;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const args=process.argv.slice(2),options={runs:[]};
  while(args.length){const key=args.shift(),value=args.shift();if(!value)throw Error('Expected flag value');
    if(key==='--runs')options.runs.push(value);else if(key==='--settings-validator')options.settingsValidator=value;
    else if(key==='--legacy-hashes')options.legacyHashes=value==='true';else options[key.replace(/^--/,'')]=value;}
  if(!options.world||!options.bounds||!options.out||!options.runs.length)throw Error('Usage: validate-world-fast.mjs --world DIR --bounds xMin,zMin,xMax,zMax --runs FILE [--runs FILE] --out FILE [--settings-validator PATH]');
  let report;try{report=await auditWorld(options);}catch(error){report={schemaVersion:1,kind:'independent-source-run-fast-world-oracle',status:'FAIL',bounds:options.bounds.split(',').map(Number),errors:[{kind:'fatal validation error',error:error.message}],comparedCells:0,mismatchedCells:0,joinedStripBlockAccepted:false,fullWorldAccepted:false};}
  fs.writeFileSync(options.out,JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify({...report,inputs:undefined,worldFiles:undefined,worldSettings:undefined,examples:undefined,conflictExamples:undefined,byY:undefined},null,2));
  if(report.status!=='PASS')process.exitCode=1;
}
