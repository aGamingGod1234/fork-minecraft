import fs from 'node:fs';import crypto from 'node:crypto';import assert from 'node:assert/strict';
const dir=process.argv[3]??'assets/fork-world/artifacts/expand-1306',rawPath=process.argv[2]??dir+'/singapore-1024.osm',text=fs.readFileSync(rawPath,'utf8');fs.mkdirSync(dir,{recursive:true});
const unescape=s=>s.replace(/&quot;/g,'"').replace(/&apos;/g,"'").replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&#(x[0-9a-f]+|\d+);/gi,(_,v)=>String.fromCodePoint(v[0]==='x'?parseInt(v.slice(1),16):+v)).replace(/&amp;/g,'&');
const attrs=s=>Object.fromEntries([...s.matchAll(/([\w:]+)="([^"]*)"/g)].map(m=>[m[1],unescape(m[2])]));
const elements=[];
for(const m of text.matchAll(/<(node|way|relation)\b([^>]*?)(?:\/>|>([\s\S]*?)<\/\1>)/g)){
 const type=m[1],a=attrs(m[2]),body=m[3]??'',e={type,id:+a.id};assert(Number.isSafeInteger(e.id));
 if(type==='node'){e.lat=+a.lat;e.lon=+a.lon;assert(Number.isFinite(e.lat)&&Number.isFinite(e.lon));}
 if(type==='way')e.nodes=[...body.matchAll(/<nd\b([^>]*?)\/>/g)].map(n=>+attrs(n[1]).ref);
 if(type==='relation')e.members=[...body.matchAll(/<member\b([^>]*?)\/>/g)].map(n=>{const v=attrs(n[1]);return {type:v.type,ref:+v.ref,role:v.role??''};});
 const tags=Object.fromEntries([...body.matchAll(/<tag\b([^>]*?)\/>/g)].map(t=>{const v=attrs(t[1]);return[v.k,v.v];}));if(Object.keys(tags).length)e.tags=tags;elements.push(e);
}
const ids=new Set(elements.map(e=>e.type+'/'+e.id));assert(elements.length>0);assert.equal(ids.size,elements.length);
for(const e of elements.filter(e=>e.type==='way'))for(const ref of e.nodes)assert(ids.has('node/'+ref),'Missing way node '+ref);
const exclusions=[],render=[];for(const e of elements){if(e.type!=='relation'){render.push(e);continue;}const relevant=['building','multipolygon'].includes(e.tags?.type),missing=e.members.filter(m=>!ids.has(m.type+'/'+m.ref));if(!relevant||missing.length)exclusions.push({id:e.id,tags:e.tags,reason:!relevant?'Non-rendered relation type':'Incomplete rendered relation: retain source and report hole',missing});else render.push(e);}
const data={version:0.6,generator:'FORK reference-complete OSM extraction',elements:render};fs.writeFileSync(dir+'/render-complete.json',JSON.stringify(data));fs.writeFileSync(dir+'/source-exclusions.json',JSON.stringify(exclusions,null,2));
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');const report={status:'ALL retained way and relation references complete',rawSha256:hash(rawPath),renderSha256:hash(dir+'/render-complete.json'),counts:Object.fromEntries(['node','way','relation'].map(t=>[t,render.filter(e=>e.type===t).length])),excludedRelations:exclusions.length,incompleteRenderedRelations:exclusions.filter(e=>e.reason.startsWith('Incomplete')).map(e=>e.id)};fs.writeFileSync(dir+'/source-validation.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));
