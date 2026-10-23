/* Parabol Desktop — app state and data logic (mirrors the Android MainViewModel). */

const M = {
  liveCategories: [],
  vodCategories: [],
  seriesCategories: [],
  liveStreams: [],
  vodStreams: [],
  seriesList: [],
  userInfo: null,
  favorites: new Set(),
  hiddenGroups: new Set(),
  epgOffset: 0,
  loading: false,
  loadError: false,
  scheduledRecordings: [],
  completedRecordings: [],
  epgCache: new Map(), // streamId -> { listings, fetchedAt }
  lastLiveGroupId: null,
  listeners: new Set(),
  _loadToken: 0,
};

/** Screens subscribe to re-render when data changes (favorites, groups, EPG, recordings…). */
function onModelChange(fn) { M.listeners.add(fn); return () => M.listeners.delete(fn); }
function emitChange(what) { M.listeners.forEach((fn) => { try { fn(what); } catch (e) { console.error(e); } }); }

function arr(v) { return Array.isArray(v) ? v : []; }

// ---------- Accounts & loading ----------

function applyProfileSettings() {
  M.favorites = Store.getFavorites();
  M.hiddenGroups = Store.getHiddenGroups();
  M.epgOffset = 0; // the guide time correction setting was removed
  M.epgCache.clear();
  M.lastLiveGroupId = null;
}

/** Loads all lists of the active account. Newer calls win over slower older ones. */
async function loadAll() {
  const token = ++M._loadToken;
  M.loading = true;
  M.loadError = false;
  emitChange('loading');
  try {
    const [info, lc, vc, sc, ls, vs, sl] = await Promise.all([
      Xtream.login().catch(() => null),
      Xtream.getLiveCategories(),
      Xtream.getVodCategories(),
      Xtream.getSeriesCategories(),
      Xtream.getLiveStreams(),
      Xtream.getVodStreams(),
      Xtream.getSeries(),
    ]);
    if (token !== M._loadToken) return;
    M.userInfo = info && info.user_info ? info.user_info : M.userInfo;
    M.liveCategories = arr(lc);
    M.vodCategories = arr(vc);
    M.seriesCategories = arr(sc);
    M.liveStreams = uniqueBy(arr(ls), (s) => s.stream_id);
    M.vodStreams = uniqueBy(arr(vs), (s) => s.stream_id);
    M.seriesList = uniqueBy(arr(sl), (s) => s.series_id);
  } catch (e) {
    if (token !== M._loadToken) return;
    M.loadError = true;
  }
  M.loading = false;
  emitChange('data');
}

function uniqueBy(list, key) {
  const seen = new Set();
  return list.filter((x) => { const k = key(x); if (seen.has(k)) return false; seen.add(k); return true; });
}

/** Validates the credentials, stores the account and makes it active. Throws a message on failure. */
async function connectAccount(name, server, username, password) {
  server = server.trim();
  if (!server || !username || !password) throw new Error('Vul alle velden in.');
  if (!/^https?:\/\//i.test(server)) server = 'http://' + server;
  Xtream.configure(server, username, password);
  let res;
  try {
    res = await Xtream.login();
  } catch (e) {
    throw new Error('Geen verbinding met de server. Controleer de server-URL.');
  }
  const ok = res && res.user_info && (res.user_info.auth === 1 || res.user_info.auth === '1' ||
    String(res.user_info.status || '').toLowerCase() === 'active');
  if (!ok) throw new Error('Inloggen mislukt. Controleer gebruikersnaam en wachtwoord.');
  const profile = Store.upsertProfile(name || username, server, username, password);
  Store.activeProfileId = profile.id;
  M.userInfo = res.user_info;
  applyProfileSettings();
  await refreshRecordings();
  loadAll();
}

function activateProfile(id) {
  const profile = Store.getProfiles().find((p) => p.id === id);
  if (!profile) return false;
  Store.activeProfileId = id;
  Xtream.configure(profile.baseUrl, profile.username, profile.password);
  M.userInfo = null;
  M.liveCategories = []; M.vodCategories = []; M.seriesCategories = [];
  M.liveStreams = []; M.vodStreams = []; M.seriesList = [];
  applyProfileSettings();
  refreshRecordings();
  loadAll();
  return true;
}

// ---------- Favorites ----------

function isFavorite(key) { return M.favorites.has(key); }
function toggleFavorite(key) {
  if (M.favorites.has(key)) M.favorites.delete(key); else M.favorites.add(key);
  Store.setFavorites(M.favorites);
  emitChange('favorites');
}

// ---------- Groups: visibility and countries ----------

const CODE_REGEX = /^[^A-Za-z0-9]*([A-Za-z]{2,3})\s*[|:\-–—•]/;
const NOT_A_COUNTRY = new Set(['HD', 'FHD', 'UHD', 'SD', 'VIP', 'PPV', 'VOD', 'XXX', 'TOP', 'NEW', 'ALL', 'TV', 'THE', 'LIVE']);

function extractCode(name) {
  if (!name) return null;
  const m = CODE_REGEX.exec(String(name).trim());
  if (!m) return null;
  const code = m[1].toUpperCase();
  return NOT_A_COUNTRY.has(code) ? null : code;
}

function categoriesOf(type) {
  return type === 'live' ? M.liveCategories : type === 'vod' ? M.vodCategories : M.seriesCategories;
}

function isGroupHidden(type, id) { return M.hiddenGroups.has(`${type}:${id}`); }

function toggleGroupHidden(type, id) {
  const key = `${type}:${id}`;
  if (M.hiddenGroups.has(key)) M.hiddenGroups.delete(key); else M.hiddenGroups.add(key);
  Store.setHiddenGroups(M.hiddenGroups);
  emitChange('groups');
}

function setGroupsHidden(type, categories, hidden) {
  categories.forEach((c) => {
    const key = `${type}:${c.category_id}`;
    if (hidden) M.hiddenGroups.add(key); else M.hiddenGroups.delete(key);
  });
  Store.setHiddenGroups(M.hiddenGroups);
  emitChange('groups');
}

/** Visible groups of a type matching the search text. */
function visibleGroups(type, query) {
  const q = (query || '').toLowerCase();
  return uniqueBy(categoriesOf(type), (c) => c.category_id).filter((c) =>
    !isGroupHidden(type, c.category_id) && (!q || String(c.category_name).toLowerCase().includes(q)));
}

/** Groups bundled per country code (≥2 groups), largest first; groups without one go to "other" (code null). */
function countryGroups(type) {
  const byCode = new Map();
  uniqueBy(categoriesOf(type), (c) => c.category_id).forEach((c) => {
    const code = extractCode(c.category_name);
    if (!byCode.has(code)) byCode.set(code, []);
    byCode.get(code).push(c);
  });
  const countries = [];
  const other = [];
  byCode.forEach((cats, code) => {
    if (code && cats.length >= 2) countries.push({ code, categories: cats });
    else other.push(...cats);
  });
  countries.sort((a, b) => b.categories.length - a.categories.length || a.code.localeCompare(b.code));
  if (other.length) countries.push({ code: null, categories: other });
  countries.forEach((c) => {
    c.total = c.categories.length;
    c.visible = c.categories.filter((x) => !isGroupHidden(type, x.category_id)).length;
  });
  return countries;
}

function toggleCountry(type, country) {
  setGroupsHidden(type, country.categories, country.visible === country.total);
}

/** "NL" -> "Nederland" in Dutch; handles the usual IPTV prefixes. */
function countryName(code) {
  if (!code) return 'Overig (geen land)';
  try {
    if (code === 'AR') return 'Arabisch';
    if (code === 'EN') return 'Engels';
    const regionCode = code === 'UK' ? 'GB' : code;
    if (regionCode.length === 2) {
      const name = new Intl.DisplayNames(['nl'], { type: 'region' }).of(regionCode);
      if (name && name !== regionCode) return name;
    }
    const lang = new Intl.DisplayNames(['nl'], { type: 'language' }).of(code.toLowerCase());
    if (lang && lang.toLowerCase() !== code.toLowerCase()) return lang.charAt(0).toUpperCase() + lang.slice(1);
  } catch (e) { /* unknown code */ }
  return code;
}

// ---------- TV guide ----------

function startSec(item) { return (parseInt(item.start_timestamp, 10) || 0) + M.epgOffset * 3600; }
function stopSec(item) { return (parseInt(item.stop_timestamp, 10) || 0) + M.epgOffset * 3600; }

const epgQueue = [];
const epgInFlight = new Set();
let epgActive = 0;

/** Short guide (now + next ~10 programmes), cached 15 minutes, max 6 requests at a time. */
function loadShortEpg(streamId) {
  const cached = M.epgCache.get(streamId);
  if (cached && Date.now() - cached.fetchedAt < 15 * 60000) return;
  if (epgInFlight.has(streamId)) return;
  epgInFlight.add(streamId);
  epgQueue.push(streamId);
  pumpEpg();
}

function pumpEpg() {
  while (epgActive < 6 && epgQueue.length) {
    const id = epgQueue.shift();
    epgActive++;
    Xtream.getShortEpg(id, 10)
      .then((r) => { M.epgCache.set(id, { listings: arr(r && r.epg_listings), fetchedAt: Date.now() }); })
      .catch(() => { M.epgCache.set(id, { listings: [], fetchedAt: Date.now() - 12 * 60000 }); })
      .finally(() => {
        epgActive--;
        epgInFlight.delete(id);
        emitChange({ epg: id });
        pumpEpg();
      });
  }
}

function nowAndNext(streamId, now) {
  const cached = M.epgCache.get(streamId);
  if (!cached) return { loaded: false, current: null, next: [] };
  const upcoming = cached.listings.filter((x) => stopSec(x) > now).sort((a, b) => startSec(a) - startSec(b));
  const current = upcoming.find((x) => startSec(x) <= now) || null;
  return { loaded: true, current, next: current ? upcoming.filter((x) => x !== current) : upcoming, all: cached.listings };
}


// ---------- Recordings (run in the main process) ----------

async function refreshRecordings() {
  if (!window.parabolAPI) return;
  try {
    const data = await window.parabolAPI.getRecordings();
    applyRecordings(data);
  } catch (e) { /* ignore */ }
}

function applyRecordings(data) {
  const id = Store.activeProfileId;
  M.scheduledRecordings = arr(data && data.scheduled).filter((r) => r.profileId === id);
  M.completedRecordings = arr(data && data.completed).filter((r) => r.profileId === id);
  emitChange('recordings');
}

function scheduleRecording(channel, title, start, stop) {
  const profile = Store.activeProfile();
  if (!profile || !window.parabolAPI) return;
  window.parabolAPI.scheduleRecording({
    id: 'rec_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8),
    profileId: profile.id,
    streamId: channel.stream_id,
    channelName: channel.name,
    programTitle: title,
    startEpochSeconds: start,
    endEpochSeconds: stop,
    url: Xtream.rawTsUrl(channel.stream_id),
  }).then(refreshRecordings);
}

function cancelRecording(id) {
  if (window.parabolAPI) window.parabolAPI.cancelRecording(id).then(refreshRecordings);
}

function deleteCompletedRecording(id) {
  if (window.parabolAPI) window.parabolAPI.deleteCompletedRecording(id).then(refreshRecordings);
}
