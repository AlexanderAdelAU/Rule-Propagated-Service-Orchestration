package org.btsn.derby.tools;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

public class ListDerbyDB {
	private static final String DB_ROOT = "./"; // Adjust this to your database directory
	private static final String JDBC_PREFIX = "jdbc:derby:";

	public static void main(String[] args) {
		File rootDir = new File(DB_ROOT);
		File[] dirs = rootDir.listFiles(File::isDirectory);

		if (dirs == null) {
			System.out.println("No directories found.");
			return;
		}

		for (File dir : dirs) {
			String dbName = dir.getName();
			String dbPath = JDBC_PREFIX + dbName;

			try (Connection conn = DriverManager.getConnection(dbPath)) {
				System.out.println("\n Database: " + dbName);
				listTablesAndColumns(conn);
			} catch (SQLException e) {
				// Not a Derby DB, or cannot connect
				if (!"XJ004".equals(e.getSQLState())) {
					System.err.println("Skipping: " + dbName + " (Reason: " + e.getMessage() + ")");
				}
			}
		}
	}

	private static void listTablesAndColumns(Connection conn) throws SQLException {
		DatabaseMetaData meta = conn.getMetaData();
		ResultSet tables = meta.getTables(null, null, null, new String[] { "TABLE" });

		while (tables.next()) {
			String tableName = tables.getString("TABLE_NAME");
			System.out.println("Table: " + tableName);

			// List columns in the table
			ResultSet columns = meta.getColumns(null, null, tableName, null);
			while (columns.next()) {
				String colName = columns.getString("COLUMN_NAME");
				String colType = columns.getString("TYPE_NAME");
				int colSize = columns.getInt("COLUMN_SIZE");
				String nullable = columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable ? "YES" : "NO";

				System.out.printf("    - %s (%s, size: %d, nullable: %s)%n", colName, colType, colSize, nullable);
			}
		}
	}
}
