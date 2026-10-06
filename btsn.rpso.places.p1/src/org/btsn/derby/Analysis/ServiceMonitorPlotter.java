package org.btsn.derby.Analysis;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.swing.SwingUtilities;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils; // Changed from ChartUtilities
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot; // Import for XYPlot
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer; // Import for XYLineAndShapeRenderer
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.statistics.HistogramDataset;
import org.jfree.data.statistics.HistogramType;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

// ServiceMonitorPlotter no longer extends JFrame, as it's now a utility class
public class ServiceMonitorPlotter {

	private static final String DRIVER = "org.apache.derby.jdbc.EmbeddedDriver";
	private static final String DB_NAME = "ServiceMonitorDatabase";
	private static final String CONNECTION_URL = "jdbc:derby:" + DB_NAME + ";create=true";
	private static final String TABLE_NAME = "EVENTRESPONSETABLE";
	private static final String CHART_FOLDER = "chart"; // New constant for the chart folder

	// Data containers are now local to fetchDataFromDerby or passed around
	// The constructor is no longer needed as we're managing frames externally
	// public ServiceMonitorPlotter(String title) { /* ... */ }

	private static void fetchDataFromDerby(XYSeries series, List<Double> responseTimes) {
		Connection conn = null;
		Statement stmt = null;
		ResultSet rs = null;

		try {
			Class.forName(DRIVER);
			System.out.println("Derby JDBC driver loaded.");

			conn = DriverManager.getConnection(CONNECTION_URL);
			System.out.println("Connected to database: " + DB_NAME);

			stmt = conn.createStatement();

			if (!tableExists(conn, TABLE_NAME, conn)) { // Pass connection to tableExists
				System.out.println("Table " + TABLE_NAME + " does not exist. Creating and inserting sample data...");
				createAndInsertSampleData(stmt);
			}

			String sql = "SELECT SEQUENCEID, EVENTARRIVALTIME, EVENTCOMPLETETIME FROM " + TABLE_NAME + " ORDER BY SEQUENCEID ASC";
			rs = stmt.executeQuery(sql);

			while (rs.next()) {
				long sequenceId = rs.getLong("SEQUENCEID");
				long arrivalTimeMillis = rs.getLong("EVENTARRIVALTIME");
				long completeTimeMillis = rs.getLong("EVENTCOMPLETETIME");

				if (arrivalTimeMillis > 0 && completeTimeMillis > 0) {
					long responseTimeMillis = completeTimeMillis - arrivalTimeMillis;
					series.add(sequenceId, responseTimeMillis);
					responseTimes.add((double) responseTimeMillis);
				} else {
					System.out.println("Skipping SEQUENCEID " + sequenceId + " due to invalid (0 or negative) timestamp values.");
				}
			}
			System.out.println("Data fetched successfully.");

		} catch (ClassNotFoundException e) {
			System.err.println("Derby JDBC Driver not found. Make sure derby.jar is in your classpath.");
			e.printStackTrace();
		} catch (SQLException e) {
			System.err.println("SQL Error: " + e.getMessage());
			e.printStackTrace();
			if (e.getSQLState().equals("XJ004")) {
				System.err.println("Database might be in use by another process. Ensure no other application is connected.");
			}
		} finally {
			try {
				if (rs != null)
					rs.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
			try {
				if (stmt != null)
					stmt.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
			try {
				if (conn != null)
					conn.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}

	// tableExists now takes a Connection object as well
	private static boolean tableExists(Connection conn, String tableName, Connection currentConn) throws SQLException {
		DatabaseMetaData dbm = currentConn.getMetaData(); // Use the provided connection
		try (ResultSet tables = dbm.getTables(null, null, tableName.toUpperCase(), null)) {
			return tables.next();
		}
	}

	private static void createAndInsertSampleData(Statement stmt) throws SQLException {
		String createTableSQL = "CREATE TABLE " + TABLE_NAME + " ("
				+ "SEQUENCEID BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1), " + "EVENTNAME VARCHAR(255), "
				+ "EVENTARRIVALTIME BIGINT, " + "EVENTCOMPLETETIME BIGINT" + ")";
		stmt.execute(createTableSQL);
		System.out.println("Table " + TABLE_NAME + " created.");

		PreparedStatement pstmt = null;
		Random random = new Random();
		long currentTime = System.currentTimeMillis();

		try {
			String insertSQL = "INSERT INTO " + TABLE_NAME + " (EVENTNAME, EVENTARRIVALTIME, EVENTCOMPLETETIME) VALUES (?, ?, ?)";
			pstmt = stmt.getConnection().prepareStatement(insertSQL);

			for (int i = 0; i < 50; i++) {
				long arrivalOffset = random.nextInt(600000);
				long arrivalTimeMillis = currentTime - arrivalOffset;
				long completeTimeMillis = arrivalTimeMillis + random.nextInt(5000) + 100;

				pstmt.setString(1, "Event_" + (i + 1));
				pstmt.setLong(2, arrivalTimeMillis);
				pstmt.setLong(3, completeTimeMillis);
				pstmt.addBatch();
			}
			pstmt.executeBatch();
			System.out.println("50 sample data rows inserted into " + TABLE_NAME);
		} finally {
			if (pstmt != null)
				pstmt.close();
		}
	}

	// Modified to create a line chart instead of a scatter plot
	// Modified to create a line chart instead of a scatter plot
	private static JFreeChart createLineChartWithEquation(XYSeriesCollection dataset, String equationText) {
		JFreeChart chart = ChartFactory.createXYLineChart("Event Response Time vs. Sequence ID", // Chart title
				"Sequence ID", // X-axis label
				"Response Time (ms)", // Y-axis label
				dataset, // Dataset
				PlotOrientation.VERTICAL, // Plot orientation
				true, // Show legend
				true, // Use tooltips
				false // Generate URLs
		);

		chart.setBackgroundPaint(Color.WHITE);

		// Customize the plot to draw lines
		XYPlot plot = chart.getXYPlot();
		plot.setBackgroundPaint(Color.lightGray);
		plot.setDomainGridlinePaint(Color.white);
		plot.setRangeGridlinePaint(Color.white);

		XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
		renderer.setSeriesPaint(0, Color.BLUE);
		renderer.setSeriesStroke(0, new BasicStroke(2.0f)); // Make the line thicker
		renderer.setSeriesShapesVisible(0, false); // Don't show shapes/points on the line
		plot.setRenderer(renderer);

		// Add the equation to the plot
		// Get the current domain and range axis bounds for accurate placement
		double xLow = plot.getDomainAxis().getLowerBound();
		double xHigh = plot.getDomainAxis().getUpperBound();
		double yLow = plot.getRangeAxis().getLowerBound();
		double yHigh = plot.getRangeAxis().getUpperBound();

		// Position the text near the top-left of the plot area
		// Adjust these multipliers (e.g., 0.1 for x, 0.9 for y) as needed for your specific data
		XYTextAnnotation annotation = new XYTextAnnotation(equationText, xLow + (xHigh - xLow) * 0.05, // 5% from the
																										// left edge
				yLow + (yHigh - yLow) * 0.95); // 95% from the bottom edge (i.e., 5% from the top)
		annotation.setFont(new Font("SansSerif", Font.BOLD, 14));
		annotation.setPaint(Color.BLACK);
		annotation.setTextAnchor(TextAnchor.TOP_LEFT); // Anchor the text to its top-left corner
		plot.addAnnotation(annotation);

		return chart;
	}

	private static JFreeChart createHistogramChart(List<Double> data) {
		HistogramDataset dataset = new HistogramDataset();
		dataset.setType(HistogramType.FREQUENCY);

		double[] values = data.stream().mapToDouble(Double::doubleValue).toArray();

		double min = data.isEmpty() ? 0 : data.stream().min(Double::compare).orElse(0.0);
		double max = data.isEmpty() ? 100 : data.stream().max(Double::compare).orElse(100.0);
		int numberOfBins = 20;

		if (max - min < 1) {
			min = Math.max(0, min - 50);
			max = max + 50;
			numberOfBins = 5;
		}

		dataset.addSeries("Response Time Distribution", values, numberOfBins, min, max);

		JFreeChart chart = ChartFactory.createHistogram("Response Time Distribution", "Response Time (ms)", "Frequency", dataset,
				PlotOrientation.VERTICAL, true, true, false);
		chart.setBackgroundPaint(Color.WHITE);
		return chart;
	}

	private static void shutdownDerby() { // Made static as ServiceMonitorPlotter no longer JFrame
		boolean gotSQLExc = false;
		try {
			DriverManager.getConnection("jdbc:derby:;shutdown=true");
		} catch (SQLException se) {
			if (se.getSQLState().equals("XJ015")) {
				gotSQLExc = true;
			} else {
				System.err.println("Derby shutdown failed unexpectedly: " + se.getMessage());
				se.printStackTrace();
			}
		}
		if (gotSQLExc) {
			System.out.println("Derby database shut down cleanly.");
		} else {
			System.out.println("Derby database did not shut down cleanly.");
		}
	}

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> {
			// Data containers
			XYSeries scatterSeries = new XYSeries("Response Time (ms)");
			List<Double> responseTimes = new ArrayList<>();

			// Fetch data (calls the now static method)
			ServiceMonitorPlotter.fetchDataFromDerby(scatterSeries, responseTimes);

			// Add a single shutdown hook for Derby (since it's a shared resource)
			Runtime.getRuntime().addShutdownHook(new Thread(ServiceMonitorPlotter::shutdownDerby));

			// Create the chart folder if it doesn't exist
			File chartFolder = new File(CHART_FOLDER);
			if (!chartFolder.exists()) {
				if (chartFolder.mkdirs()) {
					System.out.println("Created chart directory: " + CHART_FOLDER);
				} else {
					System.err.println("Failed to create chart directory: " + CHART_FOLDER);
					return; // Exit if we can't create the directory
				}
			}

			// --- Create and save Line Chart ---
			// Define your equation for Y here. This is a placeholder.
			// For example, if Y = 2 * X + 100, then equationText = "Y = 2X + 100";
			// --- Create and save Line Chart ---
			// Define your equation for Y here.
			String equationText = "Response Time = EventCompleteTime - EventArrivalTime"; // Corrected equation
			JFreeChart lineChart = createLineChartWithEquation(new XYSeriesCollection(scatterSeries), equationText);
			File lineChartFile = new File(chartFolder, "ResponseTime_LineChart.png");
			try {
				ChartUtils.saveChartAsPNG(lineChartFile, lineChart, 800, 600);
				System.out.println("Line chart saved to: " + lineChartFile.getAbsolutePath());
			} catch (IOException e) {
				System.err.println("Error saving line chart: " + e.getMessage());
				e.printStackTrace();
			}
			// --- Create and save Histogram ---
			JFreeChart histogramChart = createHistogramChart(responseTimes);
			File histogramChartFile = new File(chartFolder, "ResponseTime_Histogram.png");
			try {
				ChartUtils.saveChartAsPNG(histogramChartFile, histogramChart, 800, 600);
				System.out.println("Histogram saved to: " + histogramChartFile.getAbsolutePath());
			} catch (IOException e) {
				System.err.println("Error saving histogram chart: " + e.getMessage());
				e.printStackTrace();
			}
		});
	}

	// The centerFrame method is no longer needed as we're saving to files
	// private static void centerFrame(JFrame frame, int offsetIndex) { /* ... */ }
}