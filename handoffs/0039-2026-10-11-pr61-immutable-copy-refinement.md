# Handoff 0039 - immutable copy refinement

- PR: https://github.com/wsg138/EnthusiaLoreItems/pull/61; branch codex/per-item-storage.
- Codacy on 55f4b2a0 cleared ten findings, retaining one constructor-local map warning because its compact-constructor suppression was not recognized. Extracted the deep copy into a private helper, with a method-scoped PMD concurrency exception documenting thread confinement. Only immutable copies remain published; no behavior change.
- Check final commit CI/Codacy results separately. Prior full Gradle check passed (522 tests passed, seven existing skips); repeat application tests and plugin shadowJar for this helper extraction, plus unchanged Lizard gate.
- Reports 0037/0038 and docs/per-item-storage.md contain scope and acceptance details. No merge, deployment or restart; Java/Bedrock TEST acceptance remains pending.
