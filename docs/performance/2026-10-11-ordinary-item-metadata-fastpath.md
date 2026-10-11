# Ordinary-item nested metadata fast path — 2026-10-11

## Historical evidence and current issue

The owner-uploaded 165 MB Spark profile (see
`docs/performance/2026-10-11-direct-spark-4YE9mLsB4A.md`)
showed substantial time in `PaperTrackedItemCollector` nested identity
evidence discovery. PR #64 avoided duplicate shulker `BlockState` decoding;
PR #65 gave identity reads an allocation-efficient direct PDC view.
Nevertheless the physical collector still cloned `ItemMeta` for every
ordinary item with metadata, just to check that the result was not
`BlockStateMeta`/`BundleMeta`.

## Source-level invariant

Only `ShulkerBox` and `BundleMeta` are traversed by the collector.
Paper 1.21.x represents these as `SHULKER_BOX`/colored `*_SHULKER_BOX`
and `BUNDLE`/colored `*_BUNDLE` materials. A cheap enum material
name check now rejects unsupported nested-container types **before**
cloning any metadata. This is used in both root and recursive evidence
checks, not in ordinary identity reads.

Root identity decoding always still happens first, irrespective of
material. Invalid, partial or wrong-typed PDC fields remain fail-closed.
The existing nested evidence probe, exact paths, all duplicate
observations, depth 8, scan limit 256 and synchronization semantics
remain unchanged for shulkers and bundles. No cross-event caching.

## Regression gates

- Ordinary tracked and foreign-metadata items require **zero**
  `getItemMeta()` calls during collection, while tracked identity is
  still submitted at its original location.
- Colored blue shulker and white bundle continue revealing nested
  identity at their exact canonical paths.
- Existing physical scan, malformed-data, duplicate-copy, movement,
  authorization, rollback and lifecycle suites must pass.
- All exact-head 25 hosted workflows including Codacy, Lizard,
  reproducible JAR and runtime acceptance must pass before merge.

The optimization is source- and behavior-level evidence, not a measured
SMP TPS improvement. True per-tick wall-clock scan caps and production
deployment still require separate staged runtime evidence; no change
here weakens the queue or rejection policy.
