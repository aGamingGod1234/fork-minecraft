import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {project} from './validate-fidelity.mjs';
import {readRegion,sections,unpack} from '../fork-world/nbt-region.mjs';
const json=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const building=e=>Object.entries(e.tags??{}).some(([k,v])=>['building','building:part'].includes(k)&&v!=='no');
export function meters(raw,{zero=false,signed=false}={}) {
  if(raw===undefined)return null;
  const s=String(raw).trim(), re=signed?/^[+-]?\d+(?:\.\d+)?\s*m?$/:/^\d+(?:\.\d+)?\s*m?$/;
  if(!re.test(s))return null;
  const n=parseFloat(s);return Number.isFinite(n)&&(signed||n>0||(zero&&n===0))?n:null;
}
export function classify(tags) {
  const key=tags.height!==undefined?'height':tags['building:height']!==undefined?'building:height':null;
  const height=key?meters(tags[key]):null;
  const levels=/^\d+(?:\.\d+)?$/.test(String(tags['building:levels']??''))?Number(tags['building:levels']):null;
  const alt=meters(tags['building:height']),minimum=tags.min_height===undefined?0:meters(tags.min_height,{zero:true});
  const reasons=[];
  if(tags.height!==undefined&&tags['building:height']!==undefined&&(height===null||alt===null||height!==alt))reasons.push('height and building:height disagree');
  if(height!==null&&minimum!==null&&minimum>height)reasons.push('min_height exceeds total height');
  return {category:key?(height===null?'unsupported':'mappedMeters'):levels>0?'inferredLevels':'missing',
    heightKey:key,heightRaw:key?tags[key]:null,heightMeters:height,levels:levels>0?levels:null,
    conflict:reasons.length>0,conflictReasons:reasons,
    alternativeHeightRaw:tags['building:height']??null,
    minimumHeightMeters:minimum,minimumHeightUnsupported:tags.min_height!==undefined&&minimum===null,
    minimumHeightRaw:tags.min_height??null,roofHeightRaw:tags['roof:height']??null,
    elevationRaw:tags.ele??null,elevationUsedAsGround:false};
}
export function rangeRisk(height,groundY,minY=-64,maxY=319) {
  if(!Number.isFinite(height)||!Number.isFinite(groundY))return {status:'unknown',roofDatumY:null};
  const roofDatumY=groundY+height;
  // Block Y is its bottom face; the upper face of the final block is maxY+1.
  return {status:groundY<minY||roofDatumY>maxY+1?'outsideBuildVolume':roofDatumY>maxY?'upperBoundaryConventionUnresolved':'fitsProvisionalDatum',roofDatumY};
}
export function auditSource(data,groundY=0) {
  if(!Array.isArray(data.elements))throw Error('Expected OSM elements array');
  const counts={mappedMeters:0,inferredLevels:0,unsupported:0,missing:0};
  const features=data.elements.filter(building).map(e=>{
    const h=classify(e.tags??{});counts[h.category]++;
    return {id:e.type+'/'+e.id,name:e.tags?.name??null,...h,clippingAtProvisionalGround:rangeRisk(h.heightMeters,groundY)};
  });
  return {elementCount:data.elements.length,buildingElementCount:features.length,heightCounts:counts,
    distinctPhysicalBuildingsKnown:false,sourceClaimsAreSurveyed:false,provisionalGroundY:groundY,
    verticalDatum:'unknown; Minecraft ground Y is an implementation offset, not real altitude',
    terrainElevationTaggedElements:data.elements.filter(e=>e.tags?.ele!==undefined).length,terrainElevationAcceptedCount:0,
    conflicts:features.filter(f=>f.conflict),unsupported:features.filter(f=>f.category==='unsupported'),
    outsideBuildVolume:features.filter(f=>f.clippingAtProvisionalGround.status==='outsideBuildVolume'),
    upperBoundaryConventionUnresolved:features.filter(f=>f.clippingAtProvisionalGround.status==='upperBoundaryConventionUnresolved'),
    tallestMapped:features.filter(f=>f.heightMeters!==null).sort((a,b)=>b.heightMeters-a.heightMeters).slice(0,20),features};
}
export function inside(x,z,polygon) {
  let yes=false;
  for(let i=0,j=polygon.length-1;i<polygon.length;j=i++) {
    const a=polygon[i],b=polygon[j];
    if((a[1]>z)!==(b[1]>z)&&x<(b[0]-a[0])*(z-a[1])/(b[1]-a[1])+a[0])yes=!yes;
  }
  return yes;
}
function footprint(e,nodes,grid) {
  if(e.type!=='way'||!e.nodes||e.nodes.length<4||e.nodes[0]!==e.nodes.at(-1))return null;
  const p=e.nodes.map(id=>nodes.get(id));
  if(p.some(n=>!n||!Number.isFinite(n.lon)||!Number.isFinite(n.lat)))return null;
  return p.map(n=>project(n.lon,n.lat,grid).map(Math.round));
}
const air=new Set(['minecraft:air','minecraft:cave_air','minecraft:void_air']);
export function measureWorld(worldPath) {
  const top=new Map(),files=[];let minY=null,maxY=null,nonAirBlocks=0,chunks=0;
  const regionPath=path.join(worldPath,'region');
  for(const name of fs.readdirSync(regionPath).filter(n=>/^r\.-?\d+\.-?\d+\.mca$/.test(n)).sort()) {
    const p=path.join(regionPath,name);files.push({path:'region/'+name,bytes:fs.statSync(p).size,sha256:hash(p)});
    for(const c of readRegion(p)) {
      chunks++;const v=c.root.value,cx=v.xPos.value,cz=v.zPos.value;
      for(const s of sections(c)) {
        const u=unpack(s);if(!u)continue;
        const solid=u.pal.map(p=>!air.has(p.Name.value));
        if(!solid.some(Boolean))continue;
        for(let i=0;i<4096;i++) {
          if(!solid[u.blocks[i]])continue;
          const y=s.Y.value*16+(i>>8),x=cx*16+(i&15),z=cz*16+((i>>4)&15),k=x+','+z;
          nonAirBlocks++;minY=minY===null?y:Math.min(minY,y);maxY=maxY===null?y:Math.max(maxY,y);
          if(y>(top.get(k)??-Infinity))top.set(k,y);
        }
      }
    }
  }
  return {top,summary:{minY,maxY,nonAirBlocks,chunks,columns:top.size,files,buildRangeSatisfied:minY!==null&&minY>=-64&&maxY<=319}};
}

export function auditTile(manifestPath,data) {
  const m=json(manifestPath),base=path.dirname(manifestPath);
  if(m.grid?.kind!=='EPSG:3414'||m.grid.blocksPerMeter!==1)throw Error('Requires one-block-per-meter EPSG:3414 manifest');
  const w=measureWorld(path.resolve(base,m.output.worldPath)),nodes=new Map(data.elements.filter(e=>e.type==='node').map(e=>[e.id,e]));
  const [ox,oz]=m.tile.renderOrigin,size=m.tile.renderSize,rows=[],skipped={unsupportedGeometry:0,outsideRenderedTile:0,noInteriorColumns:0};
  for(const e of data.elements.filter(building)) {
    const h=classify(e.tags??{});if(h.category!=='mappedMeters')continue;
    const poly=footprint(e,nodes,m.grid);if(!poly){skipped.unsupportedGeometry++;continue;}
    const xs=poly.map(p=>p[0]),zs=poly.map(p=>p[1]),left=Math.min(...xs),right=Math.max(...xs),north=Math.min(...zs),south=Math.max(...zs);
    if(right<=ox||left>=ox+size||south<=oz||north>=oz+size){skipped.outsideRenderedTile++;continue;}
    const tops=[];let missingColumns=0;
    for(let z=Math.max(oz,north);z<Math.min(oz+size,south);z++)for(let x=Math.max(ox,left);x<Math.min(ox+size,right);x++) {
      // Interior centers avoid assuming that an outline vertex is a rooftop.
      if(!inside(x+.5,z+.5,poly))continue;
      const y=w.top.get((x-ox)+','+(z-oz));if(y===undefined)missingColumns++;else tops.push(y);
    }
    if(!tops.length){skipped.noInteriorColumns++;continue;}
    tops.sort((a,b)=>a-b);
    const risk=rangeRisk(h.heightMeters,m.vertical.groundY),expected=risk.roofDatumY;
    const median=tops[Math.floor(tops.length/2)],min=tops[0],max=tops.at(-1);
    const anyCompatible=tops.some(y=>Math.abs(y-expected)<=1||Math.abs(y+1-expected)<=1);
    rows.push({id:e.type+'/'+e.id,name:e.tags?.name??null,heightMeters:h.heightMeters,heightConflict:h.conflict,
      minHeightMeters:h.minimumHeightMeters,roofHeightRaw:h.roofHeightRaw,
      footprintEntirelyRendered:left>=ox&&right<=ox+size&&north>=oz&&south<=oz+size,
      columns:tops.length,missingColumns,observedTopY:{min,median,max},
      expectedRoofDatumY:expected,medianMinusExpected:median-expected,
      observedWithinOneBlockOfExpectedRoof:anyCompatible,
      rangeRisk:risk.status,possibleClippedAtCeiling:risk.status==='outsideBuildVolume'&&max>=318,
      comparisonAccepted:false,
      qualification:'Column tops may be overlapping parts, vegetation or adjacent geometry; identity and ground datum not verified'});
  }
  return {tileId:m.id,manifestSha256:hash(manifestPath),sourceSha256:m.source.sha256,
    measuredOutput:w.summary,vertical:m.vertical,skipped,footprintComparisons:rows,
    counts:{mappedFootprintsIntersecting:rows.length,fullyRendered:rows.filter(r=>r.footprintEntirelyRendered).length,
      noCompatibleColumn:rows.filter(r=>!r.observedWithinOneBlockOfExpectedRoof).length,
      possibleClippedAtCeiling:rows.filter(r=>r.possibleClippedAtCeiling).length},
    knownBuildingClippingGate:'UNACCEPTED: complete per-feature identity and terrain datum unavailable',
    fullWorldAccepted:false,releaseAccepted:false};
}
export class RunHeightAudit {
  constructor(sourceFeatures=[],groundY=0) {
    this.source=new Map(sourceFeatures.map(f=>[f.id,f]));this.groundY=groundY;
    this.report={contract:'column-runs-v1',runCount:0,sourceClassCounts:{mapped:0,inferred:0,unknown:0},
      invalidRunCount:0,outOfBuildVolumeCount:0,airRunCount:0,errors:[],equalLayerConflictCount:0,
      equalLayerConflicts:[],lowerLayerOcclusionCount:0,minY:null,maxYExclusive:null,mappedBuildingRunsWithoutSupportedHeight:0,
      completeCrossingAudit:true,fullWorldAccepted:false,releaseAccepted:false};
    this.columns=new Map();this.features=new Map();
  }
  add(r,line) {
    const q=this.report;q.runCount++;
    if(r&&typeof r==='object'&&!Array.isArray(r)&&typeof r.layer==='string')r={...r,layer:({terrain:10,landcover:20,water:30,road:40,building:50,bridge:60})[r.layer]};
    const valid=r&&typeof r==='object'&&!Array.isArray(r)&&Number.isSafeInteger(r.x)&&Number.isSafeInteger(r.z)&&Number.isSafeInteger(r.yMin)&&
      Number.isSafeInteger(r.yMax)&&r.yMin<r.yMax&&typeof r.block==='string'&&r.block.length>0&&
      typeof r.featureId==='string'&&r.featureId.length>0&&typeof r.geometryKind==='string'&&r.geometryKind.length>0&&
      (r.properties===undefined||(r.properties!==null&&typeof r.properties==='object'&&!Array.isArray(r.properties)&&Object.values(r.properties).every(v=>typeof v==='string')))&&
      ['mapped','inferred','unknown'].includes(r.sourceClass)&&[10,20,30,40,50,60].includes(r.layer);
    if(!valid){q.invalidRunCount++;if(q.errors.length<100)q.errors.push({line,reason:'Invalid run coordinates, extent or required provenance metadata'});return;}
    q.sourceClassCounts[r.sourceClass]++;
    if(r.yMin< -64||r.yMax>320){q.outOfBuildVolumeCount++;if(q.errors.length<100)q.errors.push({line,featureId:r.featureId,reason:'Run exceeds [-64,320)',yMin:r.yMin,yMax:r.yMax});}
    const occupied=!air.has(r.block);if(!occupied)q.airRunCount++;
    if(occupied){
    q.minY=q.minY===null?r.yMin:Math.min(q.minY,r.yMin);
    q.maxYExclusive=q.maxYExclusive===null?r.yMax:Math.max(q.maxYExclusive,r.yMax);
    }
    if(occupied&&(r.layer===50||r.geometryKind==='building')){
    const source=this.source.get(r.featureId),f=this.features.get(r.featureId)??{featureId:r.featureId,
      sourceClass:r.sourceClass,runCount:0,minY:r.yMin,maxYExclusive:r.yMax,sourceHeightMeters:source?.heightMeters??null,
      sourceHeightCategory:source?.category??'unknown',sourceHeightConflict:source?.conflict??false,
      expectedRoofDatumY:Number.isFinite(source?.heightMeters)?this.groundY+source.heightMeters:null};
    f.runCount++;f.minY=Math.min(f.minY,r.yMin);f.maxYExclusive=Math.max(f.maxYExclusive,r.yMax);this.features.set(r.featureId,f);
    if(r.sourceClass==='mapped'&&(!Number.isFinite(source?.heightMeters)||source.conflict))q.mappedBuildingRunsWithoutSupportedHeight++;
    }
    const k=r.x+','+r.z;
    // Bound review memory when auditing national streams. A partial audit never passes as complete.
    if(!this.columns.has(k)&&this.columns.size>=262144){q.completeCrossingAudit=false;return;}
    const previous=this.columns.get(k)??[];
    const signature=JSON.stringify([r.block,Object.entries(r.properties??{}).sort(([a],[b])=>a.localeCompare(b))]);
    previous.push({layer:r.layer,featureId:r.featureId,yMin:r.yMin,yMax:r.yMax,signature});this.columns.set(k,previous);
  }
  finish() {
    Object.assign(this.report,resolveRunColumns(this.columns));
    return {...this.report,structurallyValid:this.report.invalidRunCount===0&&this.report.outOfBuildVolumeCount===0&&this.report.equalLayerConflictCount===0&&this.report.completeCrossingAudit,
      featureHeights:[...this.features.values()].map(f=>({...f,roofExtentMinusExpected:f.expectedRoofDatumY===null?null:f.maxYExclusive-f.expectedRoofDatumY})),
      heightAgreementAccepted:false,qualification:'Run extents are occupied geometry, not surveyed heights. Only differing states at the highest active layer reject. Fully occluded lower-layer conflicts are diagnostic. Datum, source conflicts, footprint and roof conventions require review.'};
  }
}
export function auditRunRecords(records,sourceFeatures=[],groundY=0) {
  const audit=new RunHeightAudit(sourceFeatures,groundY);let line=0;
  for(const run of records)audit.add(run,++line);
  return audit.finish();
}
export async function auditRunFile(file,sourceFeatures=[],groundY=0) {
  const {createInterface}=await import('node:readline'),audit=new RunHeightAudit(sourceFeatures,groundY);
  const input=fs.createReadStream(file),digest=crypto.createHash('sha256');input.on('data',chunk=>digest.update(chunk));
  let line=0;
  for await(const body of createInterface({input,crlfDelay:Infinity})) {
    line++;if(!body.trim())continue;
    try{audit.add(JSON.parse(body.replace(/^\uFEFF/,'')),line);}
    catch(error){throw Error('Invalid run JSON at line '+line+': '+error.message);}
  }
  return {...audit.finish(),inputSha256:digest.digest('hex')};
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const args=process.argv.slice(2),source=args.shift();
  if(!source)throw Error('Usage: node validate-height.mjs SOURCE [--manifest TILE]... [--ground-y N] [--out FILE]');
  const manifests=[],runFiles=[];let out,groundY=0;
  while(args.length) {
    const option=args.shift(),value=args.shift();
    if(!value)throw Error('Missing option value: '+option);
    if(option==='--manifest')manifests.push(path.resolve(value));
    else if(option==='--runs')runFiles.push(value);
    else if(option==='--out')out=value;
    else if(option==='--ground-y'){groundY=Number(value);if(!Number.isFinite(groundY))throw Error('Invalid ground Y');}
    else throw Error('Unknown option '+option);
  }
  const data=json(source),sourceHash=hash(source),sourceReport=auditSource(data,groundY);
  const tiles=manifests.map(p=>{
    const m=json(p);if(m.source.sha256!==sourceHash)throw Error('Tile source SHA256 differs from audited source');
    return auditTile(p,data);
  });
  const runs=[];for(const file of runFiles)runs.push(await auditRunFile(file,sourceReport.features,groundY));
  const report={schemaVersion:1,auditedUtc:new Date().toISOString(),sourceSha256:sourceHash,
    source:{...sourceReport,features:undefined},tiles,runs,fullWorldAccepted:false,releaseAccepted:false,
    limitations:['Mapped is not surveyed. Levels are counts, never measured meter heights.',
      'Height is relative to ground; roof:height and min_height are not added to total height.',
      'Unknown/zero/conflicting heights are not silently replaced. No POI ele tag is used as terrain.',
      'Top-block Y and roof surface Y differ by one block; boundary conventions remain explicit.',
      'Relation multipolygons and incomplete footprints remain unmeasured.',
      'All padded region blocks are scanned, but padding is not geographic coverage.',
      'Height compatibility is diagnostic and cannot accept identity, seam assembly or a full world.']};
  if(runs.some(r=>!r.structurallyValid))process.exitCode=1;
  const body=JSON.stringify(report,null,2)+'\n';if(out)fs.writeFileSync(out,body);
  console.log(JSON.stringify({sourceSha256:sourceHash,heightCounts:sourceReport.heightCounts,
    conflicts:sourceReport.conflicts.map(r=>({id:r.id,name:r.name,height:r.heightRaw,alternative:r.alternativeHeightRaw,minHeight:r.minimumHeightMeters,reasons:r.conflictReasons})),
    tallest:sourceReport.tallestMapped.slice(0,5).map(r=>({id:r.id,name:r.name,height:r.heightMeters})),
    outsideBuildVolume:sourceReport.outsideBuildVolume,
    tiles:tiles.map(t=>({tileId:t.tileId,minY:t.measuredOutput.minY,maxY:t.measuredOutput.maxY,...t.counts,
      comparisons:t.footprintComparisons.map(r=>({id:r.id,name:r.name,height:r.heightMeters,tops:r.observedTopY,compatible:r.observedWithinOneBlockOfExpectedRoof}))})),
    runs:runs.map(r=>({runCount:r.runCount,sourceClassCounts:r.sourceClassCounts,minY:r.minY,maxYExclusive:r.maxYExclusive,invalidRunCount:r.invalidRunCount,outOfBuildVolumeCount:r.outOfBuildVolumeCount,equalLayerConflictCount:r.equalLayerConflictCount,structurallyValid:r.structurallyValid})),
    fullWorldAccepted:false},null,2));
}


function resolveRunColumns(columns) {
  const report={equalLayerConflictCount:0,equalLayerConflicts:[],lowerLayerOcclusionCount:0,
    occludedLowerLayerConflictCount:0,occludedLowerLayerConflicts:[]};
  for(const [key,runs] of columns) {
    const [x,z]=key.split(',').map(Number);
    const boundaries=[...new Set(runs.flatMap(r=>[r.yMin,r.yMax]))].sort((a,b)=>a-b);
    for(let i=1;i<boundaries.length;i++) {
      const yMin=boundaries[i-1],yMax=boundaries[i],active=runs.filter(r=>r.yMin<yMax&&yMin<r.yMax);
      if(!active.length)continue;
      const highest=Math.max(...active.map(r=>r.layer)),layers=new Map();
      for(const r of active){const group=layers.get(r.layer)??[];group.push(r);layers.set(r.layer,group);}
      if(layers.size>1)report.lowerLayerOcclusionCount++;
      for(const [layer,group] of layers) {
        if(new Set(group.map(r=>r.signature)).size<2)continue;
        const detail={x,z,yMin,yMax,layer,highestActiveLayer:highest,
          featureIds:[...new Set(group.map(r=>r.featureId))]};
        if(layer===highest) {
          report.equalLayerConflictCount++;
          if(report.equalLayerConflicts.length<100)report.equalLayerConflicts.push({...detail,
            reason:'Highest active layer contains different states; writer rejects'});
        } else {
          report.occludedLowerLayerConflictCount++;
          if(report.occludedLowerLayerConflicts.length<100)report.occludedLowerLayerConflicts.push({...detail,
            reason:'Conflicting lower states are fully hidden by a higher active layer'});
        }
      }
    }
  }
  return report;
}
