import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {validateWorldSettings} from './validate-world-settings.mjs';
import {inventory} from './validate-benchmark.mjs';
import {validateResultBindings} from './validate-benchmark-bindings.mjs';

const flags=Object.fromEntries(process.argv.slice(2).reduce((pairs,v,i,a)=>i%2?pairs:[...pairs,[v.replace(/^--/,''),a[i+1]]],[]));
for(const key of ['benchmark','writer','world','oracle','out']) assert.ok(flags[key],'Missing --'+key);
const json=file=>JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
const sha=file=>{const hash=crypto.createHash('sha256'),fd=fs.openSync(file,'r'),buf=Buffer.alloc(1024*1024);try{let n;while((n=fs.readSync(fd,buf,0,buf.length,null)))hash.update(buf.subarray(0,n));}finally{fs.closeSync(fd);}return hash.digest('hex');};
const eqSha=(a,b,label)=>assert.equal(a.toLowerCase(),b.toLowerCase(),label);
const receipt=json(flags.benchmark),writer=json(flags.writer),oracle=json(flags.oracle);
assert.equal(receipt.mode,'real');
assert.equal(receipt.status,'WRITTEN_UNACCEPTED');
assert.equal(receipt.coreSize,1024);assert.equal(receipt.halo,128);
assert.equal(receipt.coreAreaM2,1024*1024);
assert.equal(writer.kind,'global-block-run-world');
assert.equal(oracle.status,'PASS');
assert.deepEqual(oracle.errors,[]);
assert.deepEqual(oracle.metadataErrors,[]);
assert.deepEqual(oracle.inputErrors,[]);
assert.equal(oracle.mismatchedCells,0);
assert.equal(oracle.inputErrorCount,0);assert.equal(oracle.metadataErrorCount,0);
assert.equal(oracle.heightmapMismatches,0);
assert.equal(oracle.missingColumns,0);
assert.equal(oracle.sameLayerConflictingCells,0);
assert.equal(oracle.spawnClear,true);
const bounds=oracle.bounds;
assert.equal(bounds.length,4);assert.ok(bounds.every(Number.isInteger));
assert.equal(bounds[2]-bounds[0],1024);assert.equal(bounds[3]-bounds[1],1024);
assert.equal(oracle.comparedCells,receipt.coreAreaM2*384);
assert.equal(oracle.chunkCount,4096);assert.equal(oracle.comparedCoreChunkCount,4096);
assert.deepEqual(bounds.slice(0,2),receipt.coreOrigin);
assert.equal(oracle.dataVersion,writer.dataVersion);
const [wx0,wz0,wx1,wz1]=writer.bounds;
assert.deepEqual(writer.bounds,[bounds[0]-128,bounds[1]-128,bounds[2]+128,bounds[3]+128],'writer must render the exact intended128m halo');
assert.equal(writer.coordinateFrame.crs,'EPSG:3414');
assert.equal(writer.coordinateFrame.blocksPerMeter,1);
assert.equal(writer.coordinateFrame.x,'easting');
assert.equal(writer.coordinateFrame.z,'60000-northing');
const world=fs.realpathSync(flags.world),output=fs.realpathSync(receipt.outputRoot),out=path.resolve(flags.out);
assert.ok(world===output||world.startsWith(output+path.sep),'world must belong to measured output');
assert.ok(!out.startsWith(output+path.sep)&&out!==output,'validation receipt must remain outside measured output');
assert.equal(receipt.output.contentHashed,true,'measurement must bind output bytes');
assert.deepEqual(inventory([output],true),receipt.output,'measured output must remain byte-identical');
const outputHashes=[];
for(const record of writer.outputs){
  const file=path.resolve(world,record.path);assert.ok(file.startsWith(world+path.sep),'world path traversal');
  assert.equal(fs.statSync(file).size,record.bytes);
  eqSha(sha(file),record.sha256,'writer file hash');
  outputHashes.push({path:record.path,sha256:record.sha256,bytes:record.bytes});
}
const settings=validateWorldSettings(world);
assert.equal(settings.status,'PASS');assert.deepEqual(settings.errors,[]);
assert.equal(settings.dataVersion,writer.dataVersion);
assert.deepEqual(settings.spawn,oracle.spawn);
const inputRecords=oracle.sourceFiles??oracle.inputs;
assert.ok(Array.isArray(inputRecords)&&inputRecords.length>0);
assert.deepEqual(inputRecords.map(i=>i.sha256.toLowerCase()).sort(),writer.inputs.map(i=>i.sha256.toLowerCase()).sort(),'oracle must compare every consumed run file');
for(const record of inputRecords){
  eqSha(sha(record.path),record.sha256,'actual oracle source run hash');
  if(record.bytes!==undefined)assert.equal(fs.statSync(record.path).size,record.bytes);
}
assert.ok(Array.isArray(oracle.worldFiles)&&oracle.worldFiles.length>0,'oracle world file hashes required');
const proofRegionHashes=oracle.worldFiles.filter(f=>f.path.toLowerCase().endsWith('.mca')).map(f=>f.sha256.toLowerCase()).sort();
const writerRegionHashes=writer.outputs.filter(f=>f.path.toLowerCase().endsWith('.mca')).map(f=>f.sha256.toLowerCase()).sort();
assert.deepEqual(proofRegionHashes,writerRegionHashes,'oracle must bind the same actual region files');
const record=file=>({path:path.resolve(file),sha256:sha(file)});
const result={
  schemaVersion:1,kind:'independent-benchmark-result-gate',status:'PASS',createdUtc:new Date().toISOString(),
  benchmarkReceiptSha256:sha(flags.benchmark),outputInventorySha256:receipt.output.inventorySha256,
  comparisonScope:'full-volume',comparisonBounds:bounds,comparedCells:oracle.comparedCells,
  mismatchedCells:0,checks:{globalChunkCoordinates:true,metadata:true,heightmaps:true},fileHashErrors:[],
  evidence:{writerManifest:record(flags.writer),sourceRuns:inputRecords.map(i=>record(i.path)),oracleProof:record(flags.oracle)},
  writerOutputHashes:outputHashes,worldSettings:settings,experimentalMeasurementAccepted:true,
  runtimeLoadAccepted:false,terrainAccepted:false,facadeAccuracyAccepted:false,productionAccepted:false,fullWorldAccepted:false,
  scope:'Exact 1024m owned-core volume within a 128m-halo render. This validates one experimental measurement; it does not accept nationwide assembly or unmeasured geographic fidelity.'
};
validateResultBindings(receipt,result,path.dirname(out));
fs.writeFileSync(out,JSON.stringify(result,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({status:'PASS',comparedCells:result.comparedCells,sha256:sha(out)}));
