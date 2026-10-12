# Live inventory references: SPEAR evidence

Scope: owner-requested maintenance after the production capture ending 21:42:27 EDT.
Base: canonical main ec7b77cb. Existing user work is preserved in its original checkout.
The six completed program packages are not reopened or redefined by this maintenance.

## Spec

- PERF-01: When capturing or resolving an inventory reference, the Paper adapter SHALL use non-snapshot holder/block-state access rather than serialize the container contents.
- SAFE-01: Reference resolution SHALL read the current loaded inventory on each call and SHALL never load an unloaded chunk.
- SAFE-02: Player, ender chest, entity and block location identities SHALL retain their existing semantics.
- SAFE-03: Nested item scans, duplicate/malformed identity evidence, mutations, database writes and scan limits SHALL remain unchanged.

The latest 60.287-second capture sampled LoreItems at 70.11%, block-state
snapshots at 66.26% and serialization at 64.8% of server-thread time. These
inclusive categories overlap. Capture SHA-256:
2016bdb903f6a4e72107864e77a1d1a185276110f97e4494f98a49a5833695cf.

## Prove / engine / architecture / refine

Two new regressions failed on the base snapshot overloads (2 tests, 2 failures).
After the fix, both pass, as does the existing entity identity snapshot test.
Clean Java 21 Gradle check plus shadowJar passed: 552 tests, zero failures/errors,
17 platform skips. The repository Python tooling passed all 56 tests.

On a disposable Paper 26.2-128 / Java 25.0.3 runtime, the exact candidate plugin
loaded and activated durable storage. A fixture of ordinary shulkers containing
27 diamond stacks measured these reference-lookup timings (100 samples):

| Container | Old snapshot p50 / p95 ms | Candidate p50 / p95 ms |
| --- | --- | --- |
| Chest | 3.5468 / 8.1455 | 0.0951 / 0.2005 |
| Hopper | 0.7110 / 1.1179 | 0.0424 / 0.1056 |

The baseline explicitly performs the original two holder snapshot lookups and
block-state resolution. The candidate invokes actual capture/resolve reflectively
in the target plugin loader. The probe confirmed later slot changes are visible.
This is an operation benchmark, not an A/B TPS result or production speedup claim.
Existing real-runtime shulker discovery passed 20 windows, 600 continuation passes,
729 identity observations per window (p50 8.2215 ms, p95 19.6039 ms, max 69.8018 ms).
The disposable runtime was stopped. Bloom TEST and production were not changed.

Paper's supported getHolder(false) / getState(false) overloads disable block-entity
snapshots: [Inventory holder API](https://jd.papermc.io/paper/1.21.11/org/bukkit/inventory/Inventory.html#getHolder(boolean))
and [Block state API](https://jd.papermc.io/paper/1.21.11/org/bukkit/block/Block.html#getState(boolean)).

## TEST deployment boundary

The installed Bloom TEST JAR has SHA-256
e9109b4c0d750d9c2c0cc5be26b23cfe651ddd4d2cfebb734185b2f3af690e9d
and reports version 1.0.1. It contains PaperHolderAnnouncer,
PaperSignaturePreservation, PaperStorageLock, PaperCreativeStorageLockListener
and PaperContainerInsertion classes absent from canonical main. Its version
and manifest do not identify its source commit. Replacing it with this canonical
candidate would remove TEST-only functionality. Exact development source must
be identified and reconciled before a Bloom TEST replacement; no feature loss
or guessed class patch is accepted. One player was online during inspection.

## Integration / remaining evidence

Implementation stays in the Paper adapter. No contents are cached across ticks;
no identity discovery or persistence policy changes are authorized here.
Project-local EARS/state executables were not found; this file is a manual
requirement/task/evidence record, not a validator-pass claim.

Delivery: reviewable unmerged test artifact/PR, isolated TEST verification.
Production remains read-only. No merge or production activation authorized.

Hosted exact-head review/CI and the TEST development-source reconciliation remain
pending. Additional cross-event scan coalescing/shared discovery are deliberately
not introduced until this dominant snapshot cost can be measured separately;
they carry evidence-ordering and invalidation risk. This change eliminates no
required nested identity inspection and is not a complete wall-clock scan budget.
