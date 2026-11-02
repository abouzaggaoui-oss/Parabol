/* Parabol Desktop — Xtream Codes API client. */

const Xtream = {
  base: '',
  username: '',
  password: '',

  kind: 'xtream', // or 'm3u': base is then the playlist link
  epgUrl: '',

  configure(base, username, password, kind = 'xtream', epgUrl = '') {
    this.kind = kind || 'xtream';
    this.base = this.kind === 'm3u' ? base.trim() : base.replace(/\/+$/, '');
    this.username = username || '';
    this.password = password || '';
    this.epgUrl = epgUrl || '';
  },

  get isM3u() {
    return this.kind === 'm3u';
  },

  /** M3U: the item's own link, as stored with the lists. */
  directLink(list, key, id) {
    const item = (list || []).find((x) => String(x[key]) === String(id));
    return (item && item.direct_source) || '';
  },

  get isDemo() {
    return DemoServer.isDemo(this.base);
  },

  async call(action, params = {}) {
    if (this.isDemo) return DemoServer.respond(action || '', params, this.username, this.password);
    if (this.isM3u) return M3uSource.respond(this.base, this.epgUrl, action || '', params);
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
  /** categoryId: only that group; leave out for everything. */
  getLiveStreams(categoryId) {
    return this.call('get_live_streams', categoryId != null ? { category_id: categoryId } : {});
  },
  /** categoryId: only that group; leave out for everything. */
  getVodStreams(categoryId) {
    return this.call('get_vod_streams', categoryId != null ? { category_id: categoryId } : {});
  },
  /** categoryId: only that group; leave out for everything. */
  getSeries(categoryId) {
    return this.call('get_series', categoryId != null ? { category_id: categoryId } : {});
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
    if (this.isDemo) return DemoServer.liveUrl(streamId);
    if (this.isM3u) return this.directLink(M.liveStreams, 'stream_id', streamId);
    return `${this.base}/live/${this.username}/${this.password}/${streamId}.${ext}`;
  },
  movieUrl(streamId, ext) {
    if (this.isDemo) return DemoServer.movieUrl(streamId);
    if (this.isM3u) return this.directLink(M.vodStreams, 'stream_id', streamId);
    return `${this.base}/movie/${this.username}/${this.password}/${streamId}.${ext || 'mp4'}`;
  },
  episodeUrl(episodeId, ext, direct) {
    if (this.isDemo) return DemoServer.episodeUrl(episodeId);
    if (this.isM3u) return direct || '';
    return `${this.base}/series/${this.username}/${this.password}/${episodeId}.${ext || 'mp4'}`;
  },
  /**
   * Server-side catch-up ("watch back") URL — only for channels with tv_archive.
   * The timeshift start must be in the SERVER's time, so the listing's own "start" text
   * (server-local) is used when present, shifted by the user's guide correction.
   */
  catchupUrl(streamId, serverStart, startEpochSeconds, durationMinutes, offsetHours = 0) {
    if (this.isDemo || this.isM3u) return this.liveUrl(streamId);
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
