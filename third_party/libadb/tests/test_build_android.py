# SPDX-FileCopyrightText: 2026 YBBLab
# SPDX-License-Identifier: Apache-2.0

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location("libadb_build", Path(__file__).parents[1] / "build_android.py")
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class VerifiedSourceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)

    def archive(self, entries):
        archive = self.root / "source.zip"
        with zipfile.ZipFile(archive, "w") as output:
            for path, data in entries:
                output.writestr(path, data)
        identity = {"archive_root": "upstream-commit", "bytes": archive.stat().st_size,
                    "sha256": BUILD.sha256(archive), "url": "https://example.invalid/source.zip"}
        return archive, identity

    def test_mismatched_digest_is_rejected_before_extraction(self):
        archive, identity = self.archive([("upstream-commit/file.c", "valid")])
        identity["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            BUILD.extract_verified_source(archive, identity, self.root / "source")
        self.assertFalse((self.root / "source").exists())

    def test_traversal_is_rejected_before_any_file_is_written(self):
        archive, identity = self.archive([("upstream-commit/valid.c", "safe"), ("upstream-commit/../escaped", "unsafe")])
        with self.assertRaisesRegex(ValueError, "Unsafe archive"):
            BUILD.extract_verified_source(archive, identity, self.root / "source")
        self.assertFalse((self.root / "source/valid.c").exists())
        self.assertFalse((self.root / "escaped").exists())

    def test_archive_symlink_is_rejected(self):
        link = zipfile.ZipInfo("upstream-commit/link")
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        archive, identity = self.archive([(link, "../../elsewhere")])
        with self.assertRaisesRegex(ValueError, "symlink"):
            BUILD.extract_verified_source(archive, identity, self.root / "source")

    def test_cached_parent_symlink_is_rejected_without_writing_its_target(self):
        archive, identity = self.archive([("upstream-commit/sub/file.c", "source")])
        output = self.root / "source"
        (output / "other").mkdir(parents=True)
        (output / "sub").symlink_to(output / "other", target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Unsafe cached"):
            BUILD.extract_verified_source(archive, identity, output)
        self.assertFalse((output / "other/file.c").exists())

    def test_cached_edits_are_restored_from_verified_archive(self):
        archive, identity = self.archive([("upstream-commit/file.c", "original")])
        output = BUILD.extract_verified_source(archive, identity, self.root / "source")
        (output / "file.c").write_text("modified")
        BUILD.extract_verified_source(archive, identity, output)
        self.assertEqual("original", (output / "file.c").read_text())

    def test_untracked_cached_source_is_rejected(self):
        archive, identity = self.archive([("upstream-commit/file.c", "original")])
        output = BUILD.extract_verified_source(archive, identity, self.root / "source")
        (output / "injected.c").write_text("extra")
        with self.assertRaisesRegex(ValueError, "Unexpected cached"):
            BUILD.extract_verified_source(archive, identity, output)

    def test_corrupt_download_is_never_published(self):
        identity = {"bytes": 4, "sha256": hashlib.sha256(b"good").hexdigest(), "url": "https://example.invalid/source.zip"}
        with patch.object(BUILD.urllib.request, "urlopen", return_value=io.BytesIO(b"evil")):
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                BUILD.prepare_archive(identity, self.root / "downloads")
        self.assertEqual([], list((self.root / "downloads").iterdir()))

    def test_oversized_download_is_never_published(self):
        identity = {"bytes": 4, "sha256": "0" * 64, "url": "https://example.invalid/source.zip"}
        with patch.object(BUILD.urllib.request, "urlopen", return_value=io.BytesIO(b"oversized")):
            with self.assertRaisesRegex(ValueError, "exceeds"):
                BUILD.prepare_archive(identity, self.root / "downloads")
        self.assertEqual([], list((self.root / "downloads").iterdir()))

    def test_notice_retains_leading_comments_and_stops_before_code(self):
        self.assertEqual("/* Copyright Example. */\n// Licensed under Example.",
                         BUILD.leading_notice("/* Copyright Example. */\n\n// Licensed under Example.\n#include <header>\nint main();"))

    def compile_database(self, source_file, directory=None, relative=False):
        build = self.root / "build"
        build.mkdir(exist_ok=True)
        (build / "source.o").write_bytes(b"object")
        database = build / "compile_commands.json"
        file_name = "../source/crypto.cc" if relative else str(source_file)
        database.write_text(json.dumps([{"directory": directory or str(build), "file": file_name,
                                         "arguments": ["clang", "-o", "source.o", "-c", file_name]}]))
        return database

    def source_fixture(self):
        root = self.root / "source"
        root.mkdir()
        (root / "crypto.cc").write_text("/* Copyright Example. Licensed under ISC. */\n#include \"part.cc.inc\"")
        (root / "part.cc.inc").write_text("/* Copyright Included Author. Licensed under ISC. */\nint value;")
        (root / "build.json").write_text("{}")
        return root

    def test_compilation_alias_resolves_to_the_verified_source_root(self):
        source = self.source_fixture()
        alias = self.root / "alias"
        alias.symlink_to(source, target_is_directory=True)
        database = self.compile_database(alias / "crypto.cc")
        self.assertEqual({(source / "crypto.cc").resolve()}, BUILD.compiled_sources(database, source.resolve()))

    def test_compile_database_relative_directory_and_source_paths(self):
        source = self.source_fixture()
        database = self.compile_database(source / "crypto.cc", directory=".", relative=True)
        notices, evidence = BUILD.collect_source_notices(
            database, source, "    ../source/crypto.cc\n    ../source/part.cc.inc\n")
        self.assertIn("Included Author", notices["part.cc.inc"])
        self.assertEqual(["crypto.cc", "part.cc.inc"], evidence["dependency_sources"])

    def test_no_compiled_source_match_is_rejected(self):
        source = self.source_fixture()
        database = self.compile_database(self.root / "unrelated.cc")
        with self.assertRaisesRegex(ValueError, "No compiled BoringSSL"):
            BUILD.compiled_sources(database, source)

    def test_missing_dependency_records_are_rejected(self):
        source = self.source_fixture()
        database = self.compile_database(source / "crypto.cc")
        with self.assertRaisesRegex(ValueError, "Missing BoringSSL compiler dependency"):
            BUILD.collect_source_notices(database, source, "")

    def test_unknown_source_without_notice_is_rejected(self):
        source = self.source_fixture()
        (source / "part.cc.inc").write_text("int value;")
        database = self.compile_database(source / "crypto.cc")
        with self.assertRaisesRegex(ValueError, "Missing source/dependency notice"):
            BUILD.collect_source_notices(database, source, "    ../source/crypto.cc\n    ../source/part.cc.inc\n")

    def test_generated_assembly_uses_upstream_dst_mapping_and_fiat_license(self):
        source = self.source_fixture()
        (source / "asm").mkdir()
        (source / "asm/cipher-armx.pl").write_text("#!/usr/bin/perl\n# Copyright Assembly Author.\n# Licensed under OpenSSL.\n$code = 1;")
        generated = source / "gen/bcm/cipher-armv8-linux.S"
        generated.parent.mkdir(parents=True)
        generated.write_text("// Generated from Perl.\n.text\n")
        fiat = source / "third_party/fiat"
        fiat.mkdir(parents=True)
        (fiat / "LICENSE").write_text("Copyright Fiat Authors. MIT License.\n")
        (fiat / "generated.h").write_text("int generated;")
        (source / "build.json").write_text(json.dumps({"bcm": {"perlasm_aarch64": [
            {"src": "asm/cipher-armx.pl", "dst": "cipher-armv8"}]}}))
        database = self.compile_database(generated)
        notices, evidence = BUILD.collect_source_notices(database, source,
            "    ../source/gen/bcm/cipher-armv8-linux.S\n    ../source/third_party/fiat/generated.h\n")
        self.assertIn("Assembly Author", notices["asm/cipher-armx.pl"])
        self.assertEqual((fiat / "LICENSE").read_text(), notices["third_party/fiat/LICENSE"])
        self.assertEqual({"gen/bcm/cipher-armv8-linux.S": "asm/cipher-armx.pl"}, evidence["generated_assembly_inputs"])
        (source / "asm/cipher-armx.pl").write_text("$code = 1;")
        with self.assertRaisesRegex(ValueError, "Missing generated assembly source notice"):
            BUILD.collect_source_notices(database, source,
                "    ../source/gen/bcm/cipher-armv8-linux.S\n    ../source/third_party/fiat/generated.h\n")

    def test_assembly_public_domain_attribution_after_architecture_guard(self):
        source = "#include <asm.h>\n#if defined(ARM)\n# Taken from the public domain by Example.\n.text\n"
        self.assertEqual("# Taken from the public domain by Example.", BUILD.leading_notice(source, assembly=True))

    def test_perl_notice_preserves_comments_after_decorative_separator(self):
        source = ("#!/usr/bin/perl\n# Copyright Example.\n#----\n# Relicensed under ISC.\n"
                  "## Original author: Example Author.\n#====\n# These permission terms also apply.\n$code = 1;")
        self.assertEqual(source.partition("\n$code")[0], BUILD.leading_notice(source, perl=True))

    def test_aligned_load_does_not_hide_misaligned_relro(self):
        headers = "LOAD 0x0 0x0 0x0 0x1000 0x1000 R 0x4000\nGNU_RELRO 0x0 0x15260 0x15260 0x800 0xda0 R 0x1"
        with self.assertRaisesRegex(ValueError, "RELRO end"):
            BUILD.validate_program_headers(headers, "arm64-v8a")

    def test_aligned_load_and_relro_are_accepted(self):
        BUILD.validate_program_headers("LOAD 0x0 0x0 0x0 0x1000 0x1000 R 0x4000\nGNU_RELRO 0x0 0x15260 0x15260 0x800 0x2da0 R 0x1", "arm64-v8a")

    def test_missing_program_headers_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Missing LOAD/RELRO"):
            BUILD.validate_program_headers("", "arm64-v8a")


if __name__ == "__main__":
    unittest.main()
