# EnthusiaLoreItems production requirements

## 1. Purpose and scope

EnthusiaLoreItems is a single-server Paper/Leaf plugin for the Enthusia SMP. It creates, adopts, distributes, protects, locates, edits, audits, and safely removes important physical items.

Target runtime:

- Java 21
- Paper/Leaf 1.21.11
- SQLite
- Geyser and Floodgate compatibility
- One SMP server only; no proxy or multi-server coordination

The plugin will later expose a stable API used by EnthusiaTags to grant lore-item rewards. The Tags integration is a separate phase and must not be implemented until the lore-item API is stable.

## 2. Core identity model

A lore-item definition represents a named item type, such as `vanguards_hourglass`. A definition may have one physical instance or many. No uniqueness or quantity limit is enforced.

Every physical instance has:

- a hidden definition identifier;
- a hidden random instance UUID;
- a stored template revision;
- a server-side audit identity and current/last-confirmed location.

The identifiers must be stored through the item's PersistentDataContainer or an equally private Paper-supported component. They must never be added to visible name, lore, tooltip, model, or player-facing command output. Regular players must not be able to discover them through normal gameplay. Staff interfaces may display a friendly sequence such as `Instance #4`, but the internal UUID remains hidden unless a privileged diagnostic command explicitly requests it.

All tracked instances are unstackable. New and edited templates must enforce a maximum stack size of one. If a malformed stack appears, the plugin must preserve it, flag an anomaly, and notify staff rather than deleting, splitting, or silently assigning identity.

## 3. Creating and adopting items

Administrators must be able to:

1. Hold any existing item and create a new definition with a chosen display/lookup name.
2. Hold another item and adopt it as a new instance of an existing definition.
3. Create a new instance of a definition and give it to themselves.
4. Create a new instance and give or queue it for another player.
5. Queue a direct delivery for an offline player.
6. Retain arbitrary item components from a held item when cloning it into a definition.

Creating or adopting an instance assigns a fresh hidden instance UUID. Adopting an existing item must not replace its visible appearance unless the administrator intentionally chooses to normalize it to the current definition template.

Direct give operations must be durable and idempotent. A full inventory never causes an item to be dropped. Offline and full-inventory deliveries remain queued across restarts until completed or explicitly cancelled by an administrator.

## 4. Item editor

The plugin must provide simple administrative GUIs backed by chat input for text values. It must support editing at least:

- base material;
- custom name or item name;
- solid colors and multi-color gradients;
- lore lines, solid colors, and gradients;
- enchantments and levels;
- visible or hidden enchantment tooltips;
- glint override;
- damage;
- unbreakable state;
- attribute modifiers;
- item model;
- maximum stack size, while tracked items remain forced to one;
- other common Paper-supported item components relevant to the supplied examples.

There must also be an advanced `replace template from held item` operation. This provides an exact-copy path when the GUI does not expose a new or unusual Minecraft component.

Editing a definition changes every existing instance. The plugin must increment the template revision and create durable queued update work. It must update currently accessible instances through bounded work and update offline-player, unloaded-container, and otherwise inaccessible instances when they are naturally encountered. It must not force-load chunks or perform one large synchronous rewrite.

## 5. Protection and permitted movement

Lore items remain usable as their underlying vanilla item but provide no custom powers merely because they are tracked.

By default, tracked instances:

- cannot naturally despawn;
- cannot burn in fire or lava;
- cannot be destroyed by explosions, cactus, or ordinary environmental item damage;
- cannot break from durability loss;
- cannot be consumed, crafted, smelted, ground, smithing-transformed, renamed, or otherwise converted in a way that loses identity;
- can be dropped, traded, stolen, moved between inventories, and placed in containers;
- can be stored in player inventories and Ender Chests;
- can be displayed in item frames, glow item frames, and armor stands;
- cannot be picked up and retained by ordinary mobs;
- can be intentionally lost by dropping them into the void.

Void loss is valid and must be recorded as a terminal destruction event, not automatically restored.

Nested storage in shulker boxes and bundles is supported and tracked. Independent global defaults and optional per-definition overrides control insertion into shulkers, bundles, and ender chests. Missing overrides inherit the relevant default. The legacy `shared-containers-allowed` setting remains the fallback for shulkers and bundles; ender chests default to allowed. Restrictions must inspect nested contents, leave existing stored items untouched, and allow removal. See `docs/per-item-storage.md` for acceptance requirements.

## 6. Tracking and locations

The plugin must track current or last-confirmed location without force-loading chunks. Supported location categories include:

- player inventory, armor, offhand, and cursor;
- player Ender Chest;
- physical block container with world and coordinates;
- nested shulker or bundle, including the outer holder/location when known;
- dropped item entity;
- item frame or glow item frame;
- armor stand equipment;
- queued delivery;
- pending update or pending removal;
- void-destroyed;
- missing or unresolved;
- duplicate conflict.

The GUI must clearly distinguish `confirmed now` from `last confirmed`. Unloaded-container information must not be presented as a live observation.

Tracking must be event-driven and reconciled when inventories, entities, players, or chunks naturally become accessible. The implementation must account for normal inventory interactions, hopper/container movement where observable, player join/quit, inventory close, chunk load/unload, item entity lifecycle, display entities, death drops, plugin reload, and restart recovery.

The plugin cannot promise omniscience over offline world-file edits, restored backups, or other plugins that bypass Bukkit/Paper events. It must instead retain durable last-known state, reconcile on observation, report divergence, and never guess destructively.

## 7. Duplicate-instance detection

If two or more physical items carry the same instance UUID, all copies enter a duplicate-conflict state.

The plugin must:

- preserve the copies;
- avoid automatically choosing or deleting a copy;
- record every observed location;
- immediately warn online staff with the relevant permission;
- log a clear console warning;
- repeat the staff warning every five minutes while the conflict remains unresolved;
- provide a GUI for staff to inspect the copies and intentionally resolve the conflict.

Duplicate detection must also cover malformed stacks and conflicting observations that cannot safely be reconciled.

## 8. Administrative browsing and operations

The primary administrative command opens a GUI listing active definitions. Selecting a definition shows its instance count, anomaly count, queued operations, and status. Selecting it again shows holders/locations using player heads or suitable icons.

Required operations include:

- create definition from held item;
- adopt held item into a definition;
- give a new instance to self or another player;
- edit definition/template;
- inspect all instances and locations;
- inspect one instance and its audit history;
- remove one exact instance;
- remove all known instances while retaining the definition;
- fully delete the definition and every instance;
- pause, resume, or inspect queued destructive/update work;
- review duplicate and malformed-stack anomalies.

A full delete removes the definition from ordinary GUIs and tab completion, queues removal of all known instances, and deletes the physical items rather than merely stripping their metadata. If instances remain, deletion requires a clear confirmation explaining that physical items will be removed.

The plugin must retain only the minimal deleted-definition identity and audit data needed to remove a copy that later reappears from an unloaded chunk, offline inventory, rollback, or backup. Deleted definitions must not appear in normal search, GUI, or tab completion. Historical audit access may be available through a separate privileged history interface.

Destructive changes must be durable, restart-safe, and idempotent. No chunk is force-loaded. Removal from inaccessible inventories is queued until the inventory is naturally available.

## 9. One-use mass distributions

On first startup, create:

```text
plugins/EnthusiaLoreItems/groups/
plugins/EnthusiaLoreItems/groups/completed/
plugins/EnthusiaLoreItems/groups/cancelled/
```

A group file uses YAML:

```yaml
display-name: Beta Players
players:
  - P2wn
  - "*BedrockPlayer"
  - 00000000-0000-0000-0000-000000000000
```

The leading `*` is a valid Floodgate/Geyser username prefix on this server and must be preserved. Name matching must be case-insensitive while retaining the original form for audit display.

Each group file is one-use. Starting it must:

1. validate the file and selected definition;
2. create a permanent campaign UUID and immutable recipient snapshot in SQLite;
3. resolve known names to UUIDs without blocking the server thread;
4. retain unresolved names for future join matching, including players who have never joined and may first join years later;
5. make the database authoritative for the campaign;
6. rename or move the source file to an active marker state;
7. deliver through a bounded persistent queue;
8. resume automatically after restart;
9. move the file to `completed/` with a clear completed suffix only after every recipient receives exactly one campaign instance.

Editing or copying the file after start must not alter or duplicate the active campaign. Starting the same source twice must be rejected.

Online recipients receive the item only when inventory space exists. Offline and full-inventory recipients remain pending. Items are never dropped as overflow.

Required campaign commands and GUIs include:

- reload/validate group files without restarting the server;
- start a file for a chosen definition;
- show total, delivered, unresolved, offline-queued, inventory-full, failed, and remaining counts;
- pause;
- resume;
- cancel.

Cancelling stops all future deliveries and preserves instances already delivered. The campaign and audit history remain visible as cancelled. It does not remove delivered items.

## 10. Floodgate and player identity

The plugin must work for Java and Bedrock players. It must not assume Java-name syntax. It must preserve the configured Floodgate `*` prefix and use actual UUIDs whenever a player has joined or an authoritative server API can resolve them.

An unresolved name in a campaign remains pending until a joining player's current name matches it case-insensitively. After binding, the UUID becomes authoritative. UUID entries are preferred when the operator knows them.

The implementation must avoid network-dependent Mojang lookups as a correctness requirement. Such services are not reliable for Floodgate players or long-lived queued campaigns.

## 11. Persistence, recovery, and audit

Use SQLite in WAL mode with schema migrations, foreign keys, integrity checks, indexes, and explicit transactions. All database and filesystem I/O must run off the server thread.

Durable records must cover at least:

- definitions and template revisions;
- instances;
- observations/locations;
- anomalies and duplicate conflicts;
- queued direct deliveries;
- update/removal/delete operations;
- distribution campaigns and recipient state;
- immutable audit events;
- minimal deleted-definition identities.

Any operation that creates, delivers, edits, or destroys an item must persist intent before or atomically with the side effect wherever practical. Recovery must be idempotent. Ambiguous outcomes must be surfaced for staff review instead of guessed.

Shutdown stops intake, drains only bounded work, persists pending state, and closes resources cleanly. Reload validates a complete new configuration snapshot before swapping it in and must not discard active work.

## 12. Performance constraints

The plugin must be suitable for a live SMP with more than 100 players.

It must not:

- scan all loaded inventories every tick;
- scan all world region files;
- force-load chunks;
- perform SQLite or filesystem I/O on the main thread;
- use unbounded executors, queues, caches, retries, or result sets;
- rebuild large GUIs continuously;
- load every historical instance or audit event into memory;
- process unlimited queued mutations in one tick.

Use event-driven observation, indexed queries, bounded executors, bounded per-tick work budgets, batching, debouncing, pagination, and small active caches. Provide configurable queue budgets and useful operational metrics so staff can see when reconciliation or delivery work is falling behind.

## 13. Public API for EnthusiaTags

Register a versioned Bukkit service API rather than requiring command dispatch. The API must support an idempotent request to queue one new instance of a definition for a player using an external operation key.

The API must report durable outcomes such as:

- accepted and queued;
- already accepted/completed for the same idempotency key;
- unknown definition;
- service unavailable/read-only;
- validation failure.

EnthusiaTags must be able to complete a reward claim without duplicating the lore item after restart or retry. The detailed Tags integration is deferred to a later PR.

## 14. Quality and validation

The project must use clear hexagonal boundaries. Domain and application logic must not import Bukkit, Paper, JDBC, YAML, or GUI classes. Platform and persistence behavior belongs behind ports/adapters.

Required quality work includes:

- unit tests for domain policies and state transitions;
- SQLite integration tests for constraints, transactions, migrations, restart recovery, and idempotency;
- tests for duplicate detection, campaign resume, cancellation, full inventory, unresolved future players, queued updates/deletions, and late-returning deleted copies;
- architecture tests preventing platform imports in core packages and preventing dependency cycles;
- focused Paper adapter tests where practical;
- manual live-server acceptance tests before production deployment;
- Checkstyle/PMD/SpotBugs or equivalent static analysis configured to produce an A-grade-quality Codacy result;
- no broad warning suppressions merely to make Codacy green; any suppression must be narrow and documented.

A successful build alone is not production approval. The implementation PR must clearly list untested live-server behavior and provide a staged deployment and rollback checklist.
