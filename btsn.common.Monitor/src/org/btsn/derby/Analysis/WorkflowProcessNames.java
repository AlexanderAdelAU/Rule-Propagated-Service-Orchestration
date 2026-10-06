package org.btsn.derby.Analysis;

import java.awt.FontMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.btsn.constants.VersionConstants;
import org.btsn.observation.WorkflowRunMetadata;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Exact GENERATED token/timestamp matches, never an assumption about the active deployment. */
public final class WorkflowProcessNames {
    public static final String UNKNOWN = "Process not captured";
    private final Map<String, Set<String>> sources = new HashMap<>(), processes = new HashMap<>();
    private final Map<String, Set<String>> byVersion = new TreeMap<>();
    public static WorkflowProcessNames empty() { return new WorkflowProcessNames(); }
    private static String key(long token, long timestamp) { return timestamp + "-" + token; }

    public static WorkflowProcessNames load(Connection c) throws java.sql.SQLException {
        WorkflowProcessNames result = empty();
        try (ResultSet tables = c.getMetaData().getTables(null, "APP", "CONSOLIDATED_TRANSITION_FIRINGS", new String[]{"TABLE"})) {
            if (!tables.next()) return result;
        }
        Map<String, String> versions = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT DISTINCT tokenId,timestamp,transitionId FROM CONSOLIDATED_TRANSITION_FIRINGS WHERE eventType='GENERATED'")) {
            while (r.next()) {
                long token = r.getLong(1), timestamp = r.getLong(2);
                if (token <= 0 || token > Integer.MAX_VALUE || token / 1000000 == 999 || timestamp <= 0) continue;
                String key = key(token, timestamp), source = r.getString(3);
                Set<String> values = result.sources.computeIfAbsent(key, k -> new TreeSet<>());
                if (source != null && !source.isBlank()) values.add(source);
                versions.put(key, VersionConstants.getVersionFromSequenceId((int)token));
            }
        }
        Path directory = WorkflowRunMetadata.directory();
        if (Files.isDirectory(directory)) try (Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.getFileName().toString().endsWith(".json"))::iterator) {
                // Skip provenance from other generations, including previous runs reusing token IDs.
                String[] parts = file.getFileName().toString().split("-", 3);
                if (parts.length < 3 || !versions.containsKey(parts[0] + "-" + parts[1])) continue;
                try {
                    JSONObject data = (JSONObject)new JSONParser().parse(Files.readString(file));
                    String key = key(((Number)data.get("tokenId")).longValue(), ((Number)data.get("generatedAt")).longValue());
                    Object process = data.get("process");
                    if (process instanceof String && !((String)process).isBlank() &&
                            versions.get(key) != null && versions.get(key).equals(data.get("version")))
                        result.processes.computeIfAbsent(key, k -> new TreeSet<>()).add((String)process);
                } catch (Exception e) { System.err.println("Ignoring unreadable process metadata: " + file.getFileName()); }
            }
        } catch (java.io.IOException e) {
            System.err.println("Could not read diagram process metadata: " + e.getMessage());
        }
        for (Map.Entry<String,String> entry : versions.entrySet())
            result.byVersion.computeIfAbsent(entry.getValue(), k -> new TreeSet<>()).add(result.description(entry.getKey()));
        return result;
    }

    public String forRoot(int token, long generatedAt) { return description(key(token, generatedAt)); }
    private String description(String key) {
        Set<String> names = processes.get(key);
        if (names != null && names.size() == 1) return names.iterator().next();
        if (names != null && names.size() > 1) return "Conflicting process metadata: " + String.join(" / ", names);
        Set<String> generators = sources.get(key);
        return UNKNOWN + (generators == null || generators.isEmpty() ? "" : " (source: " + String.join(" / ", generators) + ")");
    }
    public String caption(long workflowBase) { return caption(workflowBase, false); }
    public String caption(long workflowBase, boolean abbreviated) {
        String selected = workflowBase < 0 ? null : VersionConstants.getVersionFromSequenceId((int)workflowBase);
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String,Set<String>> entry : byVersion.entrySet())
            if (selected == null || selected.equals(entry.getKey())) labels.add(entry.getKey() + ": " + entry.getValue().stream().map(p -> abbreviated ? shortName(p) : p).collect(java.util.stream.Collectors.joining(" / ")));
        return labels.isEmpty() ? UNKNOWN : "Processes: " + String.join("; ", labels);
    }
    public static String shortName(String process) {
        if (process.startsWith(UNKNOWN) || process.startsWith("Conflicting process metadata")) return process;
        String normalized = process.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1).replace('_', ' ');
    }
    public static List<String> wrap(String text, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>(); StringBuilder line = new StringBuilder();
        for (String word : text.replace('\n', ' ').replace('\r', ' ').split(" ")) {
            if (line.length() > 0 && metrics.stringWidth(line + " " + word) > width) {
                lines.add(line.toString().trim()); line.setLength(0);
            }
            for (int i = 0; i < word.length(); i++) {
                if (line.length() > 0 && metrics.stringWidth(line.toString() + word.charAt(i)) > width) {
                    lines.add(line.toString()); line.setLength(0);
                }
                line.append(word.charAt(i));
            }
            line.append(' ');
        }
        if (line.length() > 0) lines.add(line.toString().trim());
        return lines;
    }
}
