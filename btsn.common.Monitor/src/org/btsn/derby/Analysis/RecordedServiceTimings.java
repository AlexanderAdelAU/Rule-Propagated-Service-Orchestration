package org.btsn.derby.Analysis;

import java.sql.*;
import java.util.*;

/** Service visits remain measurable without workflow completion or a running Monitor. */
public final class RecordedServiceTimings {
    private RecordedServiceTimings() { }
    public static final class Visit {
        public long token, arrival, queueMs, serviceMs;
        public String operation, place;
        public boolean hasQueue, hasService;
        final Set<Long> queues = new TreeSet<>(), services = new TreeSet<>();
        boolean invalidQueue, invalidService;
    }
    private static boolean hasTable(Connection c, String name) throws SQLException {
        try (ResultSet r = c.getMetaData().getTables(null, "APP", name, new String[]{"TABLE"})) { return r.next(); }
    }
    public static List<Visit> load(Connection c) throws SQLException {
        Map<String, Visit> visits = new LinkedHashMap<>();
        Map<String, Set<String>> places = new HashMap<>();
        if (hasTable(c, ServiceDisplayNames.TABLE)) try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT sequenceID, operation, arrivalTime, physicalPlace FROM " + ServiceDisplayNames.TABLE)) {
            while (r.next()) {
                String key = r.getLong(1) + "/" + r.getString(2) + "/" + r.getLong(3);
                String place = r.getString(4);
                if (place != null) places.computeIfAbsent(key, k -> new TreeSet<>()).add(place);
            }
        }
        if (hasTable(c, "SERVICECONTRIBUTION")) try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT sequenceID, serviceName, operation, arrivalTime, queueTime, serviceTime FROM SERVICECONTRIBUTION")) {
            while (r.next()) {
                String place = r.getString(2);
                Set<String> captured = places.get(r.getLong(1) + "/" + r.getString(3) + "/" + r.getLong(4));
                if (captured != null && captured.size() == 1) place = captured.iterator().next();
                add(visits, r.getLong(1), place, r.getString(3), r.getLong(4), (Long)r.getObject(5), (Long)r.getObject(6));
            }
        }
        // A stopped host's own measurements are sufficient; no collector/Monitor tables are needed.
        if (visits.isEmpty() && hasTable(c, "SERVICEMEASUREMENTS")) try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT sequenceID, serviceName, operation, arrivalTime, invocationTime, publishTime FROM SERVICEMEASUREMENTS")) {
            while (r.next()) {
                long arrival = r.getLong(4), invocation = r.getLong(5), published = r.getLong(6);
                add(visits, r.getLong(1), r.getString(2), r.getString(3), arrival,
                        invocation > 0 && arrival > 0 ? invocation - arrival : null,
                        published > 0 && invocation > 0 ? published - invocation : null);
            }
        }
        for (Visit v : visits.values()) {
            v.hasQueue = !v.invalidQueue && v.queues.size() == 1;
            v.hasService = !v.invalidService && v.services.size() == 1;
            if (v.hasQueue) v.queueMs = v.queues.iterator().next();
            if (v.hasService) v.serviceMs = v.services.iterator().next();
        }
        List<Visit> result = new ArrayList<>(visits.values());
        result.sort(Comparator.comparingLong((Visit v) -> v.arrival).thenComparingLong(v -> v.token)
                .thenComparing(v -> v.place).thenComparing(v -> v.operation));
        return result;
    }
    private static void add(Map<String, Visit> visits, long token, String place, String operation,
                            long arrival, Long queue, Long service) {
        if (token <= 0 || token / 1000000 == 999 || arrival <= 0 || operation == null || operation.trim().isEmpty()) return;
        String physical = place == null ? "Unresolved place" : place;
        String key = token + "/" + physical + "/" + operation + "/" + arrival;
        Visit v = visits.computeIfAbsent(key, k -> new Visit());
        v.token = token; v.arrival = arrival; v.place = physical; v.operation = operation;
        if (queue == null || queue < 0) v.invalidQueue = true; else v.queues.add(queue);
        if (service == null || service < 0) v.invalidService = true; else v.services.add(service);
    }
}
