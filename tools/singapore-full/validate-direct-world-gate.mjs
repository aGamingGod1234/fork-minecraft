import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath, pathToFileURL} from 'node:url';

const HERE = fileURLToPath(import.meta.url);
const CELLS = 1024 * 1024 * 384;
const need = (ok, message) => { if (!ok) throw Error('Direct world gate: ' + message); };
const key = p => process.platform === 'win32' ? p.toLowerCase() : p;
const canonical = p => fs.realpathSync(p);
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const validSha = s => typeof s === 'string' && /^[a-f0-9]{64}$/i.test(s);
const same = (a,b) => JSON.stringify(a) === JSON.stringify(b);
const boundsEqual = (a,b) => Array.isArray(a) && a.length === b.length && a.every((v,i)=>Number.isSafeInteger(v) && v===b[i]);
const within = (root,p) => { const r=path.relative(key(root),key(p)); return r!=='' && r!=='..' && !r.startsWith('..'+path.sep) && !path.isAbsolute(r); };
function relative(root,p,label) {
  need(typeof p==='string' && p.length && !path.isAbsolute(p) && !path.win32.isAbsolute(p) && !p.split(/[\\/]/).includes('..'),label+' must be relative and contained');
  const resolved=canonical(path.resolve(root,p));
  need(within(root,resolved),label+' escapes root'); return resolved;
}
function fileRecord(p) {
  need(typeof p==='string' && path.isAbsolute(p),'absolute file path required');
  const file=canonical(p),fd=fs.openSync(file,'r');
  try {
    const before=fs.fstatSync(fd); need(before.isFile(),'regular file required');
    const hash=crypto.createHash('sha256'),buffer=Buffer.alloc(1024*1024); let n;
    while((n=fs.readSync(fd,buffer,0,buffer.length,null))>0)hash.update(buffer.subarray(0,n));
    const after=fs.fstatSync(fd);
    need(before.size===after.size && before.mtimeMs===after.mtimeMs && before.ctimeMs===after.ctimeMs,'file changed while hashing: '+file);
    return {path:file,bytes:before.size,sha256:hash.digest('hex')};
  } finally { fs.closeSync(fd); }
}
function matches(record,actual,label) {
  need(record && Number.isSafeInteger(record.bytes) && record.bytes>=0 && validSha(record.sha256),label+' requires bytes and SHA256');
  need(record.bytes===actual.bytes && record.sha256.toLowerCase()===actual.sha256,label+' bytes/SHA256 mismatch: '+actual.path);
}
function add(map,r,label) { need(!map.has(key(r.path)),label+' duplicate path: '+r.path); map.set(key(r.path),r); }
function equalMaps(a,b,label) {
  need(a.size===b.size,label+' file count mismatch');
  for(const [p,r] of a) { const other=b.get(p);need(other,label+' path mismatch: '+p);matches(other,r,label); }
}
function emptyArray(o,k,label) { need(Array.isArray(o[k]) && o[k].length===0,label+' '+k+' must be empty'); }

/** Produce structural evidence only. Every dependency is rehashed; no materialization or oracle is run. */
export async function createDirectWorldGate(options) {
  const cache=new Map();
  const actual=p=>{const c=canonical(p),k=key(c);if(!cache.has(k))cache.set(k,fileRecord(c));return cache.get(k);};
  const bound=(r,label)=>{need(r && typeof r.path==='string' && path.isAbsolute(r.path),label+' absolute record path required');const a=actual(r.path);matches(r,a,label);return a;};
  const pinned=(p,pin,label)=>{const h=typeof pin==='string'?pin:pin?.sha256;need(validSha(h),label+' external SHA256 pin required');const a=actual(path.resolve(p));need(a.sha256===h.toLowerCase(),label+' pin mismatch');return a;};
  const json=r=>{const bytes=fs.readFileSync(r.path);need(bytes.length===r.bytes && sha(bytes)===r.sha256,'JSON changed since pin: '+r.path);return JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''));};
  const records=(rs,label)=>{need(Array.isArray(rs)&&rs.length>0,label+' nonempty records required');const m=new Map();for(const r of rs)add(m,bound(r,label),label);return m;};
  const pathSame=(p,q,label)=>need(typeof p==='string' && path.isAbsolute(p) && key(canonical(p))===key(canonical(q)),label+' exact path mismatch');
  const expected=options.expected??{};
  const materialFile=pinned(options.materialize,expected.materialize,'materialization');
  const jobFile=pinned(options.job,expected.job,'original job');
  const oracleFile=pinned(options.oracle,expected.oracle,'oracle');
  const material=json(materialFile),job=json(jobFile),oracle=json(oracleFile);
  need(material.schemaVersion===1 && material.kind==='fork-writer-only-recovery' && material.status==='WRITTEN_AWAITING_INDEPENDENT_GATES','completed writer-only materialization required');
  need(material.inputsUnchanged===true && material.exit?.code===0 && !material.exit.signal && !material.timedOut && typeof material.completedUtc==='string' && Number.isFinite(Date.parse(material.completedUtc)),'successful unchanged materialization required');
  need(material.productionAccepted===false && material.runtimeAccepted===false && material.terrainSurveyed===false,'materialization must remain explicitly unaccepted');
  pathSame(material.originalJob?.path,jobFile.path,'original job');matches(material.originalJob,jobFile,'original job');
  need(job.schemaVersion===1 && Array.isArray(job.tiles) && job.tiles.length===1,'one frozen original tile required');
  const tile=job.tiles[0],origin=tile.coreOrigin;
  need(typeof tile.id==='string' && /^[a-z0-9_-]+$/i.test(tile.id) && Array.isArray(origin) && origin.length===2 && origin.every(v=>Number.isSafeInteger(v)&&v%16===0) && tile.coreSize===1024 && tile.halo===128,'exact chunk-aligned 1024 core and 128 halo required');
  const core=[...origin,origin[0]+1024,origin[1]+1024],render=[origin[0]-128,origin[1]-128,origin[0]+1152,origin[1]+1152];
  need([...core,...render].every(Number.isSafeInteger),'bounds overflow');
  need(boundsEqual(material.coreBounds,core)&&boundsEqual(material.bounds,render),'materialization core/render bounds mismatch');
  need(job.terrain?.mode==='flat-provisional' && job.terrain.groundY===0,'recognized provisional flat ground required');
  const recoveryFile=actual(path.join(path.dirname(materialFile.path),'job.json')),recovery=json(recoveryFile);
  need(recovery.schemaVersion===1 && recovery.kind==='fork-writer-only-recovery' && recovery.id===material.jobId && recovery.maxChunks===6400,'recovery job identity mismatch');
  need(boundsEqual(recovery.coreBounds,core)&&boundsEqual(recovery.bounds,render)&&same(recovery.terrain,job.terrain),'recovery bounds/terrain mismatch');
  for(const field of ['originalJob','originalFailedResult','source']) {
    const left=bound(material[field],'material '+field),right=bound(recovery[field],'recovery '+field);
    pathSame(left.path,right.path,field);matches(left,right,field);
  }
  const originalResultFile=bound(material.originalFailedResult,'original failed result'),original=json(originalResultFile);
  const originalRoot=canonical(path.dirname(originalResultFile.path));
  pathSame(jobFile.path,path.join(originalRoot,'job.json'),'original frozen job location');
  need(original.schemaVersion===1 && original.status==='failed' && original.tiles?.length===1,'failed original single-tile result required');
  const oldTile=original.tiles[0],stages=original.stages;
  need(oldTile.id===tile.id && boundsEqual(oldTile.coreOrigin,origin) && oldTile.coreSize===1024 && oldTile.halo===128 && boundsEqual(oldTile.renderBounds,render),'original tile differs from frozen job');
  need(Array.isArray(stages)&&stages.length>1&&stages.at(-1).status==='failed'&&stages.at(-1).name.endsWith('-world')&&stages.slice(0,-1).every(s=>s.status==='passed'),'only completed source stages preceding failed writer may be reused');
  const originalSources=records([oldTile.runs,oldTile.roadRuns,oldTile.coastRuns].filter(Boolean),'original completed runs');
  const tileRoot=relative(originalRoot,tile.id,'original tile');
  for(const r of originalSources.values())need(within(tileRoot,r.path),'original run outside original tile');
  const sources=records(material.runs,'materialization runs');
  equalMaps(sources,originalSources,'materialization/original runs');
  equalMaps(sources,records(recovery.runs,'recovery runs'),'recovery/original runs');
  need(Array.isArray(options.runs)&&options.runs.length>0,'explicit consumed run paths required');
  const requested=new Map();for(const p of options.runs)add(requested,actual(path.resolve(p)),'requested runs');
  equalMaps(sources,requested,'requested/original runs');
  const source=bound(job.source,'original source');
  for(const r of [original.source,material.source,recovery.source]) {const a=bound(r,'source provenance');pathSame(a.path,source.path,'source provenance');matches(a,source,'source provenance');}
  const provenance=records(material.provenance,'provenance');
  equalMaps(provenance,records(recovery.provenance,'recovery provenance'),'recovery/material provenance');
  for(const r of [originalResultFile,jobFile,source])need(provenance.has(key(r.path)),'provenance missing original record');
  const producerArtifacts=records(job.artifacts,'original producer artifacts');
  equalMaps(producerArtifacts,records(recovery.producerArtifacts,'recovery producer artifacts'),'producer artifacts');
  const writerArtifacts=records(material.writerArtifacts,'writer artifacts');
  equalMaps(writerArtifacts,records(recovery.artifacts,'recovery writer artifacts'),'writer artifacts');
  for(const p of [recovery.writer,recovery.adapter])need(typeof p==='string'&&writerArtifacts.has(key(canonical(p))),'executed writer/adapter missing from artifact pins');
  pathSame(recovery.python,job.tools?.python,'executed Python');
  need(producerArtifacts.has(key(canonical(recovery.python))),'executed Python missing from original artifacts');
  const templates=records(material.templateDependencies,'template dependencies');
  equalMaps(templates,records(recovery.templateDependencies,'recovery template dependencies'),'template dependencies');
  const template=bound(job.levelTemplate,'original level template');
  pathSame(recovery.levelTemplate?.path,template.path,'recovery level template');matches(recovery.levelTemplate,template,'recovery level template');
  const expectedTemplates=new Map();add(expectedTemplates,template,'expected templates');
  const aux=actual(path.join(path.dirname(template.path),'data/minecraft/world_gen_settings.dat'));
  need(aux.sha256===job.levelTemplate.worldGenSettingsSha256,'external template settings pin mismatch');add(expectedTemplates,aux,'expected templates');
  equalMaps(templates,expectedTemplates,'original template dependencies');
  const worldRoot=canonical(path.resolve(options.world)),writerFile=actual(path.resolve(options.writer));
  pathSame(material.worldPath,worldRoot,'materialized world');pathSame(material.writerManifestPath,writerFile.path,'materialized writer');
  pathSame(worldRoot,path.join(path.dirname(materialFile.path),'world'),'recovery world location');
  pathSame(writerFile.path,path.join(path.dirname(materialFile.path),'writer-manifest.json'),'recovery writer location');
  pathSame(material.writerManifest?.path,writerFile.path,'writer receipt');matches(material.writerManifest,writerFile,'writer receipt');
  const expectedArgv=[recovery.python,recovery.writer,...recovery.runs.flatMap(r=>['--runs',r.path]),'--world',material.worldPath,'--bounds',render.join(','),'--manifest',material.writerManifestPath,'--level-template',recovery.levelTemplate.path,'--job-lease',path.join(path.dirname(materialFile.path),'job-lease.json'),'--max-chunks','6400'];
  need(same(material.argv,expectedArgv),'executed recovery argv mismatch');
  const oldArgs=stages.at(-1).argv;
  need(Array.isArray(oldArgs),'original failed writer argv required');
  const oldArgSources=new Map();for(let i=0;i<oldArgs.length;i++)if(oldArgs[i]==='--runs')add(oldArgSources,actual(oldArgs[++i]),'original writer argv');
  equalMaps(sources,oldArgSources,'original writer argv/source runs');
  const wrapper=json(writerFile),writer=wrapper.writer??wrapper;
  need(wrapper.status==='WRITTEN_UNACCEPTED' && wrapper.chunkCount===6400,'completed 6400-chunk writer manifest required');
  need(writer.schemaVersion===1&&writer.kind==='global-block-run-world'&&boundsEqual(writer.bounds,render),'recognized writer with exact render bounds required');
  need(writer.dataVersion===4790&&writer.minecraftTarget==='26.1.2','MC 26.1.2 DataVersion 4790 required');
  need(writer.coordinateFrame?.crs==='EPSG:3414'&&writer.coordinateFrame.blocksPerMeter===1&&writer.coordinateFrame.x==='easting'&&writer.coordinateFrame.z==='60000-northing','global EPSG3414 meter coordinate frame required');
  need(oracle.schemaVersion===1&&oracle.kind==='independent-source-run-fast-world-oracle'&&oracle.status==='PASS'&&boundsEqual(oracle.bounds,core),'passing exact-core fast oracle required');
  for(const k of ['errors','metadataErrors','inputErrors'])emptyArray(oracle,k,'oracle');
  for(const k of ['mismatchedCells','heightmapMismatches','missingColumns','sameLayerConflictingCells','inputErrorCount','metadataErrorCount'])need(oracle[k]===0,'oracle '+k+' must be zero');
  need(oracle.comparedCells===CELLS&&oracle.chunkCount===4096&&oracle.comparedCoreChunkCount===4096&&oracle.allocatedChunkCount===6400,'oracle full 384Y core/chunk coverage required');
  need(oracle.spawnClear===true&&oracle.dataVersion===4790,'oracle modern clear spawn required');
  need(validSha(oracle.expectedIntervalSha256)&&oracle.expectedIntervalSha256===oracle.actualIntervalSha256,'oracle interval digest mismatch');
  const settingsFile=actual(path.resolve(options.settingsValidator??fileURLToPath(new URL('./validate-world-settings.mjs',import.meta.url))));
  need(settingsFile.sha256===oracle.settingsValidatorSha256,'oracle settings validator code changed');
  const {validateWorldSettings}=await import(pathToFileURL(settingsFile.path).href);
  const settings=validateWorldSettings(worldRoot);
  need(settings.status==='PASS'&&settings.dataVersion===4790,'actual modern metadata validation failed');emptyArray(settings,'errors','settings');
  need(same(settings,oracle.worldSettings)&&same(settings.spawn,oracle.spawn),'actual metadata differs from oracle');
  const regionRoot=relative(worldRoot,writer.regionDirectory,'writer region directory');
  pathSame(regionRoot,relative(worldRoot,settings.regionDirectory,'oracle region directory'),'active region directory');
  const outputs=new Map();
  need(Array.isArray(writer.outputs)&&writer.outputs.length>0,'writer outputs required');
  for(const r of writer.outputs){const a=actual(relative(worldRoot,r.path,'writer output'));matches(r,a,'writer output');add(outputs,a,'writer outputs');}
  const inventory=new Set();
  function visit(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,e.name);need(!e.isSymbolicLink(),'world links forbidden');if(e.isDirectory())visit(p);else{need(e.isFile(),'nonregular world file');const c=canonical(p);need(within(worldRoot,c)&&outputs.has(key(c)),'unlisted actual world file: '+c);need(!inventory.has(key(c)),'duplicate actual world path');inventory.add(key(c));}}}
  visit(worldRoot);need(inventory.size===outputs.size,'actual world/writer inventory mismatch');
  const oracleOutputs=new Map();
  need(Array.isArray(oracle.worldFiles)&&oracle.worldFiles.length>0&&Array.isArray(settings.files),'oracle world file inventory required');
  for(const r of oracle.worldFiles){need(/^r\.-?\d+\.-?\d+\.mca$/.test(r.path),'oracle region basename required');const p=relative(regionRoot,r.path,'oracle region'),a=outputs.get(key(p));need(a,'oracle region missing from writer');matches(r,a,'oracle region');add(oracleOutputs,a,'oracle outputs');}
  for(const r of settings.files){const p=relative(worldRoot,r.path,'oracle metadata'),a=outputs.get(key(p));need(a,'oracle metadata missing from writer');matches(r,a,'oracle metadata');add(oracleOutputs,a,'oracle outputs');}
  equalMaps(outputs,oracleOutputs,'oracle/writer outputs');
  const byName=new Map();for(const r of sources.values()){const n=key(path.basename(r.path));need(!byName.has(n),'ambiguous source basenames');byName.set(n,r);}
  const writerInputs=new Map();need(Array.isArray(writer.inputs)&&writer.inputs.length>0,'writer inputs required');
  for(const r of writer.inputs){need(typeof r.name==='string'&&r.name.length&&!/[\\/]/.test(r.name),'writer input basename required');const a=byName.get(key(r.name));need(a,'writer input missing from frozen runs');matches(r,a,'writer input');add(writerInputs,a,'writer inputs');}
  equalMaps(sources,writerInputs,'writer/source maps');
  equalMaps(sources,records(oracle.sourceFiles,'oracle sources'),'oracle/source maps');
  equalMaps(sources,records(oracle.inputs,'oracle inputs'),'oracle input aliases');
  // This receipt scopes logical comparisons to the core. Halo files are hash-bound, not block-compared.
  return {
    schemaVersion:1,kind:'actual-world-structural-validation',status:'PASS',synthetic:false,
    writerManifestSha256:writerFile.sha256,comparedBlocks:CELLS,mismatches:0,
    worldOutputs:writer.outputs,worldRoot,coreBounds:core,renderBounds:render,bounds:core,
    chunkCount:4096,allocatedChunkCount:6400,verticalBounds:[-64,320],
    coordinateFrame:writer.coordinateFrame,dataVersion:4790,minecraftTarget:'26.1.2',
    evidence:{materialization:materialFile,originalJob:jobFile,recoveryJob:recoveryFile,
      originalFailedResult:originalResultFile,oracleProof:oracleFile,writerManifest:writerFile,
      source,sourceRuns:[...sources.values()],worldFiles:[...outputs.values()],
      producerArtifacts:[...producerArtifacts.values()],writerArtifacts:[...writerArtifacts.values()],
      provenance:[...provenance.values()],templateDependencies:[...templates.values()],
      settingsValidator:settingsFile,gateModule:actual(HERE)},
    reload:{materialize:materialFile.path,job:jobFile.path,oracle:oracleFile.path,
      world:worldRoot,writer:writerFile.path,runs:[...sources.values()].map(r=>r.path),
      settingsValidator:settingsFile.path,expected:{materialize:materialFile.sha256,job:jobFile.sha256,oracle:oracleFile.sha256}},
    errors:[],runtimeAccepted:false,benchmarkAccepted:false,terrainAccepted:false,fullWorldAccepted:false
  };
}

/** The caller must pin the gate itself. Reload rederives every field from bound files. */
export async function loadDirectWorldGate(gatePath,expectedSha256) {
  need(validSha(expectedSha256),'external gate SHA256 pin required');
  const record=fileRecord(path.resolve(gatePath));need(record.sha256===expectedSha256.toLowerCase(),'gate pin mismatch');
  const bytes=fs.readFileSync(record.path);need(sha(bytes)===record.sha256,'gate changed during load');
  const gate=JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''));
  need(gate.kind==='actual-world-structural-validation'&&gate.status==='PASS'&&gate.synthetic===false&&gate.reload,'recognized direct gate required');
  const fresh=await createDirectWorldGate(gate.reload);
  need(same(gate,fresh),'gate fields differ from independently reloaded evidence');
  return fresh;
}
if(process.argv[1]&&path.resolve(process.argv[1])===HERE) {
  const opts={runs:[],expected:{}};
  try {
    const flags={'--materialize':'materialize','--world':'world','--writer':'writer','--job':'job','--oracle':'oracle','--settings-validator':'settingsValidator','--out':'out','--load':'load','--sha256':'sha256'};
    for(let i=2;i<process.argv.length;i+=2){const f=process.argv[i],v=process.argv[i+1];need(v&&!v.startsWith('--'),'flag value required: '+f);if(f==='--runs')opts.runs.push(v);else if(['--materialize-sha256','--job-sha256','--oracle-sha256'].includes(f)){const k=f.slice(2,-7);need(!opts.expected[k],'duplicate '+f);opts.expected[k]=v;}else{need(flags[f]&&!opts[flags[f]],'unknown or duplicate flag: '+f);opts[flags[f]]=v;}}
    if(opts.load){const gate=await loadDirectWorldGate(opts.load,opts.sha256);console.log(JSON.stringify({status:gate.status,kind:gate.kind,comparedBlocks:gate.comparedBlocks}));}
    else{need(opts.out,'--out required');const gate=await createDirectWorldGate(opts);fs.writeFileSync(opts.out,JSON.stringify(gate,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({status:gate.status,kind:gate.kind,gate:fileRecord(path.resolve(opts.out))}));}
  } catch(error) {console.error(error.stack);process.exitCode=1;}
}
