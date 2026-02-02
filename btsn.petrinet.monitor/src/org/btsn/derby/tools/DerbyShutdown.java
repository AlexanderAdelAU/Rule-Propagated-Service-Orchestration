package org.btsn.derby.tools;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.apache.derby.jdbc.EmbeddedDriver;

/**
 * Simple utility to shutdown Derby database instances
 * This helps resolve database locking issues when multiple JVMs try to access the same database
 */
public class DerbyShutdown {
    
    private static final String PROTOCOL = "jdbc:derby:";
    private static final String DB_NAME = "./ServiceAnalysisDataBase";
    
    static {
        try {
            DriverManager.registerDriver(new EmbeddedDriver());
        } catch (SQLException e) {
            System.err.println("Failed to register Derby driver: " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        System.out.println("=== DERBY DATABASE SHUTDOWN UTILITY ===");
        
        try {
            // Shutdown specific database
            System.out.println("Shutting down ServiceAnalysisDataBase...");
            try {
                DriverManager.getConnection(PROTOCOL + DB_NAME + ";shutdown=true");
            } catch (SQLException e) {
                // Expected - Derby shutdown always throws an exception with SQLState "08006"
                if ("08006".equals(e.getSQLState())) {
                    System.out.println("ServiceAnalysisDataBase shutdown successful");
                } else {
                    System.out.println("Unexpected exception during database shutdown: " + e.getMessage());
                }
            }
            
            // Small delay
            Thread.sleep(1000);
            
            // Shutdown Derby system
            System.out.println("Shutting down Derby system...");
            try {
                DriverManager.getConnection("jdbc:derby:;shutdown=true");
            } catch (SQLException e) {
                // Expected - Derby system shutdown always throws an exception with SQLState "XJ015"
                if ("XJ015".equals(e.getSQLState())) {
                    System.out.println("Derby system shutdown successful");
                } else {
                    System.out.println("Unexpected exception during system shutdown: " + e.getMessage());
                }
            }
            
            System.out.println("Derby shutdown complete - database locks released");
            
        } catch (Exception e) {
            System.err.println("Error during Derby shutdown: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("=== DERBY SHUTDOWN UTILITY COMPLETE ===");
    }
}