# EnthusiaLoreItems operator guide

This guide is for server operators running the WP-04 release candidate and later production releases. It documents supported installation, upgrade, backup, restore, degraded-mode recovery, queue/review recovery, staged deployment, rollback, and incident collection. It does **not** claim live Paper/Leaf acceptance; that belongs to WP-05.

## Supported runtime and files

Target runtime:

- Java 21
- Paper/Leaf 1.21.11-compatible server
- SQLite
- single SMP server; no proxy or multi-server coordination
- Geyser/Floodgate-compatible player identities

Primary plugin data lives under `plugins/EnthusiaLoreItems/`.

Important files/directories:

- `config.yml` — operator configuration
- `loreitems.db` — authoritative SQLite database
- `groups/` — pending one-use distribution source files
- `groups/completed/` — completed campaign markers/source files
- `groups/cancelled/` — cancelled campaign markers/source files

The database is authoritative for definitions, instances, observations, queued deliveries, update/removal work, campaigns, recipients, audit data, anomalies, and deleted-definition markers. Do not repair those states by hand unless the documented recovery procedure explicitly calls for database-level inspection.

## Installation

1. Stop the server cleanly.
2. Confirm Java 21 and the intended Paper/Leaf build.
3. Place the shaded EnthusiaLoreItems jar in `plugins/`.
4. Start the server.
5. Confirm `plugins/EnthusiaLoreItems/` exists and startup logs show storage initialization.
6. Confirm the plugin reaches read/write service state before exercising administrative mutations.
7. Confirm the two one-use distribution directories and marker directories exist before using mass distributions.
8. Record the server build, Java version, plugin version, jar SHA-256, and schema version in the deployment record.

If startup reports degraded/read-only or unavailable state, do not run create, give, edit, destructive, or distribution-start operations until the cause is understood.

## Configuration

Default configuration:

```yaml
database-busy-timeout-millis: 5000
database-queue-capacity: 256
database-shutdown-timeout-seconds: 10

delivery-claim-batch-size: 32
delivery-claim-lease-seconds: 30
duplicate-warning-interval-seconds: 300
default-page-size: 45
max-page-size: 100
mutation-budget-per-tick: 16
shared-containers-allowed: true
```

`shared-containers-allowed: true` is the default and leaves nested shulker/bundle storage unrestricted. Set it to `false` to prohibit players from inserting LoreItems into either shulkers or bundles; removing an existing LoreItem from those containers remains allowed.

### Restart-required settings

These are captured by storage resources or startup-created workers and require a clean server restart after change:

- `database-busy-timeout-millis`
- `database-queue-capacity`
- `database-shutdown-timeout-seconds`
- `delivery-claim-batch-size`
- `delivery-claim-lease-seconds`
- `duplicate-warning-interval-seconds`
- `default-page-size`
- `max-page-size`
- `mutation-budget-per-tick`

### Hot-reloadable setting

Only `shared-containers-allowed` is currently hot reloadable. After changing it, run `/loreitems reload` with `enthusia.loreitems.admin.reload`. The plugin validates the complete candidate snapshot off the server thread and atomically swaps it only when valid and when all restart-required settings still match the running snapshot.

A failed reload, or a reload that also changes a restart-required setting, leaves the previous complete snapshot active. Reload must not discard active deliveries, mutations, campaigns, or destructive work. Do not use Bukkit/Paper `/reload` as a substitute; use a clean server restart for every restart-required setting.

## Permissions

Administrative permissions are intentionally split by capability:

- `enthusia.loreitems.admin.create`
- `enthusia.loreitems.admin.adopt`
- `enthusia.loreitems.admin.give`
- `enthusia.loreitems.admin.audit`
- `enthusia.loreitems.admin.edit`
- `enthusia.loreitems.admin.remove`
- `enthusia.loreitems.admin.purge`
- `enthusia.loreitems.admin.delete`
- `enthusia.loreitems.admin.destructive.inspect`
- `enthusia.loreitems.admin.destructive.control`
- `enthusia.loreitems.admin.destructive.review`
- `enthusia.loreitems.admin.distribution.inspect`
- `enthusia.loreitems.admin.distribution.start`
- `enthusia.loreitems.admin.distribution.control`

Defaults are operator-only. Grant the narrowest permission set required for a staff role. In particular, do not bundle delete/purge/review permissions into ordinary browse roles.

## Command surfaces

Primary administration:

```text
/loreitems create|adopt|give|browse|anomalies|audit|recovery|remove|purge|delete|operations|targets|destructive-metrics|pause-operation|resume-operation|resolve-removal ...
```

Mass distributions:

```text
/loredistribution reload|inspect|preview|confirm|campaigns|status|recipients|pause|resume|cancel|reconcile ...
```

Use command tab completion and the administrative GUIs for the installed build's exact arguments. Destructive actions require the build's preview/confirmation path; do not bypass confirmation by directly editing the database.

## Operational invariants

Operators should treat these as release-blocking safety properties:

- full inventories never cause lore items to drop as overflow;
- offline/full-inventory delivery remains durable until completion or explicit cancellation;
- inaccessible chunks/inventories are not force-loaded for update/removal;
- ambiguous physical side effects are surfaced for review instead of guessed/repeated;
- duplicate instance UUID copies are preserved until staff intentionally resolves them;
- malformed stacks are preserved and flagged rather than silently split or deleted;
- a deleted definition remains hidden from normal browsing while enough tombstone identity remains to remove late copies;
- completed/cancelled campaign state is database-authoritative and must not be recreated by copying an old group file;
- no SQLite or filesystem I/O is expected on the server thread;
- queues, result pages, retries, and per-tick mutation work are bounded.

If an observed condition contradicts one of these invariants, stop the affected operation and collect incident evidence before attempting repair.

## Metrics and backlog interpretation

The plugin exposes bounded-work and destructive/campaign status through administrative status/metrics surfaces. During an incident or acceptance run, capture at minimum:

- database queue depth/high-water mark and rejection/defer evidence;
- delivery/update/removal/destructive/campaign pending counts;
- `REVIEW_REQUIRED` or anomaly counts;
- active versus paused operation/campaign state;
- page/query limits in effect;
- configured per-tick mutation budget;
- relevant timestamps and operation/campaign identifiers.

A growing backlog is not by itself data loss. A queue exceeding configured capacity, silent disappearance of pending work, false success, or repeated ambiguous mutation is a defect and should be treated as an incident.

## Backups

### Preferred offline backup

Use an offline backup for release changes, schema upgrades, rollback points, and acceptance evidence.

1. Stop the server cleanly and wait for shutdown to complete.
2. Confirm the process has exited.
3. Copy the entire `plugins/EnthusiaLoreItems/` directory, including `loreitems.db`, `config.yml`, and all `groups/` marker/source directories.
4. Hash at least `loreitems.db`, the deployed jar, and the backup archive/directory manifest.
5. Record plugin version, jar SHA-256, database schema version, server build, Java version, and timestamp.
6. Preserve the backup until the deployment/acceptance window is closed.

### Online backup constraints

Do **not** copy only `loreitems.db` while the server is running and assume the copy is transactionally complete. SQLite runs in WAL mode, so live state can span the main database and WAL-related files. For a release/rollback backup, prefer the offline procedure.

If an online backup is operationally unavoidable, use a SQLite-supported consistent backup mechanism against the live database and verify the resulting copy with an integrity check before treating it as recoverable. A naive filesystem copy is not release evidence.

## Upgrade procedure

1. Complete an offline backup.
2. Record the current plugin version, jar SHA-256, schema version, pending queue counts, active campaigns, active destructive/update operations, anomaly/review counts, and database hash.
3. Replace the jar with the intended release-candidate/production artifact whose checksum matches the GitHub release asset.
4. Start the server and wait for storage initialization/migrations to finish.
5. Confirm the database reaches read/write service state.
6. Confirm schema migration success and run/record an integrity check.
7. Verify definitions, representative instance identities, deleted markers, pending work, campaign counts, and recent audit history still exist.
8. Verify active work resumes without duplicate delivery or repeated destructive side effects.
9. Run the staged smoke subset before restoring normal staff access.

Never downgrade a live database by simply swapping jars unless that exact rollback path has been rehearsed against a backup.

## Restore procedure

Use this after corruption, failed deployment, or explicit rollback when the chosen backup is known-good.

1. Stop the server.
2. Preserve the failed/current `plugins/EnthusiaLoreItems/` directory separately for incident analysis.
3. Restore the entire known-good plugin data directory, not only selected tables.
4. Restore the jar version that is compatible with that backup/schema.
5. Start the server in a controlled maintenance window.
6. Confirm read/write storage state, schema version, and integrity check.
7. Compare definitions, instance counts/identities, pending queues, deleted markers, campaigns/recipient counts, anomalies, and audit tail against the backup record.
8. Reconcile naturally accessible physical items. Do not force-load chunks or guess about inaccessible locations.
9. Re-run the safety smoke cases for delivery, update, destructive operations, campaign behavior, reload/shutdown, and API replay before reopening normal administration.

A restored backup can reintroduce physical copies that exist outside the restored durable state. Duplicate/late-copy detection must be allowed to surface those conflicts; do not manually strip hidden identity metadata to silence them.

## Rollback procedure

Rollback is a restore operation, not merely a jar downgrade.

1. Stop intake for new administrative mutations/campaign starts.
2. Capture incident evidence and current queue/campaign/anomaly state.
3. Stop the server cleanly.
4. Preserve the failed deployment data and jar.
5. Restore the pre-deployment full backup and its compatible jar.
6. Start and verify integrity/read-write state.
7. Re-run the rollback smoke subset and compare durable counts/identities with the pre-deployment record.
8. Keep the failed deployment evidence until root cause and forward-fix are complete.

If a schema migration has committed, do not run an older binary against the migrated database unless compatibility is explicitly documented and tested. Restore the pre-migration backup instead.

## Degraded/read-only startup recovery

When startup cannot safely offer mutations, the Bukkit service must remain unavailable/read-only as appropriate. Treat degraded mode as protective, not as permission to bypass the application layer.

1. Stop new create/give/edit/destructive/campaign-start activity.
2. Capture the complete startup log and exact plugin/server/JVM versions.
3. Record whether the failure occurred during configuration load, database open, migration, integrity validation, or later initialization.
4. Confirm filesystem ownership/permissions and free disk space.
5. Preserve `loreitems.db` plus WAL/SHM siblings before attempting repair.
6. Run a SQLite integrity check on a copy or during a controlled offline window.
7. If migration failed, restore the pre-upgrade backup unless the migration tooling documents a safe retry and the database is known consistent.
8. Restart only after the cause is corrected; verify read/write state before permitting mutations.

Do not delete/recreate the database to clear degraded mode on a server that contains real tracked items.

## Queue and review recovery

### Pending delivery/update/removal work

Pending work is intentionally durable. After restart, expired claims should be recovered and eligible work should resume. If a queue appears stuck:

1. Capture queue metrics and operation status.
2. Determine whether the work is blocked by offline player, full inventory, unloaded/inaccessible holder, paused operation, retry/backoff, or review-required state.
3. Use the documented inspect/status/recovery GUI or command surface.
4. Resume only work whose durable state makes the next action unambiguous.
5. Do not manually mark work complete solely because the physical item is not currently visible.

### `REVIEW_REQUIRED` / ambiguous mutation

When the plugin cannot prove whether a physical side effect happened, it must not guess.

1. Capture the operation/target identifier, audit history, last confirmed location, and current naturally observable physical state.
2. Keep affected destructive/update work paused or review-gated while evidence is collected.
3. Resolve through the privileged review flow only after the operator can identify the safe outcome from physical and durable evidence.
4. Record the decision and evidence in the incident/acceptance record.

### Duplicate conflict or malformed stack

1. Do not delete or split copies automatically.
2. Capture every observed location and anomaly/audit record.
3. Use the anomaly/review GUI to inspect copies.
4. Resolve intentionally through the supported staff flow.
5. Confirm the warning/anomaly clears only after durable resolution and fresh observation.

## Deleted-marker handling

A full definition delete intentionally leaves minimal tombstone identity/audit data while physical removal may continue. If a late copy reappears from an offline inventory, unloaded chunk, rollback, or restored backup:

1. confirm the definition remains absent from ordinary GUI/search/tab completion;
2. confirm the late copy is associated with the deleted marker rather than recreated as an active definition;
3. allow the normal removal/recovery path to remove the physical copy when naturally accessible;
4. preserve audit evidence of the late observation/removal.

Do not purge deleted-marker rows merely to reduce database size while late copies may still exist.

## Campaign marker repair

The database is authoritative after a one-use distribution starts. Source/marker files are operational markers, not permission to recreate a campaign.

If a marker file is missing, duplicated, or in the wrong directory:

1. inspect the campaign by durable campaign UUID/status first;
2. capture source-name/snapshot/audit evidence;
3. do not start the source file again;
4. use the campaign reconciliation/status tooling for the installed build;
5. repair only the marker state needed to reflect the authoritative database campaign;
6. verify recipient counts and exactly-once delivery before moving/renaming any file manually.

A cancelled campaign keeps delivered instances; cancellation only stops future delivery.

## Staged deployment

For RC/final deployment, use a staged maintenance window:

1. verify release asset checksum/SBOM/dependency manifest;
2. take and verify an offline backup;
3. deploy with administrative mutation permissions temporarily restricted;
4. verify startup, schema/integrity, and read/write service state;
5. run a small create/adopt/give/API replay smoke set;
6. verify one update and one exact destructive operation on disposable test items;
7. verify a small Java/Bedrock distribution campaign including offline/full-inventory behavior;
8. verify reload/restart preserves pending work;
9. inspect queue/anomaly/review metrics;
10. only then restore normal staff access.

WP-05 expands this smoke set into the complete live acceptance matrix. Passing this staged procedure alone is not production approval.

## Incident collection

For any suspected item loss, duplicate delivery, wrong-target deletion, queue loss, main-thread stall, migration problem, or campaign inconsistency, collect before repair:

- UTC timestamp/time range;
- server implementation/build and Java version;
- plugin version and jar SHA-256;
- exact Git commit/release tag when known;
- schema version and configuration;
- relevant player UUID/name (preserve `*` Bedrock prefix exactly in display evidence);
- definition name/internal diagnostic identity when authorized;
- instance/operation/campaign identifiers from privileged diagnostics;
- command/GUI steps immediately preceding the issue;
- console logs and stack traces;
- queue/latency/rate metrics and pending counts;
- relevant audit/current-state/anomaly records or database query output;
- affected physical locations/screenshots where useful;
- restart/reload history;
- backup/restore actions already attempted;
- hashes of copied database/evidence files.

Do not expose hidden instance UUIDs or sensitive player data in public issue text. Redact public evidence while preserving an access-controlled original when required for diagnosis.

## Release/acceptance evidence rule

A local report, chat statement, or screenshot by itself is not durable release evidence. WP-04 and WP-05 evidence must be committed to GitHub or reference durable GitHub-hosted artifacts/attachments with hashes and case IDs. Any untested live behavior must remain explicitly unclaimed until WP-05 executes it.
