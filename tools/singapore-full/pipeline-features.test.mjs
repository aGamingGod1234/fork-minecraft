import assert from 'node:assert/strict';
import fs from 'node:fs';
import {assembleRings,projectBuildings} from './pipeline-features.mjs';
assert.deepEqual(assembleRings([{id:1,nodes:[1,2,3]},{id:2,nodes:[1,4,3]}]),[[1,2,3,4,1]]);
assert.throws(()=>assembleRings([{id:1,nodes:[1,2,3]}]),/unclosed/);
const source={elements:[]};let nid=1;
function ring(wayId,coordinates,tags={}){const ids=coordinates.map(([lon,lat])=>{source.elements.push({type:'node',id:nid,lon,lat});return nid++;});ids.push(ids[0]);source.elements.push({type:'way',id:wayId,nodes:ids,tags});return ids;}
const outer=ring(10,[[0,0],[20,0],[20,20],[0,20]],{building:'yes',name:'Outer'});
ring(11,[[5,5],[10,5],[10,10],[5,10]]);
source.elements.push({type:'relation',id:12,tags:{type:'multipolygon',building:'yes',height:'30'},members:[{type:'way',ref:10,role:'outer'},{type:'way',ref:11,role:'inner'}]});
ring(20,[[30,0],[50,0],[50,20],[30,20]],{building:'yes'});
ring(21,[[32,2],[40,2],[40,10],[32,10]],{'building:part':'yes',height:'24'});
source.elements.push({type:'relation',id:22,tags:{type:'building'},members:[{type:'way',ref:20,role:'outline'},{type:'way',ref:21,role:'part'}]});
source.elements.push({type:'way',id:99,nodes:[900,901,902,900],tags:{building:'yes'}});
source.elements.push({type:'relation',id:98,tags:{type:'multipolygon',building:'yes'},members:[{type:'way',ref:999,role:'outer'}]});
const original=JSON.stringify(source),{collection,manifest}=projectBuildings(source,{project:(x,y)=>[x,y]});
assert.equal(JSON.stringify(source),original);
assert.equal(collection.features.length,3);
const mp=collection.features.find(f=>f.id==='relation/12');assert.equal(mp.geometry.coordinates.length,2);assert.equal(mp.properties.height,'30');assert.equal(mp.properties.tags.height,'30');assert.ok(!collection.features.some(f=>f.id==='way/10'));
const part=collection.features.find(f=>f.id==='way/21'),outline=collection.features.find(f=>f.id==='way/20');assert.equal(part.properties.parent_identity,'way/20');assert.equal(outline.properties.renderOutline,false);assert.deepEqual(outline.properties.partFeatureIds,['way/21']);assert.equal(manifest.exclusions.length,2);
if(process.argv[2]){const {project}=await import('./pipeline.mjs');const raw=fs.readFileSync(process.argv[2],'utf8'),actual=JSON.parse(raw),result=projectBuildings(actual,{project});for(const id of [146335667,393100853]){const originalWay=actual.elements.find(e=>e.type==='way'&&e.id===id),feature=result.collection.features.find(f=>f.id===`way/${id}`);assert.ok(feature);assert.equal(feature.properties.sourceClass,'osm-mapped');const node=actual.elements.find(e=>e.type==='node'&&e.id===originalWay.nodes[0]),expected=project(node.lon,node.lat);assert.ok(feature.geometry.coordinates[0].some(p=>p[0]===expected[0]&&p[1]===expected[1]));}console.log(JSON.stringify({status:'passed',realSourceFeatures:result.manifest.featureCount,exclusions:result.manifest.excludedCount,checks:['reversed ring segments','missing references','hole assignment','duplicate member suppression','parent part identity','unmodified source','real Samsung and CapitaGreen SVY21 coordinates']}));}
else console.log('pipeline-features tests passed');
