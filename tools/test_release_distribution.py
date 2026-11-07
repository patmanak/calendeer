import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from release_notices import check, compatible, embedded_notices, sha
from release_update import APPLICATION_ID, inspect, preflight, update


class ReleaseUpdateTest(unittest.TestCase):
    def test_wrong_signer_and_nonincreasing_version_are_rejected(self):
        baseline = {"certificateSha256": "a" * 64, "versionCode": 2}
        for candidate in ({"certificateSha256": "b" * 64, "versionCode": 3},
                          {"certificateSha256": "a" * 64, "versionCode": 2}):
            with self.assertRaises(ValueError):
                preflight(baseline, candidate, "a" * 64)

    def test_debuggable_or_unsigned_apk_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / "app.apk"
            apk.write_bytes(b"fixture")
            def command(argv):
                return ("Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n" if "verify" in argv else
                        f"package: name='{APPLICATION_ID}' versionCode='2' versionName='1.0-rc1'\napplication-debuggable\n")
            with self.assertRaises(ValueError):
                inspect(apk, "signer", "aapt", command)
            with self.assertRaises(ValueError):
                inspect(apk, "signer", "aapt", lambda _: "unsigned")

    def test_mismatched_installed_baseline_never_installs_or_clears(self):
        with tempfile.TemporaryDirectory() as folder:
            baseline = Path(folder) / "baseline.apk"
            baseline.write_bytes(b"expected")
            calls = []
            def command(argv, env=None):
                calls.append(argv)
                if argv == ["witness"]:
                    return json.dumps({"authenticated": True, **{key: "a" * 64 for key in
                                        ("session", "selections", "providerRows", "durableHistory")}})
                if "path" in argv:
                    return "package:synthetic-base.apk\n"
                if "pull" in argv:
                    Path(argv[-1]).write_bytes(b"different")
                return ""
            with self.assertRaises(ValueError):
                update(baseline, "candidate.apk", "synthetic", "adb", ["witness"], command)
            self.assertFalse(any("install" in argv or "clear" in argv or "uninstall" in argv for argv in calls))

    def test_update_preserves_state_and_uses_replace_only(self):
        with tempfile.TemporaryDirectory() as folder:
            baseline = Path(folder) / "baseline.apk"
            baseline.write_bytes(b"expected")
            candidate = Path(folder) / "candidate.apk"
            candidate.write_bytes(b"candidate")
            calls = []
            def command(argv, env=None):
                calls.append(argv)
                if argv == ["witness"]:
                    return json.dumps({"authenticated": True, **{key: "a" * 64 for key in
                                        ("session", "selections", "providerRows", "durableHistory")}})
                if "path" in argv:
                    return "package:synthetic-base.apk\n"
                if "pull" in argv:
                    Path(argv[-1]).write_bytes(b"candidate" if "install" in [item for call in calls for item in call] else b"expected")
                return ""
            update(baseline, candidate, "synthetic", "adb", ["witness"], command)
            self.assertIn(["adb", "-s", "synthetic", "install", "-r", candidate], calls)
            self.assertFalse(any("clear" in argv or "uninstall" in argv or "-d" in argv for argv in calls))
            self.assertEqual(2, calls.count(["witness"]))


class NoticeTest(unittest.TestCase):
    def test_extracts_nested_library_notices_without_copying_code(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as archive:
            archive.writestr("META-INF/LICENSE", b"license text")
            archive.writestr("META-INF/Parser-ThirdParty-LICENSE", b"shaded code notice")
            archive.writestr("annotations/License.class", b"not a license")
            archive.writestr("Example.class", b"code")
        outer = io.BytesIO()
        with zipfile.ZipFile(outer, "w") as archive:
            archive.writestr("classes.jar", nested.getvalue())
            archive.writestr("NOTICE.txt", b"notice")
        self.assertEqual([("classes.jar!/META-INF/LICENSE", b"license text"),
                          ("classes.jar!/META-INF/Parser-ThirdParty-LICENSE", b"shaded code notice"),
                          ("NOTICE.txt", b"notice")],
                         embedded_notices(outer.getvalue()))

    def test_separate_compatible_component_terms_are_retained_but_unknown_terms_close_gate(self):
        self.assertTrue(compatible("Apache-2.0 AND MIT AND BSL-1.0 AND BSD-2-Clause"))
        self.assertTrue(compatible("Apache-2.0 AND MPL-2.0"))
        self.assertFalse(compatible("BSD-3-Clause AND CC-BY-2.5"))
        self.assertFalse(compatible("MIT OR Proprietary"))

    def test_stale_incomplete_and_missing_notice_reviews_fail_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for module in ("app", "auth", "sync", "proton-auth"):
                (root / module).mkdir()
                (root / module / "gradle.lockfile").write_text("a:b:1=releaseRuntimeClasspath\n", encoding="utf-8")
            (root / "buildscript-gradle.lockfile").write_text("a:b:1=classpath\n", encoding="utf-8")
            (root / "gradle").mkdir()
            (root / "gradle/verification-metadata.xml").write_text("<fixture/>", encoding="utf-8")
            manifest = root / "notices.json"
            manifest.write_text(json.dumps({"schemaVersion": 1, "scope": "app:releaseRuntimeClasspath",
                "lockSha256": "stale", "packages": [{"coordinate": "a:b:1", "reviewed": False,
                    "notices": [{"file": "missing.txt", "sha256": sha(b"notice")}]}]}), encoding="utf-8")
            problems = check(root, manifest)
            self.assertTrue(any("stale" in item for item in problems))
            self.assertTrue(any("missing/changed notice" in item for item in problems))
            self.assertTrue(any("compatibility review incomplete" in item for item in problems))
            self.assertTrue(any("Native" in item for item in problems))
            # A human approval marker cannot silently admit unsupported terms.
            report = json.loads(manifest.read_text(encoding="utf-8"))
            report["packages"][0].update(reviewed=True, licenseExpression="GPL-2.0-only", reviewEvidence="fixture")
            manifest.write_text(json.dumps(report), encoding="utf-8")
            self.assertTrue(any("compatibility policy review" in item for item in check(root, manifest)))


if __name__ == "__main__":
    unittest.main()
