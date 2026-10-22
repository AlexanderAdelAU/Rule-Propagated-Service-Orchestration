// The credit check (ServiceTutorial.md) and the claims assessment (Tutorial.md, Example 2) as a plain
// Node.js server (no packages needed). Runs locally and on Render.
//
//   node examples/rest/credit-service.js     -> http://127.0.0.1:8080/credit/assess
//                                               http://127.0.0.1:8080/claims/assess
//
// It listens on PORT (default 8080) on every interface, so the same file serves on a host such
// as Render, which sets PORT itself. See ServiceTutorial.md, "Call it over REST".

const http = require('http');
const services = {
  '/credit/assess': { assess: require('./credit').assess, result: 'creditDecision', item: 'application' },
  '/claims/assess': { assess: require('./claims').assess, result: 'token', item: 'claim' }
};

const PORT = Number(process.env.PORT || 8080);

http.createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/') {
    response.writeHead(200, { 'Content-Type': 'text/plain' })
      .end('Credit service: POST /credit/assess\nClaims service: POST /claims/assess\n');
    return;
  }
  const service = services[request.url];
  if (request.method !== 'POST' || !service) {
    response.writeHead(404).end();
    return;
  }
  let body = '';
  request.on('data', chunk => body += chunk);
  request.on('end', () => {
    try {
      const result = service.assess(JSON.parse(body), 'node');
      const answer = result[service.result];
      console.log(request.url, answer[service.item], answer.amount, '->', answer.routing_decision.routing_path);
      response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(result));
    } catch (error) {
      response.writeHead(400, { 'Content-Type': 'text/plain' }).end(String(error));
    }
  });
}).listen(PORT, () => console.log('Credit and claims services on port ' + PORT + ': POST /credit/assess, POST /claims/assess'));
