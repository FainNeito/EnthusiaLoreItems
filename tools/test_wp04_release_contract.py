import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class Wp04ReleaseContractTest(unittest.TestCase):
    def test_lifecycle_source_keeps_bounded_stop_and_atomic_reload_guards(self):
        plugin_source = (
            ROOT / "plugin/src/main/java/net/enthusia/loreitems/plugin/LoreItemsPlugin.java"
        ).read_text()
        shutdown_source = (
            ROOT / "plugin/src/main/java/net/enthusia/loreitems/plugin/LoreItemsShutdownSupport.java"
        ).read_text()
        plugin_required = [
            "stopping = true",
            'setUnavailableDelegates("The plugin is stopping.")',
            "getServer().getServicesManager().unregisterAll(this)",
            "ThreadPoolExecutor executor = lifecycleExecutor;",
            "executor.shutdownNow();",
            "failPendingReloads(STOPPING_RELOAD_DETAIL)",
            "Duration.ofSeconds(timeoutSeconds)",
            "LoreItemsShutdownSupport.start(",
            "if (stopping || result.isDone())",
            "configuration.get().replace(candidate)",
        ]
        shutdown_required = [
            "new ArrayBlockingQueue<>(4)",
            "new ThreadPoolExecutor.AbortPolicy()",
            "trackingShutdownTimeout(timeout)",
            "runtime.close(timeout)",
            "awaitLifecycleTermination(logger, executor, timeout)",
        ]
        for token in plugin_required:
            self.assertIn(token, plugin_source, token)
        for token in shutdown_required:
            self.assertIn(token, shutdown_source, token)
        self.assertLess(
            plugin_source.index("ThreadPoolExecutor executor = lifecycleExecutor;"),
            plugin_source.index("executor.shutdownNow();"),
        )
        self.assertLess(
            plugin_source.index("executor.shutdownNow();"),
            plugin_source.index("LoreItemsShutdownSupport.start("),
        )

    def test_existing_behavioral_tests_cover_atomic_reload_storage_shutdown_and_campaign_restart(self):
        required_tests = [
            "application/src/test/java/net/enthusia/loreitems/application/AtomicConfigurationTest.java",
            "adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteStorageRuntimeTest.java",
            "adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteDistributionCampaignRestartTest.java",
            "api/src/test/java/net/enthusia/loreitems/api/v1/LoreItemsServiceV1AbiTest.java",
        ]
        for relative in required_tests:
            self.assertTrue((ROOT / relative).is_file(), relative)

    def test_patch_version_is_source_controlled_and_cli_override_cannot_change_artifact_identity(self):
        properties = (ROOT / "gradle.properties").read_text()
        build = (ROOT / "build.gradle.kts").read_text()
        release_versions = re.findall(r"^releaseVersion=(.+)$", properties, re.MULTILINE)
        self.assertEqual(1, len(release_versions))
        self.assertRegex(release_versions[0], r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
        self.assertIn('rootDir.resolve("gradle.properties")', build)
        self.assertIn('sourceReleaseVersion', build)
        self.assertIn('Ignoring -PreleaseVersion=', build)
        self.assertIn('version = sourceReleaseVersion', build)
        self.assertNotIn('version = providers.gradleProperty("releaseVersion")', build)

    def test_rc_workflow_remains_historical_and_consumes_only_verified_main_ci_artifacts(self):
        release = (ROOT / ".github/workflows/release-rc.yml").read_text()
        self.assertIn("workflows:\n      - CI", release)
        self.assertIn("workflow_run.event == 'push'", release)
        self.assertIn("head_branch == 'main'", release)
        self.assertIn("EVENT_TARGET_SHA: ${{ github.event.workflow_run.head_sha }}", release)
        self.assertIn("RC_TAG: v1.0.0-rc.1", release)
        self.assertIn("gh run download", release)
        self.assertIn("wp04-verification-${TARGET_SHA}", release)
        self.assertIn('ref="refs/tags/${RC_TAG}"', release)
        self.assertIn('sha="${TARGET_SHA}"', release)
        self.assertIn("--target \"${TARGET_SHA}\"", release)
        self.assertIn("--prerelease", release)
        self.assertNotIn("actions/checkout", release)
        self.assertNotIn("gradle --no-daemon", release)

    def test_production_workflow_derives_patch_identity_and_remains_fail_closed(self):
        release = (ROOT / ".github/workflows/release.yml").read_text()
        resolver = (ROOT / ".github/scripts/resolve_release_publication_state.sh").read_text()
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        strict_semver = (
            "^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\."
            "(0|[1-9][0-9]*)$"
        )
        self.assert_production_version_resolution(release, strict_semver)
        self.assert_publication_state_contract(release, resolver)
        self.assert_verified_release_publication(release)
        self.assert_ci_release_provenance(ci, strict_semver)

    def assert_production_version_resolution(self, release, strict_semver):
        self.assertIn("workflows:\n      - CI", release)
        self.assertIn("workflow_run.event == 'push'", release)
        self.assertIn("head_branch == 'main'", release)
        self.assertIn("EVENT_TARGET_SHA: ${{ github.event.workflow_run.head_sha }}", release)
        self.assertNotIn("actions/checkout", release)
        self.assertIn("contents/gradle.properties?ref=${EVENT_TARGET_SHA}", release)
        self.assertIn(strict_semver, release)
        self.assertIn('echo "release_version=${RELEASE_VERSION}"', release)
        self.assertIn('echo "final_tag=v${RELEASE_VERSION}"', release)
        self.assertIn("FINAL_TAG: ${{ steps.version.outputs.final_tag }}", release)
        self.assertIn("RELEASE_VERSION: ${{ steps.version.outputs.release_version }}", release)
        self.assertNotIn("FINAL_TAG: v1.0.0", release)
        self.assertNotIn("version: 1.0.0", release)
        self.assertNotIn("EnthusiaLoreItems 1.0.0", release)
        self.assertIn(
            "contents/.github/scripts/resolve_release_publication_state.sh?ref=${EVENT_TARGET_SHA}",
            release,
        )
        self.assertIn("--jq '.content' | base64 --decode", release)
        self.assertIn('bash "${RESOLVER}"', release)

    def assert_publication_state_contract(self, release, resolver):
        self.assertIn('test "${EVENT_TARGET_SHA}" = "${MAIN_SHA}"', resolver)
        self.assertIn(
            'gh api "repos/${GITHUB_REPOSITORY}/releases/tags/${FINAL_TAG}"', resolver
        )
        self.assertIn("RELEASE_LOOKUP_ERROR=", resolver)
        self.assertIn("RELEASE_LOOKUP_STATUS=$?", resolver)
        self.assertIn(
            "grep -Eq '(^|[^0-9])HTTP 404([^0-9]|$)' \"${RELEASE_LOOKUP_ERROR}\"",
            resolver,
        )
        self.assertIn("--jq '[.id, .tag_name, .draft, .prerelease] | @tsv'", resolver)
        self.assertIn('test "${RELEASE_DRAFT}" = "false"', resolver)
        self.assertIn('test "${RELEASE_PRERELEASE}" = "false"', resolver)
        self.assertIn('releases?per_page=100', resolver)
        self.assertIn('test "${DRAFT_MATCH_COUNT}" -le 1', resolver)
        self.assertIn('test "${RELEASE_DRAFT}" = "true"', resolver)
        self.assertIn('test "${RELEASE_TARGET}" = "${EVENT_TARGET_SHA}"', resolver)
        self.assertIn(
            'emit_state true true true "${RELEASE_ID}" "${TAG_SHA}"', resolver
        )
        self.assertIn("RELEASE_READY=", release)
        self.assertIn("ACCEPTED_SOURCE_HEAD=", release)
        self.assertIn("ACCEPTED_JAR_SHA=", release)
        self.assertIn('test "${RELEASE_READY}" = "APPROVED"', release)
        self.assertIn('test "${ACCEPTED_SOURCE_HEAD}" = "${TARGET_SHA}"', release)
        self.assertIn('test "${ACCEPTED_JAR_SHA}" = "${JAR_SHA}"', release)

    def assert_verified_release_publication(self, release):
        self.assertIn("gh run download", release)
        self.assertIn("wp04-verification-${TARGET_SHA}", release)
        self.assertIn('ref="refs/tags/${FINAL_TAG}"', release)
        self.assertIn('sha="${TARGET_SHA}"', release)
        self.assertIn('--target "${TARGET_SHA}"', release)
        self.assertIn("Reset interrupted draft release", release)
        self.assertIn("DRAFT_RELEASE_ID: ${{ steps.state.outputs.release_id }}", release)
        self.assertIn(
            'gh api --method DELETE "repos/${GITHUB_REPOSITORY}/releases/${DRAFT_RELEASE_ID}"',
            release,
        )
        self.assertIn("Create draft production release from verified CI bundle", release)
        self.assertIn("--draft", release)
        self.assertIn("Verify exact release candidate assets", release)
        self.assertIn('cmp "${BUNDLE}/${asset}" "${RELEASED_ASSETS}/${asset}"', release)
        self.assertIn('compare/${TAG_SHA}...${TARGET_SHA}', release)
        self.assertIn('test "${MERGE_BASE}" = "${TAG_SHA}"', release)
        self.assertIn('test "${PUBLISHED_SOURCE}" = "${TAG_SHA}"', release)
        self.assertIn('test "${PUBLISHED_JAR_SHA}" = "${CURRENT_JAR_SHA}"', release)
        self.assertIn('echo "published_equivalent=true"', release)
        self.assertIn("Publish verified draft release", release)
        self.assertIn('releases?per_page=100', release)
        self.assertIn('test "${MATCH_COUNT}" -eq 1', release)
        self.assertIn(
            'gh api --method PATCH "repos/${GITHUB_REPOSITORY}/releases/${RELEASE_ID}"',
            release,
        )
        self.assertIn("-F draft=false", release)
        self.assertIn("-F prerelease=false", release)
        self.assertNotIn("--prerelease", release)
        self.assertNotIn("gradle --no-daemon", release)
        for asset in self.production_release_assets():
            self.assertIn(asset, release)

    def assert_ci_release_provenance(self, ci, strict_semver):
        self.assertIn("STATIC_RELEASE_READY=", ci)
        self.assertIn("release_ready: %s", ci)
        self.assertIn("release_source_head: %s", ci)
        self.assertIn("release_jar_sha256: %s", ci)
        self.assertIn(strict_semver, ci)
        self.assertIn('git show "${RELEASE_SOURCE_HEAD}:gradle.properties"', ci)
        self.assertIn(
            'git show "${RELEASE_SOURCE_HEAD}:docs/releases/v${RELEASE_VERSION}.md"', ci
        )
        self.assertIn(
            'git show "${RELEASE_SOURCE_HEAD}:docs/releases/v${RELEASE_VERSION}-rollback.md"', ci
        )
        self.assertIn(
            'git show "${RELEASE_SOURCE_HEAD}:docs/wp-05-acceptance/index.md"', ci
        )
        self.assertIn(
            "cp /tmp/release-source/release-notes.md /tmp/rc-first/release-notes.md", ci
        )
        self.assertIn(
            "cp /tmp/release-source/rollback-instructions.md "
            "/tmp/rc-first/rollback-instructions.md",
            ci,
        )
        self.assertIn(
            "cp /tmp/release-source/acceptance-index.md /tmp/rc-first/acceptance-index.md", ci
        )
        self.assertNotIn('cp "docs/releases/v${RELEASE_VERSION}.md"', ci)
        self.assertNotIn('cp "docs/releases/v${RELEASE_VERSION}-rollback.md"', ci)
        self.assertNotIn('test "${RELEASE_VERSION}" = "1.0.0"', ci)
        self.assertIn('--version "${RELEASE_VERSION}"', ci)
        self.assertIn("Verify release publication-state behavior", ci)

    @staticmethod
    def production_release_assets():
        return [
            "EnthusiaLoreItems.jar",
            "EnthusiaLoreItems.jar.sha256",
            "bom.cyclonedx.json",
            "gradle-dependencies.txt",
            "normalized-entry-manifest.txt",
            "wp04-profile.json",
            "EnthusiaLoreItems-test-reports.tar.gz",
            "acceptance-index.md",
            "rollback-instructions.md",
        ]

    def test_patch_release_notes_and_rollback_are_present(self):
        properties = (ROOT / "gradle.properties").read_text()
        version = re.search(r"^releaseVersion=(.+)$", properties, re.MULTILINE)
        self.assertIsNotNone(version)
        for source in [
            "docs/releases/v1.0.1.md",
            "docs/releases/v1.0.1-rollback.md",
            f"docs/releases/v{version.group(1)}.md",
            f"docs/releases/v{version.group(1)}-rollback.md",
            f"docs/releases/v{version.group(1)}-approval.txt",
        ]:
            self.assertTrue((ROOT / source).is_file(), source)

    def test_rc_workflow_recovers_only_a_verified_partial_tag(self):
        release = (ROOT / ".github/workflows/release-rc.yml").read_text()
        self.assertIn("compare/${TAG_SHA}...main", release)
        self.assertIn("head_sha=${TAG_SHA}&event=push&status=success", release)
        self.assertIn('.name == "CI" and .head_branch == "main"', release)
        self.assertIn("tag_exists=true", release)
        self.assertIn("steps.state.outputs.tag_exists != 'true'", release)

    def test_release_artifact_preparer_requires_explicit_version(self):
        preparer = (ROOT / "tools/prepare_rc_artifacts.py").read_text()
        self.assertIn('parser.add_argument("--version", required=True)', preparer)
        self.assertIn("release jar missing required entries", preparer)
        self.assertIn("plugin.yml release version mismatch", preparer)

    def test_profile_harness_declares_every_fixed_scenario(self):
        profile = (ROOT / "tools/wp04_profile.py").read_text()
        for token in [
            "PLAYERS = 100",
            "TRACKED_INSTANCES = 25_000",
            "SCOPES = 5_000",
            "PENDING_MUTATIONS = 10_000",
            "CAMPAIGNS = 10",
            "RECIPIENTS_PER_CAMPAIGN = 2_000",
            "ADMIN_QUERIES = 100",
        ]:
            self.assertIn(token, profile)


if __name__ == "__main__":
    unittest.main()
