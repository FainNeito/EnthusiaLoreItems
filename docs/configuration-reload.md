# LoreItems configuration reload

LoreItems supports an operator-facing hot reload only for settings that running components read directly from the live atomic configuration snapshot.

## Command

```text
/loreitems reload
```

Permission:

```text
enthusia.loreitems.admin.reload
```

The command is operator-only by default. Only one reload request is submitted at a time through the command surface.

## Safety contract

The reload path reads and validates a complete candidate `plugins/EnthusiaLoreItems/config.yml` snapshot off the server thread. The active snapshot is replaced atomically only after the candidate is valid and every restart-required setting matches the running snapshot.

If validation fails, or if a restart-required setting changed, the command reports that the reload was rejected and the previous complete snapshot remains active. Active deliveries, item mutations, template rollouts, destructive operations, distribution work, and editor drafts are not discarded by a rejected configuration reload.

Only this setting is currently hot reloadable:

- `shared-containers-allowed`

Storage-locked definitions need no setting: the `enthusialoreitems:storage_lock` marker on the definition template
keeps that item out of shulker boxes, bundles and Ender Chests regardless of `shared-containers-allowed`. Add it to
the held item before `/loreitems create` (or "replace template from held item"), for example with
`custom_data={PublicBukkitValues:{"enthusialoreitems:storage_lock":1b}}`.

The following settings are captured by storage resources or startup-created workers and therefore require a clean server restart when changed:

- `database-busy-timeout-millis`
- `database-queue-capacity`
- `database-shutdown-timeout-seconds`
- `delivery-claim-batch-size`
- `delivery-claim-lease-seconds`
- `duplicate-warning-interval-seconds`
- `default-page-size`
- `max-page-size`
- `mutation-budget-per-tick`

This conservative boundary prevents `/loreitems reload` from reporting success for a value that only some running components would observe. Additional settings may be made genuinely hot reloadable in a future change only if every consumer is converted to read the live snapshot or is safely rebuilt around in-flight durable work.

Do not use Bukkit/Paper `/reload` as a substitute for this command. Use `/loreitems reload` for the live shared-container policy and a clean server restart when changing any restart-required setting.

## Operator procedure

1. Preserve the current `config.yml` or make a small backup copy.
2. If changing only `shared-containers-allowed`, edit the intended value and run `/loreitems reload`.
3. Require an explicit `LoreItems configuration reload applied:` result before treating the candidate as active.
4. If the result is `LoreItems configuration reload rejected:`, correct the file; the last-known-good in-memory snapshot is still active.
5. For any restart-required setting, leave a valid complete configuration on disk and schedule a clean server restart instead of trying to force a hot reload.

WP-05 live acceptance verifies that a valid shared-container-policy reload changes real nested-storage protection behavior without a server restart, that an invalid candidate leaves the last-known-good behavior active, and that durable queued delivery survives both reload attempts.
