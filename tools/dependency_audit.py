"""Inventory checked-in Gradle locks; optionally query OSV using only public coordinates.

An unavailable/incomplete advisory lookup is an error, never a clean security result.
Checksums and advisory lookups do not establish upstream authenticity or full CVE coverage.
"""
import argparse
import datetime
import json
from pathlib import Path
import urllib.request


def inventory(root):
    packages = {}
    locks = [("buildscript", root / "buildscript-gradle.lockfile")] + [
        (module, root / module / "gradle.lockfile") for module in ("app", "auth", "sync", "proton-auth")]
    for module, lock in locks:
        if not lock.is_file():
            raise ValueError(f"Missing dependency lock: {lock.relative_to(root)}")
        for number, line in enumerate(lock.read_text(encoding="utf-8").splitlines(), 1):
            if not line or line.startswith("#") or line.startswith("empty="):
                continue
            coordinate, separator, scopes = line.partition("=")
            parts = coordinate.split(":")
            if not separator or len(parts) != 3 or not all(parts) or not scopes:
                raise ValueError(f"Malformed dependency lock entry: {module}:{number}")
            name = ":".join(parts[:2])
            entry = packages.setdefault((name, parts[2]), set())
            entry.update(f"{module}:{scope}" for scope in scopes.split(","))
    if not packages:
        raise ValueError("Empty dependency inventory")
    return [{"name": name, "version": version, "configurations": sorted(scopes)}
            for (name, version), scopes in sorted(packages.items())]


def request_json(url, body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers={
        "Content-Type": "application/json", "User-Agent": "Calendeer-dependency-audit"})
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = response.read(32 * 1024 * 1024 + 1)
    if len(payload) > 32 * 1024 * 1024:
        raise ValueError("Advisory response exceeds the size limit")
    return json.loads(payload)


def advisories(packages, fetch=request_json):
    findings = []
    for start in range(0, len(packages), 100):
        batch = packages[start:start + 100]
        queries = [{"package": {"name": p["name"], "ecosystem": "Maven"},
                    "version": p["version"]} for p in batch]
        response = fetch("https://api.osv.dev/v1/querybatch", {"queries": queries})
        if not isinstance(response, dict) or set(response) != {"results"}:
            raise ValueError("Invalid advisory batch envelope")
        results = response.get("results")
        if not isinstance(results, list) or len(results) != len(batch):
            raise ValueError("Incomplete advisory batch response")
        for package, query, result in zip(batch, queries, results):
            ids = set()
            for _ in range(20):
                if not isinstance(result, dict) or set(result) - {"vulns", "next_page_token"}:
                    raise ValueError("Invalid advisory result envelope")
                entries = result.get("vulns", [])
                if not isinstance(entries, list) or any(not isinstance(item, dict) or
                        not isinstance(item.get("id"), str) or not item["id"] for item in entries):
                    raise ValueError("Invalid advisory identifiers")
                ids.update(item["id"] for item in entries)
                token = result.get("next_page_token")
                if token is not None and not isinstance(token, str):
                    raise ValueError("Invalid advisory page token")
                if not token:
                    break
                result = fetch("https://api.osv.dev/v1/query", {**query, "page_token": token})
            else:
                raise ValueError("Advisory pagination did not complete")
            if ids:
                findings.append({"name": package["name"], "version": package["version"],
                                 "advisories": sorted(ids)})
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--osv", action="store_true", help="Send public dependency names/versions to OSV")
    parser.add_argument("--output", type=Path, default=Path("build/reports/dependency-audit.json"))
    args = parser.parse_args()
    report = {"schemaVersion": 1, "advisoryStatus": "NOT_RUN"}
    try:
        report["packages"] = inventory(args.root)
        if args.osv:
            report["advisories"] = advisories(report["packages"])
            report["advisoryStatus"] = "FINDINGS" if report["advisories"] else "NO_MATCHES"
            report["checkedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    except (OSError, ValueError, KeyError, TypeError) as error:
        report["advisoryStatus"] = "ERROR"
        report["errorType"] = type(error).__name__
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Packages: {len(report.get('packages', []))}; advisory status: {report['advisoryStatus']}")
    return 2 if report["advisoryStatus"] == "ERROR" else 1 if report["advisoryStatus"] == "FINDINGS" else 0


if __name__ == "__main__":
    raise SystemExit(main())
