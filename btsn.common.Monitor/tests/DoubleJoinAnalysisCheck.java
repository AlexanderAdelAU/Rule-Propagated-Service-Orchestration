import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.btsn.derby.Analysis.PetriNetAnalyzer;

/** Isolated event fixtures; no fixture records are used in runtime reports. */
public class DoubleJoinAnalysisCheck {
    public static void main(String[] args) throws Exception {
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        try (Connection c = DriverManager.getConnection("jdbc:derby:ServiceAnalysisDataBase;create=true")) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE CONSOLIDATED_TRANSITION_FIRINGS (workflowBase INT, tokenId INT, transitionId VARCHAR(40), timestamp BIGINT, toPlace VARCHAR(40), eventType VARCHAR(40))");
                s.execute("CREATE TABLE CONSOLIDATED_TOKEN_GENEALOGY (workflowBase INT, parentTokenId INT, childTokenId INT)");
                s.execute("CREATE TABLE SERVICEMEASUREMENTS (sequenceID INT, arrivalTime BIGINT, serviceName VARCHAR(40), operation VARCHAR(40))");
            }
            PetriNetAnalyzer analyzer = new PetriNetAnalyzer();
            setup(c, 2);
            event(c, 1000002, "T4", "JOIN_CONSUMED"); event(c, 1000001, "T4", "ENTER");
            expect(analyzer, 1, "single join");
            setup(c, 3);
            event(c, 1000002, "T4", "JOIN_CONSUMED"); event(c, 1000001, "T4", "ENTER");
            event(c, 1000003, "T6", "JOIN_CONSUMED"); event(c, 1000001, "T6", "ENTER");
            expect(analyzer, 2, "two joins of a three-branch fork");
            event(c, 1000003, "T6", "JOIN_CONSUMED"); event(c, 1000001, "T6", "ENTER");
            expect(analyzer, 2, "duplicate collector observations");
            setup(c, 2);
            event(c, 1000002, "T4", "JOIN_CONSUMED");
            expect(analyzer, 0, "missing continuation");
            setup(c, 2);
            event(c, 1000001, "T4", "JOIN_CONSUMED"); event(c, 1000002, "T4", "JOIN_CONSUMED");
            event(c, 1000000, "T4", "ENTER");
            expect(analyzer, 1, "parent continuation");
            setup(c, 2);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE CONSOLIDATED_TOKEN_GENEALOGY SET parentTokenId=2000000 WHERE childTokenId=1000002");
            }
            event(c, 2000000, "generator", "GENERATED");
            event(c, 1000002, "T4", "JOIN_CONSUMED"); event(c, 1000001, "T4", "ENTER");
            expect(analyzer, 0, "different families do not make a join");
            setup(c, 2);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM CONSOLIDATED_TRANSITION_FIRINGS");
                s.execute("CREATE TABLE TRANSITION_FIRINGS (workflowBase INT, tokenId INT, toPlace VARCHAR(40))");
                s.executeUpdate("INSERT INTO TRANSITION_FIRINGS VALUES (1000000,1000000,'TERMINATE')");
            }
            event(c, 1000001, "T4", "ENTER");
            event(c, 1000001, "T4", "ENTER"); // duplicate collector observation
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO CONSOLIDATED_TRANSITION_FIRINGS VALUES (1000000,1000001,'T4',2000,'model','ENTER')");
                s.executeUpdate("INSERT INTO CONSOLIDATED_TRANSITION_FIRINGS VALUES (1000000,1000002,'T4',2000,'JOIN_CONSUMED','JOIN_CONSUMED')");
            }
            PetriNetAnalyzer.CanonicalWorkflowAnalysis partial = analyzer.analyzeCanonicalWorkflows(1000000);
            if (partial.generatedWorkflows != 0 || partial.completedWorkflows != 0
                    || partial.placeExecutions.getOrDefault("model", 0) != 2
                    || partial.unassignedPlaceVisits != 2)
                throw new AssertionError("partial capture must retain repeated visits without invented roots/completion");
            if (analyzer.analyzeForkJoin(1000000).successfulJoins != 1)
                throw new AssertionError("observed join must not require parent completion");
            if (analyzer.getAllPlaces(1000000).contains("JOIN_CONSUMED"))
                throw new AssertionError("event marker is not a physical place");
            java.lang.reflect.Method exited = PetriNetAnalyzer.class.getDeclaredMethod("hasBaseTokenExited", int.class, int.class);
            exited.setAccessible(true);
            if ((Boolean) exited.invoke(analyzer, 1000000, 1000000))
                throw new AssertionError("raw observer termination must not complete business token");
            setup(c, 2);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO SERVICEMEASUREMENTS VALUES (1000000,1500,'MonitorService','acknowledgeTokenArrival')");
            }
            event(c, 1000001, "edge1", "ENTER");
            event(c, 1000002, "edge2", "ENTER");
            if (analyzer.analyzeForkJoin(1000000).successfulJoins != 0)
                throw new AssertionError("uncompleted children and raw TERMINATE do not prove a join");
            PetriNetAnalyzer.CanonicalWorkflowAnalysis observed = analyzer.analyzeCanonicalWorkflows(1000000);
            if (observed.monitorAcknowledgements != 1 || observed.completedWorkflows != 0
                    || observed.instances.get(1000000).completedAt != 0)
                throw new AssertionError("Monitor acknowledgement is observation, not process completion");
            event(c, 1000000, "end", "TERMINATE");
            if (analyzer.analyzeCanonicalWorkflows(1000000).completedWorkflows != 1
                    || !(Boolean) exited.invoke(analyzer, 1000000, 1000000))
                throw new AssertionError("explicit business termination must remain supported");
        }
        System.out.println("PASS: six join cases and stopped-process analysis without root or Monitor completion");
    }
    private static void setup(Connection c, int children) throws Exception {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM CONSOLIDATED_TRANSITION_FIRINGS");
            s.executeUpdate("DELETE FROM CONSOLIDATED_TOKEN_GENEALOGY");
            for (int i = 1; i <= children; i++) s.executeUpdate("INSERT INTO CONSOLIDATED_TOKEN_GENEALOGY VALUES (1000000,1000000," + (1000000+i) + ")");
        }
        event(c, 1000000, "generator", "GENERATED");
    }
    private static void event(Connection c, int token, String transition, String type) throws Exception {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO CONSOLIDATED_TRANSITION_FIRINGS VALUES (1000000,?,?,1000,'model',?)")) {
            p.setInt(1, token); p.setString(2, transition); p.setString(3, type); p.executeUpdate();
        }
    }
    private static void expect(PetriNetAnalyzer analyzer, int expected, String context) {
        int actual = analyzer.analyzeCanonicalWorkflows(1000000).successfulJoins;
        if (actual != expected) throw new AssertionError(context + ": expected " + expected + ", got " + actual);
    }
}
