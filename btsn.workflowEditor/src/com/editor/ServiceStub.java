package com.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A starting Java class for a service the designer has just described: it compiles, honours the contract and
 * takes the declared routes in turn, so the process can be run before the real logic is written. The comments
 * say where that logic goes. It follows the credit-check service of ServiceTutorial.md.
 */
public final class ServiceStub {
    private ServiceStub() { }

    /** org.btsn.business.<domain>.<Service>, the class name the catalogue entry should give. */
    /** Marks code the editor wrote; until it is gone the editor reminds the designer to write the real logic. */
    public static final String MARKER = "RPSO-STARTING-CODE";

    /** True while the class's source still holds the editor's starting code. */
    public static boolean isStartingCode(File common, String implementationClass) {
        if (common == null || implementationClass == null || implementationClass.isEmpty()) return false;
        File source = sourceFile(common, implementationClass);
        try { return source.isFile() && new String(java.nio.file.Files.readAllBytes(source.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains(MARKER); }
        catch (java.io.IOException e) { return false; }
    }

    public static String className(String domain, String service) { return "org.btsn.business." + domain + "." + service; }

    /** Where the class's source belongs: btsn.common/src/org/btsn/business/<domain>/<Service>.java. */
    public static File sourceFile(File common, String implementationClass) {
        return new File(common, "src/" + implementationClass.replace('.', '/') + ".java");
    }

    /** True for a Java identifier the packaging accepts as a service, operation or input name. */
    public static boolean identifier(String name) { return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*"); }

    public static String source(String implementationClass, CatalogueWriter.Entry entry) {
        int dot = implementationClass.lastIndexOf('.');
        String pkg = implementationClass.substring(0, dot), name = implementationClass.substring(dot + 1);
        List<String> parameters = new ArrayList<>();
        for (String input : entry.inputs) parameters.add("String " + input);
        // With no routing values declared it returns true, the value an unconditional or true-guarded arc takes.
        List<String> routes = entry.routes.isEmpty() ? java.util.Collections.singletonList("true") : entry.routes;
        List<String> quoted = new ArrayList<>();
        for (String route : routes) quoted.add("\"" + route.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        String n = "\n";
        return "package " + pkg + ";" + n + n
            + "import org.btsn.business.BaseBusinessService;" + n
            + "import org.json.simple.JSONObject;" + n + n
            + "/**" + n
            + comment(entry.description.isEmpty() ? name + "." : entry.description)
            + " *" + n
            + " * Starting code written by the process editor when " + name + " was added to the catalogue. It compiles and" + n
            + " * keeps the contract, so the process can be run now; replace the body of " + entry.operation + " with the real logic." + n
            + (entry.returns.isEmpty() ? "" : " *" + n + " * Returns: " + entry.returns.replace("*/", "* /") + n)
            + " */" + n
            + "public class " + name + " extends BaseBusinessService {" + n + n
            + "    /** Calls so far on this host: the starting code takes the routes in turn, one per call. */" + n
            + "    private static final java.util.concurrent.atomic.AtomicInteger CALLS = new java.util.concurrent.atomic.AtomicInteger();" + n + n
            + "    private final String sequenceId;" + n + n
            + "    public " + name + "() { this(\"\"); }" + n + n
            + "    /** The host passes the token's sequence id. */" + n
            + "    public " + name + "(String sequenceId) { this.sequenceId = sequenceId == null ? \"\" : sequenceId; }" + n + n
            + "    /** " + String.join(", ", entry.inputs) + " -> " + entry.returnAttribute + " */" + n
            + "    @SuppressWarnings(\"unchecked\")" + n
            + "    public String " + entry.operation + "(" + String.join(", ", parameters) + ") {" + n
            + "        // " + MARKER + ": delete this line when the real logic replaces the starting code below." + n
            + "        // TODO: the real logic. Read the inputs (each is the text the previous place published, often JSON;" + n
            + "        // BaseBusinessService's textValue and numberValue read fields from it), decide, and fill the result." + n
            + "        // Until then it returns " + (entry.routes.isEmpty() ? "true" : "the routes in turn, one per call, so every arc leaving the place is tried") + "." + n
            + "        String[] routes = {" + String.join(", ", quoted) + "};" + n
            + "        String route = routes[(CALLS.incrementAndGet() - 1) % routes.length];" + n + n
            + "        JSONObject result = new JSONObject();" + n
            + "        result.put(\"routing_decision\", routingDecision(route));" + n
            + "        return businessResult(\"" + entry.returnAttribute + "\", result);" + n
            + "    }" + n
            + "}" + n;
    }

    private static String comment(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.replace("*/", "* /").split("\n")) out.append(" * ").append(line).append("\n");
        return out.toString();
    }
}
