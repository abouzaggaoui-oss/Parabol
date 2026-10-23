/* Parabol Desktop — Movies and Series: groups on the left, posters with search on the right
 * (same layout as Live TV), plus the series page with seasons and episodes. */

const BrowseState = {
  movies: { groupId: null, groupQuery: '', titleQuery: '' },
  series: { groupId: null, groupQuery: '', titleQuery: '' },
};

/** Resume key for a movie/episode URL without the account password: "movie/123.mkv". */
function resumeKeyFor(url) {
  try {
    const parts = new URL(url).pathname.split('/').filter(Boolean);
    return parts.length >= 4 ? parts[0] + '/' + parts[parts.length - 1] : url;
  } catch (e) {
    return url;
  }
}

function browseScreen(kind) {
  const isMovies = kind === 'movies';
  const type = isMovies ? 'vod' : 'series';
  const st = BrowseState[kind];
  const el = h('div.screen');
  let gridEl = null;
  let groupsEl = null;
  let items = [];
  let rendered = 0;
  let sentinelObserver = null;

  function allItems() {
    return isMovies
      ? M.vodStreams.map((v) => ({ id: v.stream_id, name: v.name, image: v.stream_icon, group: String(v.category_id), key: 'vod:' + v.stream_id, ext: v.container_extension }))
      : M.seriesList.map((s) => ({ id: s.series_id, name: s.name, image: s.cover, group: String(s.category_id), key: 'series:' + s.series_id }));
  }

  function open(item) {
    if (isMovies) {
      const url = Xtream.movieUrl(item.id, item.ext);
      VodPlayer.play([{ url, title: item.name, resumeKey: resumeKeyFor(url) }], 0);
    } else {
      Router.go('seriesDetail', { item });
    }
  }

  function render() {
    const all = allItems();
    const byGroup = new Map();
    all.forEach((it) => { if (!byGroup.has(it.group)) byGroup.set(it.group, []); byGroup.get(it.group).push(it); });
    const counts = new Map();
    byGroup.forEach((v, k) => counts.set(k, v.length));
    const favorites = all.filter((it) => isFavorite(it.key));

    if (st.groupId === null || (st.groupId !== FAVORITES_GROUP && !visibleGroups(type, '').some((g) => String(g.category_id) === String(st.groupId)))) {
      const first = visibleGroups(type, '')[0];
      st.groupId = favorites.length ? FAVORITES_GROUP : first ? first.category_id : null;
    }
    const groupItems = st.groupId === FAVORITES_GROUP ? favorites : byGroup.get(String(st.groupId)) || [];
    const q = st.titleQuery.toLowerCase();
    items = q ? groupItems.filter((it) => String(it.name).toLowerCase().includes(q)) : groupItems;
    const title = st.groupId === FAVORITES_GROUP ? 'Favorieten'
      : ((categoriesOf(type).find((c) => String(c.category_id) === String(st.groupId)) || {}).category_name || 'Kies een groep');

    el.innerHTML = '';
    el.appendChild(topBar(isMovies ? 'Films' : 'Series', () => Router.back()));
    const split = h('div.split');
    groupsEl = groupsPanel({
      type, selectedId: st.groupId, query: st.groupQuery,
      onQuery: (qq) => { st.groupQuery = qq; },
      onSelect: (id) => { st.groupId = id; st.titleQuery = ''; render(); const f = gridEl.querySelector('.poster'); if (f) f.focus(); },
      favoritesCount: favorites.length, counts, width: 260,
    });
    split.appendChild(groupsEl);

    const right = h('div.panel.grow');
    const subtitle = () => `${items.length} titels`;
    right.appendChild(panelHeader({
      title, subtitle: subtitle(), query: st.titleQuery, placeholder: 'Zoek titels…',
      onQuery: (qq) => {
        st.titleQuery = qq;
        const lq = qq.toLowerCase();
        items = lq ? groupItems.filter((it) => String(it.name).toLowerCase().includes(lq)) : groupItems;
        right.querySelector('.subtitle').textContent = subtitle();
        renderGrid();
      },
    }));
    gridEl = h('div.grid');
    right.appendChild(gridEl);
    split.appendChild(right);
    el.appendChild(split);
    renderGrid();
    requestAnimationFrame(() => groupsEl.scrollToSelected());
  }

  function renderGrid() {
    gridEl.innerHTML = '';
    gridEl.scrollTop = 0;
    rendered = 0;
    if (!items.length) {
      gridEl.appendChild(h('div.empty', {
        style: { gridColumn: '1 / -1' },
        text: st.groupId === FAVORITES_GROUP && !st.titleQuery ? 'Nog geen favorieten. Klik met rechts op een poster om hem toe te voegen.' : 'Niets gevonden.',
      }));
      return;
    }
    appendPosters(120);
  }

  function appendPosters(n) {
    const old = gridEl.querySelector('.sentinel');
    if (old) old.remove();
    const end = Math.min(items.length, rendered + n);
    for (let i = rendered; i < end; i++) gridEl.appendChild(poster(items[i]));
    rendered = end;
    if (rendered < items.length) {
      const s = h('div.sentinel', { style: { gridColumn: '1 / -1', height: '1px' } });
      gridEl.appendChild(s);
      if (sentinelObserver) sentinelObserver.disconnect();
      sentinelObserver = new IntersectionObserver((en) => { if (en[0].isIntersecting) appendPosters(120); }, { root: gridEl, rootMargin: '600px' });
      sentinelObserver.observe(s);
    }
  }

  function poster(it) {
    const fav = isFavorite(it.key);
    const ph = h('div.ph', { text: it.name });
    const img = it.image ? h('img', { src: it.image, alt: '', loading: 'lazy' }) : null;
    if (img) img.addEventListener('error', () => img.remove());
    return h('button.poster', {
      title: it.name,
      onclick: () => open(it),
      oncontextmenu: (e) => {
        e.preventDefault();
        toggleFavorite(it.key);
        toast(isFavorite(it.key) ? `${it.name} toegevoegd aan favorieten` : `${it.name} verwijderd uit favorieten`);
      },
    },
      h('div.img', null, ph, img, fav ? h('div.badge', null, icon('star')) : null),
      h('div.t', { text: it.name }));
  }

  const off = onModelChange((what) => { if (what === 'data' || what === 'groups' || what === 'favorites') render(); });
  render();

  return {
    el,
    onKey: (e) => {
      if (isTyping(e)) return false;
      if (arrowNavigate(e, '.menu-row')) return true;
      if (e.key === 'ArrowRight' && document.activeElement && document.activeElement.matches('.menu-row')) {
        const f = gridEl.querySelector('.poster'); if (f) f.focus(); return true;
      }
      return false;
    },
    afterMount: () => groupsEl.focusSelected(),
    destroy: () => { off(); if (sentinelObserver) sentinelObserver.disconnect(); },
  };
}

SCREENS.movies = () => browseScreen('movies');
SCREENS.series = () => browseScreen('series');

// ---------------- Series page ----------------

SCREENS.seriesDetail = ({ item }) => {
  const el = h('div.screen');
  const body = h('div.page');
  let season = null;
  let info = null;
  const favBtn = iconButton('starOutline', 'Favoriet', () => { toggleFavorite(item.key); paintFav(); });
  function paintFav() {
    const on = isFavorite(item.key);
    favBtn.innerHTML = '';
    favBtn.appendChild(icon(on ? 'star' : 'starOutline'));
    favBtn.classList.toggle('active', on);
    favBtn.title = on ? 'Uit favorieten' : 'Aan favorieten toevoegen';
  }
  paintFav();
  el.appendChild(topBar(item.name, () => Router.back(), favBtn));
  el.appendChild(body);

  function render() {
    body.innerHTML = '';
    const inner = h('div.page-inner');
    body.appendChild(inner);
    if (!info) { inner.appendChild(h('div.empty', null, h('div.spinner'))); return; }
    if (info === 'error') {
      inner.appendChild(h('div.empty', null, h('div.col.gap-12', { style: { alignItems: 'center' } },
        h('div', { text: 'Kon de serie niet laden.' }),
        h('button.btn', { onclick: load }, 'Opnieuw proberen'))));
      return;
    }
    const meta = info.info || {};
    const cover = meta.cover || item.image;
    inner.appendChild(h('div.series-head', null,
      cover ? h('img', { src: cover, alt: '' }) : null,
      h('div', null,
        h('div', { style: { fontSize: '24px', fontWeight: 700 }, text: meta.name || item.name }),
        meta.plot ? h('div.muted', { style: { marginTop: '10px', lineHeight: 1.5 }, text: meta.plot }) : null)));

    const episodes = info.episodes || {};
    const seasons = Object.keys(episodes).sort((a, b) => (parseInt(a, 10) || 0) - (parseInt(b, 10) || 0));
    if (!seasons.length) { inner.appendChild(h('div.muted', { text: 'Geen afleveringen gevonden.' })); return; }
    if (!season || !episodes[season]) season = seasons[0];
    if (seasons.length > 1) {
      inner.appendChild(h('div.season-tabs', null, seasons.map((s) => h('button.tab-pill' + (s === season ? '.active' : ''), {
        onclick: () => { season = s; render(); },
      }, 'Seizoen ' + s))));
    } else {
      inner.appendChild(h('div.section-title', { text: 'Seizoen ' + season }));
    }
    const list = episodes[season].slice().sort((a, b) => (parseInt(a.episode_num, 10) || 0) - (parseInt(b.episode_num, 10) || 0));
    const queue = list.map((ep) => {
      const url = Xtream.episodeUrl(ep.id, ep.container_extension);
      return { url, title: `${meta.name || item.name} — ${ep.title || 'Aflevering ' + ep.episode_num}`, resumeKey: resumeKeyFor(url) };
    });
    list.forEach((ep, i) => {
      const pos = Store.getPosition(queue[i].resumeKey);
      inner.appendChild(h('button.ep-row', { onclick: () => VodPlayer.play(queue, i) },
        h('span.n', { text: String(ep.episode_num || i + 1) }),
        h('span.grow.ellipsis', { text: ep.title || 'Aflevering ' + (ep.episode_num || i + 1) }),
        pos ? h('span.resume', null, `verder vanaf ${Math.floor(pos / 60)} min`) : null));
    });
  }

  function load() {
    info = null;
    render();
    Xtream.getSeriesInfo(item.id).then((r) => { info = r || 'error'; render(); }).catch(() => { info = 'error'; render(); });
  }

  const off = onModelChange((what) => { if (what === 'positions') render(); });
  load();
  return { el, destroy: off };
};
