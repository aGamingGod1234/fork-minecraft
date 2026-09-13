import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const sha256 = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const finite = n => typeof n === 'number' && Number.isFinite(n);
const hashOk = s => typeof s === 'string' && /^[a-f0-9]{64}$/i.test(s);
const rad = n => n * Math.PI / 180;
// Independent WGS84 Transverse Mercator series; no dependency on pipeline proj4.
export function svy21(lon,lat){
  const a=6378137,f=1/298.257223563,e2=f*(2-f),e4=e2*e2,e6=e4*e2,ep2=e2/(1-e2);
  const p=rad(lat),p0=rad(1+22/60),l0=rad(103+50/60);
  const meridian=p=>a*((1-e2/4-3*e4/64-5*e6/256)*p-(3*e2/8+3*e4/32+45*e6/1024)*Math.sin(2*p)+(15*e4/256+45*e6/1024)*Math.sin(4*p)-35*e6/3072*Math.sin(6*p));
  const n=a/Math.sqrt(1-e2*Math.sin(p)**2),t=Math.tan(p)**2,c=ep2*Math.cos(p)**2,A=Math.cos(p)*(rad(lon)-l0);
  return [28001.642+n*(A+(1-t+c)*A**3/6+(5-18*t+t*t+72*c-58*ep2)*A**5/120),
    38744.572+meridian(p)-meridian(p0)+n*Math.tan(p)*(A*A/2+(5-t+9*c+4*c*c)*A**4/24+(61-58*t+t*t+600*c-330*ep2)*A**6/720)];
}
export function project(lon, lat, g) {
  if(g.kind==='EPSG:3414'){
    const [e,n]=svy21(lon,lat);
    return [(e-g.originEasting)*g.blocksPerMeter,(g.originNorthing-n)*g.blocksPerMeter];
  }
  return [rad(lon-g.originLon)*g.earthRadiusM*Math.cos(rad(g.referenceLat))*g.blocksPerMeter,
    rad(g.originLat-lat)*g.earthRadiusM*g.blocksPerMeter];
}
export function inverse(x,z,g) {
  if(g.kind==='EPSG:3414'){
    let lon=103.83333333333333,lat=1.3666666666666667;
    for(let i=0;i<6;i++){
      const [px,pz]=project(lon,lat,g),dx=x-px,dz=z-pz,h=1e-6;
      const q=project(lon+h,lat,g),r=project(lon,lat+h,g);
      const a=(q[0]-px)/h,b=(r[0]-px)/h,c=(q[1]-pz)/h,d=(r[1]-pz)/h,det=a*d-b*c;
      lon+=(d*dx-b*dz)/det;lat+=(-c*dx+a*dz)/det;
    }
    return [lon,lat];
  }
  return [g.originLon+x/g.blocksPerMeter/g.earthRadiusM/Math.cos(rad(g.referenceLat))*180/Math.PI,
    g.originLat-z/g.blocksPerMeter/g.earthRadiusM*180/Math.PI];
}
export function auditOsm(data) {
  if (!Array.isArray(data.elements)) throw Error('Expected OSM JSON elements');
  const elements=data.elements, ids=new Set(elements.map(e=>e.type+'/'+e.id));
  const report={elements:elements.length, buildings:0, height:{mappedMeters:0,inferredLevels:0,missing:0,invalid:0},
    facade:{mappedMaterialOrColour:0,missingAppearance:0,photographicValidation:0}, terrain:{surveyed:0},
    missingReferences:[], missingReferenceCount:0, sourceClaimsAreSurveyed:false};
  for(const e of elements) {
    const refs=e.type==='way'?(e.nodes??[]).map(r=>'node/'+r):e.type==='relation'?(e.members??[]).map(m=>m.type+'/'+m.ref):[];
    for(const ref of refs) if(!ids.has(ref)){report.missingReferenceCount++;if(report.missingReferences.length<100)report.missingReferences.push({element:e.type+'/'+e.id,ref});}
    const t=e.tags??{};
    if(!t.building&&!t['building:part'])continue;
    report.buildings++;
    const h=t.height??t['building:height'], levels=t['building:levels'];
    if(h!==undefined) {if(/^\d+(\.\d+)?\s*(m)?$/.test(String(h).trim())&&parseFloat(h)>0)report.height.mappedMeters++;else report.height.invalid++;}
    else if(levels!==undefined&&finite(Number(levels))&&Number(levels)>0)report.height.inferredLevels++;
    else report.height.missing++;
    if(t['building:material']||t['building:colour']||t['building:color'])report.facade.mappedMaterialOrColour++;
    report.facade.missingAppearance++;
  }
  return report;
}
export function validate(m, root='.') {
  const errors=[], warnings=[], checks=[];
  const need=(ok,label)=>{checks.push({label,pass:!!ok});if(!ok)errors.push(label);};
  const fileCheck=(f,label,base=root)=>{
    if(!f?.path){errors.push(label+' has no file path');return;}
    const p=path.resolve(base,f.path);
    if(!fs.existsSync(p)||!fs.statSync(p).isFile()){errors.push(label+' missing: '+f.path);return;}
    need(Number.isSafeInteger(f.bytes)&&fs.statSync(p).size===f.bytes,label+' byte count');
    need(hashOk(f.sha256)&&sha256(p).toLowerCase()===f.sha256.toLowerCase(),label+' SHA256');
  };
  need(m.schemaVersion===1,'schemaVersion 1');
  const isTile=!!m.tile;
  if(!isTile){
    need(Array.isArray(m.sources)&&m.sources.length>0,'nonempty source registry');
    for(const s of m.sources??[]){
      need(typeof s.id==='string'&&s.id.length>0,'source ID');
      need(/^https:\/\//.test(s.url??''),'source HTTPS URL');
      need(!!s.license,'source license');
      if(s.path)fileCheck(s,'source '+s.id);else warnings.push('Source '+s.id+' hash is declared but file not bound for verification');
    }
    return {kind:'source-registry',structurallyValid:errors.length===0,releaseAccepted:false,errors,warnings,checks};
  }
  const g=m.grid??{},t=m.tile, v=m.vertical??{};
  need(['EPSG:3414','local-equirectangular'].includes(g.kind),'explicit projection');
  if(g.kind==='EPSG:3414'){
    need(finite(g.originEasting)&&finite(g.originNorthing),'SVY21 translation origin');
  }else{
    for(const k of ['originLon','originLat','referenceLat','earthRadiusM','blocksPerMeter'])need(finite(g[k]),'grid '+k);
    need(g.earthRadiusM>6000000&&g.earthRadiusM<6500000,'earth radius plausible');
  }
  need(g.blocksPerMeter===1,'one block per projected meter');
  need(g.xDirection==='east'&&g.zDirection==='south','Minecraft axis orientation');
  need(Array.isArray(t.coreOrigin)&&t.coreOrigin.length===2&&t.coreOrigin.every(Number.isSafeInteger),'integer core origin');
  need(Number.isSafeInteger(t.coreSize)&&t.coreSize>0,'positive core size');
  need(Number.isSafeInteger(t.halo)&&t.halo>=0,'nonnegative halo');
  need(t.renderSize===t.coreSize+2*t.halo,'render size includes halo');
  need(Array.isArray(t.renderOrigin)&&t.renderOrigin.every((a,i)=>a===t.coreOrigin?.[i]-t.halo),'render origin includes halo');
  const bbox=t.boundsWgs84;
  need(Array.isArray(bbox)&&bbox.length===4&&bbox.every(finite)&&bbox[0]<bbox[2]&&bbox[1]<bbox[3],'WGS84 bounds south west north east');
  need(finite(v.groundY)&&typeof v.mode==='string'&&typeof v.surveyed==='boolean','vertical datum and survey status');
  if(!v.surveyed)warnings.push('Ground is provisional: no surveyed terrain or vertical datum acceptance');
  need(!!m.source?.license,'source license retained');
  if(m.source?.path)fileCheck(m.source,'tile source');
  else errors.push('tile source file is unbound');
  need(hashOk(m.generator?.sha256),'generator executable SHA256 declared');
  need(Array.isArray(m.generator?.args),'generator arguments recorded');
  need(m.mapping?.adapter==='global-grid-to-Arnis-local','declared global to Arnis adapter');
  need(m.mapping?.sourceCoordinatesPreservedInRaw===true,'original source coordinates preserved');
  const pts=m.mapping?.controlPoints??[];
  need(pts.length>=4,'at least four independently measurable control points');
  let maxPositionError=0,maxInverseError=0;
  for(const p of pts){
    const coords=[p.sourceLon,p.sourceLat,p.globalX,p.globalZ,p.renderX,p.renderZ];
    if(!coords.every(finite)){errors.push('nonfinite mapping control point');continue;}
    const expected=project(p.sourceLon,p.sourceLat,g);
    const error=Math.hypot(expected[0]-p.globalX,expected[1]-p.globalZ);
    maxPositionError=Math.max(maxPositionError,error);
    need(error<=Math.SQRT1_2+1e-6,'source point quantization <=0.708m');
    need(Math.abs(p.renderX-(p.globalX-t.renderOrigin[0]))<=1e-6&&Math.abs(p.renderZ-(p.globalZ-t.renderOrigin[1]))<=1e-6,'tile/global constant translation');
    const back=inverse(expected[0],expected[1],g);
    maxInverseError=Math.max(maxInverseError,Math.abs(back[0]-p.sourceLon),Math.abs(back[1]-p.sourceLat));
  }
  need(maxInverseError<1e-9,'projection inverse agreement');
  const files=m.output?.files??[]; const outputRoot=path.resolve(root,m.output?.worldPath??'.');
  need(files.length>0,'generated output file inventory');
  for(const f of files)fileCheck(typeof f==='string'?{path:f}:f,'output',outputRoot);
  need(Number.isSafeInteger(m.output?.regionCount)&&m.output.regionCount>0,'positive generated region count');
  need(Number.isSafeInteger(m.output?.chunkCount)&&m.output.chunkCount>0,'positive generated chunk count');
  const regionFiles=files.filter(f=>typeof f==='object'&&/\.mca$/i.test(f.path??''));
  let actualChunks=0;
  for(const f of regionFiles) {
    const p=path.resolve(outputRoot,f.path);
    if(!fs.existsSync(p))continue;
    const size=fs.statSync(p).size, fd=fs.openSync(p,'r'), header=Buffer.alloc(4096);
    try{need(size>=8192,'region has two-sector header');fs.readSync(fd,header,0,4096,0);}
    finally{fs.closeSync(fd);}
    for(let slot=0;slot<1024;slot++){
      const packed=header.readUInt32BE(slot*4),offset=packed>>>8,count=packed&255;
      if(!packed)continue;actualChunks++;
      need(offset>=2&&count>0&&(offset+count)*4096<=size,'region chunk sector bounds');
    }
  }
  need(regionFiles.length===m.output.regionCount,'actual region file count');
  need(actualChunks===m.output.chunkCount,'actual nonempty region chunk headers');
  const q=m.qualityGates??{};
  if(g.kind!=='EPSG:3414')warnings.push('PROVISIONAL spherical grid: rejected for real-world 1:1 scale acceptance');

  if(!q.assembly)warnings.push('Separate tile only; seamless whole-island world is unverified');
  if(!q.facadeMatch)warnings.push('Facade identity and photographic resemblance are unverified');
  if(!q.actualTerrain)warnings.push('Actual terrain is unverified');
  if(q.assembly===true&&!m.assemblyEvidence)errors.push('Assembly claim has no boundary measurement evidence');
  if(q.facadeMatch===true&&!m.facadeEvidence)errors.push('Facade claim has no source comparison evidence');
  if(q.actualTerrain===true&&(!v.surveyed||!m.terrainEvidence))errors.push('Terrain claim has no surveyed evidence');
  const heights=m.heightEvidence;
  if(heights){
    need(finite(heights.minY)&&finite(heights.maxY)&&heights.minY>=-64&&heights.maxY<=319,'measured output height within Minecraft -64..319');
    need(heights.clippedFeatures===0,'zero clipped known building heights');
  }else warnings.push('No per-feature source height versus generated top audit; clipping gate remains unmet');
  if(m.claims?.exactEveryElement===true)errors.push('Every-element exactness requires a complete inventory and independent audit; unsupported');
  return {kind:'tile',structurallyValid:errors.length===0,initialMilestoneAccepted:errors.length===0&&g.kind==='EPSG:3414',fullWorldAccepted:false,releaseAccepted:false,acceptanceScope:'Source integrity, projection adapter and file structure only; full world acceptance requires separate measured terrain, facade and assembly review',
    measured:{controlPoints:pts.length,maxPositionErrorMeters:maxPositionError,maxInverseErrorDegrees:maxInverseError},
    errors,warnings,checks};
}
if(process.argv[1]&&path.resolve(process.argv[1])===path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1'))){
  const [command,input,...rest]=process.argv.slice(2);
  if(!['osm','manifest'].includes(command)||!input)throw Error('Usage: node validate-fidelity.mjs osm|manifest INPUT [--root DIR] [--out FILE]');
  const at=k=>{const i=rest.indexOf(k);return i<0?undefined:rest[i+1];};
  const data=JSON.parse(fs.readFileSync(input,'utf8').replace(/^\uFEFF/,''));
  const result=command==='osm'?auditOsm(data):validate(data,at('--root')??path.dirname(path.resolve(input)));
  result.auditedUtc=new Date().toISOString();result.inputSha256=sha256(input);
  const body=JSON.stringify(result,null,2)+'\n';
  if(at('--out'))fs.writeFileSync(at('--out'),body);
  console.log(body);if(result.structurallyValid===false)process.exitCode=1;
}
