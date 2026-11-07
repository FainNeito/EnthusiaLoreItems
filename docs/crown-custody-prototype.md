# Crown custody: isolated prototype

Status: source-only and inactive. The journal has disposable fixture persistence,
but no registered Bukkit service, production migration, or physical item
operations. This is not a provider capability advertisement.

## Requirements and acceptance boundaries

- The Crown must retain one physical instance identity across its initial award,
  PvP transfer and inactivity recovery; ambiguous outcomes stay held.
- A retry must match the original operation, instance, holder and generation.
  Changed values are conflicts. An in-memory comparison alone does not fence a
  worker or enforce uniqueness; a durable atomic admission is still required.
- Before finalizing initial equipment the provider must retain the sole
  recoverable original helmet in authoritative custody. After finalization it
  must prove no other retrievable original remains. Unknown outcomes may not
  reconstruct or destroy the original.
- The existing generic LoreItems definition remains capable of multiple
  instances. The user-requested Crown exception requires a separate durable
  singleton mechanism and inventory/queue reconciliation; it must not silently
  change generic delivery semantics.
- KOTH quest credits and event records are candidate evidence, not permission
  to mint or equip. Offline inventory, stale workers, queued items and restored
  data require explicit proof and reconciliation.

## Work and evidence

`PaperCrownDeathObservation` classifies the fatal damage source into a direct
player-kill candidate, non-player death, or review. It never transfers or
retains an item. A player must be the actual direct attacker or the shooter of
the direct projectile; other player-attributed indirect causes require review.
It is unregistered and does not establish anti-abuse or custody eligibility.

The domain reservation record is a pure value type and refuses changed identity
or generation on a retry. Its review state has no release or item-creation
transition. This does not implement a database lock, identify a live Crown,
fence workers, mutate equipment, survive restarts, or prevent delivery through
other routes. No provider contract is exposed to KOTH yet.

Focused Gradle 8.13 `:domain:test` passed both reservation tests using JDK 21.
The first offline attempt failed because JaCoCo was not cached; the normal
dependency-resolving run passed. These tests are not physical custody evidence.

The package-private `SQLiteExclusiveCustodyJournal` has a disposable-schema
rehearsal only. It persists a singleton reservation, rejects mismatched
operations, and can permanently place the exact operation in review. Two
simultaneous requests, reopening the database, and missing-schema refusal pass
in the focused SQLite test. It has no production migration or lifecycle wiring,
does not prove that the instance exists or is unique elsewhere, and must not be
used as an item-mutation fence. It does not implement finalized custody,
authoritative production receipts, crash-safe player data, stale worker
cancellation, or helmet exchange.
The focused journal test also verifies that rolled-back reservations and holds
are not durable. The journal does not commit a caller's transaction: future
integration must await a confirmed commit before acting and hold on uncertainty.
The experimental journal now has an asynchronous wrapper that returns its
reservation result after the SQLite transaction commits, and propagates
failures instead of reporting a reservation. This remains an inactive fixture
schema and is not a physical item fence or a recovery authorization.
The fixture also writes reservation and review audit rows in the same
transaction. Failed audit writes roll back the corresponding state change;
replays refuse missing or inconsistent audit evidence. These are synthetic
SQLite receipts, not proof of physical custody or a production migration.
The isolated startup recovery routine moves a previously reserved operation to
review with its audit row in one transaction, preserves the instance and holder,
and refuses malformed identities or missing audit evidence. An already held
operation remains held on repeated recovery. It must only run before admitting
any custody workers; it is not yet wired into the plugin lifecycle, and no
runtime fence presently enforces that prerequisite. It never delivers or
reconstructs an item.

The experimental journal can now record bounded serialized helmet evidence
against the exact reserved operation, Crown instance, holder and generation.
The insert and audit row commit together, mismatched replays hold, and restart
recovery rejects missing or checksum-inconsistent evidence. The byte payload is
not returned by the journal. `PaperCrownHelmetEvidence` separately captures the
online player's untracked helmet on the primary thread into defensively copied
bytes; it performs no inventory mutation and does not capture tracked items.
The SQLite fixture uses synthetic payloads, and the Paper tests use MockBukkit.
The components are connected through an unregistered preparation coordinator,
but are not registered in the plugin. A saved
snapshot alone is not sole physical custody and is never permission to recreate,
consume, deliver or equip an item. Empty head slots now have a distinct,
checksummed evidence state rather than being confused with an unavailable
player; mismatched empty/item retries and corrupt markers hold. Actual empty-slot
exchange, foreign plugin protections, durable player saves and stale callbacks
remain unproven.
The unregistered preparation coordinator now joins the Paper snapshot to the
SQLite evidence port, then schedules a fresh main-thread observation after the
write. A changed or unavailable head slot attempts a durable review hold; an
unavailable provider leaves the hold unconfirmed. Even an unchanged snapshot is
only evidence for review: matching bytes cannot distinguish identical helmets,
and neither result allows equip, consume, reconstruct, or recovery.
An isolated MockBukkit and SQLite integration test confirms that changing the
helmet after the evidence write leads to a persisted review hold. This does not
prove actual client behavior, offline inventory handling or crash-safe exchange.
The preparation now also uses a bounded, main-thread callback fence. The
unregistered listener invalidates pending evidence on inventory interaction,
item drop or damage, teleport, item breakage, hand swap, death or quit; an invalidated callback cannot become
ready merely because an identical helmet reappeared. A stale callback cannot
release a newer fence. If scheduling fails the fence stays closed. This is not
a slot lock: plugins, equipment dispensers, offline state, restarts and other
unobserved paths can bypass those events, so no mutation is authorized.
If a preparation fails or an observation changes, its in-memory fence now stays
closed while the review hold is pending and after an uncertain hold, preventing
an immediate retry from bypassing that uncertainty. This intentionally requires
future reconciliation; the bounded in-memory fence is neither a durable hold nor
restart safety and can exhaust its capacity if failures accumulate.
An overlapping preparation also invalidates the original callback before
requesting review, so its late database result cannot be accepted as a fresh
observation. A local MockBukkit test exercises that ordering; it is not a
distributed worker fence or a replacement for durable hold verification.
The fence additionally exposes a primary-thread invalidation for shutdown or
provider-generation change. A late callback then requests review and remains
closed. No plugin lifecycle calls this unregistered method yet; durable startup
recovery and invalidation before admitting workers remain required.
The broader offline Paper, SQLite and architecture suites passed after this
change: 318, 164 and 8 tests respectively, with nine Paper tests skipped.
After the additional conflict checks those suites passed with 322, 165 and 8
tests respectively, including nine skipped Paper tests. The focused Paper
preparation tests passed again after retaining fences on uncertain holds.
Orphaned or foreign operation evidence prevents starting or recovering another
reservation; changed input bytes, missing audit records and failed audit writes
do not authorize a retry. A checksum detects accidental corruption, not a
malicious database edit or possession of the original physical helmet.
The fixture journal confirms a repeated review hold only after rereading the
exact operation, instance, holder, generation and required audit/evidence in a
committed transaction. Mismatched requests and damaged evidence cannot confirm
that hold. The result does not establish possession of the physical items.
In the single-operation fixture, even a foreign reservation audit without helmet
evidence blocks capture and startup recovery. A future multi-operation production
journal needs an explicit historical reconciliation model instead of copying this
fixture's blanket rule.
Audit rows for the active operation with different identity fields or an
unrecognized transition also fail closed; they cannot serve as evidence for a
helmet snapshot or restart recovery.

`PaperCrownHelmetProbe` inspects an online player's head slot on the primary
thread and distinguishes an exact definition/instance identity from another
tracked item, an untracked item, malformed identity, an empty slot, or an
offline player. MockBukkit tests pass for these cases and the thread guard. It
does not mutate the item, prove uniqueness, inspect offline inventories, reserve
the slot, survive a disconnect, or make a destructive exchange safe. It is not
registered with the plugin or exposed as a service.

`PaperCrownHelmetPreflight` records an immutable player ID and fingerprint for
an ordinary head-slot item, then can compare a later main-thread observation.
Tracked or malformed LoreItems, offline players, wrong players and serialization
errors hold rather than being treated as safe. Identical-looking replacement
items may produce the same fingerprint; this check is not a slot lock, physical
identity, proof of original custody, or permission to consume a helmet. The
preflight is unregistered and makes no inventory changes.

`PaperCrownInventoryProbe` is an isolated main-thread observation of the online
player's own inventory, equipment and cursor. It distinguishes a matching
instance on the head from one elsewhere, multiple matching instances, malformed
identity and a wrong definition. It never modifies items. A successful observation
also requires a single item, and any other instance of the same Crown definition
in the observed inventory or cursor makes the observation invalid. This is still
only a local scan and does not establish absence elsewhere. A successful observation
is not proof of global uniqueness or physical custody: nested containers, other
players, offline data, unloaded locations and provider queues are not scanned.
It is unregistered and must not authorize exchange or recovery.

`SQLiteCrownIdentityAudit` performs a read-only, single-transaction screening
against the actual LoreItems schema for the configured definition and expected
instance. It reports extra active instances, unresolved anomalies, unfinished
direct deliveries, external delivery history, active or incomplete mass
distribution, outstanding mutations and uncertain saved location. Any external
delivery history is conservatively flagged, including retryable old requests.
Active or paused destructive operations and unresolved or ambiguous destructive
targets also require review, including after their parent operation is aborted;
a terminal target with unknown effect is not treated as proof of no change.
The focused test uses the normal SQLite migrations and checks conflicting
records and storage shutdown. Even an otherwise clear result always contains
`LIVE_PHYSICAL_PROOF_REQUIRED`: saved rows and historical observations cannot
prove absence in offline inventories, restored data or provider queues. The
audit is not registered, linked to the exchange coordinator, or an authority
to mint, transfer or recover an item.

Next: design the storage schema and atomic operation journal against current
LoreItems tracking work, add restart and stale-worker tests, and implement a
native proof-producing exchange before exposing a capability. Keep the generic
delivery API unchanged and KOTH custody disabled until end-to-end evidence is
available. EARS/state helper tooling was not found during this pass.

## Local Paper recovery readback

The isolated swap and SQLite admission have now been exercised with a disposable
player across independent Paper 1.21.11 build 130 processes. A forced process
stop after a matching readback preserved staged custody. An altered journal
digest was rejected. The original escrow remained available after the fixture
digest was restored. The Paper adapter checks the actual item material and
saved escrow identity, and SQLite validates bytes, digest and audit evidence
within one read transaction. This is a snapshot comparison, not an authorization
to discard escrow. The normal plugin entry point and durable finalization are
still outside this fixture. No TEST artifact is authorized by this result.

## Equipped decision and interrupted cleanup (isolated worktree)

The SQLite adapter now retires the stored helmet payload and records EQUIPPED in a single transaction after checking the reservation, original bytes, digest and audit. A matching replay reads the receipt. A review hold or changed identity prevents finalization. Paper rechecks the worn Crown and the operation-specific escrow, replaces the original bytes with the digest marker and saves the player. The caller must supply exclusive player access and a freshly loaded save.

Paper 1.21.11 build 130 with a disposable player:

- phase9.log: rejected a deliberately wrong original, verified the original remained, committed the decision, then forcibly stopped the JVM before Paper cleanup.
- phase10.log: reloaded the still-staged helmet, read the receipt, rejected a wrong digest, saved the cleanup, then forcibly stopped the second JVM.
- phase11.log: verified the same player had the Crown equipped, the original escrow was gone and the receipt marker matched. Replaying cleanup made no new equipment.
- Final read-only SQLite inspection found exactly one EQUIPPED, one HELMET_EVIDENCE and one RESERVED audit entry, a zero-length payload and a 64-character digest for the original nonempty helmet. A trigger-injected audit failure separately verified rollback preserves the original.
- Both adapter test suites passed. This evidence is from a probe entry point and experimental tables. Normal LoreItems schema migration, startup/join ownership coordination and the KOTH award entry point remain to be wired and exercised. No shared TEST/production or deployment.

## Normal LoreItems startup and player-join recovery

Unmerged isolated worktree based on upstream 86999c03. The normal LoreItems SQLite migrator creates and checks three version-12 custody tables. An unversioned name collision rolls back V12. Historical experimental rows are not imported. Startup durably places interrupted reservations in review. Only a complete equipped receipt joined to an active registered instance can drive player-join cleanup. Paper checks session identity, actual head item and escrow again before cleanup.

Verified with Paper 1.21.11 build 130, the normal plugin entry point and a disposable account. A separate setup probe created the starting player save and was removed before each normal-plugin check. Fixture custody rows and instance registration were seeded offline; this does not exercise KOTH awarding.

| Normal startup | Saved player and journal result |
| --- | --- |
| missing-receipt.log | Staged original retained. Missing receipt surfaced for review. |
| committed.log | Original removed from escrow and replaced with its matching digest. Same netherite Crown equipped. |
| replay.log | Rejoining after another restart returned the same digest and equipped Crown. |
| interrupted.log | Uncommitted reservation moved to REVIEW_REQUIRED with one review audit. All 85 original bytes retained in escrow and journal. |

Saved digest 95d2a02e1f2f780c09e3812d924b50ce9abeab20e79d47c45112637a2d14ff84. Local plugin SHA-256 7fa3ffdcc600e744c6619f70fbd06db4cbad97801048675a9c9758e471b164f6. Full Gradle test run: 599 passed, 9 skipped, 0 failed. No project-local EARS/state helper was found. Entity initialization failed before coordination could be posted. No shared TEST or client was used.

Remaining for end-to-end TEST readiness: fence other equipment writers during a new exchange, authorize the initial solo quest/event award against the unique provider identity, and verify from that normal KOTH entry point without seeded custody rows.

## Initial exchange equipment protection

Local LoreItems worktree based on upstream 86999c03. The Paper staging entry point opens a holder-specific listener before database admission, closes it after the slot move or abort, and times out after 100 server ticks. It checks the source Crown identity before admission and compares the full source item and the original helmet again before swapping. A late accepted operation receives a review request. Direct asynchronous inventory writes by arbitrary plugins are outside Paper event protection.

Real loopback Paper 1.21.11 build 130: the disposable player sent a swap-hands packet while admission was pending. Paper cancelled it. SQLite admitted the exchange and Paper staged the Crown, then accepted the second swap-hands packet. Saved player data contained the Crown on the head and the original diamond helmet in escrow. SQLite still retained the original. The first client run timed out safely before sending an effective packet; after implementing the protocol teleport/load acknowledgments, phase2.log contains PACKET_BLOCKED, PROBE_STAGED and PACKET_RELEASED. This used a probe to initiate the swap, not a KOTH win.

Gradle test and shadowJar: 601 passed, 9 skipped, zero failures. Targeted tests include another player, late reservation, and a Crown with changed metadata and matching identity. Source and tests are local/unmerged. Entity initialize was unavailable; no shared TEST, client, or production operations.

KOTH upstream 7f43049 and the isolated quest worktree have no supported custody result in the final preflight. Verified solo event/quest eligibility, provider identity and conflicting writer checks must be connected to an idempotent first award and exercised without a preplaced fixture Crown before end-to-end TEST readiness.
Environmental death retention: With keep-inventory false, retain the holder's uniquely matched equipped Crown through Paper's death keep list. On local Paper 26.2, before the change the saved world contained the dropped Crown. After the change and a fresh restart, the same Crown was equipped with zero matching world drops. Evidence: C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105/environmental-death-175352-fixed/RESULTS.md. PvP transfer and inactive-holder recovery remain open.
PvP succession: Retain the original Crown in provider escrow through former-holder removal and a fresh player load proving absence. A single delivery reservation authorizes equipping the recorded killer, preserving their helmet in inventory. Confirm ownership and announce after a fresh player load proves the same tracked Crown equipped. Interrupted grants without saved destination proof require review. Local Paper result C:\Users\p_ric\Documents\Codex\crown-restart-local-20261105/recipient-182005/RESULTS.md.

Connected PvP saved-file confirmation: bounded NBT readback supports verified 1.21.11 and 26.2 save paths. Local Paper 26.2 transfers to the connected killer and commits generation 2 before announcing; restart retained that state without replay. Evidence C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105/live-path-fixed-200523/RESULTS.md. 616 tests, 9 skipped, zero failures/errors. Interrupted recipient grant recovery and inactivity/event integration remain pending. Entity unavailable; local unmerged work only.

Interrupted recipient delivery: incoming recovery requires saved identity and unique live head confirmation. Missing destination proof reports a pending grant for review and prevents reissue. Local Paper crash, missing/duplicate save faults, successful recovery and restart replay verified in C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105/recovery-matrix-202702/RESULTS.md. Audit-insert failure rolls back successor custody. Source remains unmerged. Inactive-holder custody release and KOTH event integration remain pending.

Staff inspection is available as /loreitems crown using the existing audit permission. It reports pending delivery identities without mutations and rejects force arguments. Permission loss during the read suppresses output. Normal Paper command verification and unchanged custody readback are recorded in C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105/recovery-matrix-202702/RESULTS.md. Inactivity recovery and special-event release remain unimplemented through the native provider.

Reclamation reservations now persist exact operation/source/instance/holder/generation through schema V16 and CrownReclamationV1. They reject stale custody and competing PvP requests. The API reserves review only; physical reclamation is pending. Local registered-service and restart evidence: C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105/recovery-matrix-202702/RESULTS.md. Source is unmerged; Entity remains unavailable.

Physical online reclamation: V17 holds the exact Crown before saved removal, then confirms HELD from matching saved/live absence. Normal join recovery resolves a crash between save and confirmation. Exact local Paper evidence and limitations: C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105/reclamation-physical-213328/RESULTS.md. Offline removal and special-event release remain pending.

## Offline reclamation and fallback-save review (2026-11-05)

Requirement: WHEN an exact committed reclamation reservation names an offline holder, the opt-in worker SHALL stage the unique original Crown before changing saved inventory; SHALL preserve hashed original evidence; SHALL update and read back both loadable save copies; and SHALL keep reconnects blocked until custody confirmation succeeds. Unknown identity, changed preimage, missing proof, unsupported save layouts, duplicate Crown, and confirmation failure SHALL remain held. The option `crown-reclamation-offline-enabled` defaults false. Only the verified Paper 26.2 layout is supported for offline writes.

Implemented the bootstrap login exclusion, main-thread validation fence, bounded single-worker offline file exchange, source identity/generation checks, preimage comparison, forced temporary writes and atomic per-file replacement. The fallback is replaced before the primary. Archives use `.dat.evidence` outside player data and are never loadable saves. Shutdown waits for an in-progress file exchange to finish before returning. No item is reconstructed on ambiguous evidence.

Local Paper verification: offline-reclamation-214910 removed the exact Crown without logging the holder in, confirmed HELD, and admitted the subsequent reconnect. A separate restart replay also passed. In offline-failure-215201 an injected SQLite confirmation failure left STAGED and denied reconnect. The fixture JVM was then killed; after removing only the fault trigger, restart completed HELD and admitted the holder. Both save copies have zero Crown identities; escrow retains the same 257-byte payload and verified digest. These are disposable local fixtures, not TEST.

Source review found that online release must also check the fallback save. The new confirmation helper rotates an already verified absent primary through Paper saveData and checks the fallback before confirming online reclamation or PvP release. fallback-online-215807 proved online reclamation HELD with neither save containing the Crown. Full connected PvP acceptance with this final helper remains to rerun.

Build: 623 tests total, 9 skipped, zero failures/errors; shadowJar passed. Final local unmerged JAR SHA256 B81CCC11D4ABE32021A117051AA6BC1376DB119F8A5EA211E477F6039CD837A4. Offline/fault runtime used earlier local pin c717d4622ad71c1d8ef75ca88559a9f86df334f6fff1e5a7e72864a113490bbf; final-pin runtime covered the online fallback change. Evidence under C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105, including offline-save-readback.json and offline-custody-readback.json.

Still pending: protected automatic inactivity observations and eligibility, native recovery-event release/delivery with fencing and replay, recipient recovery acceptance, full source/CI review and TEST acceptance. Do not interpret HELD as an event delivery authorization. Entity initialize returned DriverError; independent local work only, with pending coordination recorded. No TEST/client control, upload, merge or deployment.

Follow-up final-artifact PvP regression passed: fallback-pvp-220142/fallback-pvp.log and its custody/saved readbacks show one ownership announcement, generation 2 EQUIPPED to HillRival, zero Crown identities in both former-holder saves, and one Crown on the recipient's head. This closes the specific connected-PvP fallback regression noted above, not the remaining full acceptance matrix.

## Automatic observations and recovery-event delivery (2026-11-05 continuation)

Spec: announced, completed, protected solo holder challenges SHALL be the source of inactivity opportunities and participation. Missing, conflicting or unapproved evidence SHALL hold. Grace SHALL continue until sufficient opportunities exist. A recovery final SHALL bind one exact HELD original and generation to one protected winner with pre-start binding consent. A normal restart SHALL resume an interrupted recipient grant without issuing a second original. Recovery recipients retain their existing helmet; initial sacrifice consent remains separate.

Engine/architecture: CrownRecoveryRuntime now collects protected observations on a dedicated worker and rechecks a frozen configuration before native actions. SqlCrownRecoveryEvents persists announcement, occurrence, match and winner binding; cancelled/no-winner events release only their unawarded binding. LoreItems CrownRecoveryEventsV1 and V18 persist the native single-recipient grant, reusing the existing saved-inventory exchange/recovery path. No mint fallback is used. New configurations remain disabled/unset.

Review: fixed nested connection borrowing, worker/main-thread separation, late-opportunity grace, old-generation local hold retirement, configuration-change fencing, and former-holder login fencing while an incoming recovery grant is pending. Self-review only; independent PR review remains outstanding.

Proof: KOTH 448 tests total, 2 skipped, zero failures/errors; LoreItems 623 total, 9 skipped, zero failures/errors. Both shadow JAR builds passed. Native Paper recovery delivered the existing generation-2 original back to its former holder as generation 3 while preserving their diamond helmet. An injected custody-commit failure left one pending recipient operation; after removing the fixture fault and restarting, normal join recovery committed that same operation and announced ownership once. This was fault injection plus normal restart, not a hard-kill proof. Automatic tenure observation was persisted by the running KOTH plugin. Full scheduled recovery rehearsal results are recorded separately in RECOVERY-EVENT-RESULTS.md.

Limits: the complete automatic inactivity-to-offline-removal-to-event cycle still needs a combined rehearsal, including cancelled/missed schedules, disabled/reloaded settings, shutdown races, recipient disconnect/full inventory and hard interruption points. Existing targeted checks are not full matrix closure. Current-main reconciliation, independent review/CI and coordinated TEST/client acceptance remain. No shared TEST/client or production operations occurred. Local Entity remains unavailable (DriverError); no task versions or ownership acknowledgments were invented. Project-local EARS/state helper unavailable; this is the bounded SPEAR requirement/implementation/evidence record.

Scheduled runtime result: protected solo recovery final delivered the same original, generation 2 -> 3, preserving the recipient helmet. Final-artifact restart kept the same custody/grant, retired its event binding and produced zero duplicate announcements. Full bounded evidence: C:/Users/p_ric/Documents/Codex/crown-restart-local-20261105/RECOVERY-EVENT-RESULTS.md. Combined automatic expiry/removal/event cycle and integrated failure matrix remain before TEST readiness.
