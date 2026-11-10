/* Omnisave P1 content script.
   Bubble on every page (Shadow DOM), realtime context capture on SPA navigation,
   save sheet (reference text + optional ≤30s audio memo), tick on success. */
(() => {
  if (window.__omnisaveInjected) return;
  window.__omnisaveInjected = true;
  if (/^(chrome|edge|about|moz-extension|chrome-extension):/.test(location.protocol)) return;

  /* ---------- context detection (URL/meta first, never fragile selectors) ---------- */
  function detect() {
    const host = location.hostname.replace(/^www\./, '');
    const path = location.pathname;
    let platform = 'web', type = 'website', video = null;
    if (/(^|\.)youtube\.com$/.test(host) || host === 'youtu.be') {
      platform = 'youtube';
      let m = path.match(/^\/shorts\/([\w-]{6,})/);
      if (m) { video = { id: m[1], isShort: true }; type = 'short'; }
      else if (host === 'youtu.be') { video = { id: path.slice(1), isShort: false }; type = 'video'; }
      else if ((m = location.href.match(/[?&]v=([\w-]{6,})/))) { video = { id: m[1], isShort: false }; type = 'video'; }
    } else if (host === 'x.com' || host === 'twitter.com') {
      platform = 'x';
      type = /\/(i\/)?article/.test(path) ? 'article' : /\/status\/\d+/.test(path) ? 'post' : 'website';
    } else if (/(^|\.)linkedin\.com$/.test(host)) {
      platform = 'linkedin';
      type = /\/(posts|feed|update|pulse)\//.test(path) ? 'post' : 'website';
    } else if (/discord\.com$/.test(host)) { platform = 'discord'; type = 'post'; }
    else if (/(^|\.)reddit\.com$/.test(host)) { platform = 'reddit'; type = /\/comments\//.test(path) ? 'post' : 'website'; }
    else if (/(^|\.)(notion\.so|notion\.site)$/.test(host)) { platform = 'notion'; type = 'doc'; }
    else if (/(^|\.)instagram\.com$/.test(host)) {
      platform = 'instagram';
      type = /reel/.test(path) ? 'short' : (/\/p\//.test(path) || /\/tv\//.test(path)) ? 'post' : 'website';
    }
    if (document.contentType === 'application/pdf' || /\.(pdf|docx?|pptx?)($|\?)/i.test(location.href)) type = 'doc';
    else if (/\.(png|jpe?g|webp|gif|avif)($|\?)/i.test(location.href)) type = 'image';
    return { platform, type, video };
  }

  function grabPreview(platform) {
    try {
      const sel = String(getSelection() || '').trim().slice(0, 200);
      if (sel) return sel;
      if (platform === 'x') {
        const t = document.querySelector('article [data-testid="tweetText"]');
        if (t) return t.textContent.trim().slice(0, 200);
      }
      const p = document.querySelector('article p, main p, p');
      return p ? p.textContent.trim().slice(0, 200) : null;
    } catch { return null; }
  }

  function capture() {
    const d = detect();
    return {
      url: location.href.slice(0, 2048),
      title: (document.title || '').slice(0, 300),
      platform: d.platform,
      type: d.type,
      video: d.video,
      preview: grabPreview(d.platform)
    };
  }

  /* ---------- bubble UI (Shadow DOM) ----------
     Render first so capture() can never run on an empty page, then start realtime refresh. */
  const host = document.createElement('div');
  host.id = 'omnisave-root';
  host.style.cssText = 'position:fixed;top:0;left:0;z-index:2147483646;';
  document.documentElement.appendChild(host);
  const sh = host.attachShadow({ mode: 'closed' });

  const svgMark = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 3.5h10a1 1 0 0 1 1 1v15.2a.7.7 0 0 1-1.1.6L12 16.6l-4.9 3.7A.7.7 0 0 1 6 19.7V4.5a1 1 0 0 1 1-1z" fill="#fff"/></svg>';
  const svgTick = '<span class="tick" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M20 6.5 9.5 17 4 11.5" fill="none" stroke="#fff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg></span>';
  const svgMic = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z" fill="currentColor"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>';

  const style = document.createElement('style');
  style.textContent = `
    *{box-sizing:border-box;margin:0;font-family:-apple-system,'SF Pro Text','Segoe UI',system-ui,sans-serif}
    .b{position:fixed;width:28px;height:28px;border-radius:50%;border:none;cursor:pointer;padding:0;
       background:rgba(10,132,255,.92);box-shadow:0 2px 10px rgba(0,0,0,.25),inset 0 0 0 .5px rgba(255,255,255,.25);
       outline-offset:2px;touch-action:none;transition:transform .15s cubic-bezier(.2,.8,.2,1),background .2s}
    .b:hover{transform:scale(1.12)}
    .b:active{transform:scale(.92)}
    .b svg{width:15px;height:15px;position:absolute;left:50%;top:50%;translate:-50% -50%}
    .b .tick{position:absolute;inset:0;border-radius:50%;background:#34C759;opacity:0;transform:scale(.4);
             display:grid;place-items:center;transition:opacity .12s,transform .18s cubic-bezier(.2,1.4,.3,1)}
    .b .tick svg{width:13px;height:13px;position:static;translate:none}
    .b.ok{background:#34C759}
    .b.ok svg:first-child{opacity:0}
    .b.ok .tick{opacity:1;transform:scale(1)}
    .b.err{background:#FF3B30}
    .b.pulse{animation:op .55s cubic-bezier(.2,.8,.2,1)}
    @keyframes op{0%{transform:scale(1)}35%{transform:scale(1.28)}100%{transform:scale(1)}}
    .panel{position:fixed;width:316px;max-width:calc(100vw - 24px);border-radius:16px;padding:14px;
           background:rgba(245,245,247,.86);backdrop-filter:blur(22px) saturate(180%);
           -webkit-backdrop-filter:blur(22px) saturate(180%);
           border:.5px solid rgba(120,120,128,.45);box-shadow:0 12px 40px rgba(0,0,0,.22);
           color:#1C1C1E;opacity:0;pointer-events:none;transform:translateY(6px) scale(.97);
           transition:opacity .18s,transform .22s cubic-bezier(.2,.8,.2,1)}
    .panel.open{opacity:1;pointer-events:auto;transform:none}
    .chips{display:flex;gap:6px;margin-bottom:8px}
    .chip{font-size:10.5px;font-weight:600;letter-spacing:.3px;text-transform:uppercase;padding:3px 8px;border-radius:99px;
          background:rgba(10,132,255,.14);color:#0A6CD8}
    .chip.t2{background:rgba(120,120,128,.18);color:#48484A}
    .src{font-size:11px;color:#8E8E93;line-height:1.35;margin-bottom:10px;word-break:break-word}
    .src b{display:block;font-size:13px;color:#000;font-weight:600;max-height:34px;overflow:hidden}
    input{width:100%;height:40px;border-radius:12px;border:.5px solid rgba(120,120,128,.4);background:rgba(255,255,255,.9);
          padding:0 12px;font-size:14px;color:#1C1C1E;outline:none;margin-bottom:10px}
    input:focus{border-color:#0A84FF;box-shadow:0 0 0 3px rgba(10,132,255,.18)}
    .row{display:flex;gap:8px;align-items:center}
    .mic{width:40px;height:40px;border-radius:50%;border:.5px solid rgba(120,120,128,.4);background:rgba(255,255,255,.9);
         color:#3A3A3C;cursor:pointer;display:grid;place-items:center;flex:none;outline-offset:2px;transition:background .15s,transform .1s}
    .mic:active{transform:scale(.94)}
    .mic svg{width:17px;height:17px}
    .mic.rec{background:#FF3B30;border-color:#FF3B30;color:#fff;animation:rb 1.2s infinite}
    @keyframes rb{50%{opacity:.7}}
    .save{flex:1;height:40px;border:none;border-radius:12px;background:#0A84FF;color:#fff;font-size:14px;font-weight:600;
          cursor:pointer;transition:background .15s,opacity .15s,transform .1s}
    .save:active{transform:scale(.97)}
    .save:disabled{opacity:.5;cursor:default}
    .stat{font-size:11px;color:#8E8E93;margin-top:8px;min-height:14px;line-height:1.3;font-variant-numeric:tabular-nums}
    .stat.err{color:#FF3B30}
  `;
  const mount = document.createElement('div');
  mount.innerHTML = `
    <div class="panel" role="dialog" aria-label="Omnisave — save this page"></div>
    <button class="b" aria-label="Omnisave — save this page">${svgMark}${svgTick}</button>`;
  sh.appendChild(style);
  sh.appendChild(mount);
  const panel = sh.querySelector('.panel');
  const bubble = sh.querySelector('.b');

  /* position: restore, else right edge ~40% height; clamp on resize */
  let pos = { x: innerWidth - 60, y: Math.round(innerHeight * 0.38) };
  try {
    chrome.storage.local.get('omnisavePos', d => {
      if (d.omnisavePos) { pos = d.omnisavePos; place(); }
    });
  } catch { /* storage may throw pre-init; bubble stays default */ }
  function place() {
    pos.x = Math.min(Math.max(8, pos.x), innerWidth - 36);
    pos.y = Math.min(Math.max(8, pos.y), innerHeight - 36);
    bubble.style.left = pos.x + 'px';
    bubble.style.top = pos.y + 'px';
  }
  place();
  addEventListener('resize', place);

  /* ---------- capture categories (settings → per-source toggles) ---------- */
  function catOf(d) {
    if (d.type === 'short') return 'shorts';
    if (d.type === 'video') return 'videos';
    if (d.type === 'image') return 'images';
    if (d.type === 'post') return 'posts';
    if (d.type === 'article') return d.platform === 'x' ? 'xarticles' : 'webdocs';
    return 'webdocs'; // website + doc
  }
  let sources = null;
  try { chrome.storage.local.get('omnisaveSources', s => { sources = s.omnisaveSources; applyAllowed(); }); } catch {}
  try { chrome.storage.onChanged.addListener((ch, area) => { if (area === 'local' && ch.omnisaveSources) chrome.storage.local.get('omnisaveSources', s => { sources = s.omnisaveSources; applyAllowed(); }); }); } catch {}
  function applyAllowed() { const allowed = sources === null || sources[catOf(ctx)] !== false; host.style.display = allowed ? '' : 'none'; }

  /* ---------- realtime context (SPA + poll safety net, never stale) ---------- */
  let ctx = capture();
  function refresh() {
    ctx = capture();
    applyAllowed();
    if (!panel.classList.contains('open')) {  // three quick beats = "captured something new"
      bubble.classList.remove('pulse');
      void bubble.offsetWidth;
      bubble.classList.add('pulse');
    }
  }
  let pollKey = key();
  function key() {
    return [location.href, document.title, ctx.video?.id || ''].join('|') ;
  }
  setInterval(() => {
    const k2 = [location.href, document.title, detect().video?.id || ''].join('|');
    if (k2 !== pollKey) { pollKey = k2; refresh(); }
  }, 800);
  for (const m of ['pushState', 'replaceState']) {
    const orig = history[m];
    history[m] = function (...a) { const r = orig.apply(this, a); refresh(); return r; };
  }
  addEventListener('popstate', refresh);
  addEventListener('hashchange', refresh);

  /* ---------- bubble interaction: drag vs click ---------- */
  let drag = null, moved = false;
  bubble.addEventListener('pointerdown', e => {
    drag = { x: e.clientX - pos.x, y: e.clientY - pos.y };
    moved = false;
    try { bubble.setPointerCapture(e.pointerId); } catch { /* fallback: plain click */ }
  });
  bubble.addEventListener('pointermove', e => {
    if (!drag) return;
    const nx = e.clientX - drag.x, ny = e.clientY - drag.y;
    if (Math.abs(nx - pos.x) > 6 || Math.abs(ny - pos.y) > 6) {
      moved = true;
      pos = { x: nx, y: ny };
      place();
    }
  });
  bubble.addEventListener('pointerup', () => {
    drag = null;
    if (moved) { try { chrome.storage.local.set({ omnisavePos: pos }); } catch { /* cosmetic */ } }
    else toggleSheet();
  });
  bubble.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleSheet(); }
  });

  /* ---------- save sheet ---------- */
  let rec = null; // {mr, stream, started, blob, timer, maxT}

  function toggleSheet() {
    if (panel.classList.contains('open')) { closeSheet(); return; }

    const d = detect();
    panel.innerHTML = `
      <div class="chips"><span class="chip">${esc(d.platform)}</span><span class="chip t2">${esc(d.type)}${d.video?.isShort ? ' · shorts' : ''}</span></div>
      <div class="src"><b>${esc((document.title || 'Untitled').slice(0, 80))}</b>${esc(shortHost())}</div>
      <input id="ov-ref" maxlength="500" placeholder="Reference — why save this?" aria-label="Reference note"/>
      <div class="row">
        <button class="mic" aria-label="Record audio memo, max 30 seconds" title="Audio memo (max 30 s)">${svgMic}</button>
        <button class="save" aria-label="Save to Omnisave">Save</button>
      </div>
      <div class="stat" role="status"></div>`;
    const input = sh.querySelector('#ov-ref');
    const saveB = sh.querySelector('.save');
    const micB = sh.querySelector('.mic');
    const stat = sh.querySelector('.stat');

    panel.style.left = Math.max(12, Math.min(pos.x - 100, innerWidth - 328)) + 'px';
    panel.style.top = (pos.y + 40 > innerHeight - 250 ? Math.max(12, pos.y - 246) : pos.y + 40) + 'px';

    micB.addEventListener('click', () => toggleRec());
    input.addEventListener('keydown', e => { if (e.key === 'Enter') saveB.click(); });

    function fail(msg) {
      stat.textContent = '✕ ' + msg;
      stat.className = 'stat err';
      bubble.classList.add('err');
      setTimeout(() => bubble.classList.remove('err'), 1200);
    }

    saveB.addEventListener('click', async () => {
      saveB.disabled = true;
      if (rec) await stopRec();
      const video = d.video ? {
        id: d.video.id,
        isShort: !!d.video.isShort,
        t: d.platform === 'youtube' ? (document.querySelector('video')?.currentTime | 0) : undefined
      } : null;
      const item = {
        id: crypto.randomUUID ? crypto.randomUUID() : 'i' + Date.now() + Math.random().toString(36).slice(2),
        createdAt: Date.now(),
        type: document.contentType === 'application/pdf' ? 'doc' : d.type,
        platform: d.platform,
        url: location.href,
        title: document.title || '',
        video, preview: ctx.preview,
        refText: input.value.trim(),
        refAudio: rec?.blob ? { b64: await blobToB64(rec.blob), mime: rec.blob.type } : null,
        source: 'extension'
      };
      stat.textContent = 'Saving…';
      stat.className = 'stat';
      chrome.runtime.sendMessage({ type: 'omnisave:save', item }, resp => {
        saveB.disabled = false;
        const err = chrome.runtime.lastError;
        if (err) return fail('Extension error: ' + err.message.slice(0, 80));
        if (resp?.ok) {
          flashTick();
          closeSheet();
        } else {
          fail(resp?.reason || 'Save failed');
        }
      });
    });

    panel.classList.add('open');
    try { input.focus(); } catch { /* focus may fail silently */ }

    document.addEventListener('pointerdown', outside, true);
    document.addEventListener('keydown', escClose, true);
    function outside(e) { if (!host.contains(e.target)) closeSheet(); }
    function escClose(e) { if (e.key === 'Escape') closeSheet(); }
    function closeSheet() {
      panel.classList.remove('open');
      document.removeEventListener('pointerdown', outside, true);
      document.removeEventListener('keydown', escClose, true);
      if (rec) stopRec(); // finalize memo even if sheet closed mid-record
    }
  }

  function flashTick() {
    bubble.classList.add('ok');
    setTimeout(() => bubble.classList.remove('ok'), 1100);
  }

  /* ---------- audio memo (≤30s; recording again replaces it) ---------- */
  async function toggleRec() {
    if (rec) { stopRec(); return; }
    let stream;
    try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); }
    catch {
      const stat = sh.querySelector('.stat');
      if (stat) { stat.textContent = 'Mic blocked — text reference only'; stat.className = 'stat err'; }
      return;
    }
    try {
      const micB = sh.querySelector('.mic');
      const stat = sh.querySelector('.stat');
      const mr = new MediaRecorder(stream);
      const chunks = [];
      mr.ondataavailable = e => chunks.push(e.data);
      const started = Date.now();
      const r = rec = { mr, stream, started, blob: null, done: null };
      r.done = new Promise(res => {
        mr.onstop = () => {
          r.blob = new Blob(chunks, { type: mr.mimeType || 'audio/webm' });
          res();
        };
      });
      mr.start();
      micB.classList.add('rec');
      r.timer = setInterval(() => { stat.textContent = Math.floor((Date.now() - started) / 1000) + ' s — tap mic to stop'; }, 250);
      r.maxT = setTimeout(() => stopRec(), 30 * 1000);
    } catch (e) {
      stream.getTracks().forEach(k => k.stop());
      rec = null;
      const stat = sh.querySelector('.stat');
      if (stat) { stat.textContent = 'Recording failed — text reference only'; stat.className = 'stat err'; }
    }
  }

  async function stopRec() {
    const r = rec;
    rec = null;
    if (!r) return;
    clearTimeout(r.maxT);
    clearInterval(r.timer);
    const micB = sh.querySelector('.mic');
    const stat = sh.querySelector('.stat');
    micB?.classList.remove('rec');
    try {
      if (r.mr.state !== 'inactive') r.mr.stop();
    } catch { /* already stopped */ }
    await r.done;
    r.stream.getTracks().forEach(k => k.stop());
    if (stat && r.blob) stat.textContent = 'Audio memo ready — ' + Math.round(r.blob.size / 1024) + ' KB';
  }

  /* ---------- helpers ---------- */
  function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }
  function shortHost() {
    try { return ' · ' + new URL(location.href).hostname.replace(/^www\./, ''); } catch { return ''; }
  }
  function blobToB64(b) {
    return new Promise(res => {
      const r = new FileReader();
      r.onload = () => res(String(r.result).split(',')[1]);
      r.readAsDataURL(b);
    });
  }
})();
