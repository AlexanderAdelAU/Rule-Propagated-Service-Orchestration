// A mock remote payment service: what an online store calls to authorise a card payment. Shared by the plain Node
// server (credit-service.js, also used on Render) and the Vercel function (api/payment/authorise.js).
//
// It behaves like a real gateway, the same way every run, so a test can be repeated:
//   - each call takes 120 to 520 ms;
//   - every 8th payment is declined (insufficient funds): it routes "declined", the rest "paid";
//   - every 13th finds the gateway unavailable: HTTP 503, so the call fails;
//   - every 17th takes 4 seconds, longer than the caller waits (the deployment's timeoutMs), so the call times out.
//
// RPSO's rest adapter POSTs each call:
//   {"service": "PaymentService", "operation": "authorise", "sequenceId": "1030000", "version": "v001",
//    "inputs": {"token": {...}}}
// and publishes the answer, which has the shape a Java service returns:
//   {"paymentResult": {..., "routing_decision": {"routing_path": "paid"}}}

const { find, applicationNumber } = require('./credit');

const SLOW_MS = 4000;

/** The payment's number, amount, and how this gateway will treat it. */
function plan(call) {
  const payment = applicationNumber(call.sequenceId);
  const given = Number(find(call.inputs && call.inputs.token, 'amount'));
  const amount = Number.isFinite(given) ? given : Math.round(((payment * 37.5) % 480 + 20) * 100) / 100;
  const outcome = payment % 17 === 0 ? 'slow' : payment % 13 === 0 ? 'unavailable' : payment % 8 === 0 ? 'declined' : 'paid';
  const delayMs = outcome === 'slow' ? SLOW_MS : 120 + (payment * 73) % 401;
  return { payment, amount, outcome, delayMs };
}

/** The answer for a call, once its delay has passed: { status, body }. */
function authorise(call, servedBy) {
  const p = plan(call);
  if (p.outcome === 'unavailable') return { status: 503, body: 'Payment gateway unavailable, payment PAY-' + p.payment };
  const paid = p.outcome !== 'declined';
  return {
    status: 200,
    body: {
      paymentResult: {
        payment: 'PAY-' + p.payment,
        amount: p.amount,
        status: paid ? 'authorised' : 'declined',
        reason: paid ? '' : 'insufficient funds',
        latency_ms: p.delayMs,
        served_by: servedBy,
        routing_decision: { routing_path: paid ? 'paid' : 'declined' }
      }
    }
  };
}

module.exports = { plan, authorise, SLOW_MS };
