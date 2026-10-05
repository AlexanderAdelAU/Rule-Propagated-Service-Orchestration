package org.btsn.derby.Analysis;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/** Business labels for observation only. Never changes routing, grouping or timing. */
public final class ServiceDisplayNames {
    public static final String TABLE = "CONSOLIDATED_SERVICE_IDENTITIES";
    public static final String CREATE_TABLE = "CREATE TABLE " + TABLE +
        " (workflowBase BIGINT, sequenceID BIGINT, physicalPlace VARCHAR(255)," +
        " operation VARCHAR(255), arrivalTime BIGINT, logicalService VARCHAR(255))";
    private final Map<String, Set<String>> byPlace = new HashMap<>();
    private final Map<String, String> byVisit = new HashMap<>();
    private final Map<String, Set<String>> byLane = new HashMap<>();
    private final Map<Long, Set<String>> byFamily = new HashMap<>();
    private int inferredExecutions;

    public static ServiceDisplayNames empty() { return new ServiceDisplayNames(); }

    public static void record(Connection conn, long workflow, long token, String place,
                              String operation, long arrival, String logical) throws SQLException {
        if (logical == null || logical.trim().isEmpty()) return;
        // Supports already-created Monitor databases as well as normal initialization.
        if (!tableExists(conn, TABLE)) try (Statement s = conn.createStatement()) {
            try { s.executeUpdate(CREATE_TABLE); }
            catch (SQLException e) { if (!"X0Y32".equals(e.getSQLState())) throw e; }
        }
        String sql = "INSERT INTO " + TABLE + " VALUES (?,?,?,?,?,?)";
        try (PreparedStatement p = conn.prepareStatement(sql)) {
            p.setLong(1, workflow); p.setLong(2, token); p.setString(3, place);
            p.setString(4, operation); p.setLong(5, arrival); p.setString(6, logical);
            p.executeUpdate();
        }
    }

    public static ServiceDisplayNames load(Connection conn) throws SQLException {
        return load(conn, Paths.get(System.getProperty("btsn.common.dir", "../btsn.common")));
    }

    public static ServiceDisplayNames load(Connection conn, Path common) throws SQLException {
        ServiceDisplayNames result = new ServiceDisplayNames();
        Map<String, Set<String>> captured = new HashMap<>();
        if (tableExists(conn, TABLE)) {
            try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM " + TABLE)) {
                while (r.next()) {
                    long workflow = r.getLong("workflowBase");
                    String place = r.getString("physicalPlace");
                    String logical = r.getString("logicalService");
                    if (logical == null || logical.trim().isEmpty()) continue;
                    result.add(workflow, place, logical);
                    result.addFamily(r.getLong("sequenceID"), logical);
                    captured.computeIfAbsent(executionKey(workflow, r.getLong("sequenceID"),
                        place, r.getString("operation"), r.getLong("arrivalTime")), k -> new TreeSet<>()).add(logical);
                }
            }
        }

        // Older runs have no captured identity. Only use an operation when all
        // available catalogues agree on its logical service; never guess from P1 etc.
        Map<String, Set<String>> catalogue = catalogueNames(common);
        if (!tableExists(conn, "SERVICECONTRIBUTION") ||
            !tableExists(conn, "CONSOLIDATED_TRANSITION_FIRINGS")) return result;
        Map<String, List<Visit>> visits = new HashMap<>();
        try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery(
                "SELECT DISTINCT workflowBase, tokenId, timestamp, toPlace FROM " +
                "CONSOLIDATED_TRANSITION_FIRINGS WHERE eventType='ENTER' " +
                "ORDER BY workflowBase, tokenId, timestamp")) {
            while (r.next()) {
                visits.computeIfAbsent(tokenKey(r.getLong(1), r.getLong(2)), k -> new ArrayList<>())
                    .add(new Visit(r.getString(4), r.getLong(3)));
            }
        }
        Map<String, List<Execution>> executions = new HashMap<>();
        try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery(
                "SELECT DISTINCT workflowBase, sequenceID, operation, arrivalTime FROM SERVICECONTRIBUTION " +
                "ORDER BY workflowBase, sequenceID, arrivalTime")) {
            while (r.next()) {
                executions.computeIfAbsent(tokenKey(r.getLong(1), r.getLong(2)), k -> new ArrayList<>())
                    .add(new Execution(r.getLong(1), r.getLong(2), r.getString(3), r.getLong(4)));
            }
        }
        for (Map.Entry<String, List<Execution>> entry : executions.entrySet()) {
            List<Visit> paths = visits.get(entry.getKey());
            List<Execution> samples = entry.getValue();
            // Incomplete pairing cannot establish an execution's physical location.
            if (paths == null || paths.size() != samples.size()) continue;
            for (int i = 0; i < samples.size(); i++) {
                Execution sample = samples.get(i);
                Visit path = paths.get(i);
                Set<String> names = captured.get(executionKey(sample.workflow, sample.token, path.place,
                    sample.operation, sample.arrival));
                boolean inferred = names == null;
                if (inferred) names = catalogue.get(sample.operation);
                if (names == null || names.size() != 1) continue;
                String name = names.iterator().next();
                result.add(sample.workflow, path.place, name);
                result.addFamily(sample.token, name);
                result.byVisit.put(visitKey(sample.workflow, sample.token, path.place, path.time), name);
                if (inferred) result.inferredExecutions++;
            }
        }
        return result;
    }

    private void add(long workflow, String place, String logical) {
        byPlace.computeIfAbsent(workflow + ":" + place, k -> new TreeSet<>()).add(logical);
        byLane.computeIfAbsent(place, k -> new TreeSet<>()).add(logical);
    }
    private void addFamily(long token, String name) {
        byFamily.computeIfAbsent(token - token % org.btsn.constants.VersionConstants.TOKEN_INCREMENT,
            k -> new TreeSet<>()).add(name);
    }
    public String familyServices(long token) {
        return names(byFamily.get(token - token % org.btsn.constants.VersionConstants.TOKEN_INCREMENT), "Unresolved");
    }

    public String label(long workflow, String place) {
        return names(byPlace.get(workflow + ":" + place), place);
    }
    public String describe(long workflow, String place) {
        return withPlace(label(workflow, place), place);
    }
    public String laneLabel(String place, long workflow) {
        return workflow < 0 ? names(byLane.get(place), place) : label(workflow, place);
    }
    public String laneDescription(String place, long workflow) {
        return withPlace(laneLabel(place, workflow), place);
    }
    public String visitLabel(long workflow, long token, String place, long entry) {
        return byVisit.getOrDefault(visitKey(workflow, token, place, entry), label(workflow, place));
    }
    public int inferredExecutions() { return inferredExecutions; }

    private static String names(Set<String> names, String fallback) {
        return names == null || names.isEmpty() ? fallback : String.join(" / ", names);
    }
    private static String withPlace(String label, String place) {
        return label.equals(place) ? place : label + " (" + place + ")";
    }
    private static String tokenKey(long workflow, long token) { return workflow + ":" + token; }
    private static String executionKey(long workflow, long token, String place, String op, long arrival) {
        return tokenKey(workflow, token) + ":" + place + ":" + op + ":" + arrival;
    }
    private static String visitKey(long workflow, long token, String place, long entry) {
        return tokenKey(workflow, token) + ":" + place + ":" + entry;
    }
    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (ResultSet r = c.getMetaData().getTables(null, "APP", table, new String[]{"TABLE"})) {
            return r.next();
        }
    }
    private static Map<String, Set<String>> catalogueNames(Path common) {
        Map<String, Set<String>> result = new HashMap<>();
        Path directory = common.resolve("BusinessServiceDefinitions");
        if (!Files.isDirectory(directory)) return result;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json"))
                    .sorted()::iterator) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Object parsed = new JSONParser().parse(reader);
                    if (!(parsed instanceof JSONObject)) continue;
                    Object services = ((JSONObject) parsed).get("services");
                    if (!(services instanceof JSONArray)) continue;
                    for (Object service : (JSONArray) services) {
                        if (!(service instanceof JSONObject)) continue;
                        JSONObject definition = (JSONObject) service;
                        Object op = definition.get("operation"), name = definition.get("service");
                        if (op instanceof String && name instanceof String) {
                            result.computeIfAbsent((String) op, k -> new TreeSet<>()).add((String) name);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // An unreadable catalogue cannot justify even a partial historical label.
            result.clear();
            System.err.println("Historical business labels unavailable: " + e.getMessage());
        }
        return result;
    }
    private static final class Visit {
        final String place; final long time;
        Visit(String place, long time) { this.place = place; this.time = time; }
    }
    private static final class Execution {
        final long workflow, token, arrival; final String operation;
        Execution(long workflow, long token, String operation, long arrival) {
            this.workflow = workflow; this.token = token; this.operation = operation; this.arrival = arrival;
        }
    }
}
