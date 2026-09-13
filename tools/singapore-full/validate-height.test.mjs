import assert from 'node:assert/strict';
import {meters,classify,rangeRisk,auditSource,inside} from './validate-height.mjs';
assert.equal(meters('12.5 m'),12.5);
for(const v of ['0','-2','12ft','12;15','~20','Infinity',''])assert.equal(meters(v),null);
assert.equal(classify({'building:levels':'30'}).category,'inferredLevels');
assert.equal(classify({height:'20','building:levels':'30'}).heightMeters,20);
assert.equal(classify({height:'0','building:levels':'30'}).category,'unsupported');
assert.equal(classify({height:'25','building:height':'37'}).conflict,true);
assert.equal(classify({ele:'282'}).category,'missing');
const part=classify({height:'152',min_height:'16','roof:height':'10'});
assert.equal(part.heightMeters,152);assert.equal(part.minimumHeightMeters,16);
assert.equal(rangeRisk(null,0).status,'unknown');
assert.equal(rangeRisk(283,0).status,'fitsProvisionalDatum');
assert.equal(rangeRisk(283,64).status,'outsideBuildVolume');
assert.equal(rangeRisk(321,0).status,'outsideBuildVolume');
assert.equal(rangeRisk(320,0).status,'upperBoundaryConventionUnresolved');
assert.equal(rangeRisk(5,-65).status,'outsideBuildVolume');
assert.equal(inside(5,5,[[0,0],[10,0],[10,10],[0,10],[0,0]]),true);
assert.equal(inside(20,5,[[0,0],[10,0],[10,10],[0,10],[0,0]]),false);
const source=auditSource({elements:[{type:'way',id:1,tags:{building:'yes',height:'10'}},{type:'way',id:2,tags:{building:'yes','building:levels':'3'}},{type:'way',id:3,tags:{building:'yes',height:'0'}},{type:'way',id:4,tags:{building:'yes'}},{type:'node',id:5,tags:{ele:'282',tourism:'viewpoint'}}]});
assert.deepEqual(source.heightCounts,{mappedMeters:1,inferredLevels:1,unsupported:1,missing:1});
assert.equal(source.terrainElevationAcceptedCount,0);
console.log('PASS: source semantics, unit parsing, height precedence, datum separation, build-volume clipping and footprint inclusion.');

const {auditRunRecords}=await import('./validate-height.mjs');
const run={x:29712,z:30496,yMin:-64,yMax:320,block:'minecraft:stone',properties:{},
  featureId:'way/1',geometryKind:'building',sourceClass:'mapped',layer:50};
const all=auditRunRecords([run],source.features);
assert.equal(all.structurallyValid,true);
assert.equal(all.maxYExclusive,320);
assert.equal(all.featureHeights[0].sourceHeightMeters,10);
assert.equal(all.heightAgreementAccepted,false);
const invalid=auditRunRecords([{...run,yMax:321},{...run,x:0.5}]);
assert.equal(invalid.outOfBuildVolumeCount,1);
assert.equal(invalid.invalidRunCount,1);
assert.equal(invalid.structurallyValid,false);
const overlap=auditRunRecords([run,{...run,featureId:'way/2',block:'minecraft:glass',yMin:10,yMax:20}]);
assert.equal(overlap.equalLayerConflictCount,1);
assert.equal(overlap.equalLayerConflicts[0].yMax,20);
assert.equal(overlap.fullWorldAccepted,false);
assert.equal(auditRunRecords([run,{...run,featureId:'way/2',yMin:320,yMax:321}]).equalLayerConflictCount,0);
assert.equal(auditRunRecords([run,{...run,featureId:'way/2',layer:60}]).equalLayerConflictCount,0);
console.log('PASS: deterministic run contract, exclusive ceiling, provenance, real-height binding and same-layer crossing diagnostics.');

const fs=await import('node:fs'),{fileURLToPath}=await import('node:url');
const {auditRunFile}=await import('./validate-height.mjs');
const fixture=fileURLToPath(new URL('./validate-height-test-fixture-'+process.pid+'.jsonl',import.meta.url));
try {
  fs.writeFileSync(fixture,JSON.stringify(run)+'\n'+JSON.stringify({...run,yMax:321})+'\n');
  const streamed=await auditRunFile(fixture,source.features);
  assert.equal(streamed.runCount,2);assert.equal(streamed.outOfBuildVolumeCount,1);
  assert.match(streamed.inputSha256,/^[a-f0-9]{64}$/);
} finally {if(fs.existsSync(fixture))fs.unlinkSync(fixture);}
assert.equal(auditRunRecords([null]).invalidRunCount,1);
console.log('PASS: streaming JSONL input hashing and invalid null record rejection.');

assert.equal(classify({height:'280',min_height:'282'}).conflict,true);
assert.equal(classify({height:'280',min_height:'281'}).conflict,true);
assert.equal(classify({height:'152',min_height:'16'}).conflict,false);
assert.equal(auditRunRecords([{...run,geometryKind:''}]).invalidRunCount,1);
assert.equal(auditRunRecords([{...run,properties:42}]).invalidRunCount,1);
assert.equal(auditRunRecords([{...run,properties:{axis:42}}]).invalidRunCount,1);
assert.equal(auditRunRecords([{...run,layer:undefined}]).structurallyValid,false);
assert.equal(auditRunRecords([{...run,layer:'building'}]).structurallyValid,true);
assert.equal(auditRunRecords([{...run,layer:51}]).structurallyValid,false);
assert.equal(auditRunRecords([{...run,layer:'50'}]).structurallyValid,false);
const airOnly=auditRunRecords([{...run,block:'minecraft:air'}]);
assert.equal(airOnly.airRunCount,1);assert.equal(airOnly.maxYExclusive,null);
assert.equal(airOnly.featureHeights.length,0);
console.log('PASS: contradictory min_height, property validation, explicit numeric or named layers, air excluded from occupied height.');

assert.equal(overlap.structurallyValid,false);
assert.equal(auditRunRecords([run,{...run,featureId:'way/2'}]).structurallyValid,true);
assert.equal(auditRunRecords([run,{...run,block:'minecraft:glass'}]).structurallyValid,false);
assert.equal(auditRunRecords([run,{...run,block:'minecraft:air'}]).structurallyValid,false);
const lowerLayer=auditRunRecords([run,{...run,block:'minecraft:dirt',layer:10,geometryKind:'terrain'}]);
assert.equal(lowerLayer.lowerLayerOcclusionCount,1);assert.equal(lowerLayer.structurallyValid,true);
assert.equal(auditRunRecords([{...run,layer:40,geometryKind:'road'},{...run,layer:40,geometryKind:'road',block:'minecraft:gravel'}]).equalLayerConflictCount,1);
console.log('PASS: merger-consistent equal-layer rejection, same-state duplicates and highest-layer occlusion.');

const baseLow={...run,yMin:0,yMax:10,layer:40,geometryKind:'road'};
const hiddenConflict=[baseLow,{...baseLow,block:'minecraft:gravel'}, {...baseLow,layer:50,geometryKind:'building',block:'minecraft:glass'}];
for(const records of [hiddenConflict,[...hiddenConflict].reverse()]) {
  const audit=auditRunRecords(records);
  assert.equal(audit.structurallyValid,true);
  assert.equal(audit.equalLayerConflictCount,0);
  assert.equal(audit.occludedLowerLayerConflictCount,1);
}
const partiallyHidden=auditRunRecords([baseLow,{...baseLow,block:'minecraft:gravel'}, {...baseLow,layer:50,geometryKind:'building',block:'minecraft:glass',yMax:5}]);
assert.equal(partiallyHidden.structurallyValid,false);
assert.equal(partiallyHidden.equalLayerConflicts[0].yMin,5);
assert.equal(partiallyHidden.equalLayerConflicts[0].yMax,10);
assert.equal(partiallyHidden.occludedLowerLayerConflictCount,1);
console.log('PASS: fully hidden lower conflict is diagnostic, exposed interval fails, input order does not change acceptance.');
