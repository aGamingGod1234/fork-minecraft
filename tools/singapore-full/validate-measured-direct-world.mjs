import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {measure} from './validate-benchmark.mjs';

const flags={runs:[]};
for(let i=2;i<process.argv.length;i+=2){const name=process.argv[i],value=process.argv[i+1];assert.ok(name.startsWith('--')&&value,'Expected --flag value');if(name==='--runs')flags.runs.push(path.resolve(value));else flags[name.slice(2)]=value;}
for(const name of ['materialize','materialize-sha256','world','writer','job','job-sha256','proof-root'])assert.ok(flags[name],'Missing --'+name);
assert.ok(flags.runs.length>0,'Every consumed run file is required');
assert.equal(os.hostname().toLowerCase(),'desktop');
const read=file=>JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
const hash=file=>{const h=crypto.createHash('sha256'),fd=fs.openSync(file,'r'),buf=Buffer.alloc(1048576);try{let n;while((n=fs.readSync(fd,buf,0,buf.length,null)))h.update(buf.subarray(0,n));}finally{fs.closeSync(fd);}return h.digest('hex');};
for(const name of ['materialize','job'])assert.equal(hash(flags[name]),flags[name+'-sha256'].toLowerCase(),name+' frozen pin mismatch');
assert.equal(read(flags.materialize).status,'WRITTEN_AWAITING_INDEPENDENT_GATES','Materialization must finish before validation');
const job=read(flags.job);assert.equal(job.tiles.length,1);
const tile=job.tiles[0];assert.equal(tile.coreSize,1024);assert.equal(tile.halo,128);
assert.ok(tile.coreOrigin.length===2&&tile.coreOrigin.every(Number.isSafeInteger));
const [x,z]=tile.coreOrigin,bounds=[x,z,x+1024,z+1024],writer=read(flags.writer);
assert.deepEqual(writer.bounds,[x-128,z-128,x+1152,z+1152]);
assert.equal(writer.inputs.length,flags.runs.length);
const root=path.resolve(flags['proof-root']),world=fs.realpathSync(flags.world);
assert.ok(!fs.existsSync(root),'Proof directory must be new');
const relative=path.relative(world,root);
assert.ok(relative.startsWith('..'+path.sep)||path.isAbsolute(relative),'Proof files must stay outside candidate world');
const ram=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command','[int64](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory*1024'],{encoding:'utf8',windowsHide:true});
assert.equal(ram.status,0);const freeBefore=Number(ram.stdout.trim());assert.ok(freeBefore>=9*1024**3,'Need9GiB free before a1GiB validation job');
const folder=path.dirname(fileURLToPath(import.meta.url)),oracle=path.join(folder,'validate-world-fast.mjs'),producer=path.join(folder,'validate-direct-world-gate.mjs'),settings=path.join(folder,'validate-world-settings.mjs');
assert.ok(fs.existsSync(producer),'Direct gate producer must be installed before launch');
fs.mkdirSync(root,{recursive:true});
const oraclePath=path.join(root,'oracle.json'),gatePath=path.join(root,'gate.json'),driver=path.join(root,'driver.mjs');
const oracleArgs=[process.execPath,oracle,'--world',world,'--bounds',bounds.join(','),'--out',oraclePath];
for(const run of flags.runs)oracleArgs.push('--runs',run);
const producerArgs=['--max-old-space-size=256',producer,'--materialize',path.resolve(flags.materialize),'--materialize-sha256',flags['materialize-sha256'],'--world',world,'--writer',path.resolve(flags.writer),'--job',path.resolve(flags.job),'--job-sha256',flags['job-sha256'],'--oracle',oraclePath,'--settings-validator',settings,'--out',gatePath];
for(const run of flags.runs)producerArgs.push('--runs',run);
fs.writeFileSync(driver,[
 "import fs from 'node:fs';import crypto from 'node:crypto';import {spawnSync} from 'node:child_process';import {pathToFileURL} from 'node:url';",
 "const hash=f=>crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');",
 "console.log(JSON.stringify({pid:process.pid,startedUtc:new Date().toISOString()}));",
 "process.argv="+JSON.stringify(oracleArgs)+";await import(pathToFileURL("+JSON.stringify(oracle)+").href);",
 "if(process.exitCode)throw Error('Actual direct-world oracle did not pass');",
 "const gateRun=spawnSync(process.execPath,"+JSON.stringify(producerArgs)+".concat(['--oracle-sha256',hash("+JSON.stringify(oraclePath)+")]),{encoding:'utf8',windowsHide:true});",
 "process.stdout.write(gateRun.stdout);process.stderr.write(gateRun.stderr);if(gateRun.status!==0)throw Error('Direct source/world binding did not pass');",
 "const p=await import(pathToFileURL("+JSON.stringify(producer)+").href);await p.loadDirectWorldGate("+JSON.stringify(gatePath)+",hash("+JSON.stringify(gatePath)+"));",
 "console.log('FORK_EXACT_DIRECT_WORLD_VALIDATION_PASS');"
].join('\n'),{flag:'wx'});
const metrics=measure({argv:[process.execPath,'--max-old-space-size=768',driver],cwd:folder,timeoutSeconds:120,maxCores:1,maxMemoryBytes:1073741824,processorOffset:6,sampleIntervalMs:50},path.join(root,'process.json'),path.join(root,'stdout.log'),path.join(root,'stderr.log'));
const stdout=fs.readFileSync(path.join(root,'stdout.log'),'utf8'),stderr=fs.readFileSync(path.join(root,'stderr.log'),'utf8');
const passed=metrics.exitCode===0&&!metrics.timedOut&&!metrics.parentExited&&stdout.includes('FORK_EXACT_DIRECT_WORLD_VALIDATION_PASS');
const summary={schemaVersion:1,kind:'measured-independent-direct-world-validation',status:passed?'PASS':'FAIL',materializationReceipt:{path:path.resolve(flags.materialize),sha256:hash(flags.materialize)},originalJob:{path:path.resolve(flags.job),sha256:hash(flags.job)},freeBytesBefore:freeBefore,metrics,oracleSha256:fs.existsSync(oraclePath)?hash(oraclePath):null,gateSha256:fs.existsSync(gatePath)?hash(gatePath):null,validatorCodeHashes:Object.fromEntries([oracle,producer,settings,path.join(folder,'validate-benchmark.mjs'),path.join(folder,'validate-benchmark.windows.ps1')].map(f=>[path.basename(f),hash(f)])),pidLine:stdout.split('\n')[0],stderr,fullPipelineBenchmarkAccepted:false,fullWorldAccepted:false,createdUtc:new Date().toISOString()};
fs.writeFileSync(path.join(root,'summary.json'),JSON.stringify(summary,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(summary,null,2));if(!passed)process.exitCode=1;
