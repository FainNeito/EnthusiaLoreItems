# Per-item storage policy

## Requirements and acceptance

- STORE-01: When no override is configured, every lore-item definition SHALL inherit the global defaults for shulkers, bundles, and ender chests.
- STORE-02: When an explicit allow or deny override exists, every instance of that definition SHALL use that override for the specified destination independently of other destinations.
- STORE-03: When an insertion contains a prohibited tracked item, including nested items, the Paper adapter SHALL cancel insertion without deleting, rewriting, or moving the item.
- STORE-04: When storage becomes prohibited, removal of previously stored items SHALL remain allowed.
- STORE-05: When a configuration reload is invalid, the plugin SHALL retain the previous complete policy snapshot.
- STORE-06: While protection configuration is unavailable or nested inspection exceeds its bounded budget, the adapter SHALL reject ambiguous insertion without altering items.

Existing `shared-containers-allowed` remains the fallback for shulkers and bundles. Ender chests default to allowed. Policies are server-side configuration keyed by definition UUID; they do not depend on item names or editable lore. No database migration or template rewrite is required. Existing items in prohibited storage are not confiscated.

## SPEAR evidence

Spec: the requirements above extend the owner's explicitly requested scope. Historical fixed work-package routing documents are stale on current main; no open PR exists in either repository. This change uses a separate branch from authoritative upstream main a8416b57 and preserves ongoing local work.

Prove: inspected the prior listener's unconditional global guard and absent ender-chest handling. Added regression coverage without claiming a historical failing baseline. One initial shift-click fixture failed because its raw slot did not map to the intended MockBukkit inventory slot; corrected the fixture before verification.

Engine: immutable application policy; strict off-thread configuration parsing; live atomic policy injection; Paper-only bounded nested inventory enforcement. Existing public constructors remain available. Public LoreItems service API and database schema are unchanged.

Architecture/refine: Gradle Java 21/Paper API 1.21.11 `clean check :plugin:shadowJar` passed, followed by `check :plugin:shadowJar` after refinements. 529 JUnit cases: 522 passed, 7 pre-existing provider/platform skips, zero failures/errors. 52 repository Python tests passed in an isolated tooling environment. New-code Lizard gate passed without changing thresholds. SpotBugs is advisory in this project and printed existing adapter findings; a successful Gradle check does not establish zero static-analysis warnings. Manual diff review covered nested bypasses, insertion/removal distinction, hotbar/offhand, shift-click, drag, hopper, immutable snapshots, startup fencing, and unchanged schema/API.

Hosted Codacy on 8c23f893 reported 11 findings. Refined nested-check complexity, explicit budget decrement, named policy/key limits and reused test loader. Narrow PMD suppressions document thread-confined candidate/test maps and the compatibility-preserving legacy constructor; no analysis gate was weakened. Repeated Gradle check/shadowJar and unchanged Lizard gate passed after these refinements. Hosted final-head checks and Java/Bedrock live acceptance remain pending. No project-local EARS/state helper was found; this document tracks requirements and evidence. Configuration-only operator controls add no player GUI.

## TEST acceptance (pending)

Exercise click, shift-click, hotbar/offhand swap, drag, bundle insertion/extraction, nested shulker/bundle movement into ender chests, reload rejection, and removal after a policy change on Java and Bedrock clients. No deployment is authorized by this request.
