/* Parabol Desktop — player for movies, episodes, catch-up and recordings.
 *
 * Each item is first examined with ffmpeg (container, codecs, audio tracks, length). What
 * Chromium can play itself (MP4 with H.264/AAC, HLS, MPEG-TS with H.264/AAC) plays directly
 * with the normal controls. Everything else — MKV, AVI, AC3/E-AC3/DTS/TrueHD audio, HEVC where
 * the computer can't decode it — is converted on the fly by ffmpeg in the main process. That
 * stream can't be seeked by the browser, so Parabol shows its own bar: seeking restarts the
 * conversion at the chosen time. */

const NATIVE_VIDEO = new Set(['h264', 'av1', 'vp9', 'vp8']);
const NATIVE_AUDIO = new Set(['aac', 'mp3', 'opus', 'vorbis']);
const COPY_AUDIO = new Set(['aac', 'mp3']);

function hevcSupported() {
  try { return !!(window.MediaSource && MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L120.90"')); } catch (e) { return false; }
}

function fmtClock(sec) {
  if (!isFinite(sec) || sec < 0) sec = 0;
  sec = Math.floor(sec);
  const hh = Math.floor(sec / 3600);
  const mm = Math.floor((sec % 3600) / 60);
  const ss = sec % 60;
  return (hh ? hh + ':' + pad2(mm) : String(mm)) + ':' + pad2(ss);
}

// ISO 639-2 "bibliographic" codes (common in MKV files) -> the codes Intl understands.
const LANG_B = { ger: 'de', fre: 'fr', dut: 'nl', chi: 'zh', cze: 'cs', gre: 'el', per: 'fa', rum: 'ro', slo: 'sk', alb: 'sq', arm: 'hy', baq: 'eu', bur: 'my', ice: 'is', mac: 'mk', mao: 'mi', may: 'ms', tib: 'bo', wel: 'cy', geo: 'ka' };

/** "nld" / "dut" / "nl" -> "Nederlands". */
function langName(code) {
  if (!code) return '';
  const c = LANG_B[code.toLowerCase()] || code;
  try {
    const n = new Intl.DisplayNames(['nl'], { type: 'language' }).of(c);
    if (n && n.toLowerCase() !== c.toLowerCase()) return n.charAt(0).toUpperCase() + n.slice(1);
  } catch (e) { /* unknown */ }
  return code.toUpperCase();
}

function audioLabel(a) {
  const lang = a.lang ? langName(a.lang) : 'Audio ' + (a.index + 1);
  return `${lang.charAt(0).toUpperCase() + lang.slice(1)} · ${a.codec.toUpperCase()}${a.channels ? ' ' + a.channels : ''}`;
}

const VodPlayer = {
  el: null,
  video: null,
  engine: null,
  items: [],
  index: 0,
  open: false,
  idleTimer: null,
  lastSaved: 0,
  loadToken: 0,

  mode: 'native',     // native | remux
  probe: null,
  offset: 0,          // remux: position in the film where the current conversion started
  audioIndex: 0,
  restarts: 0,
  triedRemux: false,
  dragging: false,
  cues: [],           // subtitles as { start, end, text } in film time
  subTrack: null,

  init() {
    this.el = document.getElementById('vodPlayer');
    this.video = document.getElementById('vodVideo');
    const v = this.video;

    const btn = (id, ic, fn) => { const b = document.getElementById(id); b.appendChild(icon(ic)); b.addEventListener('click', fn); return b; };
    btn('vodBackBtn', 'back', () => this.close());
    btn('vodPrevBtn', 'prev', () => this.go(-1));
    btn('vodNextBtn', 'next', () => this.go(1));
    this.playBtn = btn('vodPlayBtn', 'pause', () => this.togglePlay());
    btn('vodBackSkip', 'replay10', () => this.seekBy(-10));
    btn('vodFwdSkip', 'forward30', () => this.seekBy(30));
    btn('vodFullBtn', 'fullscreen', () => this.toggleFullscreen());
    this.seek = document.getElementById('vodSeek');
    this.timeEl = document.getElementById('vodTime');
    this.loadingEl = document.getElementById('vodLoading');
    this.bar = document.getElementById('vodBar');

    this.seek.addEventListener('input', () => { this.dragging = true; this.paintTime(parseFloat(this.seek.value)); });
    this.seek.addEventListener('change', () => { this.dragging = false; this.seekTo(parseFloat(this.seek.value)); });

    const fileInput = document.getElementById('subtitleFileInput');
    document.getElementById('subtitleUploadBtn').addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', (e) => { handleSubtitleFile(e.target.files[0]); fileInput.value = ''; });
    document.getElementById('subtitleSearchBtn').addEventListener('click', () => {
      const item = this.items[this.index];
      promptDialog('Ondertiteling zoeken', item ? item.title : '', 'Zoeken', (q) => { if (q) searchOpenSubtitles(q); });
    });
    document.getElementById('audioTrackSelect').addEventListener('change', (e) => this.chooseAudio(parseInt(e.target.value, 10)));
    document.getElementById('subtitleSelect').addEventListener('change', (e) => this.chooseSubtitle(e.target.value));

    v.addEventListener('loadedmetadata', () => {
      if (this.mode === 'native') { setupNativeAudioTracks(v); this.restorePosition(); }
    });
    v.addEventListener('playing', () => { this.loadingEl.classList.add('hidden'); this.restarts = 0; });
    v.addEventListener('waiting', () => { if (this.mode === 'remux') this.loadingEl.classList.remove('hidden'); });
    v.addEventListener('play', () => this.paintPlay());
    v.addEventListener('pause', () => { this.paintPlay(); this.wake(); });
    v.addEventListener('timeupdate', () => {
      if (!this.dragging) this.paintTime();
      if (Date.now() - this.lastSaved > 5000) { this.lastSaved = Date.now(); this.savePosition(); }
    });
    v.addEventListener('ended', () => this.onEnded());
    v.addEventListener('error', () => this.onError());
    v.addEventListener('click', () => { if (this.mode === 'remux') this.togglePlay(); });
    v.addEventListener('dblclick', () => this.toggleFullscreen());
    this.el.addEventListener('mousemove', () => this.wake());
  },

  // ---------- Opening ----------

  /** items: [{ url, title, resumeKey?, src? }] — src is what ffmpeg reads (defaults to url). */
  play(items, startIndex = 0) {
    this.items = items;
    this.index = startIndex;
    this.open = true;
    this.el.classList.remove('hidden');
    LivePlayer.pause();
    this.load();
    this.wake();
  },

  async load() {
    const item = this.items[this.index];
    if (!item) return;
    const token = ++this.loadToken;
    this.teardown();
    this.cues = [];
    this.applyCues();
    this.stopSubPoll();
    this.subChoice = 'none';
    this.subIndex = -1;
    this.fileSubLabel = '';
    this.probe = null;
    this.audioIndex = 0;
    this.triedRemux = false;
    this.restarts = 0;
    document.getElementById('vodTitle').textContent = item.title || '';
    document.getElementById('vodInfo').textContent = '';
    const many = this.items.length > 1;
    document.getElementById('vodPrevBtn').classList.toggle('hidden', !many || this.index === 0);
    document.getElementById('vodNextBtn').classList.toggle('hidden', !many || this.index === this.items.length - 1);
    this.loadingEl.classList.remove('hidden');
    this.setMode('native');

    const api = window.parabolAPI;
    if (/\.m3u8(\?|$)/i.test(item.url) || !api || !api.probeMedia) { this.startNative(); return; }

    let probe = null;
    try { probe = await api.probeMedia(item.src || item.url); } catch (e) { probe = null; }
    if (token !== this.loadToken) return;
    this.probe = probe;
    if (probe && probe.error && !probe.video && !(probe.audios || []).length) {
      // Clear server answers (403/404/…) are shown; anything else: let the built-in player try.
      if (/Server returned|HTTP error|\b[45]\d\d\b|refused|timed out/i.test(probe.error)) { this.fail(probeErrorText(probe.error)); return; }
      this.probe = null;
      this.startNative();
      return;
    }
    // A subtitle track the file marks as default/forced is switched on, like on Android.
    const def = ((probe && probe.subtitles) || []).find((t) => t.text && (t.forced || t.isDefault));
    if (def) { this.subChoice = 'embedded'; this.subIndex = def.index; }
    this.renderSubtitleSelect();
    const needsRemux = probe && (probe.video || probe.audios.length) && !this.canPlayNatively(probe);
    if (needsRemux) this.startRemux(this.resumeStart());
    else this.startNative();
  },

  canPlayNatively(p) {
    const c = (p.container || '').toLowerCase();
    const v = p.video ? p.video.codec : null;
    const videoOk = !v || NATIVE_VIDEO.has(v) || (v === 'hevc' && hevcSupported());
    const audioOk = p.audios.every((a) => NATIVE_AUDIO.has(a.codec));
    if (p.audios.length > 1) return false; // convert, so the audio track can be chosen
    if ((p.subtitles || []).some((t) => t.text)) return false; // convert, so its subtitles can be shown
    if (c.includes('mp4') || c.includes('mov') || c.includes('webm')) return videoOk && audioOk;
    if (c.includes('mpegts')) return (!v || v === 'h264') && p.audios.every((a) => a.codec === 'aac');
    return false; // matroska, avi, flv, …
  },

  startNative() {
    const item = this.items[this.index];
    this.setMode('native');
    this.paintInfo();
    this.engine = createEngine(this.video, item.url, {
      live: false,
      onFatal: () => this.onError(),
    });
  },

  async startRemux(at) {
    const item = this.items[this.index];
    const p = this.probe || { video: { codec: 'h264' }, audios: [{ codec: 'unknown' }], duration: 0 };
    const token = this.loadToken;
    const seq = (this.remuxSeq = (this.remuxSeq || 0) + 1);
    this.loadingEl.classList.remove('hidden');
    const base = await window.parabolAPI.mediaStreamBase();
    // The conversion starts at the keyframe at/before "at". Ask where exactly: that is the
    // clock's starting point, and the audio is lined up with it (v0) so sound stays in sync.
    let v0 = null;
    // ffmpeg is asked for exactly the same start (same time, same streams) as the conversion
    // below, so it lands on the same keyframe. Only the clock uses the keyframe time.
    const requested = Math.max(0, at || 0);
    if (p.video && window.parabolAPI.mediaStartInfo) {
      try {
        const info = await window.parabolAPI.mediaStartInfo(item.src || item.url, requested, p.start || 0, this.audioIndex);
        at = info.at;
        v0 = info.v0;
      } catch (e) { /* keep at */ }
    }
    if (token !== this.loadToken || seq !== this.remuxSeq) return;
    if (this.engine) { this.engine.destroy(); this.engine = null; }
    this.setMode('remux');
    this.triedRemux = true;
    const v = p.video ? p.video.codec : 'h264';
    const copyVideo = NATIVE_VIDEO.has(v) || (v === 'hevc' && hevcSupported());
    const audio = p.audios[this.audioIndex] || p.audios[0];
    const params = new URLSearchParams({
      src: item.src || item.url,
      ss: String(requested),
      ...(v0 !== null && v0 !== undefined && audio && audio.rate ? { v0: String(v0), sr: String(audio.rate) } : {}),
      a: String(this.audioIndex),
      v: copyVideo ? 'copy' : 'h264',
      ac: audio && COPY_AUDIO.has(audio.codec) ? 'copy' : 'aac',
      hevc: v === 'hevc' ? '1' : '0',
    });
    this.stopSubPoll();
    if (this.subChoice === 'embedded' && this.subIndex >= 0) {
      this.subId = Math.random().toString(36).slice(2, 12);
      params.set('sub', String(this.subIndex));
      params.set('subid', this.subId);
    }
    this.offset = Math.max(0, at || 0);
    this.syncCapable = v0 !== null && v0 !== undefined && !!(audio && audio.rate);
    this.loadingEl.classList.remove('hidden');
    this.paintInfo(copyVideo);
    this.video.src = base + '?' + params.toString();
    this.video.play().catch(() => {});
    this.applyCues();
    this.paintTime();
    this.renderAudioSelect();
    if (params.has('subid')) this.startSubPoll(this.subId, requested);
  },

  // ---------- Subtitles inside the file ----------

  /** "none" | "emb:<n>" | "file" from the subtitle list in the top bar. */
  chooseSubtitle(value) {
    if (value === 'none') {
      this.subChoice = 'none';
      this.stopSubPoll();
      this.cues = [];
      this.applyCues();
    } else if (value.startsWith('emb:')) {
      this.subChoice = 'embedded';
      this.subIndex = parseInt(value.slice(4), 10);
      this.cues = [];
      this.applyCues();
      // The subtitles are read while converting: (re)start the conversion here.
      this.startRemux(this.currentTime());
    }
    this.renderSubtitleSelect();
    this.wake();
  },

  /** Reads the subtitles ffmpeg has converted so far, every 2 seconds, while this stream plays. */
  startSubPoll(subId, requested) {
    const start = (this.probe && this.probe.start) || 0;
    const absolute = this.syncCapable; // with -copyts the times are the file's own
    let lastLength = -1;
    const poll = async () => {
      if (this.subId !== subId || !this.open || this.subChoice !== 'embedded') return;
      try {
        const base = await window.parabolAPI.mediaSubsBase();
        const text = await fetch(base + '?id=' + subId).then((r) => r.text());
        if (text.length !== lastLength && this.subId === subId) {
          lastLength = text.length;
          this.cues = parseSubtitles(text).map((c) => {
            const shift = absolute ? -start : requested;
            return { start: c.start + shift, end: c.end + shift, text: c.text };
          });
          this.applyCues();
        }
      } catch (e) { /* try again */ }
      this.subTimer = setTimeout(poll, 2000);
    };
    this.subTimer = setTimeout(poll, 800);
  },

  stopSubPoll() {
    clearTimeout(this.subTimer);
    this.subId = null;
  },

  renderSubtitleSelect() {
    const select = document.getElementById('subtitleSelect');
    const tracks = (this.probe && this.probe.subtitles) || [];
    select.innerHTML = '';
    select.appendChild(h('option', { value: 'none', text: 'Ondertiteling uit' }));
    tracks.forEach((t) => {
      const name = subtitleLabel(t);
      select.appendChild(h('option', {
        value: 'emb:' + t.index,
        text: t.text ? name : name + ' (beeld, niet te tonen)',
        disabled: !t.text,
      }));
    });
    if (this.fileSubLabel) select.appendChild(h('option', { value: 'file', text: 'Bestand: ' + this.fileSubLabel }));
    select.value = this.subChoice === 'embedded' ? 'emb:' + this.subIndex : this.subChoice === 'file' ? 'file' : 'none';
    select.classList.toggle('hidden', !tracks.length && !this.fileSubLabel);
  },

  resumeStart() {
    const item = this.items[this.index];
    if (!item || !item.resumeKey) return 0;
    const pos = Store.getPosition(item.resumeKey);
    const d = this.probe && this.probe.duration;
    if (pos > 30 && (!d || pos < d - 120)) {
      toast('Verder kijken vanaf ' + Math.floor(pos / 60) + ' min');
      return pos;
    }
    return 0;
  },

  restorePosition() {
    const item = this.items[this.index];
    if (!item || !item.resumeKey) return;
    const pos = Store.getPosition(item.resumeKey);
    if (pos > 30 && (!this.video.duration || pos < this.video.duration - 120)) {
      this.video.currentTime = pos;
      toast('Verder kijken vanaf ' + Math.floor(pos / 60) + ' min');
    }
  },

  // ---------- Errors & end ----------

  onError() {
    if (!this.open) return;
    const err = this.video.error;
    if (this.mode === 'native' && !this.triedRemux && window.parabolAPI && window.parabolAPI.mediaStreamBase) {
      // The built-in player gave up: convert instead.
      this.startRemux(this.video.currentTime || this.resumeStart());
      return;
    }
    if (this.mode === 'remux' && this.restarts < 2 && this.currentTime() > 1) {
      this.restarts++;
      this.startRemux(this.currentTime());
      return;
    }
    const p = this.probe;
    let detail = '';
    if (p && (p.video || (p.audios || []).length)) {
      detail = ' (' + [p.video && p.video.codec, p.audios[0] && p.audios[0].codec, p.container].filter(Boolean).join(', ') + ')';
    }
    this.fail('Deze video kan niet worden afgespeeld' + detail + '.' + (err && err.code === 2 ? ' Controleer je internetverbinding.' : ''));
  },

  fail(message) {
    this.loadingEl.classList.add('hidden');
    toast(message, 6000);
  },

  onEnded() {
    const d = this.duration();
    if (this.mode === 'remux' && d && this.currentTime() < d - 10 && this.restarts < 3) {
      // The conversion stopped early (connection dropped): continue where it stopped.
      this.restarts++;
      this.startRemux(this.currentTime());
      return;
    }
    this.savePosition(true);
    if (this.index < this.items.length - 1) this.go(1);
  },

  // ---------- Transport ----------

  currentTime() { return this.mode === 'remux' ? this.offset + (this.video.currentTime || 0) : (this.video.currentTime || 0); },
  duration() {
    if (this.mode === 'remux') return (this.probe && this.probe.duration) || 0;
    return isFinite(this.video.duration) ? this.video.duration : 0;
  },

  togglePlay() { if (this.video.paused) this.video.play().catch(() => {}); else this.video.pause(); },

  seekTo(t) {
    const d = this.duration();
    if (d) t = Math.min(Math.max(0, t), d - 1);
    else t = Math.max(0, t);
    if (this.mode === 'remux') {
      if (!d) return; // unknown length (live catch-up): no seeking
      this.startRemux(t);
    } else {
      this.video.currentTime = t;
    }
    this.wake();
  },

  seekBy(delta) { this.seekTo(this.currentTime() + delta); },

  chooseAudio(i) {
    if (this.mode !== 'remux' || i === this.audioIndex) return;
    this.audioIndex = i;
    this.startRemux(this.currentTime());
  },

  toggleFullscreen() {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else this.el.requestFullscreen().catch(() => {});
  },

  go(delta) {
    const next = this.index + delta;
    if (next < 0 || next >= this.items.length) return;
    this.savePosition();
    this.index = next;
    this.load();
  },

  savePosition(finished) {
    const item = this.items[this.index];
    if (!item || !item.resumeKey) return;
    const t = this.currentTime();
    const d = this.duration();
    const nearEnd = d && t > d - 120;
    Store.setPosition(item.resumeKey, finished || t < 30 || nearEnd ? 0 : t);
  },

  teardown() {
    if (this.engine) { this.engine.destroy(); this.engine = null; }
    this.video.pause();
    this.video.removeAttribute('src');
    this.video.load();
  },

  close() {
    if (!this.open) return;
    this.savePosition();
    this.open = false;
    this.loadToken++;
    this.stopSubPoll();
    this.teardown();
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    this.el.classList.add('hidden');
    emitChange('positions');
    if (Router.current() && Router.current().name === 'live') LivePlayer.resume();
  },

  /** Keys while the player is open. Returns true when handled. */
  onKey(e) {
    if (isTyping(e)) return false;
    switch (e.key) {
      case 'Escape': if (!document.fullscreenElement) { this.close(); return true; } return false;
      case ' ': case 'k': this.togglePlay(); return true;
      case 'ArrowLeft': this.seekBy(-10); return true;
      case 'ArrowRight': this.seekBy(30); return true;
      case 'ArrowUp': this.video.volume = Math.min(1, this.video.volume + 0.1); toast('Volume ' + Math.round(this.video.volume * 100) + '%', 1000); return true;
      case 'ArrowDown': this.video.volume = Math.max(0, this.video.volume - 0.1); toast('Volume ' + Math.round(this.video.volume * 100) + '%', 1000); return true;
      case 'f': this.toggleFullscreen(); return true;
      case 'n': this.go(1); return true;
      case 'p': this.go(-1); return true;
      case 'i': { const t = document.getElementById('vodInfo').textContent; if (t) toast(t, 5000); return true; }
      default: return false;
    }
  },

  // ---------- UI ----------

  /** One line under the title: file format and how it is played (helps when something is off). */
  paintInfo(copyVideo) {
    const p = this.probe;
    const el = document.getElementById('vodInfo');
    if (!p || !(p.video || (p.audios || []).length)) { el.textContent = this.mode === 'native' ? 'Direct afgespeeld' : ''; return; }
    const cont = (p.container || '').split(',')[0].replace('matroska', 'MKV').replace('mov', 'MP4').replace('mpegts', 'TS').toUpperCase();
    const a = p.audios[this.audioIndex] || p.audios[0];
    const parts = [cont];
    if (p.video) parts.push(p.video.codec.toUpperCase() + (p.video.height ? ' ' + p.video.height + 'p' : ''));
    if (a) parts.push(a.codec.toUpperCase() + (a.channels ? ' ' + a.channels : ''));
    let how = 'direct afgespeeld';
    if (this.mode === 'remux') {
      how = 'omgezet (' + (copyVideo === false ? 'beeld en geluid' : 'geluid → AAC') + ')';
      if (!this.syncCapable) how += ', zonder uitlijning';
    }
    el.textContent = parts.filter(Boolean).join(' · ') + ' — ' + how;
  },

  setMode(mode) {
    this.mode = mode;
    this.video.controls = mode === 'native';
    this.el.classList.toggle('custom', mode === 'remux');
    if (mode === 'native') document.getElementById('audioTrackSelect').classList.add('hidden');
  },

  paintPlay() {
    this.playBtn.innerHTML = '';
    this.playBtn.appendChild(icon(this.video.paused ? 'play' : 'pause'));
    this.playBtn.setAttribute('aria-label', this.video.paused ? 'Afspelen' : 'Pauzeren');
  },

  paintTime(at) {
    if (this.mode !== 'remux') return;
    const d = this.duration();
    const t = at !== undefined ? at : this.currentTime();
    this.seek.max = String(Math.max(1, Math.floor(d)));
    this.seek.disabled = !d;
    if (at === undefined) this.seek.value = String(Math.floor(t));
    this.timeEl.textContent = d ? `${fmtClock(t)} / ${fmtClock(d)}` : fmtClock(t);
    this.seek.style.setProperty('--p', d ? (t / d * 100).toFixed(2) + '%' : '0%');
  },

  renderAudioSelect() {
    const select = document.getElementById('audioTrackSelect');
    const audios = (this.probe && this.probe.audios) || [];
    select.innerHTML = '';
    if (this.mode !== 'remux' || audios.length < 2) { select.classList.add('hidden'); return; }
    audios.forEach((a) => select.appendChild(h('option', { value: String(a.index), text: audioLabel(a) })));
    select.value = String(this.audioIndex);
    select.classList.remove('hidden');
  },

  wake() {
    this.el.classList.remove('idle');
    clearTimeout(this.idleTimer);
    this.idleTimer = setTimeout(() => { if (!this.video.paused) this.el.classList.add('idle'); }, 3000);
  },

  // ---------- Subtitles (kept as cues in film time, so they stay in sync after seeking) ----------

  setSubtitles(cues, label) {
    this.stopSubPoll();
    this.subChoice = 'file';
    this.fileSubLabel = label;
    this.renderSubtitleSelect();
    this.cues = cues;
    this.applyCues();
    toast(cues.length ? 'Ondertiteling geladen: ' + label : 'Geen ondertitels gevonden in het bestand.');
  },

  applyCues() {
    const video = this.video;
    if (!this.subTrack) this.subTrack = video.addTextTrack('subtitles', 'Ondertiteling', 'nl');
    const track = this.subTrack;
    track.mode = 'hidden';
    if (track.cues) Array.from(track.cues).forEach((c) => track.removeCue(c));
    const shift = this.mode === 'remux' ? this.offset : 0;
    this.cues.forEach((c) => {
      if (c.end - shift <= 0) return;
      try { track.addCue(new VTTCue(Math.max(0, c.start - shift), c.end - shift, c.text)); } catch (e) { /* bad cue */ }
    });
    track.mode = this.cues.length ? 'showing' : 'disabled';
  },
};

function subtitleLabel(t) {
  const name = t.lang ? langName(t.lang) : 'Ondertiteling ' + (t.index + 1);
  return name + (t.forced ? ' (geforceerd)' : '');
}

function probeErrorText(error) {
  if (/\b40[13]\b/.test(error)) return 'De server weigert deze video (' + error.replace(/^Server returned /, '') + ').';
  if (/404/.test(error)) return 'De video is niet (meer) beschikbaar bij je aanbieder (404).';
  if (/refused|timed out/i.test(error)) return 'Geen verbinding met de server.';
  if (/ffmpeg ontbreekt/.test(error)) return 'Onderdeel om video om te zetten ontbreekt. Installeer Parabol opnieuw.';
  return 'Deze video kan niet worden geopend (' + error + ').';
}

// ---------- Subtitle files ----------

function srtToVtt(srt) {
  return 'WEBVTT\n\n' + srt.replace(/\r+/g, '')
    .replace(/^\d+\n(?=\d{1,2}:\d{2}:\d{2})/gm, '')
    .replace(/(\d{1,2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2');
}

function parseVttTime(s) {
  const p = s.trim().split(':');
  let sec = 0;
  p.forEach((x) => { sec = sec * 60 + parseFloat(x.replace(',', '.')); });
  return sec;
}

/** WebVTT / SRT text -> [{ start, end, text }]. */
function parseSubtitles(text) {
  const vtt = /^﻿?WEBVTT/.test(text.trim()) ? text : srtToVtt(text);
  const cues = [];
  vtt.replace(/\r+/g, '').split(/\n{2,}/).forEach((block) => {
    const lines = block.split('\n');
    const i = lines.findIndex((l) => l.includes('-->'));
    if (i < 0) return;
    const [a, b] = lines[i].split('-->');
    const start = parseVttTime(a);
    const end = parseVttTime(b.trim().split(/\s+/)[0]);
    const body = lines.slice(i + 1).join('\n').trim();
    if (body && end > start) cues.push({ start, end, text: body });
  });
  return cues;
}

function handleSubtitleFile(file) {
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => VodPlayer.setSubtitles(parseSubtitles(String(reader.result)), file.name);
  reader.readAsText(file);
}

async function searchOpenSubtitles(query) {
  const apiKey = Store.getOpenSubtitlesApiKey();
  if (!apiKey) { toast('Vul eerst een OpenSubtitles API-key in bij Instellingen.', 4000); return; }
  const headers = { 'Api-Key': apiKey };
  try {
    toast('Ondertiteling zoeken…');
    const res = await fetch(`https://api.opensubtitles.com/api/v1/subtitles?query=${encodeURIComponent(query)}&languages=nl,en`, { headers });
    const data = await res.json();
    const best = (data.data || [])[0];
    const fileId = best && best.attributes && best.attributes.files && best.attributes.files[0] && best.attributes.files[0].file_id;
    if (!fileId) { toast('Geen ondertiteling gevonden voor "' + query + '".', 4000); return; }
    const dl = await fetch('https://api.opensubtitles.com/api/v1/download', {
      method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify({ file_id: fileId }),
    }).then((r) => r.json());
    if (!dl.link) { toast('Downloaden van de ondertiteling is mislukt.', 4000); return; }
    const text = await fetch(dl.link).then((r) => r.text());
    VodPlayer.setSubtitles(parseSubtitles(text), best.attributes.release || query);
  } catch (e) {
    toast('Zoeken via OpenSubtitles is mislukt. Controleer je API-key en internetverbinding.', 5000);
  }
}

/** Native mode: a file with several audio tracks the browser exposes (rare; most are converted). */
function setupNativeAudioTracks(video) {
  const select = document.getElementById('audioTrackSelect');
  const tracks = video.audioTracks;
  if (!tracks || tracks.length < 2) return;
  select.innerHTML = '';
  Array.from(tracks).forEach((t, i) => {
    select.appendChild(h('option', { value: String(i), text: t.label || t.language || 'Audio ' + (i + 1) }));
    if (t.enabled) select.value = String(i);
  });
  select.classList.remove('hidden');
  select.onchange = () => Array.from(tracks).forEach((t, i) => { t.enabled = i === parseInt(select.value, 10); });
}
