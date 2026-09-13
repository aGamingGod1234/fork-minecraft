import assert from 'node:assert/strict';
import fs from 'node:fs';
import {svy21,project,inverse,auditOsm,validate} from './validate-fidelity.mjs';
const dms=(d,m,s)=>d+m/60+s/3600;
const points=[
 ['SNYU',dms(103,40,47.91672),dms(1,20,44.84810),10934.735,36436.799],
 ['SRPT',dms(103,47,3.88829),dms(1,26,37.41926),22557.618,47265.636],
 ['SNPT',dms(103,50,55.51059),dms(1,22,44.82064),29717.651,40121.255],
 ['SLYG',dms(103,58,18.00928),dms(1,22,21.44707),43396.751,39403.770],
 ['SNSC',dms(103,57,42.01188),dms(1,18,49.12095),42284.293,32882.042],
 ['SNUS',dms(103,46,31.83287),dms(1,17,32.68755),21566.307,30534.079],
 ['SSMK',dms(103,46,31.49934),dms(1,12,37.48752),21555.796,21466.939],
 ['SSTS',dms(103,38,52.29482),dms(1,14,23.49077),7359.574,24723.506],
 ['SMS1',dms(103,53,16.40533),dms(1,20,25.60771),34073.254,35845.357]
];
const grid={kind:'EPSG:3414',originEasting:0,originNorthing:60000,blocksPerMeter:1,xDirection:'east',zDirection:'south'};
const residuals=points.map(([id,lon,lat,e,n])=>{
  const actual=svy21(lon,lat),error=Math.hypot(actual[0]-e,actual[1]-n);
  assert(error<0.1,id+' official reference residual '+error);
  const block=project(lon,lat,grid),back=inverse(...block,grid);
  assert(Math.max(Math.abs(lon-back[0]),Math.abs(lat-back[1]))<1e-9);
  const east=inverse(block[0]+1,block[1],grid),south=inverse(block[0],block[1]+1,grid);
  assert(east[0]>lon&&south[1]<lat,'east and south orientation');
  return {id,residualMeters:error};
});
assert(Math.hypot(...svy21(103+50/60,1+22/60).map((v,i)=>v-[28001.642,38744.572][i]))<1e-8);
assert.equal(validate({schemaVersion:1,sources:[]}).structurallyValid,false);
assert.equal(validate({schemaVersion:1,sources:[{id:'x',url:'https://example.org/a',license:'test'}]}).releaseAccepted,false);
const holes=auditOsm({elements:[{type:'way',id:1,nodes:[99],tags:{building:'yes',height:'twenty'}}]});
assert.equal(holes.missingReferenceCount,1);assert.equal(holes.height.invalid,1);
const report={status:'PASS',independentProjection:'WGS84 TM series, independent of pipeline proj4',source:'https://app.sla.gov.sg/sirent/Page/ReferenceStations',toleranceMeters:0.1,maxResidualMeters:Math.max(...residuals.map(r=>r.residualMeters)),residuals,additionalChecks:['false origin exact','nine inverse roundtrips','east/south axes','empty registry rejected','source-only cannot accept world','malformed height and missing reference reported']};
console.log(JSON.stringify(report,null,2));
if(process.argv[2])fs.writeFileSync(process.argv[2],JSON.stringify(report,null,2)+'\n');
