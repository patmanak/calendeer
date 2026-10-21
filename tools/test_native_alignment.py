import contextlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from check_native_alignment import check_elf, inspect_apk, main


def elf(machine=183, shared_writable=False, prefix_writable=False, alignment=16384):
    """Synthetic LOAD/RELRO layout, with independent page-boundary examples."""
    data = bytearray(1024)
    data[:16] = b'\x7fELF\x02\x01\x01' + bytes(9)
    struct.pack_into('<HHI', data, 16, 3, machine, 1)
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HHH', data, 52, 64, 56, 3)
    start, offset = (0x4100, 0x100) if prefix_writable else (0x4200, 0x200)
    memory = 0x1800 if shared_writable else 0xf00 if prefix_writable else 0x80
    headers = [
        (1, 6, offset, start, 0, 0x80, memory, alignment),
        (1, 6, 0x300, 0x8300, 0, 0x10, 0x10, 16384),
        (0x6474e552, 4, 0x200, 0x4200, 0, 0x80, 0xe00, 1),
    ]
    for index, header in enumerate(headers):
        struct.pack_into('<IIQQQQQQ', data, 64 + index * 56, *header)
    return bytes(data)


def apk(path, data=None, aligned=True, compressed=False, names=None):
    names = names or ['lib/arm64-v8a/libfixture.so']
    with zipfile.ZipFile(path, 'w') as archive:
        for name in names:
            entry = zipfile.ZipInfo(name)
            entry.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
            if aligned:
                padding = (-archive.fp.tell() - 30 - len(name.encode('utf-8')) - 4) % 16384
                entry.extra = struct.pack('<HH', 0xd935, padding) + bytes(padding)
            archive.writestr(entry, elf() if data is None else data)


class NativeAlignmentTest(unittest.TestCase):
    def test_non_aligned_relro_end_in_own_load_is_valid(self):
        # 0x4200+0xe00=0x5000. Protection ends at 0x8000; next RW starts at 0x8300.
        self.assertEqual({'loadSegments': 2, 'relroSegments': 1}, check_elf(elf(), 183))

    def test_relro_cannot_protect_subsequent_writable_bytes_in_same_load(self):
        with self.assertRaisesRegex(ValueError, 'RELRO_OVERLAPS_WRITABLE_DATA'):
            check_elf(elf(shared_writable=True), 183)

    def test_relro_cannot_protect_writable_prefix_at_rounded_lower_boundary(self):
        with self.assertRaisesRegex(ValueError, 'RELRO_OVERLAPS_WRITABLE_DATA'):
            check_elf(elf(prefix_writable=True), 183)

    def test_load_requires_16k_alignment_and_matching_file_virtual_offsets(self):
        with self.assertRaisesRegex(ValueError, 'LOAD_NOT_16K_ALIGNED'):
            check_elf(elf(alignment=4096), 183)
        data = bytearray(elf())
        struct.pack_into('<Q', data, 64 + 16, 0x4201)
        with self.assertRaisesRegex(ValueError, 'LOAD_NOT_16K_ALIGNED'):
            check_elf(data, 183)

    def test_malformed_headers_tables_and_segments_fail_closed(self):
        bad_size = bytearray(elf())
        struct.pack_into('<Q', bad_size, 64 + 32, 2048)
        for data in (b'', elf()[:100], bytes(bad_size), b'not-elf' + bytes(1024)):
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                check_elf(data, 183)
        with self.assertRaisesRegex(ValueError, 'INVALID_ELF64_PROGRAM_TABLE'):
            check_elf(elf(machine=62), 183)

    def test_missing_or_unmapped_relro_is_not_a_passing_security_check(self):
        for field, value in ((0, 0), (16, 0xc000)):
            data = bytearray(elf())
            struct.pack_into('<I' if field == 0 else '<Q', data, 64 + 112 + field, value)
            with self.subTest(field=field), self.assertRaises(ValueError):
                check_elf(data, 183)

    def test_aligned_apk_has_hash_and_exact_checked_library(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'fixture.apk'
            apk(path)
            result = inspect_apk(path)
            self.assertEqual(64, len(result['apkSha256']))
            self.assertEqual([{'abi': 'arm64-v8a', 'library': 'libfixture.so',
                               'loadSegments': 2, 'relroSegments': 1}], result['libraries'])

    def test_x86_64_is_checked_and_unknown_abis_are_not_silently_ignored(self):
        self.assertEqual(2, check_elf(elf(machine=62), 62)['loadSegments'])
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'fixture.apk'
            apk(path, names=['lib/arm64-v8a/libfixture.so', 'lib/riscv64/libfixture.so'])
            with self.assertRaisesRegex(ValueError, 'UNSUPPORTED_NATIVE_ABI'):
                inspect_apk(path)

    def test_zip_offset_and_compression_are_checked_before_accepting_elf(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'fixture.apk'
            for options in ({'aligned': False}, {'compressed': True}):
                apk(path, **options)
                with self.subTest(options=options), self.assertRaises(ValueError):
                    inspect_apk(path)

    def test_empty_or_duplicate_native_inventory_is_not_success(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'fixture.apk'
            apk(path, names=['lib/armeabi-v7a/libfixture.so'])
            with self.assertRaisesRegex(ValueError, 'NO_64_BIT_LIBRARIES'):
                inspect_apk(path)
            with self.assertWarnsRegex(UserWarning, 'Duplicate name'):
                apk(path, names=['lib/arm64-v8a/libfixture.so'] * 2)
            with self.assertRaisesRegex(ValueError, 'DUPLICATE_NATIVE_ENTRY'):
                inspect_apk(path)

    def test_cli_fails_on_any_bad_input_and_never_claims_runtime_coverage(self):
        with tempfile.TemporaryDirectory() as folder:
            good, bad = Path(folder) / 'good.apk', Path(folder) / 'bad.apk'
            apk(good)
            bad.write_bytes(b'not an APK')
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = main([str(good), str(bad)])
            reports = [json.loads(line) for line in output.getvalue().splitlines()]
            self.assertEqual(1, code)
            self.assertEqual(['PASS', 'FAIL'], [r['staticStatus'] for r in reports])
            self.assertTrue(all(r['android17Runtime'] == r['page16KiBRuntime'] == 'NOT_RUN' for r in reports))
            self.assertNotIn(folder, output.getvalue())


if __name__ == '__main__':
    unittest.main()
