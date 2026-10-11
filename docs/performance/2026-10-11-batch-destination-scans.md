# Batch destination identity scan (2026-10-11)

## Scope and invariant

A single inventory move can carry several unique lore identities (for example,
27 distinct tracked items inside a shulker). Before this change, both the
physical inventory event listener and block-placement/interact path called
`submitMatchingIdentity` separately for each identity, each time collecting the
**entire destination inventory and nested item tree**, then separately running
the existing reconciliation scan. This is repeated main-thread metadata work.

- PERF-01: For a move containing N distinct tracked instances, obtain one
  *bounded* destination inventory snapshot, not N whole-inventory scans.
- SAFE-01: Every occurrence of each requested identity must still be submitted.
  A unique instance may be authoritative only if the entire bounded scan
  completed and exactly one occurrence was found.
- SAFE-02: Duplicate copies, nested shulkers and bundles retain distinct
  locations and reconciliation evidence. Unrelated identities are not
  incorrectly submitted as authoritative transitions.
- SAFE-03: Each new event rescans the current inventory; no cross-event cache,
  mutation logic, persistence contract, queue admission, or budget changes.
- SAFE-04: Empty identity collections produce no scan work.

## Implementation

`PaperPhysicalInventoryScanner.submitMatchingIdentities` collects the same
existing bounded `PaperTrackedItemCollector` observations **once** into a
short-lived map, and dispatches existing per-instance evidence modes. The
one-identity method delegates to the batch method. Both event callers now
pass the whole identity set. The trailing existing `scanInventory`
reconciliation is intentionally preserved to avoid changing behavior.

Existing `PaperScanLimit(256)`, nested path typing and identity duplicate
fences are unchanged. This optimizes the work required per multi-identity
event, not the per-inventory bound or periodic background scanning.

## Evidence and follow-up

New MockBukkit tests verify a one-snapshot multi-instance move, duplicate
evidence and mode selection, nested/root simultaneous copies, unrelated and
absent identities, the empty fast path, and fresh reads across events.
The original single-identity regressions remain in place.

Hosted Java tests, Codacy, Lizard and acceptance suites are required before
merge. No production TPS or MSPT improvement is claimed without comparative
server profiling. Remaining queue fairness/source telemetry and possible
tick-duration limits are tracked in issue #39; this commit does not
silently alter lifecycle or prioritization behavior.
