// A mock card-payments API in the ordinary REST style: it knows nothing about RPSO. It is what a real payment
// provider's API looks like, so RPSO can show calling one as it is, through a call mapping in the deployment
// (ServiceDeploymentFolder/payments/Plain_Payment_Test.json). Shared by the plain Node server (credit-service.js)
// and the Vercel function (api/v1/payments.js).
//
//   POST /v1/payments
//   Authorization: Bearer demo_key_public          (a public demo key: a real API's key comes from ${env:...})
//   {"amount": 84.5, "currency": "AUD", "card": "visa-4242", "reference": "ORD-1"}
//
//   201 {"id": "pay_1", "object": "payment", "status": "succeeded", "amount": 84.5, "currency": "AUD", ...}
//   402 {"id": "pay_8", "object": "payment", "status": "declined", "decline_code": "insufficient_funds", ...}
//   400 {"error": {"type": "invalid_request", "message": ...}}       a missing or invalid field
//   401 {"error": {"type": "authentication", "message": ...}}        no key, or the wrong one
//   503 {"error": {"type": "unavailable", "message": ...}}           the provider is down
//
// It behaves the same way every run, so a test can be repeated. Payment n is the number in its reference (ORD-n):
//   - each call takes 150 to 550 ms;
//   - every 8th payment is declined, with HTTP 402, as card APIs commonly do;
//   - every 13th finds the API unavailable: HTTP 503.

const KEY = 'demo_key_public';

/** The payment's number and how this API will treat it. */
function plan(body) {
  const match = /(\d+)\s*$/.exec(String((body && body.reference) || ''));
  const payment = match ? Number(match[1]) : 0;
  const outcome = payment > 0 && payment % 13 === 0 ? 'unavailable' : payment > 0 && payment % 8 === 0 ? 'declined' : 'succeeded';
  return { payment, outcome, delayMs: 150 + (payment * 89) % 401 };
}

function error(status, type, message) {
  return { status, body: { error: { type, message } } };
}

/** The answer to POST /v1/payments: { status, body }. headers are lower-case names. */
function create(headers, body, servedBy) {
  if ((headers.authorization || '') !== 'Bearer ' + KEY) return error(401, 'authentication', 'Missing or wrong API key');
  if (!body || typeof body !== 'object') return error(400, 'invalid_request', 'The body must be a JSON object');
  const amount = Number(body.amount);
  if (!Number.isFinite(amount) || amount <= 0) return error(400, 'invalid_request', 'amount must be a positive number');
  if (!/^[A-Z]{3}$/.test(String(body.currency || ''))) return error(400, 'invalid_request', 'currency must be a 3-letter code');
  if (!body.card) return error(400, 'invalid_request', 'card is required');
  const p = plan(body);
  if (p.outcome === 'unavailable') return error(503, 'unavailable', 'Payments are temporarily unavailable');
  const payment = {
    id: 'pay_' + (p.payment || Date.now()),
    object: 'payment',
    status: p.outcome,
    amount,
    currency: body.currency,
    reference: body.reference || null,
    idempotency_key: headers['idempotency-key'] || null,
    served_by: servedBy
  };
  if (p.outcome === 'declined') {
    payment.decline_code = 'insufficient_funds';
    return { status: 402, body: payment };
  }
  return { status: 201, body: payment };
}

module.exports = { plan, create, KEY };
