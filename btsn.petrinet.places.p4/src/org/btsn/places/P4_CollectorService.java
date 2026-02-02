package org.btsn.places;

import org.btsn.base.BaseCollectorService;

/**
 * P2 Place Unified Collector Service
 * 
 * Simple service with TWO collection methods:
 * 1. getPerformanceData() - collects timing AND marking data
 * 2. getPetriNetData() - collects all 4 Petri Net data types
 * 
 * Usage:
 *   Loaded by: ServiceLoader via Service.ruleml
 *   Invoked by: Event generators with operation name
 *   Returns: JSON data to orchestrator which routes to MonitorService
 */
public class P4_CollectorService extends BaseCollectorService {

    private static final String PLACE_NAME = "P4_Place";
    
    /**
     * Constructor required by ServiceHelper framework
     */
    public P4_CollectorService(String context, String placeName, String buildVersion) {
        super(context, placeName, buildVersion);
    }

    @Override
    protected String getCollectorName() {
        return "P4_CollectorService";
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
    // No overrides needed unless P2-specific behavior required
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
        System.out.println("=== P4_CollectorService TEST HARNESS ===");
        System.out.println("=================================================\n");
        
        P4_CollectorService service = new P4_CollectorService("TEST_CONTEXT", "P2_Place", "v001");
        String testToken = "workflow_100000_v001";
        
        System.out.println("Service Info: " + service.getServiceInfo());
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