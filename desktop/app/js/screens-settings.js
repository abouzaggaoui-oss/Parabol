/* Parabol Desktop — Settings and Manage groups (choose countries, fine-tune single groups). */

SCREENS.settings = () => {
  const el = h('div.screen');
  const body = h('div.page');
  el.appendChild(topBar('Instellingen', () => Router.back()));
  el.appendChild(body);

  function render() {
    const scroll = body.scrollTop;
    body.innerHTML = '';
    const inner = h('div.page-inner');
    body.appendChild(inner);
    const active = Store.activeProfile();

    // ---- Account ----
    inner.appendChild(h('div.section-title', { text: 'Account' }));
    const exp = M.userInfo && parseInt(M.userInfo.exp_date, 10);
    inner.appendChild(h('div.card', null, h('div.card-row', null,
      h('div.grow', null,
        h('div', { text: 'Ingelogd als ' + (active ? active.username : '') }),
        exp ? h('div.hint', { text: 'Abonnement geldig tot ' + formatDate(exp) }) : null),
      h('button.btn.btn-outline', { onclick: () => { loadAll(); toast('Lijsten worden vernieuwd…'); } }, 'Vernieuwen'))));

    // ---- Accounts ----
    inner.appendChild(h('div.section-title', { text: 'Accounts' }));
    Store.getProfiles().forEach((p) => {
      const isActive = active && p.id === active.id;
      inner.appendChild(h('div.card', null, h('div.card-row', null,
        h('div.grow', null,
          h('div', { style: { fontWeight: 600 }, text: p.name }),
          h('div.hint.ellipsis', { text: `${p.username} · ${p.baseUrl}` })),
        isActive ? h('span.amber', { style: { fontWeight: 600, marginRight: '8px' }, text: 'Actief' })
          : h('button.btn.btn-outline', { onclick: () => { activateProfile(p.id); openHome(); } }, 'Wissel naar dit account'),
        h('button.btn.btn-text', {
          onclick: () => confirmDialog(`${p.name} verwijderen?`,
            'Hiermee verwijder je ook de favorieten, verborgen groepen en geplande opnames van dit account. Voltooide opnames blijven op je computer staan.',
            'Verwijderen', () => removeAccount(p.id)),
        }, 'Verwijderen'))));
    });
    inner.appendChild(h('button.btn.btn-outline', { style: { marginTop: '8px' }, onclick: () => Router.go('login', { adding: true }) }, 'Account toevoegen'));

    // ---- Groups ----
    inner.appendChild(h('div.section-title', { text: 'Groepen beheren' }));
    const total = M.liveCategories.length + M.vodCategories.length + M.seriesCategories.length;
    const hidden = ['live', 'vod', 'series'].reduce((n, t) => n + categoriesOf(t).filter((c) => isGroupHidden(t, c.category_id)).length, 0);
    inner.appendChild(h('button.card.click', { onclick: () => Router.go('groups') }, h('div.card-row', null,
      h('div.grow', null,
        h('div', { text: 'Groepen beheren' }),
        h('div.hint', { text: 'Kies welke landen en groepen je ziet bij Live tv, Films en Series.' }),
        total ? h('div.amber', { style: { fontSize: '13px', marginTop: '2px' }, text: `${total - hidden} van ${total} groepen zichtbaar` }) : null),
      icon('chevron'))));

    // ---- Subtitles ----
    inner.appendChild(h('div.section-title', { text: 'Ondertiteling (OpenSubtitles)' }));
    const key = h('input.input', { value: Store.getOpenSubtitlesApiKey(), placeholder: 'OpenSubtitles API-key' });
    key.addEventListener('change', () => { Store.setOpenSubtitlesApiKey(key.value.trim()); toast('Opgeslagen'); });
    inner.appendChild(h('div.card', null,
      h('div.hint', { style: { marginBottom: '10px' }, text: 'Maak gratis een API-key aan op opensubtitles.com/consumers en plak hem hier. Nodig om in de speler ondertiteling te zoeken.' }),
      key));

    // ---- Recordings ----
    inner.appendChild(h('div.section-title', { text: 'Opnames' }));
    inner.appendChild(h('div.card', null, h('div.card-row', null,
      h('div.grow.hint', { text: 'Opnames staan in de map Parabol in je Films-/Video\'s-map. Sluit je het venster, dan blijft Parabol actief zodat opnames doorlopen.' }),
      h('button.btn.btn-outline', { onclick: () => window.parabolAPI && window.parabolAPI.openRecordingsFolder() }, icon('folder'), 'Map openen'))));

    inner.appendChild(h('div.hint', { style: { marginTop: '28px', textAlign: 'center' }, text: 'Parabol Desktop ' + (window.parabolAPI && window.parabolAPI.version ? window.parabolAPI.version : '') }));
    body.scrollTop = scroll;
  }

  function removeAccount(id) {
    Store.removeProfile(id);
    const next = Store.activeProfile();
    if (next) { activateProfile(next.id); openHome(); }
    else { LivePlayer.stop(); Router.root('login'); }
  }

  const off = onModelChange((what) => { if (what === 'data' || what === 'groups') render(); });
  render();
  return { el, destroy: off };
};

// ---------------- Manage groups ----------------

const GROUP_TYPES = [
  { type: 'live', label: 'Live tv' },
  { type: 'vod', label: 'Films' },
  { type: 'series', label: 'Series' },
];

SCREENS.groups = () => {
  const el = h('div.screen');
  let type = 'live';
  let search = '';
  const summary = h('div.summary');
  el.appendChild(topBar('Groepen beheren', () => Router.back(), summary));
  const tabs = h('div.row.gap-8', { style: { padding: '4px 24px 8px' } });
  el.appendChild(tabs);
  const body = h('div.page');
  el.appendChild(body);
  const countriesEl = h('div.countries');
  const groupsList = h('div');
  const searchInput = h('input.input', { type: 'search', placeholder: 'Zoek groepen…' });
  searchInput.addEventListener('input', () => { search = searchInput.value; renderGroups(); });

  function filtered() {
    const q = search.toLowerCase();
    return uniqueBy(categoriesOf(type), (c) => c.category_id).filter((c) => !q || String(c.category_name).toLowerCase().includes(q));
  }

  function renderTabs() {
    tabs.innerHTML = '';
    GROUP_TYPES.forEach((t) => tabs.appendChild(h('button.tab-pill' + (t.type === type ? '.active' : ''), {
      onclick: () => { type = t.type; search = ''; searchInput.value = ''; renderAll(); },
    }, `${t.label} · ${categoriesOf(t.type).length}`)));
  }

  function renderSummary() {
    const cats = categoriesOf(type);
    const visible = cats.filter((c) => !isGroupHidden(type, c.category_id)).length;
    summary.innerHTML = '';
    summary.appendChild(icon('visibility'));
    summary.appendChild(document.createTextNode(`${visible} van ${cats.length} groepen zichtbaar`));
    summary.classList.toggle('hidden', !cats.length);
  }

  function renderCountries() {
    const focusedCode = document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.code : undefined;
    countriesEl.innerHTML = '';
    countryGroups(type).forEach((c) => {
      const all = c.visible === c.total;
      const none = c.visible === 0;
      const detail = all ? `${c.total} groepen · alles zichtbaar` : none ? `${c.total} groepen · verborgen` : `${c.visible} van ${c.total} zichtbaar · deels`;
      const tile = h('button.country' + (none ? '' : '.on'), {
        'data-code': c.code || '__other__',
        title: (none || !all ? 'Alles tonen' : 'Alles verbergen') + ' — ' + countryName(c.code),
        onclick: () => toggleCountry(type, c),
      },
        h('div.code', { text: c.code || '··' }),
        h('div.grow', null, h('div.n.ellipsis', { text: countryName(c.code) }), h('div.d.ellipsis', { text: detail })),
        h('div.switch' + (all ? '.on' : none ? '' : '.partly')));
      countriesEl.appendChild(tile);
    });
    if (focusedCode) {
      const again = countriesEl.querySelector(`[data-code="${CSS.escape(focusedCode)}"]`);
      if (again) again.focus({ preventScroll: true });
    }
  }

  function renderGroups() {
    groupsList.innerHTML = '';
    filtered().forEach((c) => {
      const on = !isGroupHidden(type, c.category_id);
      groupsList.appendChild(h('button.group-row' + (on ? '' : '.off'), {
        'data-id': c.category_id,
        onclick: () => toggleGroupHidden(type, c.category_id),
      }, h('div.checkbox' + (on ? '.on' : ''), null, on ? icon('check') : null), h('span.ellipsis', { text: c.category_name })));
    });
    if (!groupsList.childElementCount) groupsList.appendChild(h('div.hint', { style: { padding: '12px' }, text: search ? 'Geen groepen gevonden.' : 'Nog geen groepen geladen.' }));
  }

  function renderAll() {
    renderTabs();
    renderSummary();
    const scroll = body.scrollTop;
    body.innerHTML = '';
    const inner = h('div', { style: { padding: '8px 24px 40px' } });
    body.appendChild(inner);
    if (!categoriesOf(type).length) {
      inner.appendChild(h('div.empty', { text: M.loading ? 'Groepen laden…' : 'Nog geen groepen geladen.' }));
      return;
    }
    inner.appendChild(h('div', { style: { margin: '8px 0 12px' } },
      h('div', { style: { fontSize: '17px', fontWeight: 600 }, text: 'Welke landen wil je zien?' }),
      h('div.hint', { text: 'Eén klik zet alle groepen van een land aan of uit.' })));
    inner.appendChild(countriesEl);
    renderCountries();
    inner.appendChild(h('div', { style: { margin: '28px 0 10px' } },
      h('div', { style: { fontSize: '17px', fontWeight: 600 }, text: 'Losse groepen' }),
      h('div.hint', { text: 'Alles tonen en Alles verbergen werken op de groepen die je ziet, dus ook op een zoekresultaat.' })));
    inner.appendChild(h('div.row.gap-8', { style: { marginBottom: '8px' } },
      h('div.grow', null, searchInput),
      h('button.btn.btn-text', { onclick: () => setGroupsHidden(type, filtered(), false) }, 'Alles tonen'),
      h('button.btn.btn-text', { onclick: () => setGroupsHidden(type, filtered(), true) }, 'Alles verbergen')));
    inner.appendChild(groupsList);
    renderGroups();
    body.scrollTop = scroll;
  }

  const off = onModelChange((what) => {
    if (what === 'groups') {
      const focusedId = document.activeElement && document.activeElement.classList.contains('group-row') ? document.activeElement.dataset.id : null;
      renderSummary(); renderTabs(); renderCountries(); renderGroups();
      if (focusedId) { const r = groupsList.querySelector(`[data-id="${CSS.escape(focusedId)}"]`); if (r) r.focus({ preventScroll: true }); }
    }
    if (what === 'data') renderAll();
  });
  renderAll();
  return { el, destroy: off, afterMount: () => { const f = countriesEl.querySelector('.country'); if (f) f.focus(); } };
};
