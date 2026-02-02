package org.btsn.places;

import org.btsn.base.BaseCollectorService;

/**
 * Laboratory Place Unified Collector Service
 * 
 * Simple service with TWO collection methods:
 * 1. getPerformanceData() - collects timing AND marking data
 * 2. getPetriNetData() - collects all 4 Petri Net data types
 * 
 * Usage:
 *   Loaded by: ServiceLoader via Service.ruleml
 *   Invoked by: Event generators with operation name
 *   Returns: JSON data to orchestrator which routes to MonitorService
 * 
 * IMPORTANT: Constructor uses PLACE_NAME constant (not the placeName parameter)
 * because ServiceHelper may pass a different value than what's stored in the database.
 * The PLACE_NAME constant must match the serviceName column in the database tables.
 */
public class Laboratory_CollectorService extends BaseCollectorService {

    /**
     * The authoritative place/service name for database queries.
     * This MUST match the serviceName stored in timing/marking tables.
     */
    private static final String PLACE_NAME = "LaboratoryService";
    
    /**
     * Constructor required by ServiceHelper framework.
     * 
     * CRITICAL: We pass PLACE_NAME to super() instead of the placeName parameter.
     * This ensures database queries use the correct serviceName regardless of
     * what value ServiceHelper passes. The PLACE_NAME constant is the single
     * source of truth for which service this collector monitors.
     * 
     * @param context The service context identifier
     * @param placeName The place name passed by ServiceHelper (IGNORED - we use PLACE_NAME)
     * @param buildVersion The build/rule version
     */
    public Laboratory_CollectorService(String context, String placeName, String buildVersion) {
        super(context, PLACE_NAME, buildVersion);  // Use PLACE_NAME constant, not parameter
    }

    @Override
    protected String getCollectorName() {
        return "Laboratory_CollectorService";
    }

    @Override
    protected String getMonitoredPlaceName() {
        return PLACE_NAME;
    }

    // =============================================================================
    // TWO PUBLIC METHODS - INHERITED FROM BASE CLASS
    // =============================================================================
    // 
    // 1. getPerformanceData(String token)
    //    - Collects timing metrics
    //    - Collects marking data
    //    - Returns both in single JSON response
    // 
    // 2. getPetriNetData(String token)
    //    - Collects transition firings (T_in/T_out events)
    //    - Collects token paths (complete journeys)
    //    - Collects marking evolution (token count over time)
    //    - Collects place statistics (aggregated metrics)
    //    - Returns all 4 types in single JSON response
    // 
    // No overrides needed unless Laboratory-specific behavior required
    // =============================================================================

    /**
     * Optional: Get service information
     */
    public String getServiceInfo() {
        return String.format(
            "Service: %s | Place: %s | Status: Active | Methods: 2 (Performance, PetriNet)",
            getCollectorName(),
            getMonitoredPlaceName()
        );
    }

    // =============================================================================
    // TEST HARNESS
    // =============================================================================

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("=== Laboratory_CollectorService TEST HARNESS ===");
        System.out.println("=================================================\n");
        
        // Note: We pass "Laboratory_Place" but it will be ignored - PLACE_NAME is used
        Laboratory_CollectorService service = new Laboratory_CollectorService("TEST_CONTEXT", "Laboratory_Place", "v001");
        String testToken = "workflow_100000_v001";
        
        System.out.println("Service Info: " + service.getServiceInfo());
        System.out.println("Monitored Place: " + service.getMonitoredPlaceName());
        System.out.println("\n=================================================");
        
        // Test Performance Data Collection
        System.out.println("\n--- Testing Performance Data Collection ---");
        System.out.println("(Collects timing AND marking data in one call)");
        try {
            String result = service.getPerformanceData(testToken);
            System.out.println("SUCCESS: Result length: " + result.length() + " characters");
            System.out.println("First 150 chars: " + 
                (result.length() > 150 ? result.substring(0, 150) + "..." : result));
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Test Petri Net Data Collection
        System.out.println("\n--- Testing Petri Net Data Collection ---");
        System.out.println("(Collects firings, paths, marking evolution, and statistics in one call)");
        try {
            String result = service.getPetriNetData(testToken);
            System.out.println("SUCCESS: Result length: " + result.length() + " characters");
            System.out.println("First 150 chars: " + 
                (result.length() > 150 ? result.substring(0, 150) + "..." : result));
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("\n=================================================");
        System.out.println("=== TEST COMPLETE ===");
        System.out.println("=================================================");
    }
}