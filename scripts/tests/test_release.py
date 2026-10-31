"""Behavioral checks for release identities and signed artifact verification."""
from pathlib import Path
import hashlib
import importlib.util
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release', Path(__file__).resolve().parents[1] / 'release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
SOURCE = '''
        applicationId = "com.droidance"
        minSdk = 28
        versionCode = 2
        versionName = "0.2.0-beta.1"
'''


class ReleaseTests(unittest.TestCase):
    def test_identity_rejects_ambiguous_or_invalid_public_versions(self):
        self.assertEqual(2, release.build_identity(SOURCE)['version_code'])
        for invalid in ('01.0.0', '0.2.0-beta.01', '20261023-V3', '0.2', '1.0.0+build'):
            with self.subTest(version=invalid), self.assertRaises(ValueError):
                release.build_identity(SOURCE.replace('0.2.0-beta.1', invalid))
        with self.assertRaises(ValueError):
            release.build_identity(SOURCE + '\nversionCode = 3\n')
        with self.assertRaises(ValueError):
            release.build_identity(SOURCE.replace('versionCode = 2', 'versionCode = 0'))

    def test_tag_commit_clean_source_and_reserved_codes_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'app').mkdir()
            (root / 'app/build.gradle.kts').write_text(SOURCE)
            (root / 'CHANGELOG.md').write_text('## [0.2.0-beta.1] - 2026-10-23\n\n### Fixed\n\n- Upgrade handling.\n')
            def command(*args, **kwargs):
                if args[:3] == ('git', 'rev-parse', 'HEAD'):
                    return 'commit'
                if args[:2] == ('git', 'rev-parse'):
                    return 'commit'
                if args[:2] == ('git', 'status'):
                    return ''
                if args[:2] == ('git', 'tag'):
                    return 'v0.1.0\nv0.2.0-beta.1'
                if args[:2] == ('git', 'show'):
                    return SOURCE.replace('versionCode = 2', 'versionCode = 1')
                raise AssertionError(args)
            with patch.object(release, 'run', side_effect=command):
                identity, notes = release.tagged_identity('v0.2.0-beta.1', root)
                self.assertTrue(identity['prerelease'])
                self.assertIn('Upgrade handling', notes)
                with self.assertRaises(ValueError):
                    release.tagged_identity('v0.3.0', root)
            def wrong_commit(*args, **kwargs):
                if args[:2] == ('git', 'rev-parse') and args[2] != 'HEAD':
                    return 'other-commit'
                return command(*args, **kwargs)
            with patch.object(release, 'run', side_effect=wrong_commit), self.assertRaisesRegex(ValueError, 'commit'):
                release.tagged_identity('v0.2.0-beta.1', root)
            def dirty_source(*args, **kwargs):
                return ' M app/build.gradle.kts' if args[:2] == ('git', 'status') else command(*args, **kwargs)
            with patch.object(release, 'run', side_effect=dirty_source), self.assertRaisesRegex(ValueError, 'clean'):
                release.tagged_identity('v0.2.0-beta.1', root)
            def reused_code(*args, **kwargs):
                return SOURCE if args[:2] == ('git', 'show') else command(*args, **kwargs)
            with patch.object(release, 'run', side_effect=reused_code), self.assertRaisesRegex(ValueError, 'exceed'):
                release.tagged_identity('v0.2.0-beta.1', root)

    def test_signed_apk_must_match_package_version_code_sdk_and_certificate(self):
        expected = release.build_identity(SOURCE)
        cert = 'a' * 64
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'app.apk'
            apk.write_bytes(b'final signed artifact')
            def command(*args, **kwargs):
                if args[1] == 'manifest':
                    key = {'application-id': 'package', 'version-name': 'version_name',
                           'version-code': 'version_code', 'min-sdk': 'min_sdk'}[args[2]]
                    return str(expected[key])
                if args[1] == 'verify':
                    return 'Signer #1 certificate SHA-256 digest: ' + cert
                return ''
            with patch.object(release, 'run', side_effect=command):
                metadata = release.verify_apk(apk, expected, Path(directory), cert)
                self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), metadata['sha256'])
                with self.assertRaisesRegex(ValueError, 'certificate'):
                    release.verify_apk(apk, expected, Path(directory), 'b' * 64)
                for key, invalid in [('package', 'other.app'), ('version_code', 1),
                                     ('version_name', '0.3.0'), ('min_sdk', 30)]:
                    with self.subTest(key=key), self.assertRaises(ValueError):
                        release.verify_apk(apk, dict(expected, **{key: invalid}), Path(directory), cert)
            with patch.object(release, 'run', side_effect=RuntimeError('Signature rejected')):
                with self.assertRaises(RuntimeError):
                    release.verify_apk(apk, expected, Path(directory), cert)


if __name__ == '__main__':
    unittest.main()
