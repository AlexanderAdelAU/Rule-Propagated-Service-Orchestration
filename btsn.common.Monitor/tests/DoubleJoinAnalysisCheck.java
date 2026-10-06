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
        }
        System.out.println("PASS: six recorded-event join-count cases");
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
