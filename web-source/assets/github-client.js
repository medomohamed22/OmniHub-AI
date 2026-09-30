export async function githubApi(action, { method = 'GET', query = {}, body } = {}) {
  const q = new URLSearchParams({ action, ...query });
  const response = await fetch(`/api/github?${q.toString()}`, {
    method,
    credentials: 'same-origin',
    cache: 'no-store',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; }
  catch { data = { error: text || 'GitHub request failed' }; }
  if (!response.ok) throw new Error(data.error || `GitHub ${response.status}`);
  return data;
}
