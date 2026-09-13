import assert from 'node:assert/strict';
import {validateJob,aggregateBuildingEvidence} from './pipeline-adapter.mjs';
const job={schemaVersion:1,source:{path:'source.json',sha256:'a'.repeat(64)},artifacts:[{path:'a',sha256:'b'.repeat(64)},{path:'b',sha256:'c'.repeat(64)},{path:'c',sha256:'d'.repeat(64)}],tools:{python:'python',features:'features.mjs',buildings:'renderer.py',overlay:'overlay.py'},levelTemplate:{path:'level.dat'},terrain:{mode:'flat-provisional',groundY:0},tiles:[{id:'a',coreOrigin:[29712,30496],coreSize:128,halo:32}]};
assert.equal(validateJob(job),job);
for(const change of [j=>j.tiles=[],j=>j.tiles.push(j.tiles[0]),j=>j.tiles[0].id='../escape',j=>j.tiles[0].coreOrigin[0]++,j=>j.tiles[0].coreSize=100000,j=>j.tiles[0].halo=-1,j=>j.source.sha256='bad',j=>j.terrain.mode='surveyed',j=>delete j.tools.overlay]){const copy=structuredClone(job);change(copy);assert.throws(()=>validateJob(copy));}
console.log('PASS: bounded job shape, path IDs, integer grid, immutable input, tool and provisional terrain checks');
const gates={sourceGeometryComplete:true};aggregateBuildingEvidence(gates,{completeSourceGeometryAccepted:false,resolvedOverlappingVoxels:1368,exclusions:[]});assert.equal(gates.sourceGeometryComplete,false);assert.equal(gates.resolvedOverlapVoxelOccurrences,1368);aggregateBuildingEvidence(gates,{completeSourceGeometryAccepted:true,resolvedOverlappingVoxels:0});assert.equal(gates.sourceGeometryComplete,false);
console.log('PASS: an overlap rejection is retained even without excluded features and cannot be cleared by a later tile');
