package org.btsn.derby.tools;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BuildDerbyDB {
	private static final String DB_URL = "jdbc:derby:OnlinePurchases;create=true";
	private static final String CREATE_TABLE_BOOKS_SQL = "CREATE TABLE BOOKS (" + "ID INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
			+ "ISBN VARCHAR(32), " + "TITLE VARCHAR(255), " + "STOCK INT, " + "PRICE DECIMAL(10,2))";

	private static final String CREATE_TABLE_CUSTOMERS_SQL = "CREATE TABLE CUSTOMERS ("
			+ "ID INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " + "NAME VARCHAR(255), " + "EMAIL VARCHAR(255), " + "COUNTRY VARCHAR(64))";

	private static final String CREATE_TABLE_ORDERS_SQL = "CREATE TABLE ORDERS (" + "ID INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
			+ "CUSTOMER_ID INT, " + "BOOK_ID INT, " + "QUANTITY INT, " + "ORDER_DATE TIMESTAMP, "
			+ "FOREIGN KEY (CUSTOMER_ID) REFERENCES CUSTOMERS(ID), " + "FOREIGN KEY (BOOK_ID) REFERENCES BOOKS(ID))";

	private static final String INSERT_BOOK_SQL = "INSERT INTO BOOKS (ISBN, TITLE, STOCK, PRICE) VALUES (?,?,?,?)";

	private static final String INSERT_CUSTOMER_SQL = "INSERT INTO CUSTOMERS (NAME, EMAIL, COUNTRY) VALUES (?,?,?)";

	private static final String INSERT_ORDER_SQL = "INSERT INTO ORDERS (CUSTOMER_ID, BOOK_ID, QUANTITY, ORDER_DATE) VALUES (?,?,?,?)";

	public static void main(String[] args) throws Exception {
		try (Connection conn = DriverManager.getConnection(DB_URL)) {
			ensureBooksTable(conn);
			ensureCustomersTable(conn);
			ensureOrdersTable(conn);
			purgeTables(conn);
			populateSampleBooks(conn);
			importSampleCustomers(conn);
			populateSampleOrders(conn);
			printBooksTable(conn);
			printCustomersTable(conn);
			printOrdersTable(conn);

		}
		System.out.println("Import completed.");
	}

	private static void ensureBooksTable(Connection conn) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(CREATE_TABLE_BOOKS_SQL)) {
			ps.execute();
		} catch (SQLException e) {
			if (!"X0Y32".equals(e.getSQLState())) {
				throw e;
			}
		}
	}

	private static void ensureCustomersTable(Connection conn) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(CREATE_TABLE_CUSTOMERS_SQL)) {
			ps.execute();
		} catch (SQLException e) {
			if (!"X0Y32".equals(e.getSQLState())) {
				throw e;
			}
		}
	}

	private static void ensureOrdersTable(Connection conn) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(CREATE_TABLE_ORDERS_SQL)) {
			ps.execute();
		} catch (SQLException e) {
			if (!"X0Y32".equals(e.getSQLState())) {
				throw e;
			}
		}
	}

	private static void purgeTables(Connection conn) throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("DELETE FROM ORDERS");
			stmt.executeUpdate("DELETE FROM BOOKS");
			stmt.executeUpdate("DELETE FROM CUSTOMERS");
		}
	}

	private static void importBooksCsv(Connection conn, String filePath) throws Exception {
		try (BufferedReader br = new BufferedReader(new FileReader(filePath));
				PreparedStatement ps = conn.prepareStatement(INSERT_BOOK_SQL)) {
			String line;
			boolean skipHeader = true;
			while ((line = br.readLine()) != null) {
				if (skipHeader) {
					skipHeader = false;
					continue;
				}
				String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
				if (parts.length != 5) {
					continue;
				}
				ps.setString(1, parts[1].trim());
				ps.setString(2, parts[2].trim());
				ps.setInt(3, Integer.parseInt(parts[3].trim()));
				BigDecimal price = new BigDecimal(parts[4].replace("$", "").trim());
				ps.setBigDecimal(4, price);
				ps.executeUpdate();
			}
		}
	}

	private static void populateSampleBooks(Connection conn) throws SQLException {
		Object[][] books = { { "9780000000001", "Java Basics", 25, new BigDecimal("49.99") },
				{ "9780000000002", "Advanced Java", 15, new BigDecimal("59.95") },
				{ "9780000000003", "Database Systems", 10, new BigDecimal("39.99") },
				{ "9780000000004", "Algorithms", 5, new BigDecimal("79.00") },
				{ "9780000000005", "Software Engineering", 20, new BigDecimal("45.50") } };
		try (PreparedStatement ps = conn.prepareStatement(INSERT_BOOK_SQL)) {
			for (Object[] book : books) {
				ps.setString(1, (String) book[0]);
				ps.setString(2, (String) book[1]);
				ps.setInt(3, (int) book[2]);
				ps.setBigDecimal(4, (BigDecimal) book[3]);
				ps.executeUpdate();
			}
		}
	}

	private static void importSampleCustomers(Connection conn) throws SQLException {
		String[][] sampleData = { { "Alice Smith", "alice@example.com", "USA" }, { "Bob Johnson", "bob@example.com", "Canada" },
				{ "Carol Lee", "carol@example.com", "UK" }, { "David Tan", "david@example.com", "Australia" },
				{ "Eva Muller", "eva@example.com", "Germany" }, { "Farid Khan", "farid@example.com", "India" },
				{ "Giulia Rossi", "giulia@example.com", "Italy" }, { "Hiroshi Sato", "hiroshi@example.com", "Japan" },
				{ "Isla McDonald", "isla@example.com", "Scotland" }, { "Juan Perez", "juan@example.com", "Mexico" } };
		try (PreparedStatement ps = conn.prepareStatement(INSERT_CUSTOMER_SQL)) {
			for (String[] customer : sampleData) {
				ps.setString(1, customer[0]);
				ps.setString(2, customer[1]);
				ps.setString(3, customer[2]);
				ps.executeUpdate();
			}
		}
	}

	private static void populateSampleOrders(Connection conn) throws SQLException {
		Random rand = new Random();
		List<Integer> bookIds = new ArrayList<>();
		List<Integer> custIds = new ArrayList<>();

		try (Statement stmt = conn.createStatement(); ResultSet bookRs = stmt.executeQuery("SELECT ID FROM BOOKS")) {
			while (bookRs.next()) {
				bookIds.add(bookRs.getInt("ID"));
			}
		}

		try (Statement stmt = conn.createStatement(); ResultSet custRs = stmt.executeQuery("SELECT ID FROM CUSTOMERS")) {
			while (custRs.next()) {
				custIds.add(custRs.getInt("ID"));
			}
		}

		try (PreparedStatement ps = conn.prepareStatement(INSERT_ORDER_SQL)) {
			for (int i = 0; i < 10; i++) {
				int customerId = custIds.get(rand.nextInt(custIds.size()));
				int bookId = bookIds.get(rand.nextInt(bookIds.size()));
				int quantity = 1 + rand.nextInt(5);
				Timestamp orderDate = Timestamp.valueOf(LocalDateTime.now().minusDays(rand.nextInt(30)));
				ps.setInt(1, customerId);
				ps.setInt(2, bookId);
				ps.setInt(3, quantity);
				ps.setTimestamp(4, orderDate);
				ps.executeUpdate();
			}
		}
	}

	private static void printBooksTable(Connection conn) throws SQLException {
		String query = "SELECT ID, ISBN, TITLE, STOCK, PRICE FROM BOOKS";
		try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
			System.out.println("\nContents of BOOKS table:");
			while (rs.next()) {
				int id = rs.getInt("ID");
				String isbn = rs.getString("ISBN");
				String title = rs.getString("TITLE");
				int stock = rs.getInt("STOCK");
				BigDecimal price = rs.getBigDecimal("PRICE");
				System.out.printf("ID: %d, ISBN: %s, TITLE: %s, STOCK: %d, PRICE: $%.2f%n", id, isbn, title, stock, price);
			}
		}
	}

	private static void printCustomersTable(Connection conn) throws SQLException {
		String query = "SELECT ID, NAME, EMAIL, COUNTRY FROM CUSTOMERS";
		try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
			System.out.println("\nContents of CUSTOMERS table:");
			while (rs.next()) {
				int id = rs.getInt("ID");
				String name = rs.getString("NAME");
				String email = rs.getString("EMAIL");
				String country = rs.getString("COUNTRY");
				System.out.printf("ID: %d, NAME: %s, EMAIL: %s, COUNTRY: %s%n", id, name, email, country);
			}
		}
	}

	private static void printOrdersTable(Connection conn) throws SQLException {
		String query = "SELECT ID, CUSTOMER_ID, BOOK_ID, QUANTITY, ORDER_DATE FROM ORDERS";
		try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
			System.out.println("\nContents of ORDERS table:");
			while (rs.next()) {
				System.out.printf("ID: %d, CUSTOMER_ID: %d, BOOK_ID: %d, QUANTITY: %d, ORDER_DATE: %s%n", rs.getInt("ID"),
						rs.getInt("CUSTOMER_ID"), rs.getInt("BOOK_ID"), rs.getInt("QUANTITY"), rs.getTimestamp("ORDER_DATE"));
			}
		}
	}

}

/*
 * Add to your pom.xml if you use Maven <dependency> <groupId>org.apache.derby</groupId> <artifactId>derby</artifactId>
 * <version>10.17.1.0</version> <scope>runtime</scope> </dependency>
 */
