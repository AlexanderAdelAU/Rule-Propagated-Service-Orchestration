package org.btsn.places;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.HashSet;

import org.apache.derby.jdbc.EmbeddedDriver;
import org.apache.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.btsn.base.BaseStochasticPetriNetPlace;
import org.btsn.constants.VersionConstants;
import org.btsn.json.jsonLibrary;

/**
 * MonitorService - FIXED VERSION with proper workflow base calculation
 * ENHANCED with marking data collection support
 */
public class MonitorService extends BaseStochasticPetriNetPlace {

	private static final Logger logger = Logger.getLogger(MonitorService.class);

	// Database constants
	private static final String PROTOCOL = "jdbc:derby:";
	private static final String DB_NAME = "./ServiceAnalysisDataBase";
	private static final String DB_URL = PROTOCOL + DB_NAME + ";create=true";

	static {
		try {
			DriverManager.registerDriver(new EmbeddedDriver());
			logger.info("Derby driver registered in MonitorService");
		} catch (SQLException e) {
			logger.error("Failed to register Derby driver", e);
		}
	}

	// Table names
	private static final String SERVICE_MEASUREMENTS_TABLE = "SERVICEMEASUREMENTS";
	private static final String PROCESS_MEASUREMENTS_TABLE = "PROCESSMEASUREMENTS";
	private static final String SERVICE_CONTRIBUTION_TABLE = "SERVICECONTRIBUTION";
	private static final String MARKINGS_TABLE = "MARKINGS";

	private static final int PRIMING_ID = 555555000;
	
	// ============================================================================
		// ADD THESE CONSTANTS AT THE TOP OF MonitorService.java (around line 48)
		// ============================================================================

	private static final String CONSOLIDATED_TRANSITION_FIRINGS_TABLE = "CONSOLIDATED_TRANSITION_FIRINGS";
	private static final String CONSOLIDATED_TOKEN_PATHS_TABLE = "CONSOLIDATED_TOKEN_PATHS";
	private static final String CONSOLIDATED_MARKING_EVOLUTION_TABLE = "CONSOLIDATED_MARKING_EVOLUTION";
	private static final String CONSOLIDATED_PLACE_STATISTICS_TABLE = "CONSOLIDATED_PLACE_STATISTICS";
	private static final String CONSOLIDATED_TOKEN_GENEALOGY_TABLE = "CONSOLIDATED_TOKEN_GENEALOGY";

	// Migration flag - ensure we only try to add columns once per session
	private static boolean markingEvolutionColumnsChecked = false;

	public MonitorService(String sequenceID) {
		super(sequenceID, "MONITOR");
		logger.info("MonitorService created for sequenceID: " + getSequenceID());
	}

	/**
	 * Calculate workflow base for a given sequence ID based on version ranges
	 */
	private int calculateWorkflowBase(int sequenceID) {
		return VersionConstants.getWorkflowBase(VersionConstants.getVersionFromSequenceId(sequenceID));
	}

	/**
	 * Write service timing record - ENHANCED to handle chunked data with
	 * deduplication
	 */
	public String writeServiceTimingRecord(String token) {
		System.out.println("=== PROCESSING MONITORING DATA ===");

		try {
			// Parse the incoming JSON payload
			JSONObject monitoringData = jsonLibrary.parseString(token);

			if (monitoringData == null) {
				logger.error("Could not parse monitoring data JSON");
				return "{\"status\":\"error\",\"message\":\"Invalid JSON payload\"}";
			}

			// Check if this is chunked data
			boolean isChunked = monitoringData.containsKey("chunkIndex");

			if (isChunked) {
				return handleChunkedData(monitoringData, getSequenceID()); // PASS SEQUENCE ID!
			} else {
				// Handle legacy single-chunk data
				return handleSingleChunk(monitoringData);
			}

		} catch (Exception e) {
			logger.error("Error processing monitoring data: " + e.getMessage(), e);
			return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
		}
	}

	// ============================================================================
	// ADD THIS METHOD TO: MonitorService.java
	// Location: org.btsn.petrinet.services.MonitorService
	// ============================================================================

	// ADD THESE IMPORTS AT THE TOP OF YOUR FILE:
	// import org.json.simple.JSONObject;
	// import org.json.simple.JSONArray;
	// import org.json.simple.parser.JSONParser;

	/**
	 * Unified collector data writer - handles combined performance + Petri Net data
	 * Unpacks the JSON and stores each data type appropriately
	 * 
	 * This method receives data from BaseCollectorService.collectAllData()
	 * and separates it into performance and Petri Net components for storage
	 */
	public String writeCollectorData(String jsonData) {
	    System.out.println("=== MonitorService: PROCESSING UNIFIED COLLECTOR DATA ===");
	    
	    try {
	        // Parse the JSON string
	        JSONParser parser = new JSONParser();
	        JSONObject data = (JSONObject) parser.parse(jsonData);
	        
	        // Log what we received
	        String serviceName = getStringValue(data, "reportingService", "Unknown");
	        String place = getStringValue(data, "monitoredPlace", "Unknown");
	        String version = getStringValue(data, "version", "Unknown");
	        
	        System.out.println("Received data from: " + serviceName);
	        System.out.println("Monitored place: " + place);
	        System.out.println("Version: " + version);
	        
	        int perfRecordsProcessed = 0;
	        int petriNetRecordsProcessed = 0;
	        
	        // Extract and store performance data
	        if (data.containsKey("performanceData")) {
	            System.out.println("Processing performance data...");
	            JSONObject perfData = (JSONObject) data.get("performanceData");
	            perfRecordsProcessed = writePerformanceDataFromCollector(perfData, data);
	            System.out.println("Stored " + perfRecordsProcessed + " performance records");
	        } else {
	            System.out.println("No performance data in payload");
	        }
	        
	        // Extract and store Petri Net data
	        if (data.containsKey("petriNetData")) {
	            System.out.println("Processing Petri Net data...");
	            JSONObject petriNetData = (JSONObject) data.get("petriNetData");
	            petriNetRecordsProcessed = writePetriNetDataFromCollector(petriNetData, data);
	            System.out.println("Stored " + petriNetRecordsProcessed + " Petri Net records");
	        } else {
	            System.out.println("No Petri Net data in payload");
	        }
	        
	        // Return success response
	        JSONObject response = new JSONObject();
	        response.put("status", "success");
	        response.put("performanceRecordsProcessed", perfRecordsProcessed);
	        response.put("petriNetRecordsProcessed", petriNetRecordsProcessed);
	        response.put("totalRecordsProcessed", perfRecordsProcessed + petriNetRecordsProcessed);
	        
	        System.out.println("=== UNIFIED COLLECTOR DATA PROCESSING COMPLETE ===");
	        System.out.println("Total records processed: " + (perfRecordsProcessed + petriNetRecordsProcessed));
	        
	        return response.toJSONString();
	        
	    } catch (Exception e) {
	        System.err.println("Error processing collector data: " + e.getMessage());
	        e.printStackTrace();
	        
	        JSONObject errorResponse = new JSONObject();
	        errorResponse.put("status", "error");
	        errorResponse.put("message", e.getMessage());
	        return errorResponse.toJSONString();
	    }
	}

	// ============================================================================
	// HELPER METHODS
	// ============================================================================

	/**
	 * Helper method to safely get a String value from JSONObject with default
	 */
	private String getStringValue(JSONObject obj, String key, String defaultValue) {
	    Object value = obj.get(key);
	    if (value != null) {
	        return value.toString();
	    }
	    return defaultValue;
	}

	/**
	 * Helper method to safely get an integer value from JSONObject with default
	 */
	private int getIntValue(JSONObject obj, String key, int defaultValue) {
	    Object value = obj.get(key);
	    if (value instanceof Number) {
	        return ((Number) value).intValue();
	    }
	    return defaultValue;
	}

	/**
	 * Helper method to safely get a long value from JSONObject with default
	 */
	private long getLongValue(JSONObject obj, String key, long defaultValue) {
	    Object value = obj.get(key);
	    if (value instanceof Number) {
	        return ((Number) value).longValue();
	    }
	    return defaultValue;
	}

	/**
	 * Write performance data to database
	 * Extracts workflow timing and marking data from the collector payload
	 */
	@SuppressWarnings("unchecked")
	private int writePerformanceDataFromCollector(JSONObject perfData, JSONObject metadata) {
	    int recordCount = 0;
	    
	    try {
	        System.out.println("=== PROCESSING PERFORMANCE DATA ===");
	        
	        String place = getStringValue(metadata, "monitoredPlace", "Unknown");
	        String version = getStringValue(metadata, "version", "Unknown");
	        
	        // Process workflow timing records
	        if (perfData.containsKey("workflowGroups")) {
	            JSONObject workflowGroups = (JSONObject) perfData.get("workflowGroups");
	            
	            for (Object workflowIdObj : workflowGroups.keySet()) {
	                String workflowId = workflowIdObj.toString();
	                JSONArray records = (JSONArray) workflowGroups.get(workflowId);
	                
	                for (Object recordObj : records) {
	                    JSONObject record = (JSONObject) recordObj;
	                    
	                    // Extract timing information
	                    int sequenceId = getIntValue(record, "sequenceId", 0);
	                    String serviceName = getStringValue(record, "serviceName", place);
	                    String operation = getStringValue(record, "operation", "processToken");
	                    long arrivalTime = getLongValue(record, "arrivalTime", 0);
	                    long queueTime = getLongValue(record, "queueTime", 0);
	                    long serviceTime = getLongValue(record, "serviceTime", 0);
	                    long totalTime = getLongValue(record, "totalTime", 0);
	                    long workflowStartTime = getLongValue(record, "workflowStartTime", 0);
	                    int bufferSize = getIntValue(record, "bufferSize", 0);
	                    int maxQueueCapacity = getIntValue(record, "maxQueueCapacity", 0);
	                    int totalMarking = getIntValue(record, "totalMarking", 0);
	                    
	                    // Write to database
	                    writeServiceContribution(sequenceId, serviceName, operation, 
	                                            arrivalTime, queueTime, serviceTime, totalTime, 
	                                            workflowStartTime, bufferSize, maxQueueCapacity, totalMarking);
	                    
	                    recordCount++;
	                }
	            }
	        }
	        
	        // Process marking snapshots
	        if (perfData.containsKey("markingData")) {
	            JSONObject markingData = (JSONObject) perfData.get("markingData");
	            
	            for (Object workflowIdObj : markingData.keySet()) {
	                String workflowId = workflowIdObj.toString();
	                JSONArray records = (JSONArray) markingData.get(workflowId);
	                
	                for (Object recordObj : records) {
	                    JSONObject record = (JSONObject) recordObj;
	                    
	                    // Extract marking information
	                    int sequenceId = getIntValue(record, "sequenceId", 0);
	                    String serviceName = getStringValue(record, "serviceName", place);
	                    String operation = getStringValue(record, "operation", "processToken");
	                    long arrivalTime = getLongValue(record, "arrivalTime", 0);
	                    long invocationTime = getLongValue(record, "invocationTime", 0);
	                    long publishTime = getLongValue(record, "publishTime", 0);
	                    long workflowStartTime = getLongValue(record, "workflowStartTime", 0);
	                    int bufferSize = getIntValue(record, "bufferSize", 0);
	                    int maxQueueCapacity = getIntValue(record, "maxQueueCapacity", 0);
	                    int totalMarking = getIntValue(record, "totalMarking", 0);
	                    
	                    // Write to database
	                    writeMarkingRecord(sequenceId, serviceName, operation, 
	                                      arrivalTime, invocationTime, publishTime, 
	                                      workflowStartTime, bufferSize, maxQueueCapacity, totalMarking);
	                    
	                    recordCount++;
	                }
	            }
	        }
	        
	    } catch (Exception e) {
	        System.err.println("Error writing performance data: " + e.getMessage());
	        e.printStackTrace();
	    }
	    
	    return recordCount;
	}

	/**
	 * Write Petri Net data to database
	 * Extracts transition firings, token paths, and statistics from the collector payload
	 */
	private int writePetriNetDataFromCollector(JSONObject petriNetData, JSONObject metadata) {
	    int recordCount = 0;
	    
	    try {
	        System.out.println("=== PROCESSING PETRI NET DATA ===");
	        
	        String reportingService = getStringValue(metadata, "reportingService", "Unknown");
	        String place = getStringValue(metadata, "monitoredPlace", "Unknown");
	        String reportingChannel = getStringValue(metadata, "reportingChannel", "unknown");
	        long analysisTime = System.currentTimeMillis();
	        
	        // Navigate into workflowGroups
	        if (petriNetData.containsKey("workflowGroups")) {
	            JSONObject workflowGroups = (JSONObject) petriNetData.get("workflowGroups");
	            
	            // Loop through each workflow base (e.g., "100000")
	            for (Object workflowBaseKey : workflowGroups.keySet()) {
	                long workflowBase = Long.parseLong(workflowBaseKey.toString());
	                JSONObject workflowData = (JSONObject) workflowGroups.get(workflowBaseKey);
	                
	                System.out.println("Processing workflow base: " + workflowBase);
	                
	                // Process transition firings
	                if (workflowData.containsKey("transitionFirings")) {
	                    JSONArray firings = (JSONArray) workflowData.get("transitionFirings");
	                    System.out.println("Found " + firings.size() + " transition firings");
	                    recordCount += writeTransitionFirings(firings, workflowBase, place, reportingService, reportingChannel, analysisTime);
	                }
	                
	                // Process token paths
	                if (workflowData.containsKey("tokenPaths")) {
	                    JSONArray paths = (JSONArray) workflowData.get("tokenPaths");
	                    System.out.println("Found " + paths.size() + " token paths");
	                    recordCount += writeTokenPaths(paths, workflowBase, place, reportingService, reportingChannel, analysisTime);
	                }
	                
	                // Process marking evolution
	                if (workflowData.containsKey("markingEvolution")) {
	                    JSONArray markings = (JSONArray) workflowData.get("markingEvolution");
	                    System.out.println("Found " + markings.size() + " marking snapshots");
	                    recordCount += writeMarkingEvolution(markings, workflowBase, place, reportingService, reportingChannel, analysisTime);
	                }
	                
	                // Process statistics
	                if (workflowData.containsKey("statistics")) {
	                    JSONArray stats = (JSONArray) workflowData.get("statistics");
	                    System.out.println("Found " + stats.size() + " statistics records");
	                    recordCount += writePlaceStatistics(stats, workflowBase, reportingService, reportingChannel, analysisTime);
	                }
	                
	                // Process token genealogy
	                if (workflowData.containsKey("tokenGenealogy")) {
	                    JSONArray genealogy = (JSONArray) workflowData.get("tokenGenealogy");
	                    System.out.println("Found " + genealogy.size() + " genealogy records");
	                    recordCount += writeTokenGenealogy(genealogy, workflowBase, reportingService, reportingChannel, analysisTime);
	                }
	            }
	        }
	        
	    } catch (Exception e) {
	        System.err.println("Error writing Petri Net data: " + e.getMessage());
	        e.printStackTrace();
	    }
	    
	    return recordCount;
	}

	// ============================================================================
	// PETRI NET DATA WRITING METHODS
	// ============================================================================

	private int writeTransitionFirings(JSONArray firings, long workflowBase, String place, 
	                                   String reportingService, String reportingChannel, long analysisTime) {
	    int count = 0;
	    String sql = "INSERT INTO " + CONSOLIDATED_TRANSITION_FIRINGS_TABLE + 
	                 " (workflowBase, tokenId, transitionId, timestamp, toPlace, fromPlace, bufferSize, " +
	                 "placeName, reportingService, reportingChannel, analysisTime, eventType) " +
	                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
	    
	    try (Connection conn = getConnection();
	         PreparedStatement pstmt = conn.prepareStatement(sql)) {
	        
	        for (Object obj : firings) {
	            JSONObject firing = (JSONObject) obj;
	            pstmt.setLong(1, workflowBase);
	            pstmt.setLong(2, getLongValue(firing, "tokenId", 0));
	            pstmt.setString(3, getStringValue(firing, "transitionId", ""));
	            pstmt.setLong(4, getLongValue(firing, "timestamp", 0));
	            pstmt.setString(5, getStringValue(firing, "toPlace", ""));
	            pstmt.setString(6, getStringValue(firing, "fromPlace", ""));
	            pstmt.setInt(7, getIntValue(firing, "bufferSize", 0));
	            pstmt.setString(8, place);
	            pstmt.setString(9, reportingService);
	            pstmt.setString(10, reportingChannel);
	            pstmt.setLong(11, analysisTime);
	            pstmt.setString(12, getStringValue(firing, "eventType", ""));
	            pstmt.executeUpdate();
	            count++;
	        }
	    } catch (SQLException e) {
	        System.err.println("Error writing transition firings: " + e.getMessage());
	        e.printStackTrace();
	    }
	    return count;
	}

	private int writeTokenPaths(JSONArray paths, long workflowBase, String place,
	                            String reportingService, String reportingChannel, long analysisTime) {
	    int count = 0;
	    String sql = "INSERT INTO " + CONSOLIDATED_TOKEN_PATHS_TABLE + 
	                 " (workflowBase, tokenId, placeName, entryTime, exitTime, residenceTime, " +
	                 "entryBufferSize, exitBufferSize, reportingService, reportingChannel, analysisTime) " +
	                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
	    
	    try (Connection conn = getConnection();
	         PreparedStatement pstmt = conn.prepareStatement(sql)) {
	        
	        for (Object obj : paths) {
	            JSONObject path = (JSONObject) obj;
	            pstmt.setLong(1, workflowBase);
	            pstmt.setLong(2, getLongValue(path, "tokenId", 0));
	            pstmt.setString(3, place);
	            pstmt.setLong(4, getLongValue(path, "entryTime", 0));
	            pstmt.setLong(5, getLongValue(path, "exitTime", 0));
	            pstmt.setLong(6, getLongValue(path, "residenceTime", 0));
	            pstmt.setInt(7, getIntValue(path, "entryBufferSize", 0));
	            pstmt.setInt(8, getIntValue(path, "exitBufferSize", 0));
	            pstmt.setString(9, reportingService);
	            pstmt.setString(10, reportingChannel);
	            pstmt.setLong(11, analysisTime);
	            pstmt.executeUpdate();
	            count++;
	        }
	    } catch (SQLException e) {
	        System.err.println("Error writing token paths: " + e.getMessage());
	        e.printStackTrace();
	    }
	    return count;
	}

	private int writeMarkingEvolution(JSONArray markings, long workflowBase, String place,
	                                   String reportingService, String reportingChannel, long analysisTime) {
	    int count = 0;
	    
	    // Ensure new columns exist (migration for existing databases)
	    ensureMarkingEvolutionColumnsExist();
	    
	    String sql = "INSERT INTO " + CONSOLIDATED_MARKING_EVOLUTION_TABLE + 
	                 " (workflowBase, tokenId, timestamp, marking, bufferSize, placeName, " +
	                 "transitionId, toPlace, eventType, " +
	                 "reportingService, reportingChannel, analysisTime) " +
	                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
	    
	    try (Connection conn = getConnection();
	         PreparedStatement pstmt = conn.prepareStatement(sql)) {
	        
	        for (Object obj : markings) {
	            JSONObject marking = (JSONObject) obj;
	            pstmt.setLong(1, workflowBase);
	            pstmt.setLong(2, getLongValue(marking, "tokenId", 0));
	            pstmt.setLong(3, getLongValue(marking, "timestamp", 0));
	            pstmt.setInt(4, getIntValue(marking, "marking", 0));
	            pstmt.setInt(5, getIntValue(marking, "bufferSize", 0));
	            pstmt.setString(6, place);
	            pstmt.setString(7, getStringValue(marking, "transitionId", ""));
	            pstmt.setString(8, getStringValue(marking, "toPlace", ""));
	            pstmt.setString(9, getStringValue(marking, "eventType", ""));
	            pstmt.setString(10, reportingService);
	            pstmt.setString(11, reportingChannel);
	            pstmt.setLong(12, analysisTime);
	            pstmt.executeUpdate();
	            count++;
	        }
	    } catch (SQLException e) {
	        System.err.println("Error writing marking evolution: " + e.getMessage());
	        e.printStackTrace();
	    }
	    return count;
	}

	/**
	 * Ensure transitionId, toPlace, and eventType columns exist in CONSOLIDATED_MARKING_EVOLUTION.
	 * This provides backward compatibility - adds columns if they don't exist.
	 */
	private void ensureMarkingEvolutionColumnsExist() {
	    if (markingEvolutionColumnsChecked) {
	        return;
	    }
	    markingEvolutionColumnsChecked = true;
	    
	    try (Connection conn = getConnection()) {
	        // Try to add transitionId column
	        try (java.sql.Statement stmt = conn.createStatement()) {
	            stmt.execute("ALTER TABLE " + CONSOLIDATED_MARKING_EVOLUTION_TABLE + 
	                       " ADD COLUMN transitionId VARCHAR(100)");
	            System.out.println("Added transitionId column to " + CONSOLIDATED_MARKING_EVOLUTION_TABLE);
	        } catch (SQLException e) {
	            // Column probably already exists - that's fine
	        }
	        
	        // Try to add toPlace column
	        try (java.sql.Statement stmt = conn.createStatement()) {
	            stmt.execute("ALTER TABLE " + CONSOLIDATED_MARKING_EVOLUTION_TABLE + 
	                       " ADD COLUMN toPlace VARCHAR(100)");
	            System.out.println("Added toPlace column to " + CONSOLIDATED_MARKING_EVOLUTION_TABLE);
	        } catch (SQLException e) {
	            // Column probably already exists - that's fine
	        }
	        
	        // Try to add eventType column
	        try (java.sql.Statement stmt = conn.createStatement()) {
	            stmt.execute("ALTER TABLE " + CONSOLIDATED_MARKING_EVOLUTION_TABLE + 
	                       " ADD COLUMN eventType VARCHAR(20)");
	            System.out.println("Added eventType column to " + CONSOLIDATED_MARKING_EVOLUTION_TABLE);
	        } catch (SQLException e) {
	            // Column probably already exists - that's fine
	        }
	    } catch (SQLException e) {
	        System.err.println("Error checking marking evolution columns: " + e.getMessage());
	    }
	}

	private int writePlaceStatistics(JSONArray stats, long workflowBase,
	                                  String reportingService, String reportingChannel, long analysisTime) {
	    int count = 0;
	    String sql = "INSERT INTO " + CONSOLIDATED_PLACE_STATISTICS_TABLE + 
	                 " (workflowBase, placeName, tokenCount, avgResidenceTime, minResidenceTime, " +
	                 "maxResidenceTime, throughput, avgBufferSize, maxBufferSize, " +
	                 "reportingService, reportingChannel, analysisTime) " +
	                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
	    
	    try (Connection conn = getConnection();
	         PreparedStatement pstmt = conn.prepareStatement(sql)) {
	        
	        for (Object obj : stats) {
	            JSONObject stat = (JSONObject) obj;
	            pstmt.setLong(1, workflowBase);
	            pstmt.setString(2, getStringValue(stat, "placeName", ""));
	            pstmt.setInt(3, getIntValue(stat, "tokenCount", 0));
	            pstmt.setDouble(4, getDoubleValue(stat, "avgResidenceTime", 0.0));
	            pstmt.setLong(5, getLongValue(stat, "minResidenceTime", 0));
	            pstmt.setLong(6, getLongValue(stat, "maxResidenceTime", 0));
	            pstmt.setDouble(7, getDoubleValue(stat, "throughput", 0.0));
	            pstmt.setDouble(8, getDoubleValue(stat, "avgBufferSize", 0.0));
	            pstmt.setInt(9, getIntValue(stat, "maxBufferSize", 0));
	            pstmt.setString(10, reportingService);
	            pstmt.setString(11, reportingChannel);
	            pstmt.setLong(12, analysisTime);
	            pstmt.executeUpdate();
	            count++;
	        }
	    } catch (SQLException e) {
	        System.err.println("Error writing place statistics: " + e.getMessage());
	        e.printStackTrace();
	    }
	    return count;
	}

	/**
	 * Write consolidated token genealogy records
	 */
	private int writeTokenGenealogy(JSONArray genealogy, long workflowBase,
	                                String reportingService, String reportingChannel, long analysisTime) {
	    int count = 0;
	    
	    // First ensure the table exists
	    createGenealogyTableIfNotExists();
	    
	    String sql = "INSERT INTO " + CONSOLIDATED_TOKEN_GENEALOGY_TABLE + 
	                 " (workflowBase, parentTokenId, childTokenId, forkTransitionId, forkTimestamp, " +
	                 "reportingService, reportingChannel, analysisTime) " +
	                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
	    
	    try (Connection conn = getConnection();
	         PreparedStatement pstmt = conn.prepareStatement(sql)) {
	        
	        for (Object obj : genealogy) {
	            JSONObject record = (JSONObject) obj;
	            pstmt.setLong(1, workflowBase);
	            pstmt.setLong(2, getLongValue(record, "parentTokenId", 0));
	            pstmt.setLong(3, getLongValue(record, "childTokenId", 0));
	            pstmt.setString(4, getStringValue(record, "forkTransitionId", ""));
	            pstmt.setLong(5, getLongValue(record, "forkTimestamp", 0));
	            pstmt.setString(6, reportingService);
	            pstmt.setString(7, reportingChannel);
	            pstmt.setLong(8, analysisTime);
	            
	            pstmt.executeUpdate();
	            count++;
	        }
	        
	    } catch (SQLException e) {
	        System.err.println("Error writing token genealogy: " + e.getMessage());
	        e.printStackTrace();
	    }
	    
	    System.out.println("Wrote " + count + " genealogy records");
	    return count;
	}

	/**
	 * Create CONSOLIDATED_TOKEN_GENEALOGY table if it doesn't exist
	 */
	private void createGenealogyTableIfNotExists() {
	    String sql = "CREATE TABLE " + CONSOLIDATED_TOKEN_GENEALOGY_TABLE + " (" +
	                 "id INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1), " +
	                 "workflowBase BIGINT, " +
	                 "parentTokenId BIGINT, " +
	                 "childTokenId BIGINT, " +
	                 "forkTransitionId VARCHAR(100), " +
	                 "forkTimestamp BIGINT, " +
	                 "reportingService VARCHAR(100), " +
	                 "reportingChannel VARCHAR(100), " +
	                 "analysisTime BIGINT, " +
	                 "PRIMARY KEY (id))";
	    
	    try (Connection conn = getConnection();
	         java.sql.Statement stmt = conn.createStatement()) {
	        stmt.execute(sql);
	        System.out.println("Created " + CONSOLIDATED_TOKEN_GENEALOGY_TABLE + " table");
	    } catch (SQLException e) {
	        // Table probably already exists - that's fine
	    }
	}

	private double getDoubleValue(JSONObject obj, String key, double defaultValue) {
	    Object value = obj.get(key);
	    if (value != null) {
	        if (value instanceof Double) return (Double) value;
	        if (value instanceof Float) return ((Float) value).doubleValue();
	        if (value instanceof Number) return ((Number) value).doubleValue();
	        try {
	            return Double.parseDouble(value.toString());
	        } catch (NumberFormatException e) {
	            return defaultValue;
	        }
	    }
	    return defaultValue;
	}

	// ============================================================================
	// END OF METHODS TO ADD
	// ============================================================================

	
	// ============================================================================
	/**
	 * NEW: Write marking data record - ENHANCED to handle chunked marking data
	 */
	public String writeMarkingDataRecord(String token) {
		System.out.println("=== PROCESSING MARKING DATA ===");

		try {
			// Parse the incoming JSON payload
			JSONObject markingData = jsonLibrary.parseString(token);

			if (markingData == null) {
				logger.error("Could not parse marking data JSON");
				return "{\"status\":\"error\",\"message\":\"Invalid JSON payload\"}";
			}

			// Check if this is chunked data
			boolean isChunked = markingData.containsKey("chunkIndex");

			if (isChunked) {
				return handleChunkedMarkingData(markingData, getSequenceID());
			} else {
				// Handle legacy single-chunk data
				return handleSingleMarkingChunk(markingData);
			}

		} catch (Exception e) {
			logger.error("Error processing marking data: " + e.getMessage(), e);
			return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
		}
	}

	/**
	 * Handle chunked monitoring data with buffering
	 */
	private String handleChunkedData(JSONObject chunkData, String sequenceID) {
		try {
			int chunkIndex = ((Long) chunkData.get("chunkIndex")).intValue();
			int totalChunks = ((Long) chunkData.get("totalChunks")).intValue();
			boolean isLastChunk = (Boolean) chunkData.get("isLastChunk");
			String reportingService = (String) chunkData.get("reportingService");
			String reportingChannel = (String) chunkData.get("reportingChannel");

			logger.info("Received chunk " + (chunkIndex + 1) + "/" + totalChunks + " from " + reportingService + " on "
					+ reportingChannel + " for sequenceID " + sequenceID);

			// Get or create chunk buffer - INCLUDE SEQUENCE ID FOR UNIQUENESS
			String bufferKey = reportingService + "_" + reportingChannel + "_" + sequenceID;
			ChunkBuffer buffer = getOrCreateChunkBuffer(bufferKey, totalChunks);

			logger.info("Using buffer key: " + bufferKey);
			// Store this chunk
			buffer.addChunk(chunkIndex, chunkData);

			// Check if all chunks have been received
			if (buffer.isComplete()) {
				logger.info("All chunks received for " + reportingService + " (sequenceID: " + sequenceID
						+ ") - processing complete dataset");

				// Merge all chunks and process
				JSONObject mergedData = buffer.mergeChunks();
				String result = handleSingleChunk(mergedData);

				// Clean up buffer
				logger.info("Removing buffer: " + bufferKey);
				removeChunkBuffer(bufferKey);
				logger.info("Buffer removed successfully");

				return result;
			} else {
				logger.info(
						"Chunk " + (chunkIndex + 1) + "/" + totalChunks + " buffered, waiting for remaining chunks");
				return "{\"status\":\"partial\",\"chunksReceived\":" + buffer.getReceivedCount() + ",\"totalChunks\":"
						+ totalChunks + "}";
			}

		} catch (Exception e) {
			logger.error("Error handling chunked data: " + e.getMessage(), e);
			return "{\"status\":\"error\",\"message\":\"Chunk processing failed\"}";
		}
	}

	/**
	 * NEW: Handle chunked marking data with buffering
	 */
	private String handleChunkedMarkingData(JSONObject chunkData, String sequenceID) {
		try {
			int chunkIndex = ((Long) chunkData.get("chunkIndex")).intValue();
			int totalChunks = ((Long) chunkData.get("totalChunks")).intValue();
			boolean isLastChunk = (Boolean) chunkData.get("isLastChunk");
			String reportingService = (String) chunkData.get("reportingService");
			String reportingChannel = (String) chunkData.get("reportingChannel");

			logger.info("Received MARKING chunk " + (chunkIndex + 1) + "/" + totalChunks + " from " + reportingService 
					+ " on " + reportingChannel + " for sequenceID " + sequenceID);

			// Get or create chunk buffer - INCLUDE SEQUENCE ID FOR UNIQUENESS
			String bufferKey = "MARKING_" + reportingService + "_" + reportingChannel + "_" + sequenceID;
			ChunkBuffer buffer = getOrCreateChunkBuffer(bufferKey, totalChunks);

			logger.info("Using marking buffer key: " + bufferKey);
			// Store this chunk
			buffer.addChunk(chunkIndex, chunkData);

			// Check if all chunks have been received
			if (buffer.isComplete()) {
				logger.info("All MARKING chunks received for " + reportingService + " (sequenceID: " + sequenceID
						+ ") - processing complete dataset");

				// Merge all chunks and process
				JSONObject mergedData = buffer.mergeChunks();
				String result = handleSingleMarkingChunk(mergedData);

				// Clean up buffer
				logger.info("Removing marking buffer: " + bufferKey);
				removeChunkBuffer(bufferKey);
				logger.info("Marking buffer removed successfully");

				return result;
			} else {
				logger.info("MARKING chunk " + (chunkIndex + 1) + "/" + totalChunks 
						+ " buffered, waiting for remaining chunks");
				return "{\"status\":\"partial\",\"chunksReceived\":" + buffer.getReceivedCount() + ",\"totalChunks\":"
						+ totalChunks + "}";
			}

		} catch (Exception e) {
			logger.error("Error handling chunked marking data: " + e.getMessage(), e);
			return "{\"status\":\"error\",\"message\":\"Marking chunk processing failed\"}";
		}
	}

	/**
	 * Handle single chunk or merged data - FIXED with deduplication
	 */
	private String handleSingleChunk(JSONObject monitoringData) {
		JSONObject workflowGroups = (JSONObject) monitoringData.get("workflowGroups");
		if (workflowGroups == null) {
			logger.warn("No workflow groups found in monitoring data");
			return "{\"status\":\"no_data\",\"message\":\"No workflow data to process\"}";
		}

		int totalRecordsProcessed = 0;
		long totalQueueTime = 0;
		long totalServiceTime = 0;
		long totalTime = 0;

		for (Object workflowKey : workflowGroups.keySet()) {
			JSONArray workflowMetrics = (JSONArray) workflowGroups.get(workflowKey);

			for (Object metricObj : workflowMetrics) {
				JSONObject metric = (JSONObject) metricObj;

				long sequenceId = (Long) metric.get("sequenceId");
				String serviceName = (String) metric.get("serviceName");
				String operation = (String) metric.get("operation");
				long arrivalTime = (Long) metric.get("arrivalTime");
				long queueTime = (Long) metric.get("queueTime");
				long serviceTime = (Long) metric.get("serviceTime");
				long totalRecordTime = (Long) metric.get("totalTime");
				long workflowStartTime = (Long) metric.get("workflowStartTime");
					int bufferSize = metric.containsKey("bufferSize") ? ((Long) metric.get("bufferSize")).intValue() : 0;
					int maxQueueCapacity = metric.containsKey("maxQueueCapacity") ? ((Long) metric.get("maxQueueCapacity")).intValue() : 0;
					int totalMarking = metric.containsKey("totalMarking") ? ((Long) metric.get("totalMarking")).intValue() : 0;

				try {
					writeServiceContribution((int) sequenceId, serviceName, operation, arrivalTime, queueTime,
							serviceTime, totalRecordTime, workflowStartTime, bufferSize, maxQueueCapacity, totalMarking);

					totalQueueTime += queueTime;
					totalServiceTime += serviceTime;
					totalTime += totalRecordTime;
					totalRecordsProcessed++;

				} catch (SQLException e) {
					logger.error("Failed to write sequenceId " + sequenceId + ": " + e.getMessage(), e);
				}
			}
		}

		return "{\"status\":\"success\",\"recordsProcessed\":" + totalRecordsProcessed + "}";
	}

	/**
	 * NEW: Handle single marking chunk or merged marking data
	 */
	private String handleSingleMarkingChunk(JSONObject markingData) {
		JSONObject workflowGroups = (JSONObject) markingData.get("workflowGroups");
		if (workflowGroups == null) {
			logger.warn("No workflow groups found in marking data");
			return "{\"status\":\"no_data\",\"message\":\"No marking data to process\"}";
		}

		int totalRecordsProcessed = 0;

		for (Object workflowKey : workflowGroups.keySet()) {
			JSONArray workflowMarkings = (JSONArray) workflowGroups.get(workflowKey);

			for (Object markingObj : workflowMarkings) {
				JSONObject marking = (JSONObject) markingObj;

				long sequenceId = (Long) marking.get("sequenceId");
				String serviceName = (String) marking.get("serviceName");
				String operation = (String) marking.get("operation");
				long arrivalTime = (Long) marking.get("arrivalTime");
				long invocationTime = (Long) marking.get("invocationTime");
				long publishTime = (Long) marking.get("publishTime");
				long workflowStartTime = (Long) marking.get("workflowStartTime");
				int bufferSize = ((Long) marking.get("bufferSize")).intValue();
				int maxQueueCapacity = ((Long) marking.get("maxQueueCapacity")).intValue();
				int totalMarking = ((Long) marking.get("totalMarking")).intValue();

				try {
					writeMarkingRecord((int) sequenceId, serviceName, operation, arrivalTime, invocationTime,
							publishTime, workflowStartTime, bufferSize, maxQueueCapacity, totalMarking);

					totalRecordsProcessed++;

				} catch (SQLException e) {
					logger.error("Failed to write marking for sequenceId " + sequenceId + ": " + e.getMessage(), e);
				}
			}
		}

		return "{\"status\":\"success\",\"markingRecordsProcessed\":" + totalRecordsProcessed + "}";
	}
	
	
	// ============================================================================
	// ADD THIS NEW METHOD AFTER writeMarkingDataRecord() method in MonitorService.java
	// ============================================================================

	/**
	 * NEW: Write Petri Net data record - ENHANCED to handle chunked Petri Net data
	 * Processes transition firings, token paths, marking evolution, and statistics
	 */
	public String writePetriNetDataRecord(String token) {
	    System.out.println("=== PROCESSING PETRI NET DATA ===");

	    try {
	        // Parse the incoming JSON payload
	        JSONObject petriNetData = jsonLibrary.parseString(token);

	        if (petriNetData == null) {
	            logger.error("Could not parse Petri Net data JSON");
	            return "{\"status\":\"error\",\"message\":\"Invalid JSON payload\"}";
	        }

	        // Check if this is chunked data
	        boolean isChunked = petriNetData.containsKey("chunkIndex");

	        if (isChunked) {
	            return handleChunkedPetriNetData(petriNetData, getSequenceID());
	        } else {
	            // Handle legacy single-chunk data
	            return handleSinglePetriNetChunk(petriNetData);
	        }

	    } catch (Exception e) {
	        logger.error("Error processing Petri Net data: " + e.getMessage(), e);
	        return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
	    }
	}

	/**
	 * Handle chunked Petri Net data with buffering
	 */
	private String handleChunkedPetriNetData(JSONObject chunkData, String sequenceID) {
	    try {
	        int chunkIndex = ((Long) chunkData.get("chunkIndex")).intValue();
	        int totalChunks = ((Long) chunkData.get("totalChunks")).intValue();
	        boolean isLastChunk = (Boolean) chunkData.get("isLastChunk");
	        String reportingService = (String) chunkData.get("reportingService");
	        String reportingChannel = (String) chunkData.get("reportingChannel");

	        logger.info("Received PETRI NET chunk " + (chunkIndex + 1) + "/" + totalChunks + " from " 
	                + reportingService + " on " + reportingChannel + " for sequenceID " + sequenceID);

	        // Get or create chunk buffer - INCLUDE SEQUENCE ID FOR UNIQUENESS
	        String bufferKey = "PETRINET_" + reportingService + "_" + reportingChannel + "_" + sequenceID;
	        ChunkBuffer buffer = getOrCreateChunkBuffer(bufferKey, totalChunks);

	        logger.info("Using Petri Net buffer key: " + bufferKey);
	        // Store this chunk
	        buffer.addChunk(chunkIndex, chunkData);

	        // Check if all chunks have been received
	        if (buffer.isComplete()) {
	            logger.info("All PETRI NET chunks received for " + reportingService 
	                    + " (sequenceID: " + sequenceID + ") - processing complete dataset");

	            // Merge all chunks and process
	            JSONObject mergedData = buffer.mergeChunks();
	            String result = handleSinglePetriNetChunk(mergedData);

	            // Clean up buffer
	            logger.info("Removing Petri Net buffer: " + bufferKey);
	            removeChunkBuffer(bufferKey);
	            logger.info("Petri Net buffer removed successfully");

	            return result;
	        } else {
	            logger.info("PETRI NET chunk " + (chunkIndex + 1) + "/" + totalChunks 
	                    + " buffered, waiting for remaining chunks");
	            return "{\"status\":\"partial\",\"chunksReceived\":" + buffer.getReceivedCount() 
	                    + ",\"totalChunks\":" + totalChunks + "}";
	        }

	    } catch (Exception e) {
	        logger.error("Error handling chunked Petri Net data: " + e.getMessage(), e);
	        return "{\"status\":\"error\",\"message\":\"Petri Net chunk processing failed\"}";
	    }
	}

	/**
	 * Handle single chunk or merged Petri Net data
	 */
	private String handleSinglePetriNetChunk(JSONObject petriNetData) {
	    String reportingService = (String) petriNetData.get("reportingService");
	    String reportingChannel = (String) petriNetData.get("reportingChannel");
	    
	    JSONObject workflowGroups = (JSONObject) petriNetData.get("workflowGroups");
	    if (workflowGroups == null) {
	        logger.warn("No workflowGroups in Petri Net data");
	        return "{\"status\":\"error\",\"message\":\"Missing workflowGroups\"}";
	    }

	    int totalRecords = 0;
	    Set<Long> processedSequenceIds = new HashSet<>();

	    // Process each workflow group
	    for (Object workflowKey : workflowGroups.keySet()) {
	        String workflowBaseStr = (String) workflowKey;
	        JSONObject workflowData = (JSONObject) workflowGroups.get(workflowKey);
	        
	        // Process transition firings
	        JSONArray firings = (JSONArray) workflowData.get("transitionFirings");
	        if (firings != null) {
	            for (Object obj : firings) {
	                JSONObject firing = (JSONObject) obj;
	                try {
	                    Long tokenId = (Long) firing.get("tokenId");
	                    if (!processedSequenceIds.contains(tokenId)) {
	                        writeConsolidatedTransitionFiring(firing, reportingService, reportingChannel);
	                        processedSequenceIds.add(tokenId);
	                        totalRecords++;
	                    }
	                } catch (SQLException e) {
	                    logger.error("Error writing transition firing: " + e.getMessage());
	                }
	            }
	        }
	        
	        // Process token paths
	        JSONArray paths = (JSONArray) workflowData.get("tokenPaths");
	        if (paths != null) {
	            for (Object obj : paths) {
	                JSONObject path = (JSONObject) obj;
	                try {
	                    writeConsolidatedTokenPath(path, reportingService, reportingChannel);
	                    totalRecords++;
	                } catch (SQLException e) {
	                    logger.error("Error writing token path: " + e.getMessage());
	                }
	            }
	        }
	        
	        // Process marking evolution
	        JSONArray markings = (JSONArray) workflowData.get("markingEvolution");
	        if (markings != null) {
	            for (Object obj : markings) {
	                JSONObject marking = (JSONObject) obj;
	                try {
	                    writeConsolidatedMarkingEvolution(marking, reportingService, reportingChannel);
	                    totalRecords++;
	                } catch (SQLException e) {
	                    logger.error("Error writing marking evolution: " + e.getMessage());
	                }
	            }
	        }
	        
	        // Process statistics
	        JSONArray stats = (JSONArray) workflowData.get("statistics");
	        if (stats != null) {
	            for (Object obj : stats) {
	                JSONObject stat = (JSONObject) obj;
	                try {
	                    writeConsolidatedPlaceStatistics(stat, reportingService, reportingChannel);
	                    totalRecords++;
	                } catch (SQLException e) {
	                    logger.error("Error writing place statistics: " + e.getMessage());
	                }
	            }
	        }
	    }

	    logger.info("Processed " + totalRecords + " Petri Net records from " 
	            + reportingService + " on " + reportingChannel);
	    
	    return "{\"status\":\"success\",\"recordsProcessed\":" + totalRecords + "}";
	}

	// ============================================================================
	// ADD THESE DATABASE WRITE METHODS (before the existing getConnection() method)
	// ============================================================================

	/**
	 * Write consolidated transition firing record to database
	 */
	private void writeConsolidatedTransitionFiring(JSONObject firing, String reportingService, 
	        String reportingChannel) throws SQLException {
	    
	    String sql = "INSERT INTO " + CONSOLIDATED_TRANSITION_FIRINGS_TABLE + " "
	            + "(workflowBase, tokenId, transitionId, timestamp, toPlace, fromPlace, "
	            + "bufferSize, placeName, reportingService, reportingChannel, analysisTime) "
	            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

	    try (Connection connection = getConnection(); 
	         PreparedStatement pstmt = connection.prepareStatement(sql)) {

	        pstmt.setLong(1, (Long) firing.get("workflowBase"));
	        pstmt.setLong(2, (Long) firing.get("tokenId"));
	        pstmt.setString(3, (String) firing.get("transitionId"));
	        pstmt.setLong(4, (Long) firing.get("timestamp"));
	        pstmt.setString(5, (String) firing.get("toPlace"));
	        pstmt.setString(6, (String) firing.get("fromPlace"));
	        pstmt.setInt(7, ((Long) firing.get("bufferSize")).intValue());
	        pstmt.setString(8, (String) firing.get("placeName"));
	        pstmt.setString(9, reportingService);
	        pstmt.setString(10, reportingChannel);
	        pstmt.setLong(11, System.currentTimeMillis());

	        pstmt.executeUpdate();
	        
	        logger.info("Wrote transition firing: token=" + firing.get("tokenId") 
	                + " transition=" + firing.get("transitionId") + " from " + reportingService);
	    }
	}

	/**
	 * Write consolidated token path record to database
	 */
	private void writeConsolidatedTokenPath(JSONObject path, String reportingService, 
	        String reportingChannel) throws SQLException {
	    
	    String sql = "INSERT INTO " + CONSOLIDATED_TOKEN_PATHS_TABLE + " "
	            + "(workflowBase, tokenId, placeName, entryTime, exitTime, residenceTime, "
	            + "entryBufferSize, exitBufferSize, reportingService, reportingChannel, analysisTime) "
	            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

	    try (Connection connection = getConnection(); 
	         PreparedStatement pstmt = connection.prepareStatement(sql)) {

	        pstmt.setLong(1, (Long) path.get("workflowBase"));
	        pstmt.setLong(2, (Long) path.get("tokenId"));
	        pstmt.setString(3, (String) path.get("placeName"));
	        pstmt.setLong(4, (Long) path.get("entryTime"));
	        pstmt.setLong(5, (Long) path.get("exitTime"));
	        pstmt.setLong(6, (Long) path.get("residenceTime"));
	        pstmt.setInt(7, ((Long) path.get("entryBufferSize")).intValue());
	        pstmt.setInt(8, ((Long) path.get("exitBufferSize")).intValue());
	        pstmt.setString(9, reportingService);
	        pstmt.setString(10, reportingChannel);
	        pstmt.setLong(11, System.currentTimeMillis());

	        pstmt.executeUpdate();
	        
	        logger.info("Wrote token path: token=" + path.get("tokenId") 
	                + " place=" + path.get("placeName") + " from " + reportingService);
	    }
	}

	/**
	 * Write consolidated marking evolution record to database
	 */
	private void writeConsolidatedMarkingEvolution(JSONObject marking, String reportingService, 
	        String reportingChannel) throws SQLException {
	    
	    String sql = "INSERT INTO " + CONSOLIDATED_MARKING_EVOLUTION_TABLE + " "
	            + "(workflowBase, tokenId, timestamp, marking, bufferSize, placeName, "
	            + "reportingService, reportingChannel, analysisTime) "
	            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

	    try (Connection connection = getConnection(); 
	         PreparedStatement pstmt = connection.prepareStatement(sql)) {

	        pstmt.setLong(1, (Long) marking.get("workflowBase"));
	        pstmt.setLong(2, (Long) marking.get("tokenId"));
	        pstmt.setLong(3, (Long) marking.get("timestamp"));
	        pstmt.setInt(4, ((Long) marking.get("marking")).intValue());
	        pstmt.setInt(5, ((Long) marking.get("bufferSize")).intValue());
	        pstmt.setString(6, (String) marking.get("placeName"));
	        pstmt.setString(7, reportingService);
	        pstmt.setString(8, reportingChannel);
	        pstmt.setLong(9, System.currentTimeMillis());

	        pstmt.executeUpdate();
	        
	        logger.info("Wrote marking evolution: token=" + marking.get("tokenId") 
	                + " marking=" + marking.get("marking") + " from " + reportingService);
	    }
	}

	/**
	 * Write consolidated place statistics record to database
	 */
	private void writeConsolidatedPlaceStatistics(JSONObject stats, String reportingService, 
	        String reportingChannel) throws SQLException {
	    
	    String sql = "INSERT INTO " + CONSOLIDATED_PLACE_STATISTICS_TABLE + " "
	            + "(workflowBase, placeName, tokenCount, avgResidenceTime, minResidenceTime, "
	            + "maxResidenceTime, throughput, avgBufferSize, maxBufferSize, "
	            + "reportingService, reportingChannel, analysisTime) "
	            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

	    try (Connection connection = getConnection(); 
	         PreparedStatement pstmt = connection.prepareStatement(sql)) {

	        pstmt.setLong(1, (Long) stats.get("workflowBase"));
	        pstmt.setString(2, (String) stats.get("placeName"));
	        pstmt.setInt(3, ((Long) stats.get("tokenCount")).intValue());
	        pstmt.setDouble(4, (Double) stats.get("avgResidenceTime"));
	        pstmt.setLong(5, (Long) stats.get("minResidenceTime"));
	        pstmt.setLong(6, (Long) stats.get("maxResidenceTime"));
	        pstmt.setDouble(7, (Double) stats.get("throughput"));
	        pstmt.setDouble(8, (Double) stats.get("avgBufferSize"));
	        pstmt.setInt(9, ((Long) stats.get("maxBufferSize")).intValue());
	        pstmt.setString(10, reportingService);
	        pstmt.setString(11, reportingChannel);
	        pstmt.setLong(12, System.currentTimeMillis());

	        pstmt.executeUpdate();
	        
	        logger.info("Wrote place statistics: place=" + stats.get("placeName") 
	                + " tokens=" + stats.get("tokenCount") + " from " + reportingService);
	    }
	}

	
	// === CHUNK BUFFERING SUPPORT ===

	private static final Map<String, ChunkBuffer> chunkBuffers = new ConcurrentHashMap<>();
	private static final long BUFFER_TIMEOUT_MS = 60000; // 1 minute timeout

	private ChunkBuffer getOrCreateChunkBuffer(String key, int totalChunks) {
		return chunkBuffers.computeIfAbsent(key, k -> new ChunkBuffer(totalChunks));
	}

	private void removeChunkBuffer(String key) {
		chunkBuffers.remove(key);
	}

	/**
	 * Buffer for collecting chunks - ENHANCED with deduplication in merge
	 */
	private static class ChunkBuffer {
		private final JSONObject[] chunks;
		private final boolean[] received;
		private final int totalChunks;
		private final long createdTime;
		private int receivedCount = 0;

		public ChunkBuffer(int totalChunks) {
			this.totalChunks = totalChunks;
			this.chunks = new JSONObject[totalChunks];
			this.received = new boolean[totalChunks];
			this.createdTime = System.currentTimeMillis();
		}

		public synchronized void addChunk(int index, JSONObject chunk) {
			if (index >= 0 && index < totalChunks && !received[index]) {
				chunks[index] = chunk;
				received[index] = true;
				receivedCount++;
			}
		}

		public synchronized boolean isComplete() {
			return receivedCount == totalChunks;
		}

		public synchronized int getReceivedCount() {
			return receivedCount;
		}

		public synchronized boolean isExpired() {
			return (System.currentTimeMillis() - createdTime) > BUFFER_TIMEOUT_MS;
		}

		/**
		 * Merge chunks with deduplication at the chunk level
		 */
		public synchronized JSONObject mergeChunks() {
			JSONObject merged = new JSONObject();
			JSONObject mergedWorkflowGroups = new JSONObject();
			Set<Long> seenSequenceIds = new HashSet<>();

			// Use metadata from first chunk
			if (chunks[0] != null) {
				merged.put("reportingService", chunks[0].get("reportingService"));
				merged.put("reportingChannel", chunks[0].get("reportingChannel"));
				merged.put("collectionTime", chunks[0].get("collectionTime"));
			}

			// Merge workflow groups from all chunks with deduplication
			for (int i = 0; i < totalChunks; i++) {
				if (chunks[i] != null) {
					JSONObject workflowGroups = (JSONObject) chunks[i].get("workflowGroups");
					if (workflowGroups != null) {
						for (Object key : workflowGroups.keySet()) {
							JSONArray metrics = (JSONArray) workflowGroups.get(key);

							// Get or create the workflow group in merged data
							JSONArray mergedMetrics;
							if (mergedWorkflowGroups.containsKey(key)) {
								mergedMetrics = (JSONArray) mergedWorkflowGroups.get(key);
							} else {
								mergedMetrics = new JSONArray();
								mergedWorkflowGroups.put(key, mergedMetrics);
							}

							// Add only unique records based on sequenceId
							for (Object metricObj : metrics) {
								JSONObject metric = (JSONObject) metricObj;
								Long sequenceId = (Long) metric.get("sequenceId");

								if (sequenceId != null && !seenSequenceIds.contains(sequenceId)) {
									mergedMetrics.add(metric);
									seenSequenceIds.add(sequenceId);
								}
							}
						}
					}
				}
			}

			merged.put("workflowGroups", mergedWorkflowGroups);

			// Calculate total record count
			int totalRecords = 0;
			for (Object key : mergedWorkflowGroups.keySet()) {
				JSONArray metrics = (JSONArray) mergedWorkflowGroups.get(key);
				totalRecords += metrics.size();
			}
			merged.put("recordCount", totalRecords);

			return merged;
		}
	}

	// Periodic cleanup of expired buffers (call this from a scheduled task)
	public void cleanupExpiredBuffers() {
		chunkBuffers.entrySet().removeIf(entry -> entry.getValue().isExpired());
	}

	// === DATA CLASSES ===

	/**
	 * Represents a completed workflow from PROCESSMEASUREMENTS
	 */
	private static class CompletedWorkflow {
		long sequenceId;
		String serviceName;
		String operation;
		long tokenArrivalTime;
		long workflowStartTime;
		long elapsedTime;
	}

	
	/**
	 * Simple acknowledgement method - UPDATED to return JSON and use VersionConstants
	 * 
	 * NOTE: The workflow_start_time extraction here often fails because ServiceHelper
	 * strips metadata before calling this method. This is OK - ServiceThread captures
	 * workflow_start_time separately before the clean operation.
	 */
	public String acknowledgeTokenArrival(String token) {
	    logger.info("Token acknowledged for sequence: " + getSequenceID());

	    try {
	        int sequenceId = Integer.parseInt(getSequenceID());

	        // BLOCK PRIMING RECORDS - don't write to PROCESSMEASUREMENTS
	        if (sequenceId == PRIMING_ID) {
	            logger.debug("Skipping PROCESSMEASUREMENTS write for priming event - sequenceID: " + sequenceId);
	            return "{\"status\":\"acknowledged\",\"type\":\"priming\",\"sequenceId\":" + sequenceId + "}";
	        }

	        Long workflowStartTime = extractWorkflowStartTime(token);
	        if (workflowStartTime != null) {
	            long now = System.currentTimeMillis();
	            long elapsed = now - workflowStartTime;

	            // Use VersionConstants to determine version based on sequenceID
	            String versionServiceName = VersionConstants.getVersionFromSequenceId(sequenceId);

	            // Add logging before write
	            logger.info("Writing to PROCESSMEASUREMENTS: sequenceID=" + getSequenceID() + ", serviceName="
	                    + versionServiceName + ", elapsed=" + elapsed + "ms");

	            writeProcessMeasurement(getSequenceID(), versionServiceName, "-", now, workflowStartTime, elapsed);

	            logger.info("Successfully wrote to PROCESSMEASUREMENTS table");
	            return "{\"status\":\"acknowledged\",\"sequenceId\":" + sequenceId + 
	                   ",\"elapsed_ms\":" + elapsed + ",\"version\":\"" + versionServiceName + "\"}";
	        } else {
	            // NOTE: This is expected when ServiceHelper has cleaned the token.
	            // ServiceThread captures workflow_start_time separately, so timing still works.
	            logger.debug("workflow_start_time not in cleaned token (normal - ServiceThread captures it separately)");
	        }
	    } catch (SQLException e) {
	        logger.error("Database error in acknowledgement: " + e.getMessage(), e);
	        return "{\"status\":\"error\",\"message\":\"Database error: " + e.getMessage().replace("\"", "'") + "\"}";
	    } catch (Exception e) {
	        logger.error("Error in acknowledgement: " + e.getMessage(), e);
	        return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
	    }

	    return "{\"status\":\"acknowledged\",\"sequenceId\":\"" + getSequenceID() + "\"}";
	}

	// === DATABASE METHODS ===

	/**
	 * Write service contribution to database - FIXED with correct workflow base
	 * calculation
	 */
	private void writeServiceContribution(int sequenceID, String serviceName, String operation, long arrivalTime,
			long queueTime, long serviceTime, long totalTime, long workflowStartTime,
			int bufferSize, int maxQueueCapacity, int totalMarking) throws SQLException {

		String sql = "INSERT INTO " + SERVICE_CONTRIBUTION_TABLE
				+ " (workflowBase, sequenceID, serviceName, operation, arrivalTime, queueTime, serviceTime, totalTime, "
				+ "contributionPercent, workflowStartTime, analysisTime, bufferSize, maxQueueCapacity, totalMarking) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

		try (Connection connection = getConnection(); PreparedStatement pstmt = connection.prepareStatement(sql)) {

			int workflowBase = calculateWorkflowBase(sequenceID);
			
			// Use version instead of serviceName for consistency
			String version = VersionConstants.getVersionFromSequenceId(sequenceID);

			pstmt.setLong(1, workflowBase);
			pstmt.setLong(2, sequenceID);
			pstmt.setString(3, version);  // Write version instead of serviceName
			pstmt.setString(4, operation);
			pstmt.setLong(5, arrivalTime);
			pstmt.setLong(6, queueTime);
			pstmt.setLong(7, serviceTime);
			pstmt.setLong(8, totalTime);
			pstmt.setDouble(9, 0.0);
			pstmt.setLong(10, workflowStartTime);
			pstmt.setLong(11, System.currentTimeMillis());
			pstmt.setInt(12, bufferSize);
			pstmt.setInt(13, maxQueueCapacity);
			pstmt.setInt(14, totalMarking);

			pstmt.executeUpdate();

			logger.info("Wrote contribution: seq=" + sequenceID + " -> workflowBase=" + workflowBase + " ("
					+ version + ": arrival=" + arrivalTime + "ms, queue=" + queueTime + "ms, service=" + serviceTime
					+ "ms)");
		}
	}

	/**
	 * NEW: Write marking record to database
	 */
	private void writeMarkingRecord(int sequenceID, String serviceName, String operation, long arrivalTime,
			long invocationTime, long publishTime, long workflowStartTime, int bufferSize, 
			int maxQueueCapacity, int totalMarking) throws SQLException {

		String sql = "INSERT INTO " + MARKINGS_TABLE
				+ " (workflowBase, sequenceID, serviceName, operation, arrivalTime, invocationTime, publishTime, "
				+ "workflowStartTime, bufferSize, maxQueueCapacity, totalMarking, analysisTime) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

		try (Connection connection = getConnection(); PreparedStatement pstmt = connection.prepareStatement(sql)) {

			int workflowBase = calculateWorkflowBase(sequenceID);
			
			// Use version instead of serviceName for consistency
			String version = VersionConstants.getVersionFromSequenceId(sequenceID);

			pstmt.setLong(1, workflowBase);
			pstmt.setLong(2, sequenceID);
			pstmt.setString(3, version);  // Write version instead of serviceName
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

			logger.info("Wrote marking: seq=" + sequenceID + " -> workflowBase=" + workflowBase + " ("
					+ version + ": buffer=" + bufferSize + ", total=" + totalMarking + 
					", capacity=" + maxQueueCapacity + ")");
		}
	}

	private void writeProcessMeasurement(String sequenceID, String serviceName, String operation, long tokenArrivalTime,
			long workflowStartTime, long elapsedTime) throws SQLException {

		String sql = "INSERT INTO " + PROCESS_MEASUREMENTS_TABLE
				+ " (sequenceID, serviceName, operation, tokenArrivalTime, workflowStartTime, elapsedTime) "
				+ "VALUES (?, ?, ?, ?, ?, ?)";

		try (Connection connection = getConnection(); PreparedStatement pstmt = connection.prepareStatement(sql)) {

			pstmt.setLong(1, Long.parseLong(sequenceID));
			pstmt.setString(2, serviceName);
			pstmt.setString(3, operation);
			pstmt.setLong(4, tokenArrivalTime);
			pstmt.setLong(5, workflowStartTime);
			pstmt.setLong(6, elapsedTime);

			pstmt.executeUpdate();
		}
	}

	private Connection getConnection() throws SQLException {
		return DriverManager.getConnection(DB_URL);
	}

	private Long extractWorkflowStartTime(String token) {
		String pattern = "\"workflow_start_time\":";
		int startIndex = token.indexOf(pattern);
		if (startIndex >= 0) {
			startIndex += pattern.length();
			while (startIndex < token.length() && Character.isWhitespace(token.charAt(startIndex))) {
				startIndex++;
			}

			int endIndex = startIndex;
			while (endIndex < token.length() && Character.isDigit(token.charAt(endIndex))) {
				endIndex++;
			}

			if (endIndex > startIndex) {
				return Long.parseLong(token.substring(startIndex, endIndex));
			}
		}
		return null;
	}


	protected String getServiceResultsKey() {
		return "monitorResults";
	}

	protected String getCompletionStatus() {
		return "MONITOR_COMPLETE";
	}

	
    // Getters for protected access
    public String getSequenceID() { return sequenceID; }
}