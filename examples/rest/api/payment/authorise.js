// The mock payment service as a Vercel function. With the project's root directory set to examples/rest, it answers
// POST https://<project>.vercel.app/api/payment/authorise. See payment.js for how it behaves.

const { plan, authorise } = require('../../payment');

module.exports = async (request, response) => {
  if (request.method !== 'POST') {
    response.status(405).send('POST a call to this address: a mock payment service');
    return;
  }
  try {
    // Vercel parses a JSON body; parse it here if it arrives as text.
    const call = typeof request.body === 'string' ? JSON.parse(request.body) : request.body;
    await new Promise(done => setTimeout(done, plan(call).delayMs));
    const answer = authorise(call, 'vercel');
    if (answer.status === 200) response.status(200).json(answer.body);
    else response.status(answer.status).send(answer.body);
  } catch (error) {
    response.status(400).send(String(error));
  }
};
