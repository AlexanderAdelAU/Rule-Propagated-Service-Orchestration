package org.btsn.derby.Analysis.helper;

import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Monitor's database is embedded Derby, which one process can open at a time. A viewer that keeps its
 * window open would otherwise hold the database and lock out the other viewers and the analyser,
 * so each viewer reads what it needs and then releases the database.
 */
public final class MonitorDatabase {
    private MonitorDatabase() { }

    /**
     * Set by a viewer's main(): that process only reads, so it gives the database back after each read.
     * Code that keeps its own connection open (the analyser, the checks) leaves it unset.
     */
    public static volatile boolean releaseAfterReading = false;

    /** In a viewer process, shuts down the database at this jdbc:derby: URL, releasing it for other programs. */
    public static void release(String url) {
        if (!releaseAfterReading) return;
        try {
            DriverManager.getConnection(url + ";shutdown=true");
        } catch (SQLException expected) {
            // Derby reports a successful single-database shutdown as SQLState 08006; anything else
            // means the database was not open here, which leaves nothing to release.
        }
    }

    /** A message for a database another process still holds (Derby XSDB6), or null for any other failure. */
    public static String lockedMessage(Throwable failure) {
        for (Throwable t = failure; t != null; t = nextCause(t)) {
            if (t instanceof SQLException && "XSDB6".equals(((SQLException)t).getSQLState()))
                return "Monitor's database is in use by another program: stop the hosts, or close the viewer or analysis holding it, then Refresh";
            if (String.valueOf(t.getMessage()).contains("XSDB6"))
                return "Monitor's database is in use by another program: stop the hosts, or close the viewer or analysis holding it, then Refresh";
        }
        return null;
    }

    private static Throwable nextCause(Throwable t) {
        if (t instanceof SQLException && ((SQLException)t).getNextException() != null && ((SQLException)t).getNextException() != t)
            return ((SQLException)t).getNextException();
        return t.getCause() == t ? null : t.getCause();
    }
}
