package org.btsn.places;

import org.btsn.base.BaseCollectorService;

/**
 * The collector every node runs: P7_CollectorService on node P7, and so on.
 *
 * One class serves all nodes. ServiceHelper creates it for any NodeName_CollectorService and passes
 * NodeName_Place, the place whose records this collector reports to Monitor.
 */
public class HostCollectorService extends BaseCollectorService {

    public HostCollectorService(String context, String placeName, String buildVersion) {
        super(context, placeName, buildVersion);
    }

    /** P7_Place -> P7. */
    static String node(String placeName) {
        return placeName.endsWith("_Place") ? placeName.substring(0, placeName.length() - "_Place".length()) : placeName;
    }

    @Override
    protected String getCollectorName() {
        return node(placeName) + "_CollectorService";
    }

    @Override
    protected String getMonitoredPlaceName() {
        return placeName;
    }
}
