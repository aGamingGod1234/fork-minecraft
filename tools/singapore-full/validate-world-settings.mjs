import fs from 'node:fs';import path from 'node:path';import zlib from 'node:zlib';import crypto from 'node:crypto';import {fileURLToPath} from 'node:url';import {decode} from '../fork-world/nbt-region.mjs';
export function validateWorldSettings(world){
 const errors=[],files=[],read=p=>{const file=path.join(world,p);files.push({path:p,sha256:crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'),bytes:fs.statSync(file).size});return decode(zlib.gunzipSync(fs.readFileSync(file))).value;};
 const root=read('level.dat'),data=root.Data?.value;if(!data)throw Error('level.dat has no Data compound');
 const dataVersion=data.DataVersion?.value,modern=data.spawn?.value,legacy=[data.SpawnX?.value,data.SpawnY?.value,data.SpawnZ?.value],spawn=modern?.pos?.value??legacy,spawnSchema=modern?'modern Data.spawn.pos':'legacy SpawnX/Y/Z';
 if(!Array.isArray(spawn)||spawn.length!==3||!spawn.every(Number.isInteger))errors.push('Invalid spawn coordinates');
 if(dataVersion>=4790&&!modern)errors.push('MC26 requires an explicit modern Data.spawn compound');
 if(modern&&modern.dimension?.value!=='minecraft:overworld')errors.push('Modern spawn dimension is not minecraft:overworld');
 let externalSettings=null;
 if(dataVersion>=4790){
  const p='data/minecraft/world_gen_settings.dat';
  if(!fs.existsSync(path.join(world,p)))errors.push('Missing required MC26 external world generation settings');
  else{
   const settings=read(p),payload=settings.data?.value;
   externalSettings={dataVersion:settings.DataVersion?.value,generateStructures:payload?.generate_structures?.value};
   if(!payload)errors.push('External world settings have no data compound');
   if(settings.DataVersion?.value!==dataVersion)errors.push('External world settings DataVersion mismatch');
   if(payload?.generate_structures?.value!==0)errors.push('Bounded preview must explicitly disable generate_structures');
  }
 }
 const enabled=data.DataPacks?.value.Enabled?.value.items??[];
 if(enabled.some(p=>p!=='vanilla'))errors.push('Level template retains non-vanilla enabled datapacks');
 if(data.Player)errors.push('Source Player state was retained');
 const regionDirectory=dataVersion>=4790?'dimensions/minecraft/overworld/region':'region';
 if(!fs.existsSync(path.join(world,regionDirectory)))errors.push('Missing version-appropriate overworld region directory: '+regionDirectory);
 if(Array.isArray(spawn)&&spawn.length===3&&spawn.every(Number.isInteger)){
  const [x,,z]=spawn,region=path.join(world,regionDirectory,'r.'+Math.floor(x/512)+'.'+Math.floor(z/512)+'.mca');
  if(!fs.existsSync(region))errors.push('Modern/active spawn is outside generated region files');
  else{const fd=fs.openSync(region,'r'),b=Buffer.alloc(4),cx=Math.floor(x/16),cz=Math.floor(z/16),slot=((cz%32+32)%32)*32+((cx%32+32)%32);try{fs.readSync(fd,b,0,4,slot*4);}finally{fs.closeSync(fd);}if(!(b.readUInt32BE(0)>>>8))errors.push('Active spawn chunk is absent from its region header');}
 }
 return {status:errors.length?'FAIL':'PASS',dataVersion,spawn,spawnSchema,legacySpawn:legacy,externalSettings,regionDirectory,enabledDatapacks:enabled,files,errors};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [world,out]=process.argv.slice(2);if(!world)throw Error('Usage: validate-world-settings.mjs WORLD [OUT]');
 const report=validateWorldSettings(world);if(out)fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));if(report.status==='FAIL')process.exitCode=1;
}
