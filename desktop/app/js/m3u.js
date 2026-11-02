/* Parabol Desktop — M3U playlist accounts, same rules as the Android app.
 * The playlist becomes groups, channels, movies and series:
 * a link with /movie/ or a video file extension is a movie, a link with /series/ (or a
 * movie whose name has S01E02) is an episode, everything else is a live channel.
 * The tv guide (XMLTV, also .gz) is read on the first guide request; only channels in the
 * playlist and programmes from 12 hours ago to 2 days ahead are kept. */

const M3uSource = (() => {
  const ATTR = /([A-Za-z0-9_-]+)="([^"]*)"/g;
  const EPISODE = /\bS(\d{1,3})\s*[ ._-]?\s*E(\d{1,4})\b/i;
  const VIDEO = new Set(['mp4', 'mkv', 'avi', 'mov', 'm4v', 'wmv', 'flv', 'webm', 'mpg', 'mpeg']);

  /** Same as Java's String.hashCode, so ids match the Android app. */
  function javaHash(s) {
    let h = 0;
    for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
    return h;
  }
  function stableId(text, used) {
    let id = Math.max(1, Math.abs(javaHash(text) % 1000000000));
    while (used.has(id)) id++;
    used.add(id);
    return id;
  }
  const groupId = (prefix, group) => prefix + Math.abs(javaHash(group.toLowerCase())).toString(36);
  const ext = (url) => { const f = url.split('?')[0].split('/').pop(); return f.includes('.') ? f.split('.').pop().toLowerCase() : ''; };
  const attrs = (line) => { const a = {}; let m; ATTR.lastIndex = 0; while ((m = ATTR.exec(line))) a[m[1].toLowerCase()] = m[2]; return a; };
  function displayName(inf) {
    let q = false; let last = -1;
    for (let i = 0; i < inf.length; i++) { if (inf[i] === '"') q = !q; if (inf[i] === ',' && !q) last = i; }
    return last >= 0 ? inf.slice(last + 1).trim() : '';
  }

  function parse(text) {
    let guideUrl = null;
    const entries = [];
    let info = null; let groupLine = null;
    text.split(/\r?\n/).forEach((raw) => {
      const line = raw.trim().replace(/^﻿/, '');
      if (!line) return;
      if (/^#EXTM3U/i.test(line)) { const a = attrs(line); const g = a['url-tvg'] || a['x-tvg-url']; guideUrl = g ? g.split(',')[0].trim() || null : null; return; }
      if (/^#EXTINF/i.test(line)) { info = line; groupLine = null; return; }
      if (/^#EXTGRP:/i.test(line)) { groupLine = line.slice(line.indexOf(':') + 1).trim(); return; }
      if (line.startsWith('#')) return;
      const inf = info || '#EXTINF:-1,';
      const a = attrs(inf);
      entries.push({
        name: displayName(inf) || a['tvg-name'] || line.split('/').pop(),
        group: (a['group-title'] != null ? a['group-title'] : groupLine || '').trim(),
        logo: a['tvg-logo'] || null,
        tvgId: a['tvg-id'] || null,
        tvgName: a['tvg-name'] || null,
        url: line,
      });
      info = null; groupLine = null;
    });

    const used = new Set();
    const cats = { l: new Map(), v: new Map(), s: new Map() };
    const cat = (p, group) => { const name = group || 'Overig'; const id = groupId(p, name); if (!cats[p].has(id)) cats[p].set(id, { category_id: id, category_name: name }); return id; };
    const live = []; const vod = []; const showMap = new Map();
    entries.forEach((e) => {
      const path = e.url.split('?')[0].toLowerCase();
      const code = EPISODE.exec(e.name);
      const isSeries = path.includes('/series/') || (code && (path.includes('/movie/') || VIDEO.has(ext(e.url))));
      const isMovie = !isSeries && (path.includes('/movie/') || VIDEO.has(ext(e.url)));
      if (isSeries) {
        const show = ((code ? e.name.slice(0, code.index) : e.name).trim().replace(/[-|:. ]+$/, '')) || e.name;
        const title = code ? e.name.slice(code.index + code[0].length).trim().replace(/^[-|:. ]+/, '') : '';
        const key = e.group + '\u0000' + show;
        if (!showMap.has(key)) showMap.set(key, { group: e.group, show, eps: [] });
        showMap.get(key).eps.push({ e, title, season: code ? parseInt(code[1], 10) : 1, number: code ? parseInt(code[2], 10) : 0 });
      } else if (isMovie) {
        vod.push({ name: e.name, stream_id: stableId(e.url, used), stream_icon: e.logo, category_id: cat('v', e.group), container_extension: ext(e.url) || 'mp4', direct_source: e.url });
      } else {
        live.push({ num: live.length + 1, name: e.name, stream_id: stableId(e.url, used), stream_icon: e.logo, epg_channel_id: e.tvgId || e.tvgName || e.name, category_id: cat('l', e.group), tv_archive: 0, direct_source: e.url });
      }
    });
    const series = []; const episodes = {};
    showMap.forEach(({ group, show, eps }) => {
      const id = stableId(`series:${group}:${show}`, used);
      series.push({ name: show, series_id: id, cover: (eps.find((x) => x.e.logo) || {}).e?.logo || null, category_id: cat('s', group) });
      const bySeason = {};
      eps.sort((a, b) => a.season - b.season || a.number - b.number).forEach((ep) => {
        const list = bySeason[ep.season] = bySeason[ep.season] || [];
        list.push({ id: String(stableId(ep.e.url, used)), title: ep.title || ep.e.name, container_extension: ext(ep.e.url) || 'mp4', episode_num: ep.number > 0 ? ep.number : list.length + 1, season: ep.season, direct_source: ep.e.url });
      });
      episodes[id] = bySeason;
    });
    return { guideUrl, liveCategories: [...cats.l.values()], vodCategories: [...cats.v.values()], seriesCategories: [...cats.s.values()], live, vod, series, episodes };
  }

  /** "20261102183000 +0100" -> epoch seconds. */
  function xmltvTime(v) {
    const m = /(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*([+-]\d{4})?/.exec(v || '');
    if (!m) return 0;
    let t = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]) / 1000;
    if (m[7]) { const s = m[7][0] === '-' ? -1 : 1; t -= s * (parseInt(m[7].slice(1, 3), 10) * 3600 + parseInt(m[7].slice(3, 5), 10) * 60); }
    return t;
  }
  const unescapeXml = (s) => s.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#(\d+);/g, (_, n) => String.fromCharCode(+n)).replace(/&amp;/g, '&').trim();

  /** Reads an XMLTV guide (text chunks) and keeps only [wanted] channel keys (lower case). */
  async function parseGuide(chunks, wanted) {
    const now = Date.now() / 1000; const from = now - 12 * 3600; const until = now + 48 * 3600;
    const aliases = new Map(); const result = new Map();
    let buf = '';
    const handle = (el) => {
      if (el.startsWith('<channel')) {
        const id = (/\bid="([^"]*)"/.exec(el) || [])[1];
        if (!id) return;
        const keys = new Set([id.toLowerCase()]);
        const re = /<display-name[^>]*>([\s\S]*?)<\/display-name>/g; let m;
        while ((m = re.exec(el))) keys.add(unescapeXml(m[1]).toLowerCase());
        aliases.set(id, keys);
      } else {
        const ch = (/\bchannel="([^"]*)"/.exec(el) || [])[1];
        const start = xmltvTime((/\bstart="([^"]*)"/.exec(el) || [])[1]);
        const stop = xmltvTime((/\bstop="([^"]*)"/.exec(el) || [])[1]);
        if (!ch || stop <= from || start >= until || stop <= start) return;
        const keys = aliases.get(ch) || new Set([ch.toLowerCase()]);
        const key = [...keys].find((k) => wanted.has(k));
        if (!key) return;
        const title = unescapeXml((/<title[^>]*>([\s\S]*?)<\/title>/.exec(el) || [])[1] || '');
        const desc = unescapeXml((/<desc[^>]*>([\s\S]*?)<\/desc>/.exec(el) || [])[1] || '');
        if (!result.has(key)) result.set(key, []);
        result.get(key).push({ start, stop, title, desc });
      }
    };
    for await (const chunk of chunks) {
      buf += chunk;
      const re = /<(channel|programme)\b[\s\S]*?<\/\1>/g; let m; let last = 0;
      while ((m = re.exec(buf))) { handle(m[0]); last = re.lastIndex; }
      buf = buf.slice(last);
      if (buf.length > 1e6 && buf.indexOf('<channel') < 0 && buf.indexOf('<programme') < 0) buf = '';
    }
    const out = new Map();
    aliases.forEach((keys) => {
      const list = [...keys].map((k) => result.get(k)).find(Boolean);
      if (list) { list.sort((a, b) => a.start - b.start); keys.forEach((k) => out.set(k, list)); }
    });
    result.forEach((v, k) => { if (!out.has(k)) out.set(k, v.sort((a, b) => a.start - b.start)); });
    return out;
  }

  async function* textChunks(url) {
    const res = await fetch(url);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const reader = res.body.getReader();
    const first = await reader.read();
    const head = first.value || new Uint8Array();
    const gz = head[0] === 0x1f && head[1] === 0x8b;
    const raw = new ReadableStream({
      start(c) { if (head.length) c.enqueue(head); },
      async pull(c) { const r = await reader.read(); if (r.done) c.close(); else c.enqueue(r.value); },
    });
    const stream = (gz ? raw.pipeThrough(new DecompressionStream('gzip')) : raw).pipeThrough(new TextDecoderStream());
    const rd = stream.getReader();
    for (;;) { const r = await rd.read(); if (r.done) return; yield r.value; }
  }

  const sources = new Map(); // playlist url -> { playlist: Promise, guide: Promise|null }
  function source(url) {
    if (!sources.has(url)) {
      sources.set(url, {
        playlist: fetch(url).then((r) => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); }).then(parse),
        guide: null,
      });
      sources.get(url).playlist.catch(() => sources.delete(url)); // a failed read is tried again
    }
    return sources.get(url);
  }
  function guide(url, guideUrl) {
    const s = source(url);
    if (!s.guide) {
      s.guide = s.playlist.then(async (p) => {
        const g = guideUrl || p.guideUrl;
        if (!g) return new Map();
        const wanted = new Set(p.live.flatMap((c) => [String(c.epg_channel_id || '').toLowerCase(), c.name.toLowerCase()]));
        return parseGuide(textChunks(g), wanted);
      }).catch(() => { s.guide = null; return new Map(); });
    }
    return s.guide;
  }
  const b64 = (s) => btoa(unescape(encodeURIComponent(s || '')));

  async function listings(url, guideUrl, streamId) {
    const p = await source(url).playlist;
    const ch = p.live.find((c) => String(c.stream_id) === String(streamId));
    if (!ch) return [];
    const g = await guide(url, guideUrl);
    const list = [ch.epg_channel_id, ch.name].map((k) => g.get(String(k || '').toLowerCase())).find(Boolean) || [];
    return list.map((x, i) => ({ id: `${streamId}-${i}`, title: b64(x.title), description: b64(x.desc), start_timestamp: String(x.start), stop_timestamp: String(x.stop) }));
  }

  async function respond(url, guideUrl, action, params = {}) {
    const p = await source(url).playlist;
    const only = (list) => (params.category_id != null ? list.filter((x) => x.category_id === String(params.category_id)) : list);
    switch (action) {
      case '': { const ok = p.live.length + p.vod.length + p.series.length > 0; return { user_info: { status: ok ? 'Active' : 'Disabled', auth: ok ? 1 : 0 }, server_info: {} }; }
      case 'get_live_categories': return p.liveCategories;
      case 'get_vod_categories': return p.vodCategories;
      case 'get_series_categories': return p.seriesCategories;
      case 'get_live_streams': return only(p.live);
      case 'get_vod_streams': return only(p.vod);
      case 'get_series': return only(p.series);
      case 'get_series_info': {
        const s = p.series.find((x) => String(x.series_id) === String(params.series_id)) || {};
        return { info: { name: s.name, cover: s.cover }, episodes: p.episodes[params.series_id] || {} };
      }
      case 'get_short_epg': {
        const now = Date.now() / 1000;
        return { epg_listings: (await listings(url, guideUrl, params.stream_id)).filter((x) => +x.stop_timestamp > now).slice(0, +params.limit || 10) };
      }
      case 'get_simple_data_table': return { epg_listings: await listings(url, guideUrl, params.stream_id) };
      default: return {};
    }
  }

  return { parse, parseGuide, xmltvTime, respond, reset: (url) => sources.delete(url) };
})();

if (typeof module !== 'undefined') module.exports = M3uSource;
