import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {encode,writeRegion,repack} from '../fork-world/nbt-region.mjs';
import {auditWorld,StatePool,resolveColumn,compareSpans} from './validate-world-fast.mjs';
const [settingsValidator,originalOracle,evidencePath]=process.argv.slice(2);
if(!settingsValidator||!originalOracle)throw Error('Usage: node validate-world-fast.test.mjs SETTINGS_VALIDATOR EXISTING_SCALAR_ORACLE');
const folder=path.dirname(fileURLToPath(import.meta.url)),root=fs.mkdtempSync(path.join(folder,'validate-world-fast-fixture-'));
const bounds=[-16,-16,0,0],tag=(type,value)=>({type,value}),compound=value=>tag(10,value),list=items=>tag(9,{subtype:10,items});
const base={x:-15,z:-15,yMin:1,yMax:5,layer:50,block:'minecraft:stone',featureId:'way/1',geometryKind:'facade',sourceClass:'estimated'};
const runs=[base,{...base,layer:60,yMin:3,yMax:4,block:'minecraft:air',geometryKind:'bridge'},
  {...base,layer:40,yMin:1,yMax:3,block:'minecraft:dirt',geometryKind:'road'},
  {...base,layer:40,yMin:1,yMax:3,block:'minecraft:gravel',geometryKind:'road'}];
const reports=[];
function writeFixture(world,{edits=[],badHeightmap=false,badChunk=false,missingModern=false,wrongProps=false,oversizedPalette=false}={}) {
  fs.mkdirSync(path.join(world,'dimensions/minecraft/overworld/region'),{recursive:true});
  fs.mkdirSync(path.join(world,'data/minecraft'),{recursive:true});
  const data={DataVersion:tag(3,4790),spawn:compound({dimension:tag(8,'minecraft:overworld'),pos:tag(11,[-8,1,-8])}),
    DataPacks:compound({Enabled:tag(9,{subtype:8,items:['vanilla']})})};
  fs.writeFileSync(path.join(world,'level.dat'),zlib.gzipSync(encode({type:10,name:'',value:{Data:compound(data)}})));
  if(!missingModern)fs.writeFileSync(path.join(world,'data/minecraft/world_gen_settings.dat'),zlib.gzipSync(encode({type:10,name:'',value:{
    DataVersion:tag(3,4790),data:compound({generate_structures:tag(1,0)})}})));
  const states=['minecraft:air','minecraft:bedrock','minecraft:dirt','minecraft:grass_block','minecraft:stone'];
  const voxel=new Uint16Array(384*256);
  voxel.fill(1,60*256,61*256);voxel.fill(2,61*256,64*256);voxel.fill(3,64*256,65*256);
  for(const y of [1,2,4])voxel[(y+64)*256+17]=4;
  for(const edit of edits){
    const key=JSON.stringify([edit.block,edit.properties??{}]);let id=states.findIndex(v=>v===key);
    if(id<0){id=states.length;states.push(key);}
    voxel[(edit.y+64)*256+(edit.z+16)*16+(edit.x+16)]=id;
  }
  const sectionList=[];
  for(let sy=-4;sy<20;sy++) {
    const blocks=voxel.slice((sy+4)*4096,(sy+5)*4096);if(!blocks.some(Boolean))continue;
    const ids=[...new Set(blocks)],palette=ids.map(id=>{
      const value=states[id];const [name,props]=value.startsWith('[')?JSON.parse(value):[value,{}];
      return {Name:tag(8,name),...(Object.keys(props).length?{Properties:compound(Object.fromEntries(Object.entries(props).map(([k,v])=>[k,tag(8,v)])))}:{})};
    });
    const local=new Uint16Array(4096);for(let i=0;i<4096;i++)local[i]=ids.indexOf(blocks[i]);
    const section={Y:tag(1,sy),block_states:compound({})};repack(section,palette,local);sectionList.push(section);
  }
  if(oversizedPalette){const palette=Array(65537).fill({Name:tag(8,'minecraft:air')});palette[65536]={Name:tag(8,'minecraft:stone')};
    const packed=Array(Math.ceil(4096/3)).fill(0n);packed[0]=65536n;
    sectionList.push({Y:tag(1,19),block_states:compound({palette:list(palette),data:tag(12,packed)})});}
  const hm=Array(37).fill(0n);
  for(let col=0;col<256;col++){
    let top=0;for(let y=383;y>=0;y--)if(voxel[y*256+col]!==0){top=y+1;break;}
    if(badHeightmap&&col===136)top++;
    hm[Math.floor(col/7)]|=BigInt(top)<<BigInt(col%7*9);
  }
  const chunk={slot:1023,timestamp:0,dirty:true,root:{type:10,name:'',value:{
    xPos:tag(3,badChunk?0:-1),zPos:tag(3,-1),DataVersion:tag(3,4790),sections:list(sectionList),
    Heightmaps:compound({WORLD_SURFACE:tag(12,hm)})}}};
  writeRegion(path.join(world,'dimensions/minecraft/overworld/region/r.-1.-1.mca'),[chunk]);
}
async function check(name,{source=runs,skipScalar=false,...fixture}={},expectedPass=true) {
  const world=path.join(root,name),input=path.join(root,name+'.jsonl'),scalarOut=path.join(root,name+'-scalar.json');
  writeFixture(world,fixture);fs.writeFileSync(input,source.map(r=>JSON.stringify(r)).join('\n')+'\n');
  const fast=await auditWorld({world,bounds,runs:[input],settingsValidator,legacyHashes:true});
  if(skipScalar){assert.equal(fast.status,expectedPass?'PASS':'FAIL',name);reports.push({case:name,status:fast.status,metadataErrorCount:fast.metadataErrorCount});return fast;}
  const scalar=spawnSync(process.execPath,[originalOracle,'--world',world,'--bounds',bounds.join(','),'--runs',input,'--out',scalarOut],{encoding:'utf8',windowsHide:true});
  assert(fs.existsSync(scalarOut),name+' scalar output missing: '+scalar.stderr);
  const reference=JSON.parse(fs.readFileSync(scalarOut,'utf8'));
  assert.equal(fast.status,expectedPass?'PASS':'FAIL',name);
  assert.equal(fast.status,reference.status,name+' status equivalence');
  if(fast.worldSettings.status==='PASS'&&reference.worldSettings?.status==='PASS'){
    for(const key of ['comparedCells','mismatchedCells','missingColumns','heightmapMismatches'])
      assert.equal(fast[key],reference[key],name+' '+key);
    assert.equal(fast.spawnClear,!!reference.spawnClear,name+' spawnClear');
    // One tiny chunk preserves the legacy global x,z,y digest traversal exactly.
    assert.equal(fast.expectedBlockSha256,reference.expectedBlockSha256,name+' expected expanded digest');
    assert.equal(fast.actualBlockSha256,reference.actualBlockSha256,name+' actual expanded digest');
    if(!fast.missingColumns)for(const key of ['sameLayerConflictingCells','occludedLowerLayerConflicts'])assert.equal(fast[key],reference[key],name+' '+key);
  }
  reports.push({case:name,status:fast.status,referenceStatus:reference.status,mismatchedCells:fast.mismatchedCells,
    highestLayerConflicts:fast.sameLayerConflictingCells,occludedConflicts:fast.occludedLowerLayerConflicts,elapsedMs:fast.elapsedMs,peakRssBytes:fast.peakRssBytes});
  return fast;
}
try {
  const pass=await check('base-air-carve-and-hidden-conflict');
  assert.equal(pass.occludedLowerLayerConflicts,2);assert.equal(pass.sameLayerConflictingCells,0);
  assert.equal(pass.expectedIntervalSha256,pass.actualIntervalSha256);
  await check('unexpected-high-block',{edits:[{x:-2,z:-2,y:300,block:'minecraft:quartz_block'}]},false);
  await check('visible-conflict',{source:[...runs,{...base,yMin:2,yMax:3,block:'minecraft:glass'}]},false);
  await check('heightmap-corruption',{badHeightmap:true},false);
  await check('wrong-global-chunk',{badChunk:true},false);
  await check('missing-modern-world-settings',{missingModern:true},false);
  const propertyRun={...base,x:-3,z:-3,yMin:20,yMax:21,block:'minecraft:oak_log',properties:{axis:'x'}};
  await check('exact-properties',{source:[...runs,propertyRun],edits:[{x:-3,z:-3,y:20,block:'minecraft:oak_log',properties:{axis:'x'}}]});
  await check('wrong-properties',{source:[...runs,propertyRun],edits:[{x:-3,z:-3,y:20,block:'minecraft:oak_log',properties:{axis:'z'}}]},false);
  await check('bad-off-window-record',{source:[...runs,{...base,x:30000,layer:undefined}]},false);
  await check('palette-index-overflow',{oversizedPalette:true,skipScalar:true},false);
  const boundaryRuns=[{...base,x:-2,z:-2,yMin:-64,yMax:-63,block:'minecraft:quartz_block'},{...base,x:-1,z:-1,yMin:319,yMax:320,block:'minecraft:quartz_block'}];
  await check('inclusive-min-exclusive-max',{source:[...runs,...boundaryRuns],edits:[{x:-2,z:-2,y:-64,block:'minecraft:quartz_block'},{x:-1,z:-1,y:319,block:'minecraft:quartz_block'}]});
  await check('above-build-volume',{source:[...runs,{...base,yMin:320,yMax:321}]},false);
  await check('below-build-volume',{source:[...runs,{...base,yMin:-65,yMax:-64}]},false);
  await check('unsupported-numeric-layer',{source:[...runs,{...base,layer:51}]},false);
  const pool=new StatePool(),column=resolveColumn([{lo:1,hi:5,layer:50,id:pool.id('minecraft:stone')},{lo:2,hi:3,layer:60,id:pool.air}],pool);
  assert.equal(compareSpans(column.spans,column.spans).mismatches,0);
  const result={status:'PASS',tests:reports.length,scope:'Tiny 16x16 worlds only; exact scalar equivalence and negative controls',reports};
  if(evidencePath)fs.writeFileSync(evidencePath,JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify(result,null,2));
} finally {
  const resolved=fs.realpathSync(root),allowed=fs.realpathSync(folder)+path.sep+'validate-world-fast-fixture-';
  assert(resolved.startsWith(allowed)&&path.dirname(resolved)===fs.realpathSync(folder),'Fixture cleanup must stay within owned tool prefix');
  fs.rmSync(resolved,{recursive:true,force:false});
}
