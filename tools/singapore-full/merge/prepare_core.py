"""Prepare real national-tile evidence using the existing source classifiers."""
import argparse, hashlib, importlib.util, json, shutil, subprocess, sys
from pathlib import Path

BASE=Path(r'C:\Users\User\AppData\Local\FORK-Tools')
FULL=BASE/'fork-singapore-full'
WORK=BASE/'fork-minecraft-worktrees/national-assembly-20260914'
MERGE=WORK/'tools/singapore-full/merge'
sys.path.insert(0,str(MERGE))
import grow_contract as contract
PYTHON=FULL/'data/.venv/Scripts/python.exe'
NODE=Path(r'C:\Program Files\nodejs\node.exe')
CLASSIFIER=BASE/'fork-minecraft-worktrees/full-singapore-roads/tools/singapore-full/roads/east_omissions.py'
ROOT=FULL/'national-assembly-20260914'

def read(p): return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def pin(p):
    p=Path(p);h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return {'path':str(p),'bytes':p.stat().st_size,'sha256':h.hexdigest()}
def write(p,v):
    p=Path(p);p.parent.mkdir(parents=True,exist_ok=True)
    raw=(json.dumps(v,indent=2,sort_keys=True)+'\n').encode()
    if p.exists(): assert p.read_bytes()==raw, 'Immutable metadata differs: '+str(p)
    else:
        with p.open('xb') as f:f.write(raw)
    return pin(p)
def checked(ref):
    assert pin(ref['path'])['sha256']==ref['sha256'], 'Changed input: '+ref['path']
    return read(ref['path'])

def prepare(candidate):
    state=read(FULL/'queue/queue.json'); queued=state['jobs'][candidate['job']]
    argv=queued['spec']['argv']; execution=argv[argv.index('--execution')+1]
    binding=checked(pin(candidate['binding']));gate=checked(binding['gate'])
    result=checked(gate['pipelineResult']); job=checked(gate['jobSpecification'])
    tile=next(t for t in result['tiles'] if t['id']==gate['tileId'])
    tile_id=tile['id'];root=ROOT/'cores'/tile_id
    source={'id':tile_id,'core_bounds':gate['comparisonBounds'],'world_path':gate['worldRoot'],
            'writer_manifest_path':gate['evidence']['writerManifest']['path'],
            'structural_gate_path':binding['gate']['path'],'national_binding_path':candidate['binding'],
            'national_execution_path':execution}
    writer=checked(gate['evidence']['writerManifest']); writer_pin=pin(source['writer_manifest_path'])
    outputs=contract._outputs(writer,Path(source['world_path']))
    contract._national_structural(gate,source,writer_pin['sha256'],outputs,tuple(source['core_bounds']),Path(source['world_path']),Path(source['writer_manifest_path']))
    print(json.dumps({'core':tile_id,'stage':'strict-national-binding-pass'}),flush=True)
    original=Path(gate['pipelineResult']['path']).parent
    tile_root=original/tile_id
    # The existing omission classifier expects its projection next to the report.
    # Copy exact metadata bytes into the evidence workspace; generated worlds stay untouched.
    metadata=root/'metadata';metadata.mkdir(parents=True,exist_ok=True)
    for src in [original/'projected-nodes.json',tile_root/'roads-manifest.json']:
        dst=metadata/src.name
        if dst.exists():assert pin(dst)['sha256']==pin(src)['sha256']
        else:shutil.copyfile(src,dst)
    core={'id':tile_id,'coreBounds':source['core_bounds'],'source':job['source'],
          'worldPath':source['world_path'],'writerManifest':writer_pin,'structuralGate':binding['gate'],
          'reports':{'roads':pin(metadata/'roads-manifest.json')},
          'projectedNodes':pin(metadata/'projected-nodes.json')}
    adapter_job={'kind':'national-source-audit-view','derivedFrom':gate['jobSpecification'],
                 'countryMask':job['roads']['countryMask'],'foreignMask':job['roads']['foreignExclusions'],
                 'cores':[{'id':tile_id,'coreBounds':source['core_bounds'],'source':job['source']}]}
    job_ref=write(root/'audit-job.json',adapter_job)
    view={'kind':'national-source-audit-view','derivedFrom':gate['pipelineResult'],'job':job_ref,'cores':[core]}
    view_ref=write(root/'audit-result.json',view)
    policy=pin(FULL/'approved-national-source-subset-policy-v2.json')
    audit_path=root/'audit'/tile_id/'classification.json'
    if not audit_path.exists():
        proc=subprocess.run([str(PYTHON),str(CLASSIFIER),'--grow-result',view_ref['path'],'--grow-sha256',view_ref['sha256'],
            '--policy',policy['path'],'--policy-sha256',policy['sha256'],'--core-id',tile_id,'--out-root',str(root/'audit')],capture_output=True,text=True,timeout=120)
        (root/'classification.log').write_text(proc.stdout+'\n'+proc.stderr)
        if proc.returncode:raise RuntimeError('Classifier failed: '+proc.stderr[-1500:])
    audit=read(audit_path)
    print(json.dumps({'core':tile_id,'stage':'road-source-audit','ready':audit['renderScopePolicyReady'],'fatal':audit['integrity']['fatalErrors'],'counts':audit['counts']}),flush=True)
    descriptor={'source':source,'core':core,'pipelineResult':gate['pipelineResult'],'jobSpecification':gate['jobSpecification'],
                'roadRuns':pin(tile_root/'roads.runs.jsonl'),'coastRuns':pin(tile_root/'coast.masked.runs.jsonl'),
                'audit':pin(audit_path),'previewPolicy':pin(audit_path.with_name('preview-policy.json')),
                'pins':{'policy':policy['sha256'],'result':view_ref['sha256'],'job':job_ref['sha256']},
                'spawnClear':gate['rawOracleSpawnClear'],'readyForMaterialization':audit['renderScopePolicyReady']}
    write(root/'prepared.json',descriptor)
    return descriptor

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--core');args=parser.parse_args()
    candidates=read(ROOT/'validated-candidates.json')
    chosen=next(c for c in candidates if not args.core or read(c['gate'])['tileId']==args.core)
    prepare(chosen)
