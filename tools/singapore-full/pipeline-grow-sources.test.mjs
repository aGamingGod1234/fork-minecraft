import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {referenceClosure,verifyRawSource} from './pipeline-grow-sources.mjs';

const value={elements:[{type:'node',id:1,lon:103.85,lat:1.283},{type:'node',id:2,lon:103.851,lat:1.283},
  {type:'way',id:1,nodes:[1,2],tags:{highway:'residential'}},{type:'relation',id:1,members:[{type:'way',ref:1}]}]};
const sha256=crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex');
const raw={value,sha256};
const national='a'.repeat(64);
const expected={sourceSha256:sha256,nationalSourceSha256:national,coreBounds:[30720,29696,30976,29952],halo:32};
const receipt={subsetSha256:sha256,closureSha256:sha256,sourceSha256:national,referenceComplete:true,
  boundsEPSG3414:[30688,30016,31008,30336],counts:{node:2,way:1,relation:1}};
assert(verifyRawSource(raw,receipt,expected).complete);
assert.equal(referenceClosure(value).checkedReferences,3);
assert.equal(referenceClosure({elements:value.elements.slice(1)}).missingCount,1);
assert.throws(()=>referenceClosure({elements:[...value.elements,value.elements[0]]}),/Duplicate/);
assert.throws(()=>verifyRawSource(raw,{...receipt,referenceComplete:false},expected),/completeness/);
assert.throws(()=>verifyRawSource(raw,{...receipt,subsetSha256:'b'.repeat(64)},expected),/hash chain/);
assert.throws(()=>verifyRawSource(raw,{...receipt,boundsEPSG3414:[30688,30016,31008,30335]},expected),/bounds/);
assert.throws(()=>verifyRawSource(raw,{...receipt,counts:{node:3,way:1,relation:1}},expected),/counts/);
const broken={...raw,value:{elements:[...value.elements,{type:'relation',id:2,members:[{type:'relation',ref:99}]}]}};
assert.throws(()=>verifyRawSource(broken,receipt,expected),/references are incomplete/);
console.log(JSON.stringify({passed:true,checks:9,scope:'reference closure, immutable source chain, bounds and counts'}));
