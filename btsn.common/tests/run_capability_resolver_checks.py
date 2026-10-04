"""Compile and check the invocation boundary, using only repository dependencies.

Run from any directory: python btsn.common/tests/run_capability_resolver_checks.py
Requires Java 15+ with the jdk.compiler module; no Ant or external downloads.
"""
import os
from pathlib import Path
import subprocess
import tempfile


root = Path(__file__).resolve().parents[2]
common = root / "btsn.common"
libraries = os.pathsep.join(str(path) for path in (common / "lib").glob("*.jar"))
sources = [
    common / "src/org/btsn/invocation/BusinessCapabilityResolver.java",
    common / "src/org/btsn/json/jsonLibrary.java",
    common / "src/org/btsn/business/BaseBusinessService.java",
    common / "src/org/btsn/base/BaseStochasticPetriNetPlace.java",
    common / "src/org/btsn/base/TokenInfo.java",
    common / "src/org/btsn/json/JsonResponseBuilder.java",
    common / "src/org/btsn/json/JsonTokenParser.java",
    common / "src/org/btsn/logger/PetriNetEventLogger.java",
    common / "src/org/btsn/services/StochasticPlaceService.java",
    *sorted((common / "src/org/btsn/business/financial").glob("*.java")),
    common / "tests/org/btsn/invocation/BusinessCapabilityResolverTest.java",
]
# Deliberately exclude all P1-P6 placeholder classes: the resolver must invoke
# business implementations without a physical adapter available as a fallback.

helpers = [root / f"btsn.petrinet.places.p{index}/src/org/btsn/handlers/ServiceHelper.java" for index in range(1, 7)]
assert len({path.read_bytes() for path in helpers}) == 1, "P1-P6 ServiceHelper copies differ"
with tempfile.TemporaryDirectory(prefix="capability-check-") as temporary:
    work = Path(temporary)
    classes = work / "classes"
    classes.mkdir()
    subprocess.run(["java", "com.sun.tools.javac.Main", "--release", "15", "-cp", libraries,
                    "-d", str(classes), *map(str, sources), str(helpers[0])], check=True)
    classpath = str(classes) + os.pathsep + libraries
    # Compile every replicated invocation boundary independently.
    for index, helper in enumerate(helpers[1:], start=2):
        output = work / f"p{index}"
        output.mkdir()
        subprocess.run(["java", "com.sun.tools.javac.Main", "--release", "15", "-cp", classpath,
                        "-d", str(output), str(helper)], check=True)
    subprocess.run(["java", "-cp", classpath, "org.btsn.invocation.BusinessCapabilityResolverTest",
                    str(common)], cwd=work, check=True)
print("PASS: all six synchronized invocation boundaries compile")
print("PASS: metadata-selected implementations execute on all six host identities with all placeholders absent")
