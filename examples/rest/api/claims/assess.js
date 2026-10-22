// The claims assessment as a Vercel function. With the project's root directory set to examples/rest,
// it answers POST https://<project>.vercel.app/api/claims/assess. See Tutorial.md, Example 2.

const { assess } = require('../../claims');

module.exports = (request, response) => {
  if (request.method !== 'POST') {
    response.status(405).send('POST a call to this address');
    return;
  }
  try {
    // Vercel parses a JSON body; parse it here if it arrives as text.
    const call = typeof request.body === 'string' ? JSON.parse(request.body) : request.body;
    response.status(200).json(assess(call, 'vercel'));
  } catch (error) {
    response.status(400).send(String(error));
  }
};
