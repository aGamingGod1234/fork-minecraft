import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath,pathToFileURL} from 'node:url';

const key=e=>`${e.type}/${e.id}`;
const positive=v=>v!==undefined&&v!==null&&!['no','0','false'].includes(String(v));
const building=e=>positive(e.tags?.building)||positive(e.tags?.['building:part']);
const part=e=>positive(e.tags?.['building:part']);
const area=r=>r.slice(1).reduce((sum,p,i)=>sum+r[i][0]*p[1]-p[0]*r[i][1],0)/2;
const orient=(r,outer)=>area(r)*(outer?1:-1)<0?[...r].reverse():r;
const pointIn=(p,r)=>{let hit=false;for(let i=0,j=r.length-1;i<r.length;j=i++){
 const a=r[j],b=r[i],cross=(p[0]-a[0])*(b[1]-a[1])-(p[1]-a[1])*(b[0]-a[0]);
 if(Math.abs(cross)<1e-7&&p[0]>=Math.min(a[0],b[0])-1e-7&&p[0]<=Math.max(a[0],b[0])+1e-7&&p[1]>=Math.min(a[1],b[1])-1e-7&&p[1]<=Math.max(a[1],b[1])+1e-7)return true;
 if((a[1]>p[1])!==(b[1]>p[1])&&p[0]<(b[0]-a[0])*(p[1]-a[1])/(b[1]-a[1])+a[0])hit=!hit;
 }return hit;};
const polys=g=>g.type==='Polygon'?[g.coordinates]:g.coordinates;
const points=g=>polys(g).flatMap(p=>p.flat());
const bounds=g=>{const ps=points(g);return ps.reduce((b,p)=>[Math.min(b[0],p[0]),Math.min(b[1],p[1]),Math.max(b[2],p[0]),Math.max(b[3],p[1])],[Infinity,Infinity,-Infinity,-Infinity]);};
const contains=(g,p)=>polys(g).some(poly=>pointIn(p,poly[0])&&!poly.slice(1).some(h=>pointIn(p,h)));

// Endpoint IDs, not floating-point coordinate matches, determine ring topology.
export function assembleRings(segments){
 const remaining=segments.map(s=>({id:s.id,nodes:[...s.nodes]})).sort((a,b)=>String(a.id).localeCompare(String(b.id)));
 if(remaining.some(s=>s.nodes.length<2))throw Error('way has fewer than two nodes');
 const degree=new Map();for(const s of remaining)if(s.nodes[0]!==s.nodes.at(-1))for(const n of [s.nodes[0],s.nodes.at(-1)])degree.set(n,(degree.get(n)??0)+1);
 if([...degree.values()].some(v=>v!==2))throw Error('unclosed or ambiguous multipolygon endpoint topology');
 const rings=[];
 while(remaining.length){const first=remaining.shift(),ring=[...first.nodes];
  while(ring[0]!==ring.at(-1)){const endpoint=ring.at(-1),i=remaining.findIndex(s=>s.nodes[0]===endpoint||s.nodes.at(-1)===endpoint);if(i<0)throw Error('unclosed multipolygon ring');const next=remaining.splice(i,1)[0].nodes;if(next[0]!==endpoint)next.reverse();ring.push(...next.slice(1));}
  if(ring.length<4||new Set(ring.slice(0,-1)).size<3)throw Error('degenerate polygon ring');rings.push(ring);
 }return rings;
}

export function projectBuildings(data,{project}={}){
 if(!Array.isArray(data?.elements)||typeof project!=='function')throw Error('Overpass elements and project(lon,lat) are required');
 const byId=new Map(data.elements.map(e=>[key(e),e])),nodes=new Map(data.elements.filter(e=>e.type==='node').map(e=>[e.id,e]));
 const exclusions=[],notes=[],features=new Map(),suppressed=new Set(),relationGeometry=new Map();
 const exclude=(e,reason,details={})=>exclusions.push({id:key(e),reason,...details});
 const wayNodes=w=>{if(!Array.isArray(w.nodes)||w.nodes.length<2)throw Error('missing node references');const missing=w.nodes.filter(id=>!nodes.has(id));if(missing.length)throw Error('missing nodes: '+missing.join(','));return w.nodes;};
 const projectRing=ids=>{const ring=ids.map(id=>{const n=nodes.get(id);if(!n||!Number.isFinite(n.lon)||!Number.isFinite(n.lat))throw Error('missing or invalid coordinate for node '+id);const p=project(n.lon,n.lat);if(!Array.isArray(p)||p.length!==2||!p.every(Number.isFinite))throw Error('projection returned invalid coordinates');return p;});if(Math.abs(area(ring))<1e-8)throw Error('zero-area polygon ring');return ring;};
 const feature=(e,geometry,extra={})=>({type:'Feature',id:key(e),properties:{...(e.tags??{}),tags:{...(e.tags??{})},featureid:key(e),id:key(e),osmType:e.type,osmId:e.id,sourceClass:'osm-mapped',geometryStatus:'complete',buildingRole:part(e)?'part':'outline',...extra},geometry});
 function relationRings(r,stack=new Set()){
  if(relationGeometry.has(r.id))return relationGeometry.get(r.id);
  if(stack.has(r.id))throw Error('cyclic multipolygon relation');stack.add(r.id);
  const groups={outer:[],inner:[]},nested={outer:[],inner:[]},memberWayIds=[];
  for(const m of r.members??[]){
   if(m.type==='node'&&['label','admin_centre'].includes(m.role))continue;
   const role=m.role||'outer';if(!['outer','inner'].includes(role))throw Error('unsupported multipolygon member role '+role);
   const e=byId.get(`${m.type}/${m.ref}`);if(!e)throw Error('missing member '+m.type+'/'+m.ref);
   if(m.type==='way'){groups[role].push({id:e.id,nodes:wayNodes(e)});memberWayIds.push(key(e));}
   else if(m.type==='relation'&&e.tags?.type==='multipolygon'){const sub=relationRings(e,new Set(stack));nested[role].push(...sub.outer);nested[role==='outer'?'inner':'outer'].push(...sub.inner);memberWayIds.push(...sub.memberWayIds);}
   else throw Error('unsupported geometry member '+m.type+'/'+m.ref);
  }
  const result={outer:[...assembleRings(groups.outer).map(projectRing),...nested.outer],inner:[...assembleRings(groups.inner).map(projectRing),...nested.inner],memberWayIds:[...new Set(memberWayIds)]};
  if(!result.outer.length)throw Error('multipolygon has no complete outer ring');relationGeometry.set(r.id,result);return result;
 }
 for(const r of data.elements.filter(e=>e.type==='relation'&&e.tags?.type==='multipolygon'&&building(e))){try{
  const rings=relationRings(r),polygons=rings.outer.map(ring=>[orient(ring,true)]);
  for(const hole of rings.inner){const candidates=polygons.map((poly,index)=>({index,ring:poly[0],area:Math.abs(area(poly[0]))})).filter(p=>hole.slice(0,-1).every(pt=>pointIn(pt,p.ring))).sort((a,b)=>a.area-b.area);if(!candidates.length)throw Error('inner ring has no containing outer');polygons[candidates[0].index].push(orient(hole,false));}
  const geometry=polygons.length===1?{type:'Polygon',coordinates:polygons[0]}:{type:'MultiPolygon',coordinates:polygons};
  features.set(key(r),feature(r,geometry,{memberWayIds:rings.memberWayIds}));for(const id of rings.memberWayIds)suppressed.add(id);
 }catch(error){exclude(r,error.message);}}
 for(const w of data.elements.filter(e=>e.type==='way'&&building(e))){if(suppressed.has(key(w))){notes.push({id:key(w),reason:'rendered through complete multipolygon relation; duplicate standalone outline suppressed'});continue;}try{
  const ids=wayNodes(w);if(ids[0]!==ids.at(-1))throw Error('building way is not closed');const ring=assembleRings([{id:w.id,nodes:ids}])[0];features.set(key(w),feature(w,{type:'Polygon',coordinates:[orient(projectRing(ring),true)]}));
 }catch(error){exclude(w,error.message);}}
 // Explicit OSM building groups attach parts to the actual outline feature ID.
 for(const r of data.elements.filter(e=>e.type==='relation'&&e.tags?.type==='building')){
  const members=r.members??[],outlines=members.filter(m=>m.role==='outline').map(m=>`${m.type}/${m.ref}`).filter(id=>features.has(id));
  const missing=members.filter(m=>!byId.has(`${m.type}/${m.ref}`));if(missing.length)exclude(r,'building group has missing members',{missingMemberIds:missing.map(m=>`${m.type}/${m.ref}`)});
  if(outlines.length!==1){notes.push({id:key(r),reason:'building group does not identify exactly one available outline',outlineIds:outlines});continue;}
  const outline=features.get(outlines[0]);outline.properties.buildingGroupIds=[...(outline.properties.buildingGroupIds??[]),key(r)];
  for(const m of members){const f=features.get(`${m.type}/${m.ref}`);if(f&&(m.role==='part'||f.properties.buildingRole==='part')){f.properties.parent_identity=outlines[0];f.properties.parentRelationshipSource='osm-building-relation';f.properties.buildingGroupIds=[...(f.properties.buildingGroupIds??[]),key(r)];}}
 }
 // OSM Simple 3D parts commonly have no relation: infer only full containment,
 // record that inference, and use the smallest containing mapped outline.
 const outlines=[...features.values()].filter(f=>f.properties.buildingRole==='outline').map(f=>({f,b:bounds(f.geometry),area:polys(f.geometry).reduce((a,p)=>a+Math.abs(area(p[0])),0)}));
 for(const f of features.values())if(f.properties.buildingRole==='part'&&!f.properties.parent_identity){const b= bounds(f.geometry),ps=points(f.geometry),candidates=outlines.filter(p=>p.b[0]<=b[0]&&p.b[1]<=b[1]&&p.b[2]>=b[2]&&p.b[3]>=b[3]&&ps.every(pt=>contains(p.f.geometry,pt))).sort((a,b)=>a.area-b.area||a.f.id.localeCompare(b.f.id));if(candidates.length){f.properties.parent_identity=candidates[0].f.id;f.properties.parentRelationshipSource='complete-footprint-containment';}else{f.properties.parentRelationshipSource='unlinked';notes.push({id:f.id,reason:'standalone part: no complete containing outline'});}}
 for(const f of features.values())if(f.properties.parent_identity){const parent=features.get(f.properties.parent_identity);if(parent){parent.properties.partFeatureIds=[...(parent.properties.partFeatureIds??[]),f.id];parent.properties.renderOutline=false;parent.properties.outlineSuppressionReason='mapped building parts supersede outline shell';}}
 const collection={type:'FeatureCollection',name:'FORK complete OSM buildings in global Minecraft XZ',coordinateSystem:{source:'EPSG:4326',projection:'EPSG:3414',axes:['x=easting_metres','z=60000-northing_metres'],units:'metres',blocksPerMetre:1},features:[...features.values()].sort((a,b)=>a.id.localeCompare(b.id))};
 return {collection,manifest:{schemaVersion:1,sourceClass:'osm-mapped',coordinates:collection.coordinateSystem,sourceElements:data.elements.length,featureCount:collection.features.length,parts:collection.features.filter(f=>f.properties.buildingRole==='part').length,excludedCount:exclusions.length,exclusions,notes,sourceCoordinatesModified:false,geometryClipped:false}};
}

export async function main(argv){const args={};for(let i=0;i<argv.length;i+=2){if(!argv[i]?.startsWith('--')||argv[i+1]===undefined)throw Error('Expected --source --output --manifest');args[argv[i].slice(2)]=argv[i+1];}if(!args.source||!args.output||!args.manifest)throw Error('--source, --output and --manifest required');
 const {project}=await import('./pipeline.mjs');const raw=fs.readFileSync(args.source),result=projectBuildings(JSON.parse(raw),{project});result.manifest.sourceSha256=crypto.createHash('sha256').update(raw).digest('hex');result.manifest.sourceFile=path.resolve(args.source);
 for(const [file,value] of [[args.output,result.collection],[args.manifest,result.manifest]]){fs.mkdirSync(path.dirname(path.resolve(file)),{recursive:true});fs.writeFileSync(file,JSON.stringify(value,null,2)+'\n');}console.log(JSON.stringify({output:path.resolve(args.output),manifest:path.resolve(args.manifest),features:result.manifest.featureCount,exclusions:result.manifest.excludedCount}));
}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href)main(process.argv.slice(2)).catch(e=>{console.error(e.message);process.exitCode=1;});
