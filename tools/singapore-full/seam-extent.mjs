// Pure, fail-closed building extent selection. project is pipeline.project (SVY21).
// Input elements and coordinates are never mutated. Bounds: [minX,minZ,maxX,maxZ].
export function completeBuildingExtent(data,{core,halo=128,maxExtent=4096,padding=2,project}={}) {
  if(!Array.isArray(data?.elements)||typeof project!=='function')throw Error('Complete Overpass data and project required');
  if(!Array.isArray(core)||core.length!==4||!core.every(Number.isFinite)||core[0]>=core[2]||core[1]>=core[3])throw Error('Invalid core bounds');
  if(!Number.isFinite(halo)||halo<0||!Number.isFinite(maxExtent)||maxExtent<=0||!Number.isFinite(padding)||padding<0)throw Error('Invalid extent limits');
  const key=e=>`${e.type}/${e.id}`, all=new Map(data.elements.map(e=>[key(e),e]));
  const nodes=new Map(data.elements.filter(e=>e.type==='node').map(e=>[e.id,e]));
  const ways=new Map(), excludedMissingDetails=[], features=new Map();
  const yes=v=>v!==undefined&&v!=='no'&&v!=='0';
  const isBuilding=e=>yes(e.tags?.building)||yes(e.tags?.['building:part'])||e.tags?.type==='building';
  const bounds=pts=>[Math.min(...pts.map(p=>p[0])),Math.min(...pts.map(p=>p[1])),Math.max(...pts.map(p=>p[0])),Math.max(...pts.map(p=>p[1]))];
  const overlaps=(a,b)=>a[0]<=b[2]&&a[2]>=b[0]&&a[1]<=b[3]&&a[3]>=b[1];
  const inRect=(p,b)=>p[0]>=b[0]&&p[0]<=b[2]&&p[1]>=b[1]&&p[1]<=b[3];
  const segmentRect=(a,b,r)=>{let lo=0,hi=1;for(let d=0;d<2;d++){const v=b[d]-a[d];if(Math.abs(v)<1e-10){if(a[d]<r[d]||a[d]>r[d+2])return false;}else{let l=(r[d]-a[d])/v,h=(r[d+2]-a[d])/v;if(l>h)[l,h]=[h,l];lo=Math.max(lo,l);hi=Math.min(hi,h);if(lo>hi)return false;}}return true;};
  const pointIn=(p,f)=>{let inside=false;for(const [a,b] of f.segments){if(segmentRect(a,b,[p[0],p[1],p[0],p[1]]))return true;if((a[1]>p[1])!==(b[1]>p[1])&&p[0]<(b[0]-a[0])*(p[1]-a[1])/(b[1]-a[1])+a[0])inside=!inside;}return inside;};
  const intersects=(f,r)=>overlaps(f.bounds,r)&&(f.points.some(p=>inRect(p,r))||f.segments.some(([a,b])=>segmentRect(a,b,r))||[[r[0],r[1]],[r[2],r[1]],[r[2],r[3]],[r[0],r[3]]].some(p=>pointIn(p,f)));
  for(const e of data.elements.filter(e=>e.type==='way')){
    let coords,missing=[];
    if(Array.isArray(e.nodes)&&e.nodes.length){missing=e.nodes.filter(id=>!nodes.has(id));if(!missing.length)coords=e.nodes.map(id=>nodes.get(id));}
    else if(Array.isArray(e.geometry)&&e.geometry.length)coords=e.geometry;
    if(!coords||coords.some(p=>!Number.isFinite(p.lon)||!Number.isFinite(p.lat))){if(isBuilding(e))excludedMissingDetails.push({id:key(e),reason:'incomplete geometry',missingNodeIds:missing});continue;}
    const points=coords.map(p=>project(p.lon,p.lat));
    const segments=points.slice(1).map((p,i)=>[points[i],p]);
    const closed=points.length>=4&&Math.abs(points[0][0]-points.at(-1)[0])<1e-7&&Math.abs(points[0][1]-points.at(-1)[1])<1e-7;
    const f={id:key(e),element:e,points,segments,bounds:bounds(points),wayIds:[key(e)],closed};ways.set(e.id,f);
    if(isBuilding(e)){if(closed)features.set(f.id,f);else excludedMissingDetails.push({id:f.id,reason:'building way is not a complete closed polygon'});}
  }
  // Only building relations may expand a group. Route/site/admin relations do not.
  const relations=data.elements.filter(e=>e.type==='relation'&&isBuilding(e)&&['building','multipolygon'].includes(e.tags?.type));
  const relationById=new Map(relations.map(e=>[e.id,e])), resolving=new Set();
  function relationFeature(r){
    if(features.has(key(r)))return features.get(key(r));
    if(resolving.has(r.id))return null;resolving.add(r.id);
    const parts=[],missing=[];
    for(const m of r.members??[]){if(m.type==='node'&&['label','admin_centre','entrance'].includes(m.role))continue;const f=m.type==='way'?ways.get(m.ref):m.type==='relation'&&relationById.has(m.ref)?relationFeature(relationById.get(m.ref)):null;if(f)parts.push(f);else missing.push(`${m.type}/${m.ref}`);}
    resolving.delete(r.id);
    if(missing.length||!parts.length){excludedMissingDetails.push({id:key(r),reason:'incomplete building relation',missingMemberIds:missing});return null;}
    const points=parts.flatMap(f=>f.points),segments=parts.flatMap(f=>f.segments),wayIds=[...new Set(parts.flatMap(f=>f.wayIds))];
    // Multipolygon linework must close even when individual member ways are open.
    if(r.tags.type==='multipolygon'){const ends=new Map();for(const w of wayIds.map(id=>ways.get(Number(id.slice(4))))){for(const p of [w.points[0],w.points.at(-1)]){const k=p.join(',');ends.set(k,(ends.get(k)??0)+1);}}if([...ends.values()].some(n=>n%2)){excludedMissingDetails.push({id:key(r),reason:'unclosed multipolygon member linework'});return null;}}
    const f={id:key(r),element:r,points,segments,bounds:bounds(points),wayIds,closed:true};features.set(f.id,f);return f;
  }
  for(const r of relations)relationFeature(r);
  const selected=new Set([...features.values()].filter(f=>intersects(f,core)).map(f=>f.id));
  let changed=true;
  while(changed){changed=false;const add=id=>{if(!selected.has(id)){selected.add(id);changed=true;}};
    for(const r of relations){const f=features.get(key(r));if(!f)continue;if(selected.has(f.id)||f.wayIds.some(id=>selected.has(id))){add(f.id);for(const id of f.wayIds)add(id);}}
    // Untyped parent/part grouping: include the containing outline and all sibling
    // parts fully inside it. This closure is deliberately restricted to buildings.
    for(const parent of features.values()){
      if(!yes(parent.element.tags?.building)||yes(parent.element.tags?.['building:part']))continue;
      const children=[...features.values()].filter(f=>f.id!==parent.id&&yes(f.element.tags?.['building:part'])&&overlaps(f.bounds,parent.bounds)&&f.points.every(p=>pointIn(p,parent)));
      if(selected.has(parent.id)||children.some(f=>selected.has(f.id))){add(parent.id);for(const child of children)add(child.id);}
    }
  }
  const chosenFeatures=[...selected].map(id=>features.get(id)??ways.get(Number(id.slice(4)))).filter(Boolean);
  const required=core.map((v,i)=>v+(i<2?-halo:halo));
  for(const f of chosenFeatures){required[0]=Math.min(required[0],f.bounds[0]-padding);required[1]=Math.min(required[1],f.bounds[1]-padding);required[2]=Math.max(required[2],f.bounds[2]+padding);required[3]=Math.max(required[3],f.bounds[3]+padding);}
  const requiredGlobalBbox=[Math.floor(required[0]/16)*16,Math.floor(required[1]/16)*16,Math.ceil(required[2]/16)*16,Math.ceil(required[3]/16)*16];
  const span=[requiredGlobalBbox[2]-requiredGlobalBbox[0],requiredGlobalBbox[3]-requiredGlobalBbox[1]],renderSize=Math.max(...span);
  const bounded=renderSize<=maxExtent;
  const ids=new Set(selected);for(const f of chosenFeatures)for(const id of f.wayIds){ids.add(id);for(const n of all.get(id)?.nodes??[])ids.add(`node/${n}`);}
  return {selectedIds:[...ids].sort(),selectedBuildingIds:[...selected].sort(),requiredGlobalBbox,generationExtent:bounded?{origin:requiredGlobalBbox.slice(0,2),size:renderSize,bbox:[requiredGlobalBbox[0],requiredGlobalBbox[1],requiredGlobalBbox[0]+renderSize,requiredGlobalBbox[1]+renderSize]}:null,boundedStatus:bounded?'within-cap':'extent-cap-exceeded',bounded,maxExtent,excludedMissingDetails,featureBounds:chosenFeatures.map(f=>({id:f.id,bounds:f.bounds})),sourceUnmodified:true};
}
