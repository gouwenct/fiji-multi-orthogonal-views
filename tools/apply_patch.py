from __future__ import annotations

import hashlib
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path


BASE_SHA256 = "2e1a09961dfb41cee66ddc821b2577a41a072566ce45a49bae69267099741e20"
SUPPORTED_SHA256 = {
    BASE_SHA256,
    "15c18090a560e7be334445f6e4f3335efc0fcd1b491f8325ba8402562c95feaf",  # v0.1.0
}
PATCH_ENTRIES = ("ij/plugin/Orthogonal_Views.class", "ij/plugin/DragAndDrop.class")


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
    if jar.name.lower() != "ij-1.54p.jar" or jar.parent.name.lower() != "jars":
        print("expected a Fiji jars/ij-1.54p.jar target", file=sys.stderr)
        return 2
    project_root = Path(__file__).resolve().parents[1]
    patch_root = project_root / "patch"
    snapshot_source = project_root / "src" / "Snapshot_.groovy"
    snapshot = jar.parent.parent / "scripts" / "Plugins" / "Snapshot_.groovy"
    class_files = {
        entry: patch_root / entry
        for entry in PATCH_ENTRIES
    }
    if not jar.is_file() or not snapshot_source.is_file() or not all(path.is_file() for path in class_files.values()):
        print("missing JAR, patch class or Snapshot source", file=sys.stderr)
        return 2

    actual = sha256(jar)
    if actual.lower() not in SUPPORTED_SHA256:
        print(f"unsupported JAR hash: {actual}", file=sys.stderr)
        print("expected stock ij-1.54p.jar or the v0.1.0 release; see PATCH.md", file=sys.stderr)
        return 1

    backup = jar.with_name(jar.name + ".before-v0.2.0")
    snapshot_backup = snapshot.with_name(snapshot.name + ".before-v0.2.0")
    had_snapshot = snapshot.is_file()
    for original, saved in ((jar, backup), (snapshot, snapshot_backup)):
        if saved.exists() and (not original.is_file() or sha256(saved) != sha256(original)):
            print(f"backup differs from current file: {saved}", file=sys.stderr)
            return 1

    descriptor, temporary_name = tempfile.mkstemp(prefix=jar.name + ".", suffix=".tmp", dir=jar.parent)
    os.close(descriptor)
    temporary = Path(temporary_name)
    snapshot_temporary = None
    snapshot_installed = False
    try:
        with zipfile.ZipFile(jar, "r") as source, zipfile.ZipFile(temporary, "w") as target:
            found = set()
            for info in source.infolist():
                data = source.read(info.filename)
                if info.filename in class_files:
                    data = class_files[info.filename].read_bytes()
                    found.add(info.filename)
                    info.compress_type = zipfile.ZIP_DEFLATED
                target.writestr(info, data)
            missing = set(class_files) - found
            if missing:
                raise RuntimeError(f"missing {', '.join(sorted(missing))} in {jar}")
        snapshot.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(prefix="Snapshot_", suffix=".tmp", dir=snapshot.parent)
        os.close(descriptor)
        snapshot_temporary = Path(temporary_name)
        shutil.copyfile(snapshot_source, snapshot_temporary)
        if not backup.exists():
            shutil.copy2(jar, backup)
        if had_snapshot and not snapshot_backup.exists():
            shutil.copy2(snapshot, snapshot_backup)
        snapshot_temporary.replace(snapshot)
        snapshot_installed = True
        temporary.replace(jar)
    except Exception:
        if snapshot_installed:
            if had_snapshot:
                shutil.copy2(snapshot_backup, snapshot)
            else:
                snapshot.unlink()
        raise
    finally:
        temporary.unlink(missing_ok=True)
        if snapshot_temporary is not None:
            snapshot_temporary.unlink(missing_ok=True)

    print(f"patched: {jar}")
    print(f"backup:  {backup}")
    print(f"snapshot: {snapshot}")
    if had_snapshot:
        print(f"snapshot backup: {snapshot_backup}")
    print(f"sha256:  {sha256(jar)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
