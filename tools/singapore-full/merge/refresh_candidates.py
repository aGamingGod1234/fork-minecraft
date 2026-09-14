"""Append genuinely completed, hash-bound national validations to the work list."""
from datetime import datetime, timezone
import json
from pathlib import Path
import prepare_core as p
import run_batch as b

def main():
    previous=p.read(p.ROOT/'validated-candidates.json')
    by_id={p.read(c['gate'])['tileId']:c for c in previous}
    state=p.read(b.QUEUE/'queue.json');rejected=[]
    for job_id,j in state['jobs'].items():
        if j['status']!='complete' or j['spec'].get('dispatchScope')!='validation':continue
        try:
            checkpoint=p.read(Path(j['outputPath'])/'validation-result.json')
            if checkpoint.get('status')!='PASS' or checkpoint.get('validationRole')!='ASSEMBLY_COMPONENT':continue
            binding=p.checked(checkpoint['bindingReceipt']);gate=p.checked(binding['gate'])
            if gate.get('kind')!='national-pipeline-structural-result' or gate.get('status')!='PASS':continue
            argv=j['spec']['argv'];execution=p.read(argv[argv.index('--execution')+1])
            if execution.get('validationGateName') not in p.contract.NATIONAL_AUTHORITIES:continue
            by_id.setdefault(gate['tileId'],{'job':job_id,'binding':checkpoint['bindingReceipt']['path'],
                'gate':binding['gate']['path'],'core':gate['comparisonBounds']})
        except Exception as error:rejected.append({'job':job_id,'reason':str(error)})
    rows=list(by_id.values());stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    p.write(p.ROOT/'candidate-history'/('candidates-'+stamp+'.json'),rows)
    b.q.atomic_json(p.ROOT/'validated-candidates.json',rows)
    p.write(p.ROOT/'candidate-history'/('refresh-'+stamp+'.json'),{'previous':len(previous),'current':len(rows),'rejected':rejected})
    print(json.dumps({'previous':len(previous),'current':len(rows),'rejected':len(rejected)}),flush=True)

if __name__=='__main__':main()
