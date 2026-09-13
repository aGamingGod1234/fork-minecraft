import assert from 'node:assert/strict';
import {prepareRequests,BENCHMARKS} from './pipeline-benchmark-prep.mjs';
import {project} from './pipeline.mjs';

const actual=prepareRequests('exports');
assert.equal(actual.length,3);
for(let i=0;i<actual.length;i++){
  const request=actual[i],point=BENCHMARKS[i];
  const [x,z]=project(point.longitude,point.latitude);
  const [x0,z0]=request.tile.coreOrigin;
  assert.deepEqual(request.projectedPointXZ,[x,z]);
  assert(x>=x0&&x<x0+1024&&z>=z0&&z<z0+1024);
  assert.equal(x0%1024,0);assert.equal(z0%1024,0);
  const [minE,minN,maxE,maxN]=request.sourceExport.bounds;
  assert.equal(maxE-minE,1280);assert.equal(maxN-minN,1280);
  assert.deepEqual(request.renderBoundsXZ,[minE,60000-maxN,maxE,60000-minN]);
  assert.equal(request.generation.status,'not-dispatched');
}
assert.deepEqual(prepareRequests('exports',()=>[-0.25,-1024.25])[0].tile.coreOrigin,[-1024,-2048]);
assert.deepEqual(prepareRequests('exports',()=>[1024,2048])[0].tile.coreOrigin,[1024,2048]);
assert.throws(()=>prepareRequests('exports',()=>[NaN,0]),/Non-finite/);
assert.deepEqual(prepareRequests('exports'),actual);
console.log(JSON.stringify({passed:true,benchmarks:actual.map(r=>({id:r.id,coreOrigin:r.tile.coreOrigin,boundsEN:r.sourceExport.bounds})),
  checks:['exactSharedProjection','1024FloorOwnership','128Halo','NorthingAxisInversion','negativeCoordinates','exactBoundary','deterministic','noDispatch']}));
