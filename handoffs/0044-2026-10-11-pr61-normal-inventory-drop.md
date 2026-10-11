# Handoff 0044 - normal inventory drop fixture

- PR61, codex/per-item-storage; user-authorized merge only when all checks pass.
- Protection artifacts 11693232286 and 11692534067 record the server drop but stale bot inventory after raw block_dig. Experimental packet adapter did not resolve this; removed it and its tests/load wiring rather than retaining unrelated code. Normal Mineflayer clickWindow(slot,1,4) handles inventory-drop bookkeeping and sends the real player action. Server SQLite drop, void terminal and audit assertions unchanged and mandatory.
- Conversion waits for completed adoption with a passing SQLite regression. Nullable regex warning fixed. Java unchanged since 524 passed/seven skips; 53 tooling tests passed. Final hosted gates pending. Inspect every final-head check and review before guarded merge; verify authoritative main afterward. No server deployment or restart.
