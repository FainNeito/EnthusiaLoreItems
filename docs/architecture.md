# EnthusiaLoreItems architecture

## Status

This document defines the target architecture before implementation. The first implementation PR must preserve these boundaries unless it explicitly documents and justifies a change.

## Design principles

1. The physical item is not the database. Item metadata identifies an instance; SQLite stores authoritative workflow, audit, and last-confirmed tracking state.
2. Observations are evidence. An unloaded-container location is last-confirmed evidence, not a live fact.
3. Persist durable intent before destructive or distributive side effects.
4. Retry only idempotent work. Surface ambiguity instead of guessing.
5. Bukkit/Paper mutations run only on the owning server thread. Database, filesystem, parsing, and expensive serialization run off-thread.
6. No feature may force-load chunks to improve tracking or finish an operation.
7. Every queue, worker, retry, cache, page, and per-tick mutation budget is bounded.

## Proposed module and package boundaries

A single deployable jar is sufficient, but code must be divided into enforceable layers:

```text
bootstrap
  -> application
  -> domain

paper-adapter -> application/domain
sqlite-adapter -> application/domain
config-adapter -> application/domain
api-adapter -> application/domain
```

Recommended Gradle modules:

```text
domain          pure Java identities, state machines, policies, results
application     use cases and ports; pure Java
adapters-paper  Paper listeners, commands, GUIs, item codec, schedulers
adapters-sqlite migrations and repository implementations
plugin          entrypoint and dependency wiring
integration-tests
```

A smaller package-based build is acceptable only if architecture tests enforce equivalent dependency rules.

### Domain

The domain contains no imports from Bukkit, Paper, JDBC, SQLite, YAML, Adventure platform adapters, or GUI libraries.

Core aggregates and value objects:

- `LoreDefinitionId`
- `LoreInstanceId`
- `TemplateRevision`
- `LoreDefinition`
- `LoreInstance`
- `InstanceObservation`
- `LocationDescriptor`
- `DuplicateConflict`
- `PendingMutation`
- `DeliveryRequest`
- `DistributionCampaign`
- `CampaignRecipient`
- `DeletedDefinitionMarker`
- `AuditEvent`

Core state machines:

- direct delivery: `PENDING -> RESERVED -> APPLIED -> VERIFIED -> COMPLETED`
- template update/removal: `PENDING -> CLAIMED -> APPLIED -> VERIFIED -> COMPLETED`
- ambiguous mutation: any nonterminal state may enter `REVIEW_REQUIRED`
- campaign: `DRAFT -> ACTIVE <-> PAUSED -> COMPLETED | CANCELLED`
- campaign recipient: `PENDING_NAME | PENDING_OFFLINE | PENDING_SPACE -> RESERVED -> DELIVERED`, with bounded retry metadata
- full deletion: `REQUESTED -> REMOVING -> COMPLETE`, while late-returning copies remain eligible for removal through the minimal deleted-ID marker

### Application

Application services coordinate use cases through ports. They do not call Bukkit statics or JDBC directly.

Primary use cases:

- create definition from held-item snapshot;
- adopt held item as a new instance;
- queue direct give;
- edit template and enqueue revision rollout;
- observe accessible inventory/entity contents;
- resolve duplicate conflict;
- remove instance;
- purge definition instances;
- fully delete definition;
- validate/start/pause/resume/cancel campaign;
- bind unresolved campaign name on player join;
- query paginated definition, instance, location, campaign, and audit views;
- queue idempotent external API delivery.

Key ports:

- `DefinitionRepository`
- `InstanceRepository`
- `ObservationRepository`
- `MutationRepository`
- `DeliveryRepository`
- `CampaignRepository`
- `AuditRepository`
- `UnitOfWork`
- `ItemIdentityCodec`
- `ItemTemplateCodec`
- `AccessibleInventoryPort`
- `PlayerDirectoryPort`
- `SchedulerPort`
- `ClockPort`
- `StaffNotificationPort`
- `GroupFilePort`
- `MetricsPort`

## Item identity and template representation

The Paper adapter writes namespaced PDC fields for definition ID, instance UUID, and applied template revision. No identity is visible in item lore.

The database stores a canonical serialized item template produced through a versioned Paper adapter codec. The codec must include a format version and fail safely when it cannot decode a newer format. The system should also store normalized fields needed for GUI search without repeatedly deserializing every template.

Held-item creation and `replace template from held item` use the Paper item codec so uncommon components survive. GUI edits operate on a typed template-edit command rather than mutating Bukkit `ItemStack` objects in application code.

Every generated template is normalized to one item and maximum stack size one. A malformed observed stack is evidence of an anomaly and is not automatically split.

## Observation and tracking model

The system should not continuously claim one globally perfect location. It records observations with:

- instance ID;
- definition ID;
- location descriptor;
- observation source;
- observed timestamp;
- session/chunk/container identity where applicable;
- confidence/status: `CONFIRMED_NOW`, `LAST_CONFIRMED`, `CONFLICTING`, `TERMINAL_VOID`;
- optional container path for nested shulker/bundle storage.

When a complete accessible scope is scanned, the application service reconciles all lore items observed in that scope in one bounded operation. Missing items are not immediately declared destroyed if another event path or inaccessible nested container could explain them. Divergent evidence becomes unresolved or conflicting.

The Paper adapter should observe scopes only on meaningful events and debounce repeated events. Examples:

- player join, inventory close, Ender Chest close, quit snapshot;
- inventory click/drag and hopper-related movement, with delayed post-event snapshot rather than trusting pre-event contents;
- item pickup/drop/spawn/despawn/damage/merge;
- player death and respawn inventory transitions;
- chunk load/unload container and display-entity discovery within a strict configured budget;
- block container break/place;
- shulker/bundle open, close, move, place, and break;
- item frame and armor stand equipment changes.

Chunk-load reconciliation may inspect only naturally loaded chunks. It must be paged across ticks when container density is high.

## SQLite design

SQLite runs in WAL mode with foreign keys enabled, a bounded busy timeout, and versioned forward migrations. One bounded database executor is preferred for predictable write ordering. Read work may use a small bounded pool if measurements justify it.

Suggested tables:

- `lore_definitions`
- `lore_definition_revisions`
- `lore_instances`
- `instance_observations`
- `instance_current_state`
- `instance_anomalies`
- `pending_mutations`
- `direct_deliveries`
- `distribution_campaigns`
- `distribution_recipients`
- `external_delivery_requests`
- `deleted_definition_markers`
- `audit_events`
- `schema_history`

Important constraints:

- unique instance UUID;
- unique active definition lookup key;
- unique external idempotency key;
- unique `(campaign_id, recipient_key)`;
- unique campaign source fingerprint so one file cannot be started twice;
- monotonic template revision;
- explicit foreign keys and checked enum/state values;
- indexed active queues, definition/instance lookup, player UUID/name binding, location type, and anomaly state.

Large item blobs and audit history must be paged. Repository APIs must never return an unbounded `List`.

## Durable mutation protocol

For give, edit, remove, and deletion work:

1. Validate authorization and input.
2. Persist an operation with an idempotency key and expected state.
3. Claim a bounded batch of work off-thread.
4. Return to the server thread to read and mutate the accessible Paper inventory/entity.
5. Produce an observed before/after fingerprint.
6. Commit verification and audit off-thread.
7. Retry only when stored evidence proves retry safety.
8. Enter `REVIEW_REQUIRED` when the observed result is ambiguous.

The initial implementation does not need distributed leases because the plugin is single-server. It still needs atomic claim tokens or compare-and-set state transitions so reload/restart or two workers cannot apply the same mutation twice.

## Direct delivery

`give` and external API delivery create a durable request before an item is inserted. The request owns the instance UUID before insertion. The server-thread adapter verifies inventory capacity, inserts exactly the prepared item, then snapshots the resulting inventory. Completion is recorded only after the new instance is observed in the expected scope.

If the player is offline or lacks space, the request remains pending. Join, inventory-space changes, and a low-frequency bounded retry scheduler may requeue it. No delivery drops an item entity.

## Definition revision rollout

Editing creates an immutable definition revision and pending work for known active instances. The instance stores `applied_revision` and desired revision. Accessible scopes are updated in bounded batches. Inaccessible instances update when next observed.

The update adapter must preserve the hidden instance UUID while replacing visible/template components. It must verify the definition ID and old identity before writing. A conflicting or malformed item enters review rather than being overwritten.

## Duplicate conflict handling

The current-state projection may contain multiple observations for one instance ID. If two concurrently credible physical locations exist, create or maintain a duplicate conflict.

A five-minute notifier queries unresolved conflicts through an indexed count/list operation, warns staff with the permission, and logs to console. Notification work is bounded and coalesced. It does not rescan inventories.

Resolution is an explicit audited application command selecting the valid physical copy or assigning a fresh identity to an approved copy. The exact resolution options should be implemented only after the first detection and inspection workflow is tested.

## One-use distribution campaigns

The group-file adapter validates YAML and computes a source fingerprint. Campaign start commits the immutable recipient snapshot and campaign identity before moving/renaming the file to its active form. If the filesystem move fails after the database commit, recovery repairs the marker from database state rather than creating a second campaign.

After start, SQLite is authoritative. The file is an operator marker and audit artifact only.

Recipient keys use UUID when known. Unknown names, including names beginning with `*`, use a normalized case-insensitive key plus original display form. On join, the current player name is matched against unresolved keys and atomically bound to the UUID. The campaign can remain active indefinitely.

Campaign cancellation changes pending recipients to cancelled and stops future reservation. Delivered recipients remain delivered. Completion moves the file to `groups/completed`; cancellation moves it to `groups/cancelled`.

## Paper threading and performance

Main-thread work must be short, bounded, and limited to Paper object access or mutation. Never retain live `Player`, `Inventory`, `Entity`, `Chunk`, or `ItemStack` references across async boundaries. Capture immutable snapshots on-thread and process/store them off-thread.

Required controls:

- per-tick item mutation budget;
- per-tick naturally loaded chunk/container inspection budget;
- bounded database queue with backpressure;
- bounded notification and delivery retries;
- debounce map with expiry and size cap;
- paginated GUIs and queries;
- small cache for active definitions and currently online players only;
- metrics for queue depth, oldest pending age, database latency, reconciliation rate, duplicate count, and failed operations.

If storage is unavailable, the plugin enters a safe degraded/read-only mode. It may allow inspection from safe cached data but must reject creation, adoption, delivery, edits, and destructive operations that cannot be persisted.

## Configuration and reload

Parse configuration into immutable validated records. Reload builds and validates a complete replacement snapshot before swapping it atomically. Reload must not recreate executors, close the database, discard campaigns, or lose pending work.

Configuration should include:

- independent storage defaults and per-definition shulker/bundle/ender-chest overrides, with legacy shared policy fallback;
- staff duplicate-warning interval, default five minutes;
- queue and per-tick budgets with safe bounds;
- database busy timeout and maintenance settings;
- GUI pagination and cache limits;
- permissions and message templates where appropriate.

## Public API

Register a Bukkit service with a semantic API version. The first stable contract should expose an asynchronous/idempotent queue method similar to:

```java
CompletionStage<LoreDeliveryResult> queueDelivery(
    String definitionKey,
    UUID playerId,
    String externalOperationId
);
```

The returned stage represents durable acceptance, not necessarily immediate inventory insertion. Duplicate calls with the same external operation ID return the stored result and never create another instance.

Do not make command dispatch the Tags integration boundary.

## Testing architecture

Use pure unit tests for domain state machines and policy. Use SQLite integration tests with temporary databases for migrations, constraints, transaction rollback, restart recovery, idempotency, campaign resume/cancel, and late-returning deleted IDs.

Add architecture tests that fail when:

- domain/application imports Bukkit, Paper, JDBC, or YAML;
- adapters depend on each other instead of ports;
- package cycles are introduced;
- unbounded collection-returning repository methods are added.

Paper adapter tests should cover item identity codec round trips and listener cancellation where practical. A manual test matrix must cover Geyser/Floodgate names, nested storage, full inventory, restart during delivery/update/delete, natural chunk reload, item frames, armor stands, void loss, fire/explosion protection, duplicate copies, and server shutdown under queued load.
