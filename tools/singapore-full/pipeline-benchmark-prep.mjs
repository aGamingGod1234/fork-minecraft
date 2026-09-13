import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {project, GRID} from './pipeline.mjs';

export const BENCHMARKS=Object.freeze([
  {id:'dense-cbd',label:'Dense CBD',latitude:1.283,longitude:103.85},
  {id:'rural-lim-chu-kang',label:'Rural Lim Chu Kang',latitude:1.43,longitude:103.71},
  {id:'coast-changi',label:'Coast Changi',latitude:1.39,longitude:103.993},
]);

export function prepareRequests(exportDirectory,projectPoint=project){
  const size=1024, halo=128;
  return BENCHMARKS.map(point=>{
    const [x,z]=projectPoint(point.longitude,point.latitude);
    if(!Number.isFinite(x)||!Number.isFinite(z))throw Error('Non-finite projected benchmark point');
    const coreOrigin=[Math.floor(x/size)*size,Math.floor(z/size)*size];
    const [x0,z0]=coreOrigin;
    const renderBoundsXZ=[x0-halo,z0-halo,x0+size+halo,z0+size+halo];
    const [rx0,rz0,rx1,rz1]=renderBoundsXZ;
    const exportBoundsEN=[rx0,GRID.originNorthing-rz1,rx1,GRID.originNorthing-rz0];
    const outputPath=path.join(exportDirectory,`${point.id}-x${x0}-z${z0}-s${size}-h${halo}.json`);
    return {id:point.id,label:point.label,pointWgs84:{longitude:point.longitude,latitude:point.latitude},
      projectedPointXZ:[x,z],tile:{id:point.id,coreOrigin,coreSize:size,halo},
      coreBoundsXZ:[x0,z0,x0+size,z0+size],renderBoundsXZ,
      sourceExport:{boundsOrder:['minimumEasting','minimumNorthing','maximumEasting','maximumNorthing'],
        bounds:exportBoundsEN,outputPath,receiptPath:outputPath+'.manifest.json',
        status:'awaiting-index-export',sha256:null},
      generation:{status:'not-dispatched',requiresRoadsV2Gate:true,requiresIndependentBenchmarkLease:true}};
  });
}

export function bindExports(requests){
  return requests.map(request=>{
    const source=request.sourceExport;
    if(!fs.existsSync(source.outputPath)||!fs.existsSync(source.receiptPath))return request;
    const bytes=fs.readFileSync(source.outputPath), sha256=crypto.createHash('sha256').update(bytes).digest('hex');
    const receiptBytes=fs.readFileSync(source.receiptPath);
    return {...request,sourceExport:{...source,status:'export-present-pending-parent-receipt-review',
      bytes:bytes.length,sha256,receiptSha256:crypto.createHash('sha256').update(receiptBytes).digest('hex')}};
  });
}

export function main(argv){
  const opts={};
  for(let i=0;i<argv.length;i+=2){if(!argv[i].startsWith('--')||argv[i+1]===undefined)throw Error('Expected --name value');opts[argv[i].slice(2)]=argv[i+1];}
  if(!opts.output||!opts['export-dir'])throw Error('Usage: pipeline-benchmark-prep.mjs --export-dir NEW_EXPORT_DIRECTORY --output NEW_MANIFEST');
  const output=path.resolve(opts.output),exportDirectory=path.resolve(opts['export-dir']);
  if(fs.existsSync(output))throw Error('Preserve existing preparation records; use a new manifest');
  const requests=bindExports(prepareRequests(exportDirectory));
  const manifest={schemaVersion:1,kind:'fork-benchmark-source-export-preparation',
    status:'prepared-not-rendered-not-dispatched',grid:GRID,benchmarks:requests,
    sourceExportPolicy:{completeOriginalGeometry:true,referenceClosure:true,buildingParentSiblingClosure:true,
      countryMaskAppliedByRenderer:true,queryFrame:'EPSG:3414 easting/northing',indexReadOnly:true},
    pipelineJobContract:{schemaVersion:1,tilesPerJob:1,maxCoreSize:1024,terrain:{mode:'flat-provisional',groundY:0},
      requiredBeforeExecution:['source path and SHA256','frozen tools and dependency hashes','level template and world-generation settings hashes',
        'derived country mask hash','roads V2 acceptance gate','active Desktop benchmark resource lease'],
      note:'This is a preparation record, not a valid dispatch job. No tools or leases are invented.'},
    qualityGates:{actualBenchmark:false,terrainSurveyed:false,photographMatched:false,productionAccepted:false}};
  fs.mkdirSync(path.dirname(output),{recursive:true});
  const text=JSON.stringify(manifest,null,2)+'\n';
  fs.writeFileSync(output,text,{flag:'wx'});
  console.log(JSON.stringify({manifest:output,sha256:crypto.createHash('sha256').update(text).digest('hex'),
    requests:requests.map(r=>({id:r.id,coreOrigin:r.tile.coreOrigin,boundsEN:r.sourceExport.bounds,output:r.sourceExport.outputPath}))},null,2));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main(process.argv.slice(2));
