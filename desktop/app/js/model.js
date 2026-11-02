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
  snapshot: null, // content of the active account as kept on disk
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

/**
 * Shows the content kept on disk right away, then fetches only what is missing: visible
 * groups that were never loaded (new groups, or a group switched on in Groepen beheren).
 * force ("Vernieuwen") fetches all visible groups again. Newer calls win over older ones.
 */
async function loadAll(force = false) {
  if (force && Xtream.isM3u) { M3uSource.reset(Xtream.base); M.epgCache.clear(); } // read the playlist again
  const token = ++M._loadToken;
  const profile = Store.activeProfile();
  const api = window.parabolAPI;
  if (!profile) return;
  if (!M.snapshot && api && api.loadContent) {
    const stored = await api.loadContent(profile.id).catch(() => null);
    if (token !== M._loadToken) return;
    if (stored) { M.snapshot = stored; applySnapshot(stored); emitChange('data'); }
  }
  const old = M.snapshot || {};
  const isEmpty = (s) => !Object.keys(s.live || {}).length && !Object.keys(s.vod || {}).length && !Object.keys(s.series || {}).length;
  M.loading = isEmpty(old); // only "loading" when there is nothing to show yet
  M.loadError = false;
  emitChange('loading');

  const safe = (promise) => promise.then((x) => (Array.isArray(x) ? x : null), () => null);
  // Nothing stored yet for a type: everything will be fetched anyway, so start that now.
  const empty = (g) => !Object.keys(g || {}).length;
  const pre = {
    live: empty(old.live) ? safe(Xtream.getLiveStreams()) : null,
    vod: empty(old.vod) ? safe(Xtream.getVodStreams()) : null,
    series: empty(old.series) ? safe(Xtream.getSeries()) : null,
  };
  const [info, lc, vc, sc] = await Promise.all([
    Xtream.login().catch(() => null),
    safe(Xtream.getLiveCategories()),
    safe(Xtream.getVodCategories()),
    safe(Xtream.getSeriesCategories()),
  ]);
  if (token !== M._loadToken) return;
  if (info && info.user_info) M.userInfo = info.user_info;
  const liveCats = lc || old.liveCategories || [];
  const vodCats = vc || old.vodCategories || [];
  const seriesCats = sc || old.seriesCategories || [];
  const hidden = new Set(M.hiddenGroups);
  const one = (type, cats, cached, get) => ContentLoader.refresh({
    categories: cats,
    cached,
    isHidden: (id) => hidden.has(`${type}:${id}`),
    force,
    categoryOf: (x) => x.category_id,
    fetchAll: () => pre[type] || safe(get()),
    fetchGroup: (id) => safe(get(id)),
  });
  const [l, v, s] = await Promise.all([
    one('live', liveCats, old.live, (id) => Xtream.getLiveStreams(id)),
    one('vod', vodCats, old.vod, (id) => Xtream.getVodStreams(id)),
    one('series', seriesCats, old.series, (id) => Xtream.getSeries(id)),
  ]);
  if (token !== M._loadToken) return;
  const next = { liveCategories: liveCats, vodCategories: vodCats, seriesCategories: seriesCats, live: l.groups, vod: v.groups, series: s.groups };
  const groupsChanged = JSON.stringify([liveCats, vodCats, seriesCats]) !==
    JSON.stringify([old.liveCategories || [], old.vodCategories || [], old.seriesCategories || []]);
  M.snapshot = next;
  applySnapshot(next);
  M.loadError = ((!lc && !vc && !sc) || (l.failed && v.failed && s.failed)) && isEmpty(next);
  M.loading = false;
  emitChange('data');
  // Only written when something changed; never waited for, so the screen is ready first.
  if (api && api.saveContent && (groupsChanged || l.changed || v.changed || s.changed)) {
    api.saveContent(profile.id, next).catch(() => {});
  }
}

function applySnapshot(s) {
  if ((s.liveCategories || []).length) M.liveCategories = s.liveCategories;
  if ((s.vodCategories || []).length) M.vodCategories = s.vodCategories;
  if ((s.seriesCategories || []).length) M.seriesCategories = s.seriesCategories;
  const flat = (groups) => Object.values(groups || {}).flat();
  M.liveStreams = uniqueBy(flat(s.live), (x) => x.stream_id);
  M.vodStreams = uniqueBy(flat(s.vod), (x) => x.stream_id);
  M.seriesList = uniqueBy(flat(s.series), (x) => x.series_id);
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
  M.snapshot = null; M._loadToken++;
  applyProfileSettings();
  await refreshRecordings();
  loadAll();
}

/** Adds or re-opens an M3U account: the playlist must be readable and contain something. */
async function connectM3u(name, playlistUrl, guideUrl) {
  const withScheme = (u) => (u && !/^https?:\/\//i.test(u.trim()) ? 'http://' + u.trim() : (u || '').trim());
  const url = withScheme(playlistUrl);
  const guide = withScheme(guideUrl);
  if (!url) throw new Error('Vul de link van de afspeellijst in.');
  M3uSource.reset(url);
  Xtream.configure(url, '', '', 'm3u', guide);
  let res;
  try {
    res = await Xtream.login();
  } catch (e) {
    throw new Error('De afspeellijst kon niet worden gelezen. Controleer de link.');
  }
  if (!res || !res.user_info || res.user_info.auth !== 1) throw new Error('De afspeellijst bevat geen zenders, films of series.');
  const profile = Store.upsertProfile(name, url, '', '', 'm3u', guide);
  Store.activeProfileId = profile.id;
  M.userInfo = res.user_info;
  M.snapshot = null; M._loadToken++;
  applyProfileSettings();
  await refreshRecordings();
  loadAll();
}

function activateProfile(id) {
  const profile = Store.getProfiles().find((p) => p.id === id);
  if (!profile) return false;
  Store.activeProfileId = id;
  Xtream.configure(profile.baseUrl, profile.username, profile.password, profile.kind, profile.epgUrl);
  M.userInfo = null;
  M.snapshot = null; M._loadToken++;
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
// Only real two-letter country or language codes count ("NL", "UK", "EN"); "TV" and "SD"
// are country codes too, but in group names they nearly always mean television / quality.
const REGION_NAMES = new Intl.DisplayNames(['en'], { type: 'region' });
const LANGUAGE_NAMES = new Intl.DisplayNames(['en'], { type: 'language' });
function isCountryOrLanguage(code) {
  if (code === 'UK') return true;
  try { if (REGION_NAMES.of(code) !== code) return true; } catch (e) { /* not a region */ }
  try { if (LANGUAGE_NAMES.of(code.toLowerCase()) !== code.toLowerCase()) return true; } catch (e) { /* not a language */ }
  return false;
}

function extractCode(name) {
  if (!name) return null;
  const m = CODE_REGEX.exec(String(name).trim());
  if (!m) return null;
  const code = m[1].toUpperCase();
  if (code.length !== 2 || code === 'TV' || code === 'SD') return null;
  return isCountryOrLanguage(code) ? code : null;
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
  if (!M.hiddenGroups.has(key)) loadAll(); // now visible: fetched if it was never loaded
}

function setGroupsHidden(type, categories, hidden) {
  categories.forEach((c) => {
    const key = `${type}:${c.category_id}`;
    if (hidden) M.hiddenGroups.add(key); else M.hiddenGroups.delete(key);
  });
  Store.setHiddenGroups(M.hiddenGroups);
  emitChange('groups');
  if (!hidden) loadAll(); // fetch groups that are now visible but were never loaded
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

/** "NL" -> "Nederland" in Dutch; handles the usual group name prefixes. */
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

/**
 * Provider guides often contain duplicates and overlapping programmes, which made blocks
 * and titles in the guide fall on top of each other. Keeps one programme per time slot
 * (same rules as Android): sorted, no empty or duplicate entries, and an overlapping
 * programme starts where the previous one ends, or is left out when it lies inside it.
 */
function withoutOverlaps(listings) {
  const raw = (x, k) => parseInt(x[k], 10) || 0;
  const sorted = arr(listings).slice().sort((a, b) =>
    raw(a, 'start_timestamp') - raw(b, 'start_timestamp') || raw(b, 'stop_timestamp') - raw(a, 'stop_timestamp'));
  const out = [];
  let lastEnd = -Infinity;
  sorted.forEach((p) => {
    const start = raw(p, 'start_timestamp');
    const stop = raw(p, 'stop_timestamp');
    if (stop <= start || stop <= lastEnd) return;
    out.push(start < lastEnd ? { ...p, start_timestamp: String(lastEnd) } : p);
    lastEnd = stop;
  });
  return out;
}

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
      .then((r) => { M.epgCache.set(id, { listings: withoutOverlaps(r && r.epg_listings), fetchedAt: Date.now() }); })
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
