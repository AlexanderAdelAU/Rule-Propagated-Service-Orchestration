"""Check each implementation with only its own JAR and manifest dependencies."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

project = Path(__file__).resolve().parents[1]
bundle = project / "target/deployment"
index = json.loads((bundle / "deployment-index.json").read_text())
for line in (bundle / "SHA256SUMS").read_text().splitlines():
    digest, name = line.split("  ", 1)
    assert hashlib.sha256((bundle / name).read_bytes()).hexdigest() == digest, name
seen = set()
for jar in sorted(bundle.rglob("*.jar")):
    if jar.parent.name != "lib" or jar.name == "service-support.jar":
        with zipfile.ZipFile(jar) as archive:
            classes = {name for name in archive.namelist() if name.endswith(".class")}
            assert not seen.intersection(classes), "Duplicated service classes"
            seen.update(classes)
with tempfile.TemporaryDirectory(prefix="packaged-service-check-") as temporary:
    work = Path(temporary)
    subprocess.run(["java", "com.sun.tools.javac.Main", "--release", str(index["javaRelease"]),
                    "-d", str(work), str(project / "tests/PackagedServiceCheck.java")], check=True)
    for service in index["services"]:
        jar = bundle / service["jar"]
        others = [s["implementationClass"] for s in index["services"] if s != service]
        subprocess.run(["java", "-cp", str(work) + os.pathsep + str(jar), "PackagedServiceCheck",
                        service["implementationClass"], str(jar), service["operation"],
                        str(len(service["inputs"])), service["returnAttribute"], *others], cwd=work, check=True)
print("PASS: checksums, unique classes and all isolated service JARs")
