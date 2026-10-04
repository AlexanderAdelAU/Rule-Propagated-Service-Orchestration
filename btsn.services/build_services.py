"""Catalogue-driven service packaging. Requires Python 3 and a JDK 15+.

No host project, existing bin directory, Ant installation or downloaded library
is used. Run from any directory; --config accepts another packaging inventory.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


def archive(path, entries):
    """Stable ZIP/JAR bytes, including timestamps and file modes."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as output:
        for name, content in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            output.writestr(info, content)


def manifest(classpath):
    # JAR manifest lines are limited to 72 bytes; continuation starts with space.
    lines = ["Manifest-Version: 1.0"]
    value = "Class-Path: " + " ".join(classpath)
    while len(value.encode("ascii")) > 70:
        lines.append(value[:70])
        value = " " + value[70:]
    lines.extend([value, "", ""])
    return "\r\n".join(lines).encode("ascii")


def build(config_path, destination):
    config = json.loads(config_path.read_text())
    resolve = lambda name: (config_path.parent / name).resolve()
    source_root = resolve(config["sourceRoot"])
    libraries = [resolve(name) for name in config["runtimeLibraries"]]
    if len({p.name for p in libraries}) != len(libraries):
        raise ValueError("Runtime library filenames must be unique")
    for library in libraries:
        if not library.is_file():
            raise FileNotFoundError(library)
    services, catalogues = [], {}
    for name in config["catalogues"]:
        path = resolve(name)
        if path.name in catalogues:
            raise ValueError("Catalogue filenames must be unique")
        catalogues[path.name] = path.read_bytes()
        services.extend(json.loads(catalogues[path.name])["services"])
    if not services:
        raise ValueError("Packaging inventory has no services")
    for key in ("service", "implementationClass"):
        if len({s[key] for s in services}) != len(services):
            raise ValueError("Duplicate " + key)
    for service in services:
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", service["service"]):
            raise ValueError("Invalid service name")
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+", service["implementationClass"]):
            raise ValueError("Invalid implementation class")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="service-build-", dir=destination.parent) as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        sources = [source_root / (s["implementationClass"].replace(".", "/") + ".java") for s in services]
        subprocess.run(["java", "com.sun.tools.javac.Main", "--release", str(config["javaRelease"]),
                        "-implicit:class", "-sourcepath", str(source_root), "-cp",
                        os.pathsep.join(map(str, libraries)), "-d", str(classes),
                        *map(str, sources)], check=True)
        # javac follows compile-time source dependencies. Split implementation
        # classes (including their nested classes) from their shared helpers.
        owned = {s["implementationClass"].replace(".", "/"): s["service"] for s in services}
        contents = {s["service"]: {} for s in services}
        support = {}
        for path in sorted(classes.rglob("*.class")):
            relative = path.relative_to(classes).as_posix()
            if relative.startswith(("org/btsn/handlers/", "org/btsn/invocation/", "org/btsn/places/")):
                raise ValueError("Service dependency crosses into host infrastructure: " + relative)
            owner = owned.get(relative[:-6].split("$")[0])
            (contents[owner] if owner else support)[relative] = path.read_bytes()
        bundle = work / "deployment"
        for folder in ("services", "lib", "catalogues"):
            (bundle / folder).mkdir(parents=True)
        archive(bundle / "lib/service-support.jar", {"META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n", **support})
        for library in libraries:
            shutil.copyfile(library, bundle / "lib" / library.name)
        index = []
        for service in services:
            jar = "services/" + service["service"] + ".jar"
            dependencies = ["../lib/service-support.jar", *["../lib/" + p.name for p in libraries]]
            archive(bundle / jar, {"META-INF/MANIFEST.MF": manifest(dependencies), **contents[service["service"]]})
            index.append({**service, "jar": jar, "runtimeDependencies": ["lib/service-support.jar", *["lib/" + p.name for p in libraries]]})
        for name, content in catalogues.items():
            (bundle / "catalogues" / name).write_bytes(content)
        (bundle / "deployment-index.json").write_text(json.dumps({"javaRelease": config["javaRelease"], "services": index}, indent=2) + "\n")
        (bundle / "README.txt").write_text(
            "Service deployment bundle\n\n"
            "Install a selected services/<service>.jar together with lib/. Keep their relative paths.\n"
            "Add the service JAR to the generic host JVM classpath; its manifest supplies support libraries.\n"
            "Alternatively add services/* and lib/* to the JVM classpath (use the platform separator).\n"
            "Host infrastructure and deployment/rule configuration are supplied separately.\n"
            "Catalogues and deployment-index.json describe available implementations; packaging does not activate them.\n"
            "Remove duplicate service implementations from the host classpath when adopting this bundle.\n"
            "Restart the host JVM after changing its classpath. Requires Java 15 or newer.\n"
        )
        files = {p.relative_to(bundle).as_posix(): p.read_bytes() for p in bundle.rglob("*") if p.is_file()}
        (bundle / "SHA256SUMS").write_text("".join(hashlib.sha256(content).hexdigest() + "  " + name + "\n" for name, content in sorted(files.items())))
        # Replace only our fixed generated output after successful compilation.
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(bundle, destination)
    archive(destination.parent / "service-deployment.zip", {p.relative_to(destination).as_posix(): p.read_bytes() for p in destination.rglob("*") if p.is_file()})
    print("Built " + str(len(services)) + " service JARs: " + str(destination))
    print("Bundle: " + str(destination.parent / "service-deployment.zip"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("packaging.json"))
    args = parser.parse_args()
    build(args.config.resolve(), Path(__file__).resolve().parent / "target/deployment")
