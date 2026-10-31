#!/usr/bin/env python3
"""Check the committed libadb inputs, recipe and notices without network access."""
import hashlib
import json
from pathlib import Path
import zipfile
import sys

root = Path(__file__).resolve().parents[1]
provenance = json.loads((root / "third_party/libadb/provenance.json").read_text())
def verify(path, expected):
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise SystemExit(f"SHA-256 mismatch: {path.relative_to(root)}")

for name, digest in provenance["recipe_input_sha256"].items():
    verify(root / "third_party/libadb" / name, digest)
libraries = root / "core/device/src/main/jniLibs"
expected_paths = {f"{abi}/libadb.so" for abi in provenance["libraries"]}
assert {p.relative_to(libraries).as_posix() for p in libraries.rglob("*.so")} == expected_paths
for abi, entry in provenance["libraries"].items():
    verify(libraries / abi / "libadb.so", entry["sha256"])
for name, digest in provenance["license_output_sha256"].items():
    verify(root / "app/src/main/assets/licenses/libadb" / name, digest)
for apk in sys.argv[1:]:
    with zipfile.ZipFile(apk) as archive:
        for skill in (root / "skills").rglob("*"):
            if skill.is_file():
                assert archive.read("assets/skills/" + skill.relative_to(root / "skills").as_posix()) == skill.read_bytes()
        actual = {n for n in archive.namelist() if n.endswith("/libadb.so")}
        assert actual == {"lib/" + p for p in expected_paths}
        for abi, entry in provenance["libraries"].items():
            assert hashlib.sha256(archive.read(f"lib/{abi}/libadb.so")).hexdigest() == entry["sha256"]
        for name, digest in provenance["license_output_sha256"].items():
            assert hashlib.sha256(archive.read("assets/licenses/libadb/" + name)).hexdigest() == digest
print("libadb source recipe, binaries and notice hashes verified")
