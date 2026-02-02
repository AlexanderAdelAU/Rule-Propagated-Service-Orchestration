package org.btsn.derby.tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;

import org.apache.derby.jdbc.EmbeddedDriver; // Ensure derby.jar is in classpath

public class PurgeAllDerbyDataBases {

	// --- Configuration ---
	// Adjust this to the root directory containing your Derby database FOLDERS
	// For example, if your databases are in:
	// ./db1/
	// ./db2/
	// Then DB_ROOT should be "./"
	private static final String DB_ROOT = "./";
	private static final String JDBC_PREFIX = "jdbc:derby:";

	public static void main(String[] args) {
		// Register the Derby embedded driver (important for shutdown)
		try {
			DriverManager.registerDriver(new EmbeddedDriver());
			// Or, Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
			System.out.println("Derby Embedded Driver registered.");
		} catch (SQLException e) {
			System.err.println("CRITICAL: Failed to register Derby Embedded Driver.");
			e.printStackTrace();
			return;
		}

		File rootDir = new File(DB_ROOT);
		if (!rootDir.exists() || !rootDir.isDirectory()) {
			System.err.println("Error: DB_ROOT directory does not exist or is not a directory: " + rootDir.getAbsolutePath());
			return;
		}

		System.out.println("Scanning for Derby databases in: " + rootDir.getAbsolutePath());
		File[] potentialDbDirs = rootDir.listFiles(File::isDirectory);

		if (potentialDbDirs == null || potentialDbDirs.length == 0) {
			System.out.println("No subdirectories found in DB_ROOT to check.");
			return;
		}

		List<File> derbyDatabases = new ArrayList<>();
		for (File dir : potentialDbDirs) {
			if (isLikelyDerbyDatabase(dir)) {
				derbyDatabases.add(dir);
			}
		}

		if (derbyDatabases.isEmpty()) {
			System.out.println("No Derby databases found in " + rootDir.getAbsolutePath());
			return;
		}

		System.out.println("\n--- The following Derby databases will be PERMANENTLY DELETED ---");
		for (File dbDir : derbyDatabases) {
			System.out.println(" - " + dbDir.getName() + " (path: " + dbDir.getAbsolutePath() + ")");
		}
		System.out.println("---------------------------------------------------------------");

		System.out.print("\nARE YOU ABSOLUTELY SURE you want to delete all listed databases? "
				+ "This action is IRREVERSIBLE. (Type 'yes' to confirm): ");

		try (Scanner scanner = new Scanner(System.in)) {
			String confirmation = scanner.nextLine();

			if (!"yes".equalsIgnoreCase(confirmation)) {
				System.out.println("Purge operation cancelled by the user.");
				return;
			}

			System.out.println("\n--- Starting Purge Operation ---");
			int successCount = 0;
			int failCount = 0;

			for (File dbDir : derbyDatabases) {
				System.out.println("\nProcessing database: " + dbDir.getName());
				boolean success = purgeDatabase(dbDir);
				if (success) {
					successCount++;
				} else {
					failCount++;
				}
			}

			System.out.println("\n--- Purge Operation Summary ---");
			System.out.println("Successfully purged: " + successCount + " database(s).");
			System.out.println("Failed to purge: " + failCount + " database(s).");
			System.out.println("-------------------------------");

		}
	}

	/**
	 * Checks if a directory is likely a Derby database by looking for a 'seg0' subdirectory.
	 */
	private static boolean isLikelyDerbyDatabase(File directory) {
		if (directory == null || !directory.isDirectory()) {
			return false;
		}
		File seg0Dir = new File(directory, "seg0");
		return seg0Dir.exists() && seg0Dir.isDirectory();
	}

	/**
	 * Attempts to shut down and then delete the database directory.
	 */
	private static boolean purgeDatabase(File dbDirectory) {
		String dbName = dbDirectory.getName(); // Or use dbDirectory.getAbsolutePath() if DB_ROOT is not flat
		String dbPathForShutdown = dbDirectory.getAbsolutePath(); // Use absolute path for JDBC connection

		// 1. Attempt to shut down the database
		System.out.println("  Attempting to shut down database: " + dbName);
		try {
			// For embedded databases, the path in the JDBC URL is the direct file system path
			DriverManager.getConnection(JDBC_PREFIX + dbPathForShutdown + ";shutdown=true");
			// If shutdown is successful, an SQLException (08006 or XJ015) is expected.
			// If we reach here, it might mean the DB wasn't running or another issue.
			System.out.println("  Shutdown command sent, but no expected SQLException received (might be okay if already stopped).");
		} catch (SQLException e) {
			if ("08006".equals(e.getSQLState()) || "XJ015".equals(e.getSQLState())) {
				System.out.println("  Database '" + dbName + "' shut down successfully.");
			} else {
				// Other SQL error during shutdown attempt
				System.err.println(
						"  Warning: SQL error during shutdown attempt for '" + dbName + "': " + e.getSQLState() + " - " + e.getMessage());
				// Proceed with deletion anyway, as the DB might not be cleanly shutdown-able
				// or already stopped, or corrupted.
			}
		}

		// 2. Delete the database directory
		System.out.println("  Attempting to delete directory: " + dbDirectory.getAbsolutePath());
		try {
			deleteDirectoryRecursively(dbDirectory.toPath());
			System.out.println("  Successfully deleted database directory: " + dbDirectory.getName());
			return true;
		} catch (IOException e) {
			System.err.println("  FAILED to delete database directory '" + dbDirectory.getName() + "'.");
			System.err.println("  Reason: " + e.getMessage());
			e.printStackTrace(); // Print stack trace for more details on failure
			return false;
		}
	}

	/**
	 * Deletes a directory and all its contents recursively.
	 */
	private static void deleteDirectoryRecursively(Path path) throws IOException {
		if (Files.isDirectory(path)) {
			// Stream all paths inside the directory
			// Sort in reverse order to delete files before directories
			Files.list(path).sorted(Comparator.reverseOrder()) // Important: delete contents before parent
					.forEach(p -> {
						try {
							deleteDirectoryRecursively(p);
						} catch (IOException e) {
							// Wrap in a RuntimeException to handle it in the lambda
							// Or, collect exceptions and throw a single one at the end
							throw new RuntimeException("Failed to delete " + p, e);
						}
					});
		}
		try {
			Files.delete(path); // Delete the file or empty directory
		} catch (IOException e) {
			// It's possible that some OS-level locks prevent immediate deletion
			// especially on Windows, even after shutdown.
			System.err.println("  Could not delete: " + path + ". It might be locked. Error: " + e.getMessage());
			throw e; // Re-throw to indicate failure
		}
	}
}