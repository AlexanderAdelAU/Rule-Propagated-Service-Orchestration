package com.editor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * The real host processes behind a run, read from the evidence they leave: each host writes its own
 * log beside the launcher (P2_Place.out.txt, MonitorService.out.txt) with its process id and machine,
 * the ports it listens on and every service call it completes. Nothing here is simulated or assumed.
 */
public final class RunHosts {
    public static final class Host {
        public final String name, node, endpoint;
        final File log;
        public String state = "waiting", process = "";
        public int calls;
        /** Rule versions this host has confirmed (its commitments), in the order received. */
        public final List<String> committed = new ArrayList<>();
        public boolean remote, seen;
        private long offset;
        private final StringBuilder partial = new StringBuilder();
        Host(String name, String node, String endpoint, File log) { this.name = name; this.node = node; this.endpoint = endpoint; this.log = log; }
    }

    private static final Pattern PROCESS = Pattern.compile("Host process: (\\d+) on (\\S+)");
    private static final Pattern CALL = Pattern.compile("SERVICE COMPLETE: org\\.btsn\\.places\\.\\w+_Place\\.");
    private static final Pattern CONFIRMATION = Pattern.compile("commitment confirmation received: (v\\d+):\\d+");
    private static final Pattern COMMITTED = Pattern.compile("Commitment confirmed for (\\w+):\\w+");
    private static final Pattern SKIPPED = Pattern.compile("\\[([pP]\\d+|monitor)\\] Skipping - running on remote host");

    public final List<Host> hosts = new ArrayList<>();
    private long runStart;
    private String confirmingVersion = "";

    /** The hosts a launcher starts (its hosts property, or run.pN in older launchers) and Monitor, with their endpoints from SingleHost.json. */
    public static RunHosts forLauncher(File launcher, File root, boolean allHostsHere) {
        return forLauncher(launcher, root, allHostsHere, ServiceRegistry.DEFAULT_INFRASTRUCTURE);
    }

    /** As above, with the endpoints of InfrastructureDefinitionFolder/infrastructure.json. */
    public static RunHosts forLauncher(File launcher, File root, boolean allHostsHere, String infrastructure) {
        RunHosts run = new RunHosts();
        String xml;
        try { xml = new String(Files.readAllBytes(launcher.toPath()), StandardCharsets.UTF_8); } catch (IOException ex) { xml = ""; }
        Map<String, String> endpoints = endpoints(new File(root, "btsn.common/InfrastructureDefinitionFolder/" + infrastructure + ".json"), allHostsHere);
        Set<String> nodes = new TreeSet<>(java.util.Comparator.comparingInt((String n) -> Integer.parseInt(n.substring(1))));
        Matcher listed = Pattern.compile("name=\"hosts\"\\s+value=\"([^\"]*)\"").matcher(xml);
        if (listed.find()) {
            for (String node : listed.group(1).split("[,\\s]+")) if (node.matches("[pP]\\d+")) nodes.add(node.toUpperCase(Locale.ROOT));
        } else {
            Matcher m = Pattern.compile("name=\"run\\.(p\\d+)\"\\s+value=\"true\"").matcher(xml);
            while (m.find()) nodes.add(m.group(1).toUpperCase(Locale.ROOT));
        }
        File dir = launcher.getParentFile();
        for (String node : nodes)
            run.hosts.add(new Host(node + "_Place", node, endpoints.getOrDefault(node, ""), new File(dir, node + "_Place.out.txt")));
        run.hosts.add(new Host("Monitor", "monitor", allHostsHere ? "127.0.0.1 (this computer)" : "Monitor's host", new File(dir, "MonitorService.out.txt")));
        return run;
    }

    static Map<String, String> endpoints(File singleHost, boolean allHostsHere) {
        Map<String, String> out = new HashMap<>();
        try {
            JSONObject json = (JSONObject)new JSONParser().parse(new String(Files.readAllBytes(singleHost.toPath()), StandardCharsets.UTF_8));
            Object nodes = json.get("nodes");
            if (!(nodes instanceof JSONArray)) return out;
            for (Object item : (JSONArray)nodes) {
                JSONObject node = (JSONObject)item;
                String name = String.valueOf(node.get("node"));
                String address = allHostsHere ? "127.0.0.1" : firstAddress(node);
                String port = firstPort(node);
                out.put(name, address + (port.isEmpty() ? "" : ":" + port));
            }
        } catch (Exception ignored) { }
        return out;
    }
    private static String firstAddress(JSONObject node) {
        for (String key : new String[] {"address", "ip"}) if (node.get(key) != null) return String.valueOf(node.get(key));
        Object channels = node.get("channels");
        if (channels instanceof JSONArray && !((JSONArray)channels).isEmpty()) {
            Object c = ((JSONArray)channels).get(0);
            if (c instanceof JSONObject && ((JSONObject)c).get("address") != null) return String.valueOf(((JSONObject)c).get("address"));
        }
        return "?";
    }
    private static String firstPort(JSONObject node) {
        for (String key : new String[] {"fixedBasePorts", "basePorts", "ports", "basePort", "port"}) {
            Object v = node.get(key);
            if (v instanceof JSONArray && !((JSONArray)v).isEmpty()) return String.valueOf(((JSONArray)v).get(0));
            if (v != null && !(v instanceof JSONArray)) return String.valueOf(v).split("[,\\s]+")[0];
        }
        Object channels = node.get("channels");
        if (channels instanceof JSONArray && !((JSONArray)channels).isEmpty() && ((JSONArray)channels).get(0) instanceof JSONObject)
            return firstPort((JSONObject)((JSONArray)channels).get(0));
        return "";
    }

    /** A new run: earlier logs (written before this moment) are ignored until the hosts write new ones. */
    public void started(long when) {
        runStart = when;
        for (Host h : hosts) { h.state = "waiting"; h.process = ""; h.calls = 0; h.remote = false; h.seen = false; h.offset = 0; h.partial.setLength(0); h.committed.clear(); }
        confirmingVersion = "";
    }

    /**
     * A line of the launcher's own output: it reports hosts it leaves to other machines, and each host's
     * commitment to the rules sent to it ("confirmation received: v001:1", then "Commitment confirmed for
     * P2_Place:processToken"). Admin services commit for their host: P2_InitializationService is P2's.
     */
    public void launcherLine(String line) {
        Matcher v = CONFIRMATION.matcher(line);
        if (v.find()) { confirmingVersion = v.group(1); return; }
        Matcher c = COMMITTED.matcher(line);
        if (c.find() && !confirmingVersion.isEmpty()) {
            Host h = hostOfService(c.group(1));
            if (h != null && !h.committed.contains(confirmingVersion)) h.committed.add(confirmingVersion);
            return;
        }
        Matcher m = SKIPPED.matcher(line);
        if (!m.find()) return;
        String node = m.group(1).equalsIgnoreCase("monitor") ? "monitor" : m.group(1).toUpperCase(Locale.ROOT);
        for (Host h : hosts) if (h.node.equals(node)) { h.remote = true; h.state = "remote host (not started here)"; }
    }

    /** P2_Place, P2_InitializationService, P2_CollectorService -> P2_Place; Monitor... -> Monitor. */
    Host hostOfService(String service) {
        if (service.startsWith("Monitor")) { for (Host h : hosts) if ("Monitor".equals(h.name)) return h; return null; }
        Matcher node = Pattern.compile("^(P\\d+)_").matcher(service);
        if (!node.find()) return null;
        for (Host h : hosts) if (node.group(1).equals(h.node)) return h;
        return null;
    }

    /** Reads what each host has added to its log since the last refresh. */
    public void refresh() {
        for (Host h : hosts) {
            if (h.remote || !h.log.isFile() || h.log.lastModified() + 2000 < runStart) continue;
            long length = h.log.length();
            if (length < h.offset) { h.offset = 0; h.partial.setLength(0); }   // recreated by the launcher
            if (length == h.offset) continue;
            try (RandomAccessFile file = new RandomAccessFile(h.log, "r")) {
                file.seek(h.offset);
                byte[] bytes = new byte[(int)Math.min(length - h.offset, 8_000_000)];
                file.readFully(bytes);
                h.offset += bytes.length;
                h.partial.append(new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException ex) { continue; }
            int end = h.partial.lastIndexOf("\n");
            if (end < 0) continue;
            String complete = h.partial.substring(0, end);
            h.partial.delete(0, end + 1);
            if (!h.seen) { h.seen = true; h.state = "starting"; }
            for (String line : complete.split("\n")) {
                Matcher p = PROCESS.matcher(line);
                if (p.find()) h.process = "process " + p.group(1) + " on " + p.group(2);
                if (line.contains("Service Handler is listening")) h.state = "running";
                if (CALL.matcher(line).find()) h.calls++;
            }
        }
    }

    /** After the editor stops the run's process tree. */
    public void stopped() { for (Host h : hosts) if (h.seen && !h.remote) h.state = "stopped"; }

    /** "P2_Place at 127.0.0.1:4002 (process 18432 on DEV-W11, 36 service calls); Monitor (...)" */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (Host h : hosts) {
            List<String> detail = new ArrayList<>();
            if (!h.process.isEmpty()) detail.add(h.process);
            if (!h.committed.isEmpty()) detail.add("rules " + String.join(", ", h.committed) + " committed");
            if (h.remote) detail.add("remote host");
            if (!"Monitor".equals(h.name)) detail.add(h.calls + " service calls");
            parts.add(h.name + (h.endpoint.isEmpty() || "Monitor".equals(h.name) ? "" : " at " + h.endpoint) + (detail.isEmpty() ? "" : " (" + String.join(", ", detail) + ")"));
        }
        return String.join("; ", parts);
    }
}
