import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Java-only coordinator; each implementation is checked in an isolated JVM. */
public final class PackagedBundleCheck {
    public static void main(String[] args) throws Exception {
        Path bundle = Path.of(args[0]).toAbsolutePath();
        for (String line : Files.readAllLines(bundle.resolve("SHA256SUMS"))) {
            String[] parts = line.split("  ", 2);
            StringBuilder digest = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(bundle.resolve(parts[1]))))
                digest.append(String.format("%02x", value & 0xff));
            if (!digest.toString().equals(parts[0])) throw new AssertionError("Checksum mismatch: " + parts[1]);
        }
        Set<String> seen = new HashSet<>();
        Path serviceRoot = bundle.resolve("services");
        List<Path> jars;
        try (Stream<Path> paths = Files.walk(bundle)) {
            jars = paths.filter(p -> p.toString().endsWith(".jar"))
                .filter(p -> p.startsWith(serviceRoot) || p.equals(bundle.resolve("lib/service-support.jar")))
                .sorted().collect(Collectors.toList());
        }
        for (Path path : jars) {
            try (JarFile jar = new JarFile(path.toFile())) {
                for (String name : jar.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class")).collect(Collectors.toList())) {
                    if (!seen.add(name)) throw new AssertionError("Duplicate service class: " + name);
                    if (name.startsWith("org/btsn/places/") || name.startsWith("org/btsn/handlers/") || name.startsWith("org/btsn/invocation/"))
                        throw new AssertionError("Host infrastructure in service JAR: " + name);
                }
            }
        }
        JSONObject index = (JSONObject) new JSONParser().parse(Files.readString(bundle.resolve("deployment-index.json")));
        JSONArray services = (JSONArray) index.get("services");
        Set<Path> indexedJars = new HashSet<>();
        for (Object entry : services) {
            JSONObject service = (JSONObject) entry;
            String domain = (String) service.get("domain");
            if (domain == null || !domain.matches("[a-z][a-z0-9_-]*")) throw new AssertionError("Invalid indexed domain: " + domain);
            Path relative = Path.of(service.get("jar").toString());
            if (!relative.equals(Path.of("services", domain, service.get("service") + ".jar")))
                throw new AssertionError("Service JAR does not match its indexed domain: " + relative);
            Path path = bundle.resolve(relative);
            indexedJars.add(path);
            Set<Path> expectedDependencies = new HashSet<>();
            for (Object dependency : (JSONArray) service.get("runtimeDependencies"))
                expectedDependencies.add(bundle.resolve(dependency.toString()).normalize());
            try (JarFile jar = new JarFile(path.toFile())) {
                String manifestPath = jar.getManifest().getMainAttributes().getValue("Class-Path");
                Set<Path> actualDependencies = new HashSet<>();
                for (String dependency : manifestPath.split("\\s+")) {
                    Path resolved = path.getParent().resolve(dependency).normalize();
                    if (!Files.isRegularFile(resolved)) throw new AssertionError("Missing manifest dependency: " + resolved);
                    actualDependencies.add(resolved);
                }
                if (!actualDependencies.equals(expectedDependencies))
                    throw new AssertionError("Manifest dependencies differ from deployment index: " + path);
            }
        }
        Set<Path> deployedJars = jars.stream().filter(p -> p.startsWith(serviceRoot)).collect(Collectors.toSet());
        if (!deployedJars.equals(indexedJars)) throw new AssertionError("Deployed service JARs differ from deployment index");
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        for (Object entry : services) {
            JSONObject service = (JSONObject) entry;
            Path jar = bundle.resolve(service.get("jar").toString());
            List<String> command = new ArrayList<>(List.of(java, "-cp", args[1] + File.pathSeparator + jar,
                "PackagedServiceCheck", service.get("implementationClass").toString(), jar.toString(),
                service.get("operation").toString(), Integer.toString(((JSONArray) service.get("inputs")).size()),
                service.get("returnAttribute").toString(), Objects.toString(service.get("resultType"), "json")));
            for (Object other : services) {
                String otherClass = ((JSONObject) other).get("implementationClass").toString();
                if (!otherClass.equals(service.get("implementationClass"))) command.add(otherClass);
            }
            int status = new ProcessBuilder(command).directory(bundle.toFile()).inheritIO().start().waitFor();
            if (status != 0) throw new AssertionError("Isolated JAR invocation failed: " + service.get("service"));
        }
        System.out.println("PASS: domain layout, manifest dependencies, checksums, unique classes and all " + services.size() + " isolated service operations");
    }
}
