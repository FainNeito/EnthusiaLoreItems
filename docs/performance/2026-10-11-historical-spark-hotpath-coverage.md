# Historical Spark hot-path coverage review — 2026-10-11

Requested comparison: https://spark.lucko.me/4YE9mLsB4A

## Source-quality / evidence limits

The supplied older Spark URL was attempted through the viewer and raw-profile
endpoints, but profile bytes and method trees were **not retrievable** in the
available execution environment. No specific method, percentage, or
relative performance number is inferred from this URL. Mapping below is
therefore a **current-source audit** informed by previously decoded, separate
October 9–10 Spark captures, not a claimed direct decoding of 4YE9mLsB4A.
Do not compare two different workloads or count overlapping inclusive stacks
as additive exclusive CPU time.

## Previously confirmed LoreItems costs vs current main

| Path | Current status | Evidence |
| --- | --- | --- |
| Template discovery rebuilding block/shulker state for every child | Reduced | PR #59: pass-local discovery snapshots, fresh continuation reads; isolated Paper 26.2 test recorded p50 63.98 to 12.18 ms on one synthetic workload |
| Identical rapid player full-scans | Coalesced per player/source/next tick | PR #59 coalescing with duplicate evidence regressions |
| Duplicate periodic PRESENT chunk requests | Coalesced while queued | PR #59, lifecycle scans remain distinct |
| Per-identity destination full inventory traversal on a shulker move | Reduced to one match scan per event | PR #62 and duplicate/unique nested regressions |
| Queue pressure invisible within a tick | Observability added | PR #63: backlog high water, source classes, rejection and scan nanoseconds |
| Shulker physical evidence precheck and positive traversal both calling `BlockStateMeta.getBlockState()` | Fixed by this PR | One block-state snapshot reused within a single synchronous collector/physical scan; no across-tick cache |

PR #59's synthetic pass timings are **not** SMP TPS measurements, and
PR #63 only instruments work. A pre-fix Spark report does not establish
the current server binary, active settings, or any live speedup.

## Remaining possible costs / safety considerations

1. `PaperTrackedItemCollector.hasIdentityEvidence` recursively inspects
   nested contents before positive collection. This PR eliminates the
   redundant *block-state decode*, **not** the potential second inspection
   of positive nested children or per-leaf identity metadata reads.
   A true one-pass identity collector has higher duplicate-coverage risk.
2. Physical queue budgets still count requests, not worst-case milliseconds,
   and synchronous lifecycle handlers can be expensive. No untested
   time-limit/cursor system or queue priority changes are introduced.
3. Some player/physical unique scans, template invalidations and chunk
   entity/tile traversals remain synchronous. Profiling under matched
   load is required to rank them.
4. Outside LoreItems, the earlier profiles showed villager/entity and chunk
   tick costs plus possible shared-host interference; a LoreItems-only change
   cannot fix those.
5. The installed SMP LoreItems filename/version is not a verified mapping
   to the tested current `main` commit. This work does not deploy to live SMP.

## This change and gates

At scan entry, request a single shulker block-state snapshot. Pass that
same snapshot to the existing evidence check and, only if positive,
to the existing nested collection/physical scanning routine. Bundle handling,
identity decoding, duplicate observations, depth 8, 256-budget semantics,
presence/mode, memory lifetime and persistence are untouched.

New tests enforce a single `getBlockState()` invocation in both positive
physical collection and direct physical scanning, as well as negative
shulker detection. Existing duplicate tracking tests must remain green.
Full Gradle check, Codacy, source complexity, exact-head Paper acceptance
and reproducible test JAR must pass before merge.

For a meaningful production conclusion, capture an exact installed JAR SHA
and configuration, TPS/MSPT p50/p95/p99 and matched-player Spark
before-and-after profiles. Collect bounded tracking queue high-water,
rejection, truncation and scan-time deltas within one server uptime.
Do not treat a passing test suite as production performance proof.
