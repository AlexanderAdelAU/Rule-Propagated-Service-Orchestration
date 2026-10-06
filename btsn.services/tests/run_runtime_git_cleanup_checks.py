#!/usr/bin/env python3
"""Exercise backup failures, live locks and the real Git fast-forward/restore transition."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile

SOURCE = Path(__file__).resolve().parents[1] / "tools/RuntimeGitCleanup.java"
TEMP = Path(tempfile.mkdtemp(prefix="runtime cleanup checks "))
CLASSES = TEMP / "classes"
CLASSES.mkdir()
subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "11", "-d", str(CLASSES), str(SOURCE)], check=True)
ROOTS = ["btsn.common.Monitor/ServiceAnalysisDataBase", "btsn.common.Monitor/processMonitorDB",
         "btsn.common.Monitor/ServiceMonitorDatabase", "btsn.common.Monitor/RuleFolder.v999",
         "btsn.common.Monitor/charts", "btsn.common.Monitor/WorkflowRunMetadata",
         "btsn.rpso.places.p1/ServiceAnalysisDatabase", "btsn.rpso.places.p1/RuleFolder.v001",
         "btsn.common.eventgenerators/RuleFolder.v002"]

def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()

def fixture(name):
    root = TEMP / name / "BTSN"
    root.mkdir(parents=True)
    git(root, "init", "-q")
    git(root, "config", "user.name", "Cleanup Check")
    git(root, "config", "user.email", "cleanup-check@example.invalid")
    (root / "btsn.services").mkdir()
    (root / ".gitignore").write_text("**/target/\n")
    for index, relative in enumerate(ROOTS):
        directory = root / relative
        (directory / "empty").mkdir(parents=True)
        (directory / "data.bin").write_bytes(bytes(range(256)) + str(index).encode())
    source = root / "btsn.common.Monitor/src/Untouched.java"
    source.parent.mkdir()
    source.write_bytes(b"// user source\r\n")
    settings = root / "btsn.rpso.places.p1/.project"
    settings.write_bytes(b"<project>keep</project>\r\n")
    return root

def run(root, action, ok=True):
    result = subprocess.run(["java", "-cp", str(CLASSES), "RuntimeGitCleanup", action, str(root)], capture_output=True, text=True)
    assert (result.returncode == 0) == ok, result.stdout + result.stderr
    return result

def hashes(root):
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for relative in ROOTS for p in (root / relative).rglob("*") if p.is_file()}

def backup(result):
    return Path(next(line.split(": ", 1)[1] for line in result.stdout.splitlines() if line.startswith("BACKUP READY:")))

def final_ignore(root):
    (root / ".gitignore").write_text("**/target/\n# Runtime Git cleanup: local-only outputs\n"
                                 "**/ServiceAnalysisData[Bb]ase/\n**/ServiceMonitorDatabase/\n**/processMonitorDB/\n"
                                 "**/RuleFolder.*/\n**/charts/\n**/WorkflowRunMetadata/\n")

root = fixture("fast forward ü")
git(root, "add", ".")
git(root, "commit", "-qm", "before cleanup")
before = git(root, "rev-parse", "HEAD")
final_ignore(root)
for relative in ROOTS: git(root, "rm", "-rq", "--cached", "--", relative)
git(root, "add", ".gitignore")
git(root, "commit", "-qm", "tracking cleanup")
after = git(root, "rev-parse", "HEAD")
git(root, "reset", "--hard", before)
(root / ROOTS[0] / "data.bin").write_bytes(b"LATEST LIVE RESULTS\x00")
expected = hashes(root)
prepare = run(root, "prepare")
saved = backup(prepare)
assert saved.parent == root.parent
assert all(not (root / relative).exists() for relative in ROOTS)
assert (root / "btsn.common.Monitor/src/Untouched.java").read_bytes() == b"// user source\r\n"
assert (root / "btsn.rpso.places.p1/.project").read_bytes() == b"<project>keep</project>\r\n"
run(root, "prepare")  # Repeat preparation preserves the same backup.
run(root, "restore", ok=False)  # Final ignore/tracking cleanup is required.
git(root, "merge", "--ff-only", after)
run(root, "restore")
assert hashes(root) == expected
assert not git(root, "status", "--porcelain"), git(root, "status", "--porcelain")
assert all((root / relative / "empty").is_dir() for relative in ROOTS)
(root / ROOTS[0] / "data.bin").write_bytes(b"NEXT WORKFLOW RESULTS")
run(root, "restore")  # Repeated restore never overwrites a newer run.
assert (root / ROOTS[0] / "data.bin").read_bytes() == b"NEXT WORKFLOW RESULTS"
assert saved.exists()
print("PASS: modified live data, spaces/Unicode paths, empty directories, actual Git fast-forward and ignored restoration")

root = fixture("cancel")
expected = hashes(root)
run(root, "prepare")
run(root, "cancel")
assert hashes(root) == expected
print("PASS: cancellation restores exact original data without the tracking cleanup")

root = fixture("tamper")
saved = backup(run(root, "prepare"))
(saved / "snapshot" / ROOTS[0] / "data.bin").write_bytes(b"corrupt")
final_ignore(root)
run(root, "restore", ok=False)
assert all(not (root / relative).exists() for relative in ROOTS)
assert (saved / "parked" / ROOTS[0] / "data.bin").exists()
print("PASS: corrupt backup rejected before any destination is restored")

root = fixture("new results")
run(root, "prepare")
final_ignore(root)
new = root / ROOTS[-1]
new.mkdir(parents=True)
(new / "data.bin").write_bytes(b"NEW DATA")
run(root, "restore", ok=False)
assert not (root / ROOTS[0]).exists()
assert (new / "data.bin").read_bytes() == b"NEW DATA"
print("PASS: differing destination data rejected before any restoration")

root = fixture("symbolic link")
(root / ROOTS[0] / "link").symlink_to(root / "btsn.common.Monitor/src/Untouched.java")
run(root, "prepare", ok=False)
assert all((root / relative).exists() for relative in ROOTS)
assert not (root / "btsn.services/target/runtime-git-cleanup/state.properties").exists()
print("PASS: unsupported links rejected with source and runtime folders intact")

# A real cross-process OS lock, using the same file name/locking mechanism as Derby.
lock_source = TEMP / "HoldLock.java"
lock_source.write_text('import java.nio.file.*; import java.nio.channels.*; public class HoldLock { public static void main(String[] a) throws Exception { try(FileChannel c=FileChannel.open(Path.of(a[0]),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock l=c.lock()) { System.out.println("LOCKED");System.out.flush();System.in.read(); } } }')
subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", str(CLASSES), str(lock_source)], check=True)
root = fixture("locked database")
process = subprocess.Popen(["java", "-cp", str(CLASSES), "HoldLock", str(root / ROOTS[0] / "dbex.lck")], stdout=subprocess.PIPE, stdin=subprocess.PIPE, text=True)
try:
    assert process.stdout.readline().strip() == "LOCKED"
    expected = hashes(root)
    run(root, "prepare", ok=False)
    assert hashes(root) == expected
    assert all((root / relative).exists() for relative in ROOTS)
finally:
    process.communicate(input="\n", timeout=10)
print("PASS: live database lock rejected without changing any original data")
print("PASS: all runtime Git cleanup preservation checks")
