// Popup: recent saves list (P1). Library with search/filters = P2.
const card = document.getElementById('list');
const count = document.getElementById('count');
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = ts => {
  const d = Date.now() - ts;
  if (d < 60e3) return 'just now';
  if (d < 3600e3) return Math.floor(d / 60e3) + ' min ago';
  if (d < 86400e3) return Math.floor(d / 3600e3) + ' h ago';
  return new Date(ts).toLocaleDateString();
};

chrome.runtime.sendMessage({ type: 'omnisave:recent', n: 5 }, resp => {
  if (chrome.runtime.lastError || !resp?.ok) {
    card.innerHTML = `<div class="empty">Could not load saves.</div>`;
    return;
  }
  const items = resp.items || [];
  render(items);
  // approximate total (list is n=5; badge shows real count)
  chrome.action.getBadgeText({}, t => { if (t) count.textContent = t + ' saved'; });
});

function render(items) {
  if (!items.length) {
    card.innerHTML = `<div class="empty">Nothing saved yet.<br/>Open any site — the bubble is already there.</div>`;
    return;
  }
  card.innerHTML = items.map(i => `
    <a class="item" href="#" data-url="${esc(i.url)}" title="${esc(i.title || i.url)}">
      <span class="chip">${esc(i.platform)}</span>
      <span class="tx">
        <span class="t">${esc(i.title || i.url)}</span>
        ${i.refText ? `<span class="r">${esc(i.refText)}</span>` : ''}
        <span class="m">${esc(i.type)}${i.video?.isShort ? ' · short' : ''}${i.refAudio ? ' · memo' : ''} · ${fmt(i.createdAt)}</span>
      </span>
    </a>`).join('');
  card.querySelectorAll('.item').forEach(a =>
    a.addEventListener('click', e => { e.preventDefault(); chrome.tabs.create({ url: a.dataset.url }); }));
}
