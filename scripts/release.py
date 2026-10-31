#!/usr/bin/env python3
"""Validate a tagged release and verify the final signed APK before creating a draft."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SEMVER = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?')


def valid_version(value):
    match = SEMVER.fullmatch(value)
    return bool(match) and not any(part.isdigit() and len(part) > 1 and part.startswith('0')
                                   for part in (match[4] or '').split('.'))


def run(*args, cwd=ROOT):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def build_identity(source):
    def field(pattern):
        matches = re.findall(pattern, source, re.MULTILINE)
        if len(matches) != 1:
            raise ValueError('Release identity must have one authoritative Gradle value')
        return matches[0]
    version = field(r'^\s*versionName = "([^"]+)"')
    if not valid_version(version):
        raise ValueError('versionName must use SemVer without build metadata')
    code = int(field(r'^\s*versionCode = ([0-9]+)\s*$'))
    if not 0 < code <= 2100000000:
        raise ValueError('versionCode is outside the Android release range')
    return dict(version_name=version, version_code=code,
                package=field(r'^\s*applicationId = "([^"]+)"'),
                min_sdk=int(field(r'^\s*minSdk = ([0-9]+)\s*$')))


def tagged_identity(tag, root=ROOT):
    expected = build_identity((root / 'app/build.gradle.kts').read_text())
    if tag != 'v' + expected['version_name']:
        raise ValueError('Tag must be v followed by the exact Gradle versionName')
    commit = run('git', 'rev-parse', 'HEAD', cwd=root)
    if run('git', 'rev-parse', f'refs/tags/{tag}^{{commit}}', cwd=root) != commit:
        raise ValueError('Checked-out commit does not match the existing release tag')
    if run('git', 'status', '--porcelain', '--untracked-files=normal', cwd=root):
        raise ValueError('Release source must be clean')
    for other in run('git', 'tag', '--list', 'v*', cwd=root).splitlines():
        if other == tag or not valid_version(other[1:]):
            continue
        previous = build_identity(run('git', 'show', f'{other}:app/build.gradle.kts', cwd=root))
        if expected['version_code'] <= previous['version_code']:
            raise ValueError(f'versionCode must exceed reserved release {other}')
    changelog = (root / 'CHANGELOG.md').read_text()
    notes = re.search(r'^## \[' + re.escape(expected['version_name']) + r'\][^\n]*\n(.*?)(?=^## |\Z)',
                      changelog, re.MULTILINE | re.DOTALL)
    if not notes or not notes[1].strip():
        raise ValueError('CHANGELOG.md needs a nonempty entry for this public version')
    return dict(expected, tag=tag, commit=commit, prerelease='-' in expected['version_name']), notes[1].strip()


def verify_apk(apk, expected, sdk, certificate):
    analyzer = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
    tools = sdk / 'build-tools/35.0.0'
    actual = {key: run(str(analyzer), 'manifest', command, str(apk)) for key, command in (
        ('package', 'application-id'), ('version_name', 'version-name'),
        ('version_code', 'version-code'), ('min_sdk', 'min-sdk'))}
    for key in actual:
        if actual[key] != str(expected[key]):
            raise ValueError(f'Final APK {key} does not match the tagged release')
    run(str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk))
    report = run(str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk))
    signers = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', report, re.MULTILINE)
    fingerprint = certificate.replace(':', '').lower()
    if not re.fullmatch('[a-f0-9]{64}', fingerprint) or [s.lower() for s in signers] != [fingerprint]:
        raise ValueError('Final APK signing certificate does not match the distribution identity')
    hasher = hashlib.sha256()
    with apk.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(chunk)
    digest = hasher.hexdigest()
    return dict(expected, apk=apk.name, size_bytes=apk.stat().st_size, sha256=digest,
                signing_certificate_sha256=fingerprint)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    identity = sub.add_parser('identity')
    identity.add_argument('--tag', required=True)
    identity.add_argument('--output', type=Path, required=True)
    verify = sub.add_parser('verify-apk')
    verify.add_argument('--identity', type=Path, required=True)
    verify.add_argument('--apk', type=Path, required=True)
    verify.add_argument('--sdk', type=Path, required=True)
    verify.add_argument('--certificate', required=True)
    args = parser.parse_args()
    if args.command == 'identity':
        data, notes = tagged_identity(args.tag)
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / 'identity.json').write_text(json.dumps(data, indent=2) + '\n')
        (args.output / 'notes.md').write_text(notes + '\n')
        print('Tagged source identity and increasing versionCode verified')
    else:
        expected = json.loads(args.identity.read_text())
        tagged, _ = tagged_identity(expected['tag'])
        if expected != tagged:
            raise ValueError('Prepared identity no longer matches the tagged source')
        metadata = verify_apk(args.apk, expected, args.sdk, args.certificate)
        args.apk.with_suffix('.apk.sha256').write_text(f"{metadata['sha256']}  {args.apk.name}\n")
        (args.apk.parent / 'release.json').write_text(json.dumps(metadata, indent=2) + '\n')
        print('Final APK identity, alignment, signing certificate and SHA-256 verified')


if __name__ == '__main__':
    main()
