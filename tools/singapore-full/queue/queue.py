"""Durable, fail-closed desktop generation queue. Python 3.11+, standard library."""
from __future__ import annotations
import argparse, contextlib, ctypes, hashlib, json, os, shutil, subprocess, sys, time, uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
GIB = 1024 ** 3
ACTIVE = {"launching", "running"}
MAX_JOBS = 2
MIN_FREE_RAM = 8 * GIB
MIN_FREE_DISK = 100 * GIB
RESERVED_CORES = 2

class QueueError(RuntimeError):
    pass

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()

def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()

def atomic_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    with temp.open("xb") as f:
        f.write(canonical(data))
        f.flush()
        os.fsync(f.fileno())
    os.replace(temp, path)

def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))

def process_stamp(pid):
    """None means verified dead. Errors mean unknown and MUST NOT be reclaimed."""
    pid = int(pid)
    if os.name == "nt":
        from ctypes import wintypes
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            error = ctypes.get_last_error()
            if error == 87:
                return None
            raise QueueError("Process identity unknown for PID %s (Windows %s)" % (pid, error))
        try:
            code = wintypes.DWORD()
            kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
            if not kernel.GetExitCodeProcess(handle, ctypes.byref(code)):
                raise QueueError("Cannot read process state")
            if code.value != 259:
                return None
            times = [wintypes.FILETIME() for _ in range(4)]
            kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
            if not kernel.GetProcessTimes(handle, *(ctypes.byref(t) for t in times)):
                raise QueueError("Cannot read process creation time")
            return str((times[0].dwHighDateTime << 32) | times[0].dwLowDateTime)
        finally:
            kernel.CloseHandle.argtypes = [wintypes.HANDLE]
            kernel.CloseHandle(handle)
    try:
        stat = Path("/proc/%s/stat" % pid).read_text()
        fields = stat[stat.rfind(")") + 2:].split()
        return None if fields[0] == "Z" else fields[19]
    except FileNotFoundError:
        return None
    except OSError as e:
        raise QueueError("Cannot verify PID %s: %s" % (pid, e))

def identity(pid=None):
    pid = os.getpid() if pid is None else pid
    stamp = process_stamp(pid)
    if stamp is None:
        raise QueueError("Process exited before identity was recorded")
    return {"pid": pid, "created": stamp}

def alive(owner):
    if not owner:
        raise QueueError("Missing process identity; fail closed")
    return process_stamp(owner["pid"]) == owner["created"]

@contextlib.contextmanager
def locked(root, timeout=15, name=".queue.lock"):
    """Kernel file lock releases after a crash; lock path is never unlinked."""
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    lock = (root / name).open("a+b")
    lock.seek(0, os.SEEK_END)
    if not lock.tell():
        lock.write(b"\0")
        lock.flush()
    deadline = time.monotonic() + timeout
    acquired = False
    try:
        while not acquired:
            lock.seek(0)
            try:
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                acquired = True
            except OSError:
                if time.monotonic() >= deadline:
                    raise QueueError("Queue is locked by another process")
                time.sleep(.05)
        yield
    finally:
        if acquired:
            lock.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)
        lock.close()

def state_path(root):
    return Path(root) / "queue.json"

def load_state(root):
    path = state_path(root)
    return read_json(path) if path.exists() else {"schema": 1, "jobs": {}}

def save_state(root, state):
    atomic_json(state_path(root), state)

def normalize_spec(spec):
    spec = dict(spec)
    if "outputCreatedByChild" in spec and not isinstance(spec["outputCreatedByChild"], bool):
        raise QueueError("outputCreatedByChild must be boolean")
    gate_name = spec.get("benchmarkGateName")
    if gate_name is not None and (not isinstance(gate_name, str) or not gate_name.startswith("benchmark-gate") or not gate_name.endswith(".json") or "/" in gate_name or "\\" in gate_name or ".." in gate_name):
        raise QueueError("benchmarkGateName must be a local benchmark-gate*.json filename")
    for name in ("sourceSha256", "configSha256", "codeSha256"):
        value = str(spec.get(name, "")).lower()
        if len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
            raise QueueError("%s must be a SHA256" % name)
        spec[name] = value
    if not isinstance(spec.get("argv"), list) or not spec["argv"] or not all(isinstance(v, str) for v in spec["argv"]):
        raise QueueError("argv must be a nonempty string array; no shell execution")
    spec["cwd"] = str(Path(spec["cwd"]).resolve(strict=True))
    spec["dispatchScope"] = spec.get("dispatchScope", "production")
    if spec["dispatchScope"] not in {"production", "benchmark"}:
        raise QueueError("dispatchScope must be production or benchmark")
    spec["dependencies"] = sorted(set(spec.get("dependencies", [])))
    spec["memoryGiB"] = float(spec.get("memoryGiB", 3))
    spec["cpuThreads"] = int(spec.get("cpuThreads", 2))
    spec["maxAttempts"] = int(spec.get("maxAttempts", 2))
    spec["timeoutSeconds"] = int(spec.get("timeoutSeconds", 3600))
    if not 0 < spec["memoryGiB"] <= 16 or not 1 <= spec["cpuThreads"] <= 14:
        raise QueueError("Invalid resource reservation")
    if not 1 <= spec["maxAttempts"] <= 3 or not 1 <= spec["timeoutSeconds"] <= 86400:
        raise QueueError("Attempts must be 1..3 and timeout 1..86400 seconds")
    spec["retainOutputPath"] = spec["dispatchScope"] == "benchmark"
    if spec.get("workspaceRoot"):
        approved = Path("C:/FORKWork/queue").resolve()
        actual = Path(spec["workspaceRoot"]).resolve()
        if os.name != "nt" or actual != approved or str(approved).lower() != "c:\\forkwork\\queue":
            raise QueueError("workspaceRoot must be the approved shallow Desktop C:/FORKWork/queue")
        spec["workspaceRoot"] = str(actual)
    argv_text = " ".join(spec["argv"])
    if "{output_dir}" not in argv_text and not (spec["dispatchScope"] == "benchmark" and "{report_dir}" in argv_text):
        raise QueueError("argv must use {output_dir}, or benchmark {report_dir}")
    spec["reportDirectoryName"] = "benchmark-report" if "{report_dir}" in argv_text else None
    return spec

def enqueue(root, spec):
    spec = normalize_spec(spec)
    job_id = digest(spec)
    with locked(root):
        state = load_state(root)
        if job_id in state["jobs"]:
            return job_id
        if any(dep not in state["jobs"] for dep in spec["dependencies"]):
            raise QueueError("Dependencies must already be enqueued")
        state["jobs"][job_id] = {"spec": spec, "status": "queued", "attempts": 0,
                                 "createdAt": time.time(), "reason": None, "lease": None}
        save_state(root, state)
    return job_id

def resource_snapshot(root):
    if os.name == "nt":
        class MEMORYSTATUSEX(ctypes.Structure):
            _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
                (name, ctypes.c_ulonglong) for name in (
                    "totalPhys", "availPhys", "totalPage", "availPage",
                    "totalVirtual", "availVirtual", "availExtended")]
        mem = MEMORYSTATUSEX()
        mem.length = ctypes.sizeof(mem)
        if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(mem)):
            raise QueueError("Cannot read available physical memory")
        available = mem.availPhys
    else:
        values = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
        available = int(values["MemAvailable"].split()[0]) * 1024
    return {"freeRam": available, "freeDisk": shutil.disk_usage(root).free, "cores": os.cpu_count() or 1}

def can_start(spec, active, snapshot, max_jobs=MAX_JOBS):
    if len(active) >= max_jobs:
        return False, "configured %s-job limit" % max_jobs
    if snapshot["freeDisk"] < MIN_FREE_DISK:
        return False, "100 GiB free disk floor"
    # Count active declarations again conservatively, including memory not allocated yet.
    reserved = sum(j["spec"]["memoryGiB"] * GIB for j in active)
    if snapshot["freeRam"] - reserved - spec["memoryGiB"] * GIB < MIN_FREE_RAM:
        return False, "8 GiB free RAM floor including reservations"
    if sum(j["spec"]["cpuThreads"] for j in active) + spec["cpuThreads"] > snapshot["cores"] - RESERVED_CORES:
        return False, "two reserved CPU cores"
    return True, None

def gate_reason(spec, gate_path, fixtures=False, job_id=None):
    if fixtures:
        expected = HERE / "fixtures" / "fake_job.py"
        if not spec.get("fixture") or Path(spec["argv"][0]).resolve() != Path(sys.executable).resolve() or len(spec["argv"]) < 2 or Path(spec["argv"][1]).resolve() != expected:
            return "Fixture mode permits only the bundled tiny fake job"
        return None
    if not gate_path:
        return "No accepted seam gate"
    try:
        gate = read_json(gate_path)
        if not isinstance(gate, dict):
            return "Admission gate must be a JSON object"
        binding = gate
        if spec.get("dispatchScope") == "benchmark":
            if (gate.get("kind") != "fork-experimental-benchmark-admission" or
                gate.get("scope") != "pilot-policy" or gate.get("status") != "PASS" or
                gate.get("acceptedByCoordinator") is not True or
                gate.get("productionAccepted") is not False or
                gate.get("independentValidationRequired") is not True):
                return "Benchmark admission is unknown, unaccepted, or permits production"
            bindings = gate.get("queueBindings", [])
            if not isinstance(bindings, list) or len(bindings) != 3:
                return "Benchmark gate must bind exactly three queue jobs"
            ids = [entry.get("jobId") for entry in bindings if isinstance(entry, dict)]
            if (len(ids) != 3 or not all(isinstance(i, str) and len(i) == 64 and all(c in "0123456789abcdef" for c in i) for i in ids)
                or len(set(ids)) != 3 or job_id not in ids):
                return "Queue job is not one of the three benchmark bindings"
            binding = next(entry for entry in bindings if entry["jobId"] == job_id)
            proofs = gate.get("qualityProofs", {})
            if not isinstance(proofs, dict):
                return "Benchmark quality proofs must be an object"
            for name in ("roadsV2", "streamingEquivalence"):
                proof = proofs.get(name, {})
                if not isinstance(proof, dict) or not proof.get("path"):
                    return "Benchmark policy proof missing: " + name
                proof_path = Path(proof["path"])
                h = hashlib.sha256()
                with proof_path.open("rb") as f:
                    for block in iter(lambda: f.read(1024 * 1024), b""):
                        h.update(block)
                if h.hexdigest() != str(proof.get("sha256", "")).lower():
                    return "Benchmark policy proof hash mismatch: " + name
                proof_data = read_json(proof_path)
                if not isinstance(proof_data, dict) or proof_data.get("status") != "PASS":
                    return "Benchmark policy proof is not PASS: " + name
        elif gate.get("status") != "passed" or gate.get("acceptedByCoordinator") is not True:
            return "Seam gate is unknown, failed, or not accepted"
        for name in ("sourceSha256", "configSha256", "codeSha256"):
            if str(binding.get(name, "")).lower() != spec[name]:
                return "Admission fingerprint mismatch: " + name
        if spec.get("dispatchScope") != "benchmark":
            evidence = gate.get("evidenceSha256", "")
            if len(evidence) != 64 or any(c not in "0123456789abcdef" for c in evidence.lower()):
                return "Seam gate lacks evidence SHA256"
    except (OSError, ValueError, TypeError) as error:
        return "Cannot validate seam gate: " + str(error)
    return None

def file_manifest(directory):
    directory = Path(directory)
    files = []
    def reject_link(path):
        info = path.lstat()
        if path.is_symlink() or getattr(info, "st_file_attributes", 0) & 0x400:
            raise QueueError("Output symlinks and Windows reparse points are not allowed")
    reject_link(directory)
    for base, dirs, names in os.walk(directory, followlinks=False):
        dirs.sort()
        for name in dirs:
            reject_link(Path(base) / name)
        for name in sorted(names):
            path = Path(base) / name
            reject_link(path)
            h = hashlib.sha256()
            with path.open("rb") as f:
                for block in iter(lambda: f.read(1024 * 1024), b""):
                    h.update(block)
            files.append({"path": path.relative_to(directory).as_posix(), "bytes": path.stat().st_size, "sha256": h.hexdigest()})
    files.sort(key=lambda entry: entry["path"])
    if not files:
        raise QueueError("Successful job produced no files")
    return {"files": files, "sha256": digest(files)}

def artifact_root(root, spec):
    if not spec.get("workspaceRoot"):
        return Path(root)
    actual = Path(spec["workspaceRoot"]).resolve()
    if os.name != "nt" or str(actual).lower() != "c:\\forkwork\\queue":
        raise QueueError("Approved shallow workspace changed or resolves through a junction")
    return actual

def attempt_directory(root, job_id, job):
    return artifact_root(root, job["spec"]) / "jobs" / job_id / ("attempt-%s" % job["attempts"])

def completion_marker(root, job_id):
    return Path(root) / "completed" / (job_id + ".json")

def verify_output(root, job_id, job):
    output = Path(job["outputPath"]) if job.get("outputPath") else Path(root) / "outputs" / job_id
    actual = file_manifest(output)
    expected = job.get("output")
    if actual != expected:
        raise QueueError("Immutable output has changed: " + job_id)
    if job.get("reports"):
        if file_manifest(job["reports"]["path"]) != job["reports"]["manifest"]:
            raise QueueError("Immutable benchmark reports have changed: " + job_id)
    return actual

def recover(state, root=None):
    for job_id, job in state["jobs"].items():
        if job["status"] not in ACTIVE:
            continue
        lease = job["lease"] or {}
        try:
            supervisor = lease.get("supervisor")
            if supervisor and alive(supervisor):
                continue
            if not supervisor:
                dispatcher = lease.get("dispatcher")
                if not dispatcher or alive(dispatcher) or time.time() - lease.get("createdAt", time.time()) < 30:
                    job["reason"] = "Unresolved launch reservation"
                    continue
                # No supervisor can spawn a child before atomically claiming this token.
            if lease.get("childLaunchPending"):
                job["reason"] = "Unknown child launch; manual verification required"
                continue
            if lease.get("child") and alive(lease["child"]):
                job["reason"] = "Supervisor died; verified child still alive, no duplicate dispatch"
                continue
            marker_path = completion_marker(root, job_id) if root else None
            if root and marker_path.exists():
                marker = read_json(marker_path)
                expected_path = attempt_directory(root, job_id, job) / "output"
                if marker.get("jobId") != job_id or Path(marker.get("outputPath", "")) != expected_path:
                    job["status"], job["reason"] = "blocked", "Completion marker ownership mismatch"
                    continue
                recovered = dict(job, outputPath=marker["outputPath"], output=marker["output"],
                                 reports=marker.get("reports"))
                verify_output(root, job_id, recovered)
                job.update(outputPath=recovered["outputPath"], output=recovered["output"], reports=recovered["reports"],
                           status="complete", lease=None, reason=None, acceptance="WRITTEN_UNACCEPTED",
                           independentValidationRequired=True)
                continue
            if root and (Path(root) / "outputs" / job_id).exists():
                manifest_path = Path(root) / "manifests" / (job_id + ".json")
                if not manifest_path.exists():
                    job["status"], job["reason"] = "blocked", "Output rename completed without manifest; manual verification required"
                    continue
                manifest = read_json(manifest_path)
                if file_manifest(Path(root) / "outputs" / job_id) != manifest:
                    job["status"], job["reason"] = "blocked", "Interrupted output commit failed hash verification"
                    continue
                job["status"], job["output"], job["lease"], job["reason"] = "complete", manifest, None, None
                job["acceptance"], job["independentValidationRequired"] = "WRITTEN_UNACCEPTED", True
                continue
            job["status"] = "queued" if job["attempts"] < job["spec"]["maxAttempts"] else "failed"
            job["reason"] = "Verified dead lease recovered"
            job["lease"] = None
        except (QueueError, OSError, ValueError, KeyError) as error:
            job["reason"] = "Lease identity unknown; no reclaim: " + str(error)

def owner_working_set(owner):
    """One verified owner's resident set; largest owner avoids shared-page double counting."""
    if not alive(owner):
        return 0
    if os.name != "nt":
        values = dict(line.split(":", 1) for line in Path("/proc/%s/status" % owner["pid"]).read_text().splitlines() if ":" in line)
        return int(values.get("VmRSS", "0 kB").split()[0]) * 1024
    from ctypes import wintypes
    size = ctypes.c_size_t
    class Counters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("faults", wintypes.DWORD)] + [
            (name, size) for name in ("peakWS", "workingSet", "peakPaged", "paged",
                                      "peakNonPaged", "nonPaged", "pagefile", "peakPagefile")]
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.K32GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.c_void_p, wintypes.DWORD]
    handle = kernel.OpenProcess(0x1000 | 0x10, False, owner["pid"])
    if not handle:
        if not alive(owner):
            return 0
        raise QueueError("Cannot measure reserved owner working set")
    try:
        if not alive(owner):
            return 0
        counters = Counters(); counters.cb = ctypes.sizeof(counters)
        if not kernel.K32GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
            raise QueueError("Cannot read reserved owner working set")
        return counters.workingSet
    finally:
        kernel.CloseHandle(handle)

def external_active(root, fixtures=False):
    path = Path(root) / "external-reservations.json"
    if fixtures and not path.exists():
        return []
    if not path.exists():
        raise QueueError("External reservations file missing; bulk dispatch held")
    data = read_json(path)
    if not isinstance(data, dict) or data.get("schema") != 1 or not isinstance(data.get("reservations"), list):
        raise QueueError("Invalid external reservations schema")
    reservations = []
    for entry in data["reservations"]:
        if not isinstance(entry, dict) or not 0 <= float(entry.get("memoryGiB", -1)) <= 31 or not 0 <= int(entry.get("cpuThreads", -1)) <= 16:
            raise QueueError("Invalid external resource reservation")
        if entry.get("kind") == "coordinator-hold":
            if entry.get("acceptedByCoordinator") is not True:
                raise QueueError("Unaccepted coordinator reservation")
            if entry.get("held") is not True:
                continue
            if entry.get("owners"):
                raise QueueError("Static coordinator holds must not claim process owners")
            declared = float(entry["memoryGiB"])
            cores = int(entry["cpuThreads"])
            included = entry.get("memoryIncludedInFreeFloor") is True
            if included and not 0 <= declared * GIB <= MIN_FREE_RAM:
                raise QueueError("Static reservation exceeds the host free-memory floor")
            reservations.append({"spec": {"memoryGiB": 0 if included else declared, "cpuThreads": cores},
                                 "external": entry.get("id"), "kind": "coordinator-hold",
                                 "memoryIncludedInFreeFloor": included, "declaredMemoryGiB": declared})
            continue
        if not entry.get("owners"):
            raise QueueError("External reservation has no verified owner")
        statuses = [alive(owner) for owner in entry["owners"]]
        if any(statuses):
            observed = max((owner_working_set(owner) for owner, live in zip(entry["owners"], statuses) if live), default=0)
            reservations.append({"spec": {"memoryGiB": max(0, float(entry["memoryGiB"]) - observed / GIB),
                                           "cpuThreads": int(entry["cpuThreads"])},
                                 "external": entry.get("id"), "declaredMemoryGiB": float(entry["memoryGiB"]),
                                 "observedLargestOwnerWorkingSet": observed})
    return reservations

class ChildGuard:
    """Windows kernel owns this child tree; supervisor death closes and kills it."""
    def __init__(self, memory_gib):
        self.handle = None
        if os.name != "nt":
            return
        from ctypes import wintypes
        size = ctypes.c_size_t
        class Basic(ctypes.Structure):
            _fields_ = [("processTime", ctypes.c_longlong), ("jobTime", ctypes.c_longlong),
                        ("flags", wintypes.DWORD), ("minWorking", size), ("maxWorking", size),
                        ("processes", wintypes.DWORD), ("affinity", size),
                        ("priority", wintypes.DWORD), ("scheduling", wintypes.DWORD)]
        class Extended(ctypes.Structure):
            _fields_ = [("basic", Basic), ("io", ctypes.c_ulonglong * 6),
                        ("processMemory", size), ("jobMemory", size), ("peakProcess", size), ("peakJob", size)]
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.CreateJobObjectW.restype = wintypes.HANDLE
        self.kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        self.kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        self.kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        self.kernel.TerminateJobObject.argtypes = [wintypes.HANDLE, wintypes.UINT]
        self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.handle = self.kernel.CreateJobObjectW(None, None)
        if not self.handle:
            raise QueueError("Cannot create Windows child job")
        info = Extended()
        info.basic.flags = 0x2000 | 0x200 | 0x10  # kill on close, whole-job RAM, affinity
        info.basic.affinity = (1 << max(1, (os.cpu_count() or 1) - RESERVED_CORES)) - 1
        info.jobMemory = max(128 * 1024 * 1024, int(memory_gib * GIB))
        if not self.kernel.SetInformationJobObject(self.handle, 9, ctypes.byref(info), ctypes.sizeof(info)):
            self.close()
            raise QueueError("Cannot enforce Windows child limits")
    def assign(self, proc):
        if self.handle and not self.kernel.AssignProcessToJobObject(self.handle, int(proc._handle)):
            if proc.poll() is None:
                proc.terminate()  # Popen's exact process handle, never an unverified PID.
                proc.wait(timeout=10)
                raise QueueError("Cannot assign child to bounded Windows job")
    def terminate(self, proc):
        if self.handle:
            if not self.kernel.TerminateJobObject(self.handle, 9):
                raise QueueError("Cannot stop owned Windows child job")
        elif proc.poll() is None:
            proc.terminate()
    def close(self):
        if self.handle:
            self.kernel.CloseHandle(self.handle)
            self.handle = None

def detach_options():
    if os.name == "nt":
        return {"creationflags": subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW}
    return {"start_new_session": True}

def concurrency_limit(root):
    path = Path(root) / "concurrency-policy.json"
    if not path.exists():
        return MAX_JOBS
    policy = read_json(path)
    if not isinstance(policy, dict) or policy.get("acceptedByCoordinator") is not True:
        raise QueueError("Concurrency policy is not coordinator accepted")
    limit = policy.get("maxActiveJobs")
    if not isinstance(limit, int) or not 2 <= limit <= max(2, (os.cpu_count() or 2) - RESERVED_CORES):
        raise QueueError("Concurrency limit is outside the Desktop CPU envelope")
    if limit > MAX_JOBS:
        if policy.get("measuredMemoryBudget") is not True or policy.get("cpuOrDiskBenefitVerified") is not True:
            raise QueueError("Concurrency increase lacks measured capacity and benefit acceptance")
        proof = policy.get("measurementEvidence", {})
        data = Path(proof["path"]).read_bytes()
        if hashlib.sha256(data).hexdigest() != str(proof.get("sha256", "")).lower():
            raise QueueError("Concurrency measurement hash mismatch")
        if read_json(proof["path"]).get("status") != "PASS":
            raise QueueError("Concurrency measurement is not accepted PASS")
    return limit

def dispatch_once(root, gate_path=None, fixtures=False, snapshot=None):
    root = Path(root).resolve()
    launched = []
    with locked(root):
        state = load_state(root)
        recover(state, root)
        if (root / "STOP").exists():
            save_state(root, state)
            return []
        for job_id, job in state["jobs"].items():
            if job["status"] != "queued":
                continue
            job_gate = root / job["spec"].get("benchmarkGateName", "benchmark-gate.json") if job["spec"].get("dispatchScope") == "benchmark" else gate_path
            reason = gate_reason(job["spec"], job_gate, fixtures, job_id)
            if reason:
                job["reason"] = reason
                continue
            if job["spec"].get("dispatchScope") == "benchmark":
                missing = [arg for arg in job["spec"]["argv"] if Path(arg).is_absolute()
                           and arg.lower().endswith(".json") and not Path(arg).is_file()]
                if missing:
                    job["reason"] = "Waiting for frozen benchmark configuration: " + missing[0]
                    continue
            deps = [state["jobs"][d] for d in job["spec"]["dependencies"]]
            if any(d["status"] in {"failed", "blocked"} for d in deps):
                job["status"], job["reason"] = "blocked", "A dependency failed"
                continue
            if any(d["status"] != "complete" for d in deps):
                job["reason"] = "Waiting for dependencies"
                continue
            try:
                for dep in job["spec"]["dependencies"]:
                    verify_output(root, dep, state["jobs"][dep])
            except QueueError as error:
                job["status"], job["reason"] = "blocked", str(error)
                continue
            try:
                active = [j for j in state["jobs"].values() if j["status"] in ACTIVE] + external_active(root, fixtures)
            except (QueueError, OSError, ValueError, KeyError) as error:
                job["reason"] = "External reservations unresolved: " + str(error)
                continue
            limit = concurrency_limit(root)
            if job["spec"].get("dispatchScope") == "benchmark":
                benchmark_limit = read_json(job_gate).get("maxConcurrentBenchmarks", 1) if not fixtures else 1
                if benchmark_limit not in (1, 2):
                    job["reason"] = "Unsupported benchmark concurrency authorization"
                    continue
                if sum(j["spec"].get("dispatchScope") == "benchmark" for j in active) >= benchmark_limit:
                    job["reason"] = "Scoped benchmark concurrency limit"
                    continue
                camera_slots = sum(j.get("external") == "main-camera" for j in active)
                limit = max(limit, benchmark_limit + camera_slots)
            allowed, reason = can_start(job["spec"], active, snapshot or resource_snapshot(root), limit)
            if not allowed:
                job["reason"] = reason
                continue
            token = uuid.uuid4().hex
            job["attempts"] += 1
            job["status"] = "launching"
            job["reason"] = None
            job["lease"] = {"token": token, "dispatcher": identity(), "supervisor": None,
                            "child": None, "childLaunchPending": False, "createdAt": time.time()}
            save_state(root, state)
            log = root / "jobs" / job_id / ("supervisor-%s.log" % job["attempts"])
            log.parent.mkdir(parents=True, exist_ok=True)
            with log.open("ab", buffering=0) as stream:
                proc = subprocess.Popen([sys.executable, str(HERE / "queue.py"), "supervise",
                                         str(root), job_id, token], stdin=subprocess.DEVNULL,
                                        stdout=stream, stderr=subprocess.STDOUT, **detach_options())
            try:
                job["lease"]["supervisor"] = identity(proc.pid)
            except QueueError:
                # The reservation remains unresolved rather than guessing that no child exists.
                job["reason"] = "Supervisor identity not captured; waiting for supervisor claim"
            save_state(root, state)
            launched.append(job_id)
        save_state(root, state)
    return launched

def owned_job(state, job_id, token):
    job = state["jobs"][job_id]
    if job["status"] not in ACTIVE or (job.get("lease") or {}).get("token") != token:
        raise QueueError("Lease no longer owned")
    return job

def supervise(root, job_id, token):
    root = Path(root).resolve()
    proc = None
    guard = None
    try:
        with locked(root):
            state = load_state(root)
            job = owned_job(state, job_id, token)
            job["lease"]["supervisor"] = identity()
            job["status"] = "running"
            attempt = job["attempts"]
            spec = job["spec"]
            attempt_root = attempt_directory(root, job_id, job)
            staging = attempt_root / "output"
            report_dir = attempt_root / "benchmark-report"
            if spec.get("outputCreatedByChild") is True:
                attempt_root.mkdir(parents=True, exist_ok=False)
            else:
                staging.mkdir(parents=True, exist_ok=False)
            checkpoint = artifact_root(root, spec) / "jobs" / job_id / "checkpoint.json"
            argv = [a.replace("{output_dir}", str(staging)).replace("{report_dir}", str(report_dir)).replace("{checkpoint}", str(checkpoint)).replace("{job_id}", job_id) for a in spec["argv"]]
            # Persist the ambiguous launch interval BEFORE Popen; crashes here never auto-retry.
            job["lease"]["childLaunchPending"] = True
            save_state(root, state)
            env = os.environ.copy()
            env.update({"FORK_OUTPUT_DIR": str(staging), "FORK_CHECKPOINT": str(checkpoint),
                        "FORK_JOB_ID": job_id, "FORK_JOB_ATTEMPT": str(attempt), "FORK_REPORT_DIR": str(report_dir),
                        "RAYON_NUM_THREADS": str(spec["cpuThreads"]), "OMP_NUM_THREADS": str(spec["cpuThreads"])})
            log = root / "jobs" / job_id / ("child-%s.log" % attempt)
            guard = ChildGuard(spec["memoryGiB"])
            with log.open("ab", buffering=0) as stream:
                proc = subprocess.Popen(argv, cwd=spec["cwd"], env=env, stdin=subprocess.DEVNULL,
                                        stdout=stream, stderr=subprocess.STDOUT,
                                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            guard.assign(proc)
            try:
                job["lease"]["child"] = identity(proc.pid)
            except QueueError:
                if proc.poll() is None:
                    raise
                job["lease"]["child"] = None  # This exact Popen handle proves the child exited.
            job["lease"]["childLaunchPending"] = False
            save_state(root, state)
        deadline = time.monotonic() + spec["timeoutSeconds"]
        failure_reason = None
        while proc.poll() is None:
            snapshot = resource_snapshot(root)
            if time.monotonic() >= deadline:
                failure_reason = "Runtime limit exceeded"
            elif snapshot["freeRam"] < MIN_FREE_RAM or snapshot["freeDisk"] < MIN_FREE_DISK:
                failure_reason = "Live RAM/disk floor reached; owned child stopped"
            if failure_reason:
                guard.terminate(proc)
                break
            time.sleep(.25)
        code = proc.wait(timeout=15)
        guard.close()  # No detached descendant may continue writing during output hashing.
        guard = None
        with locked(root):
            state = load_state(root)
            job = owned_job(state, job_id, token)
            if code != 0 or failure_reason:
                job["status"] = "queued" if attempt < spec["maxAttempts"] else "failed"
                job["reason"] = failure_reason or "Child exited %s" % code
                job["lease"] = None
            else:
                manifest = file_manifest(staging)
                reports = None
                if spec.get("reportDirectoryName"):
                    reports = {"path": str(report_dir), "manifest": file_manifest(report_dir)}
                if spec.get("retainOutputPath"):
                    marker = completion_marker(root, job_id)
                    if marker.exists():
                        raise QueueError("Immutable completion marker already exists")
                    atomic_json(marker, {"jobId": job_id, "outputPath": str(staging), "output": manifest,
                                         "reports": reports, "acceptance": "WRITTEN_UNACCEPTED"})
                    job["outputPath"], job["reports"] = str(staging), reports
                else:
                    destination = root / "outputs" / job_id
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    if destination.exists():
                        raise QueueError("Immutable destination already exists; manual verification required")
                    os.rename(staging, destination)
                atomic_json(root / "manifests" / (job_id + ".json"), manifest)
                job["status"], job["output"] = "complete", manifest
                job["acceptance"] = "WRITTEN_UNACCEPTED"
                job["independentValidationRequired"] = True
                job["reason"], job["lease"], job["completedAt"] = None, None, time.time()
            save_state(root, state)
    except BaseException as error:
        if guard:
            guard.close()
        # Never release an uncertain child or overwrite a previous immutable output.
        try:
            with locked(root):
                state = load_state(root)
                job = owned_job(state, job_id, token)
                job["reason"] = "Supervisor exception: " + repr(error)
                if proc is None and not job["lease"].get("childLaunchPending"):
                    job["status"], job["lease"] = "failed", None
                save_state(root, state)
        except Exception:
            pass
        print(repr(error), flush=True)
        return 1
    return 0

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("identity"); p.add_argument("pid", type=int)
    p = sub.add_parser("enqueue"); p.add_argument("root"); p.add_argument("spec")
    p = sub.add_parser("status"); p.add_argument("root")
    p = sub.add_parser("verify"); p.add_argument("root")
    p = sub.add_parser("run"); p.add_argument("root"); p.add_argument("--gate")
    p.add_argument("--fixtures", action="store_true"); p.add_argument("--once", action="store_true")
    p = sub.add_parser("supervise"); p.add_argument("root"); p.add_argument("job_id"); p.add_argument("token")
    args = parser.parse_args()
    if args.command == "identity":
        print(json.dumps(identity(args.pid)))
    elif args.command == "enqueue":
        print(enqueue(args.root, read_json(args.spec)))
    elif args.command == "status":
        with locked(args.root):
            state = load_state(args.root); recover(state, args.root); save_state(args.root, state)
        print(json.dumps(state, indent=2))
    elif args.command == "verify":
        with locked(args.root):
            state = load_state(args.root)
            result = {j: verify_output(args.root, j, job) for j, job in state["jobs"].items() if job["status"] == "complete"}
        print(json.dumps({"verified": len(result), "jobs": result}, indent=2))
    elif args.command == "supervise":
        return supervise(args.root, args.job_id, args.token)
    else:
        with locked(args.root, timeout=0, name=".scheduler.lock"):
            while True:
                launched = dispatch_once(args.root, args.gate, args.fixtures)
                try:
                    gate = read_json(args.gate) if args.gate else {}
                except (OSError, ValueError):
                    gate = {}
                current = load_state(args.root)
                counts = {}
                for job in current["jobs"].values():
                    counts[job["status"]] = counts.get(job["status"], 0) + 1
                accepted = gate.get("status") == "passed" and gate.get("acceptedByCoordinator") is True
                try:
                    benchmark_gate = read_json(Path(args.root) / "benchmark-gate.json")
                except (OSError, ValueError):
                    benchmark_gate = {}
                benchmark_enabled = (benchmark_gate.get("status") == "PASS" and
                    benchmark_gate.get("kind") == "fork-experimental-benchmark-admission" and
                    benchmark_gate.get("acceptedByCoordinator") is True and
                    benchmark_gate.get("productionAccepted") is False)
                heartbeat = {"schema": 1, "owner": identity(), "updatedAt": time.time(), "launched": launched,
                             "gate": str(Path(args.gate).resolve()) if args.gate else None,
                             "gateStatus": gate.get("status", "UNKNOWN"),
                             "dispatch": "enabled" if accepted else ("BENCHMARK_ONLY" if benchmark_enabled else "HELD"),
                             "productionDispatch": "enabled" if accepted else "HELD",
                             "benchmarkGateStatus": benchmark_gate.get("status", "UNKNOWN"),
                             "jobs": counts, "fixtures": args.fixtures}
                atomic_json(Path(args.root) / "scheduler.json", heartbeat)
                if launched or args.once:
                    print(json.dumps(heartbeat), flush=True)
                if args.once or (Path(args.root) / "STOP").exists():
                    break
                time.sleep(2)
    return 0

if __name__ == "__main__":
    try:
        sys.exit(main())
    except QueueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
