"""Refresh completed validations and extend source evidence without a live agent."""
from datetime import datetime, timezone
import prepare_core as p
import refresh_candidates
import run_batch

if __name__=='__main__':
    old=p.ROOT/'batch-status.json'
    if old.exists():
        state=p.read(old)
        if run_batch.q.alive(state['owner']):raise RuntimeError('Another evidence batch is live')
        stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        p.write(p.ROOT/'batch-history'/('batch-'+stamp+'.json'),state)
    refresh_candidates.main()
    run_batch.main(1000)
