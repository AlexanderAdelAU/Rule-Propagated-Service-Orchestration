// The credit check from ServiceTutorial.md, as a REST service in Node.js (no packages needed).
//
// RPSO's rest adapter POSTs each call here:
//   {"service": "CreditLimitService", "operation": "assess", "sequenceId": "1030000", "version": "v001",
//    "inputs": {"token": {...}}}
// and publishes the answer, which has the same shape a Java service returns:
//   {"creditDecision": {..., "routing_decision": {"routing_path": "approve"}}}
//
// Run:  node examples/rest/credit-service.js        (listens on http://127.0.0.1:8080/credit/assess; PORT=... to change)

const http = require('http');

const LIMIT = 5000;
const PORT = Number(process.env.PORT || 8080);

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

function assess(call) {
  const application = applicationNumber(call.sequenceId);
  const given = Number(find(call.inputs.token, 'amount'));
  const amount = Number.isFinite(given) ? given : application * 1000;
  const decision = amount <= LIMIT ? 'approve' : 'refer';
  return {
    creditDecision: {
      application: 'APP-' + application,
      amount: amount,
      limit: LIMIT,
      served_by: 'node',
      routing_decision: { routing_path: decision }
    }
  };
}

http.createServer((request, response) => {
  if (request.method !== 'POST' || request.url !== '/credit/assess') {
    response.writeHead(404).end();
    return;
  }
  let body = '';
  request.on('data', chunk => body += chunk);
  request.on('end', () => {
    try {
      const result = assess(JSON.parse(body));
      console.log(result.creditDecision.application, result.creditDecision.amount, '->',
                  result.creditDecision.routing_decision.routing_path);
      response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(result));
    } catch (error) {
      response.writeHead(400, { 'Content-Type': 'text/plain' }).end(String(error));
    }
  });
}).listen(PORT, '127.0.0.1', () => console.log('Credit service on http://127.0.0.1:' + PORT + '/credit/assess'));
