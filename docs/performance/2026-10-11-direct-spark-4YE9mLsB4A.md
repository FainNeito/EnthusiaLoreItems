# Direct owner-supplied Spark profile audit — 2026-10-11

**Input:** `4YE9mLsB4A (1).sparkprofile` uploaded by the owner;
viewer https://spark.lucko.me/4YE9mLsB4A.
**Raw evidence:** full protobuf inspected locally; not an HTML-viewer estimate.

## Identity and scope

- Sampler: Paper `26.2-129-9240f58`, async/execution, 10 ms sampling, SIMPLE time aggregation. Spark saved one server-thread call tree with **281,380 nodes**, plus **60 one-minute windows**. Entire profiler ran **2026-10-11 04:01–18:31 UTC**; detailed window statistics available for **17:31–18:31 UTC**.
- The server reported LoreItems plugin descriptor **1.0.1**. The descriptor alone does **not** identify an installed JAR SHA or prove the presence of any merged optimization; latest repository main was not necessarily deployed.
- Last-hour windows: 24–30 players (typically 25–30), 8,269–16,878 loaded chunks; TPS median ~20.0 and minimum 19.802; *per-window* median MSPT 22.54–35.90 ms; largest recorded individual tick 724.60 ms. The profile is **not** threshold-filtered, so there is no specific sampled call-stack attribution for that worst tick.

## Top LoreItems inclusive sampled main-thread paths over final 60 minutes

| Stack path / method | Inclusive sample-equivalent wall time |
| --- | ---: |
| `PaperDeferredMainThreadActions.execute` | 82.75 s |
| `PaperTrackedItemCollector.collectItem` (across contexts) | 68.44 s |
| `PaperTrackedItemCollector.nestedMetaWithIdentityEvidence` | 65.32 s |
| `PaperPhysicalInventoryScanner.scanPlayerUnique` | 47.64 s |
| `PaperTemplateUpdateAccessController.drain` | 27.72 s |
| `PaperTrackedItemCollector.hasIdentityEvidence` | 26.72 s |
| `PaperTemplateUpdateScanner.processPass` | 21.65 s |
| `PaperPhysicalTrackingListener.scanReference` | 21.34 s |
| `PaperUniqueAccessTrackingListener.collectPlayer` | 20.53 s |

**Do not add values in this table**: parent frames include descendants; repeated appearances across contexts can also overlap. The 82.75s deferred task stack represents approximately 2.3% of the 3,598.58s sampled main-thread time in the final hour, and includes the nested work.

Other relevant inclusive paths: `ServerChunkCache.tickChunks` 1,018.23 s; `EntityTickList.forEach` 779.12 s; EnthusiaMarket `ContainerStockListener.refreshBatch` 28.48 s. These are not LoreItems costs and must not be attributed to it. The long-lived profiler covers a *different* player count and workload than October 9–10 13–14-player AI-on/off comparisons.

## Code mapping

- #59: removes template-discovery parent state duplication and coalesces player/source next-tick scans plus queued periodic loaded-chunk scans.
- #62: replaces per-identity destination full-inventory traversal with one bounded traversal per transfer.
- #63: adds fixed-category queue high-water, executed/rejected and synchronous timing evidence, **not** a scan-time limit.
- #64: reuses the positive shulker `BlockStateMeta.getBlockState()` across precheck and subsequent nested collection, matching a major observed method in this profile.
- This PR: `PaperItemIdentityCodec.hasIdentityEvidence` and `readIdentity` use Paper 1.21.1+ read-only `ItemStack.getPersistentDataContainer()` instead of constructing an `ItemMeta` snapshot on every identity check, and stop calling `PersistentDataContainer.getKeys()` and allocating stream predicates/sets for each hot-path item. Presence checks use `PersistentDataContainer.has(key)` without specifying a type, preserving partial and wrong-typed key detection, and read paths retain existing rejection behavior. This is a **micro-optimization with unmeasured magnitude**, not a full solution for recursive nested evidence scanning.

## Remaining concerns

1. Nested-identity **recursive child traversal** itself is still performed on positive shulkers in the evidence precheck and during physical collection. Removing a whole pass requires carefully preserving duplicate observation completeness, depth 8, 256-work budget, invalid PDC evidence, lifecycle and unique-vs-reconciliation modes; do not blindly skip the precheck.
2. Event scanner's bounded *count* does not enforce a strict per-tick main-thread time ceiling. Time-based continuation requires source-specific fairness, durable pending coverage and authorization/rollback tests before deployment.
3. Historical profile provides no proof of current main performance. Verify installed JAR's exact SHA, staged DB safety and release gates; capture a new matched workload with the optimized JAR to compare method trees and MSPT without changing AI load.

## Proof required before merge

MockBukkit tests for direct no-ItemMeta-snapshot reads, absent/foreign PDC keys, valid identity, partial identity, wrong-typed present keys (including retained recoverable ID), primary-thread guard and malformed/stacking evidence. Clean Java build, tooling tests, complexity/Lizard and exact-head Codacy, reproducible JAR and all runtime acceptance. No live server changes.
