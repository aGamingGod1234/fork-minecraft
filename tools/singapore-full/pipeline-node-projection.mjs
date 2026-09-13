import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {project, GRID} from './pipeline.mjs';

const args={};
for(let i=2;i<process.argv.length;i+=2){
  if(!process.argv[i].startsWith('--') || process.argv[i+1]===undefined) throw Error('Expected --sourceOverpass <JSON> --output <JSON>');
  args[process.argv[i].slice(2)]=process.argv[i+1];
}
if(!args.sourceOverpass || !args.output) throw Error('--sourceOverpass and --output required');
const input=path.resolve(args.sourceOverpass), output=path.resolve(args.output);
if(input===output || fs.existsSync(output)) throw Error('Output must be a new file');
const raw=fs.readFileSync(input), data=JSON.parse(raw.toString('utf8').replace(/^\uFEFF/,''));
if(!Array.isArray(data.elements)) throw Error('Expected complete Overpass elements');
const unique=new Map();
for(const n of data.elements){
  if(n.type!=='node') continue;
  if(!Number.isFinite(n.lon)||!Number.isFinite(n.lat)) throw Error('Invalid node longitude/latitude '+n.id);
  const key=JSON.stringify([n.lon,n.lat]);
  if(unique.has(key)) continue;
  const [e,z]=project(n.lon,n.lat), north=GRID.originNorthing-z;
  if(!Number.isFinite(e)||!Number.isFinite(north)) throw Error('Invalid projected coordinate '+n.id);
  unique.set(key,[n.lon,n.lat,e,north]);
}
const rows=[...unique.values()].sort((a,b)=>a[0]-b[0]||a[1]-b[1]);
fs.mkdirSync(path.dirname(output),{recursive:true});
fs.writeFileSync(output,JSON.stringify(rows)+'\n',{flag:'wx'});
console.log(JSON.stringify({nodeCoordinatePairs:rows.length, output,
  sourceSha256:crypto.createHash('sha256').update(raw).digest('hex'),
  lookupSha256:crypto.createHash('sha256').update(fs.readFileSync(output)).digest('hex'),
  coordinateOrder:['longitude','latitude','EPSG:3414 easting','EPSG:3414 northing'],rounded:false}));
