package org.btsn.derby.tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.derby.jdbc.EmbeddedDriver;

public class BuildMonitorDB {

	private static final String DB_NAME = "MonitorDB";
	private static final String MEASURE_TABLE = "ProcessMeasurements";
	private static final String EXPIRED_TABLE = "ExpiredPackets";

	public static void main(String[] args) {
		BuildMonitorDB builder = new BuildMonitorDB();
		builder.rebuildDatabase();
	}

	public void rebuildDatabase() {
		try {
			// Register the Derby embedded driver
			DriverManager.registerDriver(new EmbeddedDriver());

			// Get a connection to the database
			String dbUrl = "jdbc:derby:" + DB_NAME + ";create=true";
			try (Connection connection = DriverManager.getConnection(dbUrl)) {
				System.out.println("Connected to Derby database: " + DB_NAME);

				dropTableIfExists(connection, MEASURE_TABLE);
				dropTableIfExists(connection, EXPIRED_TABLE);

				createTables(connection);

			} catch (SQLException e) {
				System.err.println("Error connecting to or rebuilding the database: " + e.getMessage());
				e.printStackTrace();
			}

		} catch (SQLException e) {
			System.err.println("Error registering Derby driver: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("Database rebuild process completed.");
	}

	private void dropTableIfExists(Connection connection, String tableName) {
		try (Statement statement = connection.createStatement()) {
			String dropSQL = "DROP TABLE " + tableName;
			statement.executeUpdate(dropSQL);
			System.out.println("Table '" + tableName + "' dropped (if it existed).");
		} catch (SQLException ex) {
			// Ignore SQLState 42Y07 (Table/View does not exist)
			if (!"42Y07".equals(ex.getSQLState())) {
				System.err.println("Error dropping table '" + tableName + "': " + ex.getMessage());
				ex.printStackTrace();
			} else {
				System.out.println("Table '" + tableName + "' did not exist.");
			}
		}
	}

	private void createTables(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			// Create ProcessMeasurements table
			String createMeasureTableSQL = "CREATE TABLE " + MEASURE_TABLE + " (" + "ID INT NOT NULL GENERATED ALWAYS AS IDENTITY,"
					+ "Service VARCHAR(255)," + "Operation VARCHAR(255)," + "SequenceID VARCHAR(255)," + "ArrivalTime VARCHAR(255)," + // Added
																																		// ArrivalTime
					"ProcessStartTime VARCHAR(255)," + "ProcessElapsedTime VARCHAR(255)," + "NotAfter VARCHAR(255)," + "PRIMARY KEY (ID)"
					+ ")";
			statement.executeUpdate(createMeasureTableSQL);
			System.out.println("Table '" + MEASURE_TABLE + "' created.");

			// Create ExpiredPackets table
			String createExpiredTableSQL = "CREATE TABLE " + EXPIRED_TABLE + " (" + "ID INT NOT NULL GENERATED ALWAYS AS IDENTITY,"
					+ "SequenceID VARCHAR(255)," + "ProcessName VARCHAR(255)," + "ProcessStartTime VARCHAR(255),"
					+ "TimeArrived VARCHAR(255)," + "NotAfter VARCHAR(255)," + "TotalProcessTime VARCHAR(255),"
					+ "PacketsExpired VARCHAR(255)," + "PRIMARY KEY (ID)" + ")";
			statement.executeUpdate(createExpiredTableSQL);
			System.out.println("Table '" + EXPIRED_TABLE + "' created.");

		} catch (SQLException e) {
			System.err.println("Error creating tables: " + e.getMessage());
			throw e; // Re-throw the exception
		}
	}
}