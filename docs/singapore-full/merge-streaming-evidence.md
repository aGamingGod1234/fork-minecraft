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

`tracemalloc` starts immediately before the writer call and ends before semantic inspection. Windows peak working set is captured with `GetProcessMemoryInfo` immediately after that call, so it includes interpreter/import overhead. Each figure describes one isolated four-chunk fixture. Timings include tracing overhead and must not be used to predict production throughput. Any larger benchmark or actual district regeneration requires its separate worker-slot lease.
