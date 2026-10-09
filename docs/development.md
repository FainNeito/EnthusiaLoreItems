# Development status

## Build

The project targets Java 21 and uses a multi-module Gradle Kotlin DSL build.

```text
gradle clean check
gradle :plugin:shadowJar
```

The deployable jar is produced by `:plugin:shadowJar`.

## Module direction

```text
plugin -> api, adapters-paper, adapters-sqlite
adapters-paper -> api, application, domain
adapters-sqlite -> application, domain
api -> application
application -> domain
domain -> JDK only
```

`architecture-tests` imports the compiled modules only to verify dependency rules and cycles.

## Foundation configuration

`plugins/EnthusiaLoreItems/config.yml` is created from the packaged defaults by the off-thread lifecycle worker. It is parsed into an immutable `FoundationConfiguration` and validated before publication.

Reload builds a complete candidate snapshot and swaps it atomically only after validation. Database busy timeout, database queue capacity, and database shutdown timeout are startup resources; changing those values is rejected during reload and requires a restart. Reload does not recreate executors, close SQLite, or discard pending work.

All queue, claim, paging, warning, and per-tick settings have explicit safe bounds. Unknown or duplicate configuration keys fail validation instead of silently using a misspelled value.

## Foundation database

The production path is:

```text
plugins/EnthusiaLoreItems/loreitems.db
```

Migration `V1__foundation.sql` establishes the durable records required by the planning documents. SQLite connections enable foreign keys, WAL mode, `synchronous=NORMAL`, and a bounded busy timeout.

A single bounded database executor serializes storage work. Queue saturation rejects new work rather than allowing unbounded memory growth. Plugin shutdown stops acceptance, drains for the configured bounded timeout, and then forces executor shutdown if necessary.

Startup configuration parsing, directory creation, database opening, and migration run away from the server thread. The Bukkit service is registered immediately through a stable delegate but returns `SERVICE_UNAVAILABLE` until writable storage is active.

## Held-item definition creation

Administrators with `enthusia.loreitems.admin.create` can create the first durable definition revision with:

```text
/loreitems create <lookup-key> <display name>
```

The command is player-only because it snapshots the item in the player's main hand. Paper item access and serialization stay on the server thread. The snapshot is cloned, normalized to an amount and maximum stack size of one, stripped of any existing LoreItems instance identity, and converted to an immutable encoded template before asynchronous persistence begins. Air and malformed LoreItems identity evidence are rejected without changing the held item.

The definition, revision 1 template, and `definition_created` audit event commit in one SQLite unit of work. An existing active lookup key returns a conflict without appending an audit event. Storage failure, queue rejection, degraded mode, or shutdown leaves the command unavailable rather than claiming success.

This operation creates only the reusable definition and its first template revision. It does not adopt the held item, assign an instance UUID, replace the item in the player's hand, or deliver a physical item.

## Held-item adoption

Administrators with `enthusia.loreitems.admin.adopt` can adopt exactly one item from the selected hotbar slot into an existing active definition with:

```text
/loreitems adopt <lookup-key>
```

The command rejects air, stacks larger than one, already tracked items, and malformed LoreItems identity without changing the inventory. It snapshots a SHA-256 fingerprint and selected slot on the Paper thread, then persists a fresh instance identity, missing/unresolved current state, claimed `ADOPT_HELD_ITEM` mutation, and preparation audit before any inventory mutation.

After durable preparation, the command returns to the Paper thread and requires the same player, selected slot, untracked identity, amount, and fingerprint. It writes hidden definition, instance, and applied-revision identity to a clone, forces amount and maximum stack size to one, replaces only the exact slot, and immediately rereads that slot. Visible metadata and foreign persistent data remain unchanged by default.

A verified write completes the mutation through `APPLIED`, `VERIFIED`, and `COMPLETED` in one SQLite transaction, appends a confirmed player-inventory observation, advances current state, and writes an audit event. Slot changes, disconnects, shutdown scheduling failures, claim expiry, verification failure, or uncertain persistence are moved to `REVIEW_REQUIRED` rather than retried blindly. Expired mutation claims are recovered in a bounded startup batch and by a bounded non-overlapping periodic worker, so an overflow batch cannot remain claimed indefinitely.

Only one adoption per administrator is active at a time, and global in-flight adoption bookkeeping is hard-bounded. No live `Player`, inventory, or `ItemStack` reference crosses the asynchronous database boundary.

## Durable direct delivery

Administrators with `enthusia.loreitems.admin.give` can queue one fresh instance for themselves, an online or cached player name, or an explicit offline-player UUID:

```text
/loreitems give <lookup-key> [online/cached player name or UUID]
```

The command accepts no physical side effect before SQLite commits a fresh instance identity, queued-delivery observation/current-state projection, direct-delivery row, external idempotency result, and audit event in one transaction. Replaying the same external operation ID with the same arguments returns `ALREADY_ACCEPTED` and creates no additional instance. Synchronous queue rejection, null service results, and lifecycle races are reported as failures rather than escaping the command thread or claiming durable acceptance.

A bounded worker claims only the smaller of the configured delivery batch and per-tick mutation budget. Each claim carries the exact definition revision and immutable encoded template. Paper template decoding, hidden identity assignment, empty-slot selection, insertion, and immediate reread occur only on the server thread. The item is forced to amount and maximum stack size one, and completion is persisted only after the exact slot contains the expected definition, instance UUID, and revision.

Offline players and full inventories return safely to `PENDING` with a retry time. Nothing is dropped as overflow. Player join wakes matching pending deliveries, while a bounded low-frequency poll resumes queued work after restart and eventually retries full inventories. A crash or persistence failure after physical insertion is never retried blindly: claimed or partially applied work expires or is moved explicitly to `REVIEW_REQUIRED` for staff inspection. Synchronous defer, completion, or review-submission failures are fenced through the same durable recovery path.

Successful completion atomically advances `RESERVED -> APPLIED -> VERIFIED -> COMPLETED`, appends a confirmed player-inventory observation, replaces the queued current-state projection, clears the claim, and records the verified item fingerprint and inventory slot in audit history. No live `Player`, inventory, or mutable `ItemStack` reference crosses the asynchronous database boundary.

## Environmental, durability, conversion, and void protection

The Paper listener recognizes both valid tracked identity and malformed LoreItems identity evidence. It cancels natural item despawn, combustion, item-entity merging, ordinary item-entity damage such as fire, lava, explosions, and cactus, and durability damage to player-held or entity-held tracked items. Invalid identity evidence is preserved rather than repaired, split, or deleted. Untracked vanilla items are unaffected.

Identity-losing conversions are rejected before the platform can consume or replace the tracked stack. Covered paths include player consumption, crafting and automated crafters, cooking, furnace and brewing fuel, brewing contents, composting, block and entity placement, flower pots, bucket conversion, entity-bucket capture, consumptive block/entity interactions, projectile and arrow consumption, elytra firework use, bow ammunition consumption, and dispenser behavior. Inventory result extraction is denied when a tracked input or malformed identity is involved, covering smithing, grinding, anvil, and other result-slot conversions without mutating the evidence.

Void damage is the intentional terminal-loss exception. The event remains cancelled while the plugin persists a claimed `VOID_TERMINAL_LOSS` mutation and preparation audit off-thread. Known unresolved duplicate, malformed, conflicting-observation, or identity-mismatch anomalies block preparation. The Paper thread then reacquires the exact item entity by UUID, verifies the same hidden identity, and requires the entity to remain below the world's minimum height before removing it.

Verified removal completes one SQLite transaction that advances the mutation through `APPLIED`, `VERIFIED`, and `COMPLETED`, changes the instance lifecycle to `VOID_DESTROYED`, appends a `TERMINAL_VOID` observation, advances current state to `TERMINAL_VOID`, and records audit evidence. If the item was rescued before removal, the claimed mutation completes as an audited abort and the active instance remains unchanged. Missing entities, identity changes, scheduling failure, claim expiry, or uncertain post-removal persistence enter `REVIEW_REQUIRED` rather than causing a blind second removal or restoration.

Protection remains active during storage startup and degraded mode, but terminal void destruction is withheld unless durable storage has accepted the intent. In-flight void work and retry cooldown bookkeeping are bounded, no world or inventory scan occurs, no chunk is force-loaded, and no live Bukkit entity or item reference crosses the asynchronous storage boundary.

## Display entities and mob pickup prevention

Tracked items placed in ordinary or glow item frames and in armor-stand equipment slots are observed through Paper's event surface. Item-frame changes and breaks, armor-stand manipulation, and armor-stand damage schedule a next-tick reread of the exact entity UUID and slot. Glow item frames follow the same path because they implement the item-frame contract. No world scan or chunk force-load is used.

A confirmed item records the exact display entity location and slot as `CONFIRMED_NOW`. When the same exact durable location is observed empty after a supported removal or destruction event, the previous location is retained as `LAST_CONFIRMED` rather than erased or replaced with a guess. Unknown instances, definition/revision mismatches, inactive instances, unresolved identity anomalies, conflicting current state, terminal state, and stale removal evidence do not replace durable current state. Observation, current-state compare-and-set, and audit evidence commit atomically on the bounded SQLite executor.

Paper work is coalesced by display entity, location type, slot, and event source. Candidate identities per coalesced slot and queued persistence requests are hard-bounded. When the configured concurrency limit is occupied, observations enter a bounded FIFO and drain iteratively as prior writes complete instead of recursing or being silently discarded. Capacity exhaustion logs a warning and preserves existing durable evidence. No live Bukkit entity or mutable item reference crosses the asynchronous storage boundary.

Non-player entities cannot pick up an item that contains either valid tracked identity or malformed LoreItems identity evidence. Player pickup and untracked vanilla item pickup remain unchanged. The listener does not repair, split, delete, or otherwise mutate malformed physical evidence.

## Identity anomalies, warnings, and initial administration

Event-bounded inventory, dropped-item, display, interaction, conversion, and protection observations detect two copies carrying the same instance UUID and recoverable malformed tracked stacks. Both physical copies remain usable and unchanged. Evidence includes the observed locations and is persisted atomically with anomaly and audit records on the bounded SQLite executor.

Anomaly persistence uses bounded in-flight work, a bounded coalescing queue, iterative synchronous-completion draining, cooldowns for repeated identical evidence, and explicit overflow logging. New active anomalies request an immediate staff/console warning. A separate bounded query worker repeats warnings every five minutes while unresolved warning-eligible anomalies remain. Refreshing the same anomaly does not create a five-minute warning loop.

Administrators with `enthusia.loreitems.admin.audit` can use the initial paginated command surface:

```text
/loreitems anomalies [page]
/loreitems audit <instance-uuid> [page]
/loreitems recovery [page]
```

These commands expose unresolved anomalies, current state, recent observation/audit evidence, and non-terminal delivery or mutation recovery records. They are read-only. Explicit anomaly resolution, recovery mutation controls, and GUI administration remain assigned to later phases.

## Template editor and revision rollout

Administrators with `enthusia.loreitems.admin.audit` can select a definition from the paginated administration browser and inspect its current template revision, active-instance count, unresolved-anomaly count, pending-update count, rollout state, and safe item preview. Editing additionally requires `enthusia.loreitems.admin.edit` and a writable storage runtime.

Only one private editor session may exist per administrator, and the global session/query limits are bounded. Clicking an editor field opens a private, command-only prompt. Run `/loreitems set <value>` (or `/loreitems editor set <value>`) to apply a validated change to the in-memory draft. Run `/loreitems set cancel` to abandon only the selected field, or `/loreitems editor cancel` to discard the entire draft. Ordinary public chat is never consumed as editor input. Invalid input leaves the draft and persisted template unchanged. The entire draft is shown in a current-versus-proposed preview before confirmation. Timeout, disconnect, reload, shutdown, permission loss, stale inventory clicks, or explicit cancellation close the session without creating a revision or rollout.

The editor exposes these component actions:

```text
material:          minecraft:<material>
custom-name:       clear | literal <text> | solid <#RRGGBB> <text>
                   | gradient <#RRGGBB,#RRGGBB,...> <text>
item-name:         same styles as custom-name
lore:              add <styled-text> | edit <1-based-line> <styled-text>
                   | remove <1-based-line> | move <from> <to> | clear
enchant:           set <namespaced-key> <integer-level> | remove <key> | clear
                   | tooltip visible|hidden
glint:             true | false | unset
durability:        damage <value> | unbreakable true|false
attribute:         set <attribute-key> <modifier-key> <operation> <amount> <slot-group>
                   | remove <modifier-key> | clear
item-model:        <namespaced-key> | clear
max-stack:         1
custom-model-data: floats <csv> | flags <csv> | strings <pipe-separated> | colors <csv> | clear
dye:               <#RRGGBB> | clear
potion:            base <type> | set-effect <type> <duration> <amplifier> <ambient> <particles> <icon>
                   | remove-effect <type> | clear-effects | color <#RRGGBB> | clear-color
trim:              <material-key> <pattern-key> | clear
banner:            add <dye-color> <pattern-key> | set <index> <dye-color> <pattern-key>
                   | remove <index> | clear
profile:           <uuid> [name] | clear
firework:          power <0-255> | add <type> <colors> <fade-colors|none> <flicker> <trail>
                   | remove <index> | clear; stars use set/clear with the same effect syntax
flags:             add <item-flag> | remove <item-flag> | clear
tooltip:           hide true|false | style <namespaced-key|clear>
```

Styled text accepts literal text, one solid hex color, or a multi-stop gradient. Namespaced keys, enum values, numeric ranges, material compatibility, and component-specific item types are validated before the draft changes. Tracked templates and generated instances are always normalized to amount one and maximum stack size one, regardless of editor input.

`Replace from held item` remains the exact-copy fallback for Paper-supported components not individually exposed above. The held item is cloned through the versioned Paper template codec, all LoreItems definition/instance/revision identity is removed, foreign persistent data and supported components are retained, amount and maximum stack size are normalized to one, and the replacement still requires preview and confirmation.

One confirmation ID is persisted with the immutable next revision, actor, before/after template evidence, audit event, desired instance revisions, and durable rollout mutation rows in one SQLite transaction. Callback replay or restart returns the stored confirmation result only when every request field and template byte matches; altered replay, stale revision, inactive/deleted definition, or transaction failure creates no second revision or rollout.

The rollout planner queries and stages only configured bounded batches. Existing inventory and naturally encountered entity/container update workers cover player and Ender Chest inventories, loaded physical containers, nested shulkers and bundles, dropped items, item frames and glow item frames, item displays, and individual armor-stand equipment slots. They preserve definition ID and instance UUID, verify the expected old revision and post-write identity, and send malformed, duplicated, stale, missing, or otherwise ambiguous outcomes to review instead of overwriting or retrying blindly. Offline players and unloaded chunks remain durably pending until a supported natural observation; no editor path force-loads chunks. Paper object access and codec work stay on the server thread, while SQLite work uses the bounded database executor.

## Static analysis policy

Codacy continues to analyze Java source and tests with PMD and the configured Java analyzers. The final size findings were resolved through source decomposition: administration formatting, identity scanning, terminal-void coordination, plugin activation, and test fixture seeding now have focused boundaries. No Java metric exclusion remains. The terminal-void coordinator narrowly suppresses PMD's `UseConcurrentHashMap` rule because its map and set are one compound state machine guarded by a single lock; independently changing one collection would not make the transition atomic. The immutable released SQLite migration remains excluded from non-SQLite SQL policies.

## Degraded startup and recovery

If the database cannot open or migrate, the runtime enters `DEGRADED_READ_ONLY`. No write-capable service is published, and delivery requests return `SERVICE_UNAVAILABLE`. The plugin does not pretend a request was accepted.

Operators should preserve the database file, inspect the logged startup error, verify filesystem permissions and free space, and restore from backup when corruption is suspected. Do not delete the database merely to make the plugin start: that would discard authoritative workflow and identity records.

## Current limitations

Automated verification now covers definition creation/adoption, durable direct delivery, protection and void loss, bounded tracking and reconciliation, duplicate/malformed evidence, paginated administration, and the complete WP-01 template editor plus durable revision rollout. Destructive administration, mass distributions, production hardening/live acceptance, release publication, and EnthusiaTags integration remain assigned to later fixed packages.

No live Paper/Leaf server behavior has been tested. MockBukkit does not fully emulate every modern Paper data component, so automated adapter tests verify accepted command paths, validation, identity stripping, component round trips where supported, session lifecycle, persistence, and rollout safety; WP-05 remains responsible for real-server component rendering, event ordering, reload behavior, and the complete operator acceptance matrix.
