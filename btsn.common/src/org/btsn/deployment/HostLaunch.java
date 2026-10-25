package org.btsn.deployment;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Runs a launcher's phases on whichever hosts it lists, so a launcher names its hosts (hosts=P1,P3,P7)
 * instead of the build knowing P1 to P6. Every host is the same program (btsn.rpso.host) started as its node.
 *
 * Commands (arguments are name=value):
 *   plan        hosts=P1,P3 launch=launch.properties out=hosts.properties
 *               Which listed hosts start here: a host starts on this machine when its channel's address is this
 *               machine (or, with no place on it, when ip0 is), unless -Dpn.mode=local|remote says otherwise.
 *               Launchers written before hosts= existed list them as run.p1 ... properties; those count too.
 *   start       plan=hosts.properties runtime=dir template=btsn.rpso.host output=dir version=v001 classpath=...
 *               Starts each local host in runtime/Pn (beside the run's btsn.common), output in output/Pn_Place.out.txt,
 *               and waits; stopping this process stops them.
 *   initialise  plan=... classpath=... dir=... generator=class operation=... version=v999 process=...
 *   collect     plan=... classpath=... dir=... generator=class operation=... version=v999 process=... queryVersion=v001 delay=3
 *               Sends each host its administrative token (initialise also Monitor's), the first host's call deploying
 *               the process's rules.
 *   settle      database=dir quiet=6 limit=180
 *               Waits until Monitor has written what the collectors sent: its database unchanged for quiet seconds.
 */
public final class HostLaunch {
    private HostLaunch() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Expected: plan|start|initialise|collect name=value ...");
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("Expected name=value: " + args[i]);
            a.put(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        switch (args[0]) {
            case "plan": plan(a); break;
            case "start": start(a); break;
            case "initialise": administer(a, true); break;
            case "collect": administer(a, false); break;
            case "settle": settle(a); break;
            default: throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    /** The hosts named by hosts=, or by run.pN properties when hosts= is empty, in node order. */
    static List<String> listed(String hosts, Properties system) {
        if (hosts != null && !hosts.trim().isEmpty() && !hosts.contains("${")) return HostAdministration.hosts(hosts);
        TreeSet<Integer> numbers = new TreeSet<>();
        for (String name : system.stringPropertyNames())
            if (name.matches("run\\.p\\d+") && !"false".equalsIgnoreCase(system.getProperty(name)))
                numbers.add(Integer.parseInt(name.substring(5)));
        if (numbers.isEmpty()) throw new IllegalArgumentException("The launcher lists no hosts: set hosts, for example <property name=\"hosts\" value=\"P1,P2\"/>");
        StringBuilder list = new StringBuilder();
        for (int n : numbers) list.append(list.length() == 0 ? "" : ",").append("P").append(n);
        return HostAdministration.hosts(list.toString());
    }

    static void plan(Map<String, String> a) throws Exception {
        List<String> hosts = listed(a.get("hosts"), System.getProperties());
        Properties launch = load(Path.of(required(a, "launch")));
        List<String> local = new ArrayList<>(), remote = new ArrayList<>();
        for (String host : hosts) {
            String key = host.toLowerCase(Locale.ROOT);
            String mode = System.getProperty(key + ".mode", "auto");
            boolean here;
            if ("local".equals(mode)) here = true;
            else if ("remote".equals(mode)) here = false;
            else if ("auto".equals(mode)) here = launch.containsKey(key + ".channel")
                ? launch.containsKey(key + ".channel.is.local") : launch.containsKey("ip0.is.local");
            else throw new IllegalArgumentException("Invalid " + key + ".mode '" + mode + "'; use auto, local or remote.");
            (here ? local : remote).add(host);
            String address = launch.getProperty(key + ".address", launch.getProperty("ip0.address", ""));
            System.out.println(host + ": " + (here ? "local" : "remote") + " (requested " + mode + "; " + address + ")");
        }
        Properties plan = new Properties();
        plan.setProperty("hosts.list", String.join(",", hosts));
        plan.setProperty("hosts.first", hosts.get(0));
        plan.setProperty("hosts.local", String.join(",", local));
        plan.setProperty("hosts.remote", String.join(",", remote));
        if (!remote.isEmpty()) plan.setProperty("remote.hosts.present", "true");
        Path out = Path.of(required(a, "out")).toAbsolutePath();
        Files.createDirectories(out.getParent());
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) { plan.store(w, "Hosts of this run"); }
    }

    static void start(Map<String, String> a) throws Exception {
        Properties plan = load(Path.of(required(a, "plan")));
        Path runtime = Path.of(required(a, "runtime")), template = Path.of(required(a, "template")), output = Path.of(required(a, "output"));
        for (String host : split(plan.getProperty("hosts.remote", ""))) System.out.println("  [" + host + "] Skipping - running on remote host");
        List<Process> started = new ArrayList<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { for (Process p : started) p.destroy(); }));
        for (String host : split(plan.getProperty("hosts.local", ""))) {
            Path work = workingFolder(runtime, template, host);
            File log = output.resolve(host + "_Place.out.txt").toFile();
            List<String> command = new ArrayList<>(List.of(java(), "-cp", required(a, "classpath"), "org.btsn.handlers.ServiceLoader",
                work.toString(), "-version", required(a, "version"), "-node", host));
            started.add(new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log).start());
            System.out.println("  [" + host + "] started in " + work + ", output " + log);
        }
        int failed = 0;
        for (Process p : started) if (p.waitFor() != 0) failed++;
        if (failed > 0) throw new IllegalStateException(failed + " host(s) stopped with an error; see their Pn_Place.out.txt");
    }

    /** runtime/P7, beside the run's btsn.common: the host's configuration, its rule folders and its database. */
    static Path workingFolder(Path runtime, Path template, String host) throws IOException {
        Path work = runtime.resolve(host);
        for (String folder : List.of("ServiceLoaderQueries", "resources", "PayLoad")) {
            Path from = template.resolve(folder);
            if (!Files.isDirectory(from)) continue;
            try (Stream<Path> files = Files.walk(from)) {
                for (Path file : (Iterable<Path>)files::iterator) {
                    Path to = work.resolve(folder).resolve(from.relativize(file).toString());
                    if (Files.isDirectory(file)) Files.createDirectories(to);
                    else Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return work;
    }

    static void administer(Map<String, String> a, boolean initialise) throws Exception {
        Properties plan = load(Path.of(required(a, "plan")));
        List<String> hosts = split(plan.getProperty("hosts.list", ""));
        String first = plan.getProperty("hosts.first", "");
        long delay = Long.parseLong(a.getOrDefault("delay", "0"));
        for (String host : hosts) {
            String service = host + (initialise ? "_InitializationService" : "_CollectorService");
            if (!initialise) System.out.println("Collecting from " + service + "...");
            run(a, service, HostAdministration.sequence(host), !host.equals(first), initialise);
            if (delay > 0) Thread.sleep(delay * 1000);
        }
        if (initialise) run(a, "Monitor_InitializationService", HostAdministration.monitorSequence(), true, true);
    }

    private static void run(Map<String, String> a, String service, int sequence, boolean skipDeploy, boolean initialise) throws Exception {
        List<String> command = new ArrayList<>(List.of(java(), "-cp", required(a, "classpath"), required(a, "generator"),
            "-service", service, "-operation", required(a, "operation"), "-version", required(a, "version"),
            "-process", required(a, "process")));
        if (!initialise) { command.add("-queryVersion"); command.add(required(a, "queryVersion")); }
        command.add("-sequenceId"); command.add(Integer.toString(sequence));
        if (initialise) { command.add("-tokens"); command.add("1"); }
        if (skipDeploy) command.add("-skipDeploy");
        int exit = new ProcessBuilder(command).directory(new File(required(a, "dir"))).inheritIO().start().waitFor();
        if (exit != 0) throw new IllegalStateException(service + " " + (initialise ? "initialisation" : "collection") + " failed (exit " + exit + ")");
    }

    /** Collection ends when Monitor has stored the records: its database stops changing. */
    static void settle(Map<String, String> a) throws Exception {
        Path database = Path.of(required(a, "database"));
        long quiet = Long.parseLong(a.getOrDefault("quiet", "6")) * 1000, limit = Long.parseLong(a.getOrDefault("limit", "180")) * 1000;
        long start = System.currentTimeMillis(), last = latestChange(database), quietSince = start;
        while (System.currentTimeMillis() - start < limit) {
            Thread.sleep(1000);
            long now = latestChange(database);
            if (now != last) { last = now; quietSince = System.currentTimeMillis(); }
            else if (System.currentTimeMillis() - quietSince >= quiet) {
                System.out.println("Monitor has stored the collected records (" + (System.currentTimeMillis() - start) / 1000 + " s).");
                return;
            }
        }
        System.out.println("Monitor was still writing after " + limit / 1000 + " s; the analysis may miss its last records.");
    }

    private static long latestChange(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return 0;
        try (Stream<Path> files = Files.walk(folder)) {
            return files.mapToLong(p -> p.toFile().lastModified()).max().orElse(0);
        }
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static List<String> split(String list) {
        List<String> items = new ArrayList<>();
        for (String s : list.split(",")) if (!s.trim().isEmpty()) items.add(s.trim());
        return items;
    }

    private static Properties load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
        return p;
    }

    private static String required(Map<String, String> a, String name) {
        String value = a.get(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing " + name + "=");
        return value;
    }
}
