/* Parabol Desktop — Login and Home. */

SCREENS.login = (params) => {
  const name = h('input.input', { placeholder: 'Bijv. Thuis, Vakantiehuis' });
  const server = h('input.input', { placeholder: 'http://server.voorbeeld.com:8080', autofocus: true });
  const user = h('input.input', { autocomplete: 'off' });
  const pass = h('input.input', { type: 'password' });
  const error = h('div.error-text');
  const button = h('button.btn', { style: { justifyContent: 'center', padding: '12px' } }, 'Verbinden');
  const adding = !!params.adding;

  async function submit() {
    error.textContent = '';
    button.disabled = true;
    button.textContent = 'Verbinden…';
    try {
      if (!name.value.trim()) throw new Error('Geef het account een naam.');
      await connectAccount(name.value.trim(), server.value, user.value.trim(), pass.value);
      openHome();
    } catch (e) {
      error.textContent = e.message || 'Verbinden mislukt.';
    }
    button.disabled = false;
    button.textContent = 'Verbinden';
  }
  button.addEventListener('click', submit);
  [name, server, user, pass].forEach((i) => i.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); }));

  const el = h('div.login', null,
    h('div.login-card', null,
      h('div.login-brand', null, h('img', { src: 'img/logo.svg', alt: '' }), h('div.name', { text: 'Parabol' })),
      h('div.muted', { text: adding ? 'Voeg een extra account toe.' : 'Verbind met je Xtream-aanbieder.' }),
      h('label', { text: 'Naam voor dit account' }), name,
      h('label', { text: 'Server-URL' }), server,
      h('label', { text: 'Gebruikersnaam' }), user,
      h('label', { text: 'Wachtwoord' }), pass,
      error,
      button,
      adding ? h('button.btn.btn-text', { onclick: () => Router.back() }, 'Annuleren') : null
    )
  );
  return { el, afterMount: () => name.focus(), onBack: () => !adding };
};

SCREENS.home = () => {
  const el = h('div.home');

  function tile(route, title, sub, iconName, accent) {
    const t = h('button.tile', {
      onclick: () => Router.go(route),
      style: { '--accent': accent, '--accent-30': accent + '4D', '--accent-18': accent + '2E' },
    },
      h('div.tile-icon', null, icon(iconName)),
      h('div', null, h('div.title', { text: title }), h('div.sub', { text: sub }))
    );
    return t;
  }

  function count(n, word) {
    if (n === 0 && M.loading) return 'Laden…';
    return `${n.toLocaleString('nl-NL')} ${word}`;
  }

  function render() {
    el.innerHTML = '';
    const profile = Store.activeProfile();
    el.appendChild(h('div.home-header', null,
      h('img', { src: 'img/logo.svg', alt: '' }),
      h('div.name', { text: 'Parabol' }),
      h('div.grow'),
      clockEl(),
      profile ? h('button.chip-account', {
        onclick: () => Router.root('accounts'),
        title: 'Account wisselen of toevoegen',
      },
        icon('person'), h('span.ellipsis', { text: profile.name })) : null,
      iconButton('record', 'Opnames', () => Router.go('recordings')),
      iconButton('settings', 'Instellingen', () => Router.go('settings'))
    ));
    el.appendChild(h('div.tiles', null,
      tile('live', 'Live tv', count(M.liveStreams.length, 'zenders'), 'liveTv', '#F5B841'),
      tile('movies', 'Films', count(M.vodStreams.length, 'films'), 'movie', '#9C8CFF'),
      tile('series', 'Series', count(M.seriesList.length, 'series'), 'library', '#4FD1C5')
    ));

    const status = h('div.home-status');
    if (M.loadError && !M.loading) {
      status.appendChild(h('div.banner-error', null,
        h('span.grow', { text: 'Laden van zenders, films en series is mislukt.' }),
        h('button.btn.btn-text', { onclick: () => loadAll() }, 'Opnieuw proberen')));
    } else if (M.loading) {
      status.appendChild(h('div.row.gap-12', null, h('div.spinner'), h('span', { text: 'Zenders, films en series laden…' })));
    } else if (Xtream.isDemo) {
      status.textContent = "Demo-account: verzonnen zenders en tv-gids. Video's: open films van de Blender Foundation (CC BY 3.0), www.blender.org.";
    } else if (M.userInfo && parseInt(M.userInfo.exp_date, 10) > 0) {
      status.textContent = 'Abonnement geldig tot ' + formatDate(parseInt(M.userInfo.exp_date, 10));
    }
    el.appendChild(status);
  }

  render();
  const off = onModelChange((what) => { if (what === 'data' || what === 'loading') render(); });
  return {
    el,
    destroy: off,
    afterMount: () => { const first = el.querySelector('.tile'); if (first) first.focus(); },
  };
};

// ---------------- Account choice (before Home when there are several accounts) ----------------

const AVATAR_COLORS = ['#F5B841', '#9C8CFF', '#4FD1C5', '#FF7A6B', '#6BB8FF', '#8BD17C', '#E58BD8', '#FFA45C'];

SCREENS.accounts = () => {
  const el = h('div.accounts');
  const profiles = Store.getProfiles();
  const lastId = Store.activeProfileId;
  const grid = h('div.account-grid');

  profiles.forEach((p, i) => {
    const color = AVATAR_COLORS[i % AVATAR_COLORS.length];
    grid.appendChild(h('button.account-tile' + (p.id === lastId ? '.last' : ''), {
      style: { '--c': color },
      title: 'Open ' + p.name,
      onclick: () => { activateProfile(p.id); Router.go('home'); },
    },
      h('div.avatar', { text: (p.name || '?').trim().charAt(0).toUpperCase() || '?' }),
      h('div.name.ellipsis', { text: p.name }),
    ));
  });
  grid.appendChild(h('button.account-tile.add', { onclick: () => Router.go('login', { adding: true }) },
    h('div.avatar', { text: '+' }),
    h('div.name', { text: 'Account toevoegen' })));

  el.appendChild(h('div.home-header', null,
    h('img', { src: 'img/logo.svg', alt: '' }),
    h('div.name', { text: 'Parabol' }),
    h('div.grow'),
    clockEl(),
    iconButton('settings', 'Instellingen', () => Router.go('settings'))));
  el.appendChild(h('div.accounts-title', { text: 'Wie kijkt er?' }));
  el.appendChild(h('div.accounts-sub', { text: 'Kies een account' }));
  el.appendChild(grid);

  return {
    el,
    afterMount: () => {
      const last = grid.querySelector('.account-tile.last') || grid.querySelector('.account-tile');
      if (last) last.focus();
    },
    onKey: (e) => {
      if (!['ArrowLeft', 'ArrowRight'].includes(e.key)) return false;
      const tiles = Array.from(grid.querySelectorAll('.account-tile'));
      const i = tiles.indexOf(document.activeElement);
      const next = tiles[i + (e.key === 'ArrowRight' ? 1 : -1)];
      if (next) next.focus();
      return true;
    },
  };
};
