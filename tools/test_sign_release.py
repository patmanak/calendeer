import os
from pathlib import Path
import shutil
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

from release_update import APPLICATION_ID
from sign_release import PASSWORD_FIELDS, PRIVATE_FIELDS, configuration, process, sign


class OfflineSigningTest(unittest.TestCase):
    @unittest.skipUnless(os.name == "nt", "Windows SDK batch-launcher boundary")
    def test_windows_signer_uses_java_jar_without_command_interpreter(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "sdk/lib").mkdir(parents=True)
            (root / "jdk/bin").mkdir(parents=True)
            launcher = root / "sdk/apksigner.bat"
            launcher.write_bytes(b"not executed")
            (root / "sdk/lib/apksigner.jar").write_bytes(b"synthetic jar fixture")
            java = root / "jdk/bin/java.exe"
            java.write_bytes(b"not executed")
            with patch("sign_release.subprocess.run") as native:
                process([launcher, "sign", "--ks-key-alias", "opaque&alias"], {"JAVA_HOME": str(root / "jdk")})
            argv = native.call_args.args[0]
            self.assertEqual(str(java), argv[0])
            self.assertEqual("-jar", argv[1])
            self.assertEqual("opaque&alias", argv[-1])
            self.assertFalse(any("cmd.exe" in arg.lower() or arg.lower().endswith(".bat") for arg in argv))

    def fixture(self, folder):
        parent = Path(folder)
        root = parent / "project"
        root.mkdir()
        store = parent / "synthetic-store.fixture"
        store.write_bytes(b"not a real key; fake process fixture")
        source = root / "unsigned.apk"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"synthetic compiled manifest")
            archive.writestr("classes.dex", b"synthetic dex")
        environment = {"PATH": "synthetic-path", "CALENDEER_SIGN_RELEASE": "true",
                       "CALENDEER_KEYSTORE": str(store), "CALENDEER_KEY_ALIAS": "release-fixture",
                       "CALENDEER_KEYSTORE_PASSWORD": "fixture-store-password",
                       "CALENDEER_KEY_PASSWORD": "fixture-key-password",
                       "CALENDEER_VERSION_CODE": "2", "CALENDEER_VERSION_NAME": "0.1.0-rc1"}
        return root, source, root / "signed.apk", environment

    def runner(self, calls, debug=False, certificate="a" * 64, sign_failure=False):
        def execute(argv, env):
            calls.append((list(argv), dict(env)))
            if "sign" in argv:
                destination = Path(argv[argv.index("--out") + 1])
                shutil.copyfile(argv[-1], destination)
                return SimpleNamespace(returncode=1 if sign_failure else 0, stdout="", stderr="private diagnostic")
            if "verify" in argv:
                return SimpleNamespace(returncode=0, stderr="", stdout=
                    "Signer #1 certificate SHA-256 digest: " + certificate + "\n")
            return SimpleNamespace(returncode=0, stderr="", stdout=
                f"package: name='{APPLICATION_ID}' versionCode='2' versionName='0.1.0-rc1'\n" +
                ("application-debuggable\n" if debug else ""))
        return execute

    def test_passwords_reach_only_signer_environment_and_never_arguments(self):
        with tempfile.TemporaryDirectory() as folder:
            root, source, destination, environment = self.fixture(folder)
            calls = []
            result = sign(source, destination, root, "apksigner", "aapt", "a" * 64,
                          environment, self.runner(calls))
            self.assertEqual("a" * 64, result["certificateSha256"])
            for argv, env in calls:
                self.assertNotIn(environment["CALENDEER_KEYSTORE_PASSWORD"], map(str, argv))
                self.assertNotIn(environment["CALENDEER_KEY_PASSWORD"], map(str, argv))
                if "sign" in argv:
                    self.assertEqual(set(PASSWORD_FIELDS), set(PRIVATE_FIELDS) & set(env))
                    self.assertIn("env:CALENDEER_KEYSTORE_PASSWORD", argv)
                    self.assertIn("env:CALENDEER_KEY_PASSWORD", argv)
                else:
                    self.assertFalse(set(PRIVATE_FIELDS) & set(env))
            self.assertFalse(any("gradle" in str(arg).lower() for argv, _ in calls for arg in argv))

    def test_partial_or_unapproved_signing_is_rejected_before_processes(self):
        with tempfile.TemporaryDirectory() as folder:
            root, _, _, environment = self.fixture(folder)
            for field in ("CALENDEER_SIGN_RELEASE", "CALENDEER_KEY_PASSWORD", "CALENDEER_VERSION_NAME"):
                partial = dict(environment)
                partial.pop(field)
                with self.assertRaises(ValueError):
                    configuration(partial, root, "a" * 64)

    def test_project_keystore_debug_alias_and_invalid_version_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root, source, _, environment = self.fixture(folder)
            for field, value in (("CALENDEER_KEYSTORE", str(source)),
                                 ("CALENDEER_KEY_ALIAS", "androiddebugkey"),
                                 ("CALENDEER_VERSION_CODE", "0"),
                                 ("CALENDEER_VERSION_NAME", "invalid/name")):
                candidate = dict(environment, **{field: value})
                with self.assertRaises(ValueError):
                    configuration(candidate, root, "a" * 64)

    def test_debuggable_input_never_reaches_signer(self):
        with tempfile.TemporaryDirectory() as folder:
            root, source, destination, environment = self.fixture(folder)
            calls = []
            with self.assertRaises(ValueError):
                sign(source, destination, root, "apksigner", "aapt", "a" * 64,
                     environment, self.runner(calls, debug=True))
            self.assertFalse(any("sign" in argv for argv, _ in calls))
            self.assertFalse(destination.exists())

    def test_compiled_version_mismatch_never_reaches_signer(self):
        with tempfile.TemporaryDirectory() as folder:
            root, source, destination, environment = self.fixture(folder)
            environment["CALENDEER_VERSION_CODE"] = "3"
            calls = []
            with self.assertRaises(ValueError):
                sign(source, destination, root, "apksigner", "aapt", "a" * 64,
                     environment, self.runner(calls))
            self.assertFalse(any("sign" in argv for argv, _ in calls))

    def test_existing_apk_signing_block_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            root, source, destination, environment = self.fixture(folder)
            data = source.read_bytes()
            end = data.rfind(b"PK\x05\x06")
            central = struct.unpack_from("<I", data, end + 16)[0]
            data = bytearray(data[:central] + b"APK Sig Block 42" + data[central:])
            struct.pack_into("<I", data, end + 16 + 16, central + 16)
            source.write_bytes(data)
            with self.assertRaises(ValueError):
                sign(source, destination, root, "apksigner", "aapt", "a" * 64,
                     environment, self.runner([]))

    def test_wrong_public_signer_or_sign_failure_removes_only_fresh_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root, source, destination, environment = self.fixture(folder)
            for options in ({"certificate": "b" * 64}, {"sign_failure": True}):
                with self.assertRaises(ValueError):
                    sign(source, destination, root, "apksigner", "aapt", "a" * 64,
                         environment, self.runner([], **options))
                self.assertTrue(source.is_file())
                self.assertFalse(destination.exists())

    def test_preexisting_output_and_v1_signed_input_are_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            root, source, destination, environment = self.fixture(folder)
            destination.write_bytes(b"retain existing artifact")
            with self.assertRaises(ValueError):
                sign(source, destination, root, "apksigner", "aapt", "a" * 64,
                     environment, self.runner([]))
            self.assertEqual(b"retain existing artifact", destination.read_bytes())
            destination.unlink()
            with zipfile.ZipFile(source, "a") as archive:
                archive.writestr("META-INF/EXISTING.RSA", b"signed fixture")
            with self.assertRaises(ValueError):
                sign(source, destination, root, "apksigner", "aapt", "a" * 64,
                     environment, self.runner([]))


if __name__ == "__main__":
    unittest.main()
