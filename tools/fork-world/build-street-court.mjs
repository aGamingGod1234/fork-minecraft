import fs from 'node:fs';
import assert from 'node:assert/strict';
const old=JSON.parse(fs.readFileSync('data/fork-world/court-geographic-ground0.json'));
const d=structuredClone(old), cells=new Map();
// Full cubes only: no inventories, block entities, unstable plants or connection states.
d.palette=['air','smooth_stone','polished_andesite','white_concrete','cyan_concrete','orange_concrete','glass','dark_prismarine','yellow_concrete','sea_lantern','light_gray_concrete','stone_bricks','smooth_quartz','gray_concrete','brown_terracotta'].map(x=>'minecraft:'+x);
const put=(x,y,z,name)=>{assert([x,y,z].every(n=>n>=0&&n<16));const p=d.palette.indexOf('minecraft:'+name);assert(p>=0);cells.set([x,y,z].join(','),p);};
const box=(xa,xb,ya,yb,za,zb,n)=>{for(let x=xa;x<=xb;x++)for(let y=ya;y<=yb;y++)for(let z=za;z<=zb;z++)put(x,y,z,n);};
box(0,15,0,0,0,15,'stone_bricks');
// A continuous street pavement, with a quiet contrasting fork inlaid in the floor.
box(0,15,0,0,6,8,'smooth_stone');
for(let z=7;z<=15;z++){put(7,0,z,'polished_andesite');put(9,0,z,'polished_andesite');}
for(let i=0;i<=4;i++)for(let w=0;w<2;w++){put(7-i,0,9-i+w,'smooth_quartz');put(9+i,0,9-i+w,'smooth_quartz');}
box(7,9,0,0,12,15,'smooth_quartz');
// Shared two-storey frontage. Doors remain open and the scored rooms stay at ground level.
for(const [a,b,accent] of [[1,6,'smooth_quartz'],[10,14,'brown_terracotta']]){
 box(a,b,0,0,1,5,'smooth_stone');box(a,b,1,10,1,1,accent);
 box(a,a,1,10,2,5,accent);box(b,b,1,10,2,5,accent);
 box(a,b,5,6,1,5,'smooth_stone');box(a,b,10,10,1,5,'smooth_stone');
 box(a,b,11,11,1,5,'polished_andesite');
 box(a+1,b-1,7,9,5,5,'glass');box(a+1,b-1,2,3,1,1,'glass');
 for(let x=a;x<=b;x+=2)put(x,6,5,'polished_andesite');
 box(a,b,4,4,5,6,'gray_concrete');
}
// Piers and a recessed central service passage tie the two shops into one frontage.
for(const x of [0,7,9,15])box(x,x,1,10,1,1,'polished_andesite');
box(0,15,11,11,1,1,'polished_andesite');
box(1,2,1,1,5,5,'smooth_quartz');box(5,6,1,1,5,5,'smooth_quartz');
box(10,10,1,3,2,3,'polished_andesite');
put(5,1,3,'polished_andesite');put(5,1,4,'polished_andesite');
// Medical cross and workshop W are legible plain-block signs, not restorable sign entities.
box(2,5,7,9,5,5,'gray_concrete');for(const [x,y] of [[3,7],[3,8],[3,9],[2,8],[4,8]])put(x,y,5,'white_concrete');
box(10,14,7,9,5,5,'gray_concrete');for(const [x,y] of [[10,9],[10,8],[11,7],[12,8],[13,7],[14,8],[14,9]])put(x,y,5,'white_concrete');
// Retain the exact generator silhouette and locations, now a subdued utility cabinet.
box(7,9,1,1,10,11,'gray_concrete');put(8,2,11,'polished_andesite');
put(3,5,3,'sea_lantern');put(12,5,3,'sea_lantern');
for(const x of [1,6,10,14])put(x,4,6,'sea_lantern');
d.cells=[...cells].filter(([,p])=>p!==0).map(([k,p])=>[...k.split(',').map(Number),p]).sort((a,b)=>a[0]-b[0]||a[1]-b[1]||a[2]-b[2]);
d.layoutId='fork-market-street-frontage-v2';d.coverage='Designed gameplay clinic/workshop frontage within mapped Market Street district. Not a surveyed real business or authentic floorplan.';
d.runtimeAcceptance='NEW SESSION REQUIRED: Main must regenerate INITIAL from this layout, then verify placement, projection, demolition and restore. Never reload into the committed LiveR1 session.';
d.signage={clinic:'White medical cross above left entrance',workshop:'White W above right entrance',method:'Full-cube glyphs; no block entities',literalLabelsForExistingUI:{clinic:'Clinic',workshop:'Workshop'}};
for(const key of ['origin','size','anchors','courierPath','demolitionCells','outsideSentinels','playerArrival'])assert.deepEqual(d[key],old[key]);
const m=new Map(d.cells.map(([x,y,z,p])=>[[x,y,z].join(','),p]));
for(const [x,y,z] of [...Object.values(d.anchors),...d.courierPath,d.playerArrival])for(let dy=0;dy<3;dy++)assert(!m.has([x,y+dy,z].join(',')),'headroom');
for(let x=0;x<16;x++)for(let z=0;z<16;z++)assert(m.has([x,0,z].join(',')));
for(const p of d.demolitionCells)assert(m.has(p.join(',')));assert(d.demolitionCells.length<=64);
const queue=[d.playerArrival],seen=new Set([d.playerArrival.join(',')]);while(queue.length){const [x,y,z]=queue.pop();for(const [a,b] of [[x-1,z],[x+1,z],[x,z-1],[x,z+1]]){const k=[a,y,b].join(',');if(a>=0&&a<16&&b>=0&&b<16&&!seen.has(k)&&![0,1,2].some(dy=>m.has([a,y+dy,b].join(',')))){seen.add(k);queue.push([a,y,b]);}}}for(const p of Object.values(d.anchors))assert(seen.has(p.join(',')),'reachable anchor');
fs.writeFileSync('data/fork-world/court-market-street-v2.json',JSON.stringify(d,null,2)+'\n');
console.log(JSON.stringify({cells:d.cells.length,origin:d.origin,demolitionCells:d.demolitionCells.length,checks:'unchanged anchors/transform/routes/sentinels; full ground; three-block headroom; reachability PASS',requiredAdditionalPalette:d.palette.slice(old.palette.length)}));
