"""Sign an already built unsigned APK without giving signing credentials to Gradle.

Passwords reach only the offline apksigner child environment, through its env:
password references. No plaintext password enters argv, files, output or reports.
No Gradle process, device command, key generation or publication is performed.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

from release_update import APPLICATION_ID, inspect

PRIVATE_FIELDS = ("CALENDEER_SIGN_RELEASE", "CALENDEER_KEYSTORE", "CALENDEER_KEYSTORE_PASSWORD",
                  "CALENDEER_KEY_ALIAS", "CALENDEER_KEY_PASSWORD", "CALENDEER_CERTIFICATE_SHA256")
PASSWORD_FIELDS = ("CALENDEER_KEYSTORE_PASSWORD", "CALENDEER_KEY_PASSWORD")


def sanitized_environment(environment):
    return {name: value for name, value in environment.items() if name not in PRIVATE_FIELDS}


def configuration(environment, root, certificate):
    if environment.get("CALENDEER_SIGN_RELEASE") != "true":
        raise ValueError("Explicit private-signing opt-in required")
    required = (*PRIVATE_FIELDS[1:5], "CALENDEER_VERSION_CODE", "CALENDEER_VERSION_NAME")
    if any(not environment.get(name, "").strip() for name in required):
        raise ValueError("Private signing and explicit version fields required")
    code = environment["CALENDEER_VERSION_CODE"]
    name = environment["CALENDEER_VERSION_NAME"]
    if not re.fullmatch(r"[0-9]+", code) or not 1 <= int(code) <= 2100000000:
        raise ValueError("Invalid release version code")
    if not re.fullmatch(r"[0-9][0-9A-Za-z.+-]{0,63}", name):
        raise ValueError("Invalid release version name")
    store = Path(environment["CALENDEER_KEYSTORE"]).resolve(strict=True)
    if not store.is_file() or store.is_relative_to(root.resolve()):
        raise ValueError("Existing private keystore outside the project required")
    if environment["CALENDEER_KEY_ALIAS"].lower() == "androiddebugkey":
        raise ValueError("Debug signing identity is not a release key")
    certificate = certificate.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", certificate):
        raise ValueError("Expected public release certificate SHA-256 required")
    return {"store": store, "alias": environment["CALENDEER_KEY_ALIAS"],
            "versionCode": int(code), "versionName": name, "certificateSha256": certificate}


def unsigned_apk(apk):
    data = apk.read_bytes()
    end = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    if end < 0 or end + 22 > len(data):
        raise ValueError("Invalid APK ZIP end record")
    comment = struct.unpack_from("<H", data, end + 20)[0]
    if end + 22 + comment != len(data):
        raise ValueError("Invalid APK ZIP length")
    central = struct.unpack_from("<I", data, end + 16)[0]
    if central > end or data[max(0, central - 16):central] == b"APK Sig Block 42":
        raise ValueError("Already signed or invalid APK")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or "AndroidManifest.xml" not in names or "classes.dex" not in names:
            raise ValueError("Expected unsigned application APK")
        if any(re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", name, re.I) for name in names):
            raise ValueError("Existing JAR signature is not an unsigned release input")


def process(argv, env):
    # SDK's Windows launcher is a batch file. Invoke its JAR directly so private
    # aliases/paths are Java arguments rather than command-interpreter text.
    if os.name == "nt" and str(argv[0]).lower().endswith((".bat", ".cmd")):
        launcher = Path(argv[0]).resolve(strict=True)
        jar = launcher.parent / "lib/apksigner.jar"
        if launcher.name.lower() != "apksigner.bat" or not jar.is_file():
            raise ValueError("Expected SDK apksigner launcher/JAR")
        java = (Path(env["JAVA_HOME"]) / "bin/java.exe") if env.get("JAVA_HOME") else shutil.which("java", path=env.get("PATH"))
        if not java or not Path(java).is_file():
            raise ValueError("JDK Java executable required for offline signer")
        argv = [java, "-jar", jar, *argv[1:]]
    return subprocess.run([str(value) for value in argv], env=env, capture_output=True,
                          text=True, encoding="utf-8", errors="replace", timeout=180)


def sign(source, destination, root, apksigner, aapt, certificate, environment=None, execute=process):
    environment = dict(os.environ if environment is None else environment)
    config = configuration(environment, root, certificate)
    source = source.resolve(strict=True)
    destination = destination.resolve()
    if not source.is_file() or source == destination or destination.exists():
        raise ValueError("Distinct existing unsigned input and fresh output required")
    unsigned_apk(source)
    public_environment = sanitized_environment(environment)

    def public_command(argv):
        result = execute(argv, env=public_environment)
        if result.returncode:
            raise ValueError("Public APK verification command failed")
        if "verify" in argv and re.search(r"CN\s*=\s*Android Debug(?:,|$)", result.stdout, re.I | re.M):
            raise ValueError("Debug signing certificate is not a release identity")
        return result.stdout

    badging = public_command([aapt, "dump", "badging", source])
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if (not package or package[1] != APPLICATION_ID or int(package[2]) != config["versionCode"] or
            package[3] != config["versionName"] or re.search(r"^application-debuggable", badging, re.M)):
        raise ValueError("Unsigned APK must match non-debuggable Calendeer release identity/version")
    destination.parent.mkdir(parents=True, exist_ok=True)
    # The only child that receives these password variables is offline apksigner.
    signing_environment = dict(public_environment)
    signing_environment.update({name: environment[name] for name in PASSWORD_FIELDS})
    argv = [apksigner, "sign", "--ks", config["store"], "--ks-key-alias", config["alias"],
            "--ks-pass", "env:CALENDEER_KEYSTORE_PASSWORD", "--key-pass", "env:CALENDEER_KEY_PASSWORD",
            "--v4-signing-enabled", "false", "--out", destination, source]
    created = False
    try:
        result = execute(argv, env=signing_environment)
        created = destination.is_file()
        if result.returncode or not created:
            raise ValueError("Offline signing failed")
        verified = inspect(destination, apksigner, aapt, execute=public_command)
        if any(verified[field] != config[field] for field in ("certificateSha256", "versionCode", "versionName")):
            raise ValueError("Signed APK certificate/version mismatch")
        return verified
    except BaseException:
        # Only this call's fresh artifact may be removed; never touch input/key/data.
        if destination.is_file():
            destination.unlink(missing_ok=True)
        raise
    finally:
        for name in PASSWORD_FIELDS:
            signing_environment.pop(name, None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("unsigned_apk", type=Path)
    parser.add_argument("signed_apk", type=Path)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--certificate-sha256", required=True, help="Expected public release certificate digest")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    try:
        result = sign(args.unsigned_apk, args.signed_apk, args.root, args.apksigner, args.aapt, args.certificate_sha256)
        print(json.dumps(result, indent=2))
        return 0
    except (OSError, ValueError, KeyError, TypeError, struct.error, zipfile.BadZipFile, subprocess.SubprocessError):
        print("Offline release signing: BLOCKED/FAILED; private credentials and native diagnostics withheld")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
