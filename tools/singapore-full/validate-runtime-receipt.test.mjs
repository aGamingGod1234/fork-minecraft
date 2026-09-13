import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

// Regression checks mutate in-memory evidence only. The candidate and receipts stay frozen.
const [runtimeRoot,writer,gate] = process.argv.slice(2);
if(!gate) throw Error('Usage: node validate-runtime-receipt.test.mjs RUNTIME_ROOT WRITER_MANIFEST STRUCTURAL_GATE');
const validator=path.join(path.dirname(fileURLToPath(import.meta.url)),'validate-runtime-receipt.mjs');
for(const [mutation,reason] of [
  ['sentinel','sentinel state must match independently decoded immutable candidate'],
  ['digest','canonical completed receipt digest'],
]){
  const source = [
    "import fs from 'node:fs'; import {pathToFileURL} from 'node:url';",
    "const original=fs.readFileSync.bind(fs);",
    "fs.readFileSync=(file,...rest)=>{const raw=original(file,...rest);const name=String(file);",
    mutation==='sentinel'
      ? "if(name.endsWith('runtime-state.json')){let j=JSON.parse(raw);j.block_sentinels[0].state='minecraft:diamond_block';j.block_sentinels[0].pos[1]=300;return JSON.stringify(j);}"
      : "if(name.endsWith('runtime-receipt.json')){let j=JSON.parse(raw);j.manifest_sha256='0'.repeat(64);return JSON.stringify(j);}",
    "return raw;};",
    "fs.writeFileSync=()=>{throw Error('Negative control unexpectedly reached output writing');};",
    "process.argv="+JSON.stringify([process.execPath,validator,runtimeRoot,writer,gate,'must-not-write.json'])+";",
    "await import(pathToFileURL("+JSON.stringify(validator)+").href);"
  ].join('\n');
  const result=spawnSync(process.execPath,['--input-type=module','-e',source],{encoding:'utf8',windowsHide:true});
  assert.notEqual(result.status,0,mutation+' must fail');
  assert.ok(result.stderr.includes(reason),result.stderr);
  console.log('PASS rejected mutated '+mutation);
}
