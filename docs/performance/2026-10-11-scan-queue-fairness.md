# Source-safe physical scan queue fairness (2026-10-11)

## Why

Owner-provided 165MB Spark profile (details in `docs/performance/2026-10-11-direct-spark-4YE9mLsB4A.md`)
identified substantial main-thread LoreItems scan work. Issue #39 recorded 45
production physical-scan queue saturation warnings on an older deployed build.
The current queue has one FIFO of capacity `32 * currentBudget`; routine
reconciliation can fill every slot and cause player/chunk/entity lifecycle
observations to be rejected. New telemetry from #63 distinguishes event and
routine rejects, but does not resolve priority.

## Behavioral safety contract

- If the configured per-tick budget drops while requests are queued, admission
  stays closed until the preexisting backlog falls below the new limit; no
  evictions are performed solely to shrink the configuration-driven bound.
- Both classes share **the same unchanged total queue bound** and
  `currentBudget` per drain tick. No thread/model/persistence changes.
- Event/lifecycle requests keep FIFO **relative to other events** and
  cannot be evicted to admit routine reconciliation.
- When the queue is full of mixed work, a newly observed lifecycle/event
  request may replace only the **newest** queued routine PRESENT
  `periodic-loaded-chunk` request. The displaced periodic key is released
  and its rejection counted. A queue full of lifecycle/event requests still
  rejects new work rather than growing without bound.
- Routine reconciliation receives one polling turn after at most three
  event operations (and every turn when there are no events), so routine
  scans are not starved by *accepted* event traffic.
- Old coalescing invariants stand: routine PRESENT requests coalesce by
  world UUID and coordinates; lifecycle LAST_CONFIRMED and other sources
  remain distinct. Evicted keys may be re-offered on a future periodic sweep.
- No duplicate identity decision, authorization, queue persistence,
  rollback, forced chunk load, or tracking evidence mode changes.
- Main-thread cost is still bounded per tick by **number of requests**, not
  milliseconds. A single expensive scan can exceed a target tick.
  Implementing a strict elapsed-time continuation safely requires per-scan
  resumable cursors and source-specific proof; this change makes no
  misleading hard-time guarantee.

## Verification

JUnit unit tests assert exact periodic fairness (3:1), correct relative
lifecycle FIFO, newest-only routine eviction, all-routine draining and reset.
MockBukkit tests cover 128-slot full-mixed admission, routine key release,
correct rejected/accepted source metrics, 128-event overload safety,
routine progress with flooded events, and current prior coalescing paths.
All 25 exact-head GitHub actions plus clean Gradle verify, hosted Codacy,
changed-code complexity and reproducible test JAR must pass before merge.
Do not deploy to production without owner-authorized controlled rollout,
rollback and matched current-server profiling.
