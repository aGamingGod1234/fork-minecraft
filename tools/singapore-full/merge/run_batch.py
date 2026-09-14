"""Bounded, single-core Desktop evidence batch. Existing generation stays isolated."""
import argparse,importlib.util,json,os,subprocess,time,traceback
from pathlib import Path
import prepare_core as p
loader=importlib.util.spec_from_file_location('fork_durable',p.BASE/'fork-minecraft-worktrees/full-singapore-queue/tools/singapore-full/queue/queue.py')
q=importlib.util.module_from_spec(loader);loader.loader.exec_module(q)
QUEUE=p.FULL/'queue'

def adjacent(a,b):
    return (((a[0]==b[2] or a[2]==b[0]) and min(a[3],b[3])>max(a[1],b[1])) or
            ((a[1]==b[3] or a[3]==b[1]) and min(a[2],b[2])>max(a[0],b[0])))

def main(limit):
    candidates=p.read(p.ROOT/'validated-candidates.json')
    by_id={p.read(c['gate'])['tileId']:c for c in candidates}
    selected=['sg-25-27'];pending=list(selected)
    while pending and len(selected)<limit:
        a=by_id[pending.pop(0)]['core']
        for core_id,c in sorted(by_id.items()):
            if core_id not in selected and adjacent(a,c['core']):
                selected.append(core_id);pending.append(core_id)
                if len(selected)==limit:break
    status={'status':'running','selected':selected,'ready':[],'failed':{},'startedAt':time.time(),'owner':q.identity(),'memoryGiB':2,'cpuOffset':8}
    def save():status['updatedAt']=time.time();q.atomic_json(p.ROOT/'batch-status.json',status)
    save()
    # Reconcile through the existing queue's external resource accounting.
    reservation={'id':'national-assembly-20260914','memoryGiB':2,'cpuThreads':1,'processorOffset':8,'owners':[q.identity()]}
    with q.locked(QUEUE,timeout=120):
        path=QUEUE/'external-reservations.json';r=q.read_json(path)
        prior=[e for e in r['reservations'] if e.get('id')==reservation['id']]
        assert not any(q.alive(o) for e in prior for o in e.get('owners',[])), 'Another assembly worker is live'
        r['reservations']=[e for e in r['reservations'] if e.get('id')!=reservation['id']]+[reservation];q.atomic_json(path,r)
    try:
        for core_id in selected:
            status['current']=core_id;save()
            if (p.ROOT/'cores'/core_id/'assembly-source.json').exists():status['ready'].append(core_id);save();continue
            while True:
                resources=q.resource_snapshot(QUEUE)
                if resources['freeRam']>=q.MIN_FREE_RAM+2*q.GIB and resources['freeDisk']>=q.MIN_FREE_DISK:break
                status['phase']='waiting-for-memory';save();time.sleep(5)
            status['phase']='checking-components';save()
            folder=p.ROOT/'batch-logs';folder.mkdir(exist_ok=True)
            guard=q.ChildGuard(2,8,1);proc=None
            try:
                with (folder/(core_id+'.log')).open('ab',buffering=0) as log:
                    proc=subprocess.Popen([str(p.PYTHON),str(p.MERGE/'finish_core.py'),core_id],stdout=log,stderr=subprocess.STDOUT,
                                          creationflags=subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS)
                    guard.assign(proc);deadline=time.monotonic()+600
                    while proc.poll() is None:
                        snap=q.resource_snapshot(QUEUE)
                        if time.monotonic()>deadline or snap['freeRam']<q.MIN_FREE_RAM or snap['freeDisk']<q.MIN_FREE_DISK:
                            guard.terminate(proc);raise RuntimeError('Component job reached time/resource floor')
                        time.sleep(.5)
                    if proc.returncode:raise RuntimeError('Component worker exit '+str(proc.returncode))
                status['ready'].append(core_id)
            except Exception as error:status['failed'][core_id]=str(error)
            finally:
                guard.close()
                if proc is not None:proc.wait(timeout=15)
                save()
        status['status']='complete';status['phase']='ready-for-connected-assembly';save()
    finally:
        with q.locked(QUEUE,timeout=120):
            path=QUEUE/'external-reservations.json';r=q.read_json(path)
            r['reservations']=[e for e in r['reservations'] if not(e.get('id')==reservation['id'] and e.get('owners')==reservation['owners'])]
            q.atomic_json(path,r)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--limit',type=int,default=16);args=parser.parse_args()
    main(args.limit)
