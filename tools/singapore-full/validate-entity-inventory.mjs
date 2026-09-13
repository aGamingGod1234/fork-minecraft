import fs from 'node:fs';import path from 'node:path';
import {readRegion} from '../fork-world/nbt-region.mjs';
const [mp,out]=process.argv.slice(2);if(!out)throw Error('Usage: validate-entity-inventory.mjs tile-manifest output.json');
const m=JSON.parse(fs.readFileSync(mp)),world=path.resolve(path.dirname(mp),m.output.worldPath),counts={},samples={};
function simple(v){if(v&&typeof v==='object'&&'value'in v)return simple(v.value);if(Array.isArray(v))return v.map(simple);if(v&&typeof v==='object')return Object.fromEntries(Object.entries(v).map(([k,v])=>[k,simple(v)]));return typeof v==='bigint'?String(v):v;}
function inspect(v,at){if(!v||typeof v!=='object')return;
 const id=v.id?.value;if(typeof id==='string'&&(v.Pos||v.x||v.TileX||v.block_pos)){counts[id]=(counts[id]??0)+1;if(!samples[id])samples[id]={nbtPath:at,record:simple(v)};return;}
 if(Array.isArray(v)){for(let i=0;i<v.length;i++)inspect(v[i],at+'['+i+']');return;}
 if('value'in v){inspect(v.value,at);return;}
 for(const[k,x]of Object.entries(v)){if(['sections','Heightmaps','PostProcessing','block_ticks','fluid_ticks'].includes(k))continue;inspect(x,at+'.'+k);}
}
for(const f of m.output.files.filter(f=>/^region\/.*\.mca$/.test(f.path)))for(const c of readRegion(path.join(world,f.path)))inspect(c.root.value,f.path+'/'+c.root.value.xPos.value+','+c.root.value.zPos.value);
const report={tile:m.id,coordinateFrame:m.output.coordinateFrame,renderOrigin:m.tile.renderOrigin,counts,samples,createdUtc:new Date().toISOString()};fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));
