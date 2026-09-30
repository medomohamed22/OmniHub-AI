import { randomBytes } from 'node:crypto';

function parseCookies(header = '') {
  return Object.fromEntries(
    header
      .split(';')
      .map((v) => v.trim())
      .filter(Boolean)
      .map((v) => {
        const i = v.indexOf('=');
        return i < 0 ? [v, ''] : [v.slice(0, i), decodeURIComponent(v.slice(i + 1))];
      }),
  );
}

function secure(req) {
  return String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim() === 'https' || process.env.VERCEL === '1';
}

export default function handler(req, res) {
  let sid = parseCookies(req.headers.cookie || '').aiway_sid;

  if (!sid || !/^[A-Za-z0-9_-]{32,128}$/.test(sid)) {
    sid = randomBytes(32).toString('base64url');
    res.setHeader(
      'Set-Cookie',
      `aiway_sid=${sid}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict${secure(req) ? '; Secure' : ''}`,
    );
  }

  res.setHeader('Cache-Control', 'no-store');
  res.statusCode = 204;
  res.end();
}
