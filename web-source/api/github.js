import { createCipheriv, createDecipheriv, createHash, randomBytes, timingSafeEqual } from 'node:crypto';

const API = 'https://api.github.com';
const API_VERSION = '2022-11-28';
const MAX_FILES = 200;
const MAX_FILE_BYTES = 2 * 1024 * 1024;
const MAX_TOTAL_BYTES = 12 * 1024 * 1024;
const TEXT_RE = /(^|\/)(\.gitignore|\.npmrc|\.editorconfig|Dockerfile|Makefile)$|\.(html?|css|scss|sass|less|js|jsx|mjs|cjs|ts|tsx|json|md|mdx|txt|svg|py|rb|php|java|kt|kts|c|h|cpp|hpp|cs|go|rs|sh|bash|zsh|yml|yaml|xml|csv|sql|toml|ini|env|vue|svelte|astro|graphql|gql|prisma)$/i;

function cookies(header = '') {
  return Object.fromEntries(header.split(';').map(v => v.trim()).filter(Boolean).map(v => {
    const i = v.indexOf('=');
    return i < 0 ? [v, ''] : [v.slice(0, i), decodeURIComponent(v.slice(i + 1))];
  }));
}

function secure(req) {
  return String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim() === 'https' || process.env.VERCEL === '1';
}

function cookie(name, value, req, { maxAge = 2592000, sameSite = 'Lax' } = {}) {
  return `${name}=${encodeURIComponent(value)}; Path=/; Max-Age=${maxAge}; HttpOnly; SameSite=${sameSite}${secure(req) ? '; Secure' : ''}`;
}

function key() {
  const secret = process.env.AIWAY_CREDENTIAL_KEY;
  return secret ? createHash('sha256').update(secret).digest() : null;
}

function seal(value) {
  const k = key();
  if (!k) throw new Error('AIWAY_CREDENTIAL_KEY is not configured');
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', k, iv);
  const body = Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()]);
  return Buffer.concat([Buffer.from('GH1'), iv, cipher.getAuthTag(), body]).toString('base64url');
}

function unseal(value) {
  try {
    const k = key();
    if (!k || !value) return null;
    const raw = Buffer.from(value, 'base64url');
    if (raw.subarray(0, 3).toString() !== 'GH1') return null;
    const iv = raw.subarray(3, 15), tag = raw.subarray(15, 31), body = raw.subarray(31);
    const decipher = createDecipheriv('aes-256-gcm', k, iv);
    decipher.setAuthTag(tag);
    return JSON.parse(Buffer.concat([decipher.update(body), decipher.final()]).toString('utf8'));
  } catch { return null; }
}

function json(res, status, body) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.status(status).json(body);
}

function origin(req) {
  const proto = String(req.headers['x-forwarded-proto'] || (secure(req) ? 'https' : 'http')).split(',')[0].trim();
  return `${proto}://${req.headers.host}`;
}

function callbackUrl(req) {
  return process.env.GITHUB_OAUTH_CALLBACK_URL || `${origin(req)}/api/github?action=callback`;
}

function configured() {
  return Boolean(process.env.GITHUB_CLIENT_ID && process.env.GITHUB_CLIENT_SECRET && process.env.AIWAY_CREDENTIAL_KEY);
}

async function gh(path, token, options = {}) {
  const response = await fetch(`${API}${path}`, {
    ...options,
    headers: {
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': API_VERSION,
      'User-Agent': 'AiWay',
      Authorization: `Bearer ${token}`,
      ...(options.headers || {}),
    },
  });
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!response.ok) {
    const error = new Error(data?.message || `GitHub ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return data;
}

async function identity(token) {
  const user = await gh('/user', token);
  return { id: user.id, login: user.login, name: user.name || user.login, avatar: user.avatar_url || '' };
}

function auth(req) {
  const payload = unseal(cookies(req.headers.cookie || '').aiway_gh);
  if (!payload?.token || !payload?.user?.login) return null;
  return payload;
}

function sameState(a, b) {
  if (!a || !b) return false;
  const aa = Buffer.from(String(a)), bb = Buffer.from(String(b));
  return aa.length === bb.length && timingSafeEqual(aa, bb);
}

function validRepo(value) {
  return /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(String(value || ''));
}

function validBranch(value) {
  const v = String(value || '');
  return v.length > 0 && v.length <= 255 && !v.startsWith('-') && !v.includes('..') && !/[~^:?*\[\\\s]/.test(v) && !v.endsWith('/') && !v.endsWith('.lock');
}

function safeFilePath(value) {
  const p = String(value || '').replace(/\\/g, '/').replace(/^\.\//, '');
  if (!p || p.startsWith('/') || p.includes('\0') || p.split('/').includes('..')) throw new Error('Invalid file path');
  return p;
}

async function readBody(req) {
  if (req.body && typeof req.body === 'object') return req.body;
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 16 * 1024 * 1024) throw new Error('Request too large');
  }
  return raw ? JSON.parse(raw) : {};
}

async function repositoryTree(token, repo, branch) {
  const ref = await gh(`/repos/${repo}/git/ref/heads/${encodeURIComponent(branch)}`, token);
  const headSha = ref.object.sha;
  const commit = await gh(`/repos/${repo}/git/commits/${headSha}`, token);
  const tree = await gh(`/repos/${repo}/git/trees/${commit.tree.sha}?recursive=1`, token);
  const files = (tree.tree || [])
    .filter(x => x.type === 'blob' && x.mode !== '120000' && (x.size ?? 0) <= MAX_FILE_BYTES && TEXT_RE.test(x.path))
    .map(x => ({ path: x.path, size: x.size || 0, sha: x.sha, mode: x.mode === '100755' ? '100755' : '100644' }));
  return { headSha, files, truncated: Boolean(tree.truncated), total: files.length };
}

async function mapLimit(items, limit, worker) {
  const results = new Array(items.length);
  let cursor = 0;
  async function run() {
    while (true) {
      const index = cursor++;
      if (index >= items.length) return;
      results[index] = await worker(items[index], index);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, run));
  return results;
}

async function loadRepository(token, repo, branch, requestedPaths = null) {
  const meta = await repositoryTree(token, repo, branch);
  const wanted = Array.isArray(requestedPaths) && requestedPaths.length
    ? new Set(requestedPaths.map(safeFilePath))
    : null;
  let candidates = meta.files.filter(item => !wanted || wanted.has(item.path));
  if (wanted) {
    const available = new Set(candidates.map(x => x.path));
    const missing = [...wanted].filter(path => !available.has(path));
    if (missing.length) throw new Error(`بعض الملفات لم تعد موجودة أو غير قابلة للتحميل: ${missing.slice(0, 5).join(', ')}`);
  }
  if (meta.truncated && !wanted) throw new Error('المستودع كبير جداً للتحميل الكامل. استخدم "اختيار الملفات" وحدد الملفات المطلوبة.');
  if (candidates.length > MAX_FILES) throw new Error(`اختر ${MAX_FILES} ملفاً أو أقل قبل التحميل.`);
  const estimated = candidates.reduce((sum, item) => sum + (item.size || 0), 0);
  if (estimated > MAX_TOTAL_BYTES) throw new Error('حجم الملفات المحددة يتجاوز الحد المسموح (12MB).');

  const loaded = await mapLimit(candidates, 8, async (item) => {
    const blob = await gh(`/repos/${repo}/git/blobs/${item.sha}`, token);
    if (blob.encoding !== 'base64') return null;
    const buf = Buffer.from(String(blob.content || '').replace(/\n/g, ''), 'base64');
    if (buf.includes(0)) return null;
    return { path: item.path, text: buf.toString('utf8'), bytes: buf.length, mode: item.mode };
  });

  const files = {}, fileModes = {};
  let total = 0;
  for (const item of loaded.filter(Boolean)) {
    total += item.bytes;
    if (total > MAX_TOTAL_BYTES) throw new Error('حجم ملفات المشروع يتجاوز الحد المسموح (12MB).');
    files[item.path] = item.text;
    fileModes[item.path] = item.mode;
  }
  return { files, fileModes, loadedPaths: Object.keys(files), headSha: meta.headSha, count: Object.keys(files).length, bytes: total, truncated: meta.truncated };
}

async function pushRepository(token, { repo, branch, files, fileModes = {}, loadedPaths = [], baseSha, message }) {
  if (!files || typeof files !== 'object' || Array.isArray(files)) throw new Error('Invalid files');
  const entries = Object.entries(files);
  if (entries.length > MAX_FILES) throw new Error(`Too many files (max ${MAX_FILES})`);
  let total = 0;
  for (const [path, content] of entries) {
    safeFilePath(path);
    const size = Buffer.byteLength(String(content ?? ''), 'utf8');
    if (size > MAX_FILE_BYTES) throw new Error(`File too large: ${path}`);
    total += size;
  }
  if (total > MAX_TOTAL_BYTES) throw new Error('Workspace is too large');

  const ref = await gh(`/repos/${repo}/git/ref/heads/${encodeURIComponent(branch)}`, token);
  const parent = ref.object.sha;
  if (baseSha && parent !== baseSha) {
    const error = new Error('الفرع تغيّر على GitHub منذ آخر تحميل. اسحب أحدث نسخة قبل Push لتجنب الكتابة فوق تغييرات جديدة.');
    error.status = 409;
    throw error;
  }
  const parentCommit = await gh(`/repos/${repo}/git/commits/${parent}`, token);
  const treeItems = [];
  const currentPaths = new Set(entries.map(([p]) => p));
  for (const old of loadedPaths) {
    const p = safeFilePath(old);
    if (!currentPaths.has(p)) treeItems.push({ path: p, mode: '100644', type: 'blob', sha: null });
  }
  for (const [path, content] of entries) {
    const blob = await gh(`/repos/${repo}/git/blobs`, token, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: Buffer.from(String(content ?? ''), 'utf8').toString('base64'), encoding: 'base64' }),
    });
    const mode = fileModes?.[path] === '100755' ? '100755' : '100644';
    treeItems.push({ path, mode, type: 'blob', sha: blob.sha });
  }
  const tree = await gh(`/repos/${repo}/git/trees`, token, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ base_tree: parentCommit.tree.sha, tree: treeItems }),
  });
  const commit = await gh(`/repos/${repo}/git/commits`, token, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: String(message || 'Update from AiWay').slice(0, 500), tree: tree.sha, parents: [parent] }),
  });
  await gh(`/repos/${repo}/git/refs/heads/${encodeURIComponent(branch)}`, token, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sha: commit.sha, force: false }),
  });
  return { sha: commit.sha, headSha: commit.sha, loadedPaths: Object.keys(files) };
}

export default async function handler(req, res) {
  const action = String(req.query?.action || 'status');
  try {
    if (action === 'login') {
      if (!configured()) return json(res, 503, { error: 'GitHub OAuth غير مُعدّ على الخادم.' });
      const state = randomBytes(24).toString('base64url');
      res.setHeader('Set-Cookie', cookie('aiway_gh_state', state, req, { maxAge: 600, sameSite: 'Lax' }));
      const u = new URL('https://github.com/login/oauth/authorize');
      u.searchParams.set('client_id', process.env.GITHUB_CLIENT_ID);
      u.searchParams.set('redirect_uri', callbackUrl(req));
      u.searchParams.set('scope', 'repo read:user');
      u.searchParams.set('state', state);
      u.searchParams.set('allow_signup', 'true');
      res.redirect(302, u.toString());
      return;
    }

    if (action === 'callback') {
      const c = cookies(req.headers.cookie || '');
      const state = String(req.query?.state || ''), code = String(req.query?.code || '');
      if (!configured() || !code || !sameState(state, c.aiway_gh_state)) {
        res.setHeader('Set-Cookie', cookie('aiway_gh_state', '', req, { maxAge: 0 }));
        res.redirect(302, '/?github=error');
        return;
      }
      const exchange = await fetch('https://github.com/login/oauth/access_token', {
        method: 'POST',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'User-Agent': 'AiWay' },
        body: JSON.stringify({ client_id: process.env.GITHUB_CLIENT_ID, client_secret: process.env.GITHUB_CLIENT_SECRET, code, redirect_uri: callbackUrl(req) }),
      });
      const tokenData = await exchange.json();
      if (!exchange.ok || !tokenData.access_token) throw new Error(tokenData.error_description || tokenData.error || 'GitHub token exchange failed');
      const user = await identity(tokenData.access_token);
      res.setHeader('Set-Cookie', [
        cookie('aiway_gh', seal({ token: tokenData.access_token, user, createdAt: Date.now() }), req, { maxAge: 2592000, sameSite: 'Lax' }),
        cookie('aiway_gh_state', '', req, { maxAge: 0, sameSite: 'Lax' }),
      ]);
      res.redirect(302, '/?github=connected');
      return;
    }

    if (action === 'logout') {
      if (req.method !== 'POST') return json(res, 405, { error: 'Method not allowed' });
      res.setHeader('Set-Cookie', cookie('aiway_gh', '', req, { maxAge: 0, sameSite: 'Lax' }));
      return json(res, 200, { ok: true });
    }

    if (action === 'status') {
      const session = auth(req);
      return json(res, 200, { configured: configured(), connected: Boolean(session), user: session?.user || null });
    }

    const session = auth(req);
    if (!session) return json(res, 401, { error: 'سجل الدخول إلى GitHub أولاً.' });

    if (action === 'repos') {
      const repos = [];
      for (let page = 1; page <= 10; page++) {
        const batch = await gh(`/user/repos?per_page=100&page=${page}&sort=updated&affiliation=owner,collaborator,organization_member`, session.token);
        repos.push(...batch);
        if (batch.length < 100) break;
      }
      return json(res, 200, { repos: repos.map(r => ({ full_name: r.full_name, private: r.private, default_branch: r.default_branch, permissions: r.permissions || {}, updated_at: r.updated_at })) });
    }

    if (action === 'branches') {
      const repo = String(req.query?.repo || '');
      if (!validRepo(repo)) return json(res, 400, { error: 'Invalid repository' });
      const branches = [];
      for (let page = 1; page <= 5; page++) {
        const batch = await gh(`/repos/${repo}/branches?per_page=100&page=${page}`, session.token);
        branches.push(...batch);
        if (batch.length < 100) break;
      }
      return json(res, 200, { branches: branches.map(b => ({ name: b.name, protected: Boolean(b.protected) })) });
    }

    if (action === 'tree') {
      const repo = String(req.query?.repo || ''), branch = String(req.query?.branch || '');
      if (!validRepo(repo) || !validBranch(branch)) return json(res, 400, { error: 'Invalid repository or branch' });
      const tree = await repositoryTree(session.token, repo, branch);
      return json(res, 200, { headSha: tree.headSha, files: tree.files.map(({ path, size, mode }) => ({ path, size, mode })), truncated: tree.truncated, total: tree.total });
    }

    if (action === 'load') {
      let repo, branch, paths = null;
      if (req.method === 'POST') {
        const body = await readBody(req);
        repo = String(body.repo || ''); branch = String(body.branch || ''); paths = Array.isArray(body.paths) ? body.paths : null;
      } else {
        repo = String(req.query?.repo || ''); branch = String(req.query?.branch || '');
      }
      if (!validRepo(repo) || !validBranch(branch)) return json(res, 400, { error: 'Invalid repository or branch' });
      return json(res, 200, await loadRepository(session.token, repo, branch, paths));
    }

    if (action === 'push' && req.method === 'POST') {
      const body = await readBody(req);
      if (!validRepo(body.repo) || !validBranch(body.branch)) return json(res, 400, { error: 'Invalid repository or branch' });
      return json(res, 200, await pushRepository(session.token, body));
    }

    return json(res, 404, { error: 'Unknown GitHub action' });
  } catch (error) {
    console.error('[github]', error);
    return json(res, error.status || 500, { error: error.message || 'GitHub request failed' });
  }
}
