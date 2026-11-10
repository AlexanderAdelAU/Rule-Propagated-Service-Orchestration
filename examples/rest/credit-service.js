// The credit check (ServiceTutorial.md), the claims assessment (Tutorial.md, Example 2) and a mock payment
// service (payment.js) as a plain Node.js server (no packages needed). Runs locally and on Render.
//
//   node examples/rest/credit-service.js     -> http://127.0.0.1:8080/credit/assess
//                                               http://127.0.0.1:8080/claims/assess
//                                               http://127.0.0.1:8080/payment/authorise
//                                               http://127.0.0.1:8080/v1/payments  (an API not written for RPSO)
//
// It listens on PORT (default 8080) on every interface, so the same file serves on a host such
// as Render, which sets PORT itself. See ServiceTutorial.md, "Call it over REST".

const http = require('http');
const services = {
  '/credit/assess': { assess: require('./credit').assess, result: 'creditDecision', item: 'application' },
  '/claims/assess': { assess: require('./claims').assess, result: 'token', item: 'claim' }
};

const payment = require('./payment');
const cardPayments = require('./card-payments');
const PORT = Number(process.env.PORT || 8080);

http.createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/') {
    response.writeHead(200, { 'Content-Type': 'text/plain' })
      .end('Credit service: POST /credit/assess\nClaims service: POST /claims/assess\nPayment service: POST /payment/authorise\nCard-payments API: POST /v1/payments\n');
    return;
  }
  if (request.method === 'POST' && request.url === '/payment/authorise') {
    let body = '';
    request.on('data', chunk => body += chunk);
    request.on('end', () => {
      let call;
      try { call = JSON.parse(body); } catch (error) { response.writeHead(400).end(String(error)); return; }
      const p = payment.plan(call);
      // Answer after the payment's delay, as a real gateway would.
      setTimeout(() => {
        const answer = payment.authorise(call, 'node');
        console.log('/payment/authorise', 'PAY-' + p.payment, p.outcome, p.delayMs + ' ms');
        if (answer.status === 200) response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(answer.body));
        else response.writeHead(answer.status, { 'Content-Type': 'text/plain' }).end(answer.body);
      }, p.delayMs);
    });
    return;
  }
  if (request.method === 'POST' && request.url === '/v1/payments') {
    // The card-payments API in the ordinary REST style (card-payments.js): it knows nothing about RPSO.
    let body = '';
    request.on('data', chunk => body += chunk);
    request.on('end', () => {
      let given;
      try { given = JSON.parse(body); } catch (error) {
        response.writeHead(400, { 'Content-Type': 'application/json' })
          .end(JSON.stringify({ error: { type: 'invalid_request', message: 'The body is not JSON' } }));
        return;
      }
      setTimeout(() => {
        const answer = cardPayments.create(request.headers, given, 'node');
        console.log('/v1/payments', given.reference, answer.status, answer.body.status || answer.body.error.type);
        response.writeHead(answer.status, { 'Content-Type': 'application/json' }).end(JSON.stringify(answer.body));
      }, cardPayments.plan(given).delayMs);
    });
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
}).listen(PORT, () => console.log('Credit, claims and payment services on port ' + PORT + ': POST /credit/assess, POST /claims/assess, POST /payment/authorise, POST /v1/payments'));
