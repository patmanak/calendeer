import hashlib
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
import zlib

from check_release_annotations import PROBLEMATIC, class_definitions, verify


def dex_fixture(descriptors, definitions):
    """Minimal independent DEX class-table fixture, with valid integrity fields."""
    strings_at = 112
    types_at = strings_at + len(descriptors) * 4
    classes_at = types_at + len(descriptors) * 4
    data_at = classes_at + len(definitions) * 32
    data = bytearray(data_at)
    offsets = []
    for descriptor in descriptors:
        offsets.append(len(data))
        data += bytes([len(descriptor)]) + descriptor + b"\0"
    data[:8] = b"dex\n039\0"
    fields = {32: len(data), 36: 112, 40: 0x12345678, 56: len(descriptors),
              60: strings_at if descriptors else 0, 64: len(descriptors),
              68: types_at if descriptors else 0, 96: len(definitions),
              100: classes_at if definitions else 0}
    for offset, value in fields.items():
        struct.pack_into("<I", data, offset, value)
    for index, offset in enumerate(offsets):
        struct.pack_into("<I", data, strings_at + index * 4, offset)
        struct.pack_into("<I", data, types_at + index * 4, index)
    for index, definition in enumerate(definitions):
        struct.pack_into("<I", data, classes_at + index * 32, definition)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xffffffff)
    return bytes(data)


class AnnotationGateTest(unittest.TestCase):
    def root_fixture(self, root, include_jsr=False):
        for module in ("app", "auth", "sync", "proton-auth"):
            (root / module).mkdir()
            text = "example:safe:1=releaseRuntimeClasspath\n"
            if include_jsr and module == "app":
                text += "com.google.code.findbugs:jsr305:3.0.2=releaseRuntimeClasspath\n"
            (root / module / "gradle.lockfile").write_text(text, encoding="utf-8")
        (root / "buildscript-gradle.lockfile").write_text("example:tool:1=classpath\n", encoding="utf-8")

    def test_string_reference_is_not_mistaken_for_distributed_definition(self):
        target = next(iter(PROBLEMATIC))
        dex = dex_fixture([target, b"Lexample/Retained;"], [1])
        self.assertEqual({b"Lexample/Retained;"}, class_definitions(dex))

    def test_every_problematic_definition_is_discovered(self):
        for target in PROBLEMATIC:
            self.assertEqual({target}, class_definitions(dex_fixture([target], [0])))

    def test_corrupted_or_unsupported_dex_is_rejected(self):
        dex = bytearray(dex_fixture([b"Lexample/Retained;"], [0]))
        dex[-2] ^= 1
        with self.assertRaises(ValueError):
            class_definitions(dex)
        dex[:8] = b"dex\n041\0"
        with self.assertRaises(ValueError):
            class_definitions(dex)

    def test_missing_runtime_exclusion_blocks_even_clean_apk(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.root_fixture(root, include_jsr=True)
            apk = root / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", dex_fixture([b"Lexample/Retained;"], [0]))
            with self.assertRaises(ValueError):
                verify(apk, root)

    def test_bad_class_in_secondary_dex_blocks_clean_primary(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.root_fixture(root)
            apk = root / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", dex_fixture([b"Lexample/Retained;"], [0]))
                archive.writestr("classes2.dex", dex_fixture([next(iter(PROBLEMATIC))], [0]))
            with self.assertRaises(ValueError):
                verify(apk, root)

    def test_clean_definitions_and_absent_coordinate_pass_static_gate_only(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.root_fixture(root)
            apk = root / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", dex_fixture([next(iter(PROBLEMATIC)), b"Lexample/Retained;"], [1]))
                archive.writestr("classes10.dex", dex_fixture([b"Lexample/TenthDex;"], [0]))
            result = verify(apk, root)
            self.assertEqual("ABSENT", result["prohibitedAnnotationDefinitions"])
            self.assertEqual("NOT_RUN", result["runtimeCompatibility"])
            self.assertEqual(2, result["dexFiles"])


if __name__ == "__main__":
    unittest.main()
