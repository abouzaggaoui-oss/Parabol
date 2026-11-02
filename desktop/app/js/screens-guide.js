/* Parabol Desktop — full TV guide of one channel (watch back). */

SCREENS.guide = ({ channel }) => {
  const el = h('div.screen');
  const body = h('div.page');
  let listings = null;

  el.appendChild(topBar(channel.name + ' — tv-gids', () => Router.back(), clockEl()));
  el.appendChild(body);

  function render() {
    body.innerHTML = '';
    const inner = h('div.page-inner');
    body.appendChild(inner);
    if (listings === null) { inner.appendChild(h('div.empty', null, h('div.spinner'))); return; }
    if (listings === 'error') {
      inner.appendChild(h('div.empty', null, h('div.col.gap-12', { style: { alignItems: 'center' } },
        h('div', { text: 'Kon de tv-gids niet laden.' }), h('button.btn', { onclick: load }, 'Opnieuw proberen'))));
      return;
    }
    if (!listings.length) { inner.appendChild(h('div.empty', { text: 'Geen tv-gids beschikbaar voor deze zender.' })); return; }

    const now = nowSeconds();
    const hasCatchup = channel.tv_archive === 1 || channel.tv_archive === '1';
    const catchupDays = parseInt(channel.tv_archive_duration, 10) || 0;
    let lastDay = '';
    let nowRow = null;

    listings.forEach((x) => {
      const start = startSec(x);
      const stop = stopSec(x);
      const day = new Date(start * 1000).toLocaleDateString('nl-NL', { weekday: 'long', day: 'numeric', month: 'long' });
      if (day !== lastDay) { inner.appendChild(h('div.day-title', { text: day })); lastDay = day; }
      const isNow = start <= now && stop > now;
      const canCatchup = stop <= now && hasCatchup && now - start <= catchupDays * 86400;
      const title = decodeB64(x.title);
      const desc = decodeB64(x.description);
      const actions = [];

      if (canCatchup) {
        actions.push(h('button.btn.btn-outline', {
          onclick: () => {
            const minutes = Math.max(1, Math.round((stop - start) / 60));
            VodPlayer.play([{ url: Xtream.catchupUrl(channel.stream_id, x.start, start, minutes, M.epgOffset), title: `${channel.name} — ${title}` }], 0);
          },
        }, icon('history'), 'Terugkijken'));
      }

      const row = h('div.epg-item' + (isNow ? '.now' : ''), null,
        h('div.time', { text: `${formatTime(start)} – ${formatTime(stop)}` + (isNow ? '  NU' : '') }),
        h('div.grow', null,
          h('div.ttl', { text: title }),
          desc ? h('div.d', { text: desc }) : null,
          actions.length ? h('div.actions', null, actions) : null));
      inner.appendChild(row);
      if (isNow) nowRow = row;
    });
    if (nowRow) requestAnimationFrame(() => nowRow.scrollIntoView({ block: 'center' }));
  }

  function load() {
    listings = null;
    render();
    Xtream.getFullEpg(channel.stream_id)
      .then((r) => {
        listings = withoutOverlaps(r && r.epg_listings);
        render();
      })
      .catch(() => { listings = 'error'; render(); });
  }

  const off = onModelChange((what) => { if (what === 'epgOffset') render(); });
  load();
  return { el, destroy: off };
};
