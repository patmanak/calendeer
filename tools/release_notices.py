"""Capture pinned release-runtime license evidence; reject incomplete distribution reviews.

POM declarations are evidence, not a license compatibility decision. No network or Gradle
resolution is performed. Native embedded dependencies require a separate source review.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

from dependency_audit import inventory

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
# Selected distribution terms, after human source review. Unknown expressions
# require an explicit tooling/policy review rather than a permissive default.
COMPATIBLE_LICENSES = {"Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC",
                       "GPL-3.0-only", "GPL-3.0-or-later", "GPL-2.0-or-later", "Zlib", "BSL-1.0", "MPL-2.0"}


def compatible(expression):
    # Explicit conjunctions preserve separate component terms. Unknown alternatives,
    # exceptions and license versions remain closed pending a policy review.
    return isinstance(expression, str) and all(term in COMPATIBLE_LICENSES for term in expression.split(" AND "))


def sha(data):
    return hashlib.sha256(data).hexdigest()


def evidence_digest(record):
    """Bind the complete reviewed record; this is an omission guard, not a signature."""
    content = {key: value for key, value in record.items()
               if key not in {"reviewed", "reviewAttestation", "inventorySha256"}}
    return sha(json.dumps(content, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))


def attestation_matches(record):
    attestation = record.get("reviewAttestation", {})
    return (isinstance(attestation, dict) and attestation.get("sha256") == evidence_digest(record) and
            bool(attestation.get("basis")))


def runtime_packages(root):
    return [p for p in inventory(root) if "app:releaseRuntimeClasspath" in p["configurations"]]


def pinned(root, group, name, version, filename, data):
    tree = ET.parse(root / "gradle/verification-metadata.xml")
    for component in tree.findall(".//{*}component"):
        if component.attrib == {"group": group, "name": name, "version": version}:
            for artifact in component.findall("{*}artifact"):
                if artifact.get("name") == filename:
                    return sha(data) in [entry.get("value") for entry in artifact.findall("{*}sha256")]
    return False


def embedded_notices(data, prefix="", depth=0):
    found = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for item in archive.infolist():
            if item.is_dir():
                continue
            name = item.filename
            # Include copyright/authors as well as conventional LICENSE and NOTICE.
            if (not name.lower().endswith((".class", ".java", ".kt", ".kotlin_metadata")) and
                    re.search(r"license|notice|copying|copyright|authors|third[-_]party", name.rsplit("/", 1)[-1], re.I)):
                if item.file_size > 4 * 1024 * 1024:
                    raise ValueError("Oversized embedded notice")
                found.append((prefix + name, archive.read(item)))
            elif depth == 0 and name.endswith(".jar"):
                if item.file_size > 64 * 1024 * 1024:
                    raise ValueError("Oversized nested JAR")
                found.extend(embedded_notices(archive.read(item), prefix + name + "!/", depth + 1))
    return found


def capture(root, cache, destination):
    packages = []
    texts = destination.parent / "dependency-notices"
    texts.mkdir(parents=True, exist_ok=True)
    for package in runtime_packages(root):
        group, name = package["name"].split(":")
        version = package["version"]
        directory = cache / group / name / version
        entry = {"coordinate": f"{group}:{name}:{version}", "licenses": [], "notices": [],
                 "reviewed": False, "problems": []}
        poms = list(directory.glob("*/*.pom"))
        if len(poms) != 1:
            entry["problems"].append("missing-or-ambiguous-pom")
        else:
            data = poms[0].read_bytes()
            if not pinned(root, group, name, version, poms[0].name, data):
                entry["problems"].append("pom-not-pinned-license-declaration-needs-upstream-verification")
            pom = ET.fromstring(data)
            entry["pomSha256"] = sha(data)
            entry["pomPackaging"] = pom.findtext("m:packaging", "jar", NS)
            entry["source"] = pom.findtext("m:url", "", NS)
            entry["licenses"] = [{"name": lic.findtext("m:name", "", NS),
                                  "url": lic.findtext("m:url", "", NS)}
                                 for lic in pom.findall("m:licenses/m:license", NS)]
            if not entry["licenses"]:
                entry["problems"].append("missing-pom-license-review-parent-or-source")
        artifacts = sorted(list(directory.glob("*/*.aar")) + list(directory.glob("*/*.jar")))
        entry["artifacts"] = []
        for artifact in artifacts:
            data = artifact.read_bytes()
            if not pinned(root, group, name, version, artifact.name, data):
                raise ValueError("Artifact checksum is not pinned: " + entry["coordinate"])
            entry["artifacts"].append({"name": artifact.name, "sha256": sha(data)})
            for origin, notice in embedded_notices(data):
                filename = sha(notice) + ".txt"
                (texts / filename).write_bytes(notice)
                entry["notices"].append({"origin": artifact.name + "!/" + origin,
                                         "file": "dependency-notices/" + filename, "sha256": sha(notice)})
        if not artifacts:
            if entry.get("pomPackaging") == "pom" and not entry["problems"]:
                entry["metadataOnly"] = {"kind": "pom-bom", "reviewed": False,
                    "packaging": "pom", "sourceEvidence": {"pomSha256": entry["pomSha256"]}}
            else:
                entry["problems"].append("no-release-artifact-explicit-verified-metadata-only-review-required")
        packages.append(entry)
    report = {"schemaVersion": 1, "scope": "app:releaseRuntimeClasspath",
              "lockSha256": sha((root / "app/gradle.lockfile").read_bytes()),
              "verificationMetadataSha256": sha((root / "gradle/verification-metadata.xml").read_bytes()),
              "nativeSourceReview": {"reviewed": False,
                  "reason": "android-golib embeds Go/native dependencies absent from Maven locks; pin source/build mapping and preserve their notices"},
              "packages": packages}
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes((json.dumps(report, indent=2) + "\n").encode("utf-8"))
    return report


def check(root, manifest):
    report = json.loads(manifest.read_text(encoding="utf-8"))
    expected = {p["name"] + ":" + p["version"] for p in runtime_packages(root)}
    entries = report["packages"]
    actual = [p["coordinate"] for p in entries]
    problems = []
    if report.get("schemaVersion") != 1 or report.get("scope") != "app:releaseRuntimeClasspath":
        problems.append("Invalid notice manifest schema/scope")
    if set(actual) != expected or len(actual) != len(expected):
        problems.append("Notice inventory differs from resolved release runtime locks")
    if report.get("lockSha256") != sha((root / "app/gradle.lockfile").read_bytes()):
        problems.append("Notice inventory is stale")
    if report.get("verificationMetadataSha256") != sha((root / "gradle/verification-metadata.xml").read_bytes()):
        problems.append("Notice artifact verification evidence is stale")
    for entry in entries:
        if entry.get("reviewed") is not True or not entry.get("licenseExpression") or not entry.get("reviewEvidence"):
            problems.append(entry["coordinate"] + ": license/notice compatibility review incomplete")
        if entry.get("reviewed") is True and not compatible(entry.get("licenseExpression")):
            problems.append(entry["coordinate"] + ": selected license terms require compatibility policy review")
        if entry.get("reviewed") is True and not attestation_matches(entry):
            problems.append(entry["coordinate"] + ": reviewed evidence/notice inventory changed or is unbound")
        if not entry.get("artifacts"):
            metadata = entry.get("metadataOnly", {})
            verified = (metadata.get("reviewed") is True and metadata.get("sourceEvidence") and
                        metadata.get("kind") in {"pom-bom", "gradle-variant-redirect"})
            if metadata.get("kind") == "pom-bom":
                verified = verified and metadata.get("packaging") == "pom"
            elif metadata.get("kind") == "gradle-variant-redirect":
                variants = metadata.get("runtimeVariants", [])
                verified = verified and bool(metadata.get("moduleSha256")) and bool(variants) and all(
                    variant.get("availableAt") in expected for variant in variants)
            if not verified:
                problems.append(entry["coordinate"] + ": missing artifact or verified metadata-only review")
        if entry.get("problems"):
            problems.append(entry["coordinate"] + ": unresolved evidence problems")
        for notice in entry["notices"]:
            path = (manifest.parent / notice["file"]).resolve()
            if not path.is_relative_to(manifest.parent.resolve()) or not path.is_file() or sha(path.read_bytes()) != notice["sha256"]:
                problems.append(entry["coordinate"] + ": missing/changed notice")
    native = report.get("nativeSourceReview", {})
    if native.get("inventorySha256") != evidence_digest(native):
        problems.append("Native dependency/notice inventory differs from its captured source evidence")
    if native.get("reviewed") is True and not attestation_matches(native):
        problems.append("Native reviewed evidence/notice inventory changed or is unbound")
    if (native.get("reviewed") is not True or not native.get("reviewEvidence") or
            not native.get("sourceRevision") or not native.get("buildMapping") or
            not native.get("dependencies") or not native.get("notices")):
        problems.append("Native transitive source/license inventory incomplete")
    for dependency in native.get("dependencies", []):
        if (dependency.get("reviewed") is not True or not dependency.get("coordinate") or
                not dependency.get("reviewEvidence") or not compatible(dependency.get("licenseExpression"))):
            problems.append("Native dependency review incomplete or requires compatibility policy review")
        elif not attestation_matches(dependency):
            problems.append("Native dependency reviewed evidence/notice inventory changed or is unbound")
    for notice in native.get("notices", []):
        path = (manifest.parent / notice["file"]).resolve()
        if not path.is_relative_to(manifest.parent.resolve()) or not path.is_file() or sha(path.read_bytes()) != notice["sha256"]:
            problems.append("Native dependency notice missing/changed")
    return problems


def bundle(root, manifest, destination):
    """Preserve all reviewed and unresolved evidence; never turn a blocked audit green."""
    report = json.loads(manifest.read_text(encoding="utf-8"))
    problems = check(root, manifest)
    if any("missing/changed" in problem for problem in problems):
        raise ValueError("Notice evidence is missing or changed; refuse asset regeneration")
    lines = ["Calendeer third-party licenses and copyright notices",
             "License audit: " + ("BLOCKED - see unresolved source/licensing gates below" if problems else "PASS"),
             "Manifest SHA-256: " + sha(manifest.read_bytes()), "",
             "Imported Proton Calendar/WebClients adaptations: GPL-3.0-only.",
             "Source/provenance: proton-auth/PROVENANCE.md; GPLv3 text: GPL-3.0.txt.", "",
             "Resolved release runtime coordinates:"]
    notices = {}
    for entry in report["packages"]:
        lines.append(entry["coordinate"] + " — " + entry.get("licenseExpression", "UNREVIEWED"))
        for notice in entry["notices"]:
            notices.setdefault(notice["file"], []).append(notice["origin"])
    native = report.get("nativeSourceReview", {})
    lines += ["", "Native dependencies:"]
    for dependency in native.get("dependencies", []):
        lines.append(dependency["coordinate"] + " — " + dependency.get("licenseExpression", "UNREVIEWED"))
    for notice in native.get("notices", []):
        notices.setdefault(notice["file"], []).append(notice["origin"])
    lines += ["", "Unresolved distribution gates:", *problems, ""]
    for name, origins in sorted(notices.items()):
        path = (manifest.parent / name).resolve()
        if not path.is_relative_to(manifest.parent.resolve()):
            raise ValueError("Notice path escapes manifest directory")
        lines += ["=" * 72, "Preserved notice: " + name,
                  "Origins: " + "; ".join(sorted(set(origins))), "",
                  path.read_bytes().decode("utf-8", errors="strict"), ""]
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Keep preserved notice bytes and generated separators independent of host
    # newline translation. Git attributes retain these hash-bound evidence files.
    destination.write_bytes("\n".join(lines).encode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--capture", action="store_true", help="Overwrite candidate evidence; never preserves review decisions")
    parser.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    parser.add_argument("--manifest", type=Path, default=Path("docs/release-dependencies.json"))
    parser.add_argument("--bundle-assets", action="store_true", help="Regenerate legal text asset; unresolved gates stay visible")
    args = parser.parse_args()
    try:
        if args.capture:
            report = capture(args.root, args.cache, args.manifest)
            print(f"Captured {len(report['packages'])} runtime coordinates; review remains required")
        problems = check(args.root, args.manifest)
        if args.bundle_assets:
            bundle(args.root, args.manifest, args.root / "app/src/main/assets/legal/THIRD-PARTY-NOTICES.txt")
        print(f"Release notices: {'BLOCKED' if problems else 'PASS'} ({len(problems)} unresolved gates)")
        for problem in problems:
            print(problem)
        return 1 if problems else 0
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, zipfile.BadZipFile) as error:
        print("Release notices: ERROR (" + type(error).__name__ + ")")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
