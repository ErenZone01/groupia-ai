"""Freeze Git blob bytes of migrations 001–022; never writes a migration.

Git's existing text checkout converts CRLF on Windows. Only that checkout
conversion is reversed; the authoritative committed blobs are checked verbatim.
"""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = "873605a27d1b34e5b0dd4a373db5dcfe3270da53"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify(root=ROOT):
    manifest = json.loads((root / "tools/migrations-sha256.json").read_text())
    historical = subprocess.check_output(
        ["git", "ls-tree", "-r", "--name-only", BASE, "Backend/src/main/resources/db/changelog"], cwd=root
    ).decode().splitlines()
    paths = [p for p in historical if Path(p).name[:3].isdigit() and 1 <= int(Path(p).name[:3]) <= 22]
    if set(manifest) != set(paths) or len(paths) != 22:
        raise ValueError("Frozen migration inventory changed")
    for path in paths:
        original = subprocess.check_output(["git", "show", f"{BASE}:{path}"], cwd=root)
        committed = subprocess.check_output(["git", "show", f"HEAD:{path}"], cwd=root)
        if sha(original) != manifest[path] or committed != original:
            raise ValueError(f"Frozen manifest/committed migration changed: {path}")
        # Match the repository's existing Git clean conversion, without writing files.
        working = (root / path).read_bytes().replace(b"\r\n", b"\n")
        if sha(working) != manifest[path]:
            raise ValueError(f"Frozen working migration changed: {path}")
    return len(paths)


if __name__ == "__main__":
    print(f"PASS: {verify()} frozen migrations match the immutable baseline")
