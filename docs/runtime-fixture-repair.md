# Runtime fixture repair (PR61)

## Expected behavior

- FIXTURE-01: When setup moves a tracked item, the acceptance helper SHALL remove the original, place an independent stack, synchronize the client inventory and expose nested roots through inventory events without suppressing duplicate detection.
- FIXTURE-02: When an empty display is summoned on Paper 1.21.11, the fixture SHALL provide its block attachment position; the real client SHALL still place the tracked item and exercise display tracking.
- FIXTURE-03: When any required runtime gate fails, merge SHALL wait for a passing exact-head result and review.

## Failure evidence

Head 14c86a07 passed main verify and Codacy but failed protection, tracking and revision rollout. Protection artifact 11692289643 recorded delivery then stalled at client drop; its food instance was fenced as a player/chest duplicate. Revision artifact 11692224992 showed a helper-moved shulker item fenced as player/nested duplicate. Tracking artifact 11693285085 logged `Block-attached entity at invalid position: null` before the client could locate its frame.

The helper's direct setup moves omitted a full client refresh and nested inventory events. Tracking CI already patched nested setup at build time, leaving other workflows with different helper behavior. Promote that setup synchronization to committed helper source, clone before removing the source stack, refresh clients after source/move setup, and preserve existing acceptance chests. Remove the build-time helper patch so all gates build the same committed source.

Frame fixtures omitted `block_pos`, the attachment field documented in [Minecraft 1.21.5 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-5). Add it to both tracking and rollout fixtures. Keep strict entity-kind discovery and all database, persistence, unload/reload and physical-placement assertions intact.

## Verification

Java 21/Paper API 1.21.11 `check :plugin:shadowJar :acceptance-harness:jar` passed after the changes. 531 JUnit cases: 524 passed, seven existing skips, no failures/errors. The two new helper regressions verify source removal, independent destination ownership and preservation of non-tracked inventory on missing source. All 52 repository tooling tests passed; unchanged Lizard gate, Node syntax check and `git diff --check` passed. Manual source review retained every acceptance assertion and confirmed helper moves do not weaken production duplication protection. SpotBugs remains advisory with existing warnings. Hosted final-head runtime gates remain pending at this checkpoint.

No project EARS/state helper exists; this record provides requirement/evidence traceability. These are disposable test-fixture changes, not a server deployment or player-facing GUI. Merge authorized only after checks and review pass; live server/client acceptance of storage options remains separate.
