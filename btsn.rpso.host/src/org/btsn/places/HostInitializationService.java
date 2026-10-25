package org.btsn.places;

import org.btsn.derby.Analysis.BuildServiceAnalysisDatabase;

/**
 * The database initialisation every node runs: P7_InitializationService on node P7, and so on.
 *
 * One class serves all nodes. ServiceHelper creates it for any NodeName_InitializationService and passes
 * NodeName_Place; the node name (P7) labels the node's own analysis database.
 */
public class HostInitializationService extends BuildServiceAnalysisDatabase {

    private final String node;

    public HostInitializationService(String context, String placeName, String buildVersion) {
        super();
        this.node = HostCollectorService.node(placeName);
    }

    public String initializeLocalDatabase(String token) {
        return super.initializeLocalDatabase(node, token);
    }

    public String purgeAndInitialize(String token) {
        return super.purgeAndInitialize(node, token);
    }

    public String purgeAllDatabaseTables(String token) {
        return super.purgeAllDatabaseTables(node, token);
    }

    public String purgeAllAndInitialize(String token) {
        return super.purgeAllAndInitialize(node, token);
    }

    public String getInitializationStatus(String token) {
        return super.getInitializationStatus(node, token);
    }
}
