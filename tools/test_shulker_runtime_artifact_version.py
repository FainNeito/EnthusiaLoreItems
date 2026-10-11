"""Keep Paper runtime acceptance tied to the source-controlled release version."""

from pathlib import Path
import unittest

WORKFLOW = Path(__file__).resolve().parent.parent / ".github/workflows/shulker-runtime-acceptance.yml"


class PaperRuntimeArtifactVersionTest(unittest.TestCase):
    def test_uses_source_controlled_version_for_both_jars(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("sed -n 's/^releaseVersion=//p'", source)
        self.assertIn("plugin-${RELEASE_VERSION}.jar", source)
        self.assertIn("acceptance-harness-${RELEASE_VERSION}.jar", source)
        self.assertNotRegex(source, r"(?:plugin|acceptance-harness)-\d+\.\d+\.\d+\.jar")

    def test_rejects_nonsemantic_version(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(r'[[ "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]', source)


if __name__ == "__main__":
    unittest.main()
