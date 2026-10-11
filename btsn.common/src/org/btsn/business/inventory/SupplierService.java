package org.btsn.business.inventory;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Example supplier: restocks a backordered order, with a lead time, while it has deliveries left.
 *
 * Its remaining deliveries live in this host's process and start again when the host starts.
 * The delivery travels back to the inventory host inside the order (delivered_units), which is
 * why the result is returned as "token": the inventory service's input.
 *
 * Routes: "restocked" (back to the inventory with the delivery) or "unavailable" (the order is cancelled).
 */
public class SupplierService extends BaseBusinessService {

    static final int DELIVERIES = 1;
    static final int UNITS_PER_DELIVERY = 3;
    static final long LEAD_TIME_MS = 300;
    private static int deliveriesLeft = DELIVERIES;

    public SupplierService() { }

    public SupplierService(String sequenceId) { }

    /** inventoryResults -> token */
    @SuppressWarnings("unchecked")
    public String restock(String inventoryResults) {
        String orderId = textValue(inventoryResults, "order_id", "ORDER-?");

        boolean deliver;
        synchronized (SupplierService.class) {
            deliver = deliveriesLeft > 0;
            if (deliver) deliveriesLeft--;
        }
        if (deliver) {
            try { Thread.sleep(LEAD_TIME_MS); }            // the supplier's lead time, measured as service time
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        JSONObject result = new JSONObject();
        result.put("order_id", orderId);
        result.put("delivered_units", deliver ? UNITS_PER_DELIVERY : 0);
        result.put("supplier_status", deliver ? "delivered" : "unavailable");
        result.put("routing_decision", routingDecision(deliver ? "restocked" : "unavailable"));
        return businessResult("token", result);
    }
}
