from __future__ import annotations

import hashlib
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path


BASE_SHA256 = "2e1a09961dfb41cee66ddc821b2577a41a072566ce45a49bae69267099741e20"
TARGET_ENTRY = "ij/plugin/Orthogonal_Views.class"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: python tools/apply_patch.py <Fiji.app/jars/ij-1.54p.jar>")
        return 2

    jar = Path(sys.argv[1]).expanduser().resolve()
    class_file = Path(__file__).resolve().parents[1] / "patch" / TARGET_ENTRY
    if not jar.is_file() or not class_file.is_file():
        print("missing JAR or patch class", file=sys.stderr)
        return 2

    actual = sha256(jar)
    if actual.lower() != BASE_SHA256:
        print(f"base JAR hash mismatch: {actual}", file=sys.stderr)
        print(f"expected: {BASE_SHA256}", file=sys.stderr)
        return 1

    backup = jar.with_name(jar.name + ".original")
    if backup.exists():
        print(f"backup already exists: {backup}", file=sys.stderr)
        return 1
    shutil.copy2(jar, backup)

    descriptor, temporary_name = tempfile.mkstemp(prefix=jar.name + ".", suffix=".tmp", dir=jar.parent)
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        with zipfile.ZipFile(jar, "r") as source, zipfile.ZipFile(temporary, "w") as target:
            found = False
            for info in source.infolist():
                data = source.read(info.filename)
                if info.filename == TARGET_ENTRY:
                    data = class_file.read_bytes()
                    found = True
                    info.compress_type = zipfile.ZIP_DEFLATED
                target.writestr(info, data)
            if not found:
                raise RuntimeError(f"missing {TARGET_ENTRY} in {jar}")
        temporary.replace(jar)
    except Exception:
        temporary.unlink(missing_ok=True)
        backup.replace(jar)
        raise

    print(f"patched: {jar}")
    print(f"backup:  {backup}")
    print(f"sha256:  {sha256(jar)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
