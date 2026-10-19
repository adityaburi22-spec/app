// Serverless Stripe webhook (Vercel/Cloudflare free tier). Runs ONLY on payment events.
// Signs an Ed25519 license token the APK verifies offline. Scales to zero = $0 idle.
import Stripe from "stripe";
import nacl from "tweetnacl";

const stripe = new Stripe(process.env.STRIPE_SECRET);
const PRIVATE_KEY = Buffer.from(process.env.ED25519_PRIVATE_B64, "base64"); // 64-byte secret
const b64u = (b) => Buffer.from(b).toString("base64url");

function issueLicense(deviceId, days = 30) {
  const now = Math.floor(Date.now() / 1000);
  const payload = { sub: deviceId, iat: now, exp: now + days * 86400, plan: "pro" };
  const body = Buffer.from(JSON.stringify(payload)); // keys stable; mirror notebook separators if strict
  const sig = nacl.sign.detached(body, PRIVATE_KEY);
  return `${b64u(body)}.${b64u(sig)}`;
}

export default async function handler(req, res) {
  const event = stripe.webhooks.constructEvent(
    req.body, req.headers["stripe-signature"], process.env.STRIPE_WEBHOOK_SECRET);
  if (event.type === "checkout.session.completed" || event.type === "invoice.paid") {
    const deviceId = event.data.object.client_reference_id;        // set at checkout
    const token = issueLicense(deviceId, 30);
    // Deliver token: store for the app to pull on next online sync, or email/deep-link it.
    await deliver(deviceId, token);
  }
  res.json({ received: true });
}

async function deliver(deviceId, token) { /* write to KV / push / email */ }
