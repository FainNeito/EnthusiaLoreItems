# Creative inventory and tracking browser

## Normal staff workflow

- Move tracked items between hotbar or inventory slots in Creative as normal.
  A verified move inside the same player's inventory updates the internal slot
  without adding a location-history record.
- Middle-click a tracked item in Creative to request an additional tracked copy.
  LoreItems cancels vanilla cloning and offers clickable **YES / NO** actions
  for 30 seconds. Confirmation queues a durable new delivery from the saved
  definition, with a fresh instance ID. The original is unchanged.
- The copy uses the **saved template** rather than cloning ad-hoc differences
  on an individual instance.
- A source which is no longer in the player's accessible inventory/cursor at
  confirmation time cancels the request. A copy must never reuse a source ID.
- Creative set-slot packets without an existing tracked source remain blocked.
  The Paper event mappings require live staging verification on Java and Bedrock
  before enabling on production.

## Browser

- Browse definitions -> management -> tracked copies -> location and history.
- Default tooltips display collection names, copy number, friendly status, and
  concise colored hints; they hide revision/codec/blob details, raw UUIDs,
  observation IDs, and raw PDC paths.
- On a copy's location/history screen, the **CURRENT LOCATION** compass shows
  readable player inventory/Ender Chest labels, container coordinates and
  world, or the last known location if unloaded. Red/yellow warnings explain
  duplicate conflicts and missing/unresolved cases.
- History includes transfers to another player, chest, Ender Chest, drops and
  displays. Old slot-by-slot history is collapsed on individual pages.
  Staff can still inspect the underlying evidence if required.
- Shift-click a collection/copy/observation for its technical ID/evidence,
  or shift-click the diagnostics clock to see queue/scan metrics.
- Right-clicking a tracked copy does not remove it; use supported destructive
  administration with explicit review/confirmation.

## Data integrity

- When a *unique authoritative* inventory scan verifies a slot change within
  the same owner/container, update the current exact slot **without appending
  an observation or audit entry**. Conservative ambiguous observations can
  still fence duplicate IDs. Don't discard physical slot evidence required
  for exact administrative removal.
- Creative disappearance is checked again after 10 server ticks. If the same
  item cannot be located and the durable state still claims that player's
  inventory, record an audit event and mark current position
  **MISSING_UNRESOLVED**. This is intentionally not the terminal REMOVED or
  VOID_DESTROYED state, because the item may later reappear elsewhere.
- Once a new real location is verified the missing flag clears. Audit history
  remains available.
- The approval does not grant bypasses for creative item injection or
  unresolved/corrupted identity data.

## Required disposable live acceptance

1. Move one tracked item within Creative inventory, hotbar, cursor and back.
   Confirm each move works and no new location-history entry appears.
2. Middle-click the tracked item. Decline, then accept a separate request.
   Confirm no duplicate ID, exact-one delivery, no extra item after restart,
   and a new durable tracked instance row for the accepted copy.
3. Try a second/forged copy with the same ID: it must be blocked or flagged for
   review. Two actual identities inside the same inventory must not silently
   become one.
4. Transfer to a chest, Ender Chest, another player and dropped item; the
   friendly location must show the correct holder/world/coordinates and each
   transition must be recorded exactly once.
5. Delete an isolated disposable tracked item in Creative. Verify that
   audit/history records the possible disappearance and the browser reports
   'Possibly deleted - location unresolved', not a confident inventory location.
6. Exercise existing Paper duplicate-resolution and destructive flows and
   verify the new browser does not change mutation permissions.
7. Restore from a disposable backup before retrying ambiguous results.
