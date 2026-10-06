package org.btsn.packaging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Catalogue metadata reader. Compilation, copying and archives are native Ant tasks. */
public final class ServiceInventory {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "generate": generate(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); break;
            case "check-classes": checkClasses(Path.of(args[1])); break;
            case "checksums": checksums(Path.of(args[1])); break;
            case "same-file":
                if (!Arrays.equals(Files.readAllBytes(Path.of(args[1])), Files.readAllBytes(Path.of(args[2]))))
                    throw new IllegalStateException("Invocation boundary copies differ: " + args[2]);
                break;
            default: throw new IllegalArgumentException("Unknown inventory command");
        }
    }

    private static JSONObject read(Path path) throws Exception {
        return (JSONObject) new JSONParser().parse(Files.readString(path));
    }

    private static String xml(Object value) {
        return value.toString().replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Path resolve(Path config, Object path) {
        return config.getParent().resolve(path.toString()).toAbsolutePath().normalize();
    }

    @SuppressWarnings("unchecked")
    private static void generate(Path configPath, Path target, Path project) throws Exception {
        configPath = configPath.toAbsolutePath().normalize();
        target = target.toAbsolutePath().normalize();
        project = project.toAbsolutePath().normalize();
        JSONObject config = read(configPath);
        Path sources = resolve(configPath, config.get("sourceRoot"));
        Path inventory = target.resolve("inventory");
        // Native Ant deletes stale inventory before invoking this reader.
        Files.createDirectories(inventory.resolve("catalogues"));
        JSONArray services = new JSONArray();
        JSONObject catalogueDomains = (JSONObject) config.getOrDefault("catalogueDomains", new JSONObject());
        Set<String> catalogueNames = new HashSet<>(), libraryNames = new HashSet<>();
        for (Object name : (JSONArray) config.get("catalogues")) {
            Path catalogue = resolve(configPath, name);
            if (!catalogueNames.add(catalogue.getFileName().toString())) throw new IllegalArgumentException("Duplicate catalogue filename");
            Files.write(inventory.resolve("catalogues").resolve(catalogue.getFileName()), Files.readAllBytes(catalogue));
            JSONObject catalogueData = read(catalogue);
            Object selectedDomain = catalogueDomains.getOrDefault(catalogue.getFileName().toString(), catalogueData.get("domain"));
            if (!(selectedDomain instanceof String)) throw new IllegalArgumentException("Missing packaging domain for catalogue: " + catalogue);
            String domain = ((String) selectedDomain).toLowerCase(Locale.ROOT);
            if (!domain.matches("[a-z][a-z0-9_-]*")) throw new IllegalArgumentException("Invalid packaging domain: " + selectedDomain);
            for (Object entry : (JSONArray) catalogueData.get("services")) {
                JSONObject service = new JSONObject(new LinkedHashMap<>((JSONObject) entry));
                service.put("domain", domain);
                services.add(service);
            }
        }
        if (services.isEmpty()) throw new IllegalArgumentException("Empty service inventory");
        StringBuilder libraryPath = new StringBuilder(), copyLibraries = new StringBuilder();
        String dependencies = "../../lib/service-support.jar";
        JSONArray runtimeDependencies = new JSONArray();
        runtimeDependencies.add("lib/service-support.jar");
        for (Object name : (JSONArray) config.get("runtimeLibraries")) {
            Path library = resolve(configPath, name);
            String filename = library.getFileName().toString();
            if (!Files.isRegularFile(library)) throw new IllegalArgumentException("Missing library: " + library);
            if (!filename.matches("[A-Za-z0-9_.-]+") || !libraryNames.add(filename) || filename.equals("service-support.jar"))
                throw new IllegalArgumentException("Invalid or duplicate library filename");
            dependencies += " ../../lib/" + filename;
            runtimeDependencies.add("lib/" + filename);
            libraryPath.append("<pathelement location=\"").append(xml(library)).append("\"/>");
            copyLibraries.append("<copy file=\"").append(xml(library)).append("\" todir=\"${bundle}/lib\"/>\n");
        }
        Files.writeString(target.resolve("runtime-library-excludes.txt"),
            libraryNames.stream().sorted().collect(Collectors.joining("\n", "", "\n")));
        Set<String> capabilities = new HashSet<>();
        java.util.Map<String, String> names = new LinkedHashMap<>(), implementations = new LinkedHashMap<>();
        java.util.Map<String, String> serviceDomains = new LinkedHashMap<>();
        StringBuilder includes = new StringBuilder(), excludes = new StringBuilder(), jars = new StringBuilder();
        JSONArray index = new JSONArray();
        for (Object entry : services) {
            JSONObject service = (JSONObject) entry;
            String domain = (String) service.get("domain");
            String name = (String) service.get("service"), implementation = (String) service.get("implementationClass");
            String operation = (String) service.get("operation");
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || operation == null || !operation.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || !capabilities.add(name + "." + operation))
                throw new IllegalArgumentException("Invalid or duplicate capability: " + name + "." + operation);
            if (implementation == null || !implementation.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))
                throw new IllegalArgumentException("Invalid or duplicate implementation: " + implementation);
            String existing = names.putIfAbsent(name, implementation);
            if (existing != null && !existing.equals(implementation))
                throw new IllegalArgumentException("Conflicting implementations for service: " + name);
            String existingDomain = serviceDomains.putIfAbsent(name, domain);
            if (existingDomain != null && !existingDomain.equals(domain))
                throw new IllegalArgumentException("Service assigned to different packaging domains: " + name);
            String owner = implementations.putIfAbsent(implementation, name);
            if (owner != null && !owner.equals(name))
                throw new IllegalArgumentException("Implementation assigned to different services: " + implementation);
            String classPath = implementation.replace('.', '/');
            if (!Files.isRegularFile(sources.resolve(classPath + ".java"))) throw new IllegalArgumentException("Missing implementation: " + implementation);
            if (existing == null) {
                includes.append("<include name=\"").append(xml(classPath)).append(".java\"/>\n");
                excludes.append("<exclude name=\"").append(xml(classPath)).append(".class\"/>")
                    .append("<exclude name=\"").append(xml(classPath)).append("$*.class\"/>\n");
                jars.append("<mkdir dir=\"${bundle}/services/").append(xml(domain)).append("\"/>\n")
                    .append("<jar destfile=\"${bundle}/services/").append(xml(domain)).append("/").append(xml(name)).append(".jar\" modificationtime=\"315532800000\">\n")
                    .append("<fileset dir=\"${classes}\"><include name=\"").append(xml(classPath)).append(".class\"/>")
                    .append("<include name=\"").append(xml(classPath)).append("$*.class\"/></fileset>\n")
                    .append("<manifest><attribute name=\"Class-Path\" value=\"").append(xml(dependencies)).append("\"/></manifest></jar>\n");
            }
            JSONObject packaged = new JSONObject(new LinkedHashMap<>(service));
            packaged.put("jar", "services/" + domain + "/" + name + ".jar");
            packaged.put("runtimeDependencies", runtimeDependencies);
            index.add(packaged);
        }
        JSONObject deployment = new JSONObject();
        deployment.put("javaRelease", config.get("javaRelease"));
        deployment.put("services", index);
        Files.writeString(inventory.resolve("deployment-index.json"), deployment.toJSONString() + "\n");
        Files.writeString(inventory.resolve("README.txt"), "Service deployment bundle\n\n"
            + "Install selected services/<domain>/<service>.jar together with lib/. Keep relative paths.\n"
            + "Domains organise catalogue inventory only; host placement remains in deployment metadata.\n"
            + "Java classpath wildcards do not recurse: select JARs explicitly or use services/<domain>/*.\n"
            + "Add selected JARs to the generic host JVM classpath; manifests supply support libraries.\n"
            + "Host infrastructure, logging configuration, resolver metadata and rules are supplied separately.\n"
            + "Packaging does not activate or place services. Remove duplicate implementation classes from the host classpath.\n"
            + "The Ant host launcher supplies these JARs on its classpath; ServiceHelper instantiates the configured class for each invocation.\n"
            + "Java " + config.get("javaRelease") + "+ required.\n");
        String build = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project name="catalogue-service-tasks" default="package" basedir="%s">
              <property name="classes" location="classes"/>
              <property name="bundle" location="deployment"/>
              <path id="libraries">%s</path>
              <path id="tools"><pathelement location="tools"/><pathelement location="%s"/></path>
              <target name="package">
                <delete dir="${classes}"/><delete dir="${bundle}"/>
                <mkdir dir="${classes}"/><mkdir dir="${bundle}/services"/><mkdir dir="${bundle}/lib"/>
                <javac srcdir="%s" sourcepath="%s" destdir="${classes}" release="%s" includeantruntime="false" encoding="UTF-8">
                  <classpath refid="libraries"/><compilerarg value="-implicit:class"/>%s
                </javac>
                <java classname="org.btsn.packaging.ServiceInventory" fork="true" failonerror="true">
                  <classpath refid="tools"/><arg value="check-classes"/><arg file="${classes}"/>
                </java>
                <jar destfile="${bundle}/lib/service-support.jar" modificationtime="315532800000">
                  <fileset dir="${classes}" includes="**/*.class">%s</fileset>
                </jar>
                %s
                %s
                <copy todir="${bundle}"><fileset dir="inventory"/></copy>
                <java classname="org.btsn.packaging.ServiceInventory" fork="true" failonerror="true">
                  <classpath refid="tools"/><arg value="checksums"/><arg file="${bundle}"/>
                </java>
                <delete file="service-deployment.zip"/>
                <zip destfile="service-deployment.zip" basedir="${bundle}" modificationtime="315532800000"/>
              </target>
              <target name="check">
                <mkdir dir="test-classes"/>
                <javac srcdir="%s" destdir="test-classes" release="%s" includeantruntime="false" encoding="UTF-8"
                       includes="PackagedServiceCheck.java,PackagedBundleCheck.java">
                  <classpath refid="tools"/>
                </javac>
                <java classname="PackagedBundleCheck" fork="true" failonerror="true">
                  <classpath><pathelement location="test-classes"/><path refid="tools"/></classpath>
                  <arg file="${bundle}"/><arg file="test-classes"/>
                </java>
              </target>
            </project>
            """.formatted(xml(target), libraryPath, xml(Path.of(JSONParser.class.getProtectionDomain().getCodeSource().getLocation().toURI())),
                xml(sources), xml(sources), xml(config.get("javaRelease")), includes, excludes, jars, copyLibraries,
                xml(project.resolve("tests")), xml(config.get("javaRelease")));
        Files.writeString(target.resolve("service-tasks.xml"), build);
        System.out.println("Generated native Ant tasks for " + names.size() + " service JARs and " + services.size() + " operations");
    }

    private static void checkClasses(Path classes) throws Exception {
        Set<String> sources = new java.util.TreeSet<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path path : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String name = classes.relativize(path).toString().replace('\\', '/');
                if (name.startsWith("org/btsn/handlers/") || name.startsWith("org/btsn/invocation/") || name.startsWith("org/btsn/places/"))
                    throw new IllegalStateException("Service depends on host infrastructure: " + name);
                if (name.endsWith(".class")) {
                    sources.add(name.substring(0, name.length() - 6).split("\\$")[0] + ".java");
                }
            }
        }
        Files.writeString(classes.getParent().resolve("service-source-excludes.txt"), String.join("\n", sources) + "\n");
    }

    private static void checksums(Path bundle) throws Exception {
        StringBuilder result = new StringBuilder();
        try (Stream<Path> paths = Files.walk(bundle)) {
            for (Path file : paths.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals("SHA256SUMS"))
                    .sorted().collect(Collectors.toList())) {
                for (byte value : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
                    result.append(String.format("%02x", value & 0xff));
                result.append("  ").append(bundle.relativize(file).toString().replace('\\', '/')).append('\n');
            }
        }
        Files.writeString(bundle.resolve("SHA256SUMS"), result);
    }
}
