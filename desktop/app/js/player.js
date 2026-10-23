/* Parabol Desktop — video playback.
 *  LivePlayer: one <video> for Live TV that sits over the preview slot (split view) or fills
 *              the window (full screen), so switching between the two never restarts the stream.
 *  VodPlayer:  full-window player for movies, episodes, catch-up and recordings (seek bar,
 *              subtitles, audio track, resume position, previous/next episode). */

/** Attaches a URL to a <video>: HLS via hls.js, MPEG-TS via mpegts.js, anything else natively. */
function createEngine(video, url, { live, onFatal }) {
  const isHls = /\.m3u8(\?|$)/i.test(url);
  const isTs = /\.ts(\?|$)/i.test(url);
  if (isHls && window.Hls && Hls.isSupported()) {
    const hls = new Hls({ enableWorker: true, lowLatencyMode: false, backBufferLength: 30 });
    let recovered = false;
    hls.on(Hls.Events.ERROR, (_e, data) => {
      if (!data.fatal) return;
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !recovered) { recovered = true; hls.recoverMediaError(); return; }
      onFatal && onFatal(data);
    });
    hls.loadSource(url);
    hls.attachMedia(video);
    hls.on(Hls.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
    return { destroy: () => hls.destroy() };
  }
  if (isTs && window.mpegts && mpegts.getFeatureList().mseLivePlayback) {
    const p = mpegts.createPlayer({ type: 'mpegts', isLive: !!live, url }, {
      enableWorker: false, lazyLoad: !live, liveBufferLatencyChasing: !!live, autoCleanupSourceBuffer: true,
    });
    p.on(mpegts.Events.ERROR, (type, detail) => onFatal && onFatal({ type, detail }));
    p.attachMediaElement(video);
    p.load();
    const r = p.play();
    if (r && r.catch) r.catch(() => {});
    return { destroy: () => { try { p.pause(); p.unload(); p.detachMediaElement(); p.destroy(); } catch (e) { /* already gone */ } } };
  }
  video.src = url;
  video.play().catch(() => {});
  video.onerror = () => onFatal && onFatal({ type: 'native' });
  return { destroy: () => { video.onerror = null; video.removeAttribute('src'); video.load(); } };
}

// ======================================================================
// Live TV
// ======================================================================

const LivePlayer = {
  layer: null,
  video: null,
  engine: null,
  channel: null,
  mode: 'hidden', // hidden | split | fullscreen
  slot: null,
  slotObserver: null,
  bannerTimer: null,
  uiTimer: null,
  onExitFullscreen: null, // set by the Live TV screen
  bannerBuilder: null,    // () => Node, set by the Live TV screen

  init() {
    this.layer = document.getElementById('liveLayer');
    this.video = document.getElementById('liveVideo');
    const back = document.getElementById('liveBackBtn');
    back.appendChild(icon('back'));
    back.addEventListener('click', (e) => { e.stopPropagation(); this.exitFullscreen(); });

    this.layer.addEventListener('click', () => {
      if (this.mode === 'split') this.enterFullscreen();
      else if (this.mode === 'fullscreen') this.showBanner();
    });
    this.layer.addEventListener('dblclick', () => { if (this.mode === 'fullscreen') this.exitFullscreen(); });
    this.layer.addEventListener('mousemove', () => { if (this.mode === 'fullscreen') this.showUi(); });

    // Leaving OS full screen (Esc, green button on macOS) also leaves our full-screen mode.
    document.addEventListener('fullscreenchange', () => {
      if (!document.fullscreenElement && this.mode === 'fullscreen') this.exitFullscreen(true);
    });
    window.addEventListener('resize', () => this.position());
    document.addEventListener('scroll', () => this.position(), true);
  },

  play(channel) {
    this.channel = channel;
    this.stopEngine();
    const start = (ext) => {
      this.engine = createEngine(this.video, Xtream.liveUrl(channel.stream_id, ext), {
        live: true,
        onFatal: () => {
          if (this.channel !== channel) return;
          if (ext === 'm3u8') { this.stopEngine(); start('ts'); return; } // provider doesn't serve HLS for it
          toast('Deze zender kan niet worden afgespeeld.');
        },
      });
    };
    start('m3u8');
  },

  stopEngine() {
    if (this.engine) { this.engine.destroy(); this.engine = null; }
    this.video.pause();
    this.video.removeAttribute('src');
    this.video.load();
  },

  stop() {
    this.stopEngine();
    this.channel = null;
    this.setMode('hidden');
  },

  pause() { this.video.pause(); },
  resume() { if (this.channel) this.video.play().catch(() => {}); },

  setSlot(el) {
    if (this.slotObserver) this.slotObserver.disconnect();
    this.slot = el;
    if (el) {
      this.slotObserver = new ResizeObserver(() => this.position());
      this.slotObserver.observe(el);
    }
    this.position();
  },

  setMode(mode) {
    this.mode = mode;
    this.layer.classList.toggle('hidden', mode === 'hidden');
    this.layer.classList.toggle('fullscreen', mode === 'fullscreen');
    this.layer.classList.remove('show-banner', 'show-ui');
    this.position();
  },

  position() {
    if (this.mode !== 'split') return;
    if (!this.slot || !this.slot.isConnected) { this.layer.classList.add('hidden'); return; }
    const r = this.slot.getBoundingClientRect();
    this.layer.classList.toggle('hidden', r.width === 0);
    Object.assign(this.layer.style, { left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px' });
  },

  enterFullscreen() {
    if (!this.channel) return;
    this.setMode('fullscreen');
    if (!document.fullscreenElement) document.documentElement.requestFullscreen().catch(() => {});
    this.showBanner();
  },

  exitFullscreen(fromOs) {
    if (this.mode !== 'fullscreen') return;
    if (!fromOs && document.fullscreenElement) document.exitFullscreen().catch(() => {});
    this.setMode('split');
    if (this.onExitFullscreen) this.onExitFullscreen();
  },

  showBanner() {
    const banner = document.getElementById('liveBanner');
    banner.innerHTML = '';
    if (this.bannerBuilder) banner.appendChild(this.bannerBuilder());
    this.layer.classList.add('show-banner');
    clearTimeout(this.bannerTimer);
    this.bannerTimer = setTimeout(() => this.layer.classList.remove('show-banner'), 3000);
  },

  showUi() {
    this.layer.classList.add('show-ui');
    clearTimeout(this.uiTimer);
    this.uiTimer = setTimeout(() => this.layer.classList.remove('show-ui'), 2500);
  },
};
