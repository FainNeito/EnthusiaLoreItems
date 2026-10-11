# Handoff 0037 — per-item storage policies

## Session metadata
- Date: 2026-10-11 America/New_York
- Phase: owner-requested storage policy extension
- Repository: wsg138/EnthusiaLoreItems (head fork FainNeito/EnthusiaLoreItems)
- Branch: codex/per-item-storage
- Pull request: https://github.com/wsg138/EnthusiaLoreItems/pull/61
- Reported implementation head: c584c6abd694c2477dd54b3b4767899be98f2d1a
- Session status: in progress (local verification passed; hosted final-head gates pending)

## Objective
Add independent per-definition shulker, bundle and ender-chest rules inheriting global defaults.

## Work completed
Strict flat config options, immutable deep-copied policy, atomic reload and startup fencing; Paper insertion enforcement covering nested contents, clicks, shift clicks, offhand/hotbar, drag and hopper movement. Existing stored items are not confiscated; removal stays allowed. Operator docs and STORE-01 through STORE-06 acceptance requirements updated.

## Important decisions and invariants
Overrides use immutable definition UUIDs and apply to all copies, never mutable display names. Absent/"inherit" destinations use global defaults. Legacy shared-containers-allowed supplies shulker/bundle fallback; ender chests default true. 1024 definition overrides maximum; nested checks stop at eight levels/512 visited items and reject ambiguous insertion. No live server files changed.

## Files or modules changed
application ItemStoragePolicy and FoundationConfiguration/AtomicConfiguration; plugin parser, defaults, startup gate and listener wiring; PaperItemStorageProtection and protection listener; related tests; REQUIREMENTS.md and docs/operator-guide.md, docs/architecture.md, docs/per-item-storage.md.

## Persistence, state-machine, or API changes
No database migration, item rewrite or public LoreItems service API changes. Policies persist in config.yml and publish atomically only after full validation. Existing constructor signatures retained. No schema ceiling change.

## Verification actually performed
Java 21, Paper API 1.21.11: clean check plus plugin shadowJar, then check plus shadowJar after refinements. 529 JUnit tests: 522 passed, seven existing skips, zero errors/failures. 52 Python tooling tests passed in isolated venv with repository requests dependency. Lizard new-code comparison passed without weakening thresholds. Manual source diff reviewed. Advisory SpotBugs printed existing adapter findings; Gradle success does not mean no warnings. No project-local SPEAR EARS/state tools exist; docs/per-item-storage.md records traceability. No historical red/green claim: initial shift-click fixture failure was corrected to use actual raw-slot item mapping.

## Live automation observed
PR61 created draft and attached. Hosted CI run 38113096536 and acceptance/test-jar workflows started on implementation head. At inspection, none of the final results had been established; no reviews or threads yet. Documentation checkpoint changes the head; verify that final head, not these earlier runs.

## Unresolved risks or missing evidence
Hosted final-head checks/review and Java/Bedrock storage interaction acceptance pending. No merged release or deployment, and no production activation. Native bundle behavior and client inventory synchronization need TEST acceptance.

## Exact next step
Inspect final-head CI/Codacy/review annotations, fix in-scope findings on this branch, then arrange explicitly authorized TEST deployment and run docs/per-item-storage.md acceptance matrix. Do not merge or deploy without authorization.

## Required prior reports
None; current source and the new policy/evidence document contain relevant scope and decisions. Historical phase-4 handoff was stale against live main; no open PR preceded this request.
