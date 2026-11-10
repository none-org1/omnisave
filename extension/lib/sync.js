/* Omnisave optional sync — user's own free Firebase (Spark tier) + E2E encryption.
   Boot requires {code, fbConfig{apiKey, databaseURL, projectId, ...}} at
   chrome.storage.local['omnisaveSync']. Off by default; everything here fails soft. */
const sync = (() => {
  const SDK = ['https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js',
               'https://www.gstatic.com/firebasejs/10.12.2/firebase-database-compat.js'];
  let st = null;      // {root, key} after boot
  let booting = null; // shared in-flight boot promise
  let lastSync = 0;
  let remoteApply = null; // sw hook: called with decrypted row (sw writes locally + refreshes badge)
  let loaded = false;     // returned by initial delta pull

  const b64 = {
    enc: buf => btoa(String.fromCharCode(...new Uint8Array(buf))),
    dec: s => Uint8Array.from(atob(s), c => c.charCodeAt(0))
  };

  async function deriveKey(code) {
    const enc = new TextEncoder();
    const material = await crypto.subtle.importKey('raw', enc.encode(code), 'PBKDF2', false, ['deriveKey']);
    return crypto.subtle.deriveKey(
      { name: 'PBKDF2', salt: enc.encode('omnisave-v1'), iterations: 120000, hash: 'SHA-256' },
      material, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  }

  async function seal(envelope) {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, st.key,
      new TextEncoder().encode(JSON.stringify(envelope)));
    return { c: b64.enc(iv), d: b64.enc(ct), u: envelope.updatedAt || 0 };
  }

  async function unseal(env) {
    const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: b64.dec(env.c) }, st.key, b64.dec(env.d));
    return JSON.parse(new TextDecoder().decode(pt));
  }

  async function readConfig() {
    try {
      const s = await chrome.storage.local.get(['omnisaveSync']);
      const c = s.omnisaveSync;
      if (!c?.code || !c?.fbConfig?.databaseURL) return null;
      return c;
    } catch { return null; }
  }

  async function boot() {
    if (st) return st;
    if (booting) return booting;
    booting = (async () => {
      try {
        const cfg = await readConfig();
        if (!cfg) return null;
        importScripts(...SDK);
        firebase.initializeApp(cfg.fbConfig);
        st = {
          root: firebase.database().ref('omnisave/' + encodeURIComponent(cfg.code) + '/items'),
          key: await deriveKey(cfg.code)
        };
        try { lastSync = (await chrome.storage.local.get(['omnisaveLastSync'])).omnisaveLastSync || 0; } catch {}
        return st;
      } catch (e) {
        console.warn('[omnisave-sync] boot failed:', e?.message || e);
        st = null;
        return null;
      } finally {
        booting = null;
      }
    })();
    return booting;
  }

  async function applyRemote(id, env) {
    if (!loaded || lastSync >= (env.u || 0)) return; // ignore stale replays
    try {
      if (env.t === 'tombstone') { remoteApply?.({ id, tombstone: true }); return; }
      const item = await unseal(env);
      item._remote = true;
      remoteApply?.(item);
    } catch (e) {
      // wrong sync code on the other device, or corrupted envelope — drop silently
      console.warn('[omnisave-sync] unseal failed for', id);
    }
  }

  async function pull() {
    const s = await boot();
    if (!s) return;
    const snap = await s.root.orderByChild('u').startAt(lastSync).get();
    const envs = snap.val() || {};
    // Delta window: reconcile exactly once at start, then stream
    for (const [id, env] of Object.entries(envs)) await applyRemote(id, env);
    loaded = true;
    const now = Date.now();
    s.root.orderByChild('u').startAt(now).on('child_added', x => applyRemote(x.key, x.val()));
    s.root.orderByChild('u').startAt(now).on('child_changed', x => applyRemote(x.key, x.val()));
    s.root.orderByChild('u').startAt(now).on('child_removed', x => remoteApply?.({ id: x.key, tombstone: true }));
    lastSync = now;
    try { chrome.storage.local.set({ omnisaveLastSync: lastSync }); } catch {}
  }

  // sw calls these after a LOCAL write (tombstones for removes):
  async function pushSave(item) {
    const s = await boot();
    if (!s || item._remote) return;
    try { await s.root.child(item.id).set(await seal(item)); } catch (e) { console.warn('[omnisave-sync] push failed', e?.message || e); }
  }
  async function pushRemove(id) {
    const s = await boot();
    if (!s) return;
    try { await s.root.child(id).set({ t: 'tombstone', u: Date.now() }); } catch (e) { console.warn('[omnisave-sync] tombstone failed', e?.message || e); }
  }

  async function init(hooks) {
    remoteApply = hooks?.applyRemote || remoteApply;
    const s = await boot();
    if (!s || loaded) return s;
    await pull();
    return s;
  }

  // config change at runtime (settings screen) → clean re-boot
  function reinit(hooks) {
    try {
      st?.root?.off();
    } catch {}
    st = null; loaded = false;
    return init(hooks);
  }

  return { init, reinit, pushSave, pushRemove, boot };
})();
