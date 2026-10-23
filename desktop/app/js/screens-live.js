/* Parabol Desktop — Live TV (same layout as the Android app):
 * groups on the left, channels with a TV-guide timeline, and the playing channel in a
 * preview on the right (split view). Picking a channel opens it full screen; Esc goes back
 * to the split view, again stops playback, again goes to Home. */

const FAVORITES_GROUP = '__favorites__';

/** Left-hand groups menu shared by Live TV, Movies and Series. */
function groupsPanel({ type, selectedId, query, onQuery, onSelect, favoritesCount, counts, width }) {
  const panel = h('div.panel', { style: { width: width + 'px', flex: 'none' } });
  const list = h('div.list');

  function rows() {
    list.innerHTML = '';
    const groups = visibleGroups(type, query);
    list.appendChild(h('button.menu-row' + (selectedId === FAVORITES_GROUP ? '.selected' : ''), {
      'data-group': FAVORITES_GROUP, onclick: () => onSelect(FAVORITES_GROUP),
    }, icon('star'), h('span.grow.ellipsis', { text: 'Favorieten' }), h('span.count', { text: String(favoritesCount) })));
    groups.forEach((g) => {
      list.appendChild(h('button.menu-row' + (String(g.category_id) === String(selectedId) ? '.selected' : ''), {
        'data-group': g.category_id, onclick: () => onSelect(g.category_id), title: g.category_name,
      }, h('span.grow.ellipsis', { text: g.category_name }), h('span.count', { text: String(counts.get(String(g.category_id)) || 0) })));
    });
    if (!groups.length && query) list.appendChild(h('div.empty', { text: 'Geen groepen gevonden.' }));
  }

  panel.appendChild(panelHeader({
    title: 'Groepen', query, placeholder: 'Zoek groepen…',
    onQuery: (q) => { query = q; onQuery(q); rows(); },
  }));
  panel.appendChild(list);
  rows();
  panel.scrollToSelected = () => {
    const sel = list.querySelector('.menu-row.selected');
    if (sel) sel.scrollIntoView({ block: 'nearest' });
  };
  panel.focusSelected = () => {
    const sel = list.querySelector('.menu-row.selected') || list.querySelector('.menu-row');
    if (sel) sel.focus();
  };
  return panel;
}

/** Arrow up/down moves focus between rows of the same kind (e.g. channels). */
function arrowNavigate(e, selector) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return false;
  const active = document.activeElement;
  if (!active || !active.matches(selector)) return false;
  const all = Array.from(document.querySelectorAll(selector));
  const i = all.indexOf(active);
  const next = all[i + (e.key === 'ArrowDown' ? 1 : -1)];
  if (next) { next.focus(); next.scrollIntoView({ block: 'nearest' }); }
  return true;
}

const LiveState = {
  groupId: null,
  groupQuery: '',
  channelQuery: '',
  session: null, // { channels, index, groupId }
  keepSession: false,
};

SCREENS.live = () => {
  const el = h('div.screen');
  let now = nowSeconds();
  let rowById = new Map();
  let timelineMode = false;
  let windowLength = 7200;
  let renderedCount = 0;
  let channels = [];
  let channelsPanelEl = null;
  let listEl = null;
  let rulerEl = null;
  let previewEl = null;
  let groupsEl = null;
  let rowObserver = null;
  let sentinelObserver = null;
  let resizeObserver = null;

  if (LiveState.groupId === null) LiveState.groupId = M.lastLiveGroupId;

  const playing = () => (LiveState.session ? LiveState.session.channels[LiveState.session.index] : null);

  function channelsByGroup() {
    const map = new Map();
    M.liveStreams.forEach((s) => {
      const k = String(s.category_id);
      if (!map.has(k)) map.set(k, []);
      map.get(k).push(s);
    });
    return map;
  }

  function currentChannels(byGroup) {
    let list;
    if (LiveState.groupId === FAVORITES_GROUP) list = M.liveStreams.filter((s) => isFavorite('live:' + s.stream_id));
    else list = byGroup.get(String(LiveState.groupId)) || [];
    const q = LiveState.channelQuery.toLowerCase();
    return q ? list.filter((s) => String(s.name).toLowerCase().includes(q)) : list;
  }

  function groupTitle() {
    if (LiveState.groupId === FAVORITES_GROUP) return 'Favorieten';
    const g = M.liveCategories.find((c) => String(c.category_id) === String(LiveState.groupId));
    return g ? g.category_name : 'Kies een groep';
  }

  // ---------- Playback ----------

  function play(list, index, fullscreen = true) {
    if (!list.length) return;
    const i = ((index % list.length) + list.length) % list.length;
    LiveState.session = { channels: list, index: i, groupId: LiveState.groupId };
    LivePlayer.play(list[i]);
    loadShortEpg(list[i].stream_id);
    if (fullscreen) {
      render(); // split layout behind the full-screen video
      LivePlayer.enterFullscreen();
    } else {
      render();
      LivePlayer.setMode('split');
    }
  }

  function zap(delta) {
    const s = LiveState.session;
    if (!s || s.channels.length < 2) return;
    const i = ((s.index + delta) % s.channels.length + s.channels.length) % s.channels.length;
    s.index = i;
    LivePlayer.play(s.channels[i]);
    loadShortEpg(s.channels[i].stream_id);
    LivePlayer.showBanner();
    renderPreview();
    markPlayingRow();
  }

  function stop() {
    LiveState.session = null;
    LivePlayer.stop();
    render();
  }

  function openGuide(channel) {
    LiveState.keepSession = true;
    Router.go('guide', { channel });
  }

  LivePlayer.onExitFullscreen = () => {
    const p = playing();
    if (!p) return;
    const row = rowById.get(p.stream_id);
    if (row) { row.scrollIntoView({ block: 'center' }); row.focus({ preventScroll: true }); }
  };

  LivePlayer.bannerBuilder = () => {
    const s = LiveState.session;
    const ch = playing();
    if (!ch) return h('div');
    const epg = nowAndNext(ch.stream_id, nowSeconds());
    const c = epg.current;
    const nx = epg.next[0];
    return h('div.row.gap-16.grow', null,
      h('div.num', { text: String(ch.num || s.index + 1) }),
      channelLogo(ch, 56),
      h('div.grow', null,
        h('div.name.ellipsis', { text: ch.name }),
        c ? [h('div.line.ellipsis', { text: `${formatTime(startSec(c))} – ${formatTime(stopSec(c))}   ${decodeB64(c.title)}` }),
          progressBar(startSec(c), stopSec(c), nowSeconds())]
          : h('div.line', { style: { opacity: 0.7 }, text: epg.loaded ? 'Geen tv-gids beschikbaar' : '' }),
        nx ? h('div.next.ellipsis', { text: `Hierna  ${formatTime(startSec(nx))}  ${decodeB64(nx.title)}` }) : null
      )
    );
  };

  // ---------- Rendering ----------

  function render() {
    const byGroup = channelsByGroup();
    const counts = new Map();
    byGroup.forEach((v, k) => counts.set(k, v.length));
    const favCount = M.liveStreams.filter((s) => isFavorite('live:' + s.stream_id)).length;

    // Open on a group straight away so the channel list isn't empty.
    if (LiveState.groupId === null || (LiveState.groupId !== FAVORITES_GROUP && !visibleGroups('live', '').some((g) => String(g.category_id) === String(LiveState.groupId)))) {
      const first = visibleGroups('live', '')[0];
      LiveState.groupId = favCount ? FAVORITES_GROUP : first ? first.category_id : null;
    }
    M.lastLiveGroupId = LiveState.groupId;
    channels = currentChannels(byGroup);
    const p = playing();

    el.innerHTML = '';
    el.appendChild(topBar('Live tv', () => Router.back(), clockEl()));

    const split = h('div.split');
    groupsEl = groupsPanel({
      type: 'live',
      selectedId: LiveState.groupId,
      query: LiveState.groupQuery,
      onQuery: (q) => { LiveState.groupQuery = q; },
      onSelect: (id) => {
        LiveState.groupId = id;
        LiveState.channelQuery = '';
        render();
        const first = listEl && listEl.querySelector('.ch-row');
        if (first) first.focus();
      },
      favoritesCount: favCount,
      counts,
      width: p ? 220 : 260,
    });
    split.appendChild(groupsEl);

    channelsPanelEl = h('div.panel.grow');
    split.appendChild(channelsPanelEl);
    renderChannelsPanel();

    if (p) {
      previewEl = h('div.panel.preview', { style: { width: 'clamp(300px, 36vw, 460px)', flex: 'none' } });
      split.appendChild(previewEl);
      renderPreview();
    } else {
      previewEl = null;
      LivePlayer.setSlot(null);
    }
    el.appendChild(split);
    requestAnimationFrame(() => groupsEl.scrollToSelected());
  }

  function renderChannelsPanel() {
    const panel = channelsPanelEl;
    panel.innerHTML = '';
    if (resizeObserver) resizeObserver.disconnect();
    panel.appendChild(panelHeader({
      title: groupTitle(),
      subtitle: `${channels.length} zenders`,
      query: LiveState.channelQuery,
      placeholder: 'Zoek zenders…',
      onQuery: (q) => {
        LiveState.channelQuery = q;
        channels = currentChannels(channelsByGroup());
        panel.querySelector('.subtitle').textContent = `${channels.length} zenders`;
        renderList();
      },
    }));
    rulerEl = h('div.ruler');
    listEl = h('div.list');
    panel.appendChild(rulerEl);
    panel.appendChild(listEl);
    resizeObserver = new ResizeObserver(() => {
      const w = panel.clientWidth;
      const tl = w >= 560;
      const len = w - 250 > 900 ? 3 * 3600 : 2 * 3600;
      if (tl !== timelineMode || len !== windowLength || !listEl.childElementCount) {
        timelineMode = tl;
        windowLength = len;
        renderList();
      }
    });
    resizeObserver.observe(panel);
  }

  function renderList() {
    if (!listEl) return;
    const scroll = listEl.scrollTop;
    listEl.innerHTML = '';
    rowById = new Map();
    if (rowObserver) rowObserver.disconnect();
    if (sentinelObserver) sentinelObserver.disconnect();
    rulerEl.classList.toggle('hidden', !timelineMode || !channels.length);
    if (timelineMode) renderRuler();

    if (!channels.length) {
      listEl.appendChild(h('div.empty', {
        text: LiveState.groupId === FAVORITES_GROUP && !LiveState.channelQuery
          ? 'Nog geen favorieten. Klik met rechts op een zender of gebruik de ster.'
          : 'Niets gevonden.',
      }));
      return;
    }
    rowObserver = new IntersectionObserver((entries) => {
      entries.forEach((en) => { if (en.isIntersecting) loadShortEpg(Number(en.target.dataset.id)); });
    }, { root: listEl, rootMargin: '200px' });
    renderedCount = 0;
    appendRows(Math.max(120, Math.ceil(scroll / 60) + 60));
    listEl.scrollTop = scroll;
  }

  function appendRows(n) {
    const end = Math.min(channels.length, renderedCount + n);
    const old = listEl.querySelector('.sentinel');
    if (old) old.remove();
    for (let i = renderedCount; i < end; i++) {
      const row = buildRow(channels[i], i);
      listEl.appendChild(row);
    }
    renderedCount = end;
    if (renderedCount < channels.length) {
      const sentinel = h('div.sentinel', { style: { height: '1px' } });
      listEl.appendChild(sentinel);
      if (sentinelObserver) sentinelObserver.disconnect();
      sentinelObserver = new IntersectionObserver((en) => { if (en[0].isIntersecting) appendRows(150); }, { root: listEl, rootMargin: '400px' });
      sentinelObserver.observe(sentinel);
    }
  }

  function windowStart() { return now - (now % 1800); }

  function renderRuler() {
    rulerEl.innerHTML = '';
    const inner = h('div', { style: { position: 'absolute', left: '254px', right: '4px', top: 0, bottom: 0 } });
    const ws = windowStart();
    for (let t = ws; t < ws + windowLength; t += 1800) {
      inner.appendChild(h('div.t', { style: { left: ((t - ws) / windowLength * 100) + '%' }, text: formatTime(t) }));
    }
    inner.appendChild(h('div.now-line', { style: { left: ((now - ws) / windowLength * 100) + '%' } }));
    rulerEl.appendChild(inner);
  }

  function buildRow(ch, index) {
    const p = playing();
    const isPlaying = p && p.stream_id === ch.stream_id;
    const fav = isFavorite('live:' + ch.stream_id);
    const number = ch.num || index + 1;
    const row = h('button.ch-row' + (timelineMode ? '.timeline' : '.compact') + (isPlaying ? '.playing' : ''), {
      'data-id': ch.stream_id,
      title: ch.name,
      onclick: () => {
        const cur = playing();
        if (cur && cur.stream_id === ch.stream_id) LivePlayer.enterFullscreen();
        else play(channels, index);
      },
      oncontextmenu: (e) => {
        e.preventDefault();
        toggleFavorite('live:' + ch.stream_id);
        toast(isFavorite('live:' + ch.stream_id) ? `${ch.name} toegevoegd aan favorieten` : `${ch.name} verwijderd uit favorieten`);
      },
    });

    if (timelineMode) {
      row.appendChild(h('div.ch-ident', { style: { width: '250px' } },
        h('div.ch-num', { text: String(number) }),
        channelLogo(ch, 40),
        h('div.ch-name.grow', { text: ch.name }),
        fav ? icon('star') : null));
      row.lastChild.querySelectorAll('svg').forEach((s) => s.classList.add('fav-star'));
      row.appendChild(buildStrip(ch));
    } else {
      const epg = nowAndNext(ch.stream_id, now);
      const c = epg.current;
      const nx = epg.next[0];
      row.appendChild(h('div.ch-num', { text: String(number) }));
      row.appendChild(channelLogo(ch, 40));
      row.appendChild(h('div.meta', null,
        h('div.ch-name.ellipsis', { text: ch.name }),
        c ? [h('div.now.ellipsis', { text: decodeB64(c.title) }), progressBar(startSec(c), stopSec(c), now)] : null,
        nx ? h('div.next.ellipsis', { text: `${formatTime(startSec(nx))}  ${decodeB64(nx.title)}` }) : null));
      if (fav) { const s = icon('star'); s.classList.add('fav-star'); s.style.marginLeft = '8px'; row.appendChild(s); }
    }
    rowById.set(ch.stream_id, row);
    if (rowObserver) rowObserver.observe(row);
    return row;
  }

  /** The coming programmes of one channel as blocks on the shared time window. */
  function buildStrip(ch) {
    const strip = h('div.strip');
    const ws = windowStart();
    const we = ws + windowLength;
    const cached = M.epgCache.get(ch.stream_id);
    const listings = cached ? cached.listings : null;
    const visible = (listings || []).filter((x) => stopSec(x) > ws && startSec(x) < we);
    if (!visible.length) strip.appendChild(h('div.no-epg', { text: listings ? 'Geen tv-gids' : '' }));
    visible.forEach((x) => {
      const s = Math.max(startSec(x), ws);
      const e = Math.min(stopSec(x), we);
      if (e <= s) return;
      const isNow = startSec(x) <= now && stopSec(x) > now;
      const left = (s - ws) / windowLength * 100;
      const width = (e - s) / windowLength * 100;
      strip.appendChild(h('div.prog' + (isNow ? '.now' : ''), {
        style: { left: left + '%', width: `calc(${width}% - 3px)` },
        title: `${formatTime(startSec(x))} – ${formatTime(stopSec(x))}  ${decodeB64(x.title)}`,
      }, h('span', { text: decodeB64(x.title) })));
    });
    strip.appendChild(h('div.now-line', { style: { left: ((now - ws) / windowLength * 100) + '%' } }));
    return strip;
  }

  function refreshRow(streamId) {
    const row = rowById.get(streamId);
    if (!row) return;
    const index = channels.findIndex((c) => c.stream_id === streamId);
    if (index < 0) return;
    const focused = document.activeElement === row;
    const fresh = buildRow(channels[index], index);
    row.replaceWith(fresh);
    if (focused) fresh.focus({ preventScroll: true });
  }

  function markPlayingRow() {
    const p = playing();
    rowById.forEach((row, id) => {
      const on = p && p.stream_id === id;
      if (row.classList.contains('playing') !== !!on) refreshRow(id);
    });
  }

  function renderPreview() {
    if (!previewEl) return;
    const ch = playing();
    if (!ch) return;
    previewEl.innerHTML = '';
    const slot = h('div.video-slot');
    const epg = nowAndNext(ch.stream_id, nowSeconds());
    const c = epg.current;
    const fav = isFavorite('live:' + ch.stream_id);
    previewEl.appendChild(slot);
    previewEl.appendChild(h('div.row.gap-12', { style: { marginTop: '14px' } },
      channelLogo(ch, 36), h('div.ellipsis', { style: { fontSize: '17px', fontWeight: 600 }, text: ch.name })));
    if (c) {
      previewEl.appendChild(h('div.label-now', { text: `NU  ·  ${formatTime(startSec(c))} – ${formatTime(stopSec(c))}` }));
      previewEl.appendChild(h('div.prog-title', { text: decodeB64(c.title) }));
      previewEl.appendChild(h('div', { style: { marginTop: '6px' } }, progressBar(startSec(c), stopSec(c), nowSeconds())));
      const d = decodeB64(c.description);
      if (d) previewEl.appendChild(h('div.desc', { text: d }));
    } else {
      previewEl.appendChild(h('div.muted', { style: { marginTop: '10px' }, text: epg.loaded ? 'Geen tv-gids beschikbaar' : 'Tv-gids laden…' }));
    }
    previewEl.appendChild(h('div.row.gap-8', { style: { marginTop: '14px' } },
      h('button.btn', { onclick: () => LivePlayer.enterFullscreen() }, icon('fullscreen'), 'Volledig scherm'),
      h('button.btn.btn-outline', {
        title: fav ? 'Uit favorieten' : 'Aan favorieten toevoegen',
        onclick: () => toggleFavorite('live:' + ch.stream_id),
      }, icon(fav ? 'star' : 'starOutline')),
      h('button.btn.btn-outline', { title: 'Tv-gids', onclick: () => openGuide(ch) }, icon('guide'))));
    if (fav) previewEl.querySelectorAll('.btn-outline svg')[0].style.color = 'var(--amber)';
    if (epg.next.length) {
      const up = h('div.upnext', null, h('div.muted', { style: { fontWeight: 600, fontSize: '13px', marginBottom: '4px' }, text: 'Hierna' }));
      epg.next.slice(0, 5).forEach((x) => up.appendChild(h('div.item', null,
        h('span.time', { text: formatTime(startSec(x)) }), h('span.ellipsis', { text: decodeB64(x.title) }))));
      previewEl.appendChild(up);
    }
    LivePlayer.setSlot(slot);
  }

  // ---------- Updates ----------

  const off = onModelChange((what) => {
    if (what && what.epg !== undefined) {
      if (timelineMode || rowById.has(what.epg)) refreshRow(what.epg);
      const p = playing();
      if (p && p.stream_id === what.epg) renderPreview();
      return;
    }
    if (what === 'data' || what === 'groups') { render(); if (LiveState.session && LivePlayer.mode !== 'fullscreen') LivePlayer.setMode('split'); return; }
    if (what === 'favorites') { render(); return; }
    if (what === 'epgOffset') { renderList(); renderPreview(); }
  });

  const tick = setInterval(() => {
    now = nowSeconds();
    if (timelineMode) renderList(); else rowById.forEach((_r, id) => refreshRow(id));
    renderPreview();
  }, 60000);

  // ---------- Keys ----------

  function onKey(e) {
    if (LivePlayer.mode === 'fullscreen') {
      switch (e.key) {
        case 'ArrowUp': case 'PageUp': zap(1); return true;
        case 'ArrowDown': case 'PageDown': zap(-1); return true;
        case 'Enter': case ' ': case 'i': case 'ArrowLeft': case 'ArrowRight': LivePlayer.showBanner(); return true;
        case 'Escape': case 'Backspace': LivePlayer.exitFullscreen(); return true;
        default: return false;
      }
    }
    if (isTyping(e)) return false;
    if (arrowNavigate(e, '.ch-row') || arrowNavigate(e, '.menu-row')) return true;
    if (e.key === 'ArrowRight' && document.activeElement && document.activeElement.matches('.menu-row')) {
      const first = listEl && listEl.querySelector('.ch-row');
      if (first) first.focus();
      return true;
    }
    if (e.key === 'ArrowLeft' && document.activeElement && document.activeElement.matches('.ch-row')) {
      groupsEl.focusSelected();
      return true;
    }
    return false;
  }

  render();
  if (LiveState.session) {
    // Back from the full guide: continue in split view.
    LivePlayer.resume();
    LivePlayer.setMode('split');
    renderPreview();
  }
  LiveState.keepSession = false;

  return {
    el,
    onKey,
    afterMount: () => {
      const p = playing();
      const target = (p && rowById.get(p.stream_id)) || (listEl && listEl.querySelector('.ch-row'));
      if (target) target.focus({ preventScroll: !p });
      else groupsEl.focusSelected();
      LivePlayer.position();
    },
    onBack: () => {
      if (LivePlayer.mode === 'fullscreen') { LivePlayer.exitFullscreen(); return true; }
      if (LiveState.session) { stop(); return true; }
      return false;
    },
    destroy: () => {
      off();
      clearInterval(tick);
      if (resizeObserver) resizeObserver.disconnect();
      if (rowObserver) rowObserver.disconnect();
      if (sentinelObserver) sentinelObserver.disconnect();
      LivePlayer.onExitFullscreen = null;
      if (LiveState.keepSession) {
        LivePlayer.pause();
        LivePlayer.setMode('hidden');
      } else {
        LiveState.session = null;
        LivePlayer.stop();
      }
      LivePlayer.setSlot(null);
    },
  };
};
