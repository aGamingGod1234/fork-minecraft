import subprocess,sys,json
from pathlib import Path
import prepare_core as p
from coast_core import coast

core_id=sys.argv[1]
candidates=p.read(p.ROOT/'validated-candidates.json')
candidate=next(c for c in candidates if p.read(c['gate'])['tileId']==core_id)
root=p.ROOT/'cores'/core_id
if not (root/'prepared.json').exists():d=p.prepare(candidate)
else:d=p.read(root/'prepared.json')
if not d['readyForMaterialization']:raise RuntimeError('Source audit needs repair: '+core_id)
script=p.MERGE/'materialize_core.mjs'
if not (root/'roads-evidence.json').exists():subprocess.run([str(p.NODE),str(script),str(root/'prepared.json')],check=True,timeout=150)
if not (root/'coast-evidence.json').exists():coast(core_id)
if not (root/'water-evidence.json').exists():subprocess.run([str(p.NODE),str(script),str(root/'prepared.json'),'water'],check=True,timeout=150)
s=d['source'];s['coverage']={}
writer=p.read(s['writer_manifest_path'])
for component in ['roads','water']:
    proof=p.pin(root/(component+'-evidence.json'))
    entry={'status':'rendered_subset_preview','evidence_path':proof['path'],'evidence_sha256':proof['sha256']}
    p.contract._coverage(component,entry,s['core_bounds'],d['core']['writerManifest']['sha256'],writer['inputs'])
    s['coverage'][component]=entry
p.write(root/'assembly-source.json',s)
print(json.dumps({'core':core_id,'status':'ALL_COMPONENT_GATES_PASS','spawnClear':d['spawnClear']}),flush=True)
