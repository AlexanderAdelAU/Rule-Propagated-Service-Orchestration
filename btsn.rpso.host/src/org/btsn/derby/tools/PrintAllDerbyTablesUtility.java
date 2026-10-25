package org.btsn.derby.tools;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.jdbc.EmbeddedDriver;

public class PrintAllDerbyTablesUtility {

	// Define the base directory to scan for Derby databases.
	// "." means the current directory where the utility is run.
	// You can change this to a specific path, e.g., "C:/derby_databases/"
	private static final String BASE_SCAN_DIRECTORY = ".";

	public static void main(String[] args) {
		try {
			// Register the Derby embedded driver (only needs to be done once)
			DriverManager.registerDriver(new EmbeddedDriver());
			System.out.println("Derby Embedded Driver registered.");

			File baseDir = new File(BASE_SCAN_DIRECTORY);
			if (!baseDir.exists() || !baseDir.isDirectory()) {
				System.err.println("Error: Base scan directory does not exist or is not a directory: " + baseDir.getAbsolutePath());
				return;
			}

			System.out.println("Scanning for Derby databases in: " + baseDir.getAbsolutePath() + "\n");
			findAndProcessDatabases(baseDir);

		} catch (SQLException e) {
			System.err.println("Error registering Derby driver: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private static void findAndProcessDatabases(File directory) {
		File[] files = directory.listFiles();
		if (files == null) {
			System.err.println("Could not list files in directory: " + directory.getAbsolutePath());
			return;
		}

		for (File file : files) {
			if (file.isDirectory()) {
				// A simple heuristic: if a directory contains a 'seg0' subdirectory,
				// it's likely a Derby database.
				File seg0Dir = new File(file, "seg0");
				if (seg0Dir.exists() && seg0Dir.isDirectory()) {
					processDatabase(file);
				} else {
					// Optionally, you could recursively scan subdirectories
					// findAndProcessDatabases(file); // Uncomment to scan recursively
				}
			}
		}
	}

	private static void processDatabase(File dbDirectory) {
		String dbUrl = "jdbc:derby:" + dbDirectory.getAbsolutePath();
		System.out.println("\n=======================================================");
		System.out.println("Attempting to connect to database: " + dbDirectory.getName() + " (" + dbUrl + ")");
		System.out.println("=======================================================");

		try (Connection connection = DriverManager.getConnection(dbUrl)) {
			System.out.println("Successfully connected to database: " + dbDirectory.getName());

			List<String> tableNames = getAllTableNames(connection);
			if (tableNames.isEmpty()) {
				System.out.println("No user tables found in database: " + dbDirectory.getName());
			} else {
				System.out.println("Found tables: " + tableNames);
				for (String tableName : tableNames) {
					printTableContents(connection, tableName);
				}
			}

		} catch (SQLException e) {
			// It's common for this to fail if a directory isn't a valid/accessible Derby DB
			System.err.println("Could not connect to or process database at '" + dbDirectory.getAbsolutePath() + "': " + e.getMessage());
			// e.printStackTrace(); // Uncomment for more detailed error, can be verbose
		} finally {
			// Attempt to shutdown the specific database to release locks,
			// especially if it was created by this process (though not in this utility's scope)
			// For a general utility, this might be too aggressive if other apps use the DB.
			// Consider if this is needed for your use case.
			try {
				DriverManager.getConnection(dbUrl + ";shutdown=true");
			} catch (SQLException se) {
				// SQL Exception XJ015 (shutdown) is expected.
				if (!"XJ015".equals(se.getSQLState())) {
					// System.err.println("Error during database shutdown for " + dbDirectory.getName() + ": " +
					// se.getMessage());
				}
			}
			System.out.println("-------------------------------------------------------");
			System.out.println("Finished processing database: " + dbDirectory.getName());
			System.out.println("-------------------------------------------------------");
		}
	}

	private static List<String> getAllTableNames(Connection connection) throws SQLException {
		List<String> tableNames = new ArrayList<>();
		DatabaseMetaData metaData = connection.getMetaData();
		try (ResultSet rs = metaData.getTables(null, null, "%", new String[] { "TABLE" })) {
			while (rs.next()) {
				String schema = rs.getString("TABLE_SCHEM");
				String tableName = rs.getString("TABLE_NAME");
				if (!"SYS".equalsIgnoreCase(schema)) {
					System.out.println("Found Table: " + tableName + ", Schema: " + schema); // Added logging
					tableNames.add(tableName);
				}
			}
		}
		return tableNames;
	}

	private static void printTableContents(Connection connection, String tableName) {
		System.out.println("\n--- Contents of table: " + tableName + " ---");
		try (Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
				ResultSet resultSet = statement.executeQuery("SELECT * FROM \"" + tableName + "\"")) { // Qualify
																										// with
																										// schema
																										// and
																										// quote

			ResultSetMetaData metaData = resultSet.getMetaData();
			int columnCount = metaData.getColumnCount();

			// Print column headers
			for (int i = 1; i <= columnCount; i++) {
				System.out.printf("%-25s", metaData.getColumnName(i));
			}
			System.out.println();
			for (int i = 1; i <= columnCount; i++) {
				System.out.printf("%-25s", "-------------------------");
			}
			System.out.println();

			// Print data rows
			boolean hasRows = false;
			if (resultSet.next()) {
				hasRows = true;
				do {
					for (int i = 1; i <= columnCount; i++) {
						String value = resultSet.getString(i);
						System.out.printf("%-25s", (value != null ? value : "NULL"));
					}
					System.out.println();
				} while (resultSet.next());
			}

			if (!hasRows) {
				System.out.println("(No data in this table)");
			}

		} catch (SQLException e) {
			System.err.println("Error querying table '" + tableName + "': " + e.getMessage());
		}
	}
}