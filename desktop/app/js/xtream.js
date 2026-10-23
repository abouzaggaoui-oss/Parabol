/* Parabol Desktop — Xtream Codes API client. */

const Xtream = {
  base: '',
  username: '',
  password: '',

  configure(base, username, password) {
    this.base = base.replace(/\/+$/, '');
    this.username = username;
    this.password = password;
  },

  async call(action, params = {}) {
    const url = new URL(this.base + '/player_api.php');
    url.searchParams.set('username', this.username);
    url.searchParams.set('password', this.password);
    if (action) url.searchParams.set('action', action);
    Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v));

    const res = await fetch(url.toString());
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return res.json();
  },

  login() {
    return this.call('');
  },
  getLiveCategories() {
    return this.call('get_live_categories');
  },
  getVodCategories() {
    return this.call('get_vod_categories');
  },
  getSeriesCategories() {
    return this.call('get_series_categories');
  },
  getLiveStreams() {
    return this.call('get_live_streams');
  },
  getVodStreams() {
    return this.call('get_vod_streams');
  },
  getSeries() {
    return this.call('get_series');
  },
  getSeriesInfo(seriesId) {
    return this.call('get_series_info', { series_id: seriesId });
  },
  getShortEpg(streamId, limit = 10) {
    return this.call('get_short_epg', { stream_id: streamId, limit });
  },
  getFullEpg(streamId) {
    return this.call('get_simple_data_table', { stream_id: streamId });
  },

  liveUrl(streamId, ext = 'm3u8') {
    return `${this.base}/live/${this.username}/${this.password}/${streamId}.${ext}`;
  },
  /** Raw MPEG-TS URL, used specifically for recording (simple byte-copy download). */
  rawTsUrl(streamId) {
    return `${this.base}/live/${this.username}/${this.password}/${streamId}.ts`;
  },
  movieUrl(streamId, ext) {
    return `${this.base}/movie/${this.username}/${this.password}/${streamId}.${ext || 'mp4'}`;
  },
  episodeUrl(episodeId, ext) {
    return `${this.base}/series/${this.username}/${this.password}/${episodeId}.${ext || 'mp4'}`;
  },
  /**
   * Server-side catch-up ("watch back") URL — only for channels with tv_archive.
   * The timeshift start must be in the SERVER's time, so the listing's own "start" text
   * (server-local) is used when present, shifted by the user's guide correction.
   */
  catchupUrl(streamId, serverStart, startEpochSeconds, durationMinutes, offsetHours = 0) {
    const pad = (n) => String(n).padStart(2, '0');
    const fmt = (d) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}:${pad(d.getUTCHours())}-${pad(d.getUTCMinutes())}`;
    let startStr;
    const m = serverStart ? /(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/.exec(serverStart) : null;
    if (m) {
      const asUtc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]) + offsetHours * 3600000;
      startStr = fmt(new Date(asUtc));
    } else {
      startStr = fmt(new Date(startEpochSeconds * 1000));
    }
    return `${this.base}/timeshift/${this.username}/${this.password}/${durationMinutes}/${startStr}/${streamId}.ts`;
  },
};
