package org.btsn.derby.Analysis;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.derby.jdbc.EmbeddedDriver;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.btsn.constants.VersionConstants;
import org.json.simple.JSONObject;

/**
 * BuildServiceAnalysisDatabase - REFACTORED & ENHANCED
 * 
 * Comprehensive database management for Petri Net service analysis.
 * 
 * TABLE CATEGORIES:
 * ================
 * 
 * 1. CORE MEASUREMENT TABLES (4):
 *    - SERVICEMEASUREMENTS    : Real-time service timing (captured by ServiceThread)
 *    - PROCESSMEASUREMENTS    : Token arrival measurements (captured by MonitorService)
 *    - SERVICECONTRIBUTION    : Workflow contribution analysis (captured by MonitorService)
 *    - MARKINGS               : Petri Net marking data (captured by MonitorService)
 * 
 * 2. LOCAL PETRI NET TABLES (3):
 *    - TRANSITION_FIRINGS     : Transition firing events (local to each service)
 *    - TOKEN_GENEALOGY        : Token parent-child relationships (local to each service)
 *    - JOIN_SYNCHRONIZATION   : Join synchronization tracking (local to each service)
 * 
 * 3. CONSOLIDATED PETRI NET TABLES (5):
 *    - CONSOLIDATED_TRANSITION_FIRINGS  : Aggregated transition data from all services
 *    - CONSOLIDATED_TOKEN_PATHS         : Aggregated token path data from all services
 *    - CONSOLIDATED_MARKING_EVOLUTION   : Aggregated marking snapshots from all services
 *    - CONSOLIDATED_PLACE_STATISTICS    : Aggregated place statistics from all services
 *    - CONSOLIDATED_TOKEN_GENEALOGY     : Aggregated token genealogy from all services
 * 
 * 4. EVENT TRACKING TABLE (1):
 *    - EVENTRESPONSETABLE              : Event arrival and completion tracking
 * 
 * TOTAL: 13 TABLES
 * 
 * @version 2.0
 * @author BTSN Team
 */
public class BuildServiceAnalysisDatabase {

	private static final Logger logger = Logger.getLogger(BuildServiceAnalysisDatabase.class);

	// =========================================================================
	// DATABASE CONFIGURATION
	// =========================================================================
	
	private static final String PROTOCOL = "jdbc:derby:";
	private static final String DB_NAME = "ServiceAnalysisDataBase";
	private static final String DB_URL = PROTOCOL + DB_NAME + ";create=true";

	// =========================================================================
	// TABLE NAME CONSTANTS - ORGANIZED BY CATEGORY
	// =========================================================================
	
	// Core Measurement Tables
	private static final String SERVICE_MEASUREMENTS_TABLE = "SERVICEMEASUREMENTS";
	private static final String PROCESS_MEASUREMENTS_TABLE = "PROCESSMEASUREMENTS";
	private static final String SERVICE_CONTRIBUTION_TABLE = "SERVICECONTRIBUTION";
	private static final String MARKINGS_TABLE = "MARKINGS";

	// Local Petri Net Analysis Tables
	private static final String TRANSITION_FIRINGS_TABLE = "TRANSITION_FIRINGS";
	private static final String TOKEN_GENEALOGY_TABLE = "TOKEN_GENEALOGY";
	private static final String JOIN_SYNCHRONIZATION_TABLE = "JOIN_SYNCHRONIZATION";

	// Consolidated Petri Net Tables (Monitor-side)
	private static final String CONSOLIDATED_TRANSITION_FIRINGS_TABLE = "CONSOLIDATED_TRANSITION_FIRINGS";
	private static final String CONSOLIDATED_TOKEN_PATHS_TABLE = "CONSOLIDATED_TOKEN_PATHS";
	private static final String CONSOLIDATED_MARKING_EVOLUTION_TABLE = "CONSOLIDATED_MARKING_EVOLUTION";
	private static final String CONSOLIDATED_PLACE_STATISTICS_TABLE = "CONSOLIDATED_PLACE_STATISTICS";
	private static final String CONSOLIDATED_TOKEN_GENEALOGY_TABLE = "CONSOLIDATED_TOKEN_GENEALOGY";
	private static final String EVENT_RESPONSE_TABLE = "EVENTRESPONSETABLE";

	// =========================================================================
	// TABLE GROUPS - FOR BULK OPERATIONS
	// =========================================================================
	
	/**
	 * All tables in initialization order
	 */
	private static final String[] ALL_TABLES = {
		// Core tables
		SERVICE_MEASUREMENTS_TABLE,
		PROCESS_MEASUREMENTS_TABLE,
		SERVICE_CONTRIBUTION_TABLE,
		MARKINGS_TABLE,
		// Consolidated Petri Net tables
		CONSOLIDATED_TRANSITION_FIRINGS_TABLE,
		CONSOLIDATED_TOKEN_PATHS_TABLE,
		CONSOLIDATED_MARKING_EVOLUTION_TABLE,
		CONSOLIDATED_PLACE_STATISTICS_TABLE,
		CONSOLIDATED_TOKEN_GENEALOGY_TABLE,
		// Local Petri Net tables
		TRANSITION_FIRINGS_TABLE,
		TOKEN_GENEALOGY_TABLE,
		JOIN_SYNCHRONIZATION_TABLE,
		// Event tracking table
		EVENT_RESPONSE_TABLE
	};

	// Synchronization lock for concurrent access
	private static final Object DB_INIT_LOCK = new Object();

	// =========================================================================
	// CONSTRUCTOR & INITIALIZATION
	// =========================================================================
	
	/**
	 * Constructor - registers Derby driver
	 */
	public BuildServiceAnalysisDatabase() {
		try {
			DriverManager.registerDriver(new EmbeddedDriver());
			logger.info("Derby Embedded Driver registered successfully.");
		} catch (SQLException e) {
			logger.fatal("Failed to register Derby driver: " + e.getMessage(), e);
			throw new RuntimeException("Failed to register Derby driver", e);
		}
	}

	// =========================================================================
	// INITIALIZATION ENTRY POINTS (for Ant/EventGenerator calls)
	// These replace BaseInitializationService - called by thin wrapper classes
	// e.g., P1_InitializationService, Triage_InitializationService, etc.
	// =========================================================================

	/**
	 * Initialize the local database - entry point for service initialization
	 * @param serviceName The name of the calling service (e.g., "P1", "P2", "Monitor", "Triage")
	 * @param token Security token (currently unused but kept for interface consistency)
	 * @return JSON response string
	 */
	@SuppressWarnings("unchecked")
	public String initializeLocalDatabase(String serviceName, String token) {
		try {
			System.out.println("\n=== " + serviceName.toUpperCase() + " DATABASE INITIALIZATION ===");
			
			long startTime = System.currentTimeMillis();
			initializeDatabase();
			long duration = System.currentTimeMillis() - startTime;
			
			TreeMap<String, Integer> stats = getMeasurementStatistics();
			
			JSONObject response = new JSONObject();
			response.put("service", serviceName + "_InitializationService");
			response.put("status", "success");
			response.put("action", "initialize");
			response.put("duration_ms", duration);
			response.put("statistics", new JSONObject(stats));
			
			System.out.println("[SUCCESS] " + serviceName + " database initialized (" + duration + "ms)");
			return response.toJSONString();
			
		} catch (SQLException e) {
			return buildInitErrorResponse(serviceName, "initialize", e);
		}
	}

	/**
	 * Purge ALL tables in database (delete data) and reinitialize
	 * @param serviceName The name of the calling service
	 * @param token Security token
	 * @return JSON response string
	 */
	@SuppressWarnings("unchecked")
	public String purgeAndInitialize(String serviceName, String token) {
		try {
			System.out.println("\n=== " + serviceName.toUpperCase() + " DATABASE PURGE & INITIALIZE ===");
			System.out.println("Using metadata discovery to purge ALL tables...");
			
			long startTime = System.currentTimeMillis();
			int tablesPurged = purgeAllTables();
			initializeDatabase();
			long duration = System.currentTimeMillis() - startTime;
			
			TreeMap<String, Integer> stats = getMeasurementStatistics();
			
			JSONObject response = new JSONObject();
			response.put("service", serviceName + "_InitializationService");
			response.put("status", "success");
			response.put("action", "purge_and_initialize");
			response.put("duration_ms", duration);
			response.put("tables_purged", tablesPurged);
			response.put("statistics", new JSONObject(stats));
			
			System.out.println("[SUCCESS] " + serviceName + " database purged (" + tablesPurged + " tables) and initialized (" + duration + "ms)");
			return response.toJSONString();
			
		} catch (SQLException e) {
			return buildInitErrorResponse(serviceName, "purge_and_initialize", e);
		}
	}

	/**
	 * Purge ALL tables found in the database (drops tables completely)
	 * @param serviceName The name of the calling service
	 * @param token Security token
	 * @return JSON response string
	 */
	@SuppressWarnings("unchecked")
	public String purgeAllDatabaseTables(String serviceName, String token) {
		try {
			System.out.println("\n=== " + serviceName.toUpperCase() + " DATABASE - PURGE ALL TABLES ===");
			System.out.println("WARNING: This will drop ALL tables in the database!");
			
			long startTime = System.currentTimeMillis();
			int tablesDropped = dropAllTables();
			long duration = System.currentTimeMillis() - startTime;
			
			JSONObject response = new JSONObject();
			response.put("service", serviceName + "_InitializationService");
			response.put("status", "success");
			response.put("action", "purge_all_database_tables");
			response.put("duration_ms", duration);
			response.put("tables_dropped", tablesDropped);
			
			System.out.println("[SUCCESS] All database tables purged (" + tablesDropped + " tables, " + duration + "ms)");
			return response.toJSONString();
			
		} catch (SQLException e) {
			return buildInitErrorResponse(serviceName, "purge_all_tables", e);
		}
	}

	/**
	 * Purge all database tables (drop) and reinitialize
	 * @param serviceName The name of the calling service
	 * @param token Security token
	 * @return JSON response string
	 */
	@SuppressWarnings("unchecked")
	public String purgeAllAndInitialize(String serviceName, String token) {
		try {
			System.out.println("\n=== " + serviceName.toUpperCase() + " DATABASE - PURGE ALL & INITIALIZE ===");
			System.out.println("WARNING: This will drop ALL tables and reinitialize!");
			
			long startTime = System.currentTimeMillis();
			int tablesDropped = dropAllTables();
			initializeDatabase();
			long duration = System.currentTimeMillis() - startTime;
			
			TreeMap<String, Integer> stats = getMeasurementStatistics();
			
			JSONObject response = new JSONObject();
			response.put("service", serviceName + "_InitializationService");
			response.put("status", "success");
			response.put("action", "purge_all_and_initialize");
			response.put("duration_ms", duration);
			response.put("tables_dropped", tablesDropped);
			response.put("statistics", new JSONObject(stats));
			
			System.out.println("[SUCCESS] All database tables purged and reinitialized (" + tablesDropped + " tables, " + duration + "ms)");
			return response.toJSONString();
			
		} catch (SQLException e) {
			return buildInitErrorResponse(serviceName, "purge_all_and_initialize", e);
		}
	}

	/**
	 * Get initialization status
	 * @param serviceName The name of the calling service
	 * @param token Security token
	 * @return JSON response string
	 */
	@SuppressWarnings("unchecked")
	public String getInitializationStatus(String serviceName, String token) {
		try {
			TreeMap<String, Integer> stats = getMeasurementStatistics();
			
			JSONObject response = new JSONObject();
			response.put("service", serviceName + "_InitializationService");
			response.put("status", "success");
			response.put("initialized", true);
			response.put("statistics", new JSONObject(stats));
			
			return response.toJSONString();
			
		} catch (SQLException e) {
			JSONObject response = new JSONObject();
			response.put("service", serviceName + "_InitializationService");
			response.put("status", "success");
			response.put("initialized", false);
			return response.toJSONString();
		}
	}

	/**
	 * Build error response JSON for initialization methods
	 * @param serviceName The service name
	 * @param action The action that failed
	 * @param e The exception that occurred
	 * @return JSON error response string
	 */
	@SuppressWarnings("unchecked")
	private String buildInitErrorResponse(String serviceName, String action, Exception e) {
		System.err.println("ERROR: " + serviceName + " " + action + " failed: " + e.getMessage());
		
		JSONObject response = new JSONObject();
		response.put("service", serviceName + "_InitializationService");
		response.put("status", "error");
		response.put("action", action);
		response.put("errorMessage", e.getMessage());
		
		return response.toJSONString();
	}

	// =========================================================================
	// END OF INITIALIZATION ENTRY POINTS
	// =========================================================================

	/**
	 * Main method for standalone testing
	 */
	public static void main(String[] args) {
		PropertyConfigurator.configure("resources/log4j.properties");
		BuildServiceAnalysisDatabase dbManager = new BuildServiceAnalysisDatabase();
		try {
			dbManager.purgeAllTables();
			dbManager.initializeDatabase();
			logger.info("Database initialized successfully");
			
			// Print summary
			System.out.println(dbManager.getMeasurementSummary());
			
		} catch (SQLException e) {
			logger.fatal("Database initialization failed:", e);
			printSQLException(e);
		} finally {
			shutdownDerby();
		}
	}

	// =========================================================================
	// DATABASE INITIALIZATION
	// =========================================================================
	
	/**
	 * Initialize database with all 11 tables
	 * SYNCHRONIZED to prevent concurrent initialization issues
	 */
	public void initializeDatabase() throws SQLException {
		synchronized (DB_INIT_LOCK) {
			Connection connection = null;
			Statement statement = null;
			try {
				connection = DriverManager.getConnection(DB_URL);
				statement = connection.createStatement();
				logger.info("Connected to Derby database: " + DB_NAME);

				// Create core tables
				createCoreTables(statement);
				
				// Create consolidated Petri Net tables
				createConsolidatedPetriNetTables(statement);
				
				// Create local Petri Net tables
				createLocalPetriNetTables(statement);
				
				logger.info("Database initialization complete - 13 tables (4 core + 5 consolidated PN + 3 local PN + 1 event) created/verified");

			} finally {
				closeStatement(statement);
				closeConnection(connection);
			}
		}
	}

	/**
	 * Create core measurement tables (4 tables)
	 */
	private void createCoreTables(Statement statement) throws SQLException {
		logger.info("Creating core measurement tables...");
		
		// 1. SERVICEMEASUREMENTS - Real-time service timing
		String createServiceMeasurementsSQL = "CREATE TABLE " + SERVICE_MEASUREMENTS_TABLE + " ("
				+ "id INT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1) PRIMARY KEY, "
				+ "sequenceID BIGINT, "
				+ "serviceName VARCHAR(255), "
				+ "operation VARCHAR(255), "
				+ "arrivalTime BIGINT, "      // When event arrived at service
				+ "invocationTime BIGINT, "   // When service started processing
				+ "publishTime BIGINT, "      // When service published result
				+ "workflowStartTime BIGINT, " // Original workflow start time
				+ "bufferSize INT, "          // Petri Net marking: tokens in buffer
				+ "maxQueueCapacity INT, "    // Petri Net: configured max queue size
				+ "totalMarking INT"          // Petri Net: buffer + place (total marking)
				+ ")";
		manageTable(statement, SERVICE_MEASUREMENTS_TABLE, createServiceMeasurementsSQL);

		// 2. PROCESSMEASUREMENTS - Token arrival tracking
		String createProcessMeasurementsSQL = "CREATE TABLE " + PROCESS_MEASUREMENTS_TABLE + " ("
				+ "id INT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1) PRIMARY KEY, "
				+ "sequenceID BIGINT, "
				+ "serviceName VARCHAR(255), "
				+ "operation VARCHAR(255), "
				+ "tokenArrivalTime BIGINT, "  // When token arrived at monitor
				+ "workflowStartTime BIGINT, " // Original workflow start
				+ "elapsedTime BIGINT"         // Total elapsed time
				+ ")";
		manageTable(statement, PROCESS_MEASUREMENTS_TABLE, createProcessMeasurementsSQL);

		// 3. SERVICECONTRIBUTION - Post-workflow analysis
		String createServiceContributionSQL = "CREATE TABLE " + SERVICE_CONTRIBUTION_TABLE + " ("
			+ "id INT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1) PRIMARY KEY, "
			+ "workflowBase BIGINT, "
			+ "sequenceID BIGINT, "
			+ "serviceName VARCHAR(255), "
			+ "operation VARCHAR(255), "
			+ "arrivalTime BIGINT, "
			+ "queueTime BIGINT, "
			+ "serviceTime BIGINT, "
			+ "totalTime BIGINT, "
			+ "contributionPercent DOUBLE, "
			+ "workflowStartTime BIGINT, "
			+ "analysisTime BIGINT, "
			+ "bufferSize INT, "
			+ "maxQueueCapacity INT, "
			+ "totalMarking INT"
			+ ")";
		manageTable(statement, SERVICE_CONTRIBUTION_TABLE, createServiceContributionSQL);

		// 4. MARKINGS - Petri Net marking data for analysis
		String createMarkingsSQL = "CREATE TABLE " + MARKINGS_TABLE + " ("
			+ "id INT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1) PRIMARY KEY, "
			+ "workflowBase BIGINT, "
			+ "sequenceID BIGINT, "
			+ "serviceName VARCHAR(255), "
			+ "operation VARCHAR(255), "
			+ "arrivalTime BIGINT, "       // When token arrived at service
			+ "invocationTime BIGINT, "    // When service started processing
			+ "publishTime BIGINT, "       // When service published result
			+ "workflowStartTime BIGINT, " // Original workflow start time
			+ "bufferSize INT, "           // Tokens in buffer at dequeue
			+ "maxQueueCapacity INT, "     // Configured max queue capacity
			+ "totalMarking INT, "         // Total marking (buffer + place)
			+ "analysisTime BIGINT"        // When this record was created
			+ ")";
		manageTable(statement, MARKINGS_TABLE, createMarkingsSQL);
		
		logger.info("Core measurement tables created/verified");
	}

	/**
	 * Create consolidated Petri Net tables (4 tables) - NEW
	 * These tables aggregate data from all distributed services at the Monitor
	 */
	private void createConsolidatedPetriNetTables(Statement statement) throws SQLException {
		logger.info("Creating consolidated Petri Net tables...");
		
		// 5. CONSOLIDATED_TRANSITION_FIRINGS - Aggregated transition data from all services
		String createConsolidatedTransitionFiringsSQL = "CREATE TABLE " + CONSOLIDATED_TRANSITION_FIRINGS_TABLE + " ("
			+ "id INT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1) PRIMARY KEY, "
			+ "workflowBase BIGINT, "
			+ "tokenId BIGINT, "
			+ "transitionId VARCHAR(100), "
			+ "timestamp BIGINT, "
			+ "toPlace VARCHAR(100), "
			+ "fromPlace VARCHAR(100), "
			+ "bufferSize INT, "
			+ "placeName VARCHAR(100), "
			+ "reportingService VARCHAR(100), "
			+ "reportingChannel VARCHAR(50), "
			+ "analysisTime BIGINT"
			+ ")";
		manageTable(statement, CONSOLIDATED_TRANSITION_FIRINGS_TABLE, createConsolidatedTransitionFiringsSQL);

		// 6. CONSOLIDATED_TOKEN_PATHS - Aggregated token path data from all services
		String createConsolidatedTokenPathsSQL = "CREATE TABLE " + CONSOLIDATED_TOKEN_PATHS_TABLE + " ("
			+ "id INT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1) PRIMARY KEY, "
			+ "workflowBase BIGINT, "
			+ "tokenId BIGINT, "
			+ "placeName VARCHAR(100), "
			+ "entryTime BIGINT, "
			+ "exitTime BIGINT, "
			+ "residenceTime BIGINT, "
			+ "entryBufferSize INT, "
			+ "exitBufferSize INT, "
			+ "reportingService VARCHAR(100), "
			+ "reportingChannel VARCHAR(50), "
			+ "analysisTime BIGINT"
			+ ")";
		manageTable(statement, CONSOLIDATED_TOKEN_PATHS_TABLE, createConsolidatedTokenPathsSQL);

		// 7. CONSOLIDATED_MARKING_EVOLUTION - Aggregated marking snapshots from all services
		String createConsolidatedMarkingEvolutionSQL = "CREATE TABLE " + CONSOLIDATED_MARKING_EVOLUTION_TABLE + " ("
			+ "id INT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1) PRIMARY KEY, "
			+ "workflowBase BIGINT, "
			+ "tokenId BIGINT, "
			+ "timestamp BIGINT, "
			+ "marking INT, "
			+ "bufferSize INT, "
			+ "placeName VARCHAR(100), "
			+ "reportingService VARCHAR(100), "
			+ "reportingChannel VARCHAR(50), "
			+ "analysisTime BIGINT"
			+ ")";
		manageTable(statement, CONSOLIDATED_MARKING_EVOLUTION_TABLE, createConsolidatedMarkingEvolutionSQL);

		// 8. CONSOLIDATED_PLACE_STATISTICS - Aggregated place statistics from all services
		String createConsolidatedPlaceStatisticsSQL = "CREATE TABLE " + CONSOLIDATED_PLACE_STATISTICS_TABLE + " ("
			+ "id INT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1) PRIMARY KEY, "
			+ "workflowBase BIGINT, "
			+ "placeName VARCHAR(100), "
			+ "tokenCount INT, "
			+ "avgResidenceTime DOUBLE, "
			+ "minResidenceTime BIGINT, "
			+ "maxResidenceTime BIGINT, "
			+ "throughput DOUBLE, "
			+ "avgBufferSize DOUBLE, "
			+ "maxBufferSize INT, "
			+ "reportingService VARCHAR(100), "
			+ "reportingChannel VARCHAR(50), "
			+ "analysisTime BIGINT"
			+ ")";
		manageTable(statement, CONSOLIDATED_PLACE_STATISTICS_TABLE, createConsolidatedPlaceStatisticsSQL);
		
		// 9. CONSOLIDATED_TOKEN_GENEALOGY - Aggregated token genealogy from all services
		String createConsolidatedTokenGenealogySQL = "CREATE TABLE " + CONSOLIDATED_TOKEN_GENEALOGY_TABLE + " ("
			+ "id INT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1) PRIMARY KEY, "
			+ "parentTokenId BIGINT, "
			+ "childTokenId BIGINT, "
			+ "forkTransitionId VARCHAR(100), "
			+ "forkTimestamp BIGINT, "
			+ "workflowBase BIGINT, "
			+ "reportingService VARCHAR(100), "
			+ "reportingChannel VARCHAR(50), "
			+ "analysisTime BIGINT"
			+ ")";
		manageTable(statement, CONSOLIDATED_TOKEN_GENEALOGY_TABLE, createConsolidatedTokenGenealogySQL);
		
		logger.info("Consolidated Petri Net tables created/verified");
	}

	/**
	 * Create local Petri Net analysis tables (3 tables)
	 * These tables track Petri Net events locally at each service
	 */
	private void createLocalPetriNetTables(Statement statement) throws SQLException {
		logger.info("Creating local Petri Net analysis tables...");
		
		// 9. TRANSITION_FIRINGS table
		String createTransitionFiringsSQL = "CREATE TABLE " + TRANSITION_FIRINGS_TABLE + " ("
				+ "eventId BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1) PRIMARY KEY, "
				+ "timestamp BIGINT NOT NULL, "
				+ "transitionId VARCHAR(100) NOT NULL, "
				+ "transitionType VARCHAR(50) NOT NULL, "
				+ "tokenId BIGINT NOT NULL, "
				+ "workflowBase BIGINT NOT NULL, "
				+ "fromPlace VARCHAR(100), "
				+ "toPlace VARCHAR(100), "
				+ "forkDecision VARCHAR(200), "
				+ "joinState VARCHAR(50), "
				+ "bufferSize INT, "
				+ "ruleVersion VARCHAR(20)"
				+ ")";
		manageTable(statement, TRANSITION_FIRINGS_TABLE, createTransitionFiringsSQL);
		
		// 10. TOKEN_GENEALOGY table
		String createTokenGenealogySQL = "CREATE TABLE " + TOKEN_GENEALOGY_TABLE + " ("
				+ "parentTokenId BIGINT NOT NULL, "
				+ "childTokenId BIGINT NOT NULL PRIMARY KEY, "
				+ "forkTransitionId VARCHAR(100) NOT NULL, "
				+ "forkTimestamp BIGINT NOT NULL, "
				+ "workflowBase BIGINT NOT NULL"
				+ ")";
		manageTable(statement, TOKEN_GENEALOGY_TABLE, createTokenGenealogySQL);
		
		// 11. JOIN_SYNCHRONIZATION table
		String createJoinSynchronizationSQL = "CREATE TABLE " + JOIN_SYNCHRONIZATION_TABLE + " ("
				+ "joinTransitionId VARCHAR(100) NOT NULL, "
				+ "workflowBase BIGINT NOT NULL, "
				+ "tokenId BIGINT NOT NULL, "
				+ "arrivalTimestamp BIGINT NOT NULL, "
				+ "requiredCount INT NOT NULL, "
				+ "currentCount INT NOT NULL, "
				+ "status VARCHAR(20) NOT NULL, "
				+ "continuationTokenId BIGINT, "
				+ "PRIMARY KEY (joinTransitionId, workflowBase, tokenId)"
				+ ")";
		manageTable(statement, JOIN_SYNCHRONIZATION_TABLE, createJoinSynchronizationSQL);
		
		logger.info("Local Petri Net tables created/verified");
	}

	// =========================================================================
	// WRITE METHODS - CORE TABLES
	// =========================================================================
	
	/**
	 * Write service timing record to SERVICEMEASUREMENTS
	 */
	public void writeServiceTimingRecord(Map<String, String> serviceDataMap) throws SQLException {
		if (serviceDataMap == null || serviceDataMap.isEmpty()) {
			logger.warn("Received null or empty service data map");
			return;
		}

		String sql = "INSERT INTO " + SERVICE_MEASUREMENTS_TABLE
				+ " (sequenceID, serviceName, operation, arrivalTime, invocationTime, publishTime, " 
				+ "workflowStartTime, bufferSize, maxQueueCapacity, totalMarking) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

		try (Connection conn = getConnection(); 
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {

			pstmt.setLong(1, parseLongValue(serviceDataMap.get("sequenceID"), 0L));
			pstmt.setString(2, serviceDataMap.get("serviceName"));
			pstmt.setString(3, serviceDataMap.get("operation"));
			pstmt.setLong(4, parseLongValue(serviceDataMap.get("arrivalTime"), 0L));
			pstmt.setLong(5, parseLongValue(serviceDataMap.get("invocationTime"), 0L));
			pstmt.setLong(6, parseLongValue(serviceDataMap.get("publishTime"), 0L));
			pstmt.setLong(7, parseLongValue(serviceDataMap.get("workflowStartTime"), 0L));
			pstmt.setInt(8, parseIntValue(serviceDataMap.get("bufferSize"), 0));
			pstmt.setInt(9, parseIntValue(serviceDataMap.get("maxQueueCapacity"), 0));
			pstmt.setInt(10, parseIntValue(serviceDataMap.get("totalMarking"), 0));

			pstmt.executeUpdate();
			
			logger.info("Wrote service timing record for sequenceID: " + serviceDataMap.get("sequenceID"));

		} catch (SQLException e) {
			logger.error("Failed to write service timing record: " + e.getMessage(), e);
			throw e;
		}
	}

	/**
	 * Write marking record to MARKINGS table - Direct parameter version
	 */
	public void writeMarkingRecord(int workflowBase, int sequenceID, String serviceName, 
									String operation, long arrivalTime, long invocationTime, 
									long publishTime, long workflowStartTime, int bufferSize, 
									int maxQueueCapacity, int totalMarking) throws SQLException {
		
		String sql = "INSERT INTO " + MARKINGS_TABLE
				+ " (workflowBase, sequenceID, serviceName, operation, arrivalTime, invocationTime, "
				+ "publishTime, workflowStartTime, bufferSize, maxQueueCapacity, totalMarking, analysisTime) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

		try (Connection connection = getConnection(); 
			 PreparedStatement pstmt = connection.prepareStatement(sql)) {

			pstmt.setLong(1, workflowBase);
			pstmt.setLong(2, sequenceID);
			pstmt.setString(3, serviceName);
			pstmt.setString(4, operation);
			pstmt.setLong(5, arrivalTime);
			pstmt.setLong(6, invocationTime);
			pstmt.setLong(7, publishTime);
			pstmt.setLong(8, workflowStartTime);
			pstmt.setInt(9, bufferSize);
			pstmt.setInt(10, maxQueueCapacity);
			pstmt.setInt(11, totalMarking);
			pstmt.setLong(12, System.currentTimeMillis());

			pstmt.executeUpdate();

			logger.info("Wrote marking: seq=" + sequenceID + " -> workflowBase=" + workflowBase + 
					   " (" + serviceName + ": buffer=" + bufferSize + ", total=" + totalMarking + 
					   ", capacity=" + maxQueueCapacity + ")");
		}
	}

	/**
	 * Write marking record using Map (for compatibility with existing write methods)
	 */
	public void writeMarkingRecord(Map<String, String> markingData) throws SQLException {
		int sequenceID = parseIntValue(markingData.get("sequenceID"), 0);
		int workflowBase = VersionConstants.getWorkflowBase(
			VersionConstants.getVersionFromSequenceId(sequenceID));
		
		String serviceName = markingData.get("serviceName");
		String operation = markingData.get("operation");
		long arrivalTime = parseLongValue(markingData.get("arrivalTime"), 0L);
		long invocationTime = parseLongValue(markingData.get("invocationTime"), 0L);
		long publishTime = parseLongValue(markingData.get("publishTime"), 0L);
		long workflowStartTime = parseLongValue(markingData.get("workflowStartTime"), 0L);
		int bufferSize = parseIntValue(markingData.get("bufferSize"), 0);
		int maxQueueCapacity = parseIntValue(markingData.get("maxQueueCapacity"), 0);
		int totalMarking = parseIntValue(markingData.get("totalMarking"), 0);

		writeMarkingRecord(workflowBase, sequenceID, serviceName, operation, arrivalTime, 
						  invocationTime, publishTime, workflowStartTime, bufferSize, 
						  maxQueueCapacity, totalMarking);
	}

	// =========================================================================
	// WRITE METHODS - LOCAL PETRI NET TABLES
	// =========================================================================
	
	/**
	 * Write transition firing record
	 * Records when a transition fires (token moves from place to place)
	 */
	public void writeTransitionFiring(TreeMap<String, String> record) {
		String sql = "INSERT INTO " + TRANSITION_FIRINGS_TABLE + 
				" (timestamp, transitionId, transitionType, tokenId, workflowBase, " +
				"fromPlace, toPlace, forkDecision, joinState, bufferSize, ruleVersion) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		
		Connection conn = null;
		PreparedStatement pstmt = null;
		
		try {
			conn = getConnection();
			pstmt = conn.prepareStatement(sql);
			
			pstmt.setLong(1, parseLongValue(record.get("timestamp"), 0));
			pstmt.setString(2, record.get("transitionId"));
			pstmt.setString(3, record.get("transitionType"));
			pstmt.setLong(4, parseLongValue(record.get("tokenId"), 0));
			pstmt.setLong(5, parseLongValue(record.get("workflowBase"), 0));
			pstmt.setString(6, record.get("fromPlace"));
			pstmt.setString(7, record.get("toPlace"));
			pstmt.setString(8, record.get("forkDecision"));
			pstmt.setString(9, record.get("joinState"));
			
			// Handle bufferSize and ruleVersion
			String bufferSizeStr = record.get("bufferSize");
			if (bufferSizeStr != null && !bufferSizeStr.isEmpty()) {
				pstmt.setInt(10, parseIntValue(bufferSizeStr, 0));
			} else {
				pstmt.setNull(10, java.sql.Types.INTEGER);
			}
			
			String ruleVersionStr = record.get("ruleVersion");
			if (ruleVersionStr != null && !ruleVersionStr.isEmpty()) {
				pstmt.setString(11, ruleVersionStr);
			} else {
				pstmt.setNull(11, java.sql.Types.VARCHAR);
			}
			
			pstmt.executeUpdate();
			
			logger.debug("Wrote transition firing: " + record.get("transitionId") + 
						" token=" + record.get("tokenId"));
			
		} catch (SQLException e) {
			logger.error("Failed to write transition firing: " + record.get("transitionId"), e);
		} finally {
			close(null, pstmt, conn);
		}
	}
	
	/**
	 * Write token genealogy record (parent-child relationship from fork)
	 */
	public void writeTokenGenealogy(TreeMap<String, String> record) {
		String sql = "INSERT INTO " + TOKEN_GENEALOGY_TABLE + 
				" (parentTokenId, childTokenId, forkTransitionId, forkTimestamp, workflowBase) " +
				"VALUES (?, ?, ?, ?, ?)";
		
		Connection conn = null;
		PreparedStatement pstmt = null;
		
		try {
			conn = getConnection();
			pstmt = conn.prepareStatement(sql);
			
			pstmt.setLong(1, parseLongValue(record.get("parentTokenId"), 0));
			pstmt.setLong(2, parseLongValue(record.get("childTokenId"), 0));
			pstmt.setString(3, record.get("forkTransitionId"));
			pstmt.setLong(4, parseLongValue(record.get("forkTimestamp"), 0));
			pstmt.setLong(5, parseLongValue(record.get("workflowBase"), 0));
			
			pstmt.executeUpdate();
			
			logger.debug("Wrote token genealogy: parent=" + record.get("parentTokenId") + 
						" -> child=" + record.get("childTokenId"));
			
		} catch (SQLException e) {
			logger.error("Failed to write token genealogy", e);
		} finally {
			close(null, pstmt, conn);
		}
	}
	
	/**
	 * Write join synchronization record
	 */
	public void writeJoinSynchronization(TreeMap<String, String> record) {
		String sql = "INSERT INTO " + JOIN_SYNCHRONIZATION_TABLE + 
				" (joinTransitionId, workflowBase, tokenId, arrivalTimestamp, " +
				"requiredCount, currentCount, status, continuationTokenId) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
		
		Connection conn = null;
		PreparedStatement pstmt = null;
		
		try {
			conn = getConnection();
			pstmt = conn.prepareStatement(sql);
			
			pstmt.setString(1, record.get("joinTransitionId"));
			pstmt.setLong(2, parseLongValue(record.get("workflowBase"), 0));
			pstmt.setLong(3, parseLongValue(record.get("tokenId"), 0));
			pstmt.setLong(4, parseLongValue(record.get("arrivalTimestamp"), 0));
			pstmt.setInt(5, parseIntValue(record.get("requiredCount"), 0));
			pstmt.setInt(6, parseIntValue(record.get("currentCount"), 0));
			pstmt.setString(7, record.get("status"));
			
			// continuationTokenId may be empty initially
			String contTokenId = record.get("continuationTokenId");
			if (contTokenId != null && !contTokenId.isEmpty()) {
				pstmt.setLong(8, parseLongValue(contTokenId, 0));
			} else {
				pstmt.setNull(8, java.sql.Types.BIGINT);
			}
			
			pstmt.executeUpdate();
			
			logger.debug("Wrote join sync: " + record.get("joinTransitionId") + 
						" token=" + record.get("tokenId") + 
						" (" + record.get("currentCount") + "/" + record.get("requiredCount") + ")");
			
		} catch (SQLException e) {
			logger.error("Failed to write join synchronization", e);
		} finally {
			close(null, pstmt, conn);
		}
	}
	
	/**
	 * Update join records with continuation token when join completes
	 */
	public void updateJoinCompletion(String joinTransitionId, int workflowBase, 
									int continuationTokenId) {
		String sql = "UPDATE " + JOIN_SYNCHRONIZATION_TABLE + 
				" SET continuationTokenId = ?, status = 'COMPLETE' " +
				"WHERE joinTransitionId = ? AND workflowBase = ?";
		
		Connection conn = null;
		PreparedStatement pstmt = null;
		
		try {
			conn = getConnection();
			pstmt = conn.prepareStatement(sql);
			
			pstmt.setLong(1, continuationTokenId);
			pstmt.setString(2, joinTransitionId);
			pstmt.setLong(3, workflowBase);
			
			int updated = pstmt.executeUpdate();
			
			logger.debug("Updated join completion: " + joinTransitionId + 
						" workflowBase=" + workflowBase + 
						" continuation=" + continuationTokenId + 
						" (" + updated + " records updated)");
			
		} catch (SQLException e) {
			logger.error("Failed to update join completion", e);
		} finally {
			close(null, pstmt, conn);
		}
	}
	
	/**
	 * Get the number of tokens that have arrived at a join
	 */
	public int getJoinTokenCount(String joinTransitionId, int workflowBase) {
		String sql = "SELECT COUNT(*) as tokenCount FROM " + JOIN_SYNCHRONIZATION_TABLE + 
				" WHERE joinTransitionId = ? AND workflowBase = ?";
		
		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try {
			conn = getConnection();
			pstmt = conn.prepareStatement(sql);
			
			pstmt.setString(1, joinTransitionId);
			pstmt.setLong(2, workflowBase);
			
			rs = pstmt.executeQuery();
			if (rs.next()) {
				return rs.getInt("tokenCount");
			}
			
		} catch (SQLException e) {
			logger.error("Failed to get join token count", e);
		} finally {
			close(rs, pstmt, conn);
		}
		
		return 0;
	}
	
	/**
	 * Get all tokens that have contributed to a join
	 */
	public ArrayList<Integer> getContributingTokens(String joinTransitionId, int workflowBase) {
		ArrayList<Integer> tokens = new ArrayList<>();
		
		String sql = "SELECT tokenId FROM " + JOIN_SYNCHRONIZATION_TABLE + 
				" WHERE joinTransitionId = ? AND workflowBase = ? " +
				"ORDER BY tokenId";
		
		Connection conn = null;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try {
			conn = getConnection();
			pstmt = conn.prepareStatement(sql);
			
			pstmt.setString(1, joinTransitionId);
			pstmt.setLong(2, workflowBase);
			
			rs = pstmt.executeQuery();
			while (rs.next()) {
				tokens.add((int) rs.getLong("tokenId"));
			}
			
		} catch (SQLException e) {
			logger.error("Failed to get contributing tokens", e);
		} finally {
			close(rs, pstmt, conn);
		}
		
		return tokens;
	}

	// =========================================================================
	// READ METHODS - CORE TABLES
	// =========================================================================
	
	/**
	 * Read service measurements filtered by workflow base
	 * This prevents mixing different test runs
	 */
	public TreeMap<Integer, ArrayList<Object>> readServiceMeasurementsByWorkflowBase(int workflowBase)
			throws SQLException {
		TreeMap<Integer, ArrayList<Object>> measurements = new TreeMap<>();

		String query = "SELECT * FROM " + SERVICE_MEASUREMENTS_TABLE + 
				" WHERE sequenceID >= ? AND sequenceID < ? " +
				" AND arrivalTime > 0 AND invocationTime > 0 AND publishTime > 0 " +
				" AND arrivalTime <= invocationTime AND invocationTime <= publishTime " + 
				" ORDER BY sequenceID";

		try (Connection conn = getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(query)) {
			
			pstmt.setLong(1, workflowBase);
			pstmt.setLong(2, workflowBase + 100); // Next workflow base

			ResultSet rs = pstmt.executeQuery();

			while (rs.next()) {
				ArrayList<Object> record = new ArrayList<>();
				int id = rs.getInt("id");

				record.add(id);                                // 0
				record.add(rs.getLong("sequenceID"));          // 1
				record.add(rs.getString("serviceName"));       // 2
				record.add(rs.getString("operation"));         // 3
				record.add(rs.getLong("arrivalTime"));         // 4
				record.add(rs.getLong("invocationTime"));      // 5
				record.add(rs.getLong("publishTime"));         // 6
				record.add(rs.getLong("workflowStartTime"));   // 7
				record.add(rs.getInt("bufferSize"));           // 8
				record.add(rs.getInt("maxQueueCapacity"));     // 9
				record.add(rs.getInt("totalMarking"));         // 10

				measurements.put(id, record);
			}

			logger.info("Read " + measurements.size() + " service measurements for workflow base " + workflowBase);
		}

		return measurements;
	}

	/**
	 * Read service measurements for a specific sequence ID
	 */
	public TreeMap<Integer, ArrayList<Object>> readServiceMeasurementsBySequenceId(int sequenceId) 
			throws SQLException {
		TreeMap<Integer, ArrayList<Object>> measurements = new TreeMap<>();

		String query = "SELECT * FROM " + SERVICE_MEASUREMENTS_TABLE + 
				" WHERE sequenceID = ? ORDER BY arrivalTime";

		try (Connection conn = getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(query)) {
			
			pstmt.setLong(1, sequenceId);

			ResultSet rs = pstmt.executeQuery();

			while (rs.next()) {
				ArrayList<Object> record = new ArrayList<>();
				int id = rs.getInt("id");

				record.add(id);                                // 0
				record.add(rs.getLong("sequenceID"));          // 1
				record.add(rs.getString("serviceName"));       // 2
				record.add(rs.getString("operation"));         // 3
				record.add(rs.getLong("arrivalTime"));         // 4
				record.add(rs.getLong("invocationTime"));      // 5
				record.add(rs.getLong("publishTime"));         // 6
				record.add(rs.getLong("workflowStartTime"));   // 7
				record.add(rs.getInt("bufferSize"));           // 8
				record.add(rs.getInt("maxQueueCapacity"));     // 9
				record.add(rs.getInt("totalMarking"));         // 10
				
				measurements.put(id, record);
			}
			
			logger.info("Read " + measurements.size() + " service measurements for sequence ID " + sequenceId);
		}
		
		return measurements;
	}

	/**
	 * Read service measurements (all for a workflow base)
	 */
	public TreeMap<Integer, ArrayList<Object>> readServiceMeasurements(int workflowBase) throws SQLException {
		return readServiceMeasurementsByWorkflowBase(workflowBase);
	}

	/**
	 * Read marking data filtered by workflow base
	 */
	public TreeMap<Integer, ArrayList<Object>> readMarkingsByWorkflowBase(int workflowBase) 
			throws SQLException {
		TreeMap<Integer, ArrayList<Object>> markings = new TreeMap<>();

		String query = "SELECT * FROM " + MARKINGS_TABLE + 
					  " WHERE sequenceID >= ? AND sequenceID < ? " + 
					  " ORDER BY sequenceID, arrivalTime";

		try (Connection conn = getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(query)) {
			
			pstmt.setLong(1, workflowBase);
			pstmt.setLong(2, workflowBase + 100); // Next workflow base

			ResultSet rs = pstmt.executeQuery();

			while (rs.next()) {
				ArrayList<Object> record = new ArrayList<>();
				int id = rs.getInt("id");

				record.add(id);                                // 0: id
				record.add(rs.getLong("workflowBase"));        // 1: workflowBase
				record.add(rs.getLong("sequenceID"));          // 2: sequenceID
				record.add(rs.getString("serviceName"));       // 3: serviceName
				record.add(rs.getString("operation"));         // 4: operation
				record.add(rs.getLong("arrivalTime"));         // 5: arrivalTime
				record.add(rs.getLong("invocationTime"));      // 6: invocationTime
				record.add(rs.getLong("publishTime"));         // 7: publishTime
				record.add(rs.getLong("workflowStartTime"));   // 8: workflowStartTime
				record.add(rs.getInt("bufferSize"));           // 9: bufferSize
				record.add(rs.getInt("maxQueueCapacity"));     // 10: maxQueueCapacity
				record.add(rs.getInt("totalMarking"));         // 11: totalMarking
				record.add(rs.getLong("analysisTime"));        // 12: analysisTime

				markings.put(id, record);
			}

			logger.info("Read " + markings.size() + " marking records for workflow base " + workflowBase);
		}

		return markings;
	}

	/**
	 * Read marking data for a specific sequence ID
	 */
	public TreeMap<Integer, ArrayList<Object>> readMarkingsBySequenceId(int sequenceId) 
			throws SQLException {
		TreeMap<Integer, ArrayList<Object>> markings = new TreeMap<>();

		String query = "SELECT * FROM " + MARKINGS_TABLE + 
					  " WHERE sequenceID = ? ORDER BY arrivalTime";

		try (Connection conn = getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(query)) {
			
			pstmt.setLong(1, sequenceId);

			ResultSet rs = pstmt.executeQuery();

			while (rs.next()) {
				ArrayList<Object> record = new ArrayList<>();
				int id = rs.getInt("id");

				record.add(id);                                // 0: id
				record.add(rs.getLong("workflowBase"));        // 1: workflowBase
				record.add(rs.getLong("sequenceID"));          // 2: sequenceID
				record.add(rs.getString("serviceName"));       // 3: serviceName
				record.add(rs.getString("operation"));         // 4: operation
				record.add(rs.getLong("arrivalTime"));         // 5: arrivalTime
				record.add(rs.getLong("invocationTime"));      // 6: invocationTime
				record.add(rs.getLong("publishTime"));         // 7: publishTime
				record.add(rs.getLong("workflowStartTime"));   // 8: workflowStartTime
				record.add(rs.getInt("bufferSize"));           // 9: bufferSize
				record.add(rs.getInt("maxQueueCapacity"));     // 10: maxQueueCapacity
				record.add(rs.getInt("totalMarking"));         // 11: totalMarking
				record.add(rs.getLong("analysisTime"));        // 12: analysisTime

				markings.put(id, record);
			}

			logger.info("Read " + markings.size() + " marking records for sequence ID " + sequenceId);
		}

		return markings;
	}

	// =========================================================================
	// DATABASE MANAGEMENT METHODS
	// =========================================================================
	
	/**
	 * Purge ALL tables in the database - DELETE data but keep table structure
	 * Uses database metadata to discover ALL tables (not just known ones)
	 * This ensures no tables are missed even if new ones are added elsewhere
	 * 
	 * @return Number of tables purged
	 */
	public int purgeAllTables() throws SQLException {
		Connection conn = null;
		Statement stmt = null;
		int tablesPurged = 0;
		
		try {
			conn = getConnection();
			stmt = conn.createStatement();
			
			// Discover ALL tables from database metadata
			ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"});
			List<String> allTables = new ArrayList<>();
			
			while (rs.next()) {
				String tableName = rs.getString("TABLE_NAME");
				// Skip Derby system tables
				if (!tableName.startsWith("SYS")) {
					allTables.add(tableName);
				}
			}
			rs.close();
			
			logger.info("Discovered " + allTables.size() + " tables to purge");
			
			// Delete all data from each discovered table
			for (String tableName : allTables) {
				try {
					stmt.execute("DELETE FROM " + tableName);
					logger.info("Purged table: " + tableName);
					tablesPurged++;
				} catch (SQLException e) {
					logger.warn("Could not purge table " + tableName + ": " + e.getMessage());
				}
			}
			
			logger.info("Total tables purged: " + tablesPurged);
			
		} finally {
			closeStatement(stmt);
			closeConnection(conn);
		}
		
		return tablesPurged;
	}

	/**
	 * Drop ALL tables in the database - REMOVE table structure
	 * Uses database metadata to discover ALL tables (not just known ones)
	 * 
	 * @return Number of tables dropped
	 */
	public int dropAllTables() throws SQLException {
		Connection conn = null;
		Statement stmt = null;
		int tablesDropped = 0;

		try {
			conn = getConnection();
			stmt = conn.createStatement();

			// Discover ALL tables from database metadata
			ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"});
			List<String> allTables = new ArrayList<>();
			
			while (rs.next()) {
				String tableName = rs.getString("TABLE_NAME");
				// Skip Derby system tables
				if (!tableName.startsWith("SYS")) {
					allTables.add(tableName);
				}
			}
			rs.close();
			
			logger.info("Discovered " + allTables.size() + " tables to drop");
			
			// Drop each discovered table
			for (String tableName : allTables) {
				try {
					stmt.execute("DROP TABLE " + tableName);
					logger.info("Dropped table: " + tableName);
					tablesDropped++;
				} catch (SQLException e) {
					logger.warn("Could not drop table " + tableName + ": " + e.getMessage());
				}
			}
			
			logger.info("Total tables dropped: " + tablesDropped);

		} finally {
			closeStatement(stmt);
			closeConnection(conn);
		}
		
		return tablesDropped;
	}

	/**
	 * Purge ALL tables found in the database (drops and recreates)
	 * This discovers tables via metadata rather than using the known list.
	 * More aggressive cleanup than purgeAllTables which just deletes data.
	 * 
	 * @return Number of tables that were dropped
	 */
	public int purgeAllDatabaseTables() throws SQLException {
		Connection conn = null;
		Statement stmt = null;
		int tablesDropped = 0;
		
		try {
			conn = getConnection();
			stmt = conn.createStatement();
			
			// Get all table names from database metadata
			ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"});
			List<String> allTables = new ArrayList<>();
			
			while (rs.next()) {
				String tableName = rs.getString("TABLE_NAME");
				// Skip Derby system tables
				if (!tableName.startsWith("SYS")) {
					allTables.add(tableName);
				}
			}
			rs.close();
			
			logger.info("Found " + allTables.size() + " tables to purge");
			
			// Drop each discovered table
			for (String tableName : allTables) {
				try {
					stmt.execute("DROP TABLE " + tableName);
					logger.info("Dropped table: " + tableName);
					tablesDropped++;
				} catch (SQLException e) {
					logger.warn("Could not drop table " + tableName + ": " + e.getMessage());
				}
			}
			
			logger.info("Total tables dropped: " + tablesDropped);
			
		} finally {
			closeStatement(stmt);
			closeConnection(conn);
		}
		
		return tablesDropped;
	}

	// =========================================================================
	// SUMMARY & STATISTICS METHODS
	// =========================================================================
	
	/**
	 * Get comprehensive measurement summary
	 */
	public String getMeasurementSummary() throws SQLException {
		StringBuilder summary = new StringBuilder();
		Connection conn = null;
		Statement stmt = null;
		ResultSet rs = null;

		try {
			conn = getConnection();
			stmt = conn.createStatement();

			summary.append("\n========== MEASUREMENT SUMMARY ==========\n");

			// Core tables count
			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + SERVICE_MEASUREMENTS_TABLE);
			if (rs.next()) {
				summary.append("Service Measurements: ").append(rs.getInt("count")).append("\n");
			}
			rs.close();

			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + PROCESS_MEASUREMENTS_TABLE);
			if (rs.next()) {
				summary.append("Process Measurements: ").append(rs.getInt("count")).append("\n");
			}
			rs.close();

			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + SERVICE_CONTRIBUTION_TABLE);
			if (rs.next()) {
				summary.append("Service Contributions: ").append(rs.getInt("count")).append("\n");
			}
			rs.close();

			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + MARKINGS_TABLE);
			if (rs.next()) {
				summary.append("Marking Records: ").append(rs.getInt("count")).append("\n");
			}
			rs.close();

			// Consolidated Petri Net tables count
			summary.append("\n--- Consolidated Petri Net Data ---\n");
			
			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + CONSOLIDATED_TRANSITION_FIRINGS_TABLE);
			if (rs.next()) {
				summary.append("Consolidated Transition Firings: ").append(rs.getInt("count")).append("\n");
			}
			rs.close();

			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + CONSOLIDATED_TOKEN_PATHS_TABLE);
			if (rs.next()) {
				summary.append("Consolidated Token Paths: ").append(rs.getInt("count")).append("\n");
			}
			rs.close();

			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + CONSOLIDATED_MARKING_EVOLUTION_TABLE);
			if (rs.next()) {
				summary.append("Consolidated Marking Evolution: ").append(rs.getInt("count")).append("\n");
			}
			rs.close();

			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + CONSOLIDATED_PLACE_STATISTICS_TABLE);
			if (rs.next()) {
				summary.append("Consolidated Place Statistics: ").append(rs.getInt("count")).append("\n");
			}
			rs.close();

			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + CONSOLIDATED_TOKEN_GENEALOGY_TABLE);
			if (rs.next()) {
				summary.append("Consolidated Token Genealogy: ").append(rs.getInt("count")).append("\n");
			}
			rs.close();

			// Local Petri Net tables count
			summary.append("\n--- Local Petri Net Data ---\n");
			
			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + TRANSITION_FIRINGS_TABLE);
			if (rs.next()) {
				summary.append("Transition Firings: ").append(rs.getInt("count")).append("\n");
			}
			rs.close();

			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + TOKEN_GENEALOGY_TABLE);
			if (rs.next()) {
				summary.append("Token Genealogy: ").append(rs.getInt("count")).append("\n");
			}
			rs.close();

			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + JOIN_SYNCHRONIZATION_TABLE);
			if (rs.next()) {
				summary.append("Join Synchronization: ").append(rs.getInt("count")).append("\n");
			}
			rs.close();

			summary.append("\n--- Service Measurements Detail ---\n");

			// Check if we have any data other than MonitorService
			rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + SERVICE_MEASUREMENTS_TABLE
					+ " WHERE serviceName != 'MonitorService'");

			boolean hasServiceData = false;
			if (rs.next()) {
				hasServiceData = rs.getInt("count") > 0;
			}
			rs.close();

			if (hasServiceData) {
				// Calculate stats for non-Monitor services
				rs = stmt.executeQuery("SELECT " + 
						"AVG(invocationTime - arrivalTime) as avgQueue, "
						+ "AVG(publishTime - invocationTime) as avgService, "
						+ "AVG(publishTime - arrivalTime) as avgTotal, "
						+ "MAX(publishTime - arrivalTime) as maxTotal, " + 
						"MIN(publishTime - arrivalTime) as minTotal "
						+ "FROM " + SERVICE_MEASUREMENTS_TABLE + 
						" WHERE serviceName != 'MonitorService'");

				if (rs.next() && rs.getObject("avgQueue") != null) {
					summary.append("Service Timing Statistics (excluding Monitor):\n");
					summary.append("  Avg Queue Time: ").append(Math.round(rs.getDouble("avgQueue"))).append("ms\n");
					summary.append("  Avg Service Time: ").append(Math.round(rs.getDouble("avgService")))
							.append("ms\n");
					summary.append("  Avg Total Time: ").append(Math.round(rs.getDouble("avgTotal"))).append("ms\n");
					summary.append("  Max Total Time: ").append(rs.getLong("maxTotal")).append("ms\n");
					summary.append("  Min Total Time: ").append(rs.getLong("minTotal")).append("ms\n");
				}
				rs.close();
			} else {
				summary.append("Note: Only MonitorService acknowledgments recorded.\n");
				summary.append("Actual service timing data will appear when ServiceThread monitoring is enabled.\n");
			}

			// Workflow elapsed time stats from PROCESSMEASUREMENTS
			rs = stmt.executeQuery("SELECT " + 
					"COUNT(*) as count, " + 
					"AVG(elapsedTime) as avgElapsed, "
					+ "MAX(elapsedTime) as maxElapsed, " + 
					"MIN(elapsedTime) as minElapsed " + 
					"FROM " + PROCESS_MEASUREMENTS_TABLE);

			if (rs.next() && rs.getInt("count") > 0) {
				summary.append("\nWorkflow Elapsed Time Statistics:\n");
				summary.append("  Total Workflows: ").append(rs.getInt("count")).append("\n");
				summary.append("  Avg Elapsed: ").append(Math.round(rs.getDouble("avgElapsed"))).append("ms\n");
				summary.append("  Max Elapsed: ").append(rs.getLong("maxElapsed")).append("ms\n");
				summary.append("  Min Elapsed: ").append(rs.getLong("minElapsed")).append("ms\n");
			}
			rs.close();

			// Marking statistics
			rs = stmt.executeQuery("SELECT " 
					+ "COUNT(*) as count, "
					+ "AVG(CAST(bufferSize AS DOUBLE)) as avgBuffer, "
					+ "MAX(bufferSize) as maxBuffer, "
					+ "AVG(CAST(totalMarking AS DOUBLE)) as avgMarking, "
					+ "MAX(totalMarking) as maxMarking "
					+ "FROM " + MARKINGS_TABLE);

			if (rs.next() && rs.getInt("count") > 0) {
				summary.append("\nPetri Net Marking Statistics:\n");
				summary.append("  Total Records: ").append(rs.getInt("count")).append("\n");
				summary.append("  Avg Buffer Size: ").append(String.format("%.1f", rs.getDouble("avgBuffer"))).append("\n");
				summary.append("  Max Buffer Size: ").append(rs.getInt("maxBuffer")).append("\n");
				summary.append("  Avg Total Marking: ").append(String.format("%.1f", rs.getDouble("avgMarking"))).append("\n");
				summary.append("  Max Total Marking: ").append(rs.getInt("maxMarking")).append("\n");
			}
			rs.close();

			summary.append("\n==========================================\n");

		} catch (SQLException e) {
			logger.error("Error generating measurement summary: " + e.getMessage(), e);
			throw e;
		} finally {
			closeResultSet(rs);
			closeStatement(stmt);
			closeConnection(conn);
		}

		return summary.toString();
	}

	/**
	 * Get table statistics (helper method)
	 */
	public TreeMap<String, Integer> getMeasurementStatistics() throws SQLException {
		TreeMap<String, Integer> statistics = new TreeMap<>();

		try (Connection conn = getConnection();
			 Statement stmt = conn.createStatement()) {

			// Count records in each table
			for (String tableName : ALL_TABLES) {
				try {
					ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + tableName);
					if (rs.next()) {
						statistics.put(tableName + "_Count", rs.getInt("count"));
					}
					rs.close();
				} catch (SQLException e) {
					logger.warn("Could not get count for " + tableName + ": " + e.getMessage());
					statistics.put(tableName + "_Count", -1);
				}
			}

			logger.info("Retrieved measurement statistics: " + statistics);
		}

		return statistics;
	}

	// =========================================================================
	// UTILITY METHODS
	// =========================================================================
	
	/**
	 * Get database connection
	 */
	public Connection getConnection() throws SQLException {
		return DriverManager.getConnection(DB_URL);
	}

	/**
	 * Check if table exists
	 */
	private boolean tableExists(Statement statement, String tableName) throws SQLException {
		ResultSet rs = null;
		try {
			rs = statement.getConnection().getMetaData().getTables(null, null, tableName.toUpperCase(), null);
			return rs.next();
		} finally {
			closeResultSet(rs);
		}
	}

	/**
	 * Manage table - create or verify existence
	 */
	private void manageTable(Statement statement, String tableName, String createSQL) throws SQLException {
		if (tableExists(statement, tableName)) {
			logger.info("Table " + tableName + " already exists");
		} else {
			statement.execute(createSQL);
			logger.info("Table " + tableName + " created successfully");
		}
	}

	// =========================================================================
	// PARSING UTILITY METHODS
	// =========================================================================
	
	private int parseIntValue(String value, int defaultValue) {
		try {
			return value != null ? Integer.parseInt(value) : defaultValue;
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	private long parseLongValue(String value, long defaultValue) {
		try {
			return value != null ? Long.parseLong(value) : defaultValue;
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	private double parseDoubleValue(String value, double defaultValue) {
		try {
			return value != null ? Double.parseDouble(value) : defaultValue;
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	// =========================================================================
	// RESOURCE CLEANUP METHODS
	// =========================================================================
	
	private void close(ResultSet rs, Statement stmt, Connection conn) {
		closeResultSet(rs);
		closeStatement(stmt);
		closeConnection(conn);
	}

	private void close(ResultSet rs, PreparedStatement pstmt, Connection conn) {
		closeResultSet(rs);
		closeStatement(pstmt);
		closeConnection(conn);
	}

	private static void closeResultSet(ResultSet rs) {
		if (rs != null) {
			try {
				rs.close();
			} catch (SQLException e) {
				// Silent close
			}
		}
	}

	private static void closeStatement(Statement statement) {
		if (statement != null) {
			try {
				statement.close();
			} catch (SQLException e) {
				// Silent close
			}
		}
	}

	private static void closeConnection(Connection connection) {
		if (connection != null) {
			try {
				connection.close();
			} catch (SQLException e) {
				// Silent close
			}
		}
	}

	private static void shutdownDerby() {
		try {
			DriverManager.getConnection(PROTOCOL + ";shutdown=true");
		} catch (SQLException e) {
			if ("08006".equals(e.getSQLState()) || "XJ015".equals(e.getSQLState())) {
				logger.info("Derby shutdown normally.");
			} else {
				logger.error("Derby shutdown error:", e);
			}
		}
	}

	private static void printSQLException(SQLException e) {
		while (e != null) {
			logger.error("SQL State: " + e.getSQLState());
			logger.error("Error Code: " + e.getErrorCode());
			logger.error("Message: " + e.getMessage());
			e = e.getNextException();
		}
	}
}