/* Parabol Desktop — persistent local storage: accounts (profiles), per-profile
 * favorites/hidden groups. Recordings live in the main process (see main.js),
 * not here, since they must keep running even while the window is hidden. */

const Store = {
  _migrateLegacyIfNeeded() {
    if (localStorage.getItem('parabol_profiles') !== null) return;
    const legacyBase = localStorage.getItem('parabol_baseUrl') || '';
    const legacyUser = localStorage.getItem('parabol_username') || '';
    const legacyPass = localStorage.getItem('parabol_password') || '';

    if (!legacyBase || !legacyUser || !legacyPass) {
      localStorage.setItem('parabol_profiles', '[]');
      return;
    }

    const id = 'profile_' + Date.now();
    const profile = {
      id,
      name: legacyUser,
      baseUrl: legacyBase.replace(/\/+$/, ''),
      username: legacyUser,
      password: legacyPass,
    };
    localStorage.setItem('parabol_profiles', JSON.stringify([profile]));
    localStorage.setItem('parabol_activeProfileId', id);

    const favs = localStorage.getItem('parabol_favorites');
    const hidden = localStorage.getItem('parabol_hiddenGroups');
    if (favs) localStorage.setItem('parabol_favorites_' + id, favs);
    if (hidden) localStorage.setItem('parabol_hiddenGroups_' + id, hidden);

    localStorage.removeItem('parabol_baseUrl');
    localStorage.removeItem('parabol_username');
    localStorage.removeItem('parabol_password');
    localStorage.removeItem('parabol_favorites');
    localStorage.removeItem('parabol_hiddenGroups');
  },

  // ---- Profiles (accounts) ----

  getProfiles() {
    this._migrateLegacyIfNeeded();
    try {
      return JSON.parse(localStorage.getItem('parabol_profiles') || '[]');
    } catch (e) {
      return [];
    }
  },

  _setProfiles(list) {
    localStorage.setItem('parabol_profiles', JSON.stringify(list));
  },

  addProfile(name, baseUrl, username, password) {
    const id = 'profile_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8);
    const profile = {
      id,
      name: name || username,
      baseUrl: baseUrl.replace(/\/+$/, ''),
      username,
      password,
    };
    this._setProfiles(this.getProfiles().concat([profile]));
    if (!this.activeProfileId) this.activeProfileId = id;
    return profile;
  },

  removeProfile(id) {
    this._setProfiles(this.getProfiles().filter((p) => p.id !== id));
    localStorage.removeItem('parabol_favorites_' + id);
    localStorage.removeItem('parabol_hiddenGroups_' + id);
    if (this.activeProfileId === id) {
      const remaining = this.getProfiles();
      this.activeProfileId = remaining.length ? remaining[0].id : '';
    }
  },

  get activeProfileId() {
    return localStorage.getItem('parabol_activeProfileId') || '';
  },
  set activeProfileId(v) {
    localStorage.setItem('parabol_activeProfileId', v);
  },

  activeProfile() {
    const id = this.activeProfileId;
    return this.getProfiles().find((p) => p.id === id) || null;
  },

  get hasCredentials() {
    return !!this.activeProfile();
  },

  // ---- Favorites (scoped to the active profile) ----

  getFavorites() {
    const id = this.activeProfileId;
    if (!id) return new Set();
    try {
      return new Set(JSON.parse(localStorage.getItem('parabol_favorites_' + id) || '[]'));
    } catch (e) {
      return new Set();
    }
  },
  setFavorites(set) {
    const id = this.activeProfileId;
    if (!id) return;
    localStorage.setItem('parabol_favorites_' + id, JSON.stringify([...set]));
  },

  // ---- Hidden groups (scoped to the active profile) ----

  getHiddenGroups() {
    const id = this.activeProfileId;
    if (!id) return new Set();
    try {
      return new Set(JSON.parse(localStorage.getItem('parabol_hiddenGroups_' + id) || '[]'));
    } catch (e) {
      return new Set();
    }
  },
  setHiddenGroups(set) {
    const id = this.activeProfileId;
    if (!id) return;
    localStorage.setItem('parabol_hiddenGroups_' + id, JSON.stringify([...set]));
  },

  /** Updates an existing profile with the same server + username instead of adding a duplicate. */
  upsertProfile(name, baseUrl, username, password) {
    const base = baseUrl.replace(/\/+$/, '');
    const list = this.getProfiles();
    const existing = list.find((p) => p.baseUrl === base && p.username === username);
    if (existing) {
      existing.password = password;
      if (name) existing.name = name;
      this._setProfiles(list);
      return existing;
    }
    return this.addProfile(name, base, username, password);
  },

  // ---- Guide time correction in hours (per profile) ----

  getEpgOffset() {
    const id = this.activeProfileId;
    return parseInt(localStorage.getItem('parabol_epgOffset_' + id) || '0', 10) || 0;
  },
  setEpgOffset(hours) {
    localStorage.setItem('parabol_epgOffset_' + this.activeProfileId, String(hours));
  },

  // ---- Resume positions for movies / episodes (key never contains the password) ----

  getPosition(key) {
    return parseFloat(localStorage.getItem('parabol_pos_' + key) || '0') || 0;
  },
  setPosition(key, seconds) {
    if (seconds > 0) localStorage.setItem('parabol_pos_' + key, String(Math.floor(seconds)));
    else localStorage.removeItem('parabol_pos_' + key);
  },

  // ---- OpenSubtitles API key (global app setting, not per-profile) ----
  getOpenSubtitlesApiKey() {
    return localStorage.getItem('parabol_openSubtitlesApiKey') || '';
  },
  setOpenSubtitlesApiKey(key) {
    localStorage.setItem('parabol_openSubtitlesApiKey', key);
  },
};
