# Shulker scan and acceptance repair

Maintenance scope authorized after production Spark NqUh5Qd1WK: reduce nested discovery snapshots, integrate the reviewed performance/security changes and metrics, investigate acceptance failures, and validate on production's Paper 26.2 / Java 25 runtime. Production stays unchanged; no merge or restart is authorized.

## Requirements

- PERF-01: While scanning a container within one main-thread discovery pass, the scanner SHALL decode each visited shulker or bundle once, including when multiple sibling identities are present.
- PERF-02: When a scan resumes on a later tick, the scanner SHALL read fresh live contents and retain duplicate identity observations across passes. Mutation-time reference resolution SHALL continue to revalidate the live inventory.
- SAFE-01: The scanner SHALL preserve the existing node, depth, continuation and queue limits and SHALL retain no live inventory or metadata snapshot between passes.
- ACC-01: Before testing an asynchronously delivered physical item, acceptance SHALL wait for the actual player-inventory projection and shall fail with diagnostics if delivery does not finish.
- ACC-02: Before asserting LAST_CONFIRMED after moving away, acceptance SHALL record chunk load/ticket state so a retained chunk is distinguishable from missing unload tracking. Assertions SHALL not be relaxed or marked passing on retry alone.
- RUNTIME-01: The new artifact SHALL have exact-source/checksum evidence and an isolated Paper 26.2 / Java 25 startup and shulker workload check. Historical Paper 1.21.11 acceptance remains required.

## Tasks / evidence

Spec: recorded above. Project-local EARS/state helpers are absent; this is a manual requirement and evidence record, not a tooling-pass claim.

Prove: the sibling regression failed with 27 block-state decodes (expected 1) before the discovery-pass change and passes afterward. PR #57 protection artifact shows the second instance in QUEUED_DELIVERY when the harness requests damage; lifecycle ACTIVE alone does not prove physical delivery. Acceptance now waits for CONFIRMED_NOW player storage before damage and void scenarios. Revision rollout times out waiting for three unloaded location records; cause pending. Added exact SQL state, loaded-chunk, force/plugin-ticket and unload-event diagnostics without relaxing assertions.

Engine: pass-local parent/item snapshots replace repeated root-to-child resolution during discovery. Cursors retain references and observations only. Later passes read fresh inventory contents; mutation-time resolution is unchanged. Existing 256-node, depth-8, 64-pass and 8192-pending limits remain. There is no elapsed-time deadline; expensive individual decodes can still exceed a tick.

Architecture / refine: clean check and both JAR builds passed on Java 21 (514 tests: 507 passed, 7 MockBukkit skips; no failures). Existing SpotBugs tasks are advisory (`ignoreFailures=true`), not a clean static-analysis guarantee. Repository tooling: 52 Python tests passed; new-code Lizard thresholds passed. Existing tests cover continuation freshness, duplicate observations, bundles and resolvable references. Project-local SPEAR tooling remains absent.

Runtime evidence: isolated, fresh Paper 26.2 build 129 (9240f58), Java 25.0.3, same host/JVM settings, one sequential run per artifact. Synthetic payload: 27 shulkers each with 27 identical tracked observations; 20 windows, 60 bounded passes, all 729 observations per window retained. A test-only inventory wrapper keeps synthetic identities out of the physical tracking queue. This is discovery workload evidence, not player acceptance or production TPS proof.

| Artifact | p50 pass ms | p95 pass ms | max pass ms |
| --- | ---: | ---: | ---: |
| PR #52 e50ae612 baseline | 63.9818 | 89.1652 | 306.8522 |
| Local combined shulker fix | 12.1755 | 32.9702 | 100.1825 |

Local plugin SHA-256: e2e72af954826c0b5bf6ea6719be1b52d114bf84b2b613a695c53018cfc48b6d. Unmerged test artifact; never a production release. Exact-head hosted acceptance and rollout diagnosis remain pending. Base main 4fcc8711dff6bf634594a7e521d810d919c36270. Reviewed integration e50ae6128047c9ab6cc1deb54408c099774a7090 and metrics 8a69d5d4f82259ab478a8c8965594841e73af66f are included for combined regression testing.
