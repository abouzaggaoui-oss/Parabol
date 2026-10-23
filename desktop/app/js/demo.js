/* Parabol Desktop — built-in demo account (server "demo", user "demo", password "demo").
 * Same content as the Android app: fictional channels and guide; the videos are the
 * Blender Foundation open movies (CC BY 3.0) from Google's public sample bucket. */

const DemoServer = (() => {
  const SAMPLES = 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/';
  const films = [
    { id: 1001, title: 'Big Buck Bunny', file: 'BigBuckBunny.mp4' },
    { id: 1002, title: 'Elephants Dream', file: 'ElephantsDream.mp4' },
    { id: 1003, title: 'Sintel', file: 'Sintel.mp4' },
    { id: 1004, title: 'Tears of Steel', file: 'TearsOfSteel.mp4' },
  ];
  const channels = [
    { id: 1, name: 'Parabol Demo 1', film: films[0], shows: ['Ochtendfilm', 'Korte animaties', 'Achter de schermen'] },
    { id: 2, name: 'Parabol Demo 2', film: films[1], shows: ['Middagfilm', 'Kunst en techniek', 'Studiogesprek'] },
    { id: 3, name: 'Parabol Demo 3', film: films[2], shows: ['Avondfilm', 'Maak je eigen animatie', 'Filmpraat'] },
    { id: 4, name: 'Parabol Demo 4', film: films[3], shows: ['Nachtfilm', 'Effecten uitgelegd', 'Herhaling'] },
  ];
  const b64 = (s) => btoa(unescape(encodeURIComponent(s)));

  function isDemo(base) {
    const host = String(base || '').trim().toLowerCase()
      .replace(/^https?:\/\//, '').split('/')[0].split(':')[0];
    return host === 'demo';
  }

  function epg(streamId, limit) {
    const ch = channels.find((c) => c.id === Number(streamId));
    if (!ch) return { epg_listings: [] };
    const slot = 1800;
    const now = Math.floor(Date.now() / 1000);
    const first = Math.floor(now / slot) * slot - 6 * slot;
    let list = Array.from({ length: 24 }, (_, i) => {
      const start = first + i * slot;
      const isFilm = i % 3 === 0;
      return {
        id: `${streamId}-${i}`,
        title: b64(isFilm ? ch.film.title : ch.shows[i % ch.shows.length]),
        description: b64(isFilm
          ? 'Open film van de Blender Foundation (CC BY). Voorbeeldprogramma in de demo van Parabol.'
          : 'Verzonnen programma in de demo van Parabol.'),
        start_timestamp: String(start),
        stop_timestamp: String(start + slot),
      };
    });
    if (limit) list = list.filter((l) => Number(l.stop_timestamp) > now).slice(0, Number(limit));
    return { epg_listings: list };
  }

  function respond(action, params, username, password) {
    switch (action) {
      case '': {
        const ok = String(username).trim() === 'demo' && String(password).trim() === 'demo';
        return { user_info: { username, status: ok ? 'Active' : 'Disabled', auth: ok ? 1 : 0 }, server_info: { url: 'demo' } };
      }
      case 'get_live_categories': return [{ category_id: '1', category_name: 'Demo' }];
      case 'get_vod_categories': return [{ category_id: '11', category_name: 'Open films (Blender Foundation, CC BY)' }];
      case 'get_series_categories': return [{ category_id: '21', category_name: 'Demo-series' }];
      case 'get_live_streams':
        return channels.map((c, i) => ({ num: i + 1, name: c.name, stream_id: c.id, category_id: '1', tv_archive: 0 }));
      case 'get_vod_streams':
        return films.map((f) => ({ name: f.title, stream_id: f.id, category_id: '11', container_extension: 'mp4' }));
      case 'get_series':
        return [{ name: 'Open films', series_id: 2001, category_id: '21', plot: 'De open films van de Blender Foundation als demo-serie (CC BY).' }];
      case 'get_series_info':
        return {
          info: { name: 'Open films', plot: 'Demo-serie met de open films van de Blender Foundation (CC BY).' },
          episodes: { 1: films.map((f, i) => ({ id: String(f.id + 2000), title: f.title, container_extension: 'mp4', episode_num: i + 1, season: 1 })) },
        };
      case 'get_short_epg': return epg(params.stream_id, params.limit || 10);
      case 'get_simple_data_table': return epg(params.stream_id);
      default: return {};
    }
  }

  const liveUrl = (id) => SAMPLES + (channels.find((c) => c.id === Number(id)) || channels[0]).film.file;
  const movieUrl = (id) => SAMPLES + (films.find((f) => f.id === Number(id)) || films[0]).file;
  const episodeUrl = (id) => movieUrl(Number(id) - 2000);

  return { isDemo, respond, liveUrl, movieUrl, episodeUrl };
})();
