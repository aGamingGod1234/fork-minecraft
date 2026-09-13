# Streaming writer equivalence evidence

`tests/test_streaming_equivalence.py` is an independent command-line regression harness. It writes only tiny, explicitly synthetic worlds in new directories below the private full-Singapore `merged` root. It does not generate the accepted 256-metre district or claim an island-scale memory benchmark.

```powershell
python tools/singapore-full/merge/tests/test_streaming_equivalence.py `
  --repository <merge-worktree> `
  --candidate-module <merge-worktree>/tools/singapore-full/merge/overlay.py `
  --level-template <accepted-modern-world>/level.dat `
  --baseline-ref 04dacfb
```

The baseline is fetched using `git show` at the resolved frozen commit, including both `overlay.py` and `anvil.py`. It never imports a mutable `main` writer as its baseline. Candidate sources are snapshotted independently. Each implementation/case runs in a fresh Python process with a four-chunk maximum. Input source and modern template hashes are checked again after the suite.

The positive fixture crosses negative and positive chunk/region origins, includes building wall/glass/roof material, an explicit air window, water, vegetation and a terrain override. Reversed JSONL order must give the same output. A same-layer material conflict must be rejected without a completed `level.dat`; scratch or incomplete output is retained as evidence.

Checks compare decoded block-state semantic hashes independently of palette numbering, exact region-file bytes, level and external world-generation NBT/bytes, modern region layout, modern spawn, vanilla-only data packs, removed embedded player state and four exact wall/air/glass/roof sentinels. Every successful output is reopened twice. An existing-world runtime or visual acceptance is not implied.

The initial harness self-check passed against frozen `04dacfb` and the then-current pre-streaming writer. The retained receipt is in `merged/synthetic-streaming-equivalence-d89196fbbdde4fcb908e8207f1b26da1/equivalence-receipt.json`. Baseline Python traced peak was 33,580,292 bytes; Windows peak working set was 56,967,168 bytes. The pre-streaming candidate measured 33,580,297 and 57,036,800 bytes respectively. These establish that the measurement path works; they do not demonstrate a streaming improvement. The new streaming writer must be compared after it is frozen.

The subsequent comparison of frozen streaming writer `022fca96484b3b24e0d5f5526e5bd9a4a7e0e747` against `04dacfb` passed all six cases. Its candidate snapshot includes `run_spool.py`. The receipt is `merged/synthetic-eq-b07a5a025c30/equivalence-receipt.json`. Both writers produced decoded semantic SHA256 `3bfe65c1f22eb01a74465ee870d9f4f0443023f2c004f17df07f1eeedc174c8a`; all four region files and the modern level/settings files were byte-identical. Baseline traced/Windows peaks were 33,579,992/56,856,576 bytes; streaming peaks were 33,580,000/57,372,672 bytes. Fixed overhead dominates this tiny fixture, so these measurements do not demonstrate a memory reduction. No 256-metre generation ran.

That streaming comparison initially failed when the writer's staging UUID and region spool filename pushed a long synthetic path beyond the Windows path limit. The successful fixture uses short synthetic directory names. This workaround does not certify production queue paths; the writer's long-path handling remains a separate required fix.

`tracemalloc` starts immediately before the writer call and ends before semantic inspection. Windows peak working set is captured with `GetProcessMemoryInfo` immediately after that call, so it includes interpreter/import overhead. Each figure describes one isolated four-chunk fixture. Timings include tracing overhead and must not be used to predict production throughput. Any larger benchmark or actual district regeneration requires its separate worker-slot lease.
