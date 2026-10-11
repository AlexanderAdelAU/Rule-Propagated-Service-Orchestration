package org.btsn.business.inventory;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Example stateful business service: reserves one unit of stock per order.
 *
 * The stock count lives in this host's process (static, shared by the host's service threads) and
 * starts again when the host starts. Stock delivered by the supplier, on another host, arrives in
 * the order itself (delivered_units): hosts share no memory, so state changes travel as messages.
 *
 * Routes: "reserved" (the order ships) or "out_of_stock" (the order goes to the supplier).
 */
public class InventoryService extends BaseBusinessService {

    static final int INITIAL_STOCK = 5;
    private static int stock = INITIAL_STOCK;

    private final String sequenceId;

    public InventoryService() { this(""); }

    /** The host passes the order's token (sequence) id. */
    public InventoryService(String sequenceId) { this.sequenceId = sequenceId == null ? "" : sequenceId; }

    /** token -> inventoryResults */
    @SuppressWarnings("unchecked")
    public String reserve(String token) {
        int delivered = (int)numberValue(token, "delivered_units", 0);
        String orderId = textValue(token, "order_id", orderId(sequenceId));

        int stockAfter;
        boolean reserved;
        synchronized (InventoryService.class) {
            stock += Math.max(0, delivered);
            reserved = stock > 0;
            if (reserved) stock--;
            stockAfter = stock;
        }

        JSONObject result = new JSONObject();
        result.put("order_id", orderId);
        result.put("delivered_units", delivered);
        result.put("stock_after", stockAfter);
        result.put("order_status", reserved ? "reserved" : "backordered");
        result.put("routing_decision", routingDecision(reserved ? "reserved" : "out_of_stock"));
        return businessResult("inventoryResults", result);
    }

    /** ORDER-6 for the sixth token of a version (token ids are base + n x 10000). */
    static String orderId(String sequenceId) {
        try {
            long id = Long.parseLong(sequenceId.trim());
            return "ORDER-" + ((id % 1_000_000L) / 10_000L + 1);
        } catch (NumberFormatException e) {
            return "ORDER-" + (sequenceId.isEmpty() ? "?" : sequenceId);
        }
    }

    /** For tests: the stock this host holds now. */
    static synchronized int stock() { return stock; }
}
