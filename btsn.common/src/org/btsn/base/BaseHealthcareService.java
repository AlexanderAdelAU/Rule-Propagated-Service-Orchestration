package org.btsn.base;

import org.btsn.json.JsonResponseBuilder;
import org.btsn.json.JsonTokenParser;
import org.btsn.utils.SequenceUtils;
import org.btsn.utils.TimeStampUtils;
import org.btsn.exceptions.ServiceProcessingException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Abstract base class for all healthcare services
 * Handles common functionality like JSON processing, token parsing, database setup
 */
public abstract class BaseHealthcareService {
    
    protected final String sequenceID;
    protected final String placeId;
    protected final Connection database;
    protected final JsonTokenParser tokenParser;
    protected final JsonResponseBuilder responseBuilder;
    
    /**
     * Constructor that all services must implement
     */
    public BaseHealthcareService(String sequenceID, String serviceType) {
        this.sequenceID = sequenceID;
        this.placeId = serviceType.toUpperCase() + "_PLACE_" + sequenceID;
        this.tokenParser = new JsonTokenParser(placeId);
        this.responseBuilder = new JsonResponseBuilder();
        this.database = initializeDatabase(serviceType);
        
        // Setup service-specific database schema
        setupServiceDatabase();
        
        System.out.printf("[%s] %s initialized for sequence: %s\n", 
            placeId, this.getClass().getSimpleName(), sequenceID);
    }
    
    /**
     * Main processing method - template method pattern
     * Each service implements processServiceSpecificAssessment()
     */
    public String processAssessment(String inputData) {
        System.out.printf("[%s] Processing assessment for sequence %s with data: %s\n", 
            placeId, sequenceID, inputData);
        
        try {
            // Parse incoming data using common parser
            TokenInfo tokenInfo = tokenParser.parseIncomingToken(inputData);
            
            System.out.printf("[%s] Extracted: Patient=%s, Indication=%s\n", 
                placeId, tokenInfo.getPatientId(), tokenInfo.getIndication());
            
            // Let the specific service do its work
            ServiceAssessment assessment = processServiceSpecificAssessment(tokenInfo);
            
            // Build JSON response using common builder
            String jsonResponse = responseBuilder
                .setServiceType(getServiceResultsKey())
                .setTokenInfo(tokenInfo)
                .setAssessment(assessment)
                .setPlaceId(placeId)
                .setSequenceId(sequenceID)
                .setStatus(getCompletionStatus())
                .build();
            
            System.out.printf("[%s] Assessment completed successfully\n", placeId);
            return jsonResponse;
            
        } catch (Exception e) {
            String errorJson = responseBuilder.createErrorResponse(
                getServiceResultsKey(), e.getMessage(), sequenceID, placeId);
            System.err.printf("[%s] Error: %s\n", placeId, e.getMessage());
            return errorJson;
        }
    }
    
    /**
     * Abstract methods that each service must implement
     */
    protected abstract ServiceAssessment processServiceSpecificAssessment(TokenInfo tokenInfo) 
        throws ServiceProcessingException;
    
    protected abstract String getServiceResultsKey(); // e.g., "cardiologyResults"
    
    protected abstract String getCompletionStatus();   // e.g., "CARDIAC_COMPLETE"
    
    protected abstract String[] getRequiredTables();   // SQL table creation statements
    
    protected abstract void setupServiceDatabase();    // Service-specific DB setup
    
    /**
     * Common database initialization
     */
    private Connection initializeDatabase(String serviceType) {
        try {
            String dbURL = String.format("jdbc:derby:memory:%sDB_%s_%d;create=true", 
                serviceType, sequenceID, System.nanoTime());
            Connection conn = DriverManager.getConnection(dbURL);
            
            System.out.printf("[%s] Database initialized\n", placeId);
            return conn;
            
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database: " + e.getMessage(), e);
        }
    }
    
    /**
     * Helper method to execute table creation
     */
    protected void createTables(String[] tableDefinitions) {
        try (Statement stmt = database.createStatement()) {
            for (String tableDef : tableDefinitions) {
                stmt.execute(tableDef);
            }
            System.out.printf("[%s] Database tables created\n", placeId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create tables: " + e.getMessage(), e);
        }
    }
    
    /**
     * Generate assessment ID using common utility
     */
    protected String generateAssessmentId(String prefix, String patientId) {
        return SequenceUtils.generateAssessmentId(prefix, patientId);
    }
    
    /**
     * Get current timestamp using common utility
     */
    protected String getCurrentTimestamp() {
        return TimeStampUtils.getCurrentTimestamp();
    }
    
    /**
     * Common shutdown method
     */
    public void shutdown() {
        try {
            if (database != null && !database.isClosed()) {
                database.close();
                System.out.printf("[%s] Database closed\n", placeId);
            }
        } catch (SQLException e) {
            System.err.printf("[%s] Error closing database: %s\n", placeId, e.getMessage());
        }
    }
    
    // Getters for protected access
    protected String getSequenceID() { return sequenceID; }
    protected String getPlaceId() { return placeId; }
    protected Connection getDatabase() { return database; }
}