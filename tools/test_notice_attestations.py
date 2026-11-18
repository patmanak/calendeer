import copy
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from release_notices import bundle, capture, check, evidence_digest, sha


class NoticeAttestationTest(unittest.TestCase):
    def fixture(self, folder):
        root = Path(folder)
        coordinate = "com.fasterxml.jackson.core:jackson-core:2.18.11"
        for module in ("app", "auth", "sync", "proton-auth"):
            (root / module).mkdir()
            (root / module / "gradle.lockfile").write_text(coordinate + "=releaseRuntimeClasspath\n", encoding="utf-8")
        (root / "buildscript-gradle.lockfile").write_text(coordinate + "=classpath\n", encoding="utf-8")
        (root / "gradle").mkdir()
        verification = root / "gradle/verification-metadata.xml"
        verification.write_text("<verification-metadata/>", encoding="utf-8")
        for filename, text in (("NOTICE.txt", "Copyright fixture author"), ("MIT.txt", "MIT fixture grant")):
            (root / filename).write_text(text, encoding="utf-8")
        package = {"coordinate": coordinate, "reviewed": True, "licenseExpression": "Apache-2.0 AND MIT",
                   "pomSha256": "a" * 64, "reviewEvidence": {"source": "versioned source fixture"}, "problems": [],
                   "artifacts": [{"name": "jackson-core-2.18.11.jar", "sha256": "b" * 64}],
                   "notices": [{"file": name, "sha256": sha((root / name).read_bytes())}
                               for name in ("NOTICE.txt", "MIT.txt")]}
        package["reviewAttestation"] = {"sha256": evidence_digest(package), "basis": "fixture source review"}
        native = {"reviewed": False, "dependencies": [{"coordinate": "example@v1", "reviewed": False}], "notices": []}
        native["inventorySha256"] = evidence_digest(native)
        report = {"schemaVersion": 1, "scope": "app:releaseRuntimeClasspath",
                  "lockSha256": sha((root / "app/gradle.lockfile").read_bytes()),
                  "verificationMetadataSha256": sha(verification.read_bytes()),
                  "packages": [package], "nativeSourceReview": native}
        manifest = root / "notices.json"
        manifest.write_text(json.dumps(report), encoding="utf-8")
        return root, manifest, report

    def test_deleting_jackson_notice_or_artifact_list_invalidates_existing_review(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, report = self.fixture(folder)
            self.assertFalse(any("changed or is unbound" in p for p in check(root, manifest)))
            for field in ("notices", "artifacts"):
                changed = copy.deepcopy(report)
                changed["packages"][0][field] = []
                manifest.write_text(json.dumps(changed), encoding="utf-8")
                self.assertTrue(any("reviewed evidence/notice inventory changed or is unbound" in p
                                    for p in check(root, manifest)), field)

    def test_bundle_has_identical_utf8_lf_bytes_on_all_hosts(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, report = self.fixture(folder)
            package = report["packages"][0]
            for notice in package["notices"]:
                notice["origin"] = "synthetic-source!/" + notice["file"]
            package["reviewAttestation"]["sha256"] = evidence_digest(package)
            manifest.write_text(json.dumps(report), encoding="utf-8")
            destination = root / "bundled.txt"
            bundle(root, manifest, destination)
            first = destination.read_bytes()
            self.assertNotIn(b"\r\n", first)
            self.assertIn(b"Copyright fixture author", first)
            self.assertIn(b"MIT fixture grant", first)
            self.assertIn(b"BLOCKED", first)
            bundle(root, manifest, destination)
            self.assertEqual(first, destination.read_bytes())

    def test_native_dependency_deletion_invalidates_captured_graph_even_while_unreviewed(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, report = self.fixture(folder)
            self.assertFalse(any("differs from its captured source evidence" in p for p in check(root, manifest)))
            report["nativeSourceReview"]["dependencies"] = []
            manifest.write_text(json.dumps(report), encoding="utf-8")
            self.assertTrue(any("differs from its captured source evidence" in p for p in check(root, manifest)))

    def test_rebinding_an_empty_artifact_list_cannot_substitute_for_metadata_only_review(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, report = self.fixture(folder)
            package = report["packages"][0]
            package["artifacts"] = []
            package["reviewAttestation"]["sha256"] = evidence_digest(package)
            manifest.write_text(json.dumps(report), encoding="utf-8")
            self.assertTrue(any("missing artifact or verified metadata-only review" in p for p in check(root, manifest)))

    def test_completed_native_review_also_binds_full_inventory(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, report = self.fixture(folder)
            notice = report["packages"][0]["notices"][0]
            dependency = {"coordinate": "example@v1", "reviewed": True, "licenseExpression": "MIT",
                          "reviewEvidence": "versioned source fixture", "notices": [notice]}
            dependency["reviewAttestation"] = {"sha256": evidence_digest(dependency), "basis": "fixture source review"}
            native = {"reviewed": True, "sourceRevision": "fixture-revision", "buildMapping": "fixture mapping",
                      "reviewEvidence": "fixture source review", "notices": [notice], "dependencies": [dependency]}
            native["inventorySha256"] = evidence_digest(native)
            native["reviewAttestation"] = {"sha256": evidence_digest(native), "basis": "fixture full native review"}
            report["nativeSourceReview"] = native
            manifest.write_text(json.dumps(report), encoding="utf-8")
            self.assertFalse(any("Native" in p for p in check(root, manifest)))
            native["dependencies"] = []
            manifest.write_text(json.dumps(report), encoding="utf-8")
            self.assertTrue(any("Native reviewed evidence/notice inventory changed" in p for p in check(root, manifest)))

    def test_capture_marks_missing_binary_artifact_as_incomplete(self):
        with tempfile.TemporaryDirectory() as folder:
            root, manifest, _ = self.fixture(folder)
            cache = root / "cache"
            captured = capture(root, cache, root / "captured.json")
            self.assertNotIn(b"\r\n", (root / "captured.json").read_bytes())
            package = captured["packages"][0]
            self.assertFalse(package["reviewed"])
            self.assertIn("no-release-artifact-explicit-verified-metadata-only-review-required", package["problems"])

    def test_capture_reads_source_built_native_from_local_repository_and_retains_patents(self):
        with tempfile.TemporaryDirectory() as folder:
            root, _, _ = self.fixture(folder)
            version = "2.10.0-2-go1.27.1-16k-linux2"
            group = "com.patmanak.calendeer.crypto"
            coordinate = f"{group}:android-golib:{version}"
            for module in ("app", "auth", "sync", "proton-auth"):
                (root / module / "gradle.lockfile").write_text(coordinate + "=releaseRuntimeClasspath\n")
            repository = root / "proton-auth/native/build/maven" / group.replace(".", "/") / "android-golib" / version
            repository.mkdir(parents=True)
            pom = repository / f"android-golib-{version}.pom"
            pom.write_bytes(b'<project xmlns="http://maven.apache.org/POM/4.0.0"><packaging>aar</packaging>'
                            b'<licenses><license><name>GPLv3</name></license></licenses></project>')
            aar = pom.with_suffix(".aar")
            with zipfile.ZipFile(aar, "w") as archive:
                archive.writestr("legal/Go/PATENTS", b"Fixture patent grant")
                archive.writestr("legal/saferith/LICENSE_go", b"Fixture BSD grant")
            artifacts = "".join(f'<artifact name="{file.name}"><sha256 value="{sha(file.read_bytes())}"/></artifact>'
                                for file in (pom, aar))
            (root / "gradle/verification-metadata.xml").write_text(
                f'<verification-metadata><components><component group="{group}" name="android-golib" '
                f'version="{version}">{artifacts}</component></components></verification-metadata>')
            captured = capture(root, root / "empty-cache", root / "captured.json")
            entry = captured["packages"][0]
            self.assertEqual([], entry["problems"])
            self.assertEqual(sha(aar.read_bytes()), entry["artifacts"][0]["sha256"])
            self.assertEqual({"Fixture patent grant", "Fixture BSD grant"},
                             {(root / notice["file"]).read_text() for notice in entry["notices"]})
            self.assertFalse(entry["reviewed"])


if __name__ == "__main__":
    unittest.main()
