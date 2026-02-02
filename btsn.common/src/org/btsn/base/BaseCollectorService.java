package org.btsn.base;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import org.apache.derby.jdbc.EmbeddedDriver;
import org.btsn.utils.OOjdrewAPI;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Base class for all Collector Services
 * Provides TWO unified collection methods:
 * 1. getPerformanceData() - collects timing and marking data
 * 2. getPetriNetData() - collects all Petri Net analysis data
 * 
 * MULTI-VERSION SUPPORT: Token can specify multiple versions as comma-separated list
 * Example: workflow_100000_v001,v002,v003
 * 
 * FULLY ORCHESTRATED: Returns data, orchestrator handles routing to Monitor_Place
 */
public abstract class BaseCollectorService {

    // Database constants
    private static final String PROTOCOL = "jdbc:derby:";
    private static final String DB_NAME = "./ServiceAnalysisDataBase";
    private static final String SERVICE_MEASUREMENTS_TABLE = "SERVICEMEASUREMENTS";
    private static final String TRANSITION_FIRINGS_TABLE = "TRANSITION_FIRINGS";
    
    // Monitor service configuration (resolved from rule base)
    private String monitorHost = null;
    private int monitorPort = -1;
    private boolean monitorConfigResolved = false;
    
    // Instance variables
    protected final String serviceContext;
    protected final String placeName;
    private String currentRuleVersion = "v001";  // Workflow version from token
    private String buildVersion;                 // Build version from service deployment
    
    // OOjDREW API for rule queries
    private static final OOjdrewAPI oojdrew = new OOjdrewAPI();
    
    static {
        try {
            DriverManager.registerDriver(new EmbeddedDriver());
        } catch (SQLException e) {
            System.err.println("ERROR: Failed to register Derby driver: " + e.getMessage());
        }
    }

    /**
     * Constructor - buildVersion must match the version ServiceLoader used to deploy this service
     * @param context Service context
     * @param placeName Place name being monitored
     * @param buildVersion The version this service was built/deployed with (from ServiceLoader)
     */
    public BaseCollectorService(String context, String placeName, String buildVersion) {
        this.serviceContext = context;
        this.placeName = placeName;
        this.buildVersion = buildVersion;
        System.out.println(getCollectorName() + " initialized with context: " + context + 
                         ", place: " + placeName + ", buildVersion: " + buildVersion);
    }
    
    /**
     * Set the build version if it needs to be updated after construction
     * This should match the version the service was deployed with
     */
    public void setBuildVersion(String version) {
        this.buildVersion = version;
        System.out.println(getCollectorName() + " build version set to: " + version);
        // Reset monitor config so it gets resolved again with new version
        this.monitorConfigResolved = false;
    }

    protected abstract String getCollectorName();
    protected abstract String getMonitoredPlaceName();

    // =============================================================================
    // TWO PUBLIC COLLECTION METHODS
    // =============================================================================

    /**
     * Collect ALL Performance Data (timing + marking)
     * Returns JSON with both data types
     * Supports multiple versions: workflow_100000_v001,v002,v003
     */
    public String getPerformanceData(String token) {
        try {
            System.out.println("=== " + getCollectorName() + ": PERFORMANCE DATA COLLECTION ===");
            System.out.println("Raw token received: " + token);
            
            List<String> requestedVersions = parseVersionsFromToken(token);
            this.currentRuleVersion = requestedVersions.get(0); // Use first version for compatibility
            System.out.println("Successfully parsed version(s): " + requestedVersions);
            
            // Collect timing and marking data for ALL requested versions
            List<ServiceTiming> timingData = new ArrayList<>();
            List<ServiceMarking> markingData = new ArrayList<>();
            
            for (String version : requestedVersions) {
                System.out.println(getCollectorName() + ": Collecting data for version: " + version);
                
                List<ServiceTiming> versionTimingData = readServiceTimingDataByVersion(version);
                List<ServiceMarking> versionMarkingData = readServiceMarkingDataByVersion(version);
                
                System.out.println(getCollectorName() + ":   " + version + " - " + versionTimingData.size() + " timing records");
                System.out.println(getCollectorName() + ":   " + version + " - " + versionMarkingData.size() + " marking records");
                
                timingData.addAll(versionTimingData);
                markingData.addAll(versionMarkingData);
            }
            
            System.out.println(getCollectorName() + ": TOTAL " + timingData.size() + " timing records across " + requestedVersions.size() + " version(s)");
            System.out.println(getCollectorName() + ": TOTAL " + markingData.size() + " marking records across " + requestedVersions.size() + " version(s)");
            
            // Build combined JSON response
            String jsonResponse = buildPerformanceDataResponse(timingData, markingData, token);
            System.out.println(getCollectorName() + ": Generated response (" + jsonResponse.length() + " chars)");
            
            
            return jsonResponse;
            
        } catch (InvalidTokenException e) {
            return handleTokenError(e, token);
        } catch (Exception e) {
            return handleGeneralError(e);
        }
    }

    /**
     * Collect ALL Petri Net Data (transition firings, paths, marking evolution, statistics)
     * Returns JSON with all 4 data types
     * Supports multiple versions: workflow_100000_v001,v002,v003
     */
    public String getPetriNetData(String token) {
        try {
            System.out.println("=== " + getCollectorName() + ": PETRI NET DATA COLLECTION ===");
            System.out.println("Raw token received: " + token);
            
            List<String> requestedVersions = parseVersionsFromToken(token);
            this.currentRuleVersion = requestedVersions.get(0); // Use first version for compatibility
            System.out.println("Successfully parsed version(s): " + requestedVersions);
            
            // Collect all 5 Petri Net data types for ALL requested versions
            List<TransitionFiring> firingData = new ArrayList<>();
            List<TokenPath> pathData = new ArrayList<>();
            List<MarkingSnapshot> markingData = new ArrayList<>();
            List<PlaceStatistics> statsData = new ArrayList<>();
            List<TokenGenealogy> genealogyData = new ArrayList<>();
            
            for (String version : requestedVersions) {
                System.out.println(getCollectorName() + ": Collecting Petri Net data for version: " + version);
                
                List<TransitionFiring> versionFiringData = readTransitionFiringsForPlace(version);
                List<TokenPath> versionPathData = readTokenPathsForPlace(version);
                List<MarkingSnapshot> versionMarkingData = readMarkingEvolutionForPlace(version);
                List<PlaceStatistics> versionStatsData = computePlaceStatistics(version);
                List<TokenGenealogy> versionGenealogyData = readTokenGenealogyForPlace(version);
                
                System.out.println(getCollectorName() + ":   " + version + " - " + versionFiringData.size() + " transition firings");
                System.out.println(getCollectorName() + ":   " + version + " - " + versionPathData.size() + " token paths");
                System.out.println(getCollectorName() + ":   " + version + " - " + versionMarkingData.size() + " marking snapshots");
                System.out.println(getCollectorName() + ":   " + version + " - " + versionStatsData.size() + " statistics records");
                System.out.println(getCollectorName() + ":   " + version + " - " + versionGenealogyData.size() + " genealogy records");
                
                firingData.addAll(versionFiringData);
                pathData.addAll(versionPathData);
                markingData.addAll(versionMarkingData);
                statsData.addAll(versionStatsData);
                genealogyData.addAll(versionGenealogyData);
            }
            
            System.out.println(getCollectorName() + ": TOTAL " + firingData.size() + " transition firing records across " + requestedVersions.size() + " version(s)");
            System.out.println(getCollectorName() + ": TOTAL " + pathData.size() + " token path records across " + requestedVersions.size() + " version(s)");
            System.out.println(getCollectorName() + ": TOTAL " + markingData.size() + " marking snapshot records across " + requestedVersions.size() + " version(s)");
            System.out.println(getCollectorName() + ": TOTAL " + statsData.size() + " statistics records across " + requestedVersions.size() + " version(s)");
            System.out.println(getCollectorName() + ": TOTAL " + genealogyData.size() + " genealogy records across " + requestedVersions.size() + " version(s)");
            
            // Build combined JSON response
            String jsonResponse = buildPetriNetDataResponse(firingData, pathData, markingData, statsData, genealogyData, token);
            System.out.println(getCollectorName() + ": Generated response (" + jsonResponse.length() + " chars)");
            
            
            return jsonResponse;
            
        } catch (InvalidTokenException e) {
            return handleTokenError(e, token);
        } catch (Exception e) {
            return handleGeneralError(e);
        }
    }

    // =============================================================================
    // DATABASE READ METHODS - PERFORMANCE DATA
    // =============================================================================

    private List<ServiceTiming> readServiceTimingDataByVersion(String version) {
        List<ServiceTiming> timings = new ArrayList<>();
        
        // CORRECTED: Query actual columns from SERVICEMEASUREMENTS table
        // Calculate derived metrics (queueTime, serviceTime, totalTime) from timestamps
        String sql = 
            "SELECT sequenceID, serviceName, operation, " +
            "       arrivalTime, invocationTime, publishTime, " +
            "       workflowStartTime, bufferSize, maxQueueCapacity, totalMarking " +
            "FROM " + SERVICE_MEASUREMENTS_TABLE + " " +
            "WHERE serviceName = ? " +
            "ORDER BY workflowStartTime, sequenceID";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, placeName);
            
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                ServiceTiming timing = new ServiceTiming();
                timing.sequenceId = rs.getLong("sequenceID");
                timing.serviceName = rs.getString("serviceName");
                timing.operation = rs.getString("operation");
                
                // Get raw timestamps
                long arrival = rs.getLong("arrivalTime");
                long invocation = rs.getLong("invocationTime");
                long publish = rs.getLong("publishTime");
                
                timing.arrivalTime = arrival;
                timing.workflowStartTime = rs.getLong("workflowStartTime");
                timing.bufferSize = rs.getInt("bufferSize");
                timing.maxQueueCapacity = rs.getInt("maxQueueCapacity");
                timing.totalMarking = rs.getInt("totalMarking");
                
                // Calculate derived timing metrics from raw timestamps
                timing.queueTime = invocation - arrival;        // Time in queue
                timing.serviceTime = publish - invocation;      // Processing time
                timing.totalTime = publish - arrival;           // Total time
                
                timings.add(timing);
            }
            
        } catch (SQLException e) {
            System.err.println("Error reading timing data: " + e.getMessage());
            e.printStackTrace();
        }
        
        return timings;
    }
    
 // ============================================================================
 // ADD THIS METHOD TO: BaseCollectorService.java
 // Location: org.btsn.common.base.BaseCollectorService
 // ============================================================================

 // ADD THESE IMPORTS AT THE TOP OF YOUR FILE:
 // import org.json.simple.JSONObject;
 // import org.json.simple.JSONArray;
 // import org.json.simple.parser.JSONParser;

 /**
  * Unified data collection - returns ALL data in one JSON response
  * This eliminates routing ambiguity by having a single method with a single destination
  * 
  * Collects:
  * - Performance Data (timing metrics + marking snapshots)
  * - Petri Net Data (transition firings + token paths + marking evolution + statistics)
  * 
  * Returns: JSON string containing both data types packaged together
  */
 @SuppressWarnings("unchecked")
 public String collectAllData(String token) {
     System.out.println("=== " + getClass().getSimpleName() + ": UNIFIED DATA COLLECTION ===");
     System.out.println("Raw token received: " + token);
     
     try {
         // Parse the version from the token
         String version = extractVersionFromToken(token);
         System.out.println("Successfully parsed version: " + version);
         
         // Create the master response object
         JSONObject response = new JSONObject();
         
         // Add metadata
         response.put("monitoredPlace", placeName);
         response.put("reportingService", getClass().getSimpleName());
         response.put("collectionTime", System.currentTimeMillis());
      // Extract the service context (sequence ID) from the token
         
        // String serviceContext = extractVersionFromToken(token);   
         String serviceContext = extractServiceContextFromToken(token);
     
         response.put("serviceContext", serviceContext);  // ✅ uses extracted value
         response.put("version", version);
         response.put("reportingChannel", "unified_collection");
         response.put("token", token);
         
         // Collect Performance Data (timing + marking)
         System.out.println("Collecting performance data...");
         JSONObject performanceData = collectPerformanceDataAsJSON(token, version);
         response.put("performanceData", performanceData);
         
         int perfTimingCount = getIntValue(performanceData, "timingRecordCount", 0);
         int perfMarkingCount = getIntValue(performanceData, "markingRecordCount", 0);
         System.out.println("Performance data collected: " + perfTimingCount + 
                          " timing records, " + perfMarkingCount + " marking records");
         
         // Collect Petri Net Data (firings + paths + statistics)
         System.out.println("Collecting Petri Net data...");
         JSONObject petriNetData = collectPetriNetDataAsJSON(token, version);
         response.put("petriNetData", petriNetData);
         
         int firingCount = getIntValue(petriNetData, "firingRecordCount", 0);
         int pathCount = getIntValue(petriNetData, "pathRecordCount", 0);
         int markingCount = getIntValue(petriNetData, "markingRecordCount", 0);
         int statsCount = getIntValue(petriNetData, "statisticsRecordCount", 0);
         System.out.println("Petri Net data collected: " + firingCount + " firings, " + 
                          pathCount + " paths, " + markingCount + " markings, " + 
                          statsCount + " statistics");
         
         String result = response.toJSONString();
         System.out.println(getClass().getSimpleName() + ": Generated unified response (" + 
                          result.length() + " chars)");
         
         return result;
         
     } catch (Exception e) {
         System.err.println("Error in collectAllData: " + e.getMessage());
         e.printStackTrace();
         
         // Return error response
         JSONObject errorResponse = new JSONObject();
         errorResponse.put("status", "error");
         errorResponse.put("message", e.getMessage());
         errorResponse.put("token", token);
         errorResponse.put("monitoredPlace", placeName);
         errorResponse.put("reportingService", getClass().getSimpleName());
         return errorResponse.toJSONString();
     }
 }

 // ============================================================================
 // HELPER METHODS
 // ============================================================================

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
  * Extract version from token
  * Token format: {"token":"workflow_300000_v003"}
  */
 private String extractVersionFromToken(String token) {
	    try {
	        JSONParser parser = new JSONParser();
	        JSONObject tokenObj = (JSONObject) parser.parse(token);
	        String tokenValue = (String) tokenObj.get("token");
	        
	        // Token format: "workflow_300000_v003"
	        String[] parts = tokenValue.split("_");
	        if (parts.length >= 3) {
	            return parts[2].replaceAll("[^a-zA-Z0-9]", ""); 
	        }
	     System.err.println("ERROR  extracting version: defaulting to v001" );
         return "v001"; // Default fallback
     } catch (Exception e) {
         System.err.println("Error extracting version: " + e.getMessage());
         return "v001";
     }
 }
 
//=============================================================================
//ADD THIS METHOD RIGHT AFTER extractVersionFromToken() (around line 316)
//=============================================================================

/**
* Extract service context (sequence ID) from token
* Token format: {"token":"workflow_300000_v003"}
* Returns the sequence ID portion (e.g., "300000")
*/
private String extractServiceContextFromToken(String token) {
  try {
      JSONParser parser = new JSONParser();
      JSONObject tokenObj = (JSONObject) parser.parse(token);
      String tokenValue = (String) tokenObj.get("token");
      
      // Token format: "workflow_300000_v003"
      String[] parts = tokenValue.split("_");
      if (parts.length >= 3) {
          return parts[1]; // Returns "300000" (the sequence ID)
      }
      
      return this.serviceContext; // Fallback to instance field
  } catch (Exception e) {
      System.err.println("Error extracting service context: " + e.getMessage());
      return this.serviceContext; // Fallback to instance field
  }
}


 /**
  * Collect performance data and return as JSONObject
  */
 @SuppressWarnings("unchecked")
 private JSONObject collectPerformanceDataAsJSON(String token, String version) {
     JSONObject perfData = new JSONObject();
     
     try {
         // Call the existing getPerformanceData method
         String perfDataString = getPerformanceData(token);
         
         // Parse the JSON response
         JSONParser parser = new JSONParser();
         JSONObject parsed = (JSONObject) parser.parse(perfDataString);
         
         // Extract the relevant fields
         if (parsed.containsKey("workflowGroups")) {
             perfData.put("workflowGroups", parsed.get("workflowGroups"));
         } else {
             perfData.put("workflowGroups", new JSONObject());
         }
         
         if (parsed.containsKey("markingData")) {
             perfData.put("markingData", parsed.get("markingData"));
         } else {
             perfData.put("markingData", new JSONObject());
         }
         
         perfData.put("timingRecordCount", getIntValue(parsed, "timingRecordCount", 0));
         perfData.put("markingRecordCount", getIntValue(parsed, "markingRecordCount", 0));
         
     } catch (Exception e) {
         System.err.println("Error collecting performance data: " + e.getMessage());
         e.printStackTrace();
         // Don't fail the whole collection - just return empty data
         perfData.put("error", e.getMessage());
         perfData.put("timingRecordCount", 0);
         perfData.put("markingRecordCount", 0);
         perfData.put("workflowGroups", new JSONObject());
         perfData.put("markingData", new JSONObject());
     }
     
     return perfData;
 }

 /**
  * Collect Petri Net data and return as JSONObject
  */
 @SuppressWarnings("unchecked")
 private JSONObject collectPetriNetDataAsJSON(String token, String version) {
     JSONObject petriNetData = new JSONObject();
     
     try {
         // Call the existing getPetriNetData method
         String petriNetDataString = getPetriNetData(token);
         
         // Parse the JSON response
         JSONParser parser = new JSONParser();
         JSONObject parsed = (JSONObject) parser.parse(petriNetDataString);
         
         // Extract counts
         petriNetData.put("firingRecordCount", getIntValue(parsed, "firingRecordCount", 0));
         petriNetData.put("pathRecordCount", getIntValue(parsed, "pathRecordCount", 0));
         petriNetData.put("markingRecordCount", getIntValue(parsed, "markingRecordCount", 0));
         petriNetData.put("statisticsRecordCount", getIntValue(parsed, "statisticsRecordCount", 0));
         
         // Include the actual data arrays/objects if present
         if (parsed.containsKey("transitionFirings")) {
             petriNetData.put("transitionFirings", parsed.get("transitionFirings"));
         }
         if (parsed.containsKey("tokenPaths")) {
             petriNetData.put("tokenPaths", parsed.get("tokenPaths"));
         }
         if (parsed.containsKey("markingSnapshots")) {
             petriNetData.put("markingSnapshots", parsed.get("markingSnapshots"));
         }
         if (parsed.containsKey("statistics")) {
             petriNetData.put("statistics", parsed.get("statistics"));
         }
         if (parsed.containsKey("workflowGroups")) {
             petriNetData.put("workflowGroups", parsed.get("workflowGroups"));
         }
         
     } catch (Exception e) {
         System.err.println("Error collecting Petri Net data: " + e.getMessage());
         e.printStackTrace();
         // Don't fail the whole collection - just return empty data
         petriNetData.put("error", e.getMessage());
         petriNetData.put("firingRecordCount", 0);
         petriNetData.put("pathRecordCount", 0);
         petriNetData.put("markingRecordCount", 0);
         petriNetData.put("statisticsRecordCount", 0);
     }
     
     return petriNetData;
 }

 // ============================================================================
 // END OF METHODS TO ADD
 // ============================================================================
 
    
    
    private List<ServiceMarking> readServiceMarkingDataByVersion(String version) {
        List<ServiceMarking> markings = new ArrayList<>();
        
        String sql = 
            "SELECT sequenceId, serviceName, operation, arrivalTime, invocationTime, " +
            "       publishTime, workflowStartTime, bufferSize, maxQueueCapacity, totalMarking " +
            "FROM " + SERVICE_MEASUREMENTS_TABLE + " " +
            "WHERE serviceName = ? " +
            "ORDER BY workflowStartTime, sequenceId";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
           // pstmt.setString(1, version);
            pstmt.setString(1, placeName);
            
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                ServiceMarking marking = new ServiceMarking();
                marking.sequenceId = rs.getLong("sequenceID");  // Uppercase D
                marking.serviceName = rs.getString("serviceName");
                marking.operation = rs.getString("operation");
                marking.arrivalTime = rs.getLong("arrivalTime");
                marking.invocationTime = rs.getLong("invocationTime");
                marking.publishTime = rs.getLong("publishTime");
                marking.workflowStartTime = rs.getLong("workflowStartTime");
                marking.bufferSize = rs.getInt("bufferSize");
                marking.maxQueueCapacity = rs.getInt("maxQueueCapacity");
                marking.totalMarking = rs.getInt("totalMarking");
                markings.add(marking);
            }
            
        } catch (SQLException e) {
            System.err.println("Error reading marking data: " + e.getMessage());
            e.printStackTrace();
        }
        
        return markings;
    }

    // =============================================================================
    // DATABASE READ METHODS - PETRI NET DATA
    // =============================================================================

    private List<TransitionFiring> readTransitionFiringsForPlace(String version) {
        List<TransitionFiring> firings = new ArrayList<>();
        
        // Include EVENT_GENERATOR events for the first place in the workflow
        // GENERATED events have eventType = 'GENERATED' and toPlace = this place
        String sql = 
            "SELECT tokenId, transitionId, timestamp, toPlace, fromPlace, workflowBase, bufferSize, eventType " +
            "FROM " + TRANSITION_FIRINGS_TABLE + " " +
            "WHERE ((transitionId = ? OR transitionId = ?) " +
            "       OR (eventType = 'GENERATED' AND toPlace = ?)) " +
            "  AND ruleVersion = ? " +
            "ORDER BY workflowBase, timestamp";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, buildTInId(placeName));
            pstmt.setString(2, buildTOutId(placeName));
            pstmt.setString(3, placeName);  // For EVENT_GENERATOR events destined for this place
            pstmt.setString(4, version);
            
         
            System.out.println("DEBUG: Query params - " + buildTInId(placeName) + ", " + buildTOutId(placeName) + ", placeName=" + placeName + ", version='" + version + "' length=" + version.length());

            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                TransitionFiring firing = new TransitionFiring();
                firing.tokenId = rs.getInt("tokenId");
                firing.transitionId = rs.getString("transitionId");
                firing.timestamp = rs.getLong("timestamp");
                firing.toPlace = rs.getString("toPlace");
                firing.fromPlace = rs.getString("fromPlace");
                firing.workflowBase = rs.getInt("workflowBase");
                firing.bufferSize = rs.getInt("bufferSize");
                firing.eventType = rs.getString("eventType");
                firing.placeName = placeName;
                firings.add(firing);
            }
            
        } catch (SQLException e) {
            System.err.println("Error reading transition firings: " + e.getMessage());
            e.printStackTrace();
        }
        
        return firings;
    }

    /**
     * FIXED: Read token paths with correct entry/exit pairing
     * 
     * The original query created a Cartesian product when a token visited
     * the same place multiple times. The fix ensures each entry is paired 
     * with its NEXT corresponding exit by requiring exit timestamp >= entry 
     * timestamp and using NOT EXISTS to pick only the first exit.
     */
    private List<TokenPath> readTokenPathsForPlace(String version) {
        List<TokenPath> paths = new ArrayList<>();
        
        // FIXED: Pair each entry with its corresponding NEXT exit
        String sql = 
            "SELECT t_in.tokenId, t_in.toPlace, " +
            "       t_in.timestamp as entryTime, " +
            "       t_out.timestamp as exitTime, t_in.workflowBase, " +
            "       t_in.bufferSize as entryBufferSize, t_out.bufferSize as exitBufferSize " +
            "FROM " + TRANSITION_FIRINGS_TABLE + " t_in " +
            "JOIN " + TRANSITION_FIRINGS_TABLE + " t_out " +
            "  ON t_in.tokenId = t_out.tokenId " +
            "  AND t_in.workflowBase = t_out.workflowBase " +
            "  AND t_out.transitionId = ? " +
            "  AND t_out.timestamp >= t_in.timestamp " +
            "WHERE t_in.transitionId = ? " +
            "  AND t_in.ruleVersion = ? " +
            "  AND t_out.ruleVersion = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM " + TRANSITION_FIRINGS_TABLE + " t_between " +
            "      WHERE t_between.tokenId = t_in.tokenId " +
            "        AND t_between.workflowBase = t_in.workflowBase " +
            "        AND t_between.transitionId = t_out.transitionId " +
            "        AND t_between.ruleVersion = t_in.ruleVersion " +
            "        AND t_between.timestamp > t_in.timestamp " +
            "        AND t_between.timestamp < t_out.timestamp " +
            "  ) " +
            "ORDER BY t_in.workflowBase, t_in.timestamp";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, buildTOutId(placeName));
            pstmt.setString(2, buildTInId(placeName));
            pstmt.setString(3, version);
            pstmt.setString(4, version);
            
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                TokenPath path = new TokenPath();
                path.tokenId = rs.getInt("tokenId");
                path.placeName = placeName;
                path.entryTime = rs.getLong("entryTime");
                path.exitTime = rs.getLong("exitTime");
                path.residenceTime = path.exitTime - path.entryTime;
                path.workflowBase = rs.getInt("workflowBase");
                path.entryBufferSize = rs.getInt("entryBufferSize");
                path.exitBufferSize = rs.getInt("exitBufferSize");
                paths.add(path);
            }
            
        } catch (SQLException e) {
            System.err.println("Error reading token paths: " + e.getMessage());
            e.printStackTrace();
        }
        
        return paths;
    }

    /**
     * Read token genealogy records (fork parent-child relationships)
     */
    private List<TokenGenealogy> readTokenGenealogyForPlace(String version) {
        List<TokenGenealogy> genealogy = new ArrayList<>();
        
        String sql = 
            "SELECT parentTokenId, childTokenId, forkTransitionId, forkTimestamp, workflowBase " +
            "FROM TOKEN_GENEALOGY " +
            "ORDER BY forkTimestamp";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                TokenGenealogy record = new TokenGenealogy();
                record.parentTokenId = rs.getInt("parentTokenId");
                record.childTokenId = rs.getInt("childTokenId");
                record.forkTransitionId = rs.getString("forkTransitionId");
                record.forkTimestamp = rs.getLong("forkTimestamp");
                record.workflowBase = rs.getInt("workflowBase");
                genealogy.add(record);
            }
            
        } catch (SQLException e) {
            // Table might not exist in older databases - that's OK
            System.out.println("Note: TOKEN_GENEALOGY table not found or empty");
        }
        
        return genealogy;
    }

    /**
     * FIXED: Read marking evolution for place - outputs per-token entry/exit events
     * 
     * PREVIOUS BEHAVIOR (BROKEN FOR ANIMATION):
     * Calculated cumulative place marking (total tokens in place) which resulted in
     * negative values for fork/join synchronization. This made it impossible to
     * track individual token movements.
     * 
     * NEW BEHAVIOR (CORRECT FOR ANIMATION):
     * Outputs simple per-token marking:
     *   - T_in event  -> marking = 1 (token entered this place)
     *   - T_out event -> marking = 0 (token exited this place)
     * 
     * This allows the animator to track each token's entry/exit at each place,
     * including forked tokens that visit places multiple times.
     */
    private List<MarkingSnapshot> readMarkingEvolutionForPlace(String version) {
        List<MarkingSnapshot> snapshots = new ArrayList<>();
        
        // Simple query: each T_in is marking=1, each T_out is marking=0
        // This gives per-token entry/exit events that the animator can use
        // ADDED: toPlace for routing info at forks, eventType for lifecycle events
        String sql = 
            "SELECT tokenId, timestamp, transitionId, workflowBase, bufferSize, toPlace, eventType, " +
            "       CASE WHEN transitionId = ? THEN 1 ELSE 0 END as marking " +
            "FROM " + TRANSITION_FIRINGS_TABLE + " " +
            "WHERE (transitionId = ? OR transitionId = ?) " +
            "  AND ruleVersion = ? " +
            "ORDER BY workflowBase, timestamp";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            String tIn = buildTInId(placeName);
            String tOut = buildTOutId(placeName);
            
            pstmt.setString(1, tIn);     // For CASE expression
            pstmt.setString(2, tIn);     // First OR in WHERE
            pstmt.setString(3, tOut);    // Second OR in WHERE
            pstmt.setString(4, version); // Version filter
            
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                MarkingSnapshot snapshot = new MarkingSnapshot();
                snapshot.tokenId = rs.getInt("tokenId");
                snapshot.timestamp = rs.getLong("timestamp");
                snapshot.marking = rs.getInt("marking");
                snapshot.workflowBase = rs.getInt("workflowBase");
                snapshot.bufferSize = rs.getInt("bufferSize");
                snapshot.placeName = placeName;
                snapshot.transitionId = rs.getString("transitionId");  // T_in_X or T_out_X
                snapshot.toPlace = rs.getString("toPlace");            // Destination (for exits)
                snapshot.eventType = rs.getString("eventType");        // ENTER, EXIT, FORK_CONSUMED, TERMINATE
                snapshots.add(snapshot);
            }
            
        } catch (SQLException e) {
            System.err.println("Error reading marking evolution: " + e.getMessage());
            e.printStackTrace();
        }
        
        return snapshots;
    }
    /**
     * FIXED: Compute place statistics with correct entry/exit pairing
     * 
     * The original query created a Cartesian product when computing statistics.
     * The fix ensures correct pairing by requiring exit timestamp >= entry 
     * timestamp and using NOT EXISTS to pick only the first exit after each entry.
     */
    private List<PlaceStatistics> computePlaceStatistics(String version) {
        List<PlaceStatistics> statsList = new ArrayList<>();
        
        // FIXED: Use correct entry/exit pairing with NOT EXISTS
        String sql = 
            "SELECT t_in.workflowBase, " +
            "       COUNT(*) as tokenCount, " +
            "       AVG(t_out.timestamp - t_in.timestamp) as avgResidence, " +
            "       MIN(t_out.timestamp - t_in.timestamp) as minResidence, " +
            "       MAX(t_out.timestamp - t_in.timestamp) as maxResidence, " +
            "       AVG(t_in.bufferSize) as avgBufferSize, " +
            "       MAX(t_in.bufferSize) as maxBufferSize " +
            "FROM " + TRANSITION_FIRINGS_TABLE + " t_in " +
            "JOIN " + TRANSITION_FIRINGS_TABLE + " t_out " +
            "  ON t_in.tokenId = t_out.tokenId " +
            "  AND t_in.workflowBase = t_out.workflowBase " +
            "  AND t_out.transitionId = ? " +
            "  AND t_out.timestamp >= t_in.timestamp " +
            "WHERE t_in.transitionId = ? " +
            "  AND t_in.ruleVersion = ? " +
            "  AND t_out.ruleVersion = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM " + TRANSITION_FIRINGS_TABLE + " t_between " +
            "      WHERE t_between.tokenId = t_in.tokenId " +
            "        AND t_between.workflowBase = t_in.workflowBase " +
            "        AND t_between.transitionId = t_out.transitionId " +
            "        AND t_between.ruleVersion = t_in.ruleVersion " +
            "        AND t_between.timestamp > t_in.timestamp " +
            "        AND t_between.timestamp < t_out.timestamp " +
            "  ) " +
            "GROUP BY t_in.workflowBase " +
            "ORDER BY t_in.workflowBase";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, buildTOutId(placeName));
            pstmt.setString(2, buildTInId(placeName));
            pstmt.setString(3, version);
            pstmt.setString(4, version);
            
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                PlaceStatistics stats = new PlaceStatistics();
                stats.placeName = placeName;
                stats.workflowBase = rs.getInt("workflowBase");
                stats.tokenCount = rs.getInt("tokenCount");
                stats.avgResidenceTime = rs.getDouble("avgResidence");
                stats.minResidenceTime = rs.getLong("minResidence");
                stats.maxResidenceTime = rs.getLong("maxResidence");
                stats.avgBufferSize = rs.getDouble("avgBufferSize");
                stats.maxBufferSize = rs.getInt("maxBufferSize");
                
                if (stats.avgResidenceTime > 0) {
                    stats.throughput = 1000.0 / stats.avgResidenceTime;
                }
                
                statsList.add(stats);
            }
            
        } catch (SQLException e) {
            System.err.println("Error computing place statistics: " + e.getMessage());
            e.printStackTrace();
        }
        
        return statsList;
    }

    // =============================================================================
    // JSON RESPONSE BUILDERS
    // =============================================================================

    private String buildPerformanceDataResponse(List<ServiceTiming> timingData, List<ServiceMarking> markingData, String token) {
        JSONObject response = new JSONObject();
        response.put("reportingService", getCollectorName());
        response.put("monitoredPlace", placeName);
        response.put("dataType", "performance_data");
        response.put("reportingChannel", "unified_collection");
        response.put("collectionTime", System.currentTimeMillis());
        response.put("version", currentRuleVersion);
        response.put("token", token);
        response.put("serviceContext", serviceContext);
        
        // Timing data grouped by workflow
        JSONObject timingGroups = new JSONObject();
        Map<String, List<ServiceTiming>> timingsByWorkflow = groupTimingsByWorkflowBase(timingData);
        for (Map.Entry<String, List<ServiceTiming>> entry : timingsByWorkflow.entrySet()) {
            JSONArray timingsArray = new JSONArray();
            for (ServiceTiming timing : entry.getValue()) {
                JSONObject timingObj = new JSONObject();
                timingObj.put("sequenceId", timing.sequenceId);
                timingObj.put("serviceName", timing.serviceName);
                timingObj.put("operation", timing.operation);
                timingObj.put("arrivalTime", timing.arrivalTime);
                timingObj.put("queueTime", timing.queueTime);
                timingObj.put("serviceTime", timing.serviceTime);
                timingObj.put("totalTime", timing.totalTime);
                timingObj.put("workflowStartTime", timing.workflowStartTime);
                timingObj.put("bufferSize", timing.bufferSize);
                timingObj.put("maxQueueCapacity", timing.maxQueueCapacity);
                timingObj.put("totalMarking", timing.totalMarking);
                timingsArray.add(timingObj);
            }
            timingGroups.put(entry.getKey(), timingsArray);
        }
        response.put("workflowGroups", timingGroups);  // Monitor_Place expects "workflowGroups"
        
        // Marking data grouped by workflow
        JSONObject markingGroups = new JSONObject();
        Map<String, List<ServiceMarking>> markingsByWorkflow = groupMarkingsByWorkflowBase(markingData);
        for (Map.Entry<String, List<ServiceMarking>> entry : markingsByWorkflow.entrySet()) {
            JSONArray markingsArray = new JSONArray();
            for (ServiceMarking marking : entry.getValue()) {
                JSONObject markingObj = new JSONObject();
                markingObj.put("sequenceId", marking.sequenceId);
                markingObj.put("serviceName", marking.serviceName);
                markingObj.put("operation", marking.operation);
                markingObj.put("arrivalTime", marking.arrivalTime);
                markingObj.put("invocationTime", marking.invocationTime);
                markingObj.put("publishTime", marking.publishTime);
                markingObj.put("workflowStartTime", marking.workflowStartTime);
                markingObj.put("bufferSize", marking.bufferSize);
                markingObj.put("maxQueueCapacity", marking.maxQueueCapacity);
                markingObj.put("totalMarking", marking.totalMarking);
                markingsArray.add(markingObj);
            }
            markingGroups.put(entry.getKey(), markingsArray);
        }
        response.put("markingData", markingGroups);
        
        response.put("timingRecordCount", timingData.size());
        response.put("markingRecordCount", markingData.size());
        
        return response.toJSONString();
    }

// Replace buildPetriNetDataResponse() method in BaseCollectorService.java (lines 493-590)
    
    private String buildPetriNetDataResponse(List<TransitionFiring> firingData, List<TokenPath> pathData, 
                                             List<MarkingSnapshot> markingData, List<PlaceStatistics> statsData,
                                             List<TokenGenealogy> genealogyData, String token) {
        JSONObject response = new JSONObject();
        response.put("reportingService", getCollectorName());
        response.put("monitoredPlace", placeName);
        response.put("dataType", "petrinet_data");
        response.put("reportingChannel", "unified_collection");
        response.put("collectionTime", System.currentTimeMillis());
        response.put("version", currentRuleVersion);
        response.put("token", token);
        response.put("serviceContext", serviceContext);
        
        // Group all data by workflow first
        Map<String, List<TransitionFiring>> firingsByWorkflow = groupFiringsByWorkflowBase(firingData);
        Map<String, List<TokenPath>> pathsByWorkflow = groupPathsByWorkflowBase(pathData);
        Map<String, List<MarkingSnapshot>> markingsByWorkflow = groupMarkingSnapshotsByWorkflowBase(markingData);
        Map<String, List<PlaceStatistics>> statsByWorkflow = groupStatisticsByWorkflowBase(statsData);
        Map<String, List<TokenGenealogy>> genealogyByWorkflow = groupGenealogyByWorkflowBase(genealogyData);
        
        // Get all unique workflow bases
        java.util.Set<String> allWorkflows = new java.util.HashSet<>();
        allWorkflows.addAll(firingsByWorkflow.keySet());
        allWorkflows.addAll(pathsByWorkflow.keySet());
        allWorkflows.addAll(markingsByWorkflow.keySet());
        allWorkflows.addAll(statsByWorkflow.keySet());
        allWorkflows.addAll(genealogyByWorkflow.keySet());
        
        // Build nested structure: workflowGroups -> workflowBase -> data types
        JSONObject workflowGroups = new JSONObject();
        
        for (String workflowBase : allWorkflows) {
            JSONObject workflowData = new JSONObject();
            
            // Add transition firings for this workflow
            JSONArray firingsArray = new JSONArray();
            List<TransitionFiring> firings = firingsByWorkflow.get(workflowBase);
            if (firings != null) {
                for (TransitionFiring firing : firings) {
                    JSONObject firingObj = new JSONObject();
                    firingObj.put("workflowBase", firing.workflowBase);
                    firingObj.put("tokenId", firing.tokenId);
                    firingObj.put("transitionId", firing.transitionId);
                    firingObj.put("timestamp", firing.timestamp);
                    firingObj.put("toPlace", firing.toPlace);
                    firingObj.put("fromPlace", firing.fromPlace);
                    firingObj.put("placeName", firing.placeName);
                    firingObj.put("bufferSize", firing.bufferSize);
                    firingObj.put("eventType", firing.eventType);  // ENTER, EXIT, FORK_CONSUMED, TERMINATE
                    firingsArray.add(firingObj);
                }
            }
            workflowData.put("transitionFirings", firingsArray);
            
            // Add token paths for this workflow
            JSONArray pathsArray = new JSONArray();
            List<TokenPath> paths = pathsByWorkflow.get(workflowBase);
            if (paths != null) {
                for (TokenPath path : paths) {
                    JSONObject pathObj = new JSONObject();
                    pathObj.put("workflowBase", path.workflowBase);
                    pathObj.put("tokenId", path.tokenId);
                    pathObj.put("placeName", path.placeName);
                    pathObj.put("entryTime", path.entryTime);
                    pathObj.put("exitTime", path.exitTime);
                    pathObj.put("residenceTime", path.residenceTime);
                    pathObj.put("entryBufferSize", path.entryBufferSize);
                    pathObj.put("exitBufferSize", path.exitBufferSize);
                    pathsArray.add(pathObj);
                }
            }
            workflowData.put("tokenPaths", pathsArray);
            
            // Add marking evolution for this workflow
            JSONArray markingsArray = new JSONArray();
            List<MarkingSnapshot> markings = markingsByWorkflow.get(workflowBase);
            if (markings != null) {
                for (MarkingSnapshot snapshot : markings) {
                    JSONObject snapshotObj = new JSONObject();
                    snapshotObj.put("workflowBase", snapshot.workflowBase);
                    snapshotObj.put("tokenId", snapshot.tokenId);
                    snapshotObj.put("timestamp", snapshot.timestamp);
                    snapshotObj.put("marking", snapshot.marking);
                    snapshotObj.put("bufferSize", snapshot.bufferSize);
                    snapshotObj.put("placeName", snapshot.placeName);
                    snapshotObj.put("transitionId", snapshot.transitionId);  // Which transition fired
                    snapshotObj.put("toPlace", snapshot.toPlace);            // Destination (for routing)
                    snapshotObj.put("eventType", snapshot.eventType);        // ENTER, EXIT, FORK_CONSUMED, TERMINATE
                    markingsArray.add(snapshotObj);
                }
            }
            workflowData.put("markingEvolution", markingsArray);
            
            // Add statistics for this workflow
            JSONArray statsArray = new JSONArray();
            List<PlaceStatistics> stats = statsByWorkflow.get(workflowBase);
            if (stats != null) {
                for (PlaceStatistics stat : stats) {
                    JSONObject statsObj = new JSONObject();
                    statsObj.put("workflowBase", stat.workflowBase);
                    statsObj.put("placeName", stat.placeName);
                    statsObj.put("tokenCount", stat.tokenCount);
                    statsObj.put("avgResidenceTime", stat.avgResidenceTime);
                    statsObj.put("minResidenceTime", stat.minResidenceTime);
                    statsObj.put("maxResidenceTime", stat.maxResidenceTime);
                    statsObj.put("throughput", stat.throughput);
                    statsObj.put("avgBufferSize", stat.avgBufferSize);
                    statsObj.put("maxBufferSize", stat.maxBufferSize);
                    statsArray.add(statsObj);
                }
            }
            workflowData.put("statistics", statsArray);
            
            // Add token genealogy for this workflow
            JSONArray genealogyArray = new JSONArray();
            List<TokenGenealogy> genealogies = genealogyByWorkflow.get(workflowBase);
            if (genealogies != null) {
                for (TokenGenealogy record : genealogies) {
                    JSONObject genealogyObj = new JSONObject();
                    genealogyObj.put("parentTokenId", record.parentTokenId);
                    genealogyObj.put("childTokenId", record.childTokenId);
                    genealogyObj.put("forkTransitionId", record.forkTransitionId);
                    genealogyObj.put("forkTimestamp", record.forkTimestamp);
                    genealogyObj.put("workflowBase", record.workflowBase);
                    genealogyArray.add(genealogyObj);
                }
            }
            workflowData.put("tokenGenealogy", genealogyArray);
            
            // Add this workflow's data to the groups
            workflowGroups.put(workflowBase, workflowData);
        }
        
        // Put the nested structure in response
        response.put("workflowGroups", workflowGroups);
        
        response.put("firingRecordCount", firingData.size());
        response.put("pathRecordCount", pathData.size());
        response.put("markingRecordCount", markingData.size());
        response.put("statisticsRecordCount", statsData.size());
        response.put("genealogyRecordCount", genealogyData.size());
        
        return response.toJSONString();
    }

    // =============================================================================
    // GROUPING METHODS
    // =============================================================================

    private Map<String, List<ServiceTiming>> groupTimingsByWorkflowBase(List<ServiceTiming> timings) {
        Map<String, List<ServiceTiming>> groups = new HashMap<>();
        for (ServiceTiming timing : timings) {
            String key = String.valueOf(timing.workflowStartTime);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(timing);
        }
        return groups;
    }

    private Map<String, List<ServiceMarking>> groupMarkingsByWorkflowBase(List<ServiceMarking> markings) {
        Map<String, List<ServiceMarking>> groups = new HashMap<>();
        for (ServiceMarking marking : markings) {
            String key = String.valueOf(marking.workflowStartTime);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(marking);
        }
        return groups;
    }

    private Map<String, List<TransitionFiring>> groupFiringsByWorkflowBase(List<TransitionFiring> firings) {
        Map<String, List<TransitionFiring>> groups = new HashMap<>();
        for (TransitionFiring firing : firings) {
            String key = String.valueOf(firing.workflowBase);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(firing);
        }
        return groups;
    }

    private Map<String, List<TokenPath>> groupPathsByWorkflowBase(List<TokenPath> paths) {
        Map<String, List<TokenPath>> groups = new HashMap<>();
        for (TokenPath path : paths) {
            String key = String.valueOf(path.workflowBase);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(path);
        }
        return groups;
    }

    private Map<String, List<MarkingSnapshot>> groupMarkingSnapshotsByWorkflowBase(List<MarkingSnapshot> snapshots) {
        Map<String, List<MarkingSnapshot>> groups = new HashMap<>();
        for (MarkingSnapshot snapshot : snapshots) {
            String key = String.valueOf(snapshot.workflowBase);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(snapshot);
        }
        return groups;
    }

    private Map<String, List<PlaceStatistics>> groupStatisticsByWorkflowBase(List<PlaceStatistics> statsList) {
        Map<String, List<PlaceStatistics>> groups = new HashMap<>();
        for (PlaceStatistics stats : statsList) {
            String key = String.valueOf(stats.workflowBase);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(stats);
        }
        return groups;
    }

    private Map<String, List<TokenGenealogy>> groupGenealogyByWorkflowBase(List<TokenGenealogy> genealogy) {
        Map<String, List<TokenGenealogy>> groups = new HashMap<>();
        for (TokenGenealogy record : genealogy) {
            String key = String.valueOf(record.workflowBase);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
        }
        return groups;
    }

    // =============================================================================
    // HELPER METHODS
    // =============================================================================

    private String handleTokenError(InvalidTokenException e, String token) {
        String errorMsg = String.format(
            "\n===============================================================\n" +
            " FATAL: INVALID TOKEN FORMAT\n" +
            "===============================================================\n" +
            " Collector: %s\n" +
            " Error: %s\n" +
            " Token: %s\n" +
            "===============================================================",
            getCollectorName(), e.getMessage(), token);
        System.err.println(errorMsg);
        e.printStackTrace();
        return buildErrorResponse("Invalid token format: " + e.getMessage());
    }

    private String handleGeneralError(Exception e) {
        String errorMsg = String.format(
            "\n===============================================================\n" +
            " FATAL: COLLECTION FAILED\n" +
            "===============================================================\n" +
            " Collector: %s\n" +
            " Error: %s\n" +
            "===============================================================",
            getCollectorName(), e.getMessage());
        System.err.println(errorMsg);
        e.printStackTrace();
        return buildErrorResponse(e.getMessage());
    }

    private String buildErrorResponse(String errorMessage) {
        JSONObject response = new JSONObject();
        response.put("reportingService", getCollectorName());
        response.put("monitoredPlace", placeName);
        response.put("reportingChannel", "unified_collection");
        response.put("collectionTime", System.currentTimeMillis());
        response.put("status", "error");
        response.put("errorMessage", errorMessage);
        response.put("serviceContext", serviceContext);
        return response.toJSONString();
    }

    // =============================================================================
    // TOPOLOGY NAMING CONVENTION HELPERS
    // =============================================================================
    
    /**
     * Derive the topology element label (node name) from a service/place name.
     * 
     * TOPOLOGY NAMING CONVENTION:
     *   Service Name = Node Name + "_Place"
     *   Node Name    = Service Name - "_Place"
     * 
     * Examples:
     *   P1_Place      -> P1
     *   P5_Place      -> P5
     *   Monitor_Place -> Monitor
     * 
     * This ensures transition IDs match the topology (T_in_P5, not T_in_P5_Place).
     * 
     * @param serviceName The runtime service/place name (e.g., "P5_Place")
     * @return The topology node name (e.g., "P5")
     */
    private String deriveElementLabel(String serviceName) {
        if (serviceName == null || serviceName.isEmpty()) {
            return serviceName;
        }
        
        // Apply the canonical rule: strip "_Place" suffix
        if (serviceName.endsWith("_Place")) {
            return serviceName.substring(0, serviceName.length() - "_Place".length());
        }
        
        // Service name doesn't follow convention - log warning but continue
        System.err.println("NAMING CONVENTION VIOLATION: '" + serviceName + 
                   "' does not end with '_Place'. Transition IDs may not match topology.");
        return serviceName;
    }
    
    /**
     * Build T_in transition ID from service/place name.
     * @param serviceName The runtime service/place name (e.g., "P5_Place")
     * @return Transition ID matching topology (e.g., "T_in_P5")
     */
    private String buildTInId(String serviceName) {
        return "T_in_" + deriveElementLabel(serviceName);
    }
    
    /**
     * Build T_out transition ID from service/place name.
     * @param serviceName The runtime service/place name (e.g., "P5_Place")
     * @return Transition ID matching topology (e.g., "T_out_P5")
     */
    private String buildTOutId(String serviceName) {
        return "T_out_" + deriveElementLabel(serviceName);
    }

    private Connection getConnection() throws SQLException {
        String url = PROTOCOL + DB_NAME;
        return DriverManager.getConnection(url);
    }

    /**
     * Parse version(s) from token - supports both single and multi-version formats
     * Single version: workflow_100000_v001 -> ["v001"]
     * Multi-version:  workflow_100000_v001,v002,v003 -> ["v001", "v002", "v003"]
     */
    private List<String> parseVersionsFromToken(String token) throws InvalidTokenException {
        if (token == null || token.trim().isEmpty()) {
            throw new InvalidTokenException("Token is null or empty");
        }
        String[] parts = token.split("_");
        if (parts.length < 3) {
            throw new InvalidTokenException("Token must have at least 3 parts separated by '_'. Got: " + token);
        }
        
        String versionPart = parts[2];
        if (versionPart.trim().isEmpty()) {
            throw new InvalidTokenException("Version part is empty");
        }
        
        // Split by comma to support multiple versions
        String[] versionArray = versionPart.split(",");
        List<String> versions = new ArrayList<>();
        
        for (String v : versionArray) {
            String cleaned = v.replaceAll("[^a-zA-Z0-9]", "").trim();
            if (!cleaned.isEmpty()) {
                versions.add(cleaned);
            }
        }
        
        if (versions.isEmpty()) {
            throw new InvalidTokenException("No valid versions found in token");
        }
        
        return versions;
    }
    
    /**
     * Legacy method for backward compatibility - returns first version only
     * @deprecated Use parseVersionsFromToken() for multi-version support
     */
    @Deprecated
    private String parseVersionFromTokenStrict(String token) throws InvalidTokenException {
        List<String> versions = parseVersionsFromToken(token);
        return versions.get(0);
    }

    // =============================================================================
    // MONITOR SERVICE COMMUNICATION
    // =============================================================================

    // =============================================================================
    // REMOVED: All sendToMonitor, UDP, XML building infrastructure  
    // Collectors now just return data - ServiceThread handles routing to Monitor_Place
    // =============================================================================

    protected static class ServiceTiming {
        long sequenceId;
        String serviceName;
        String operation;
        long arrivalTime;
        long queueTime;
        long serviceTime;
        long totalTime;
        long workflowStartTime;
        int bufferSize;
        int maxQueueCapacity;
        int totalMarking;
    }

    protected static class ServiceMarking {
        long sequenceId;
        String serviceName;
        String operation;
        long arrivalTime;
        long invocationTime;
        long publishTime;
        long workflowStartTime;
        int bufferSize;
        int maxQueueCapacity;
        int totalMarking;
    }

    protected static class TransitionFiring {
        int tokenId;
        String transitionId;
        long timestamp;
        String toPlace;
        String fromPlace;
        int workflowBase;
        int bufferSize;
        String placeName;
        String eventType;  // ENTER, EXIT, FORK_CONSUMED, TERMINATE
    }

    protected static class TokenPath {
        int tokenId;
        String placeName;
        long entryTime;
        long exitTime;
        long residenceTime;
        int workflowBase;
        int entryBufferSize;
        int exitBufferSize;
    }

    protected static class MarkingSnapshot {
        int tokenId;
        long timestamp;
        int marking;
        int workflowBase;
        int bufferSize;
        String placeName;
        String transitionId;  // T_in_X or T_out_X - identifies which transition fired
        String toPlace;       // Destination place (for T_out events) - critical for fork routing
        String eventType;     // ENTER, EXIT, FORK_CONSUMED, TERMINATE
    }

    protected static class PlaceStatistics {
        String placeName;
        int workflowBase;
        int tokenCount;
        double avgResidenceTime;
        long minResidenceTime;
        long maxResidenceTime;
        double throughput;
        double avgBufferSize;
        int maxBufferSize;
    }

    protected static class TokenGenealogy {
        int parentTokenId;
        int childTokenId;
        String forkTransitionId;
        long forkTimestamp;
        int workflowBase;
    }

    public static class InvalidTokenException extends Exception {
        public InvalidTokenException(String message) {
            super(message);
        }
        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}