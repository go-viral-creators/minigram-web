// /api/upload-media.js
//
// Uploads a base64 file (e.g. a voice note recording) to a GitHub repo
// using the GitHub REST API, then returns a free jsDelivr CDN URL that
// serves the file instantly worldwide — no Firebase Storage, no card needed.
//
// Required Vercel Environment Variables:
//   GITHUB_TOKEN     -> a GitHub Personal Access Token (fine-grained,
//                       scoped to ONLY the "Contents: Read and write"
//                       permission on ONE repo — see setup notes below)
//   GITHUB_REPO      -> e.g. "yourusername/minigram-media"
//   GITHUB_BRANCH    -> e.g. "main"
//   APP_SECRET       -> same shared secret used by send-notification.js

module.exports = async (req, res) => {
  if (req.method !== 'POST') {
    res.status(405).json({ error: 'Method not allowed' });
    return;
  }

  const secret = req.headers['x-app-secret'];
  if (!secret || secret !== process.env.APP_SECRET) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }

  try {
    const { base64Data, fileName, folder } = req.body || {};
    if (!base64Data || !fileName) {
      res.status(400).json({ error: 'base64Data and fileName are required' });
      return;
    }

    const repo   = process.env.GITHUB_REPO;
    const branch = process.env.GITHUB_BRANCH || 'main';
    const path   = `${folder ? folder.replace(/^\/|\/$/g, '') + '/' : 'media/'}${Date.now()}_${fileName}`;

    const ghRes = await fetch(`https://api.github.com/repos/${repo}/contents/${path}`, {
      method: 'PUT',
      headers: {
        Authorization: `Bearer ${process.env.GITHUB_TOKEN}`,
        'Content-Type': 'application/json',
        Accept: 'application/vnd.github+json',
      },
      body: JSON.stringify({
        message: `upload: ${path}`,
        content: base64Data,   // raw base64, no data: prefix
        branch,
      }),
    });

    const ghJson = await ghRes.json();
    if (!ghRes.ok) {
      console.error('GitHub upload failed:', ghJson);
      res.status(500).json({ error: ghJson.message || 'GitHub upload failed' });
      return;
    }

    // jsDelivr free CDN — serves any public GitHub repo file, cached globally.
    const cdnUrl = `https://cdn.jsdelivr.net/gh/${repo}@${branch}/${path}`;

    res.status(200).json({ success: true, url: cdnUrl });
  } catch (err) {
    console.error('upload-media error:', err);
    res.status(500).json({ error: err.message || 'Internal error' });
  }
};
