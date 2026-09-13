# Immutable connected-world growth contract

`grow_contract.validate_plan(plan)` is read-only. It admits immutable source
worlds and their explicitly owned cores before a separate writer creates a new
world. It does not modify existing sources or promote a passing coordinate
check into a fidelity claim.

A schema-1 plan contains `sources`, each with `id`, `world_path`, `core_bounds`,
`writer_manifest_path`, `structural_gate_path`, and `coverage`. Core bounds are
`[x0,z0,x1,z1]`, half-open and aligned to 16 blocks, inside the writer bounds.
Cores must be disjoint and connected through shared edges of positive length.
Corner contact is insufficient. Detached worlds are rejected in this version.

Every writer uses `coordinateFrame` equal to `{crs:"EPSG:3414",x:"easting",
z:"60000-northing",blocksPerMeter:1}`, `dataVersion:4790`,
`minecraftTarget:"26.1.2"` and
`regionDirectory:"dimensions/minecraft/overworld/region"`. Sources contain
root `level.dat`, `data/minecraft/world_gen_settings.dat`, and MC26
`dimensions/minecraft/overworld/region/r.X.Z.mca` outputs. Root `region/`
is the wrong layout for this target and is rejected. Every output's size and SHA-256 is rechecked;
missing, changed, duplicate, unsafe or unlisted artifacts fail.

Structural acceptance supports the existing actual
`independent-joined-strip-structural-gate`: `status=PASS`, a matching
`writerManifestSha256`, `bounds` covering the core, enough `chunkCount`, and
empty `fileHashErrors`. A `synthetic:true` gate is rejected. Its writer binding
binds every rehashed output, without requiring a duplicate oracle.
The actual accepted 16:42 MC26 repair receipt instead supplies `regionIdentity`,
`priorGeometryOracleSha256`, `metadataProofSha256`, `comparedCells`, modern
`regionDirectory` and empty `errors`. This rebound proof is accepted when every
region identity matches the source bytes and all block/seam/heightmap mismatch
counts are zero. It does not need a fabricated `fileHashErrors` field.

A generic adapter may instead supply `schemaVersion:1`,
`kind:"actual-world-structural-validation"`, `status:"PASS"`, `synthetic:false`,
`writerManifestSha256`, matching `worldOutputs:[{path,bytes,sha256}]`, positive
integer `comparedBlocks` and zero integer `mismatches`. Any reported structural
errors or mismatch counts must also be empty or zero.

Roads and water are required independently. Each `coverage` entry has a status
of `included`, `pass` or `no_features`, plus `evidence_path` and
`evidence_sha256`. The typed evidence contains `component` (`roads` or `water`),
`status` (`PASS` or `NO_FEATURES`), `sourceSha256`, matching `coreBounds`, matching
`writerManifestSha256` and an integer `featureCount`. `no_features` requires
zero; included/pass requires a positive count. UNKNOWN and buildings_only fail.
Absence also requires `sourceCoverageComplete:true`, `blockedDiagnostics:0`
and `unmappedSourceCount:0`; zero emitted runs alone never proves no features.
An evidence wrapper can bind an existing passing actual report using
`reportPath` and `reportSha256`; its bytes are rechecked. This is an adapter
around evidence, not permission to relabel a failed or synthetic report.
The existing `fork.roads-runs.v1` source manifest needs no invented PASS field:
its wrapper binds `runsPath`/`runsSha256` to its `outputSha256`, matches its source
hash and requires zero blocked diagnostics. This source can contain roads,
water and landcover; wrapper feature counts must be scoped to the component
and exact owned core by the evidence producer.
The emitter's `runSha256` is a semantic digest, not the raw JSONL file hash.

The normalized result has `sources`, `extent`, `expected_chunks`, `data_version`
and `coordinate_frame`. Each normalized source includes the exact writer and
gate SHA-256, reverified `outputs`, and coverage evidence hashes for later
unchanged-input checks. `iter_owned_chunks(sources)` yields exact `(cx,cz)`
keys without building an island-sized set. The bounding extent may include
holes and must never be presented as the covered chunk set.

## Measured 1024-metre benchmark sources

The actual `independent-benchmark-result-gate` is accepted under its original
type only alongside its passing `measured-independent-benchmark-validation`
summary. A source may provide `benchmark_summary_path`,
`benchmark_receipt_path` and `benchmark_validator_path`. The summary defaults
to `summary.json` beside the gate; the receipt defaults to the summary's
`receiptPath`. The validator defaults to the sibling `validate-benchmark.mjs`.
Alternatively, `structural_gate_path` may name the measured summary, with
`benchmark_gate_path` naming its gate (default `gate.json` beside the summary).

Admission requires exact owned-core bounds of 1024 by 1024 blocks, full-volume
comparison of 402,653,184 cells, zero mismatches, and passing coordinates,
metadata, heightmaps and file hashes. Summary, receipt, oracle, writer and
world-output hashes must agree. The existing read-only
`loadIndependentlyValidatedReceipt` calls `validateResultBindings`, rechecking
the full measured output inventory, frozen job/core/halo identity, every input
run and the exact oracle/writer file maps. It does not repeat the volume scan.
Both validator module hashes are pinned to the measured summary before Node
imports them. Node runs sequentially with a 384-MiB JavaScript heap limit.

The normalized source retains summary, receipt and gate paths/hashes plus
`benchmark_validator_code` records. The experimental gate does not waive the
separate roads/water coverage requirements or assert nationwide fidelity.

## Multi-source water evidence

The optional `fork-multi-source-component-evidence` type combines water from
exactly `coast-water` and `inland-water`; existing single-source rural evidence
is unchanged. Its `sourceSetPath` points to a `fork-component-source-set`
descriptor whose raw file hash is the aggregate `sourceSha256`. Both objects
bind the same component, exact core and writer manifest. Their contributor
sets must agree exactly, with no duplicate or missing IDs.

Every contributor carries `evidencePath`, `evidenceSha256`, `sourceSha256`,
`runsSha256`, `status` and `featureCount`. Its source-set record pins those
hashes plus `sourceReportSha256`. The consumer recursively validates each
existing child wrapper, rehashes its raw source report and run file, and
requires the run basename, size and hash in the actual writer's input map.
The coast child must bind a real `fork.coast-surface.v1` report, an actual
passing water oracle, and its `fork.masked-runs.v1` country/foreign mask chain.
The inland child must bind `fork.roads-runs.v1`. An aggregate PASS cannot
replace either child proof. Nested aggregates and cyclic evidence are rejected.

`emittedFeatureIds` must be the exact sorted union of each child's IDs prefixed
with that child's source hash and `:`. Both aggregate counts must equal that
union's size. Zero features requires both children's complete absence proofs.
Normalized output retains the immutable source-set and every child's evidence,
run and source-report path/hash for final unchanged-input verification.

## Geometry-only assembly components

An East gate may have `role:"assembly-component"`,
`standaloneStatus:"NOT_STANDALONE"`, `componentSpawnAccepted:false` and
`finalAssembledSafeSpawnRequired:true`. This does not turn an unsafe source
spawn into a standalone PASS. The consumer pins and invokes the gate's actual
`validate-east-world-gate.mjs` loader, which rederives the exact source, job,
run, world, metadata and full-volume proof. Its only permitted failed-oracle
exception is the preserved unsupported/obstructed component-spawn error.

Any plan containing components must explicitly name a different accepted
source in `spawn_source_id`. The selected source's actual NBT floor, feet and
head are checked before copying. A component can never provide final spawn or
configuration, even when its local spawn happens to be safe. The final receipt
rechecks source roles, verifies final configs match the selected source (only
the display name may differ), and checks the final world's actual spawn.
Runtime acceptance remains a separate required gate. Normalized descriptors
retain the NOT_STANDALONE role, spawn obligation and pinned loader provenance.

The CBD core `[29696,29696,30720,30720]` can be admitted alone (4096 chunks)
when its own gates pass. Twelve 256-metre east cores may extend coverage to
`[30720,29696,31488,30720]`, adding 3072 chunks, only after every source's roads
and water evidence passes. Their existence or completed building generation
alone does not authorize inclusion.
