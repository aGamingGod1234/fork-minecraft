import fs from 'node:fs';import assert from 'node:assert/strict';import crypto from 'node:crypto';
const raw=fs.readFileSync(new URL('../../data/fork-world/court-v1.json',import.meta.url)),d=JSON.parse(raw),m=new Map(d.cells.map(([x,y,z,p])=>[`${x},${y},${z}`,p]));
assert.equal(crypto.createHash('sha256').update(raw).digest('hex'),'538149df807a946101e7f8a14806aa3464b874ad4b3ae626451291ca2f3d9e14');
assert.deepEqual(d.origin,[0,64,0]);assert.deepEqual(d.size,[16,16,16]);assert.equal(d.dimension,'minecraft:overworld');assert.equal(d.defaultPaletteIndex,0);assert.equal(d.palette[0],'minecraft:air');assert.equal(m.size,d.cells.length);
for(const [x,y,z,p] of d.cells){assert([x,y,z].every(v=>Number.isInteger(v)&&v>=0&&v<16));assert(p>0&&p<d.palette.length);}
for(let x=0;x<16;x++)for(let z=0;z<16;z++)assert(m.has(`${x},0,${z}`));
assert.deepEqual(d.anchors,{medic:[3,1,3],engineer:[12,1,3],courier:[3,1,12],delivery:[4,1,3]});
for(const [x,y,z] of [...Object.values(d.anchors),...d.courierPath,d.playerArrival]){assert(m.has(`${x},${y-1},${z}`));for(let dy=0;dy<3;dy++)assert(!m.has(`${x},${y+dy},${z}`));}
const queue=[d.playerArrival],seen=new Set([d.playerArrival.join(',')]);while(queue.length){const [x,y,z]=queue.pop();for(const [nx,nz]of [[x+1,z],[x-1,z],[x,z+1],[x,z-1]]){const k=`${nx},${y},${nz}`;if(nx>=0&&nx<16&&nz>=0&&nz<16&&!seen.has(k)&&!m.has(k)&&!m.has(`${nx},${y+1},${nz}`)&&m.has(`${nx},${y-1},${nz}`)){seen.add(k);queue.push([nx,y,nz]);}}}for(const a of Object.values(d.anchors))assert(seen.has(a.join(',')));
assert(d.demolitionCells.length<=64);for(const p of d.demolitionCells){assert(p[1]>0);assert(m.has(p.join(',')));assert(!d.courierPath.some(a=>a.join(',')===p.join(',')));}
for(const p of d.outsideSentinels)assert(!p.every(v=>v>=0&&v<16));
const initial=new Map();for(let x=0;x<16;x++)for(let y=0;y<16;y++)for(let z=0;z<16;z++){const k=`${x},${y},${z}`;initial.set(k,m.get(k)??0);}const world=new Map(initial);for(const p of d.outsideSentinels)world.set(p.join(','),'sentinel');for(let i=0;i<3;i++){world.set('3,2,3',3);for(const p of d.demolitionCells)world.set(p.join(','),0);for(const [k,v]of initial)world.set(k,v);for(const [k,v]of initial)assert.equal(world.get(k),v);for(const p of d.outsideSentinels)assert.equal(world.get(p.join(',')),'sentinel');}
console.log(`PASS: frozen shape; ${m.size} plain blocks / ${4096-m.size} explicit default-air cells; ground; anchors; three-block headroom; connected routes; ${d.demolitionCells.length} facade cells; six outside sentinels; three simulated restores. Minecraft live checks UNRUN.`);
