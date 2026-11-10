/* Omnisave P2 library + settings. One page, two views. Direct IndexedDB access. */
const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const PLATS = ['', 'web', 'youtube', 'x', 'linkedin', 'instagram', 'reddit', 'discord', 'notion'];
const TYPES = ['', 'short', 'video', 'post', 'article', 'doc', 'image', 'website'];
const state = { q: '', plat: '', type: '', memo: false, shown: 50, items: [] };

$('btnSettings').addEventListener('click', () => { $('viewLib').hidden = true; $('viewSet').hidden = false; });
$('btnBack').addEventListener('click', () => { $('viewSet').hidden = true; $('viewLib').hidden = false; });

/* ---------- filters ---------- */
function chip(label, kind, val, on) {
  const b = document.createElement('button');
  b.className = 'fchip' + (on ? ' on' : '');
  b.textContent = label;
  b.setAttribute('aria-pressed', on);
  b.addEventListener('click', () => {
    state[kind] = state[kind] === val ? '' : val.slice();
    if (kind === 'q') state.memoo = false;
    if (kind === 'type' && val === 'memo') { state.type = ''; state.memo = !state.memo; }
    state.shown = 50;
    drawChips();
    render();
  });
  return b;
}
function drawChips() {
  $('chips').innerHTML = '';
  $('chips').appendChild(chip('All', 'plat', '', !state.plat && !state.memo));
  for (const p of PLATS.slice(1)) $('chips').appendChild(chip(p, 'plat', p, state.plat === p));
  for (const t of TYPES.slice(1)) $('chips').appendChild(chip(t, 'type', t, state.type === t));
  $('chips').appendChild(chip('memo', 'type', 'memo', state.memo));
}
drawChips();
$('q').addEventListener('input', () => { state.q = $('q').value.trim().toLowerCase(); state.shown = 50; render(); });
$('btnMore').addEventListener('click', () => { state.shown += 100; render(); });

/* ---------- list ---------- */
async function load() {
  state.items = await db.listAll();
  const memos = state.items.filter(i => i.refAudio).length;
  $('count').textContent = state.items.length + ' saved';
  render();
}
function matches(i) {
  if (state.plat && i.platform !== state.plat) return false;
  if (state.type && i.type !== state.type) return false;
  if (state.memo && !i.refAudio) return false;
  if (state.q) {
    const hay = [i.title, i.refText, i.preview, i.url, ...(i.tags || [])].join(' ').toLowerCase();
    if (!hay.includes(state.q)) return false;
  }
  return true;
}
const fmt = ts => {
  const d = Date.now() - ts;
  if (d < 60e3) return 'just now';
  if (d < 3600e3) return Math.floor(d / 60e3) + ' min ago';
  if (d < 86400e3) return Math.floor(d / 3600e3) + ' h ago';
  return new Date(ts).toLocaleDateString();
};
function delSvg() { return `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 6h14M9 6V4.5A1.5 1.5 0 0 1 10.5 3h3A1.5 1.5 0 0 1 15 4.5V6m-8 0 .7 12A2 2 0 0 0 9.7 20h4.6a2 2 0 0 0 2-2L17 6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>`; }

function render() {
  const ms = state.items.filter(matches);
  const slice = ms.slice(0, state.shown);
  const main = $('list');
  $('more').hidden = ms.length <= slice.length;
  if (!ms.length) {
    main.innerHTML = `<div class="empty">No saved items${state.q ? ' match "' + esc(state.q) + '"' : ' yet'}.<br/>Open any site and tap the blue bubble.</div>`;
    return;
  }
  main.innerHTML = `<div class="card" id="card"></div>`;
  const card = main.firstElementChild;
  for (const i of slice) {
    const row = document.createElement('div');
    row.className = 'item';
    row.innerHTML = `
      <div class="chips">
        <span class="chip">${esc(i.platform)}</span>
        <span class="chip t2">${esc(i.type)}${i.video?.isShort ? ' · shorts' : ''}</span>
        ${i.refAudio ? `<span class="chip memo">memo</span>` : ''}
      </div>
      <div class="tx">
        <a href="${esc(i.url)}" target="_blank" rel="noopener"><span class="t">${esc(i.title || i.url)}</span></a>
        ${i.refText ? `<div class="r">${esc(i.refText)}</div>` : ''}
        ${!i.refText && i.preview ? `<div class="preview">${esc(i.preview)}</div>` : ''}
        ${(i.tags || []).length ? `<div class="tags">${i.tags.map(t => `<span class="tag">${esc(t)}</span>`).join('')}</div>` : ''}
        ${i.summary ? `<div class="preview">${esc(i.summary)}</div>` : ''}
        ${i.refAudio ? `<audio controls preload="none" aria-label="Audio memo"></audio>` : ''}
        <div class="meta">${fmt(i.createdAt)}${i.video?.t ? ' · @' + i.video.t + 's' : ''} · ${esc(new URL(i.url).hostname.replace(/^www\./, ''))}</div>
      </div>
      <div class="actions"><button class="del" aria-label="Delete this item">${delSvg()}</button></div>`;
    row.querySelector('.del').addEventListener('click', () => {
      chrome.runtime.sendMessage({ type: 'omnisave:remove', id: i.id }, load);
    });
    if (i.refAudio) {
      const a = row.querySelector('audio');
      a.src = URL.createObjectURL(i.refAudio.blob);
    }
    card.appendChild(row);
  }
}
chrome.runtime.onMessage.addListener(m => { if (m?.type === 'omnisave:changed') load(); });
load();

/* ---------- settings ---------- */
async function initSettings() {
  const s = await chrome.storage.local.get(['omnisaveGemini', 'omnisaveSync']);
  $('gemKey').value = s.omnisaveGemini?.key || '';
  $('gemModel').value = s.omnisaveGemini?.model || 'gemini-2.0-flash';
  $('gemOn').checked = !!s.omnisaveGemini?.on;
  $('syncOn').checked = !!s.omnisaveSync?.on;
  $('syncCode').value = s.omnisaveSync?.code || '';
  $('fbCfg').value = s.omnisaveSync?.fbConfig ? JSON.stringify(s.omnisaveSync.fbConfig) : '';

  // capture toggles
  const CATS = [
    ['shorts', 'Shorts & Reels'], ['videos', 'Long videos'], ['xarticles', 'X articles'],
    ['posts', 'Social posts'], ['webdocs', 'Websites & docs'], ['images', 'Images']
  ];
  const src = s.omnisaveSources || {};
  const grid = document.createElement('div');
  grid.className = 'srcgrid';
  for (const [key, label] of CATS) {
    const l = document.createElement('label');
    l.className = 'row2';
    const c = document.createElement('input');
    c.type = 'checkbox';
    c.checked = src[key] !== false;
    c.addEventListener('change', async () => {
      const cur = (await chrome.storage.local.get('omnisaveSources')).omnisaveSources || {};
      cur[key] = c.checked;
      await chrome.storage.local.set({ omnisaveSources: cur });
    });
    l.appendChild(c);
    const sp = document.createElement('span');
    sp.textContent = label;
    l.appendChild(sp);
    grid.appendChild(l);
  }
  $('srcToggles').appendChild(grid);

  // storage used
  try {
    const est = await navigator.storage.estimate();
    $('storeRow').textContent = `Using ~${(est.usage / 1048576).toFixed(1)} MB of ${(est.quota / 1048576).toFixed(0)} MB available (includes sync/Gemini caches).`;
  } catch { $('storeRow').textContent = 'Storage size not available on this browser.'; }
}
initSettings();

$('gemKey').addEventListener('change', saveGemini);
$('gemModel').addEventListener('change', saveGemini);
$('gemOn').addEventListener('change', saveGemini);
function saveGemini() {
  chrome.storage.local.set({ omnisaveGemini: { key: $('gemKey').value.trim(), model: $('gemModel').value.trim() || 'gemini-2.0-flash', on: $('gemOn').checked } });
}
$('btnSyncSave').addEventListener('click', async () => {
  const code = $('syncCode').value.trim();
  let fbRaw = $('fbCfg').value.trim();
  let fbConfig = null;
  if (fbRaw) {
    try { fbConfig = JSON.parse(fbRaw); }
    catch {
      // allow pasting the full script tag contents: grab first {...} blob
      const m = fbRaw.match(/\{[\s\S]*\}/);
      if (m) { try { fbConfig = JSON.parse(m[0]); } catch { fbConfig = null; } }
    }
    if (!fbConfig?.databaseURL) { alert('Config must include "databaseURL" (Realtime Database).'); return; }
  }
  await chrome.storage.local.set({
    omnisaveSync: { on: $('syncOn').checked && !!code && !!fbConfig, code, fbConfig }
  });
  $('btnSyncSave').textContent = 'Saved ✓';
  setTimeout(() => $('btnSyncSave').textContent = 'Save sync settings', 1200);
});
$('btnExport').addEventListener('click', async () => {
  const items = await db.listAll();
  const t = items.map(i => ({ ...i, refAudio: undefined })); // JSON-safe
  const blob = new Blob([JSON.stringify(t, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'omnisave-export-' + new Date().toISOString().slice(0, 10) + '.json';
  a.click();
});
$('fileImport').addEventListener('change', async e => {
  const f = e.target.files[0];
  if (!f) return;
  try {
    const items = JSON.parse(await f.text());
    for (const it of items) await db.put({ ...it, refAudio: undefined, _remote: false });
    load();
    alert(`Imported ${items.length} items.`);
  } catch { alert('Import failed — not an Omnisave export.'); }
});
$('btnWipe').addEventListener('click', async () => {
  if (!confirm('Erase every saved item on this device? Synced copies on your other device stay.')) return;
  const items = await db.listAll();
  for (const i of items) chrome.runtime.sendMessage({ type: 'omnisave:remove', id: i.id });
  setTimeout(load, 400);
});
