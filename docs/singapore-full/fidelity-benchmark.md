# Singapore benchmark harness

The harness is verified with tiny synthetic processes on Desktop and actual 1024 m experimental runs. Rural Lim Chu Kang and Changi coast passed independent full-core structural validation; both CBD attempts failed and are excluded from successful throughput. [Measured results](fidelity-benchmark-1024-measured.md) retain exact receipt and gate provenance. No national or land ETA is available. Existing 128 m smoke tiles do not establish representative whole-Singapore throughput.

Run the focused fixture check:

    node tools/singapore-full/validate-benchmark.test.mjs

Run approved cases only after the coordinator grants a resource slot and the selected admission gate passes:

    node tools/singapore-full/validate-benchmark.mjs run private-config.json new-private-report-directory

The configuration is private operational input, not a request for approval. The coordinator supplies its approval and evidence before execution. The CLI accepts only real mode; the test invokes the measurement backend directly with tiny synthetic processes. Both paths are Windows-only. No broad process search or unrelated process termination occurs.

Configuration shape (all hashes, paths, dates and lease values must be supplied from actual evidence):

    {
      "schemaVersion": 1,
      "mode": "real",
      "seamEvidence": "current-seam-summary.json",
      "seamEvidenceSha256": "<SHA256 of actual seam summary>",
      "resourceLease": {
        "id": "<coordinator-issued lease ID>",
        "heavyJobSlot": "<one of the coordinator's two total heavy slots>",
        "approvedBy": "<authorizing coordinator>",
        "machine": "Desktop",
        "startsUtc": "<UTC timestamp>",
        "expiresUtc": "<UTC timestamp>",
        "reservedCores": 2,
        "reservedMemoryBytes": 6442450944,
        "maxCores": 1,
        "processorOffset": 2,
        "maxMemoryBytes": 1073741824
      },
      "cases": [{
        "id": "dense-measured-tile",
        "class": "dense",
        "approvedCommand": true,
        "argv": ["<absolute executable path>", "<approved arguments>"],
        "executableSha256": "<executable SHA256>",
        "generatorArtifacts": [
          {"path": "<generator script or binary>", "sha256": "<artifact SHA256>"}
        ],
        "cwd": ".",
        "sourcePaths": ["<source input path>"],
        "sourceInventorySha256": "<source inventory hash including content hashes>",
        "outputDir": "<new isolated world directory>",
        "coreAreaM2": 16384,
        "timeoutSeconds": 120,
        "settings": {"coreSize": 128, "halo": 32},
        "settingsSha256": "<SHA256 of canonical settings JSON>",
        "representative": false
      }]
    }

Paths other than argv[0] resolve relative to the configuration file; command arguments remain literal and the generator resolves them using cwd. This is an argv API without shell interpolation. Supply the actual Node script, JAR, native executable or other generator artifacts and their hashes, not just the interpreter. Settings must describe the approved generation settings; the wrapper does not infer settings from arbitrary command arguments. Canonical JSON recursively sorts object keys, preserves array order, and contains no whitespace.

The actual seam summary must contain status PASS, comparedCells greater than zero and mismatchedCells equal to zero. Its hash binds the report used. The seam summary must also contain benchmarkBindings: an array of objects with sourceInventorySha256, settingsSha256 and generatorArtifactsSha256 matching each case. Generator artifact digest is SHA256 of canonical generatorArtifacts JSON. Source inventory digest is returned by the exported inventory(sourcePaths, true) function: sorted recursive files with absolute real paths, lengths and streamed content hashes. The source content is checked before execution with a 1 MiB read buffer. The coordinator must create these bindings only when the measured seam applies to these inputs. The harness does not independently perform the seam comparison or issue authorization.

The lease must be active and long enough for each case's full timeout. Cases run sequentially, with memory headroom and lease expiration checked before each case. A private Job Object limits committed memory and CPU affinity. processorOffset defaults to2; the admitted range starts at that logical processor and spans maxCores. A nondefault offset must match the case binding in the coordinator gate. Process reports record processorOffset, logicalProcessorCount and the actual configured affinityMask. Logical processors0 and1 remain outside the admitted range and requiring at least 6 GiB free memory beyond the lease's budget before starting. This is admission headroom, not a system-wide memory reservation: other applications can subsequently consume memory. The coordinator owns scheduling across all leads and enforces the currently approved resource and concurrency limits. The coordinator explicitly allocates generation slots and any simultaneous camera work; historical source-export or camera reservations must not be treated as current permission. A lease slot is recorded evidence, not a distributed lock. Single processor-group hosts up to 64 logical processors are supported.

For each dense, rural or coast case, files include command stdout/stderr, the measurement request and process report, and a receipt. The report directory must be new; each output directory must be absent or empty. The harness leaves output creation to the adapter. Reports and generated output must be disjoint trees, preferably sibling benchmark-report and output directories under the same owned queue attempt. Never copy the measurement receipt into the measured output afterward, because that changes its bound inventory. A zero exit with no output files is recorded as FAIL. A failed or timed-out case is recorded, and subsequent cases stop. Commands must keep output within their declared isolated directory; this wrapper is process containment, not a filesystem sandbox.

Measurements:

- Wall time starts immediately before resuming the generator and includes descendants until the job becomes empty. It excludes helper compilation, source inventory and output inventory.
- User and kernel CPU time come from Windows Job Object accounting and include exited descendants.
- peakSampledRssBytes is the largest observed sum of owned process working sets. Sampling defaults to 50 ms and reports sample count and missed process samples. This is a non-atomic estimate: short peaks can be missed, and shared pages can be counted more than once.
- peakJobCommitBytes is the Windows aggregate peak committed memory charge. It is distinct from resident memory.
- sourceLogicalBytes and outputLogicalBytes sum regular-file lengths, deduplicating overlapping source paths. These are logical disk bytes, not allocated filesystem blocks, compression savings or final assembled-world bytes. Symlinks/junctions are rejected. Source and output inventories bind streamed file content hashes. Source provenance remains the pipeline's responsibility. Inventory hashing is outside the measured generator process interval.
- Receipts retain core area, settings, canonical settings hash, executable and generator artifact hashes, exact argv and its hash, exit code, timeout state and authorization evidence.

The backend creates the generator suspended, assigns it to a private Job Object, and resumes it. Ordinary child processes inherit job membership. A timeout or disappearance of the invoking Node process terminates only this job; kill-on-close provides a helper-exit fallback. A failed assignment cleans up the owned suspended root. External launch mechanisms such as WMI, services or already-running workers are outside this model and must not be used as benchmark commands. Child nonzero exit codes are not all exposed by Job accounting: the generator must propagate failures to its root exit code.

Optional estimates require targetCoreAreaM2ByClass with explicit dense, rural and coast core areas. Every nonzero target class requires successful real receipts marked representative true and a representativeEvidence selection rationale. All selected measurements must share settings, executable and generator artifact hashes. Estimates weight measured wall/CPU/output bytes by core area within each class. They assume serial work, exclude halo area and padded region chunks, and do not estimate download, assembly or validation. They are measured extrapolations, not completion dates or promises. Synthetic fixtures, smoke tiles, failed runs, absent classes and mixed generator/settings hashes cannot establish a whole-island estimate. Representativeness remains a review decision; the flag alone does not prove it.

The focused test covers a parent exiting before its child, CPU spent by the exited child, RSS/commit observations, source-size deduplication, nonzero exit status, timeout removal of a detached descendant, failed executable launch, failed-seam refusal, and rejection of unusable estimate inputs. Its machine-readable evidence is fidelity-benchmark-test.json. All numbers there are synthetic fixture measurements and must not enter project estimates.

Windows API references: [Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects), [aggregate CPU accounting](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_basic_accounting_information), [memory limits and peaks](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_extended_limit_information).


## Scoped experimental admission

The configuration schema is tools/singapore-full/validate-benchmark.schema.json. For the three experimental benchmarks, replace seamEvidence/seamEvidenceSha256 with admissionEvidence/admissionEvidenceSha256 pointing to one immutable coordinator gate. Generation settings exclude admission metadata so gate and config hashes do not form a cycle. Each case additionally supplies its integer coreOrigin [x,z], coreSize 1024, halo 128, coreAreaM2 1048576 and jobSpecification {path,sha256} for its frozen adapter job.

The parent-owned admission gate has this shape:

    {
      "schemaVersion": 1,
      "kind": "fork-experimental-benchmark-admission",
      "scope": "pilot-policy",
      "status": "PASS",
      "acceptedByCoordinator": true,
      "approvedBy": "<coordinator>",
      "independentValidationRequired": true,
      "productionAccepted": false,
      "qualityProofs": {
        "roadsV2": {"path": "<actual focused proof>", "sha256": "<SHA256>"},
        "streamingEquivalence": {"path": "<actual semantic proof>", "sha256": "<SHA256>"}
      },
      "caseBindings": [{
        "id": "<allowlisted case ID>",
        "sourceInventorySha256": "<source paths, sizes and content digest>",
        "settingsSha256": "<generation settings digest>",
        "generatorArtifactsSha256": "<generator artifacts digest>",
        "commandSha256": "<inner adapter argv digest>",
        "jobSpecificationSha256": "<adapter job JSON digest>"
      }]
    }

Proof paths resolve relative to the gate file. Both proof contents must report status PASS and match their recorded hashes. At most three distinct case IDs are admitted. Queue runtime adds its own queueBindings to this same file before the parent freezes it; the harness checks caseBindings and Slot A, while the queue separately enforces its allowlist. The canonicalJson export computes the sorted JSON used for settings, artifact-list and inner argv hashes.

This gate attests that the generator/policy passed focused roads and streaming semantic checks. It does not claim that new benchmark sources already passed their own seams. Every successful experimental run reports WRITTEN_UNACCEPTED, independentValidationRequired true and productionAccepted false. CLI exit zero means the measured command completed; it does not mean world acceptance. Failed commands still stop the sequence and return nonzero.

The independent validator supplies a typed report:

    {
      "schemaVersion": 1,
      "kind": "independent-benchmark-result-gate",
      "status": "PASS",
      "benchmarkReceiptSha256": "<exact immutable measurement receipt hash>",
      "outputInventorySha256": "<receipt.output.inventorySha256>",
      "comparisonScope": "full-volume",
      "comparisonBounds": ["<core x0>", "<core z0>", "<core x1>", "<core z1>"],
      "comparedCells": 402653184,
      "mismatchedCells": 0,
      "checks": {"globalChunkCoordinates": true, "metadata": true, "heightmaps": true},
      "fileHashErrors": [],
      "evidence": {
        "writerManifest": {"path": "<actual writer manifest>", "sha256": "<SHA256>"},
        "sourceRuns": [{"path": "<actual source run>", "sha256": "<SHA256>"}],
        "oracleProof": {"path": "<actual full-volume oracle report>", "sha256": "<SHA256>"}
      }
    }

The comparison bounds are integer values matching the receipt's exact 1024 m owned core. The required count is (x1-x0)*(z1-z0)*384, or 402653184 cells for that core, covering Y=-64 through319. The legitimate 128 m halo is excluded. Evidence paths resolve relative to the gate.

loadIndependentlyValidatedReceipt(receiptPath, validationPath) verifies the gate type, receipt hash, unchanged live output content inventory, full-volume bounds/count, explicit checks, empty fileHashErrors, and all referenced evidence hashes. It parses the actual fastoracle proof and requires status PASS, identical bounds/counts, empty errors, zero heightmapMismatches/missingColumns/inputErrorCount/metadataErrorCount/sameLayerConflictingCells, and chunkCount/comparedCoreChunkCount equal to coreAreaM2/256. It returns an analysis-only accepted measurement without rewriting the receipt or granting production acceptance. No per-benchmark runtime relaunch is implied. Administrative scenarios exclude pilot measurements until that independent report passes.

administrativeScenarios(receipts, 1716) reports each measured sample repeated across all 1716 administrative-mask jobs as an explicit hypothetical scenario. Its minimum and maximum describe those scenarios; they are not verified island bounds. The 1716 jobs include water, and no land count, water-only count or skip policy is inferred. Coastal jobs can include land and water. Without a verified class inventory, landEstimate remains unavailable. One sample per class supplies no within-class confidence interval; serial wall time must not be divided by a CPU or slot count to invent a parallel ETA.

The shared synchronous validateResultBindings(receipt, validation, gateDirectory) helper in validate-benchmark-bindings.mjs also pins the frozen adapter job, derives its exact tile/world location, requires writer render bounds to equal core plus halo, and verifies canonical path/bytes/hash maps across actual world files, writer outputs, oracle regions/metadata and actual source runs. This rejects proof rebinding between outputs or identical source bytes at different paths. The standalone binding fixture suite exercises swapped regions, changed source/output content, omitted files and link escapes.

The scoped-admission tests reject absent coordinator approval, production-acceptance claims, more than three cases, tampered/failed proofs and validation reports bound to a different receipt. Scenario tests keep WRITTEN_UNACCEPTED measurements out of extrapolation and keep administrative job counts distinct from land coverage.
