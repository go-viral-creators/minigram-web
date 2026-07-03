// /api/send-notification.js
//
// Sends an FCM push notification using firebase-admin.
// The service account key lives ONLY in Vercel's environment variables —
// it is NEVER shipped inside the Android APK anymore.
//
// Required Vercel Environment Variables (set in Vercel dashboard):
//   FIREBASE_SERVICE_ACCOUNT  -> paste the FULL contents of service_account.json
//   APP_SECRET                -> any random string you choose, e.g. a UUID.
//                                Must match APP_SECRET in FCMNotificationSender.java

const admin = require('firebase-admin');

let app;
function getAdminApp() {
  if (app) return app;
  const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
  app = admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
  });
  return app;
}

module.exports = async (req, res) => {
  if (req.method !== 'POST') {
    res.status(405).json({ error: 'Method not allowed' });
    return;
  }

  // ── Simple shared-secret check ──────────────────────────────────────────
  // Not as strong as verifying a Firebase Auth ID token, but it stops random
  // internet bots from hitting this endpoint. Worst case if this string
  // leaks: someone can send junk notifications through YOUR sender —
  // they can NOT read/write your database or steal user data, unlike the
  // old approach where the full service account key was exposed.
  const secret = req.headers['x-app-secret'];
  if (!secret || secret !== process.env.APP_SECRET) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }

  try {
    const { topic, token, title, body, image, type, extra, channel_name } = req.body || {};

    if (!topic && !token) {
      res.status(400).json({ error: 'topic or token is required' });
      return;
    }

    const message = {
      data: {
        title: title || '',
        body: body || '',
        image: image || '',
        type: type || '',
        extra: extra || '',
        channel_name: channel_name || '',
      },
      android: { priority: 'high' },
    };

    if (topic) message.topic = topic;
    else message.token = token;

    const admin_ = getAdminApp();
    const messageId = await admin_.messaging().send(message);

    res.status(200).json({ success: true, messageId });
  } catch (err) {
    console.error('send-notification error:', err);
    res.status(500).json({ error: err.message || 'Internal error' });
  }
};
