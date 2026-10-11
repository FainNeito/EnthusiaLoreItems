# Main-thread physical scan queue source telemetry — 2026-10-11

## Purpose

This is a behavior-preserving follow-up to PR #62, after issue #39 showed that
post-drain backlog and aggregate rejection metrics cannot explain saturation.

- OBS-01: Observe the true queued high-water mark on every accepted admission,
  including spikes that occur between scheduled drain ticks.
- OBS-02: Distinguish regular event/lifecycle scans from routine
  `periodic-loaded-chunk` PRESENT reconciliation for queue depth, rejections,
  processing count and cumulative synchronous execution cost.
- SAFE-01: No queue ordering, admission budget, coalescing criteria, duplicate
  identity handling, lifecycle processing, persistence, or fallback policy changes.
- PERF-01: Instrument only already-accepted queue requests using two
  `System.nanoTime()` reads each and bounded fixed-name counters. Do not create
  unbounded per-source tags or retain request history.
- SAFE-02: Counter updates run even if a scan throws, and the original exception
  remains visible to Paper. A coalesced periodic request is not counted as
  rejected. The high-water mark is monotonic through drain/close.

## What changes

`PaperPhysicalTrackingListener` delegates fixed-category metrics and one-shot
saturation logs to `PaperPhysicalScanTelemetry` to satisfy the 500-NLOC
class-size gate. It reports queue depth as requests arrive instead
of waiting until the end of the tick. Rejections now include event versus
routine attribution. Completed queue operations record synchronous elapsed
nanoseconds (which includes Paper-side scanning and submission, **not**
asynchronous database persistence). The already-separated
`tracking.persistence_nanos` metric remains unchanged.

`TrackingMetrics` aggregates stable counters and makes them visible in the
existing staff tracking GUI without exposing item identities, player UUIDs
or unbounded labels. All telemetry remains process-local; capture before and
after samples from one uptime, not across restarts.

Tests assert exact high-water at 128 queued scans, separated rejection
classes, drain counts, current depths and monotonic peak. CI, all runtime
acceptance, exact-head Codacy and Lizard are mandatory before merging.

## Next safety gate

Use real source-attributed queue depth, rejection and execution cost to
choose whether to implement priority/fairness without starving routine
reconciliation or dropping important lifecycle evidence. Never raise the
budget blindly; retain the current FIFO until timing and saturation evidence
justify a tested change. This change is **not** a measured TPS improvement.

## Validation and promotion

This pull request is rebased onto the exact merged source of #62
(`main` commit `993766164e7970963355e77e239718c085c126d9`).
The separate Sentinel startup check on #62 failed to download its
toolchain and is not a success signal. LoreItems-owned acceptance on
#62 was 25/25 green. PR #63 needs a fresh exact-head validation
of this independent telemetry change after retargeting to `main`.

When collecting live telemetry, compare snapshots from the same
server uptime with representative player counts. The timing counters
are cumulative nanoseconds; they are not per-tick MSPT or a benchmark.
Do not tune admission/fairness until load-source measurements explain
whether queue saturation or synchronous execution cost dominates.
