// The credit check as a plain Node.js server (no packages needed). Runs locally and on Render.
//
//   node examples/rest/credit-service.js     -> http://127.0.0.1:8080/credit/assess
//
// It listens on PORT (default 8080) on every interface, so the same file serves on a host such
// as Render, which sets PORT itself. See ServiceTutorial.md, "Call it over REST".

const http = require('http');
const { assess } = require('./credit');

const PORT = Number(process.env.PORT || 8080);

http.createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/') {
    response.writeHead(200, { 'Content-Type': 'text/plain' }).end('Credit service: POST /credit/assess\n');
    return;
  }
  if (request.method !== 'POST' || request.url !== '/credit/assess') {
    response.writeHead(404).end();
    return;
  }
  let body = '';
  request.on('data', chunk => body += chunk);
  request.on('end', () => {
    try {
      const result = assess(JSON.parse(body), 'node');
      console.log(result.creditDecision.application, result.creditDecision.amount, '->',
                  result.creditDecision.routing_decision.routing_path);
      response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(result));
    } catch (error) {
      response.writeHead(400, { 'Content-Type': 'text/plain' }).end(String(error));
    }
  });
}).listen(PORT, () => console.log('Credit service on port ' + PORT + ': POST /credit/assess'));
