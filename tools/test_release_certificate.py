import unittest
from release_update import verified_certificate


class VerifiedCertificateTest(unittest.TestCase):
    def modern(self):
        return ("Verifies\nVerified using v3 scheme (APK Signature Scheme v3): true\n"
                "Number of signers: 1\nV3.0 Signer: certificate DN: CN=Synthetic\n"
                "V3.0 Signer: certificate SHA-256 digest: " + "a" * 64 + "\n")

    def test_observed_build_tools_37_single_v3_report_is_accepted(self):
        self.assertEqual("a" * 64, verified_certificate(self.modern()))

    def test_duplicate_missing_false_or_unknown_modern_signer_is_rejected(self):
        baseline = self.modern()
        for report in (baseline.replace("Number of signers: 1", "Number of signers: 2"),
                       baseline.replace("Number of signers: 1\n", ""),
                       baseline.replace(": true", ": false"),
                       baseline.replace("V3.0 Signer:", "V3.1 Signer:"),
                       baseline + "V3.0 Signer: certificate SHA-256 digest: " + "a" * 64 + "\n",
                       baseline + "Signer #1 certificate SHA-256 digest: " + "b" * 64 + "\n"):
            with self.subTest(report=report), self.assertRaises(ValueError):
                verified_certificate(report)

    def test_legacy_single_signer_remains_supported_without_adopting_another_index(self):
        self.assertEqual("b" * 64, verified_certificate("Signer #1 certificate SHA-256 digest: " + "B" * 64 + "\n"))
        for report in ("Signer #2 certificate SHA-256 digest: " + "b" * 64 + "\n",
                       "Number of signers: 2\nSigner #1 certificate SHA-256 digest: " + "b" * 64 + "\n",
                       "DO NOT SHIP\nSigner #1 certificate SHA-256 digest: " + "b" * 64 + "\n"):
            with self.assertRaises(ValueError):
                verified_certificate(report)
