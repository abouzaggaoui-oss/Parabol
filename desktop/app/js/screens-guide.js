/* Parabol Desktop — full TV guide of one channel (record / watch back) and Recordings. */

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
      const isFuture = start > now;
      const canCatchup = stop <= now && hasCatchup && now - start <= catchupDays * 86400;
      const title = decodeB64(x.title);
      const desc = decodeB64(x.description);
      const actions = [];

      if (isFuture || isNow) {
        const rec = M.scheduledRecordings.find((r) => String(r.streamId) === String(channel.stream_id) && r.startEpochSeconds === start);
        actions.push(h('button.btn' + (rec ? '' : '.btn-outline'), {
          onclick: () => {
            if (rec) cancelRecording(rec.id);
            else { scheduleRecording(channel, title, Math.max(start, now), stop); toast('Opname ingepland: ' + title); }
          },
        }, icon('record'), rec ? 'Ingepland — annuleren' : (isNow ? 'Nu opnemen' : 'Opnemen')));
      }
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
        listings = arr(r && r.epg_listings).slice().sort((a, b) => startSec(a) - startSec(b));
        render();
      })
      .catch(() => { listings = 'error'; render(); });
  }

  const off = onModelChange((what) => { if (what === 'recordings' || what === 'epgOffset') render(); });
  load();
  return { el, destroy: off };
};

SCREENS.recordings = () => {
  const el = h('div.screen');
  const body = h('div.page');
  el.appendChild(topBar('Opnames', () => Router.back(),
    h('button.btn.btn-outline', { onclick: () => window.parabolAPI && window.parabolAPI.openRecordingsFolder() }, icon('folder'), 'Map openen')));
  el.appendChild(body);

  function render() {
    body.innerHTML = '';
    const inner = h('div.page-inner');
    body.appendChild(inner);
    inner.appendChild(h('div.section-title', { text: 'Gepland' }));
    if (!M.scheduledRecordings.length) {
      inner.appendChild(h('div.hint', { text: 'Nog geen opnames ingepland. Open de tv-gids van een zender en kies Opnemen.' }));
    }
    M.scheduledRecordings.slice().sort((a, b) => a.startEpochSeconds - b.startEpochSeconds).forEach((r) => {
      const active = r.startEpochSeconds <= nowSeconds();
      inner.appendChild(h('div.card', null, h('div.card-row', null,
        h('div.grow', null,
          h('div', { style: { fontWeight: 600 }, text: r.programTitle }),
          h('div.hint', { text: `${r.channelName} · ${formatDateTime(r.startEpochSeconds)} – ${formatTime(r.endEpochSeconds)}` + (active ? ' · bezig met opnemen' : '') })),
        h('button.btn.btn-text', { onclick: () => cancelRecording(r.id) }, active ? 'Stoppen' : 'Annuleren'))));
    });

    inner.appendChild(h('div.section-title', { text: 'Opgenomen' }));
    if (!M.completedRecordings.length) inner.appendChild(h('div.hint', { text: 'Nog geen opnames.' }));
    M.completedRecordings.slice().sort((a, b) => b.recordedAtEpochSeconds - a.recordedAtEpochSeconds).forEach((r) => {
      inner.appendChild(h('div.card', null, h('div.card-row', null,
        h('div.grow', null,
          h('div', { style: { fontWeight: 600 }, text: r.programTitle }),
          h('div.hint', { text: `${r.channelName} · ${formatDateTime(r.recordedAtEpochSeconds)}` })),
        h('button.btn', { onclick: () => VodPlayer.play([{ url: 'parabol-rec://rec/' + encodeURIComponent(r.id) + '.' + (r.ext || 'ts'), src: 'rec:' + r.id, title: r.programTitle }], 0) }, 'Afspelen'),
        h('button.btn.btn-text', {
          onclick: () => confirmDialog('Opname verwijderen?', `"${r.programTitle}" wordt van je computer verwijderd.`, 'Verwijderen', () => deleteCompletedRecording(r.id)),
        }, 'Verwijderen'))));
    });
  }

  const off = onModelChange((what) => { if (what === 'recordings') render(); });
  render();
  refreshRecordings();
  return { el, destroy: off };
};
