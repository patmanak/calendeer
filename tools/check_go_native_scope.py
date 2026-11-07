"""Read-only symbol-scope witness for the inventoried android-golib AAR.

Usage: python tools/check_go_native_scope.py --aar PATH [--output REPORT.json]
No extraction, network, native execution or advisory suppression. Names are not a
call graph: absent names do not exclude inlining, compiler defects or attacks.
Go 1.23.12 format reference (parsePclnTab, funcTab, funcData, funcName):
https://github.com/golang/go/blob/go1.23.12/src/debug/gosym/pclntab.go
Only little-endian ELF32/64 ET_DYN and Go 1.20+ pclntab magic are supported.
Unknown/malformed input fails closed; success means metadata parsed, not safe.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile

COORDINATE = "me.proton.crypto:android-golib:2.10.0-2"
INVENTORY = Path(__file__).resolve().parents[1] / "docs/release-dependencies.json"
ABIS = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183),
        "x86": (1, 3), "x86_64": (2, 62)}
MAX_ELF = 64 * 1024 * 1024
MAX_AAR = 256 * 1024 * 1024
MAX_FUNCTIONS = 100000
FAMILIES = {
    "edwards_multiscalarmult": "filippo.io/edwards25519.(*Point).MultiScalarMult",
    "circl_p384": "github.com/cloudflare/circl/ecc/p384.",
    "protobuf_json": "google.golang.org/protobuf/encoding/protojson.",
    "stdlib_asn1": "encoding/asn1.",
    "stdlib_x509": "crypto/x509.",
    "key_transparency": "github.com/ProtonMail/pm-key-transparency-go-client.",
    "compiler": "cmd/compile.",
}
LIMITATION = ("Symbol metadata only; no call graph, input-condition, inlining or "
              "compiler-miscompilation exclusion. Entry offsets are relative to "
              "runtime.text; its relocation is unresolved. No safety conclusion.")


class ScopeError(ValueError):
    """A controlled, path-free diagnostic code."""


def require(condition, message):
    if not condition:
        raise ScopeError(message)


def span(data, offset, size):
    require(0 <= offset <= len(data) and 0 <= size <= len(data) - offset,
            "OUT_OF_BOUNDS")
    return data[offset:offset + size]


def string_at(data, offset):
    require(0 <= offset < len(data), "STRING_OFFSET")
    end = data.find(b"\0", offset, min(len(data), offset + 4097))
    require(end >= 0, "UNTERMINATED_STRING")
    return data[offset:end].decode("utf-8", "strict")


def inventory_pins(path=INVENTORY):
    require(path.stat().st_size <= 8 * 1024 * 1024, "INVENTORY_TOO_LARGE")
    data = json.loads(path.read_text(encoding="utf-8"))
    packages = [p for p in data["packages"] if p["coordinate"] == COORDINATE]
    require(len(packages) == 1, "INVENTORY_COORDINATE")
    artifacts = [a for a in packages[0]["artifacts"]
                 if a["name"] == "android-golib-2.10.0-2.aar"]
    mapping = data["nativeSourceReview"]["buildMapping"]
    require(len(artifacts) == 1 and artifacts[0]["sha256"] == mapping["artifactSha256"],
            "INVENTORY_ARTIFACT_MAPPING")
    entries = mapping["abis"]
    expected = {f"jni/{abi}/libgojni.so" for abi in ABIS}
    require(len(entries) == 4 and {e["path"] for e in entries} == expected,
            "INVENTORY_ABIS")
    pins = {e["path"]: e["sha256"] for e in entries}
    require(all(e["goVersion"] == "go1.23.12" for e in entries), "INVENTORY_GO_VERSION")
    require(all(re.fullmatch(r"[0-9a-f]{64}", v)
                for v in [mapping["artifactSha256"], *pins.values()]), "INVENTORY_HASH")
    return mapping["artifactSha256"], pins


def elf_sections(data, abi):
    elfclass, machine = ABIS[abi]
    header_size, section_size = (64, 64) if elfclass == 2 else (52, 40)
    require(header_size <= len(data) <= MAX_ELF, "ELF_SIZE")
    require(data[:7] == b"\x7fELF" + bytes((elfclass, 1, 1)), "ELF_IDENT")
    require(struct.unpack_from("<HHI", data, 16) == (3, machine, 1), "ELF_TYPE_MACHINE")
    require(struct.unpack_from("<H", data, 52 if elfclass == 2 else 40)[0] == header_size,
            "ELF_HEADER_SIZE")
    shoff = struct.unpack_from("<Q" if elfclass == 2 else "<I", data,
                               40 if elfclass == 2 else 32)[0]
    size, count, name_index = struct.unpack_from("<HHH", data, 58 if elfclass == 2 else 46)
    require(size == section_size and 1 < count <= 4096 and 0 < name_index < count,
            "ELF_SECTION_HEADER")
    require(shoff >= header_size, "ELF_SECTION_OFFSET")
    span(data, shoff, size * count)
    fmt = "<IIQQQQIIQQ" if elfclass == 2 else "<IIIIIIIIII"
    sections = [struct.unpack_from(fmt, data, shoff + i * size) for i in range(count)]
    require(sections[name_index][1] == 3, "ELF_SECTION_NAMES")
    for s in sections:
        if s[1] != 8:  # SHT_NOBITS has no file payload.
            span(data, s[4], s[5])
    names = span(data, sections[name_index][4], sections[name_index][5])
    for s in sections:
        string_at(names, s[0])
    return sections


def parse_pclntab(data, base, end, ptr):
    table = span(data, base, end - base)
    require(table[:6] == b"\xf1\xff\xff\xff\0\0" and
            len(table) >= 8 and table[6] in (1, 2, 4) and table[7] == ptr,
            "PCLNTAB_HEADER")
    span(table, 8, 8 * ptr)
    nf, nfile, _, fn, cu, files, pc, ft = struct.unpack_from(
        "<" + ("Q" if ptr == 8 else "I") * 8, table, 8)
    require(0 < nf <= MAX_FUNCTIONS and 0 < nfile <= MAX_FUNCTIONS,
            "PCLNTAB_COUNTS")
    require(8 + 8 * ptr <= fn < cu <= files <= pc <= ft < len(table),
            "PCLNTAB_OFFSETS")
    names = span(table, fn, cu - fn)
    require(len(names) <= 8 * 1024 * 1024, "PCLNTAB_NAMES_SIZE")
    span(table, ft, (nf * 2 + 1) * 4)
    functions = []
    last = -1
    for i in range(nf):
        entry, offset = struct.unpack_from("<II", table, ft + i * 8)
        require(entry > last, "PCLNTAB_PC_ORDER")
        last = entry
        require(offset >= (nf * 2 + 1) * 4, "PCLNTAB_FUNC_OVERLAP")
        span(table, ft + offset, 44)  # Go 1.20+ _func fixed portion.
        entry2, name_offset = struct.unpack_from("<Ii", table, ft + offset)
        require(entry == entry2, "PCLNTAB_ENTRY_MISMATCH")
        name = string_at(names, name_offset)
        require(name and all(ord(c) >= 32 for c in name), "PCLNTAB_FUNC_NAME")
        functions.append((name, entry))
    terminal = struct.unpack_from("<I", table, ft + nf * 8)[0]
    require(terminal > last, "PCLNTAB_PC_END")
    strings = []
    for item in re.finditer(b"[^\0]+", names):
        require(len(strings) < MAX_FUNCTIONS * 2 and len(item[0]) <= 4096,
                "PCLNTAB_NAMES_LIMIT")
        strings.append(item[0].decode("utf-8", "strict"))
    return functions, strings


def inspect_elf(data, abi):
    sections = elf_sections(data, abi)
    ptr = 8 if ABIS[abi][0] == 2 else 4
    symbol_count = 0
    kt_export = False
    for s in sections:
        if s[1] not in (2, 11):
            continue
        require(s[9] == (24 if ptr == 8 else 16) and s[5] % s[9] == 0,
                "ELF_SYMBOL_SIZE")
        require(s[5] // s[9] <= 250000, "ELF_SYMBOL_COUNT")
        require(0 < s[6] < len(sections) and sections[s[6]][1] == 3,
                "ELF_SYMBOL_STRINGS")
        st = sections[s[6]]
        strings = span(data, st[4], st[5])
        for off in range(s[4], s[4] + s[5], s[9]):
            index = struct.unpack_from("<I", data, off)[0]
            if index:
                name = string_at(strings, index)
                symbol_count += 1
                kt_export |= name == "Java_com_proton_gopenpgp_ktclient_Ktclient_verifyEpoch"
    tables = []
    candidate_count = 0
    for candidate in re.finditer(b"\xf1\xff\xff\xff\0\0[\x01\x02\x04][\x04\x08]", data):
        candidate_count += 1
        require(candidate_count <= 64, "PCLNTAB_TOO_MANY_CANDIDATES")
        base = candidate.start()
        containers = [s for s in sections if s[1] == 1 and
                      s[4] <= base < s[4] + s[5]]
        if len(containers) != 1:
            continue
        try:
            functions, names = parse_pclntab(data, base, containers[0][4] + containers[0][5], ptr)
            tables.append((base, functions, names))
        except (ValueError, UnicodeError, struct.error):
            continue
    require(len(tables) == 1, "PCLNTAB_EXPECTED_ONE_VALID_TABLE")
    base, functions, names = tables[0]
    families = {}
    for key, prefix in FAMILIES.items():
        matches = [(name, offset) for name, offset in functions if name.startswith(prefix)]
        families[key] = {
            "functionCount": len(matches),
            "nameTableStringCount": sum(name.startswith(prefix) for name in names),
            "sampleEntryOffsetsFromRuntimeText": [hex(offset) for _, offset in matches[:3]],
        }
    return {"abi": abi, "sha256": hashlib.sha256(data).hexdigest(),
            "elfNamedSymbolCount": symbol_count, "pclntabFileOffset": hex(base),
            "goFunctionCount": len(functions), "families": families,
            "keyTransparencyVerifyEpochJniSymbolPresent": kt_export}


def inspect_aar(path, inventory=INVENTORY):
    expected_hash, pins = inventory_pins(inventory)
    require(0 < path.stat().st_size <= MAX_AAR, "AAR_SIZE")
    # Keep one handle open across hash and ZIP reads to avoid path replacement.
    with path.open("rb") as raw:
        digest = hashlib.file_digest(raw, "sha256").hexdigest()
        require(digest == expected_hash, "AAR_HASH_MISMATCH")
        raw.seek(0)
        with zipfile.ZipFile(raw) as archive:
            entries = archive.infolist()
            require(len(entries) <= 10000, "ZIP_ENTRY_COUNT")
            native = [e for e in entries if e.filename.endswith("libgojni.so")]
            require(len(native) == 4 and {e.filename for e in native} == set(pins),
                    "ZIP_NATIVE_ENTRIES")
            libraries = []
            for entry in sorted(native, key=lambda e: e.filename):
                require(not entry.flag_bits & 1 and 0 < entry.file_size <= MAX_ELF and
                        entry.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                        "ZIP_NATIVE_FORMAT")
                with archive.open(entry) as source:
                    data = source.read(MAX_ELF + 1)
                require(len(data) == entry.file_size, "ZIP_NATIVE_SIZE")
                require(hashlib.sha256(data).hexdigest() == pins[entry.filename],
                        "ELF_HASH_MISMATCH")
                libraries.append(inspect_elf(data, entry.filename.split("/")[1]))
    return {"status": "METADATA_PARSED_APPLICABILITY_UNRESOLVED", "coordinate": COORDINATE,
            "aarSha256": digest, "goVersionFromPinnedInventory": "go1.23.12",
            "limitations": LIMITATION, "libraries": libraries}


def check_output_destination(output, aar, inventory=INVENTORY):
    """Refuse direct paths, symlinks and hardlinks to read-only inputs."""
    if output is None:
        return
    for protected in (aar, inventory):
        require(output.resolve() != protected.resolve(), "OUTPUT_ALIASES_INPUT")
        if output.exists() and protected.exists():
            require(not output.samefile(protected), "OUTPUT_ALIASES_INPUT")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        check_output_destination(args.output, args.aar)
        report = inspect_aar(args.aar)
        encoded = (json.dumps(report, indent=2) + "\n").encode("utf-8")
        if args.output:
            args.output.write_bytes(encoded)
        else:
            print(encoded.decode("utf-8"), end="")
    except ScopeError as error:
        print(json.dumps({"status": "ERROR", "reason": str(error)}))
        return 2
    except (ValueError, OSError, KeyError, TypeError, struct.error,
            zipfile.BadZipFile, RuntimeError) as error:
        # Exception messages can include local paths or malformed input bytes.
        print(json.dumps({"status": "ERROR", "reason": type(error).__name__}))
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
