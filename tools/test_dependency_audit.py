import tempfile
from pathlib import Path
import unittest

from dependency_audit import advisories, inventory


class DependencyAuditTest(unittest.TestCase):
    def test_missing_locks_cannot_be_reported_as_a_clean_inventory(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                inventory(Path(folder))

    def test_versions_are_kept_separate_and_configurations_are_merged(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for module in ("app", "auth", "sync", "proton-auth"):
                (root / module).mkdir()
                (root / module / "gradle.lockfile").write_text(
                    "# Gradle lock\na:b:1=compile,runtime\na:b:2=test\nempty=other\n", encoding="utf-8")
            (root / "buildscript-gradle.lockfile").write_text("a:b:1=classpath\n", encoding="utf-8")
            packages = inventory(root)
            self.assertEqual(["1", "2"], [p["version"] for p in packages])
            self.assertEqual(9, len(packages[0]["configurations"]))

    def test_missing_buildscript_lock_cannot_hide_execution_dependencies(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for module in ("app", "auth", "sync", "proton-auth"):
                (root / module).mkdir()
                (root / module / "gradle.lockfile").write_text("a:b:1=runtime\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                inventory(root)

    def test_truncated_batch_is_an_error_instead_of_no_matches(self):
        with self.assertRaises(ValueError):
            advisories([{"name": "a:b", "version": "1"}], lambda *_: {"results": []})

    def test_all_advisory_pages_are_consumed_without_losing_package_identity(self):
        calls = []
        def fetch(url, body):
            calls.append((url, body))
            return {"results": [{"vulns": [{"id": "A"}], "next_page_token": "next"}]} if len(calls) == 1 else {
                "vulns": [{"id": "B"}, {"id": "A"}]}
        result = advisories([{"name": "a:b", "version": "1"}], fetch)
        self.assertEqual([{"name": "a:b", "version": "1", "advisories": ["A", "B"]}], result)
        self.assertEqual("next", calls[1][1]["page_token"])

    def test_service_error_inside_a_batch_cannot_be_reported_as_no_matches(self):
        with self.assertRaises(ValueError):
            advisories([{"name": "a:b", "version": "1"}],
                       lambda *_: {"results": [{"error": "unavailable"}]})

    def test_service_error_during_pagination_cannot_hide_partial_results(self):
        responses = iter([{"results": [{"next_page_token": "next"}]}, {"error": "unavailable"}])
        with self.assertRaises(ValueError):
            advisories([{"name": "a:b", "version": "1"}], lambda *_: next(responses))

    def test_malformed_result_shapes_are_errors_instead_of_clean_answers(self):
        for result in (None, [], {"vulns": {}}, {"vulns": [{"id": ""}]},
                       {"vulns": [{"id": 12}]}, {"next_page_token": 12}):
            with self.subTest(result=result), self.assertRaises(ValueError):
                advisories([{"name": "a:b", "version": "1"}], lambda *_: {"results": [result]})


if __name__ == "__main__":
    unittest.main()
