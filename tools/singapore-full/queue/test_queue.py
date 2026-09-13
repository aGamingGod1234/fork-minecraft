import importlib.util, json, os, subprocess, sys, tempfile, time, unittest
from pathlib import Path
MODULE=Path(__file__).with_name("queue.py")
loader=importlib.util.spec_from_file_location("fork_durable_queue",MODULE)
q=importlib.util.module_from_spec(loader); loader.loader.exec_module(q)

class QueueTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix="fork-queue-test-")
        self.root=Path(self.temp.name)
        self.workers=[]
    def tearDown(self):
        # Tests wait for their tiny children and never target generation processes.
        time.sleep(.1)
        self.temp.cleanup()
    def spec(self,name="one",sleep=.2,fail=False):
        args=[sys.executable,str(q.HERE/"fixtures"/"fake_job.py"),"--output","{output_dir}",
              "--checkpoint","{checkpoint}","--name",name,"--sleep",str(sleep)]
        if fail: args.append("--fail-first")
        return {"sourceSha256":"a"*64,"configSha256":"b"*64,"codeSha256":"c"*64,
                "argv":args,"cwd":str(q.HERE),"fixture":True,"memoryGiB":.01,
                "cpuThreads":1,"maxAttempts":2,"timeoutSeconds":15}
    def state(self):
        with q.locked(self.root):
            return q.load_state(self.root)
    def wait(self, job, status="complete", timeout=15):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            q.dispatch_once(self.root,fixtures=True)
            current=self.state()["jobs"][job]
            if current["status"]==status: return current
            time.sleep(.1)
        self.fail(json.dumps(self.state(),indent=2))
    def test_identity_and_versioned_enqueue(self):
        me=q.identity(); self.assertTrue(q.alive(me))
        self.assertFalse(q.alive({"pid":me["pid"],"created":"wrong-creation"}))
        first=q.enqueue(self.root,self.spec())
        self.assertEqual(first,q.enqueue(self.root,self.spec()))
        changed=self.spec(); changed["sourceSha256"]="d"*64
        self.assertNotEqual(first,q.enqueue(self.root,changed))
    def test_resource_and_failed_gate(self):
        spec=self.spec()
        snap={"freeRam":40*q.GIB,"freeDisk":200*q.GIB,"cores":16}
        self.assertTrue(q.can_start(spec,[],snap)[0])
        self.assertFalse(q.can_start(spec,[{"spec":spec}]*2,snap)[0])
        self.assertFalse(q.can_start(spec,[],dict(snap,freeRam=8*q.GIB))[0])
        self.assertFalse(q.can_start(spec,[],dict(snap,freeDisk=99*q.GIB))[0])
        self.assertFalse(q.can_start(spec,[],dict(snap,cores=2))[0])
        j=q.enqueue(self.root,spec)
        q.atomic_json(self.root/"gate.json",{"status":"HOLD","accepted":False})
        self.assertEqual(q.dispatch_once(self.root,self.root/"gate.json"),[])
        self.assertEqual(self.state()["jobs"][j]["attempts"],0)
    def test_duplicate_dispatch_and_durable_success(self):
        j=q.enqueue(self.root,self.spec(sleep=.5))
        commands=[subprocess.Popen([sys.executable,str(MODULE),"run",str(self.root),"--fixtures","--once"],
                    stdout=subprocess.DEVNULL,stderr=subprocess.PIPE) for _ in range(3)]
        for proc in commands:
            _, errors=proc.communicate(timeout=10)
            self.assertIn(proc.returncode,(0,2),errors)
            if proc.returncode==2: self.assertIn(b"locked",errors)
        job=self.wait(j)
        self.assertEqual(job["attempts"],1)
        q.verify_output(self.root,j,job)
        self.assertIn("checkpoint-visits=1",(self.root/"outputs"/j/"result.txt").read_text())
        (self.root/"outputs"/j/"result.txt").write_text("tampered")
        with self.assertRaises(q.QueueError): q.verify_output(self.root,j,job)
    def test_bounded_retry_uses_checkpoint(self):
        j=q.enqueue(self.root,self.spec(fail=True))
        job=self.wait(j)
        self.assertEqual(job["attempts"],2)
        self.assertIn("checkpoint-visits=2",(self.root/"outputs"/j/"result.txt").read_text())
    def test_verified_dead_lease_recovery_and_unknown_hold(self):
        dead=subprocess.Popen([sys.executable,"-c","pass"])
        stamp=q.identity(dead.pid); dead.wait()
        state={"jobs":{"j":{"status":"running","attempts":1,"spec":{"maxAttempts":2},
                          "lease":{"supervisor":stamp,"child":stamp,"childLaunchPending":False}}}}
        q.recover(state)
        self.assertEqual(state["jobs"]["j"]["status"],"queued")
        state["jobs"]["j"].update(status="running",lease={"supervisor":stamp,"child":None,"childLaunchPending":True})
        q.recover(state)
        self.assertEqual(state["jobs"]["j"]["status"],"running")
        self.assertIn("manual verification",state["jobs"]["j"]["reason"])
        output=self.root/"outputs"/"j"; output.mkdir(parents=True)
        (output/"test.txt").write_text("complete-before-crash")
        q.atomic_json(self.root/"manifests"/"j.json",q.file_manifest(output))
        state["jobs"]["j"]["lease"]["childLaunchPending"]=False
        q.recover(state,self.root)
        self.assertEqual(state["jobs"]["j"]["status"],"complete")
        self.assertEqual(state["jobs"]["j"]["acceptance"],"WRITTEN_UNACCEPTED")
        self.assertTrue(state["jobs"]["j"]["independentValidationRequired"])
    def test_actual_supervisor_crash_then_resume(self):
        j=q.enqueue(self.root,self.spec(sleep=1.5))
        q.dispatch_once(self.root,fixtures=True)
        end=time.monotonic()+10
        while time.monotonic()<end:
            job=self.state()["jobs"][j]
            if job["lease"].get("child") and not job["lease"].get("childLaunchPending"): break
            time.sleep(.05)
        else: self.fail("No child lease")
        supervisor=job["lease"]["supervisor"]
        old_child=job["lease"]["child"]
        self.assertTrue(q.alive(supervisor))
        # Exact fixture supervisor only. Its child is deliberately allowed to finish.
        if os.name=="nt":
            subprocess.run(["taskkill","/PID",str(supervisor["pid"]),"/F"],check=True,stdout=subprocess.DEVNULL)
        else:
            import signal; os.kill(supervisor["pid"],signal.SIGKILL)
        time.sleep(.1)
        q.dispatch_once(self.root,fixtures=True)
        job=self.state()["jobs"][j]
        if os.name == "nt":
            self.assertFalse(q.alive(old_child))
        if os.name != "nt":
            self.assertEqual(job["attempts"],1)
            self.assertIn("child still alive",job["reason"])
        job=self.wait(j)
        self.assertEqual(job["attempts"],2)
        self.assertIn("checkpoint-visits=2",(self.root/"outputs"/j/"result.txt").read_text())
    def test_timeout_is_bounded_and_stops_owned_tree(self):
        spec=self.spec(sleep=3)
        spec["timeoutSeconds"]=1
        j=q.enqueue(self.root,spec)
        job=self.wait(j,"failed")
        self.assertEqual(job["attempts"],2)
        self.assertIn("Runtime limit",job["reason"])
        self.assertFalse((self.root/"outputs"/j).exists())
    def benchmark_gate(self, bindings):
        import hashlib
        proofs={}
        for name in ("roadsV2","streamingEquivalence"):
            path=self.root/(name+".json")
            q.atomic_json(path,{"status":"PASS","fixture":True})
            proofs[name]={"path":str(path),"sha256":hashlib.sha256(path.read_bytes()).hexdigest()}
        gate={"schemaVersion":1,"kind":"fork-experimental-benchmark-admission","scope":"pilot-policy",
              "status":"PASS","acceptedByCoordinator":True,"productionAccepted":False,
              "independentValidationRequired":True,"qualityProofs":proofs,"queueBindings":bindings}
        path=self.root/"benchmark-gate.json"; q.atomic_json(path,gate)
        return path,gate
    def test_benchmark_gate_exact_allowlist_and_proof_hashes(self):
        bindings=[]
        for name in ("dense","rural","coast"):
            spec=self.spec(name); spec["dispatchScope"]="benchmark"
            j=q.enqueue(self.root,spec)
            bindings.append({"jobId":j,**{k:spec[k] for k in ("sourceSha256","configSha256","codeSha256")}})
        path,gate=self.benchmark_gate(bindings)
        j=bindings[0]["jobId"]; spec=self.state()["jobs"][j]["spec"]
        self.assertIsNone(q.gate_reason(spec,path,job_id=j))
        self.assertIn("not one",q.gate_reason(spec,path,job_id="f"*64))
        changed=dict(spec,sourceSha256="f"*64)
        self.assertIn("fingerprint",q.gate_reason(changed,path,job_id=j))
        gate["acceptedByCoordinator"]=False; q.atomic_json(path,gate)
        self.assertIn("unaccepted",q.gate_reason(spec,path,job_id=j))
        gate["acceptedByCoordinator"]=True; q.atomic_json(path,gate)
        (self.root/"roadsV2.json").write_text('{"status":"PASS","tampered":true}')
        self.assertIn("hash mismatch",q.gate_reason(spec,path,job_id=j))
        self.assertIn("Seam gate",q.gate_reason(self.spec(),path))
    def test_camera_slot_uses_existing_free_floor(self):
        hold={"id":"main-camera","kind":"coordinator-hold","held":True,"acceptedByCoordinator":True,
              "memoryGiB":6,"cpuThreads":2,"memoryIncludedInFreeFloor":True}
        q.atomic_json(self.root/"external-reservations.json",{"schema":1,"reservations":[hold]})
        reservations=q.external_active(self.root)
        self.assertEqual(reservations[0]["spec"]["memoryGiB"],0)
        snapshot={"freeRam":int(17.5*q.GIB),"freeDisk":200*q.GIB,"cores":16}
        spec=dict(self.spec(),memoryGiB=4)
        self.assertTrue(q.can_start(spec,reservations,snapshot)[0])
        self.assertFalse(q.can_start(spec,reservations+[{"spec":spec}],snapshot)[0])
        hold["memoryGiB"]=9
        q.atomic_json(self.root/"external-reservations.json",{"schema":1,"reservations":[hold]})
        with self.assertRaises(q.QueueError): q.external_active(self.root)
    def test_three_scoped_jobs_dispatch_only_one_at_a_time(self):
        hold={"id":"main-camera","kind":"coordinator-hold","held":True,"acceptedByCoordinator":True,
              "memoryGiB":6,"cpuThreads":2,"memoryIncludedInFreeFloor":True}
        q.atomic_json(self.root/"external-reservations.json",{"schema":1,"reservations":[hold]})
        bindings=[]
        for name in ("dense","rural","coast"):
            spec=self.spec(name); spec["dispatchScope"]="benchmark"
            j=q.enqueue(self.root,spec)
            bindings.append({"jobId":j,**{k:spec[k] for k in ("sourceSha256","configSha256","codeSha256")}})
        self.benchmark_gate(bindings)
        q.atomic_json(self.root/"seam-gate.json",{"status":"HOLD","accepted":False})
        self.assertEqual(len(q.dispatch_once(self.root,self.root/"seam-gate.json")),1)
        end=time.monotonic()+15
        while time.monotonic()<end:
            launched=q.dispatch_once(self.root,self.root/"seam-gate.json")
            self.assertLessEqual(len(launched),1)
            state=self.state()
            self.assertLessEqual(sum(j["status"] in q.ACTIVE for j in state["jobs"].values()),1)
            if all(j["status"]=="complete" for j in state["jobs"].values()): break
            time.sleep(.1)
        else: self.fail("Scoped tiny jobs did not finish")
        self.assertTrue(all(j["acceptance"]=="WRITTEN_UNACCEPTED" for j in state["jobs"].values()))
        extra=q.enqueue(self.root,dict(self.spec("fourth"),dispatchScope="benchmark"))
        self.assertEqual(q.dispatch_once(self.root,self.root/"seam-gate.json"),[])
        self.assertEqual(self.state()["jobs"][extra]["attempts"],0)
    def test_retained_benchmark_path_and_report_integrity(self):
        spec=self.spec()
        spec["dispatchScope"]="benchmark"; spec["maxAttempts"]=1
        spec["argv"]+=["--report","{report_dir}"]
        j=q.enqueue(self.root,spec)
        job=self.wait(j)
        expected=self.root/"jobs"/j/"attempt-1"/"output"
        self.assertEqual(Path(job["outputPath"]),expected)
        self.assertTrue(expected.exists())
        self.assertTrue(q.completion_marker(self.root,j).exists())
        q.verify_output(self.root,j,job)
        report=Path(job["reports"]["path"])/"measurement.json"
        self.assertEqual(json.loads(report.read_text())["outputRoot"],str(expected))
        report.write_text("tampered")
        with self.assertRaises(q.QueueError): q.verify_output(self.root,j,job)
    def test_measured_concurrency_requires_explicit_policy(self):
        self.assertEqual(q.concurrency_limit(self.root),2)
        q.atomic_json(self.root/"concurrency-policy.json",{"maxActiveJobs":4,"acceptedByCoordinator":False})
        with self.assertRaises(q.QueueError): q.concurrency_limit(self.root)
        proof=self.root/"capacity.json"; q.atomic_json(proof,{"status":"PASS"})
        import hashlib
        policy={"maxActiveJobs":4,"acceptedByCoordinator":True,"measuredMemoryBudget":True,
                "cpuOrDiskBenefitVerified":True,"measurementEvidence":{"path":str(proof),
                "sha256":hashlib.sha256(proof.read_bytes()).hexdigest()}}
        q.atomic_json(self.root/"concurrency-policy.json",policy)
        self.assertEqual(q.concurrency_limit(self.root),4)
        proof.write_text('{"status":"PASS","tampered":true}')
        with self.assertRaises(q.QueueError): q.concurrency_limit(self.root)
    def test_missing_benchmark_config_does_not_spend_attempt(self):
        spec=self.spec()
        spec["dispatchScope"]="benchmark"
        spec["argv"]+=["--pending-config",str(self.root/"not-frozen.json")]
        j=q.enqueue(self.root,spec)
        binding={k:spec[k] for k in ("sourceSha256","configSha256","codeSha256")}
        self.benchmark_gate([{"jobId":j,**binding},{"jobId":"d"*64,**binding},{"jobId":"e"*64,**binding}])
        q.atomic_json(self.root/"external-reservations.json",{"schema":1,"reservations":[]})
        self.assertEqual(q.dispatch_once(self.root),[])
        job=self.state()["jobs"][j]
        self.assertEqual(job["attempts"],0)
        self.assertIn("Waiting for frozen",job["reason"])
    def test_two_explicit_benchmarks_plus_camera_slot(self):
        hold={"id":"main-camera","kind":"coordinator-hold","held":True,"acceptedByCoordinator":True,
              "memoryGiB":6,"cpuThreads":2,"memoryIncludedInFreeFloor":True}
        q.atomic_json(self.root/"external-reservations.json",{"schema":1,"reservations":[hold]})
        bindings=[]
        for name in ("dense","rural","coast"):
            spec=self.spec(name,sleep=.5);spec["dispatchScope"]="benchmark"
            j=q.enqueue(self.root,spec)
            bindings.append({"jobId":j,**{k:spec[k] for k in ("sourceSha256","configSha256","codeSha256")}})
        path,gate=self.benchmark_gate(bindings)
        gate["maxConcurrentBenchmarks"]=2;q.atomic_json(path,gate)
        self.assertEqual(len(q.dispatch_once(self.root)),2)
        self.assertEqual(sum(j["status"] in q.ACTIVE for j in self.state()["jobs"].values()),2)
        for entry in bindings:self.wait(entry["jobId"])

    def test_versioned_benchmark_gate_preserves_previous_gate(self):
        spec=self.spec("v2"); spec["dispatchScope"]="benchmark"
        spec["benchmarkGateName"]="benchmark-gate-cbd-v2.json"
        j=q.enqueue(self.root,spec)
        binding={k:spec[k] for k in ("sourceSha256","configSha256","codeSha256")}
        path,gate=self.benchmark_gate([{"jobId":j,**binding},{"jobId":"d"*64,**binding},{"jobId":"e"*64,**binding}])
        q.atomic_json(self.root/"benchmark-gate-cbd-v2.json",gate)
        q.atomic_json(path,dict(gate,acceptedByCoordinator=False))
        original=path.read_bytes()
        q.atomic_json(self.root/"external-reservations.json",{"schema":1,"reservations":[]})
        self.assertEqual(len(q.dispatch_once(self.root)),1)
        self.wait(j)
        self.assertEqual(path.read_bytes(),original)
        for bad in ("../benchmark-gate.json","benchmark-gate/other.json","benchmark-gate\\\\other.json","gate.json"):
            with self.assertRaises(q.QueueError):
                q.normalize_spec(dict(spec,benchmarkGateName=bad))

    def test_child_creates_fresh_output_without_duplicate_writers(self):
        spec=self.spec("fresh")
        spec["outputCreatedByChild"]=True
        spec["argv"]+=["--expect-output-absent"]
        spec["maxAttempts"]=1
        j=q.enqueue(self.root,spec)
        self.assertEqual(q.enqueue(self.root,spec),j)
        job=self.wait(j)
        self.assertEqual(job["attempts"],1)
        q.verify_output(self.root,j,job)

    def test_external_reservation_and_dependencies(self):
        entry={"id":"manual-test","owners":[q.identity()],"memoryGiB":3,"cpuThreads":2}
        q.atomic_json(self.root/"external-reservations.json",{"schema":1,"reservations":[entry]})
        self.assertEqual(len(q.external_active(self.root)),1)
        entry["owners"][0]["created"]="old"
        q.atomic_json(self.root/"external-reservations.json",{"schema":1,"reservations":[entry]})
        self.assertEqual(q.external_active(self.root),[])
        first=q.enqueue(self.root,self.spec())
        second=self.spec("dependent"); second["dependencies"]=[first]
        j=q.enqueue(self.root,second)
        self.wait(j)
        self.assertEqual(self.state()["jobs"][first]["status"],"complete")

if __name__=="__main__": unittest.main(verbosity=2)
