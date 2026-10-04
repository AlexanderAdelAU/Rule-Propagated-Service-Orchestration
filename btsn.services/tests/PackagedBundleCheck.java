import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        List<Path> jars;
        try (Stream<Path> paths = Files.walk(bundle)) {
            jars = paths.filter(p -> p.toString().endsWith(".jar"))
                .filter(p -> p.getParent().getFileName().toString().equals("services") || p.getFileName().toString().equals("service-support.jar"))
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
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        for (Object entry : services) {
            JSONObject service = (JSONObject) entry;
            Path jar = bundle.resolve(service.get("jar").toString());
            List<String> command = new ArrayList<>(List.of(java, "-cp", args[1] + File.pathSeparator + jar,
                "PackagedServiceCheck", service.get("implementationClass").toString(), jar.toString(),
                service.get("operation").toString(), Integer.toString(((JSONArray) service.get("inputs")).size()),
                service.get("returnAttribute").toString()));
            for (Object other : services) if (other != entry) command.add(((JSONObject) other).get("implementationClass").toString());
            int status = new ProcessBuilder(command).directory(bundle.toFile()).inheritIO().start().waitFor();
            if (status != 0) throw new AssertionError("Isolated JAR invocation failed: " + service.get("service"));
        }
        System.out.println("PASS: checksums, unique classes and all " + services.size() + " isolated service JARs");
    }
}
