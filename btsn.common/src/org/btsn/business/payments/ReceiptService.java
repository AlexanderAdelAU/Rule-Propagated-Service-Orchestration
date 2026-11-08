package org.btsn.business.payments;

import org.btsn.business.BaseBusinessService;
import org.json.simple.JSONObject;

/**
 * Issues the receipt for a paid order: the next step after the payment service, in ServiceTutorial.md.
 *
 * Its one input is named paymentResult because that is what the place before it, Payment, publishes. It arrives as
 * {"paymentResult": {...the payment service's answer...}}, with the host's timing fields and, under "original_token",
 * the token the event generator sent: the order. So the receipt can quote both the order and the payment.
 *
 * Returns: the receipt, under "receipt". Routes on "issued".
 */
public class ReceiptService extends BaseBusinessService {

    public ReceiptService() { }

    /** The host passes the token's sequence id; a receipt does not need it. */
    public ReceiptService(String sequenceId) { }

    /** paymentResult -> receipt */
    @SuppressWarnings("unchecked")
    public String issue(String paymentResult) {
        String order = originalToken(paymentResult);   // the order, as the event generator sent it
        JSONObject receipt = new JSONObject();
        receipt.put("orderId", textValue(order, "orderId", "unknown order"));
        receipt.put("customer", textValue(order, "customer", ""));
        receipt.put("payment", textValue(paymentResult, "payment", ""));
        receipt.put("amount", numberValue(paymentResult, "amount", 0));
        receipt.put("currency", textValue(order, "currency", ""));
        receipt.put("routing_decision", routingDecision("issued"));
        return businessResult("receipt", receipt);
    }
}
