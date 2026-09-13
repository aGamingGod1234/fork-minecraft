import fs from 'node:fs';
import path from 'node:path';
import {project} from './validate-fidelity.mjs';
const [ap,bp,out]=process.argv.slice(2);if(!ap||!bp||!out)throw Error('Usage: validate-seam-source.mjs left-manifest right-manifest output');
function read(p){const m=JSON.parse(fs.readFileSync(p));const r=m.generator.args[m.generator.args.indexOf('--file')+1],src=JSON.parse(fs.readFileSync(path.join(path.dirname(r),'source.json'))),render=JSON.parse(fs.readFileSync(r));return {m,src:new Map(src.elements.map(e=>[e.type+'/'+e.id,e])),render:new Map(render.elements.map(e=>[e.type+'/'+e.id,e]))};}
const a=read(ap),b=read(bp),x0=Math.max(a.m.tile.renderOrigin[0],b.m.tile.renderOrigin[0]),x1=Math.min(a.m.tile.renderOrigin[0]+a.m.tile.renderSize,b.m.tile.renderOrigin[0]+b.m.tile.renderSize),z0=Math.max(a.m.tile.coreOrigin[1],b.m.tile.coreOrigin[1]),z1=Math.min(a.m.tile.coreOrigin[1]+a.m.tile.coreSize,b.m.tile.coreOrigin[1]+b.m.tile.coreSize);
function actual(n,t){const [s,w,nn,e]=t.m.mapping.renderBbox,size=t.m.tile.renderSize;return [Math.trunc((n.lon-w)/(e-w)*size)+t.m.tile.renderOrigin[0],Math.trunc((nn-n.lat)/(nn-s)*size)+t.m.tile.renderOrigin[1]];}
function contains(poly,x,z){let inside=false;for(let i=0,j=poly.length-1;i<poly.length;j=i++){const p=poly[i],q=poly[j];if(((p[1]>z)!==(q[1]>z))&&(x<(q[0]-p[0])*(z-p[1])/(q[1]-p[1])+p[0]))inside=!inside;}return inside;}
let independentlyCheckedNodes=0; const expectedPositionDifferences=[]; for(const t of [a,b])for(const [id,e]of t.src){if(e.type!=='node')continue;independentlyCheckedNodes++;const expected=project(e.lon,e.lat,t.m.grid).map(Math.round),rendered=actual(t.render.get(id),t);if(expected.some((v,i)=>v!==rendered[i]))expectedPositionDifferences.push({id,tile:t.m.id,expected,rendered});}
const shared=[],sourceDifferences=[],positionDifferences=[],buildings=[],unsharedOverlap=[];
for(const [id,e]of a.src){
  if(b.src.has(id)){shared.push(id);if(JSON.stringify(e)!==JSON.stringify(b.src.get(id)))sourceDifferences.push(id);if(e.type==='node'){const p=actual(a.render.get(id),a),q=actual(b.render.get(id),b);if(p[0]!==q[0]||p[1]!==q[1])positionDifferences.push({id,left:p,right:q});}}
}
for(const t of [a,b])for(const [id,e]of t.src){if(e.type!=='way')continue;const p=e.nodes.map(n=>t.src.get('node/'+n)).filter(Boolean).map(n=>project(n.lon,n.lat,t.m.grid).map(Math.round));if(!p.length)continue;
 const bbox=[Math.min(...p.map(p=>p[0])),Math.min(...p.map(p=>p[1])),Math.max(...p.map(p=>p[0])),Math.max(...p.map(p=>p[1]))],overlap=bbox[0]<x1&&bbox[2]>=x0&&bbox[1]<z1&&bbox[3]>=z0;
 if(overlap&&!(t===a?b:a).src.has(id))unsharedOverlap.push({tile:t.m.id,id,tags:e.tags,bbox});
 if(t===a&&overlap&&(e.tags?.building||e.tags?.['building:part']))buildings.push({id,bbox,shared:b.src.has(id),tags:e.tags,containsFirstMismatch:contains(p,x0+.1,z0+.1),crossesRenderBoundary:bbox[0]<t.m.tile.renderOrigin[0]||bbox[1]<t.m.tile.renderOrigin[1]||bbox[2]>t.m.tile.renderOrigin[0]+t.m.tile.renderSize||bbox[3]>t.m.tile.renderOrigin[1]+t.m.tile.renderSize});
}
const report={status:sourceDifferences.length||positionDifferences.length||unsharedOverlap.length||expectedPositionDifferences.length?'MISMATCH':'SOURCE_AND_NODE_ALIGNMENT_PASS',scope:'Source equality and quantized point agreement only; actual block seam remains FAILED until separately verified',sharedElements:shared.length,independentlyCheckedNodes,expectedPositionDifferences,sourceDifferences,positionDifferences,unsharedOverlap,overlapBounds:[x0,z0,x1,z1],buildings,createdUtc:new Date().toISOString()};fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));
