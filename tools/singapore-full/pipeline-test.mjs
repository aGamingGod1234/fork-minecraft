import assert from 'node:assert/strict';
import {GRID,project,inverse,adapter,selectElements} from './pipeline.mjs';
const origin=project(103.83333333333333,1.366666666666667);
assert.ok(Math.abs(origin[0]-28001.642)<0.0001);
assert.ok(Math.abs(origin[1]-(60000-38744.572))<0.0001);
let checks=2;
for(const lon of [103.55,103.75,103.95,104.45])for(const lat of [1.15,1.283,1.5]){
  const p=project(lon,lat),q=inverse(...p);assert.ok(Math.abs(q[0]-lon)<1e-9&&Math.abs(q[1]-lat)<1e-9);checks++;
}
const p=project(103.8488,1.283),x=Math.floor(p[0]/16)*16,z=Math.floor(p[1]/16)*16;
const left=adapter([x-32,z-32],192),right=adapter([x+128-32,z-32],192);
for(let dx=0;dx<=128;dx+=0.125){const [lon,lat]=inverse(x+dx,z+73.25),n={type:'node',id:1,lon,lat};
  const l=left.local(left.map(n)),r=right.local(right.map(n));
  assert.equal(l[0]+x-32,r[0]+x+128-32);assert.equal(l[1]+z-32,r[1]+z-32);checks+=2;
}
const data={elements:[{type:'node',id:1,lon:1,lat:1},{type:'node',id:2,lon:3,lat:3},{type:'way',id:7,nodes:[1,2],tags:{highway:'primary'}}]};
assert.equal(selectElements(data,[1.5,1.5,2.5,2.5]).elements.length,3);checks++;
assert.throws(()=>selectElements({elements:[data.elements[2]]},[0,0,4,4]),/Incomplete/);checks++;
console.log(JSON.stringify({status:'PASS',checks,grid:GRID,smokeOrigin:[x,z],adjacentOrigin:[x+128,z]}));
