import fs from 'node:fs';import crypto from 'node:crypto';
const dir='assets/fork-world/artifacts/expand-1306';fs.mkdirSync(dir,{recursive:true});
const bbox={south:1.278400648,west:103.845399492,north:1.287599352,east:103.854600508};
const url=`https://api.openstreetmap.org/api/0.6/map?bbox=${bbox.west},${bbox.south},${bbox.east},${bbox.north}`;
const rawPath=dir+'/singapore-1024.osm',record={url,bbox,startedUtc:new Date().toISOString(),status:'REQUESTING',attempts:1};
if(fs.existsSync(rawPath))throw Error('Raw source already exists; no refetch');
if(Date.now()>=Date.parse('2026-09-13T05:18:30Z'))throw Error('Acquisition deadline');
try{const r=await fetch(url,{headers:{'User-Agent':'FORK-world-build/1.0 (OpenStreetMap attribution retained)'},signal:AbortSignal.timeout(45000)});if(!r.ok)throw Error('OSM API HTTP '+r.status);const text=await r.text();if(!text.includes('<osm')||!text.includes('</osm>'))throw Error('Incomplete OSM XML');fs.writeFileSync(rawPath,text,{flag:'wx'});record.bytes=Buffer.byteLength(text);record.sha256=crypto.createHash('sha256').update(text).digest('hex');record.status='ACQUIRED';}catch(e){record.status='FAILED_NO_RETRY';record.error=String(e);record.cause=String(e.cause??'');process.exitCode=1;}record.finishedUtc=new Date().toISOString();fs.writeFileSync(dir+'/source-acquisition.json',JSON.stringify(record,null,2)+'\n');console.log(JSON.stringify(record));
