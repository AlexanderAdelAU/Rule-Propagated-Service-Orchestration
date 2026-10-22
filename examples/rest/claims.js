// The claims assessment of Tutorial.md, Example 2: the service the Assess place calls over REST.
// Shared by the plain Node server (credit-service.js) and the Vercel function (api/claims/assess.js).
//
// RPSO's rest adapter POSTs each call:
//   {"service": "AssessService", "operation": "assess", "sequenceId": "1060000", "version": "v001",
//    "inputs": {"token": {...}}}
// where the token is what Triage published (the claim, its amount and the threshold), and publishes
// the answer under the return attribute "token":
//   {"token": {"claim": "CLM-7", "amount": 3500, "limit": 4000, "routing_decision": {"routing_path": "accept"}}}

const { find, applicationNumber } = require('./credit');

const LIMIT = 4000;

function assess(call, servedBy) {
  const claim = applicationNumber(call.sequenceId);
  const given = Number(find(call.inputs && call.inputs.token, 'amount'));
  const amount = Number.isFinite(given) ? given : claim * 500;
  const decision = amount <= LIMIT ? 'accept' : 'reject';
  return {
    token: {
      claim: 'CLM-' + claim,
      amount: amount,
      limit: LIMIT,
      served_by: servedBy,
      routing_decision: { routing_path: decision }
    }
  };
}

module.exports = { assess, LIMIT };
