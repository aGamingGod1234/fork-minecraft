import fs from 'node:fs';import assert from 'node:assert/strict';import crypto from 'node:crypto';
import {readRegion,sections,unpack} from './nbt-region.mjs';
const base='assets/fork-world/artifacts/visual-1210',m=JSON.parse(fs.readFileSync(base+'/patch-manifest.json'));
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
assert.equal(sha(m.sourceWorld+'/region/r.0.0.mca'),m.sourceRegionSha256);assert.equal(sha(m.targetWorld+'/region/r.0.0.mca'),m.targetRegionSha256);
const before=readRegion(m.sourceWorld+'/region/r.0.0.mca'),after=readRegion(m.targetWorld+'/region/r.0.0.mca'),expected=new Map();
for(const c of m.changes){const k=[c.x,c.y,c.z].join(',');const previous=expected.get(k);if(previous)assert.equal(previous.after,c.before);expected.set(k,{...c,before:previous?.before??c.before});}
let changed=0,occupancyChecks=0;const changedChunks=[];
const air=n=>/^(minecraft:air|minecraft:cave_air|minecraft:void_air)$/.test(n);
for(let ci=0;ci<before.length;ci++){
 const b=before[ci],a=after[ci];assert.equal(a.slot,b.slot);const omit=v=>Object.fromEntries(Object.entries(v).filter(([k])=>!['sections','isLightOn'].includes(k)));assert.deepEqual(omit(a.root.value),omit(b.root.value));
 const bs=sections(b),as=sections(a);assert.equal(as.length,bs.length);let count=0;
 for(let si=0;si<bs.length;si++){
  const x=unpack(bs[si]),y=unpack(as[si]);if(!x){assert(!y);continue;}assert(y);assert.equal(bs[si].Y.value,as[si].Y.value);
  for(let i=0;i<4096;i++){
   const old=x.pal[x.blocks[i]],next=y.pal[y.blocks[i]],pos=[b.root.value.xPos.value*16+i%16,bs[si].Y.value*16+Math.floor(i/256),b.root.value.zPos.value*16+Math.floor(i/16)%16],k=pos.join(',');
   const on=old.Name.value,nn=next.Name.value;assert.equal(air(on),air(nn),'Occupancy changed '+k);occupancyChecks++;
   if(JSON.stringify(old)!==JSON.stringify(next)){const e=expected.get(k);assert(e,'Unmanifested change '+k);assert.equal(on,e.before);assert.equal(nn,e.after);changed++;count++;assert(!(pos[0]>=46&&pos[0]<62&&pos[2]>=22&&pos[2]<38&&pos[1]>=0&&pos[1]<16));}
  }
 }
 if(count)changedChunks.push({slot:a.slot,changes:count});
}
assert.equal(changed,[...expected.values()].filter(c=>c.before!==c.after).length);
const court=JSON.parse(fs.readFileSync(m.courtFile)),oldCourt=JSON.parse(fs.readFileSync('data/fork-world/court-geographic-ground0.json'));
for(const k of ['origin','size','anchors','courierPath','demolitionCells','outsideSentinels','playerArrival'])assert.deepEqual(court[k],oldCourt[k]);
const map=new Map(court.cells.map(([x,y,z,p])=>[[x,y,z].join(','),court.palette[p]]));
for(const [x,y,z] of [...Object.values(court.anchors),...court.courierPath,court.playerArrival]){assert(map.has([x,y-1,z].join(',')));for(let dy=0;dy<3;dy++)assert(!map.has([x,y+dy,z].join(',')));}
const report={status:'PASS structural saved-region verification; in-game appearance/relighting/restore pending Main',checkedUtc:new Date().toISOString(),changedCells:changed,occupancyChecks,changedChunks,allOccupancyUnchanged:true,allColumnHeightsUnchanged:true,sourceHashUnchanged:true,courtAndSentinelsExcluded:true,anchorContractUnchanged:true,courtHeadroom:true,sourceHeightsCaveat:'Preserves generated heights including inherited source/default discrepancies; does not certify survey accuracy.'};
fs.writeFileSync(base+'/verification.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({...report,changedChunks:changedChunks.length}));
