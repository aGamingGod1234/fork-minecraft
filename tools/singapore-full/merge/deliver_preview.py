"""Build a filled rectangular preview, then leave larger source checks running."""
import json, subprocess, time, traceback
from datetime import datetime, timezone, timedelta
from pathlib import Path
import prepare_core as p
import run_batch as batch
q = batch.q
ROOT = p.ROOT / 'delivery-v2'
SNAPSHOT = p.FULL / 'merged/grow-national-preview-v1'

def main():
    ROOT.mkdir(exist_ok=True)
    state = {'status':'running', 'owner':q.identity(), 'startedAt':time.time()}
    def save(phase):
        state.update(phase=phase, updatedAt=time.time())
        q.atomic_json(ROOT/'status.json',state)
    save('retrying-component-checks')
    # Previous attempts and their logs remain available for comparison.
    old = p.ROOT/'batch-status.json'
    if old.exists():
        previous=p.read(old)
        if q.alive(previous['owner']):raise RuntimeError('Existing evidence batch is still live')
        if not (ROOT/'first-batch.json').exists():p.write(ROOT/'first-batch.json', previous)
    if not (ROOT/'plan.json').exists():batch.main(16)
    ready = {p.read(f)['id']:p.read(f) for f in (p.ROOT/'cores').glob('*/assembly-source.json')}
    # A filled rectangle avoids pretending holes in a bounding box are built.
    grid = {tuple(map(int,k.split('-')[1:])):k for k in ready}
    choices=[]
    for x0,z0 in grid:
        for x1,z1 in grid:
            if x1<x0 or z1<z0:continue
            cells=[(x,z) for x in range(x0,x1+1) for z in range(z0,z1+1)]
            if all(c in grid for c in cells):
                choices.append((len(cells), (25,27) in cells, cells))
    _,_,cells=max(choices)
    selected=[ready[grid[c]] for c in sorted(cells)]
    safe=[s['id'] for s in selected if p.read(p.ROOT/'cores'/s['id']/'prepared.json')['spawnClear']]
    if not safe:raise RuntimeError('No independently confirmed safe spawn')
    spawn='sg-25-27' if 'sg-25-27' in safe else safe[0]
    plan={'schemaVersion':1,'world_name':'FORK - Singapore National Preview v1',
          'spawn_source_id':spawn,'sources':selected}
    if (ROOT/'plan.json').exists():
        plan=p.read(ROOT/'plan.json');selected=plan['sources']
    p.write(ROOT/'plan.json',plan)
    state.update(cores=[s['id'] for s in selected],areaKm2=len(selected)*1.048576)
    save('waiting-for-assembly-memory')
    reservation={'id':'national-preview-delivery-v1','memoryGiB':4,'cpuThreads':1,
                 'processorOffset':8,'owners':[q.identity()]}
    with q.locked(batch.QUEUE,timeout=120):
        path=batch.QUEUE/'external-reservations.json';r=q.read_json(path)
        assert not any(q.alive(o) for e in r['reservations'] if e.get('id')==reservation['id'] for o in e.get('owners',[]))
        r['reservations']=[e for e in r['reservations'] if e.get('id')!=reservation['id']]+[reservation]
        q.atomic_json(path,r)
    def run(name,command,memory,timeout):
        while True:
            r=q.resource_snapshot(batch.QUEUE)
            if r['freeRam']>=q.MIN_FREE_RAM+memory*q.GIB and r['freeDisk']>=q.MIN_FREE_DISK:break
            time.sleep(5)
        save(name)
        guard=q.ChildGuard(memory,8,1);proc=None
        try:
            with (ROOT/(name+'.log')).open('xb',buffering=0) as log:
                proc=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,
                    creationflags=subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS)
                guard.assign(proc);deadline=time.monotonic()+timeout
                state['child']=q.identity(proc.pid);save(name)
                while proc.poll() is None:
                    r=q.resource_snapshot(batch.QUEUE)
                    if r['freeRam']<q.MIN_FREE_RAM or r['freeDisk']<q.MIN_FREE_DISK or time.monotonic()>deadline:
                        guard.terminate(proc);raise RuntimeError(name+' reached time/resource floor')
                    time.sleep(1)
                if proc.returncode:raise RuntimeError(name+' exit '+str(proc.returncode))
        finally:
            if proc and proc.poll() is None:guard.terminate(proc)
            guard.close()
            if proc:proc.wait(timeout=15)
            state.pop('child',None)
    try:
        now=datetime.now(timezone.utc)
        if not (ROOT/'lease.json').exists():p.write(ROOT/'lease.json',{'id':'national-preview-delivery-v1','machine':'Desktop',
            'approvedBy':'/root/singapore_full_coordinator','heavyJobSlot':'A','cpuThreads':1,
            'outputRoot':str(SNAPSHOT),'startsUtc':now.isoformat(),'expiresUtc':(now+timedelta(hours=2)).isoformat()})
        if not (SNAPSHOT/'grow-manifest.json').exists():run('assemble',[str(p.PYTHON),str(p.MERGE/'grow.py'),'--plan',str(ROOT/'plan.json'),
            '--world',str(SNAPSHOT/'world'),'--manifest',str(SNAPSHOT/'grow-manifest.json'),
            '--job-lease',str(ROOT/'lease.json')],2,3600)
        command=[str(p.PYTHON),str(p.MERGE/'grow_runtime_run.py'),'--candidate',str(SNAPSHOT/'world'),
                 '--district','national-preview-v1']
        for s in selected:
            d=p.read(p.ROOT/'cores'/s['id']/'prepared.json')
            tile_root=Path(d['pipelineResult']['path']).parent/s['id']
            for filename in ['buildings.runs.jsonl','roads.runs.jsonl']:
                path=tile_root/filename
                if not path.is_file():raise FileNotFoundError(path)
                command+=['--runs',str(path)]
        run('runtime-and-package',command,4,1800)
        state['delivery']=p.read(SNAPSHOT/'delivery.json');state['status']='complete';save('runtime-passed-package-ready')
    except Exception as e:
        state.update(status='failed',error=str(e));save('needs-repair');raise
    finally:
        with q.locked(batch.QUEUE,timeout=120):
            path=batch.QUEUE/'external-reservations.json';r=q.read_json(path)
            r['reservations']=[e for e in r['reservations'] if not(e.get('id')==reservation['id'] and e.get('owners')==reservation['owners'])]
            q.atomic_json(path,r)

if __name__=='__main__':main()
