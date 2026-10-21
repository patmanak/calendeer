"""Read-only APK gate for this project's uncompressed 64-bit native libraries.

Checks packaging, ELF LOAD alignment and RELRO page separation, never runtime
compatibility. No extraction, repair, device access, network or third-party tools.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


PAGE = 16384
MAX_LIBRARY_BYTES = 64 * 1024 * 1024
MACHINES = {"arm64-v8a": 183, "x86_64": 62}


def check_elf(data, machine):
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError("INVALID_ELF64_HEADER")
    kind, actual_machine, version = struct.unpack_from("<HHI", data, 16)
    phoff = struct.unpack_from("<Q", data, 32)[0]
    header_size, entry_size, count = struct.unpack_from("<HHH", data, 52)
    if (kind != 3 or actual_machine != machine or version != 1 or header_size != 64
            or entry_size != 56 or count == 0 or count == 0xffff
            or phoff < 64 or phoff + entry_size * count > len(data)):
        raise ValueError("INVALID_ELF64_PROGRAM_TABLE")
    loads, relros = [], []
    for i in range(count):
        kind, flags, offset, address, _, file_size, memory_size, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, phoff + i * entry_size)
        if file_size > memory_size or offset + file_size > len(data) or address + memory_size >= 1 << 64:
            raise ValueError("INVALID_ELF64_SEGMENT")
        if kind == 1:  # PT_LOAD
            if (alignment < PAGE or alignment & (alignment - 1)
                    or offset % alignment != address % alignment):
                raise ValueError("LOAD_NOT_16K_ALIGNED")
            loads.append((address, address + memory_size, flags))
        elif kind == 0x6474e552:  # PT_GNU_RELRO
            relros.append((address, address + memory_size))
    if not loads or not relros or any(start == end for start, end in relros):
        raise ValueError("MISSING_LOAD_OR_RELRO")
    for start, end in relros:
        if not any(load_start <= start < end <= load_end for load_start, load_end, _ in loads):
            # Linkers may extend RELRO into padding after its containing LOAD.
            if not any(load_start == start and load_end <= end
                       and end <= (load_end + PAGE - 1) // PAGE * PAGE
                       for load_start, load_end, _ in loads):
                raise ValueError("RELRO_OUTSIDE_LOAD")
        page_start, page_end = start // PAGE * PAGE, (end + PAGE - 1) // PAGE * PAGE
        for load_start, load_end, flags in loads:
            if flags & 2:  # PF_W: rounded protection must not cover other writable bytes.
                overlap_start, overlap_end = max(page_start, load_start), min(page_end, load_end)
                if overlap_start < overlap_end and not (start <= overlap_start and overlap_end <= end):
                    raise ValueError("RELRO_OVERLAPS_WRITABLE_DATA")
    return {"loadSegments": len(loads), "relroSegments": len(relros)}


def inspect_apk(path):
    libraries, seen = [], set()
    with zipfile.ZipFile(path) as apk, path.open("rb") as raw:
        for entry in apk.infolist():
            parts = entry.filename.split("/")
            if (len(parts) >= 3 and parts[0] == "lib" and parts[-1].endswith(".so")
                    and parts[1] not in (*MACHINES, "armeabi-v7a", "x86")):
                raise ValueError("UNSUPPORTED_NATIVE_ABI")
            if len(parts) < 2 or parts[0] != "lib" or parts[1] not in MACHINES:
                continue
            if len(parts) != 3 or not parts[2].endswith(".so"):
                if not entry.is_dir():
                    raise ValueError("UNEXPECTED_NATIVE_ENTRY")
                continue
            if entry.filename in seen:
                raise ValueError("DUPLICATE_NATIVE_ENTRY")
            seen.add(entry.filename)
            if (entry.compress_type != zipfile.ZIP_STORED or entry.flag_bits & 1
                    or entry.file_size > MAX_LIBRARY_BYTES):
                raise ValueError("UNSUPPORTED_NATIVE_PACKAGING")
            raw.seek(entry.header_offset)
            header = raw.read(30)
            if len(header) != 30 or header[:4] != b"PK\x03\x04":
                raise ValueError("INVALID_ZIP_LOCAL_HEADER")
            name_size, extra_size = struct.unpack_from("<HH", header, 26)
            data_offset = entry.header_offset + 30 + name_size + extra_size
            if data_offset % PAGE:
                raise ValueError("NATIVE_ZIP_OFFSET_NOT_16K_ALIGNED")
            with apk.open(entry) as source:
                data = source.read(MAX_LIBRARY_BYTES + 1)
            if len(data) != entry.file_size or len(data) > MAX_LIBRARY_BYTES:
                raise ValueError("INVALID_NATIVE_SIZE")
            layout = check_elf(data, MACHINES[parts[1]])
            libraries.append({"abi": parts[1], "library": parts[2], **layout})
    if not libraries:
        raise ValueError("NO_64_BIT_LIBRARIES")
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {"apkSha256": digest, "libraries": libraries}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, nargs="+")
    args = parser.parse_args(argv)
    failed = False
    for index, path in enumerate(args.apk, 1):
        report = {"argument": index, "android17Runtime": "NOT_RUN", "page16KiBRuntime": "NOT_RUN"}
        try:
            report.update(inspect_apk(path))
            report["staticStatus"] = "PASS"
        except ValueError as failure:
            failed = True
            report.update(staticStatus="FAIL", reason=str(failure))
        except (OSError, zipfile.BadZipFile, RuntimeError, NotImplementedError, struct.error):
            failed = True
            report.update(staticStatus="FAIL", reason="INVALID_OR_UNREADABLE_APK")
        print(json.dumps(report))
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
