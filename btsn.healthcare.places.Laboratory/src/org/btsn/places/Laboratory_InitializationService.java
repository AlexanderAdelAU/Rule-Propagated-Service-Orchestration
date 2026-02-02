package org.btsn.places;

import org.btsn.derby.Analysis.BuildServiceAnalysisDatabase;

/**
 * Laboratory Database Initialization Service  
 * Thin wrapper that provides service name to BuildServiceAnalysisDatabase methods
 */
public class Laboratory_InitializationService extends BuildServiceAnalysisDatabase {
    
    private static final String SERVICE_NAME = "Laboratory";
    
    public Laboratory_InitializationService(String context) {
        super();
    }
    
    // Wrapper methods that ServiceThread can call (single token parameter)
    public String initializeLocalDatabase(String token) {
        return super.initializeLocalDatabase(SERVICE_NAME, token);
    }
    
    public String purgeAndInitialize(String token) {
        return super.purgeAndInitialize(SERVICE_NAME, token);
    }
    
    public String purgeAllDatabaseTables(String token) {
        return super.purgeAllDatabaseTables(SERVICE_NAME, token);
    }
    
    public String purgeAllAndInitialize(String token) {
        return super.purgeAllAndInitialize(SERVICE_NAME, token);
    }
    
    public String getInitializationStatus(String token) {
        return super.getInitializationStatus(SERVICE_NAME, token);
    }
}