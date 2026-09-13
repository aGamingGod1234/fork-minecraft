import argparse, json, os, time
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument("--output", required=True)
p.add_argument("--checkpoint", required=True)
p.add_argument("--sleep", type=float, default=.2)
p.add_argument("--fail-first", action="store_true")
p.add_argument("--name", default="fixture")
p.add_argument("--report")
p.add_argument("--expect-output-absent",action="store_true")
a=p.parse_args()
if not 0 <= a.sleep <= 10:
    raise SystemExit("Tiny fixture sleep must be <= 10 seconds")
out=Path(a.output)
if a.expect_output_absent and out.exists():
    raise SystemExit("Output was precreated")
out.mkdir(parents=True,exist_ok=True)
checkpoint=Path(a.checkpoint)
previous=json.loads(checkpoint.read_text()) if checkpoint.exists() else {"visits":0}
previous["visits"]+=1
temporary=checkpoint.with_suffix(".tmp")
temporary.write_text(json.dumps(previous))
os.replace(temporary,checkpoint)
(out/"started.json").write_text(json.dumps({"pid":os.getpid(),"visit":previous["visits"]}))
time.sleep(a.sleep)
if a.fail_first and previous["visits"]==1:
    raise SystemExit(7)
(out/"result.txt").write_text(a.name+":checkpoint-visits="+str(previous["visits"]))

if a.report:
    report=Path(a.report); report.mkdir(parents=True,exist_ok=True)
    (report/"measurement.json").write_text(json.dumps({"outputRoot":str(out),"status":"WRITTEN_UNACCEPTED"}))
