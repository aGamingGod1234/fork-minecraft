import assert from 'node:assert/strict';
import {validateGrowJob} from './pipeline-grow.mjs';
const bound={path:'source',sha256:'a'.repeat(64)};
const job={schemaVersion:1,kind:'fork-connected-expansion',terrain:{mode:'flat-provisional',groundY:0},cores:[{id:'east-1',coreBounds:[30720,29696,30976,29952],buildingRuns:bound,source:bound,sourceReceipt:bound,workerReceipt:bound,referenceClosureComplete:true}],tools:{python:'python',nodeProjection:'node',roads:'roads',maskRuns:'mask',coastSurface:'coast',overlay:'writer'},leasePath:'lease',artifacts:[bound],importReceipt:bound,sourceManifest:bound,levelTemplate:bound,countryMask:bound,foreignMask:bound,coastMask:bound};
assert.equal(validateGrowJob(job),job);
for(const mutate of [j=>j.cores[0].coreBounds[0]=0,j=>j.cores.push({...j.cores[0],id:'east-2'}),j=>j.cores[0].referenceClosureComplete=false,j=>delete j.coastMask,j=>j.terrain.groundY=1,j=>j.cores[0].id='../escape']){const copy=structuredClone(job);mutate(copy);assert.throws(()=>validateGrowJob(copy));}
console.log('PASS verified east-core ownership, source closure and complete surface-input contract');
