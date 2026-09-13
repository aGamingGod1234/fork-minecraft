# Real 256 m writer comparison

On 13 September 2026 at 19:07 SGT, one shared-queue-admitted comparison ran the
frozen old writer and optimized writer sequentially on Desktop. Both consumed
the same 181,821 runs and pristine Minecraft template, producing 256 chunks
within `[29712,30496,29968,30752)`.

| Version | Commit | Writer elapsed | Peak process RSS |
| --- | --- | ---: | ---: |
| Old | `8a4205c38ae914225cceb09c9f8a61c36c909989` | 7.530418800 s | 56,520,704 bytes |
| Optimized | `efb9536aa1f0308199e2c91fd843fc805e9c286d` | 7.761188700 s | 56,381,440 bytes |

All four complete world files matched byte-for-byte across both versions and
the previously accepted original: `level.dat`, external world-generation
settings, and both region files. Input/template hashes, frozen code, and the
original world remained unchanged. Overwrite diagnostics also matched.

This single old-then-new sample ran alongside other admitted work. The measured
ratio was **0.970266×**: the optimized writer took about 3.1% longer. It proves
compatibility on this input; it does **not** demonstrate whole-world throughput
improvement or establish a performance regression. Earlier synthetic chunk
speedups must not be presented as real-world generation speedups. The active
renderer remains frozen; no rollout or further benchmark follows this result.

The proof used CPU 10, verified BelowNormal priority, one Windows Job Object
with a 1 GiB aggregate committed-memory cap, and continuous 8 GiB free-RAM /
100 GiB free-disk floors. Separate old/new processes supplied independent OS
peak RSS measurements. The entire guarded run took 16.047 seconds, below its
60-second cap. Supervisor, driver, and both workers exited with their recorded
creation identities verified dead; the guard closed before reservation release.

Private evidence is under
`fork-singapore-full/queue/jobs/p256-fast/attempt-1/`:

- `proof.json`: SHA-256 `8fe4b6ecbb679948bf658ef1ef74d6abd40199534f4e293ecf55553e3f589b05`.
- `completion.json`: SHA-256 `88edec2eaa2daac267aacfd49a6656a49cd36e47120b43e506b45b5f4d27bc06`.
- `release-evidence.json`: verified death of supervisor 43468, driver 22180,
  old writer 23828, and optimized writer 41508, bound to the two hashes above.
- `admission.json`, `job-lease.json`, frozen code, phase receipts, logs, and both
  immutable output worlds retain the full comparison evidence.

No Minecraft runtime or visual acceptance is claimed by this writer benchmark.
