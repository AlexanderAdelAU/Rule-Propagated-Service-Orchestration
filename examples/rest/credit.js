// The credit check from ServiceTutorial.md: the service's logic, shared by the plain Node server
// (credit-service.js, also used on Render) and the Vercel function (api/credit/assess.js).
//
// RPSO's rest adapter POSTs each call:
//   {"service": "CreditLimitService", "operation": "assess", "sequenceId": "1030000", "version": "v001",
//    "inputs": {"token": {...}}}
// and publishes the answer, which has the same shape a Java service returns:
//   {"creditDecision": {..., "routing_decision": {"routing_path": "approve"}}}

const LIMIT = 5000;

// 1 for the first token of a version, 2 for the second, ... (token ids are base + n x 10000).
function applicationNumber(sequenceId) {
  const id = Number(sequenceId);
  return Number.isFinite(id) ? Math.floor((id % 1000000) / 10000) + 1 : 1;
}

// Finds a field anywhere in the input, as the Java service's numberValue does.
function find(value, key) {
  if (value === null || typeof value !== 'object') return undefined;
  if (key in value) return value[key];
  for (const child of Object.values(value)) {
    const found = find(child, key);
    if (found !== undefined) return found;
  }
  return undefined;
}

function assess(call, servedBy) {
  const application = applicationNumber(call.sequenceId);
  const given = Number(find(call.inputs && call.inputs.token, 'amount'));
  const amount = Number.isFinite(given) ? given : application * 1000;
  const decision = amount <= LIMIT ? 'approve' : 'refer';
  return {
    creditDecision: {
      application: 'APP-' + application,
      amount: amount,
      limit: LIMIT,
      served_by: servedBy,
      routing_decision: { routing_path: decision }
    }
  };
}

module.exports = { assess, LIMIT };
