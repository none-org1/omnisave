// Omnisave background service worker: normalize + dedupe + persist, count badge, optional E2E sync.
importScripts('../lib/db.js');
importScripts('../lib/sync.js');

const AUDIO_MAX_S = 30;

function canonicalize(raw) {
  try {
    const u = new URL(raw);
    const drop = [...u.searchParams.keys()].filter(k => /^(utm_|mc_|ref$)/i.test(k) || ['fbclid', 'gclid', 'si', 'feature'].includes(k));
    drop.forEach(k => u.searchParams.delete(k));
    u.hash = '';
    return u.toString();
  } catch { return raw; }
}

function b64ToBlob(b64, mime) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type: mime });
}

// SW has no FileReader: Blob → b64 via arrayBuffer for the sync envelope.
async function blobToB64(blob) {
  const u = new Uint8Array(await blob.arrayBuffer());
  let s = '';
  for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
  return btoa(s);
}

// Sync transport needs JSON-compatible refAudio (b64), not the stored Blob.
async function toTransport(item) {
  const t = { ...item };
  if (t.refAudio?.blob) t.refAudio = { b64: await blobToB64(t.refAudio.blob), mime: t.refAudio.blob.type };
  return t;
}

function broadcastChanged() { try { chrome.runtime.sendMessage({ type: 'omnisave:changed' }, () => void chrome.runtime.lastError); } catch {} }

/* ----- Gemini enrich (optional, never blocking the save/tick) ----- */
async function enrichSave(id, data) {
  try {
    const { omnisaveGemini: g } = await chrome.storage.local.get('omnisaveGemini');
    if (!g?.on || !g?.key) return;
    const prompt =
      'You label saved content for a personal library. Reply with JSON ONLY: {"tags":["up to 3 short lowercase tags"],"summary":"one line, max 90 chars"}\n' +
      `Title: ${data.title}\nURL: ${data.url}\nUser note (the "why"): ${data.refText || '(none)'}\nPage preview: ${(data.preview || '').slice(0, 400)}`;
    const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${g.model || 'gemini-2.0-flash'}:generateContent?key=${encodeURIComponent(g.key)}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }], generationConfig: { temperature: 0.2, maxOutputTokens: 120 } })
    });
    if (!res.ok) return;
    const out = await res.json();
    const text = out?.candidates?.[0]?.content?.parts?.map(p => p.text).join('') || '';
    const js = JSON.parse(text.slice(text.indexOf('{'), text.lastIndexOf('}') + 1));
    const all = await db.listAll();
    const item = all.find(x => x.id === id);
    if (!item) return;
    if (Array.isArray(js.tags)) item.tags = js.tags.slice(0, 3).map(t => String(t).slice(0, 24).toLowerCase());
    if (js.summary) item.summary = String(js.summary).slice(0, 160);
    await db.put(item);
    broadcastChanged();
  } catch (e) { console.warn('[omnisave-gemini] enrich failed:', e?.message || e); }
}

async function refreshBadge() {
  try {
    const n = await db.count();
    await chrome.action.setBadgeText({ text: n > 0 ? String(n) : '' });
    await chrome.action.setBadgeBackgroundColor({ color: '#0A84FF' });
  } catch (e) { /* badge is cosmetic */ }
}

/* ----- sync wiring: remote rows land locally; local rows get pushed; deletes = tombstones ----- */
function applyRemote(row) {
  (async () => {
    try {
      if (row.tombstone) { await db.remove(row.id); }
      else { await db.put(row); }
      refreshBadge();
      broadcastChanged();
    } catch (e) { console.warn('[omnisave] remote apply failed', e); }
  })();
}
const syncHooks = { applyRemote };
sync.init(syncHooks).catch(() => {});
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.omnisaveSync) sync.reinit(syncHooks).catch(() => {});
});

chrome.runtime.onInstalled.addListener(refreshBadge);
chrome.runtime.onStartup.addListener(refreshBadge);

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg?.type === 'omnisave:save') {
    (async () => {
      try {
        const item = msg.item;
        if (!item?.url) throw new Error('no URL captured');
        item.url = String(item.url).slice(0, 2048);
        item.canonical = canonicalize(item.url);
        item.title = (item.title || '').slice(0, 300);
        item.refText = (item.refText || '').slice(0, 500);
        item.updatedAt = Date.now();
        if (item.refAudio?.b64) item.refAudio = { blob: b64ToBlob(item.refAudio.b64, item.refAudio.mime || 'audio/webm') };
        else delete item.refAudio;

        // Dedupe: same canonical URL saved within 15 min = update the reference, not a new row.
        const prev = await db.findByCanonical(item.canonical);
        const twin = prev.find(x => Date.now() - x.createdAt < 15 * 60 * 1000);
        if (twin) {
          twin.createdAt = item.createdAt;
          twin.updatedAt = Date.now();
          twin.video = item.video || twin.video;
      if (item.refText) twin.refText = item.refText;
          if (item.refAudio) twin.refAudio = item.refAudio;
          twin.title = item.title || twin.title;
          await db.put(twin);
          sync.pushSave(await toTransport(twin));
          sendResponse({ ok: true, updated: true });
          enrichSave(twin.id, { title: twin.title, url: twin.url, refText: twin.refText, preview: twin.preview });
        } else {
          await db.put(item);
          sync.pushSave(await toTransport(item));
          sendResponse({ ok: true, updated: false });
          enrichSave(item.id, item);
        }
        refreshBadge();
        broadcastChanged();
      } catch (e) {
        sendResponse({ ok: false, reason: String(e && e.message || e).slice(0, 120) });
      }
    })();
    return true; // async response
  }

  if (msg?.type === 'omnisave:recent') {
    (async () => {
      try {
        const items = await db.recent(Math.min(msg.n || 5, 20));
        const slim = items.map(i => ({
          ...i,
          refAudio: i.refAudio ? { mime: i.refAudio.blob?.type || 'audio/webm' } : undefined
        }));
        sendResponse({ ok: true, items: slim });
      } catch (e) { sendResponse({ ok: false, reason: String(e) }); }
    })();
    return true;
  }

  if (msg?.type === 'omnisave:remove') {
    (async () => {
      try { await db.remove(msg.id); await sync.pushRemove(msg.id); await refreshBadge(); sendResponse({ ok: true }); }
      catch (e) { sendResponse({ ok: false, reason: String(e) }); }
    })();
    return true;
  }
});
