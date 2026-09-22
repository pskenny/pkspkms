  const qInput = document.getElementById('q'), highlightPre = document.getElementById('highlightPre');
  const btn = document.getElementById('btn'), saveBtn = document.getElementById('saveBtn');
  const statusEl = document.getElementById('status'), resultsEl = document.getElementById('results'), rawEl = document.getElementById('raw');
  const recentWrap = document.getElementById('recentWrap'), recentChips = document.getElementById('recentChips');

  let lastJson = null, currentFiles = [], currentPage = 0;
  const PAGE_SIZE = 100;
  const STORAGE_KEY = 'pkspkms_queries';

  // --- Syntax highlighting ---
  function tokenize(query) {
    const tokens = []; let i = 0;
    while (i < query.length) {
      const c = query[i];
      if (/\s/.test(c)) { tokens.push({ type: 'PLAIN', text: c }); i++; continue; }
      if (c === '"' || c === "'") {
        const q = c; let j = i + 1;
        while (j < query.length && query[j] !== q) j++;
        tokens.push({ type: 'STR', text: query.slice(i, j + 1) }); i = j + 1; continue;
      }
      if (c === '(' || c === ')') { tokens.push({ type: 'PAREN', text: c }); i++; continue; }
      if (c === '*') { tokens.push({ type: 'WILD', text: c }); i++; continue; }
      const two = query.slice(i, i + 2);
      if (two === '>=' || two === '<=' || two === '!=') { tokens.push({ type: 'OP', text: two }); i += 2; continue; }
      if (c === ':' || c === '>' || c === '<') { tokens.push({ type: 'OP', text: c }); i++; continue; }
      let j = i;
      while (j < query.length && !/[\s()=:<>'"*]/.test(query[j])) j++;
      const word = query.slice(i, j), u = word.toUpperCase();
      if (u === 'AND' || u === 'OR' || u === 'NOT') tokens.push({ type: 'BOOL', text: word });
      else if (u === 'LIKE') tokens.push({ type: 'OP', text: word });
      else if (/^[a-zA-Z_][a-zA-Z0-9_.]*$/.test(word)) tokens.push({ type: 'FIELD', text: word });
      else tokens.push({ type: 'PLAIN', text: word });
      i = j;
    }
    return tokens;
  }

  function escapeHtml(text) {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function updateHighlight() {
    const html = tokenize(qInput.value)
      .map(t => `<span class="tok-${t.type.toLowerCase()}">${escapeHtml(t.text)}</span>`)
      .join('');
    highlightPre.innerHTML = html;
    highlightPre.scrollLeft = qInput.scrollLeft;
    highlightPre.scrollTop = qInput.scrollTop;
    saveBtn.disabled = !qInput.value.trim();
  }

  qInput.addEventListener('input', updateHighlight);
  qInput.addEventListener('scroll', () => { highlightPre.scrollLeft = qInput.scrollLeft; highlightPre.scrollTop = qInput.scrollTop; });
  qInput.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); search(); } });

  // --- Search ---
  async function search() {
    const query = qInput.value;
    btn.disabled = true;
    statusEl.innerHTML = '<span class="spinner"></span> Searching...';
    resultsEl.innerHTML = ''; rawEl.textContent = ''; lastJson = null;
    try {
      const res = await fetch('/files/list?query=' + encodeURIComponent(query));
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      lastJson = data; render(data);
    } catch (e) {
      statusEl.innerHTML = '';
      resultsEl.innerHTML = '<div class="error">Error: ' + escapeHtml(e.message) + '</div>';
    } finally { btn.disabled = false; }
  }

  function render(data) {
    const size = data.resultSize || 0;
    currentFiles = data.files || []; currentPage = 0;
    statusEl.textContent = size + ' result' + (size === 1 ? '' : 's');
    if (size === 0) { resultsEl.innerHTML = '<div class="empty">No files matched your query.</div>'; rawEl.textContent = ''; return; }
    resultsEl.innerHTML = ''; rawEl.textContent = ''; renderBatch();
  }

  function renderBatch() {
    const start = currentPage * PAGE_SIZE;
    const end = Math.min(start + PAGE_SIZE, currentFiles.length);
    const batch = currentFiles.slice(start, end);

    let html = '';
    for (const file of batch) html += buildCardHtml(file);

    const existingBtn = document.getElementById('loadMoreBtn');
    if (existingBtn) existingBtn.remove();

    const temp = document.createElement('div');
    temp.innerHTML = html;
    while (temp.firstChild) resultsEl.appendChild(temp.firstChild);

    if (end < currentFiles.length) {
      const remaining = currentFiles.length - end;
      const wrap = document.createElement('div');
      wrap.className = 'load-more'; wrap.id = 'loadMoreBtn';
        wrap.innerHTML = `<button class="btn btn-secondary" data-more="1">Load more (${remaining} remaining)</button>`;
      resultsEl.appendChild(wrap);
    }
  }

  function loadMore() { currentPage++; renderBatch(); }

  // --- Card builder ---
  function buildCardHtml(file) {
    const title = file.title || file.filePath || 'Untitled';
    const path = file.filePath || '';
    const tags = Array.isArray(file.tags) ? file.tags : [];
    const links = Array.isArray(file.links) ? file.links.length : 0;
    const backlinks = Array.isArray(file.backlinks) ? file.backlinks.length : 0;
    const { subtitle, url, price, description, type } = file;

    const copyIcon = `<svg class="copy-icon" data-copy="1" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`;

    const titleEl = `<div class="card-title"><span class="field-label">Title:</span>${escapeHtml(title)}</div>`;
    const typeEl = type ? `<div class="card-type" data-add="type = ${escapeHtml(String(type))}">${escapeHtml(String(type))}</div>` : '';
    const subtitleEl = subtitle ? `<div class="card-subtitle"><span class="field-label">Subtitle:</span>${escapeHtml(String(subtitle))}</div>` : '';
    const urlEl = url ? `<div class="card-url"><span class="field-label">Url:</span><a class="card-link" href="${escapeHtml(String(url))}" target="_blank" rel="noopener">${escapeHtml(String(url))} ↗</a></div>` : '';
    const pillsHtml = tags.map(t => `<span class="pill" data-add="tags = ${escapeHtml(String(t))}">${escapeHtml(String(t))}</span>`).join('');

    let metaHtml = '';
    if (price != null) metaHtml += `<span class="meta-item"><span class="field-label">Price:</span>${escapeHtml(String(price))}</span>`;
    if (links > 0 || backlinks > 0) {
      if (metaHtml) metaHtml += '<span style="color:var(--muted);">&middot;</span>';
      metaHtml += '<span>';
      if (links > 0) metaHtml += `${links} link${links === 1 ? '' : 's'}`;
      if (links > 0 && backlinks > 0) metaHtml += ' &middot; ';
      if (backlinks > 0) metaHtml += `${backlinks} backlink${backlinks === 1 ? '' : 's'}`;
      metaHtml += '</span>';
    }

    const pathEl = `<div class="card-path"><span class="field-label">Path:</span>${escapeHtml(path)}</div>${copyIcon}`;
    const descEl = description ? `<div class="card-desc"><span class="field-label">Description:</span>${escapeHtml(String(description))}</div>` : '';

    return `<div class="card">
      <div class="card-header"><div class="card-title-row">${titleEl}${typeEl}</div>${subtitleEl}</div>
      ${urlEl}${pathEl}${descEl}
      ${pillsHtml ? `<div class="pills">${pillsHtml}</div>` : ''}
      ${metaHtml ? `<div class="meta">${metaHtml}</div>` : ''}
    </div>`;
  }

  function addToQuery(fragment) {
    const current = qInput.value.trim();
    qInput.value = current ? current + ' ' + fragment : fragment;
    updateHighlight();
  }

  function copyToClipboard(el) {
    const prev = el.previousElementSibling;
    const text = prev ? (prev.getAttribute('href') || prev.textContent) : '';
    if (!text) return;
    navigator.clipboard.writeText(text).catch(() => {});
    el.style.color = '#1a7f37';
    setTimeout(() => { el.style.color = ''; }, 800);
  }

  function toggleRaw() {
    if (!lastJson) return;
    const willShow = rawEl.style.display !== 'block';
    rawEl.style.display = willShow ? 'block' : 'none';
    if (willShow && !rawEl.textContent) rawEl.textContent = JSON.stringify(lastJson, null, 2);
  }

  // --- Saved queries ---
  function getSavedQueries() {
    try { const raw = localStorage.getItem(STORAGE_KEY); return raw ? JSON.parse(raw) : []; } catch { return []; }
  }
  function setSavedQueries(queries) { localStorage.setItem(STORAGE_KEY, JSON.stringify(queries)); renderRecent(); }
  function saveQuery() {
    const query = qInput.value;
    if (!query.trim()) return;
    let queries = getSavedQueries().filter(q => q !== query);
    queries.unshift(query);
    setSavedQueries(queries.slice(0, 10));
  }
  function runSavedQuery(query) { qInput.value = query; updateHighlight(); search(); }
  function deleteSavedQuery(query) { setSavedQueries(getSavedQueries().filter(q => q !== query)); }
  function clearAllSaved() { setSavedQueries([]); }
  function renderRecent() {
    const queries = getSavedQueries();
    if (!queries.length) { recentWrap.style.display = 'none'; return; }
    recentWrap.style.display = '';
    recentChips.innerHTML = queries.map(q =>
      `<div class="chip" title="${escapeHtml(q)}" data-query="${escapeHtml(q)}">${escapeHtml(q)}<span class="del" data-delete="${escapeHtml(q)}">×</span></div>`
    ).join('');
  }

  // --- Init ---
  updateHighlight();
  renderRecent();

    // --- B4: delegated handlers replace inline onclick (CSP script-src 'self') ---
    function delegated(container, selector, handler) {
        container.addEventListener('click', event => {
            const target = event.target.closest(selector);
            if (target) handler(target, event);
        });
    }

    btn.addEventListener('click', search);
    saveBtn.addEventListener('click', saveQuery);
    document.getElementById('clearSaved').addEventListener('click', clearAllSaved);
    document.getElementById('rawToggle').addEventListener('click', toggleRaw);

    delegated(resultsEl, '[data-copy]', target => copyToClipboard(target));
    delegated(resultsEl, '.pill, .card-type', target => { if (target.dataset.add) addToQuery(target.dataset.add); });
    delegated(resultsEl, '[data-more]', () => loadMore());
    delegated(recentChips, '.del', target => { if (target.dataset.delete) deleteSavedQuery(target.dataset.delete); });
    delegated(recentChips, '.chip', target => { if (target.dataset.query) runSavedQuery(target.dataset.query); });
