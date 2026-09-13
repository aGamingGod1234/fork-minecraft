import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {measure} from './validate-benchmark.mjs';

const [receiptPath,proofRootArg]=process.argv.slice(2);
assert.ok(proofRootArg,'Usage: node validate-measured-world.mjs BENCHMARK_RECEIPT NEW_PROOF_DIRECTORY');
assert.equal(os.hostname().toLowerCase(),'desktop');
const read=file=>JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
const hash=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const receipt=read(receiptPath);
assert.equal(receipt.status,'WRITTEN_UNACCEPTED');assert.equal(receipt.mode,'real');
assert.equal(receipt.coreSize,1024);assert.equal(receipt.halo,128);
const job=read(receipt.jobSpecification.path);
assert.equal(hash(receipt.jobSpecification.path),receipt.jobSpecification.sha256);
assert.equal(job.tiles.length,1);const tile=job.tiles[0];
assert.deepEqual(tile.coreOrigin,receipt.coreOrigin);
assert.equal(tile.coreSize,receipt.coreSize);assert.equal(tile.halo,receipt.halo);
assert.match(tile.id,/^[a-z0-9_-]+$/i);
const tileRoot=path.resolve(receipt.outputRoot,tile.id),world=path.join(tileRoot,'world'),writerPath=path.join(tileRoot,'writer-manifest.json'),writer=read(writerPath);
const root=path.resolve(proofRootArg);
assert.ok(!fs.existsSync(root),'Proof directory must be new');
const rel=path.relative(path.resolve(receipt.outputRoot),root);
assert.ok(rel.startsWith('..'+path.sep)||path.isAbsolute(rel),'Proof files must remain outside measured output');
const freeCheck=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command','[int64](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory*1024'],{encoding:'utf8',windowsHide:true});
assert.equal(freeCheck.status,0);const freeBefore=Number(freeCheck.stdout.trim());
assert.ok(freeBefore>=9*1024**3,'Need 9GiB hostfree to retain8GiB under1GiB jobcap');
const folder=path.dirname(fileURLToPath(import.meta.url)),oracle=path.join(folder,'validate-world-fast.mjs'),producer=path.join(folder,'validate-result-gate.mjs'),harness=path.join(folder,'validate-benchmark.mjs');
const bounds=[...receipt.coreOrigin,receipt.coreOrigin[0]+1024,receipt.coreOrigin[1]+1024];
const oraclePath=path.join(root,'oracle.json'),gatePath=path.join(root,'gate.json');
const args=[process.execPath,oracle,'--world',world,'--bounds',bounds.join(','),'--out',oraclePath];
for(const input of writer.inputs){assert.ok(input.name&&!/[\\/]/.test(input.name));args.push('--runs',path.join(tileRoot,input.name));}
fs.mkdirSync(root,{recursive:true});
const driver=path.join(root,'driver.mjs');
const source=[
  "import fs from 'node:fs';import {spawnSync} from 'node:child_process';import {pathToFileURL} from 'node:url';",
  "console.log(JSON.stringify({pid:process.pid,startedUtc:new Date().toISOString()}));",
  "process.argv="+JSON.stringify(args)+";await import(pathToFileURL("+JSON.stringify(oracle)+").href);",
  "if(process.exitCode)throw Error('Actual oracle did not pass');",
  "const gateRun=spawnSync(process.execPath,"+JSON.stringify(['--max-old-space-size=256',producer,'--benchmark',path.resolve(receiptPath),'--writer',writerPath,'--world',world,'--oracle',oraclePath,'--out',gatePath])+",{encoding:'utf8',windowsHide:true});",
  "process.stdout.write(gateRun.stdout);process.stderr.write(gateRun.stderr);if(gateRun.status!==0)throw Error('Proof binding did not pass');",
  "const h=await import(pathToFileURL("+JSON.stringify(harness)+").href);h.loadIndependentlyValidatedReceipt("+JSON.stringify(path.resolve(receiptPath))+","+JSON.stringify(gatePath)+");",
  "console.log('FORK_EXACT_BENCHMARK_VALIDATION_PASS');"
].join('\n');
fs.writeFileSync(driver,source,{flag:'wx'});
const metrics=measure({argv:[process.execPath,'--max-old-space-size=768',driver],cwd:folder,timeoutSeconds:120,maxCores:1,maxMemoryBytes:1073741824,processorOffset:6,sampleIntervalMs:50},
 path.join(root,'process.json'),path.join(root,'stdout.log'),path.join(root,'stderr.log'));
const stdout=fs.readFileSync(path.join(root,'stdout.log'),'utf8'),stderr=fs.readFileSync(path.join(root,'stderr.log'),'utf8');
const passed=metrics.exitCode===0&&!metrics.timedOut&&!metrics.parentExited&&stdout.includes('FORK_EXACT_BENCHMARK_VALIDATION_PASS');
const summary={schemaVersion:1,kind:'measured-independent-benchmark-validation',status:passed?'PASS':'FAIL',receiptPath:path.resolve(receiptPath),benchmarkReceiptSha256:hash(receiptPath),freeBytesBefore:freeBefore,metrics,
 oracleSha256:fs.existsSync(oraclePath)?hash(oraclePath):null,gateSha256:fs.existsSync(gatePath)?hash(gatePath):null,
 validatorCodeHashes:Object.fromEntries([oracle,producer,harness,path.join(folder,'validate-benchmark-bindings.mjs'),path.join(folder,'validate-benchmark.windows.ps1')].map(f=>[path.basename(f),hash(f)])),
 pidLine:stdout.split('\n')[0],stderr,fullWorldAccepted:false,createdUtc:new Date().toISOString()};
fs.writeFileSync(path.join(root,'summary.json'),JSON.stringify(summary,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(summary,null,2));if(!passed)process.exitCode=1;
