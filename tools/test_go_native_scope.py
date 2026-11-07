"""Synthetic corrupt-input and inventory-binding tests; no native execution."""
import copy
import hashlib
import contextlib
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock
import zipfile

from check_go_native_scope import (
    ABIS, COORDINATE, INVENTORY, inspect_aar, inspect_elf,
    inventory_pins, parse_pclntab,
)
import check_go_native_scope as scope


def pclntab(ptr=8):
    names = b"encoding/asn1.parseSequenceOf\0fixture.other\0"
    fn = 8 + 8 * ptr
    cu = fn + len(names)
    ft = (cu + 7) // 8 * 8
    data = bytearray(ft + 20 + 88)
    data[:8] = b"\xf1\xff\xff\xff\0\0\x01" + bytes([ptr])
    struct.pack_into("<" + ("Q" if ptr == 8 else "I") * 8,
                     data, 8, 2, 1, 0, fn, cu, cu, cu, ft)
    data[fn:cu] = names
    struct.pack_into("<IIIII", data, ft, 0, 20, 32, 64, 64)
    struct.pack_into("<Ii", data, ft + 20, 0, 0)
    struct.pack_into("<Ii", data, ft + 64, 32, len(b"encoding/asn1.parseSequenceOf\0"))
    return data, ft, fn


def elf(abi="arm64-v8a", table=None):
    wide = ABIS[abi][0] == 2
    ptr = 8 if wide else 4
    table = bytes(pclntab(ptr)[0]) if table is None else table
    header, shsize = (64, 64) if wide else (52, 40)
    names = b"\0.shstrtab\0.rodata\0"
    nameoff, dataoff = header, 128
    shoff = (dataoff + len(table) + 7) // 8 * 8
    data = bytearray(shoff + 3 * shsize)
    data[:16] = b"\x7fELF" + bytes([2 if wide else 1, 1, 1]) + bytes(9)
    struct.pack_into("<HHI", data, 16, 3, ABIS[abi][1], 1)
    struct.pack_into("<Q" if wide else "<I", data, 40 if wide else 32, shoff)
    struct.pack_into("<H", data, 52 if wide else 40, header)
    struct.pack_into("<HHH", data, 58 if wide else 46, shsize, 3, 1)
    data[nameoff:nameoff + len(names)] = names
    data[dataoff:dataoff + len(table)] = table
    fmt = "<IIQQQQIIQQ" if wide else "<IIIIIIIIII"
    struct.pack_into(fmt, data, shoff + shsize, 1, 3, 0, 0, nameoff, len(names), 0, 0, 1, 0)
    struct.pack_into(fmt, data, shoff + 2 * shsize, 11, 1, 0, 0, dataoff, len(table), 0, 0, 8, 0)
    return data


class GoNativeScopeTests(unittest.TestCase):
    def run_cli(self, *arguments):
        output = io.StringIO()
        with mock.patch("sys.argv", ["check_go_native_scope.py", *map(str, arguments)]), contextlib.redirect_stdout(output):
            result = scope.main()
        return result, output.getvalue()

    def test_cli_refuses_direct_and_resolved_output_aliases(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            aar = root / "private.aar"
            aar.write_bytes(b"immutable input")
            for destination in (aar, root / "unused" / ".." / "private.aar", INVENTORY):
                with self.subTest(destination=destination.name), mock.patch.object(scope, "inspect_aar") as inspect:
                    result, output = self.run_cli("--aar", aar, "--output", destination)
                    self.assertEqual(result, 2)
                    self.assertEqual(json.loads(output)["reason"], "OUTPUT_ALIASES_INPUT")
                    inspect.assert_not_called()
                    self.assertEqual(aar.read_bytes(), b"immutable input")
                    self.assertNotIn(str(root), output)

    def test_cli_refuses_samefile_identity_before_inspection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            aar, alias = root / "private.aar", root / "alias.json"
            aar.write_bytes(b"immutable input")
            alias.write_bytes(b"existing output")
            with mock.patch.object(Path, "samefile", return_value=True) as identity, mock.patch.object(scope, "inspect_aar") as inspect:
                result, output = self.run_cli("--aar", aar, "--output", alias)
                self.assertEqual(result, 2)
                self.assertEqual(json.loads(output)["reason"], "OUTPUT_ALIASES_INPUT")
                identity.assert_called_once_with(aar)
                inspect.assert_not_called()
                self.assertEqual(alias.read_bytes(), b"existing output")

    def test_cli_refuses_real_hardlink_output_alias(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            aar, alias = root / "private.aar", root / "hardlink.json"
            aar.write_bytes(b"immutable input")
            try:
                os.link(aar, alias)
            except OSError:
                self.skipTest("Hardlink creation unavailable on this host")
            with mock.patch.object(scope, "inspect_aar") as inspect:
                result, output = self.run_cli("--aar", aar, "--output", alias)
                self.assertEqual(result, 2)
                self.assertEqual(json.loads(output)["reason"], "OUTPUT_ALIASES_INPUT")
                inspect.assert_not_called()
                self.assertEqual(aar.read_bytes(), b"immutable input")

    def test_cli_refuses_symlink_output_alias(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            aar = root / "private.aar"
            aar.write_bytes(b"immutable input")
            alias = root / "symlink.json"
            try:
                alias.symlink_to(aar)
            except OSError:
                self.skipTest("Symlink creation unavailable on this host")
            with mock.patch.object(scope, "inspect_aar") as inspect:
                result, output = self.run_cli("--aar", aar, "--output", alias)
                self.assertEqual(result, 2)
                self.assertEqual(json.loads(output)["reason"], "OUTPUT_ALIASES_INPUT")
                inspect.assert_not_called()
                self.assertEqual(aar.read_bytes(), b"immutable input")

    def test_cli_os_and_uncontrolled_value_errors_do_not_disclose_paths_or_input(self):
        for error in (OSError("owner/private.aar sensitive input"), ValueError("sensitive input")):
            with self.subTest(error=type(error).__name__), mock.patch.object(scope, "inspect_aar", side_effect=error):
                result, output = self.run_cli("--aar", "owner/private.aar")
                self.assertEqual(result, 2)
                self.assertEqual(json.loads(output), {"status": "ERROR", "reason": type(error).__name__})

    def test_cli_missing_file_and_output_failure_are_closed_without_traceback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result, output = self.run_cli("--aar", root / "owner-secret.aar")
            self.assertEqual(result, 2)
            self.assertEqual(json.loads(output)["reason"], "FileNotFoundError")
            self.assertNotIn(str(root), output)
            with mock.patch.object(scope, "inspect_aar", return_value={"status": "fixture"}):
                result, output = self.run_cli("--aar", root / "fixture.aar", "--output", root / "missing" / "private.json")
                self.assertEqual(result, 2)
                self.assertEqual(json.loads(output)["reason"], "FileNotFoundError")
                self.assertNotIn(str(root), output)

    def test_cli_output_has_exact_utf8_lf_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            destination = root / "report.json"
            report = {"status": "fixture", "detail": "é"}
            with mock.patch.object(scope, "inspect_aar", return_value=report):
                result, output = self.run_cli("--aar", root / "fixture.aar", "--output", destination)
            self.assertEqual((result, output), (0, ""))
            self.assertEqual(destination.read_bytes(), (json.dumps(report, indent=2) + "\n").encode("utf-8"))
            self.assertNotIn(b"\r", destination.read_bytes())

    def test_actual_inventory_has_consistent_coordinate_and_four_pins(self):
        digest, pins = inventory_pins()
        self.assertEqual(len(digest), 64)
        self.assertEqual(set(pins), {f"jni/{abi}/libgojni.so" for abi in ABIS})

    def test_synthetic_four_abi_tables_have_relative_offsets_and_compact_counts(self):
        for abi in ABIS:
            with self.subTest(abi=abi):
                report = inspect_elf(elf(abi), abi)
                self.assertEqual(report["goFunctionCount"], 2)
                self.assertEqual(report["families"]["stdlib_asn1"]["functionCount"], 1)
                self.assertEqual(report["families"]["stdlib_asn1"]["sampleEntryOffsetsFromRuntimeText"], ["0x0"])
                self.assertEqual(report["families"]["circl_p384"]["functionCount"], 0)
                self.assertNotIn("functions", report)

    def test_truncated_and_wrong_elf_headers_fail(self):
        original = elf()
        for length in (0, 6, 51, 63):
            with self.subTest(length=length), self.assertRaises(ValueError):
                inspect_elf(original[:length], "arm64-v8a")
        for offset, value in ((0, 0), (4, 1), (5, 2), (6, 2), (16, 2), (18, 62), (52, 52)):
            broken = bytearray(original)
            broken[offset] = value
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                inspect_elf(broken, "arm64-v8a")

    def test_section_table_out_of_bounds_fails(self):
        data = elf()
        struct.pack_into("<Q", data, 40, len(data) - 1)
        with self.assertRaisesRegex(ValueError, "OUT_OF_BOUNDS"):
            inspect_elf(data, "arm64-v8a")

    def test_section_payload_out_of_bounds_fails(self):
        data = elf()
        shoff = struct.unpack_from("<Q", data, 40)[0]
        struct.pack_into("<Q", data, shoff + 128 + 32, len(data))
        with self.assertRaisesRegex(ValueError, "OUT_OF_BOUNDS"):
            inspect_elf(data, "arm64-v8a")

    def test_pclntab_truncated_header_and_wrong_format_fail(self):
        table, _, _ = pclntab()
        for data in (table[:12], b"\xf0" + table[1:]):
            with self.assertRaises(ValueError):
                parse_pclntab(data, 0, len(data), 8)

    def test_pclntab_count_limit_fails(self):
        table, _, _ = pclntab()
        struct.pack_into("<Q", table, 8, 100001)
        with self.assertRaisesRegex(ValueError, "COUNTS"):
            parse_pclntab(table, 0, len(table), 8)

    def test_pclntab_offsets_cannot_escape_container(self):
        table, _, _ = pclntab()
        struct.pack_into("<Q", table, 64, len(table) + 1)
        with self.assertRaisesRegex(ValueError, "OFFSETS"):
            parse_pclntab(table, 0, len(table), 8)

    def test_function_records_and_names_cannot_escape_tables(self):
        original, ft, fn = pclntab()
        for offset, fmt, value in ((ft + 4, "<I", len(original)),
                                   (ft + 24, "<i", -1),
                                   (ft + 24, "<i", 100000),
                                   (ft + 4, "<I", 0)):
            data = bytearray(original)
            struct.pack_into(fmt, data, offset, value)
            with self.subTest(offset=offset, value=value), self.assertRaises(ValueError):
                parse_pclntab(data, 0, len(data), 8)

    def test_function_entry_mismatch_and_nonmonotonic_pc_fail(self):
        original, ft, _ = pclntab()
        for offset, value in ((ft + 20, 1), (ft + 8, 0), (ft + 16, 32)):
            data = bytearray(original)
            struct.pack_into("<I", data, offset, value)
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                parse_pclntab(data, 0, len(data), 8)

    def test_unterminated_name_cannot_read_other_tables(self):
        table, ft, fn = pclntab()
        cu = struct.unpack_from("<Q", table, 40)[0]
        table[fn:cu] = b"x" * (cu - fn)
        with self.assertRaisesRegex(ValueError, "UNTERMINATED"):
            parse_pclntab(table, 0, len(table), 8)

    def test_unknown_table_and_multiple_valid_tables_fail_closed(self):
        original = pclntab()[0]
        for table in (b"unknown", original + original):
            with self.subTest(length=len(table)), self.assertRaisesRegex(ValueError, "ONE_VALID"):
                inspect_elf(elf(table=table), "arm64-v8a")

    def test_symbol_entry_size_and_string_link_are_validated(self):
        for entry_size, link in ((0, 1), (24, 99), (24, 2)):
            data = elf()
            shoff = struct.unpack_from("<Q", data, 40)[0]
            struct.pack_into("<I", data, shoff + 128 + 4, 11)
            struct.pack_into("<Q", data, shoff + 128 + 32, 24)
            struct.pack_into("<I", data, shoff + 128 + 40, link)
            struct.pack_into("<Q", data, shoff + 128 + 56, entry_size)
            with self.subTest(size=entry_size, link=link), self.assertRaisesRegex(ValueError, "ELF_SYMBOL"):
                inspect_elf(data, "arm64-v8a")

    def test_function_name_invalid_utf8_fails(self):
        table, _, fn = pclntab()
        table[fn] = 255
        with self.assertRaises(UnicodeError):
            parse_pclntab(table, 0, len(table), 8)

    def test_zip_missing_duplicate_unexpected_and_unsupported_native_entries_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            libraries = {f"jni/{abi}/libgojni.so": bytes(elf(abi)) for abi in ABIS}
            original_names = list(libraries)
            variants = [
                (original_names[:-1], zipfile.ZIP_STORED, "ZIP_NATIVE_ENTRIES"),
                (original_names + ["jni/unknown/libgojni.so"], zipfile.ZIP_STORED, "ZIP_NATIVE_ENTRIES"),
                (original_names + [original_names[0]], zipfile.ZIP_STORED, "ZIP_NATIVE_ENTRIES"),
                (original_names, zipfile.ZIP_BZIP2, "ZIP_NATIVE_FORMAT"),
            ]
            for names, compression, reason in variants:
                with self.subTest(names=names, compression=compression):
                    aar = root / "fixture.aar"
                    with zipfile.ZipFile(aar, "w", compression=compression) as archive:
                        for index, name in enumerate(names):
                            if name in names[:index]:
                                with self.assertWarns(UserWarning):
                                    archive.writestr(name, libraries[name])
                            else:
                                archive.writestr(name, libraries.get(name, b"fixture"))
                    manifest = json.loads(INVENTORY.read_text(encoding="utf-8"))
                    mapping = manifest["nativeSourceReview"]["buildMapping"]
                    digest = hashlib.sha256(aar.read_bytes()).hexdigest()
                    mapping["artifactSha256"] = digest
                    package = next(p for p in manifest["packages"] if p["coordinate"] == COORDINATE)
                    next(a for a in package["artifacts"] if a["name"].endswith(".aar"))["sha256"] = digest
                    inventory = root / "inventory.json"
                    inventory.write_text(json.dumps(manifest), encoding="utf-8")
                    with self.assertRaisesRegex(ValueError, reason):
                        inspect_aar(aar, inventory)

    def test_aar_hash_abi_and_inventory_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            aar = root / "fixture.aar"
            libraries = {f"jni/{abi}/libgojni.so": bytes(elf(abi)) for abi in ABIS}
            with zipfile.ZipFile(aar, "w") as archive:
                for name, data in libraries.items():
                    archive.writestr(name, data)
            manifest = json.loads(INVENTORY.read_text(encoding="utf-8"))
            mapping = manifest["nativeSourceReview"]["buildMapping"]
            digest = hashlib.sha256(aar.read_bytes()).hexdigest()
            mapping["artifactSha256"] = digest
            for entry in mapping["abis"]:
                entry["sha256"] = hashlib.sha256(libraries[entry["path"]]).hexdigest()
            package = next(p for p in manifest["packages"] if p["coordinate"] == COORDINATE)
            next(a for a in package["artifacts"] if a["name"].endswith(".aar"))["sha256"] = digest
            inventory = root / "inventory.json"
            inventory.write_text(json.dumps(manifest), encoding="utf-8")
            result = inspect_aar(aar, inventory)
            self.assertEqual(len(result["libraries"]), 4)
            self.assertEqual(result["status"], "METADATA_PARSED_APPLICABILITY_UNRESOLVED")
            wrong_library = copy.deepcopy(manifest)
            wrong_library["nativeSourceReview"]["buildMapping"]["abis"][0]["sha256"] = "0" * 64
            inventory.write_text(json.dumps(wrong_library), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "ELF_HASH_MISMATCH"):
                inspect_aar(aar, inventory)
            inventory.write_text(json.dumps(manifest), encoding="utf-8")
            aar.write_bytes(aar.read_bytes() + b"changed")
            with self.assertRaisesRegex(ValueError, "AAR_HASH_MISMATCH"):
                inspect_aar(aar, inventory)
            broken = copy.deepcopy(manifest)
            broken["nativeSourceReview"]["buildMapping"]["abis"].pop()
            inventory.write_text(json.dumps(broken), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "INVENTORY_ABIS"):
                inventory_pins(inventory)


if __name__ == "__main__":
    unittest.main()
