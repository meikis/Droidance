#!/usr/bin/env python3
"""Reject recognizable credentials without printing their values (a scan is not a full audit)."""
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
paths = subprocess.check_output(
    ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root
).decode().split("\0")
patterns = {
    "provider credential": r"(?:sk-(?:proj-|or-v1-|ant-api\d+-)[A-Za-z0-9_-]{24,}|sk-[A-Za-z0-9]{40,}|ark-[a-f0-9-]{36,}|AIza[0-9A-Za-z_-]{35})",
    "GitHub credential": r"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})",
    "AWS access key": r"AKIA[0-9A-Z]{16}",
    "private key material": r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s*\n[A-Za-z0-9+/=\r\n]{48,}"
}
findings = []
for name in paths:
    if not name:
        continue
    try:
        content = (root / name).read_text()
    except (OSError, UnicodeError):
        continue
    for label, pattern in patterns.items():
        for match in re.finditer(pattern, content):
            findings.append(f"{name}:{content.count(chr(10), 0, match.start()) + 1}: {label}")
if findings:
    print("Potential credentials require review:\n" + "\n".join(findings), file=sys.stderr)
    raise SystemExit(1)
print("No recognizable credentials found in the candidate source tree")
