"""Run with stock and v0.1.0 ij JAR paths; exercises install and rollback."""
import contextlib
import importlib.util
import io
import shutil
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch


root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("apply_patch", root / "tools/apply_patch.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
expected_jar = "2f880b1489130fef7dcc085c2fc5e39a06a74f02062350cf692b1ebad81cd9f9"
source = root / "src/Snapshot_.groovy"


def install(jar):
    with patch.object(sys, "argv", ["apply_patch.py", str(jar)]):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return installer.main()


(root / "dist").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="patch-test-", dir=root / "dist") as directory:
    work = Path(directory).resolve()
    assert work.is_relative_to((root / "dist").resolve())

    def fixture(name, original, old_script=True):
        app = work / name / "Fiji.app"
        jar = app / "jars/ij-1.54p.jar"
        jar.parent.mkdir(parents=True)
        shutil.copy2(original, jar)
        script = app / "scripts/Plugins/Snapshot_.groovy"
        if old_script:
            script.parent.mkdir(parents=True)
            script.write_bytes(b"old snapshot script\n")
        return jar, script

    for n, original in enumerate(map(Path, sys.argv[1:])):
        jar, script = fixture("accepted-" + str(n), original, n != 0)
        original_hash = installer.sha256(jar)
        assert install(jar) == 0
        assert installer.sha256(jar) == expected_jar
        assert script.read_bytes() == source.read_bytes()
        assert installer.sha256(jar.with_name(jar.name + ".before-v0.2.0")) == original_hash
        if n != 0:
            assert script.with_name(script.name + ".before-v0.2.0").read_bytes() == b"old snapshot script\n"
        assert install(jar) == 1, "Already patched JAR must not be overwritten"

    assert len(sys.argv) == 3, "Pass both stock and published v0.1.0 JAR fixtures"
    stock = Path(sys.argv[1])
    misplaced = work / "loose" / "ij-1.54p.jar"
    misplaced.parent.mkdir()
    shutil.copy2(stock, misplaced)
    assert install(misplaced) == 2
    assert not (work / "scripts").exists(), "Wrong JAR location must not create a Snapshot script"
    assert installer.sha256(misplaced) == installer.sha256(stock)
    jar, script = fixture("unsupported", stock)
    jar.write_bytes(b"unknown jar")
    assert install(jar) == 1
    assert jar.read_bytes() == b"unknown jar"
    assert script.read_bytes() == b"old snapshot script\n"

    for old_script in (False, True):
        jar, script = fixture("locked-" + str(old_script), stock, old_script)
        original_hash = installer.sha256(jar)
        replace = Path.replace

        def locked_replace(self, destination):
            if Path(destination) == jar:
                raise PermissionError("Simulated Windows JAR lock")
            return replace(self, destination)

        with patch.object(Path, "replace", locked_replace):
            try:
                install(jar)
                raise AssertionError("Locked JAR must fail installation")
            except PermissionError:
                pass
        assert installer.sha256(jar) == original_hash
        assert script.read_bytes() == b"old snapshot script\n" if old_script else not script.exists()
        assert not list(jar.parent.glob("*.tmp"))
        assert not list(script.parent.glob("*.tmp"))
        assert install(jar) == 0, "Retry after lock removal must succeed with preserved backups"

print("PASS: stock/v0.1.0 upgrade, backups, Snapshot deployment, unknown hash rejection, lock rollback and retry")
