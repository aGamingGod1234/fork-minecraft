import assert from 'node:assert/strict';
import {validateRecovery} from './pipeline-grow-recover.mjs';
const job={schemaVersion:1,kind:'fork-writer-only-recovery',runs:[{}],artifacts:[{}],provenance:[{}],bounds:[29568,29568,30848,30848],maxChunks:6400,terrain:{mode:'flat-provisional',groundY:0},writer:'overlay.py',python:'python',levelTemplate:{path:'level.dat'},leasePath:'lease.json'};
assert.equal(validateRecovery(job),job);
assert.throws(()=>validateRecovery({...job,maxChunks:6399}),/6400/);
assert.throws(()=>validateRecovery({...job,bounds:[0,0,4096,4096]}),/6400/);
assert.throws(()=>validateRecovery({...job,terrain:{mode:'surveyed',groundY:0}}),/Terrain/);
assert.throws(()=>validateRecovery({...job,provenance:[]}),/Bound/);
console.log('writer recovery contract PASS');
