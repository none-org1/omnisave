// Shared storage layer — imported by service worker (importScripts) and dev pages (script tag).
// IndexedDB is the only "local storage" we use; chrome.storage.local is limited to ~10MB.
const db = (() => {
  const DB_NAME = 'omnisave';
  const STORE = 'items';
  let p = null;

  function open() {
    if (p) return p;
    p = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => {
        const d = req.result;
        if (!d.objectStoreNames.contains(STORE)) {
          const s = d.createObjectStore(STORE, { keyPath: 'id' });
          s.createIndex('createdAt', 'createdAt');
          s.createIndex('canonical', 'canonical');
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return p;
  }

  async function tx(mode, fn) {
    const d = await open();
    return new Promise((resolve, reject) => {
      const t = d.transaction(STORE, mode);
      const res = fn(t.objectStore(STORE));
      t.oncomplete = () => resolve(res instanceof IDBRequest ? res.result : res);
      t.onerror = () => reject(t.error);
    });
  }

  return {
    put(item) { return tx('readwrite', s => s.put(item)); },
    remove(id) { return tx('readwrite', s => s.delete(id)); },
    count() { return tx('readonly', s => s.count()); },
    listAll() {
      return open().then(d => new Promise((resolve, reject) => {
        const out = [];
        const req = d.transaction(STORE, 'readonly').objectStore(STORE)
          .index('createdAt').openCursor(null, 'prev');
        req.onsuccess = () => {
          const c = req.result;
          if (!c) return resolve(out);
          out.push(c.value);
          c.continue();
        };
        req.onerror = () => reject(req.error);
      }));
    },
    recent(n) {
      return open().then(d => new Promise((resolve, reject) => {
        const out = [];
        const req = d.transaction(STORE, 'readonly').objectStore(STORE)
          .index('createdAt').openCursor(null, 'prev');
        req.onsuccess = () => {
          const c = req.result;
          if (!c || out.length >= n) return resolve(out);
          out.push(c.value);
          c.continue();
        };
        req.onerror = () => reject(req.error);
      }));
    },
    findByCanonical(canonical) {
      return open().then(d => new Promise((resolve, reject) => {
        const req = d.transaction(STORE, 'readonly').objectStore(STORE)
          .index('canonical').getAll(IDBKeyRange.only(canonical));
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
      }));
    }
  };
})();
