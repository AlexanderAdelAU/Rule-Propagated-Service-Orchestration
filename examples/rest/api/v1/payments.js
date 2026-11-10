// The mock card-payments API (card-payments.js) as a Vercel function. With the project's root directory set to
// examples/rest, it answers POST https://<project>.vercel.app/api/v1/payments. It knows nothing about RPSO.

const { plan, create } = require('../../card-payments');

module.exports = async (request, response) => {
  if (request.method !== 'POST') {
    response.status(405).json({ error: { type: 'invalid_request', message: 'POST a payment to this address' } });
    return;
  }
  let body;
  try {
    // Vercel parses a JSON body; parse it here if it arrives as text.
    body = typeof request.body === 'string' ? JSON.parse(request.body) : request.body;
  } catch (error) {
    response.status(400).json({ error: { type: 'invalid_request', message: 'The body is not JSON' } });
    return;
  }
  await new Promise(done => setTimeout(done, plan(body).delayMs));
  const answer = create(request.headers, body, 'vercel');
  response.status(answer.status).json(answer.body);
};
