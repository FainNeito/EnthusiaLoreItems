# Handoff 0043 - authoritative client inventory

- PR61 codex/per-item-storage. Merge authorized only after passing final-head gates. No server deployment/restart.
- Head 9d2a93b0 artifact 11693232286 proves the server drop occurred and recorded PlayerDropItemEvent while Mineflayer's sword cache stayed stale. Inspection of pinned 4.35.0 inventory plugin confirmed omitted set_player_inventory/set_cursor_item handlers. Protocol fields verified from PrismarineJS 1.21.11 data.
- Added a fixture-only packet adapter, server-authoritative slot/cursor updates and two Node tests (removal, storage/equipment mapping, open window, cursor, invalid slots). Protection workflow runs those tests and loads the adapter with the pinned item codec. Does not modify production plugin logic or simulate a passing action.
- Codacy's nullable regex-match finding fixed with explicit failure when query is absent. Prior main verify failure was the Codacy gate. All 53 tooling and two Node tests pass; Java source unchanged since 524 JUnit passed/seven existing skips.
- docs/runtime-fixture-repair.md contains cumulative evidence. Inspect every exact-head runtime result and reviews before guarded merge, then verify authoritative main and record outcome in PR body.
