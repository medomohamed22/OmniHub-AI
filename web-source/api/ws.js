import express from 'express';
import { createServer } from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { spawn } from 'node:child_process';
import { createHash, createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { mkdtemp, mkdir, readFile, writeFile, rm, stat, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, dirname, normalize, relative, isAbsolute } from 'node:path';
import { createRequire } from 'node:module';
import { get, put, del } from '@vercel/blob';

const require = createRequire(import.meta.url);
const app = express();
const server = createServer(app);
const wss = new WebSocketServer({ server, maxPayload: 16 * 1024 * 1024 });

app.get('/api/ws', (_req, res) => res.status(426).json({ error: 'WebSocket upgrade required' }));
app.get('/', (_req, res) => res.status(426).json({ error: 'WebSocket upgrade required' }));

function cookies(header = '') {
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

function sessionHash(sid) {
  return createHash('sha256').update(sid).digest('hex');
}

function encryptionKey() {
  const value = process.env.AIWAY_CREDENTIAL_KEY;
  return value ? createHash('sha256').update(value).digest() : null;
}

function encrypt(buf) {
  const key = encryptionKey();
  if (!key) throw new Error('AIWAY_CREDENTIAL_KEY is not configured');
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  const body = Buffer.concat([cipher.update(buf), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([Buffer.from('AIWAY1'), iv, tag, body]);
}

function decrypt(buf) {
  const key = encryptionKey();
  if (!key) throw new Error('AIWAY_CREDENTIAL_KEY is not configured');
  if (buf.subarray(0, 6).toString() !== 'AIWAY1') throw new Error('Invalid encrypted credential format');
  const iv = buf.subarray(6, 18);
  const tag = buf.subarray(18, 34);
  const body = buf.subarray(34);
  const decipher = createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(body), decipher.final()]);
}

async function streamToBuffer(stream) {
  const chunks = [];
  for await (const chunk of stream) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks);
}

function blobPath(hash) {
  return `codex-auth/${hash}.bin`;
}

function persistenceConfigured(req) {
  return Boolean(
    process.env.AIWAY_CREDENTIAL_KEY &&
      (process.env.BLOB_READ_WRITE_TOKEN ||
        req?.headers?.['x-vercel-oidc-token'] ||
        process.env.VERCEL_OIDC_TOKEN),
  );
}

async function restoreAuth(hash, codexHome, enabled) {
  if (!enabled) return false;
  try {
    const result = await get(blobPath(hash), { access: 'private', useCache: false });
    if (!result) return false;
    const encrypted = await streamToBuffer(result.stream);
    await writeFile(join(codexHome, 'auth.json'), decrypt(encrypted), { mode: 0o600 });
    return true;
  } catch (error) {
    if (String(error?.message || error).match(/404|not found|BlobNotFound/i)) return false;
    console.error('restoreAuth', error);
    return false;
  }
}

async function persistAuth(hash, codexHome, enabled) {
  if (!enabled) return false;
  try {
    const authPath = join(codexHome, 'auth.json');
    await stat(authPath);
    const raw = await readFile(authPath);
    await put(blobPath(hash), encrypt(raw), {
      access: 'private',
      allowOverwrite: true,
      contentType: 'application/octet-stream',
      cacheControlMaxAge: 60,
    });
    return true;
  } catch (error) {
    if (error?.code !== 'ENOENT') console.error('persistAuth', error);
    return false;
  }
}

async function removePersistedAuth(hash, enabled) {
  if (!enabled) return;
  try {
    await del(blobPath(hash));
  } catch (error) {
    console.error('removePersistedAuth', error);
  }
}

function codexBin() {
  const packageJson = require.resolve('@openai/codex/package.json');
  return join(dirname(packageJson), 'bin', 'codex.js');
}

const MAX_WORKSPACE_FILES = 200;
const MAX_WORKSPACE_FILE_BYTES = 2 * 1024 * 1024;
const MAX_WORKSPACE_TOTAL_BYTES = 12 * 1024 * 1024;

function safeWorkspaceRelativePath(value) {
  const raw = String(value || '').replace(/\\/g, '/').replace(/^\.\//, '');
  if (!raw || raw.includes('\0') || raw.startsWith('/') || raw.split('/').includes('..')) {
    throw new Error('Invalid workspace path');
  }
  const clean = normalize(raw).replace(/\\/g, '/');
  if (!clean || clean === '.' || clean.startsWith('../') || isAbsolute(clean)) {
    throw new Error('Invalid workspace path');
  }
  return clean;
}

async function replaceWorkspace(workDir, files) {
  if (!files || typeof files !== 'object' || Array.isArray(files)) throw new Error('files must be an object');
  const entries = Object.entries(files);
  if (entries.length > MAX_WORKSPACE_FILES) throw new Error(`Too many files (max ${MAX_WORKSPACE_FILES})`);
  let total = 0;
  const prepared = [];
  for (const [name, value] of entries) {
    const rel = safeWorkspaceRelativePath(name);
    const content = Buffer.from(String(value ?? ''), 'utf8');
    if (content.length > MAX_WORKSPACE_FILE_BYTES) throw new Error(`File too large: ${rel}`);
    total += content.length;
    if (total > MAX_WORKSPACE_TOTAL_BYTES) throw new Error('Workspace is too large');
    prepared.push([rel, content]);
  }
  await rm(workDir, { recursive: true, force: true });
  await mkdir(workDir, { recursive: true });
  for (const [rel, content] of prepared) {
    const target = join(workDir, rel);
    const relCheck = relative(workDir, target);
    if (!relCheck || relCheck.startsWith('..') || isAbsolute(relCheck)) throw new Error('Invalid workspace path');
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, content, { mode: 0o600 });
  }
  return { count: prepared.length, bytes: total };
}

async function readWorkspace(workDir) {
  const files = {};
  let count = 0;
  let total = 0;
  async function walk(dir, prefix = '') {
    const entries = await readdir(dir, { withFileTypes: true });
    for (const entry of entries) {
      if (entry.name === '.git' || entry.name === 'node_modules') continue;
      const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        await walk(full, rel);
      } else if (entry.isFile()) {
        count += 1;
        if (count > MAX_WORKSPACE_FILES) throw new Error(`Too many files (max ${MAX_WORKSPACE_FILES})`);
        const buf = await readFile(full);
        if (buf.length > MAX_WORKSPACE_FILE_BYTES) throw new Error(`File too large: ${rel}`);
        total += buf.length;
        if (total > MAX_WORKSPACE_TOTAL_BYTES) throw new Error('Workspace is too large');
        files[rel] = buf.toString('utf8');
      }
    }
  }
  await walk(workDir);
  return { files, count, bytes: total };
}

wss.on('connection', async (ws, req) => {
  const requestOrigin = String(req.headers.origin || '');
  if (requestOrigin) {
    try {
      const u = new URL(requestOrigin);
      if (u.host !== req.headers.host || !['http:', 'https:'].includes(u.protocol)) {
        ws.close(4403, 'Origin not allowed');
        return;
      }
    } catch {
      ws.close(4403, 'Invalid origin');
      return;
    }
  }

  const sid = cookies(req.headers.cookie || '').aiway_sid;
  if (!sid || !/^[A-Za-z0-9_-]{32,128}$/.test(sid)) {
    ws.close(4401, 'Session cookie required');
    return;
  }

  const hash = sessionHash(sid);
  const canPersist = persistenceConfigured(req);
  const codexHome = await mkdtemp(join(tmpdir(), `aiway-${hash.slice(0, 12)}-`));
  const workDir = join(codexHome, 'workspace');
  await mkdir(workDir, { recursive: true });

  await writeFile(
    join(codexHome, 'config.toml'),
    'cli_auth_credentials_store = "file"\ncheck_for_update_on_startup = false\n',
    { mode: 0o600 },
  );

  const restored = await restoreAuth(hash, codexHome, canPersist);
  const child = spawn(process.execPath, [codexBin(), 'app-server', '--listen', 'stdio://'], {
    cwd: workDir,
    env: { ...process.env, CODEX_HOME: codexHome, HOME: codexHome },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  let stdoutBuffer = '';
  let closed = false;
  let saveTimer = null;

  const safeSend = (obj) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(typeof obj === 'string' ? obj : JSON.stringify(obj));
    }
  };

  const wsLifetimeMs = Math.max(60000, Math.min(290000, Number(process.env.AIWAY_WS_LIFETIME_MS || 280000)));
  const reconnectSuggestedMs = Math.max(30000, wsLifetimeMs - 40000);
  const reconnectSuggestedTimer = setTimeout(() => {
    safeSend({ method: 'aiway/reconnectSuggested', params: { reason: 'function-lifetime', inMs: 40000 } });
  }, reconnectSuggestedMs);
  const reconnectRequiredTimer = setTimeout(() => {
    safeSend({ method: 'aiway/reconnectRequired', params: { reason: 'function-lifetime' } });
  }, wsLifetimeMs);
  reconnectSuggestedTimer.unref?.();
  reconnectRequiredTimer.unref?.();

  safeSend({
    method: 'aiway/server',
    params: { persistence: canPersist, restored, workDir: '/workspace', wsLifetimeMs },
  });

  const schedulePersist = () => {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      persistAuth(hash, codexHome, canPersist).catch(() => {});
    }, 700);
  };

  child.stdout.setEncoding('utf8');
  child.stdout.on('data', (chunk) => {
    stdoutBuffer += chunk;
    let index;
    while ((index = stdoutBuffer.indexOf('\n')) >= 0) {
      const line = stdoutBuffer.slice(0, index).trim();
      stdoutBuffer = stdoutBuffer.slice(index + 1);
      if (!line) continue;
      safeSend(line);
      try {
        const message = JSON.parse(line);
        if (message.method === 'account/login/completed' && message.params?.success) schedulePersist();
        if (message.method === 'account/updated') schedulePersist();
      } catch {}
    }
  });

  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (data) => console.error('[codex]', String(data).slice(0, 2000)));
  child.on('error', (error) => {
    safeSend({ method: 'aiway/error', params: { message: error.message } });
  });
  child.on('exit', (code, signal) => {
    safeSend({ method: 'aiway/codexExited', params: { code, signal } });
    if (!closed && ws.readyState === WebSocket.OPEN) ws.close(1011, 'Codex process exited');
  });

  ws.on('message', async (data) => {
    if (child.stdin.destroyed) return;
    const text = Buffer.isBuffer(data) ? data.toString('utf8') : String(data);
    try {
      const message = JSON.parse(text);
      if (message.method === 'aiway/workspace/push') {
        try {
          const result = await replaceWorkspace(workDir, message.params?.files || {});
          safeSend({ id: message.id, result });
        } catch (error) {
          safeSend({ id: message.id, error: { message: error.message } });
        }
        return;
      }
      if (message.method === 'aiway/workspace/pull') {
        try {
          const result = await readWorkspace(workDir);
          safeSend({ id: message.id, result });
        } catch (error) {
          safeSend({ id: message.id, error: { message: error.message } });
        }
        return;
      }
      if (message.method === 'account/logout') {
        setTimeout(() => removePersistedAuth(hash, canPersist), 500);
      }
    } catch {}
    child.stdin.write(text.replace(/[\r\n]+$/, '') + '\n');
  });

  ws.on('close', async () => {
    closed = true;
    clearTimeout(saveTimer);
    clearTimeout(reconnectSuggestedTimer);
    clearTimeout(reconnectRequiredTimer);
    await persistAuth(hash, codexHome, canPersist).catch(() => {});
    try {
      child.kill('SIGTERM');
    } catch {}
    const killTimer = setTimeout(() => {
      try {
        child.kill('SIGKILL');
      } catch {}
    }, 1200);
    killTimer.unref?.();
    setTimeout(() => rm(codexHome, { recursive: true, force: true }).catch(() => {}), 1500).unref?.();
  });

  ws.on('error', () => {});
});

export default server;
