# Durable Singapore generation queue

This queue runs on Desktop with Python 3.11 and the standard library. It does not control Laptop, Minecraft, OBS, or the hackathon repository. A hidden scheduled task keeps an idle dispatcher alive after chat or SSH disconnects. The coordinator owns acceptance of the seam gate; a failed, missing, unknown, or fingerprint-mismatched gate dispatches no generation jobs.

## Invocation contract

Run queue.py enqueue QUEUE_ROOT job.json. Identical normalized specs return the same job ID. Each spec includes sourceSha256, configSha256, codeSha256; argv as an array (never a shell string); cwd; dependencies; memoryGiB; cpuThreads; maxAttempts (1 to 3); timeoutSeconds (1 to 86400).

The runner replaces {output_dir}, {checkpoint}, and {job_id} in argv. A typical pipeline invocation is node pipeline-adapter.mjs --job immutable-tile-job.json --attempt-root {output_dir} --checkpoint {checkpoint}. It also supplies FORK_OUTPUT_DIR, FORK_CHECKPOINT, FORK_JOB_ID, FORK_JOB_ATTEMPT, RAYON_NUM_THREADS, and OMP_NUM_THREADS.

The adapter MUST verify input/source/code hashes itself and write only beneath the supplied output directory and checkpoint path. It must exit zero only after complete validation and wait for its children. The queue is a cooperative job coordinator, not a filesystem sandbox. Windows job objects bound the owned process tree's RAM, lifetime and CPU affinity. The queue cannot prevent an arbitrary trusted adapter from writing unrelated paths.

Namespace integration: queueFingerprints(descriptor) in namespace/index.mjs binds source identity plus all masks, ancillary inputs, generator configuration, and spatial/datum configuration. Pass these three returned fingerprints in the spec. A namespace run directory may contain its own queue root; do not nest a second namespace attempt inside the queue attempt. Host paths in a queue spec currently cause safe cache misses if a checkout moves, never reuse across a changed path.

## Persistence and exclusivity

queue.json is atomically replaced under a kernel file lock. The dispatcher also holds one kernel scheduler lock. Jobs have creation-time-qualified PID identities, launch tokens and attempt counts. No wall-clock lease expiration permits duplicate writers. A live, unknown or ambiguous child launch stays held. Only a verified dead/reused PID can be recovered automatically.

Each attempt writes jobs/JOB_ID/attempt-N/output. The checkpoint remains jobs/JOB_ID/checkpoint.json between attempts. An exit-zero attempt receives a streamed SHA256 file manifest and is moved to outputs/JOB_ID without overwrite. Output symlinks and Windows reparse points are rejected. A dependency is eligible only after its immutable output hashes verify. An interrupted completed rename is accepted only when the existing manifest verifies; missing evidence blocks for manual review.

Windows job objects stop descendants when their supervisor dies, enforce whole-job RAM, and reserve the last two logical CPU cores for other work. A failed attempt retries at most the declared bound. Runtime expiration kills the exact owned job object. Live free RAM and disk are checked every 250 ms while the child runs; crossing a floor stops that owned tree. A small allocation/write between a sample and termination remains possible.

## Resource contract

At most two heavy operations are active across queue jobs and recorded external reservations. Admission requires at least 8 GiB free RAM after conservative declared reservations, 100 GiB free disk, and two unused logical CPU cores. The 8 GiB floor preserves the requested 6 GiB Main allowance with 2 GiB margin. Actual Windows memory use is capped per job (minimum fixture cap 128 MiB). Queue invocations also have CPU affinity limited to the other cores. Resource declarations and external reservations must be truthful.

external-reservations.json uses {"schema":1,"reservations":[...]}. Each entry has id, owners:[{"pid":123,"created":"Windows creation token"}], memoryGiB, cpuThreads. Obtain exact owners with queue.py identity PID. One operation may list a launcher and actual worker; it consumes one slot until every owner is verified dead. Missing or unreadable reservations fail closed for production. Existing operations must reserve before dispatch; the coordinator owns sequencing new manual operations with this file.

## Gate contract and held startup

Production gate JSON must contain status:"passed", acceptedByCoordinator:true, evidenceSha256, sourceSha256, configSha256, codeSha256. All fingerprints must match the candidate job. The coordinator's current status:"HOLD" file intentionally fails this contract. UTF-8 BOM input is supported. The worker never edits or accepts the gate.

Install-QueueTask.ps1 -QueueRoot ABSOLUTE_ROOT registers a hidden, limited-user, logon-triggered task and starts it. Run-Queue.ps1 starts the Python dispatcher hidden and waits for it. Task restart is bounded to three retries, with one-minute intervals. It does not wake the machine or survive Windows being shut down; it resumes on the user's next Windows logon. A newly installed task can remain empty and visibly HELD.

scheduler.json records PID plus creation token, heartbeat time, gate status and job counts. scheduler.stdout.log and scheduler.stderr.log contain process logs. status reads/reconciles queue state; verify rehashes every completed output. Creating QUEUE_ROOT/STOP stops dispatch without stopping active supervised jobs. Remove STOP and start the task to resume. Do not remove ambiguous lease records or existing outputs to force a retry.

## Verification

Run py -3 tools/singapore-full/queue/test_queue.py on Desktop. Tiny fake processes verify competing dispatchers, duplicate enqueue, fingerprint invalidation, actual supervisor crash and child-tree termination, checkpoint resume, bounded retries/timeouts, output tamper rejection, resource/seam gates, external PID reservations and dependency sequencing. --fixtures permits only the bundled tiny fixture executable path; it cannot launch the production adapter. No Singapore generation is performed by these tests.

## Scoped experimental benchmarks

A benchmark spec uses dispatchScope:"benchmark". Its parent-owned gate is QUEUE_ROOT/benchmark-gate.json, separate from the production seam gate. The gate kind is fork-experimental-benchmark-admission, scope pilot-policy, status PASS, acceptedByCoordinator:true, productionAccepted:false, independentValidationRequired:true. It contains exactly three unique queueBindings (jobId plus source/config/code SHA256) and caseBindings validated by the measurement harness. Both actual roadsV2 and streamingEquivalence proof files must hash correctly and report PASS. A fourth job cannot enter through this gate.

The benchmark wrapper invokes the frozen measurement harness using {report_dir}; its immutable config points to the predetermined attempt output/checkpoint. Missing final JSON configuration holds dispatch without spending an attempt. These three experiments use maxAttempts:1 because each harness config binds an exact attempt path. A corrected generator requires a new version, spec and coordinator gate, preserving failed attempts.

The owned attempt contains sibling output and benchmark-report directories. Successful benchmark output remains at its measured path. A completion marker hashes both directories; their hashes are rechecked during verification. Neither a successful exit nor crash recovery grants world acceptance: both record WRITTEN_UNACCEPTED and independentValidationRequired:true. This prevents renaming from invalidating the measurement receipt's outputRoot and avoids putting a self-referential receipt into the measured output.

Main's camera reservation is a coordinator-hold with held:true, memoryGiB:6, cpuThreads:2, memoryIncludedInFreeFloor:true. It consumes one operation slot until Main explicitly releases it. Its static 6 GiB is already included in the 8 GiB host free-memory floor and is not subtracted twice. For live external process reservations, admission subtracts only declared RAM minus the largest verified owner's current working set. The largest owner is a conservative group observation, avoiding shared-page double counting across owners; unknown identity or unreadable memory holds dispatch.

By explicit coordinator approval, maxConcurrentBenchmarks:2 in the accepted benchmark gate allows two 4 GiB benchmark trees plus the separate camera slot, if other reservations, RAM and CPU admission fit. The default remains one benchmark when that field is absent. Distinct processor affinity is a measurement-harness lease responsibility; do not run two cases concurrently on the same core.

For later measured scaling, concurrency-policy.json may declare maxActiveJobs with acceptedByCoordinator:true. Increases above the default two additionally require measuredMemoryBudget:true, cpuOrDiskBenefitVerified:true, and a hashed measurementEvidence file reporting PASS. This is an explicit coordinator policy, never automatic scaling. No increased generic policy has been installed for the three initial experiments.

A later frozen adapter may opt into workspaceRoot:"C:/FORKWork/queue" to shorten paths. The queue rejects other workspace roots or junction redirects. The initial three frozen adapters allow only the original FULL/queue tree, so their specs deliberately omit workspaceRoot.


Benchmark jobs may set benchmarkGateName to a local benchmark-gate*.json filename. This keeps earlier immutable config/gate bindings intact when a corrected case receives a separate approved gate. Parent approval remains mandatory. On 2026-09-13 at 17:42 SGT Main released the camera reservation; the live file now contains no camera hold while the 8 GiB host free-memory floor remains enforced.
Recovery adapters that require an absent output directory may opt into outputCreatedByChild: true. The queue still creates the exclusive attempt directory and leases it before launch; the child creates output. The flag is part of immutable job identity. Existing jobs retain the precreated output behavior.
