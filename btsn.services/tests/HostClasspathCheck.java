import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Confirms the real host classpath resolves implementations only from deployed JARs. */
public final class HostClasspathCheck {
    public static void main(String[] args) throws Exception {
        Path bundle = Path.of(args[0]);
        Path host = Path.of(args[1]);
        origin("org.btsn.handlers.ServiceHelper", host);
        origin("org.btsn.handlers.ServiceThread", host);
        origin("org.btsn.invocation.BusinessCapabilityResolver", Path.of(args[2]));
        JSONObject index = (JSONObject) new JSONParser().parse(Files.readString(bundle.resolve("deployment-index.json")));
        for (Object entry : (JSONArray) index.get("services")) {
            JSONObject service = (JSONObject) entry;
            origin(service.get("implementationClass").toString(), bundle.resolve(service.get("jar").toString()));
        }
        Path support = bundle.resolve("lib/service-support.jar");
        try (JarFile jar = new JarFile(support.toFile())) {
            for (String name : jar.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class")).collect(Collectors.toList())) {
                origin(name.substring(0, name.length() - 6).replace('/', '.'), support);
            }
        }
        System.out.println("PASS: " + host.getFileName() + " loads service implementations and helpers exclusively from deployed JARs");
    }
    private static void origin(String name, Path expected) throws Exception {
        ClassLoader loader = HostClasspathCheck.class.getClassLoader();
        Class<?> type = Class.forName(name, false, loader);
        Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!actual.equals(expected.toAbsolutePath().normalize())) throw new AssertionError(name + " loaded from " + actual);
        java.util.List<URL> copies = Collections.list(loader.getResources(name.replace('.', '/') + ".class"));
        if (copies.size() != 1) throw new AssertionError("Duplicate runtime copies of " + name + ": " + copies);
    }
}
