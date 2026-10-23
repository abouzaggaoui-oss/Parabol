/* Parabol Desktop — small DOM helpers, icons, formatting, dialogs. */

/** Screen registry: name -> (params) => { el, destroy?, onBack?, onKey?, afterMount? }. Filled by screens-*.js. */
const SCREENS = {};

/** h('div.class#id', {attrs/props/on*}, children…) — tiny element builder. */
function h(tag, props, ...children) {
  const m = /^([a-z0-9]+)([.#][^]*)?$/i.exec(tag);
  const el = document.createElement(m ? m[1] : 'div');
  if (m && m[2]) {
    m[2].split(/(?=[.#])/).forEach((part) => {
      if (part[0] === '.') el.classList.add(part.slice(1));
      else if (part[0] === '#') el.id = part.slice(1);
    });
  }
  if (props) {
    Object.entries(props).forEach(([k, v]) => {
      if (v === undefined || v === null || v === false) return;
      if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'style' && typeof v === 'object') {
        Object.entries(v).forEach(([sk, sv]) => {
          if (sk.startsWith('--')) el.style.setProperty(sk, sv); else el.style[sk] = sv;
        });
      }
      else if (k === 'html') el.innerHTML = v; // only used with our own static markup (icons)
      else if (k === 'text') el.textContent = v;
      else if (k === 'className') el.className = v;
      else if (k in el && typeof v !== 'string') el[k] = v;
      else el.setAttribute(k, v === true ? '' : v);
    });
  }
  appendChildren(el, children);
  return el;
}

function appendChildren(el, children) {
  children.flat(Infinity).forEach((c) => {
    if (c === null || c === undefined || c === false) return;
    el.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
  });
}

/** Material-style icons as inline SVG (stroke/fill use currentColor). */
const ICONS = {
  back: '<path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/>',
  search: '<path d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/>',
  close: '<path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>',
  star: '<path d="M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"/>',
  starOutline: '<path d="m22 9.24-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z"/>',
  liveTv: '<path d="M21 6h-7.59l3.29-3.29L16 2l-4 4-4-4-.71.71L10.59 6H3a2 2 0 0 0-2 2v12c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V8a2 2 0 0 0-2-2zm0 14H3V8h18v12zM9 10v8l7-4z"/>',
  movie: '<path d="m18 4 2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z"/>',
  library: '<path d="M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 12.5v-9l6 4.5-6 4.5z"/>',
  settings: '<path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/>',
  record: '<circle cx="12" cy="12" r="8"/>',
  person: '<path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>',
  fullscreen: '<path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/>',
  guide: '<path d="M3 14h4v-4H3v4zm0 5h4v-4H3v4zM3 9h4V5H3v4zm5 5h13v-4H8v4zm0 5h13v-4H8v4zM8 5v4h13V5H8z"/>',
  visibility: '<path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/>',
  chevron: '<path d="M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/>',
  check: '<path d="M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/>',
  play: '<path d="M8 5v14l11-7z"/>',
  pause: '<path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>',
  replay10: '<path d="M11.99 5V1l-5 5 5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6h-2c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8zm-1.1 11h-.85v-3.26l-1.01.31v-.69l1.77-.63h.09V16zm4.28-1.76c0 .32-.03.6-.1.82s-.17.42-.29.57-.28.26-.45.33-.37.1-.59.1-.41-.03-.59-.1-.33-.18-.46-.33-.23-.34-.3-.57-.11-.5-.11-.82v-.74c0-.32.03-.6.1-.82s.17-.42.29-.57.28-.26.45-.33.37-.1.59-.1.41.03.59.1.33.18.46.33.23.34.3.57.11.5.11.82v.74zm-.85-.86c0-.19-.01-.35-.04-.48s-.07-.23-.12-.31-.11-.14-.19-.17-.16-.05-.25-.05-.18.02-.25.05-.14.09-.19.17-.09.18-.12.31-.04.29-.04.48v.97c0 .19.01.35.04.48s.07.24.12.32.11.14.19.17.16.05.25.05.18-.02.25-.05.14-.09.19-.17.09-.19.11-.32.04-.29.04-.48v-.97z"/>',
  forward30: '<path d="M18 13c0 3.31-2.69 6-6 6s-6-2.69-6-6 2.69-6 6-6v4l5-5-5-5v4c-4.42 0-8 3.58-8 8s3.58 8 8 8 8-3.58 8-8h-2zm-7.46 2.22c-.06.05-.12.09-.2.12s-.17.04-.27.04c-.09 0-.17-.01-.25-.04s-.14-.06-.2-.11-.1-.1-.13-.17-.05-.14-.05-.22h-.85c0 .21.04.39.12.55s.19.28.33.38.29.18.46.23.35.07.53.07c.21 0 .41-.03.6-.08s.34-.14.48-.24.24-.24.32-.39.12-.33.12-.53c0-.23-.06-.44-.18-.61s-.3-.3-.54-.39c.1-.05.2-.1.28-.17s.15-.14.2-.22.1-.16.13-.25.04-.18.04-.27c0-.2-.04-.37-.11-.53s-.17-.28-.3-.38-.28-.18-.46-.23-.37-.08-.59-.08c-.19 0-.38.03-.54.08s-.32.13-.44.23-.23.22-.3.37-.11.3-.11.48h.85c0-.07.02-.14.05-.2s.07-.11.12-.15.11-.07.18-.1.14-.03.22-.03c.1 0 .18.01.25.04s.13.06.18.11.08.11.11.17.04.14.04.22c0 .18-.05.32-.16.43s-.26.16-.48.16h-.43v.66h.45c.11 0 .2.01.29.04s.16.06.22.11.11.12.14.2.05.18.05.29c0 .09-.01.17-.04.24s-.08.11-.13.17zm3.9-3.44c-.18-.07-.37-.1-.59-.1s-.41.03-.59.1-.33.18-.45.33-.23.34-.29.57-.1.5-.1.82v.74c0 .32.04.6.11.82s.17.42.3.57.28.26.46.33.37.1.59.1.41-.03.59-.1.33-.18.45-.33.22-.34.29-.57.1-.5.1-.82v-.74c0-.32-.04-.6-.11-.82s-.17-.42-.3-.57-.29-.26-.46-.33zm.01 2.57c0 .19-.01.35-.04.48s-.06.24-.11.32-.11.14-.19.17-.16.05-.25.05-.18-.02-.25-.05-.14-.09-.19-.17-.09-.19-.12-.32-.04-.29-.04-.48v-.97c0-.19.01-.35.04-.48s.06-.23.12-.31.11-.14.19-.17.16-.05.25-.05.18.02.25.05.14.09.19.17.09.18.12.31.04.29.04.48v.97z"/>',
  prev: '<path d="M6 6h2v12H6zm3.5 6 8.5 6V6z"/>',
  next: '<path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/>',
  folder: '<path d="M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/>',
  history: '<path d="M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/>',
};

function icon(name) {
  const span = document.createElement('span');
  span.style.display = 'inline-flex';
  span.innerHTML = `<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">${ICONS[name] || ''}</svg>`;
  return span.firstChild;
}

function iconButton(name, label, onClick, extraClass) {
  return h('button.icon-btn' + (extraClass ? '.' + extraClass : ''), { 'aria-label': label, title: label, onclick: onClick }, icon(name));
}

// ---------- Formatting ----------

function pad2(n) { return String(n).padStart(2, '0'); }

function formatTime(epochSeconds) {
  if (!epochSeconds) return '';
  const d = new Date(epochSeconds * 1000);
  return pad2(d.getHours()) + ':' + pad2(d.getMinutes());
}

function formatDate(epochSeconds) {
  return new Date(epochSeconds * 1000).toLocaleDateString('nl-NL', { day: 'numeric', month: 'long', year: 'numeric' });
}

function formatDateTime(epochSeconds) {
  return new Date(epochSeconds * 1000).toLocaleString('nl-NL', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

function nowSeconds() { return Math.floor(Date.now() / 1000); }

function decodeB64(v) {
  if (!v) return '';
  try {
    const bin = atob(v);
    const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
    return new TextDecoder('utf-8').decode(bytes).trim();
  } catch (e) {
    return String(v);
  }
}

// ---------- Images ----------

/** Channel logo, or its initials when the provider has none / it fails to load. */
function channelLogo(channel, size) {
  const initials = (channel.name || '?').replace(/^[^A-Za-z0-9]+/, '').split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join('');
  const ph = h('div.logo.ph', { style: { width: size + 'px', height: size + 'px' } }, initials || '·');
  if (!channel.stream_icon) return ph;
  const img = h('img.logo', { src: channel.stream_icon, alt: '', loading: 'lazy', style: { width: size + 'px', height: size + 'px' } });
  img.addEventListener('error', () => img.replaceWith(ph));
  return img;
}

function progressBar(start, stop, now) {
  const p = stop > start ? Math.min(1, Math.max(0, (now - start) / (stop - start))) : 0;
  return h('div.progress', null, h('div', { style: { width: (p * 100).toFixed(1) + '%' } }));
}

// ---------- Dialogs & toast ----------

function showDialog({ title, body, actions }) {
  const host = document.getElementById('dialogHost');
  const backdrop = h('div.dialog-backdrop');
  const close = () => { backdrop.remove(); document.removeEventListener('keydown', onKey, true); };
  const onKey = (e) => { if (e.key === 'Escape') { e.stopPropagation(); e.preventDefault(); close(); } };
  document.addEventListener('keydown', onKey, true);
  const box = h('div.dialog', { role: 'dialog', 'aria-modal': 'true' },
    h('h2', { text: title }),
    body,
    h('div.actions', null, actions.map((a) => h('button.btn' + (a.primary ? '' : '.btn-text'), {
      onclick: () => { if (a.onClick && a.onClick() === false) return; close(); },
    }, a.label)))
  );
  backdrop.appendChild(box);
  backdrop.addEventListener('mousedown', (e) => { if (e.target === backdrop) close(); });
  host.appendChild(backdrop);
  const first = box.querySelector('input, button.btn:not(.btn-text), button');
  if (first) setTimeout(() => first.focus(), 0);
  return close;
}

function confirmDialog(title, message, confirmLabel, onConfirm) {
  showDialog({
    title,
    body: h('div.hint', { text: message }),
    actions: [{ label: 'Annuleren' }, { label: confirmLabel, primary: true, onClick: onConfirm }],
  });
}

/** Text input dialog (Electron has no window.prompt). */
function promptDialog(title, value, okLabel, onOk) {
  const input = h('input.input', { value: value || '' });
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); close(); onOk(input.value.trim()); } });
  const close = showDialog({
    title,
    body: input,
    actions: [{ label: 'Annuleren' }, { label: okLabel, primary: true, onClick: () => onOk(input.value.trim()) }],
  });
}

let toastTimer = null;
function toast(message, ms = 3000) {
  const el = document.getElementById('toast');
  // Keep it visible when the player is in full screen.
  const host = document.fullscreenElement || document.body;
  if (el.parentNode !== host) host.appendChild(el);
  el.textContent = message;
  el.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), ms);
}

/** Panel header with a title, subtitle and a search toggle (like the Android app). */
function panelHeader({ title, subtitle, query, placeholder, onQuery, onBack }) {
  const wrap = h('div');
  const input = h('input.input', { type: 'search', placeholder, value: query || '' });
  const searchRow = h('div.panel-search' + (query ? '' : '.hidden'), null, input);
  const toggle = iconButton(query ? 'close' : 'search', query ? 'Zoeken sluiten' : 'Zoeken', () => {
    const open = searchRow.classList.contains('hidden');
    searchRow.classList.toggle('hidden', !open);
    toggle.innerHTML = '';
    toggle.appendChild(icon(open ? 'close' : 'search'));
    if (open) input.focus();
    else if (input.value) { input.value = ''; onQuery(''); }
  });
  input.addEventListener('input', () => onQuery(input.value));
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && input.value) { e.stopPropagation(); input.value = ''; onQuery(''); }
  });
  wrap.appendChild(h('div.panel-header', null,
    onBack ? iconButton('back', 'Terug', onBack) : null,
    h('div.titles', null, h('div.title.ellipsis', { text: title }), subtitle ? h('div.subtitle', { text: subtitle }) : null),
    toggle
  ));
  wrap.appendChild(searchRow);
  return wrap;
}

function topBar(title, onBack, ...actions) {
  return h('div.topbar', null,
    iconButton('back', 'Terug', onBack),
    h('h1.grow.ellipsis', { text: title }),
    ...actions
  );
}

function clockEl() {
  const el = h('div.clock', { text: formatTime(nowSeconds()) });
  const t = setInterval(() => {
    if (!el.isConnected) { clearInterval(t); return; }
    el.textContent = formatTime(nowSeconds());
  }, 10000);
  return el;
}
