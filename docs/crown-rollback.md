# Crown branch rollback to a pre-Crown LoreItems binary

Status: procedure plus local evidence, including one isolated Paper 26.2 downgrade and re-upgrade rehearsal; not rehearsed on TEST. Applies to downgrading from the Crown custody branch, which registers schema migrations V12–V18, to a pre-Crown binary (schema V11): this fork's `main` at `d9e15ae6`, upstream `main` at `86999c0`, or earlier. Both have identical `MigrationRunner` and `SQLiteStorageRuntime` sources, so the evidence below, recorded against upstream `86999c0`, applies to both. The KOTH side is covered separately in the KOTH repository's `docs/crown-rollback.md`.

## What a pre-Crown binary does with a Crown database

Observed by running `main`'s own `SQLiteStorageRuntime` and `MigrationRunner` against a database migrated by this branch (throwaway probe, 2026-11-07):

- Startup refuses the database with `Database schema version 18 is newer than supported version 11`, and storage enters `DEGRADED_READ_ONLY`.
- Every storage operation is then rejected with `SQLite storage is not writable`. This affects all of LoreItems, not only the Crown: no definition, delivery, tracking or distribution writes succeed while the old binary runs.
- The database file is byte-for-byte unchanged (SHA-256 compared before and after). Reinstalling the Crown branch reopens it normally.
- On Paper (26.2-129, local loopback rehearsal, 2026-11-07), `main` fails even earlier with the branch config: `Unknown config key: crown-first-award-enabled`, and "writes remain unavailable". Only after the five `crown-*` keys were removed did it reach the schema check above and log "entered degraded read-only mode".
- A player joining under `main` kept the equipped Crown with its full identity and an intact `crown_exchange` escrow, and the database stayed byte-identical. After the config and branch JAR were restored, storage came back active and custody was unchanged (same operation, generation and EQUIPPED state), with no review hold and one instance.
- Not observed: whether gameplay protections stay active in degraded mode, or what `main` does if the Crown is moved, dropped or traded while it runs. It cannot record any such movement.

So a plain binary swap cannot lose custody data, but it disables LoreItems persistence until the branch is reinstalled. It is a stop-the-feature rollback, not a working downgrade. Keep the branch config (or a backup of it), because the branch needs the `crown-*` keys back on re-upgrade.

## Before any downgrade

1. Disable new Crown work: set `crown-first-award-enabled`, `crown-reclamation-offline-enabled` and `crown-recovery-events-enabled` to `false` on LoreItems. On KOTH, follow the KOTH rollback procedure so that no recovery final or holder challenge can start.
2. Confirm no Crown operation is in flight. Run `/loreitems crown` (staff inspection, read-only), or read back the database with:
   `SELECT operation_id,instance_id,holder_id,generation,state FROM crown_custody;`
   `SELECT operation_id,killer_id FROM crown_succession_requests;`
   `SELECT recovery_operation,state FROM crown_reclamation_escrow;`
   `SELECT recovery_operation,winner_id,recipient_operation FROM crown_recovery_delivery;`
   Proceed only if custody is `EQUIPPED` and there is no succession request, reclamation, staged or held escrow, or recovery delivery for the current operation. A `RESERVED` or `REVIEW_REQUIRED` custody row, or any of those rows, means a staged exchange, an offline removal, a PvP handoff or a recovery grant is in progress: finish or review it on the branch first.
3. Make sure the holder is online and has been saved since the last Crown transition. Their save should hold the Crown as its only copy, with a matching `crown_exchange` escrow (the custody prototype documents this readback).
4. Stop the server, then back up the LoreItems database file, every player data file and the KOTH state, and record their SHA-256 hashes.

## Downgrade options

- **Preferred: do not downgrade LoreItems.** Disable the Crown flags and leave the branch binary in place. The flags stop new first awards, offline reclamations and recovery deliveries, while ordinary LoreItems behaviour continues. Join recovery and `/loreitems crown` stay registered so an already-committed exchange can still finish and be inspected.
- **Binary swap:** LoreItems persistence stays degraded until the branch is reinstalled. Use this only as a short emergency stop.
- **Restore the pre-upgrade backup:** this loses every LoreItems change made since the upgrade, not only Crown records. Player saves made after the upgrade may still hold a Crown and escrow markers that the restored database does not know about, so that needs staff reconciliation per player. Never mint or reconstruct a Crown to fill the gap.
- **Do not** delete `schema_history` rows 12–18 or drop Crown tables to make an old binary start. That would silently discard the custody fence while physical Crowns remain in player saves.

## Evidence and gaps

- Covered: the main-reader probe above; branch migration tests (`MigrationRunnerTest`, `MigrationUpgradeMatrixTest`); and the custody and reclamation integration tests.
- Covered on Paper: the downgrade and re-upgrade with an equipped Crown described above.
- Not covered: a downgrade while a reclamation is held or a delivery is in flight; protection behaviour in degraded mode; Crown movement while the old binary runs; TEST.
