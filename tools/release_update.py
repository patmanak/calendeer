"""Verify a signed APK pair and perform a bounded update with private state witnesses.

Device execution must be enclosed by the owner's enrolled-target coordination harness.
No uninstall, downgrade, data clear, login or remote mutation is performed. The private
witness program must observe an existing session, selections, controlled Provider row
identities and durable request history; it must not expose their actual contents.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

APPLICATION_ID = "com.patmanak.proton.andcalendarbridge"
WITNESS_FIELDS = ("session", "selections", "providerRows", "durableHistory")


def run(argv, env=None):
    result = subprocess.run([str(value) for value in argv], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", env=env, timeout=180)
    if result.returncode:
        # Native output can contain a device identifier or private application state.
        raise ValueError("External verification command failed")
    return result.stdout


def verified_certificate(signature):
    """Accept the measured legacy and Build Tools 37 single-v3-signer outputs.

    Unknown schemes, lineage/mixed identities and ambiguous certificate reports
    stay closed. The caller must first obtain successful apksigner verification.
    """
    counts = re.findall(r"^Number of signers: ([0-9]+)$", signature, re.M)
    legacy = re.findall(r"^Signer #([0-9]+) certificate SHA-256 digest: ([0-9a-fA-F]{64})$", signature, re.M)
    modern = re.findall(r"^V3\.0 Signer: certificate SHA-256 digest: ([0-9a-fA-F]{64})$", signature, re.M)
    reported = sum("certificate SHA-256 digest:" in line for line in signature.splitlines())
    if ("DO NOT SHIP" in signature or counts and counts != ["1"] or
            reported != len(legacy) + len(modern)):
        raise ValueError("Exactly one verified release signer required")
    if len(legacy) == 1 and legacy[0][0] == "1" and not modern:
        return legacy[0][1].lower()
    if (len(modern) == 1 and not legacy and counts == ["1"] and
            "Verified using v3 scheme (APK Signature Scheme v3): true" in signature.splitlines()):
        return modern[0].lower()
    raise ValueError("Exactly one verified release signer required")


def inspect(apk, apksigner, aapt, execute=run):
    signature = execute([apksigner, "verify", "--verbose", "--print-certs", apk])
    digest = verified_certificate(signature)
    if re.search(r"CN\s*=\s*Android Debug(?:,|$)", signature, re.I | re.M):
        raise ValueError("Exactly one verified release signer required")
    badging = execute([aapt, "dump", "badging", apk])
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not match or match[1] != APPLICATION_ID or re.search(r"^application-debuggable", badging, re.M):
        raise ValueError("Expected non-debuggable Calendeer package")
    return {"certificateSha256": digest, "versionCode": int(match[2]),
            "versionName": match[3], "apkSha256": hashlib.sha256(Path(apk).read_bytes()).hexdigest()}


def preflight(baseline, candidate, expected_certificate):
    expected = expected_certificate.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("Expected public signing certificate SHA-256 required")
    if baseline["certificateSha256"] != expected or candidate["certificateSha256"] != expected:
        raise ValueError("Signing identity mismatch; existing session must not be reset")
    if candidate["versionCode"] <= baseline["versionCode"]:
        raise ValueError("Update versionCode must increase")


def witness(command, serial, execute=run):
    environment = dict(os.environ, CALENDEER_TEST_SERIAL=serial)
    state = json.loads(execute(command, env=environment))
    if state.get("authenticated") is not True or set(state) != {"authenticated", *WITNESS_FIELDS}:
        raise ValueError("Incomplete authenticated state witness")
    if any(not isinstance(state[field], str) or not re.fullmatch(r"[0-9a-f]{64}", state[field]) for field in WITNESS_FIELDS):
        raise ValueError("Witnesses must be opaque SHA-256 fingerprints")
    return state


def update(baseline, candidate, serial, adb, witness_command, execute=run):
    before = witness(witness_command, serial, execute)
    package = execute([adb, "-s", serial, "shell", "pm", "path", APPLICATION_ID]).strip().splitlines()
    if len(package) != 1 or not package[0].startswith("package:"):
        raise ValueError("Expected one existing base APK; split installations need separate qualification")
    with tempfile.TemporaryDirectory(prefix="calendeer-update-") as folder:
        installed = Path(folder) / "installed.apk"
        execute([adb, "-s", serial, "pull", package[0][len("package:"):], installed])
        if hashlib.sha256(installed.read_bytes()).hexdigest() != hashlib.sha256(Path(baseline).read_bytes()).hexdigest():
            raise ValueError("Installed APK differs from qualified signed baseline")
    execute([adb, "-s", serial, "install", "-r", candidate])
    installed_path = execute([adb, "-s", serial, "shell", "pm", "path", APPLICATION_ID]).strip().splitlines()
    if len(installed_path) != 1 or not installed_path[0].startswith("package:"):
        raise ValueError("Updated package inventory is invalid")
    with tempfile.TemporaryDirectory(prefix="calendeer-update-") as folder:
        installed = Path(folder) / "updated.apk"
        execute([adb, "-s", serial, "pull", installed_path[0][len("package:"):], installed])
        if hashlib.sha256(installed.read_bytes()).hexdigest() != hashlib.sha256(Path(candidate).read_bytes()).hexdigest():
            raise ValueError("Installed candidate byte verification failed; preserve state for inspection")
    # Force a new application process; retain all app/account/Provider data.
    execute([adb, "-s", serial, "shell", "am", "force-stop", APPLICATION_ID])
    execute([adb, "-s", serial, "shell", "am", "start", "-W", "-n", APPLICATION_ID + "/.MainActivity"])
    if witness(witness_command, serial, execute) != before:
        raise ValueError("Update state witness changed; inspect retained state without reset or downgrade")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--certificate-sha256", required=True)
    parser.add_argument("--execute-update", action="store_true")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--witness-command", nargs="+", help="Private program returning only the documented JSON witness")
    args = parser.parse_args()
    try:
        baseline = inspect(args.baseline, args.apksigner, args.aapt)
        candidate = inspect(args.candidate, args.apksigner, args.aapt)
        preflight(baseline, candidate, args.certificate_sha256)
        if args.execute_update:
            serial = os.environ.get("CALENDEER_TEST_SERIAL")
            if not serial or not args.witness_command:
                raise ValueError("Enrolled target environment and private witness command required")
            update(args.baseline, args.candidate, serial, args.adb, args.witness_command)
        print(json.dumps({"baseline": baseline, "candidate": candidate,
                          "signedUpdate": "PASS" if args.execute_update else "NOT_RUN"}, indent=2))
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print("Signed update: BLOCKED/FAILED; inspect private prerequisites and retained state")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
