"""Reject packaged JSR305 classes using actual DEX class definitions and resolved locks.

This static gate does not establish runtime compatibility; run release behavior checks
separately. It never edits an APK, Gradle locks, manifests or dependency metadata.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile
import zlib

from dependency_audit import inventory

PROBLEMATIC = {b"Ljavax/annotation/concurrent/" + name + b";" for name in
               (b"GuardedBy", b"Immutable", b"NotThreadSafe", b"ThreadSafe")}


def uint(data, offset):
    if offset < 0 or offset + 4 > len(data):
        raise ValueError("Truncated DEX field")
    return struct.unpack_from("<I", data, offset)[0]


def section(data, count, offset, stride):
    if (count == 0 and offset != 0) or (count and (offset < 112 or offset % 4)) or offset + count * stride > len(data):
        raise ValueError("Invalid DEX table bounds")


def class_definitions(data):
    if (len(data) < 112 or not re.fullmatch(rb"dex\n0(?:3[5-9]|40)\x00", data[:8]) or
            uint(data, 32) != len(data) or uint(data, 36) != 112 or uint(data, 40) != 0x12345678):
        raise ValueError("Unsupported or malformed DEX header")
    if data[12:32] != hashlib.sha1(data[32:]).digest() or uint(data, 8) != zlib.adler32(data[12:]) & 0xffffffff:
        raise ValueError("DEX integrity fields do not match")
    strings, strings_at = uint(data, 56), uint(data, 60)
    types, types_at = uint(data, 64), uint(data, 68)
    classes, classes_at = uint(data, 96), uint(data, 100)
    for count, offset, stride in ((strings, strings_at, 4), (types, types_at, 4), (classes, classes_at, 32)):
        section(data, count, offset, stride)
    definitions = set()
    for number in range(classes):
        type_id = uint(data, classes_at + number * 32)
        if type_id >= types:
            raise ValueError("Invalid DEX class type index")
        string_id = uint(data, types_at + type_id * 4)
        if string_id >= strings:
            raise ValueError("Invalid DEX descriptor string index")
        offset = uint(data, strings_at + string_id * 4)
        if offset < 112 or offset >= len(data):
            raise ValueError("Invalid DEX string data offset")
        # Skip the bounded UTF-16-size ULEB128. Descriptors are compared as raw
        # MUTF-8 bytes, so irrelevant non-ASCII identifiers need no lossy decoding.
        for _ in range(5):
            if offset >= len(data):
                raise ValueError("Truncated DEX string length")
            byte = data[offset]
            offset += 1
            if not byte & 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.find(b"\x00", offset)
        if end < 0:
            raise ValueError("Unterminated DEX descriptor")
        descriptor = data[offset:end]
        if not descriptor.startswith(b"L") or not descriptor.endswith(b";") or descriptor in definitions:
            raise ValueError("Invalid or duplicate DEX class descriptor")
        definitions.add(descriptor)
    return definitions


def verify(apk, root):
    if any(p["name"] == "com.google.code.findbugs:jsr305" and
           "app:releaseRuntimeClasspath" in p["configurations"] for p in inventory(root)):
        raise ValueError("JSR305 remains in the resolved app release runtime lock")
    definitions = set()
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK ZIP entries")
        dexes = sorted(name for name in names if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name))
        if not dexes or any(name.endswith(".dex") and name not in dexes for name in names):
            raise ValueError("Missing or unexpected APK DEX inventory")
        for name in dexes:
            entry = archive.getinfo(name)
            if entry.file_size > 256 * 1024 * 1024:
                raise ValueError("Oversized DEX")
            classes = class_definitions(archive.read(name))
            if classes & definitions:
                raise ValueError("Duplicate classes across APK DEX files")
            definitions.update(classes)
    problematic = sorted(PROBLEMATIC & definitions)
    if problematic:
        raise ValueError("Prohibited concurrent annotation class definitions are packaged")
    # The coordinate is annotation-only: fail if any copy was shaded under its
    # original javax.annotation namespace, not only the four known CC-BY classes.
    if any(name.startswith(b"Ljavax/annotation/") for name in definitions):
        raise ValueError("JSR305 namespace still has packaged class definitions")
    return {"apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "dexFiles": len(dexes),
            "classDefinitions": len(definitions), "jsr305RuntimeCoordinate": "ABSENT",
            "prohibitedAnnotationDefinitions": "ABSENT", "runtimeCompatibility": "NOT_RUN"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.apk, args.root), indent=2))
        return 0
    except (OSError, ValueError, KeyError, TypeError, struct.error, zipfile.BadZipFile):
        print("Release annotation gate: BLOCKED/FAILED")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
