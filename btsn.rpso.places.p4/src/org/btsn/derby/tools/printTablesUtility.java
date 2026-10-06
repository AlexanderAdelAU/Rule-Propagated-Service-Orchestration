package org.btsn.derby.tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.derby.jdbc.EmbeddedDriver;

public class printTablesUtility {

	private static final String DB_NAME = "MonitorDB";

	public static void main(String[] args) {
		try {
			// Register the Derby embedded driver
			DriverManager.registerDriver(new EmbeddedDriver());

			// Establish a connection to the database
			String dbUrl = "jdbc:derby:" + DB_NAME;
			try (Connection connection = DriverManager.getConnection(dbUrl)) {
				System.out.println("Connected to Derby database: " + DB_NAME);

				printTable(connection, "ProcessMeasurements");
				printTable(connection, "ExpiredPackets");

			} catch (SQLException e) {
				System.err.println("Error connecting to or querying the database: " + e.getMessage());
				e.printStackTrace();
			}

		} catch (SQLException e) {
			System.err.println("Error registering Derby driver: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private static void printTable(Connection connection, String tableName) throws SQLException {
		System.out.println("\n--- Contents of table: " + tableName + " ---");
		Statement statement = null;
		ResultSet resultSet = null;
		try {
			// Create a Statement that allows scrollable ResultSets
			statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
			resultSet = statement.executeQuery("SELECT * FROM " + tableName);

			ResultSetMetaData metaData = resultSet.getMetaData();
			int columnCount = metaData.getColumnCount();

			// Print column headers
			for (int i = 1; i <= columnCount; i++) {
				System.out.printf("%-20s", metaData.getColumnName(i));
			}
			System.out.println();
			for (int i = 1; i <= columnCount; i++) {
				System.out.printf("%-20s", "--------------------");
			}
			System.out.println();

			// Print data rows
			if (resultSet.next()) { // Move to the first row (if any)
				do {
					for (int i = 1; i <= columnCount; i++) {
						System.out.printf("%-20s", resultSet.getString(i));
					}
					System.out.println();
				} while (resultSet.next());
			} else {
				System.out.println("(No data in this table)");
			}

		} catch (SQLException e) {
			System.err.println("Error querying table '" + tableName + "': " + e.getMessage());
			e.printStackTrace();
		} finally {
			// Ensure resources are closed
			if (resultSet != null) {
				try {
					resultSet.close();
				} catch (SQLException e) {
					System.err.println("Error closing ResultSet: " + e.getMessage());
				}
			}
			if (statement != null) {
				try {
					statement.close();
				} catch (SQLException e) {
					System.err.println("Error closing Statement: " + e.getMessage());
				}
			}
		}
	}
}