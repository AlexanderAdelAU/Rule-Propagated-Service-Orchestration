package org.btsn.derby.Analysis;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYImageAnnotation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.RectangleAnchor;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

public class PerformanceMonitorPlotter {

	private static final String DRIVER = "org.apache.derby.jdbc.EmbeddedDriver";
	private static final String DB_NAME = "processMonitorDB";
	private static final String CONNECTION_URL = "jdbc:derby:" + DB_NAME + ";create=true";
	private static final String TABLE_NAME = "PROCESSMEASUREMENTS";
	private static final String CHART_OUTPUT_DIR_NAME = "charts";
	private static File CHART_OUTPUT_DIR;

	public static void main(String[] args) {
		CHART_OUTPUT_DIR = new File(System.getProperty("user.dir"), CHART_OUTPUT_DIR_NAME);
		if (!CHART_OUTPUT_DIR.exists()) {
			boolean created = CHART_OUTPUT_DIR.mkdirs();
			if (created) {
				System.out.println("Created chart output directory: " + CHART_OUTPUT_DIR.getAbsolutePath());
			} else {
				System.err.println("Failed to create chart output directory: " + CHART_OUTPUT_DIR.getAbsolutePath());
			}
		}

		Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownDerby()));

		SwingUtilities.invokeLater(() -> {
			Map<String, XYSeriesCollection> processDurationDatasets = new HashMap<>();
			Map<String, XYSeriesCollection> queueTimeDatasets = new HashMap<>();

			fetchDataFromDerby(processDurationDatasets, queueTimeDatasets);

			int chartCounter = 1;

			for (Map.Entry<String, XYSeriesCollection> entry : processDurationDatasets.entrySet()) {
				String serviceOperationKey = entry.getKey();
				String[] parts = serviceOperationKey.split("_");
				String serviceName = parts[0];
				String operationName = parts.length > 1 ? parts[1] : "";

				// 1. Plot Process Duration Chart
				XYSeriesCollection processDurationDataset = entry.getValue();
				if (processDurationDataset != null && processDurationDataset.getSeriesCount() > 0) {
					JFreeChart processDurationChart = createLineChart(processDurationDataset,
							serviceName + " - Process Duration vs. Relative Process Start Time (" + operationName + ")",
							"Relative Process Start Time (s)", "Process Duration (ms)",
							"$\\Delta = \\mathrm{PROCESSELAPSEDTIME}$");
					saveChart(processDurationChart, serviceName, operationName, "ProcessDuration", chartCounter++);
				} else {
					System.out.println("No process duration data for: " + serviceOperationKey);
				}

				// 2. Plot Queue Time Chart
				XYSeriesCollection queueDataset = queueTimeDatasets.get(serviceOperationKey);
				if (queueDataset != null && queueDataset.getSeriesCount() > 0) {
					JFreeChart queueTimeChart = createLineChart(queueDataset,
							serviceName + " - Queue Time vs. Relative Arrival Time (" + operationName + ")",
							"Relative Arrival Time (s)", "Queue Time (ms)",
							"$\\Delta = \\mathrm{PROCESSSTARTTIME} - \\mathrm{ARRIVALTIME}$");
					saveChart(queueTimeChart, serviceName, operationName, "QueueTime", chartCounter++);
				} else {
					System.out.println("No queue time data for: " + serviceOperationKey);
				}
			}
			System.out.println("\nCharts saved to: " + CHART_OUTPUT_DIR.getAbsolutePath());
		});
	}

	/**
	 * Fetches process measurement data from the Derby database and populates two distinct datasets: 
	 * one for process durations and one for queue times.
	 * 
	 * FIXED: Corrected understanding of database schema:
	 * - ARRIVALTIME: When event arrived at the system
	 * - PROCESSSTARTTIME: When processing actually started  
	 * - PROCESSELAPSEDTIME: Duration of processing (not end time!)
	 */
	private static void fetchDataFromDerby(Map<String, XYSeriesCollection> processDurationDatasets,
			Map<String, XYSeriesCollection> queueTimeDatasets) {
		Connection conn = null;
		Statement stmt = null;
		ResultSet rs = null;

		// Maps to store the first times for relative calculations
		Map<String, Long> firstProcessStartTimeMap = new HashMap<>();
		Map<String, Long> firstArrivalTimeMap = new HashMap<>();

		try {
			Class.forName(DRIVER);
			System.out.println("Derby JDBC driver loaded.");

			conn = DriverManager.getConnection(CONNECTION_URL);
			System.out.println("Connected to database: " + DB_NAME);

			stmt = conn.createStatement();

			if (!tableExists(conn, TABLE_NAME)) {
				System.out.println("Table " + TABLE_NAME + " does not exist. Creating and inserting sample data...");
				createAndInsertSampleData(stmt);
			}

			// FIXED: Updated SQL query and ordering
			String sql = "SELECT SERVICE, OPERATION, SEQUENCEID, ARRIVALTIME, PROCESSSTARTTIME, PROCESSELAPSEDTIME FROM " 
			           + TABLE_NAME + " ORDER BY SERVICE, OPERATION, ARRIVALTIME ASC";
			rs = stmt.executeQuery(sql);

			while (rs.next()) {
				String service = rs.getString("SERVICE");
				String operation = rs.getString("OPERATION");
				String sequenceIdStr = rs.getString("SEQUENCEID");
				long arrivalTime = rs.getLong("ARRIVALTIME");
				long processStartTime = rs.getLong("PROCESSSTARTTIME");
				long processElapsedTime = rs.getLong("PROCESSELAPSEDTIME"); // This is duration, not end time!

				String serviceOperationKey = service + "_" + operation;

				// Store the first times for relative time calculation
				firstProcessStartTimeMap.putIfAbsent(serviceOperationKey, processStartTime);
				firstArrivalTimeMap.putIfAbsent(serviceOperationKey, arrivalTime);

				long datumProcessStartTime = firstProcessStartTimeMap.get(serviceOperationKey);
				long datumArrivalTime = firstArrivalTimeMap.get(serviceOperationKey);

				// Calculate relative X-axis values (in seconds)
				double relativeProcessStartTimeSec = (double) (processStartTime - datumProcessStartTime) / 1000.0;
				double relativeArrivalTimeSec = (double) (arrivalTime - datumArrivalTime) / 1000.0;

				// FIXED: Correct calculations based on actual data structure
				// Process Duration: PROCESSELAPSEDTIME (this is already the duration!)
				long processDurationMs = processElapsedTime;

				// Queue Time: Time spent waiting before processing started
				long queueTimeMs = processStartTime - arrivalTime;

				// Skip invalid data points
				if (arrivalTime <= 0 || processStartTime <= 0 || processElapsedTime <= 0) {
					System.out.println("Skipping invalid data for sequence: " + sequenceIdStr);
					continue;
				}

				// Ensure datasets exist
				processDurationDatasets.computeIfAbsent(serviceOperationKey, k -> new XYSeriesCollection());
				queueTimeDatasets.computeIfAbsent(serviceOperationKey, k -> new XYSeriesCollection());

				// Add data to series
				XYSeries durationSeries = getOrCreateSeries(processDurationDatasets.get(serviceOperationKey), operation);
				durationSeries.add(relativeProcessStartTimeSec, processDurationMs);

				XYSeries queueSeries = getOrCreateSeries(queueTimeDatasets.get(serviceOperationKey), operation);
				queueSeries.add(relativeArrivalTimeSec, queueTimeMs);
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
			// Close resources
			try {
				if (rs != null) rs.close();
				if (stmt != null) stmt.close();
				if (conn != null) conn.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}

			// Debug output for verification
			System.out.println("\n--- Dataset Value Verification ---");
			for (Map.Entry<String, XYSeriesCollection> entry : processDurationDatasets.entrySet()) {
				String key = entry.getKey();
				XYSeriesCollection dataset = entry.getValue();
				for (int i = 0; i < dataset.getSeriesCount(); i++) {
					XYSeries series = dataset.getSeries(i);
					if (series.getItemCount() > 0) {
						double minX = series.getMinX();
						double maxX = series.getMaxX();
						double minY = series.getMinY();
						double maxY = series.getMaxY();
						System.out.println("Process Duration - " + key + ", Series: " + series.getKey());
						System.out.println("  X Range: [" + String.format("%.2f", minX) + ", " + String.format("%.2f", maxX) + "] sec");
						System.out.println("  Y Range: [" + String.format("%.2f", minY) + ", " + String.format("%.2f", maxY) + "] ms");
					}
				}
			}

			for (Map.Entry<String, XYSeriesCollection> entry : queueTimeDatasets.entrySet()) {
				String key = entry.getKey();
				XYSeriesCollection dataset = entry.getValue();
				for (int i = 0; i < dataset.getSeriesCount(); i++) {
					XYSeries series = dataset.getSeries(i);
					if (series.getItemCount() > 0) {
						double minX = series.getMinX();
						double maxX = series.getMaxX();
						double minY = series.getMinY();
						double maxY = series.getMaxY();
						System.out.println("Queue Time - " + key + ", Series: " + series.getKey());
						System.out.println("  X Range: [" + String.format("%.2f", minX) + ", " + String.format("%.2f", maxX) + "] sec");
						System.out.println("  Y Range: [" + String.format("%.2f", minY) + ", " + String.format("%.2f", maxY) + "] ms");
					}
				}
			}
			System.out.println("----------------------------------\n");
		}
	}

	private static XYSeries getOrCreateSeries(XYSeriesCollection dataset, String seriesName) {
		int seriesIndex = dataset.indexOf(seriesName);
		if (seriesIndex >= 0) {
			return dataset.getSeries(seriesIndex);
		} else {
			XYSeries newSeries = new XYSeries(seriesName);
			dataset.addSeries(newSeries);
			return newSeries;
		}
	}

	private static boolean tableExists(Connection conn, String tableName) throws SQLException {
		DatabaseMetaData dbm = conn.getMetaData();
		try (ResultSet tables = dbm.getTables(null, null, tableName.toUpperCase(), null)) {
			return tables.next();
		}
	}

	/**
	 * FIXED: Creates sample data with correct understanding of the schema:
	 * - ARRIVALTIME: When event arrived
	 * - PROCESSSTARTTIME: When processing started (after ARRIVALTIME)
	 * - PROCESSELAPSEDTIME: Duration of processing (not end time)
	 */
	private static void createAndInsertSampleData(Statement stmt) throws SQLException {
		String createTableSQL = "CREATE TABLE " + TABLE_NAME + " ("
				+ "ID INT NOT NULL GENERATED ALWAYS AS IDENTITY, "
				+ "SERVICE VARCHAR(255) NOT NULL, "
				+ "OPERATION VARCHAR(255) NOT NULL, "
				+ "SEQUENCEID VARCHAR(255), "
				+ "ARRIVALTIME BIGINT, "
				+ "PROCESSSTARTTIME BIGINT, "
				+ "PROCESSELAPSEDTIME BIGINT, "
				+ "NOTAFTER BIGINT, "
				+ "PRIMARY KEY (ID)"
				+ ")";
		stmt.execute(createTableSQL);
		System.out.println("Table " + TABLE_NAME + " created.");

		PreparedStatement pstmt = null;
		Random random = new Random();
		long baseTime = System.currentTimeMillis() - 3600_000; // 1 hour ago

		try {
			String insertSQL = "INSERT INTO " + TABLE_NAME
					+ " (SERVICE, OPERATION, SEQUENCEID, ARRIVALTIME, PROCESSSTARTTIME, PROCESSELAPSEDTIME, NOTAFTER) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?)";
			pstmt = stmt.getConnection().prepareStatement(insertSQL);

			String[] services = { "TriageService", "PaymentService" };
			String[] operations = { "processTriageAssessment", "completeOrder", "validateTransaction" };

			for (String svc : services) {
				for (String op : operations) {
					long currentServiceBaseTime = baseTime + random.nextInt(1800_000); // Spread over 30 minutes

					for (int i = 0; i < 100; i++) {
						// 1. Event arrives at system
						long arrivalTime = currentServiceBaseTime + (i * 500) + random.nextInt(200);

						// 2. Processing starts after some queue time (0-2000ms)
						long queueTime = random.nextInt(2000);
						long processStartTime = arrivalTime + queueTime;

						// 3. Processing takes some time (50-500ms)
						long processDuration = random.nextInt(450) + 50;

						// 4. NotAfter time for timeout purposes
						long notAfterTime = arrivalTime + 60000; // 1 minute timeout

						pstmt.setString(1, svc);
						pstmt.setString(2, op);
						pstmt.setString(3, String.valueOf((i + 1) * 1000));
						pstmt.setLong(4, arrivalTime);
						pstmt.setLong(5, processStartTime);
						pstmt.setLong(6, processDuration); // This is duration, not end time
						pstmt.setLong(7, notAfterTime);
						pstmt.addBatch();
					}
				}
			}
			pstmt.executeBatch();
			System.out.println("Sample data inserted into " + TABLE_NAME);
		} finally {
			if (pstmt != null)
				pstmt.close();
		}
	}

	private static JFreeChart createLineChart(XYSeriesCollection dataset, String title, String xAxisLabel, 
			String yAxisLabel, String equationLaTeX) {
		JFreeChart chart = ChartFactory.createXYLineChart(title, xAxisLabel, yAxisLabel, dataset, 
				PlotOrientation.VERTICAL, true, true, false);
		chart.setBackgroundPaint(Color.WHITE);

		XYPlot plot = chart.getXYPlot();
		plot.setBackgroundPaint(Color.WHITE);
		plot.setDomainGridlinePaint(Color.lightGray);
		plot.setRangeGridlinePaint(Color.lightGray);

		XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot.getRenderer();
		for (int i = 0; i < dataset.getSeriesCount(); i++) {
			renderer.setSeriesShapesVisible(i, true);
			renderer.setSeriesShape(i, new java.awt.geom.Ellipse2D.Double(-4, -4, 8, 8));
			renderer.setSeriesLinesVisible(i, true);
		}

		NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
		rangeAxis.setAutoRangeIncludesZero(true);
		rangeAxis.setUpperMargin(0.20);

		NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();
		domainAxis.setAutoRangeIncludesZero(true);

		chart.setPadding(new RectangleInsets(10, 10, 10, 10));

		// LaTeX annotation
		if (equationLaTeX != null && !equationLaTeX.isEmpty() && dataset.getSeriesCount() > 0) {
			try {
				TeXFormula formula = new TeXFormula(equationLaTeX);
				TeXIcon teXIcon = formula.createTeXIcon(TeXConstants.STYLE_DISPLAY, 20);
				teXIcon.setInsets(new Insets(5, 5, 5, 5));

				BufferedImage bufferedImage = new BufferedImage(teXIcon.getIconWidth(), teXIcon.getIconHeight(),
						BufferedImage.TYPE_INT_ARGB);
				Graphics2D g2 = bufferedImage.createGraphics();

				g2.setColor(Color.WHITE);
				g2.fillRect(0, 0, teXIcon.getIconWidth(), teXIcon.getIconHeight());

				teXIcon.paintIcon(null, g2, 0, 0);
				g2.dispose();

				double xRangeLower = plot.getDomainAxis().getLowerBound();
				double xRangeUpper = plot.getDomainAxis().getUpperBound();
				double yRangeUpperForAnnotation = plot.getRangeAxis().getUpperBound();

				double xCoord = xRangeLower + (xRangeUpper - xRangeLower) * 0.02;
				double yCoord = yRangeUpperForAnnotation * 0.95;

				System.out.println("Placing annotation at (X=" + xCoord + ", Y=" + yCoord + ") for equation: " + equationLaTeX);

				XYImageAnnotation annotation = new XYImageAnnotation(xCoord, yCoord, bufferedImage, RectangleAnchor.TOP_LEFT);
				plot.addAnnotation(annotation);

			} catch (Exception e) {
				System.err.println("Error rendering LaTeX equation: " + equationLaTeX + " - " + e.getMessage());
				e.printStackTrace();
			}
		}

		return chart;
	}

	private static void saveChart(JFreeChart chart, String serviceName, String operationName, String plotType, int counter) {
		String sanitizedService = serviceName.replaceAll("[^a-zA-Z0-9.-]", "_");
		String sanitizedOperation = operationName.replaceAll("[^a-zA-Z0-9.-]", "_");
		String filename = String.format("%s_%s_%s_%d.png", sanitizedService, sanitizedOperation, plotType, counter);
		File outputFile = new File(CHART_OUTPUT_DIR, filename);

		try {
			ChartUtils.saveChartAsPNG(outputFile, chart, 1024, 768);
			System.out.println("Saved chart to " + outputFile.getAbsolutePath());
		} catch (IOException e) {
			System.err.println("Error saving chart " + filename + ": " + e.getMessage());
			e.printStackTrace();
		}
	}

	private static void deleteDirectory(File directory) {
		if (directory.exists()) {
			File[] allContents = directory.listFiles();
			if (allContents != null) {
				for (File file : allContents) {
					deleteDirectory(file);
				}
			}
			directory.delete();
		}
	}

	private static void displayChart(JFreeChart chart, String frameTitle, int offsetIndex) {
		ChartPanel chartPanel = new ChartPanel(chart);
		chartPanel.setPreferredSize(new Dimension(800, 600));

		JFrame frame = new JFrame(frameTitle);
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.getContentPane().add(chartPanel, BorderLayout.CENTER);
		frame.pack();
		centerFrame(frame, offsetIndex);
		frame.setVisible(true);
	}

	private static void shutdownDerby() {
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

	private static void centerFrame(JFrame frame, int offsetIndex) {
		Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
		int x = (dim.width - frame.getSize().width) / 2;
		int y = (dim.height - frame.getSize().height) / 2;

		int offsetX = (offsetIndex % 4) * 50;
		int offsetY = (offsetIndex / 4) * 50;

		frame.setLocation(x + offsetX, y + offsetY);
	}
}