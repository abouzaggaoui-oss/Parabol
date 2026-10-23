/* Parabol Desktop — media helper in the main process.
 *
 * Chromium's built-in player can't play many IPTV movie files: Matroska (.mkv), AVI, and audio
 * in AC3 / E-AC3 / DTS / TrueHD. For those, a bundled ffmpeg
 *   1. probes the file (container, codecs, audio tracks, duration), and
 *   2. converts it on the fly to fragmented MP4 (video copied when possible, audio to AAC),
 * served from a local HTTP server on 127.0.0.1 that only answers requests carrying a secret
 * token. The renderer plays that like any other video. */

const { spawn } = require('child_process');
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

// Subtitle formats that can be turned into text (WebVTT). Picture-based ones (PGS, DVD, DVB)
// can't be shown as text and are listed as unavailable.
const TEXT_SUBS = new Set(['subrip', 'srt', 'ass', 'ssa', 'webvtt', 'mov_text', 'text', 'subviewer', 'microdvd', 'jacosub', 'realtext', 'sami', 'stl']);

const UA = 'Parabol/2.2 (Desktop)';

function ffmpegBinary(app) {
  try {
    let p = require('ffmpeg-static');
    if (!p) return null;
    // Inside the packaged app the binary lives next to (not inside) app.asar.
    if (app.isPackaged) p = p.replace('app.asar', 'app.asar.unpacked');
    return p;
  } catch (e) {
    return null;
  }
}

function inputArgs(input) {
  return /^https?:/i.test(input)
    ? ['-user_agent', UA, '-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_on_network_error', '1', '-reconnect_delay_max', '5', '-rw_timeout', '20000000']
    : [];
}

/** Reads container, duration and streams from ffmpeg's banner ("ffmpeg -i input"). */
function parseProbe(text) {
  const info = { container: null, duration: 0, video: null, audios: [], subtitles: [], error: null };
  const c = /Input #0, ([^,]+(?:,[^,]+)*?), from/.exec(text);
  if (c) info.container = c[1];
  const d = /Duration: (\d+):(\d+):(\d+(?:\.\d+)?)/.exec(text);
  if (d) info.duration = +d[1] * 3600 + +d[2] * 60 + parseFloat(d[3]);
  const st = /, start: (-?\d+(?:\.\d+)?)/.exec(text);
  info.start = st ? parseFloat(st[1]) : 0;
  const re = /Stream #0:(\d+)(?:\[0x[0-9a-f]+\])?(?:\(([^)]*)\))?: (Video|Audio|Subtitle): ([a-z0-9_]+)([^\n]*)/gi;
  let m;
  let audioIndex = 0;
  let subIndex = 0;
  while ((m = re.exec(text))) {
    const [, , lang, kind, codec, rest] = m;
    if (kind === 'Video' && !info.video && !/attached pic/.test(rest)) {
      const size = /(\d{2,5})x(\d{2,5})/.exec(rest);
      info.video = { codec, width: size ? +size[1] : 0, height: size ? +size[2] : 0, tenBit: /p10|10le|10be/.test(rest) };
    } else if (kind === 'Audio') {
      const ch = /(mono|stereo|5\.1|7\.1|\d+ channels)/.exec(rest);
      const hz = /(\d{4,6}) Hz/.exec(rest);
      info.audios.push({ index: audioIndex++, codec, lang: lang && lang !== 'und' ? lang : '', channels: ch ? ch[1] : '', rate: hz ? +hz[1] : 0 });
    } else if (kind === 'Subtitle') {
      info.subtitles.push({
        index: subIndex++, codec, lang: lang && lang !== 'und' ? lang : '',
        text: TEXT_SUBS.has(codec), isDefault: /\(default\)/.test(rest), forced: /\(forced\)/.test(rest),
      });
    }
  }
  const err = /(Server returned [^\n]+|Connection refused|Connection timed out|No such file or directory|Invalid data found when processing input|HTTP error [^\n]+)/.exec(text);
  if (err && !info.video && !info.audios.length) info.error = err[1].trim();
  return info;
}

function createMediaService(app, resolveRecordingPath) {
  const ffmpeg = ffmpegBinary(app);
  const token = crypto.randomBytes(16).toString('hex');
  let port = 0;
  const running = new Set();

  /** "rec:<id>" -> the recording's file path; http(s) URLs pass through; anything else is refused. */
  function resolveInput(src) {
    if (typeof src !== 'string') return null;
    if (/^https?:\/\//i.test(src)) return src;
    if (src.startsWith('rec:')) return resolveRecordingPath(src.slice(4));
    return null;
  }

  function probe(src) {
    return new Promise((resolve) => {
      const input = resolveInput(src);
      if (!ffmpeg) return resolve({ error: 'ffmpeg ontbreekt' });
      if (!input) return resolve({ error: 'Onbekende bron' });
      const proc = spawn(ffmpeg, ['-hide_banner', ...inputArgs(input), '-i', input], { windowsHide: true });
      let text = '';
      const timer = setTimeout(() => proc.kill('SIGKILL'), 25000);
      proc.stderr.on('data', (b) => { if (text.length < 200000) text += b.toString(); });
      proc.on('error', (e) => { clearTimeout(timer); resolve({ error: e.message }); });
      proc.on('close', () => { clearTimeout(timer); resolve(parseProbe(text)); });
    });
  }

  /**
   * Where the converted stream will really start. ffmpeg starts at the keyframe at or before the
   * requested time; this returns that keyframe's timestamp, both raw (as in the file, "v0") and
   * as film time ("at", for the player's clock and subtitles). The conversion then lines the
   * audio up with exactly this point, so sound and picture stay in sync.
   */
  function startInfo(src, ss, start, audio) {
    return new Promise((resolve) => {
      const input = resolveInput(src);
      const fallback = { at: ss || 0, v0: null };
      if (!ffmpeg || !input) return resolve(fallback);
      const args = ['-hide_banner', '-loglevel', 'error'];
      if (ss > 0) args.push('-ss', Number(ss).toFixed(3));
      args.push('-noaccurate_seek', '-copyts', ...inputArgs(input), '-i', input,
        '-map', '0:v:0', '-map', `0:a:${Math.max(0, parseInt(audio, 10) || 0)}?`, '-c', 'copy', '-frames:v', '1', '-f', 'framemd5', '-');
      const proc = spawn(ffmpeg, args, { windowsHide: true });
      let out = '';
      const timer = setTimeout(() => proc.kill('SIGKILL'), 15000);
      proc.stdout.on('data', (b) => { out += b.toString(); });
      proc.on('error', () => { clearTimeout(timer); resolve(fallback); });
      proc.on('close', () => {
        clearTimeout(timer);
        const tb = /#tb 0: (\d+)\/(\d+)/.exec(out);
        const line = out.split('\n').find((l) => /^0,/.test(l)); // stream 0 = the video
        if (!tb || !line) return resolve(fallback);
        const v0 = (parseInt(line.split(',')[2], 10) * +tb[1]) / +tb[2];
        const at = v0 - (start || 0);
        if (!isFinite(v0) || at < -1 || at > (ss || 0) + 1) return resolve(fallback);
        resolve({ at: Math.max(0, at), v0 });
      });
    });
  }

  const subFiles = new Set();
  const subFile = (id) => path.join(os.tmpdir(), `parabol-subs-${token.slice(0, 8)}-${id}.vtt`);

  /** GET /subs/<token>?id=… — the subtitles converted so far (WebVTT), or empty. */
  function handleSubs(url, res) {
    const id = (url.searchParams.get('id') || '').replace(/[^a-z0-9]/gi, '').slice(0, 40);
    let text = '';
    try { text = fs.readFileSync(subFile(id), 'utf-8'); } catch (e) { /* not written yet */ }
    res.writeHead(200, { 'Content-Type': 'text/vtt; charset=utf-8', 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*' });
    res.end(text);
  }

  /** GET /stream/<token>?src=…&ss=<sec>&v0=<raw start>&a=<audio index>&v=copy|h264&ac=copy|aac */
  function handle(req, res) {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (url.pathname === '/subs/' + token) { handleSubs(url, res); return; }
    if (url.pathname !== '/stream/' + token) { res.writeHead(404); res.end(); return; }
    const input = resolveInput(url.searchParams.get('src'));
    if (!input || !ffmpeg) { res.writeHead(400); res.end(); return; }
    const ss = Math.max(0, parseFloat(url.searchParams.get('ss')) || 0);
    const audio = Math.max(0, parseInt(url.searchParams.get('a'), 10) || 0);
    const vcopy = url.searchParams.get('v') !== 'h264';
    const hevc = url.searchParams.get('hevc') === '1';
    const v0 = parseFloat(url.searchParams.get('v0'));
    const rate = parseInt(url.searchParams.get('sr'), 10) || 0;
    const sync = isFinite(v0) && rate > 0;
    const acopy = url.searchParams.get('ac') === 'copy' && !sync;

    // Audio and video are cut at the same point (the keyframe), and the audio is padded or
    // trimmed so its first sample lines up exactly with the first picture. Without this, files
    // whose audio starts a little before/after the video (common with IPTV files) or a jump to
    // a point between keyframes play with the sound seconds early or late: the fragmented MP4
    // that the player reads starts every track at 0.
    const args = ['-hide_banner', '-loglevel', 'error', '-nostdin'];
    if (ss > 0) args.push('-ss', ss.toFixed(3));
    args.push('-noaccurate_seek');
    if (sync) args.push('-copyts');
    args.push(...inputArgs(input), '-i', input,
      '-map', '0:v:0?', '-map', `0:a:${audio}?`, '-sn', '-dn', '-map_chapters', '-1');
    if (vcopy) {
      args.push('-c:v', 'copy');
      if (hevc) args.push('-tag:v', 'hvc1');
    } else {
      args.push('-c:v', 'libx264', '-preset', 'veryfast', '-crf', '21', '-pix_fmt', 'yuv420p', '-vf', "scale='min(1920,iw)':-2");
    }
    if (acopy) {
      args.push('-c:a', 'copy');
    } else {
      // With -copyts both streams keep the file's own timestamps; the audio is padded/trimmed to
      // begin exactly at the first picture (v0) and the output is then shifted so both start at 0.
      args.push('-af', sync ? `aresample=async=1:first_pts=${Math.round(v0 * rate)}` : 'aresample=async=1');
      args.push('-c:a', 'aac', '-b:a', '192k', '-ac', '2');
    }
    if (sync) args.push('-output_ts_offset', (-v0).toFixed(6));
    args.push('-movflags', 'frag_keyframe+empty_moov+default_base_moof', '-frag_duration', '2000000', '-f', 'mp4', 'pipe:1');

    // Optional second output: the chosen subtitle track of the file, written as WebVTT while the
    // film is converted (the player fetches it via /subs). Keeps the file's own timestamps.
    const sub = parseInt(url.searchParams.get('sub'), 10);
    const subId = (url.searchParams.get('subid') || '').replace(/[^a-z0-9]/gi, '').slice(0, 40);
    if (sub >= 0 && subId) {
      const file = subFile(subId);
      try { fs.unlinkSync(file); } catch (e) { /* none yet */ }
      subFiles.add(file);
      args.push('-map', `0:s:${sub}`, '-c:s', 'webvtt', '-flush_packets', '1', '-f', 'webvtt', '-y', file);
    }

    const proc = spawn(ffmpeg, args, { windowsHide: true });
    running.add(proc);
    let errText = '';
    proc.stderr.on('data', (b) => { if (errText.length < 20000) errText += b.toString(); });
    res.writeHead(200, { 'Content-Type': 'video/mp4', 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*' });
    proc.stdout.pipe(res);
    const stop = () => { try { proc.kill('SIGKILL'); } catch (e) { /* gone */ } };
    req.on('close', stop);
    res.on('close', stop);
    proc.on('close', (code) => {
      running.delete(proc);
      if (code && errText) console.warn('[ffmpeg]', errText.slice(0, 500));
      res.end();
    });
  }

  const server = http.createServer(handle);
  const ready = new Promise((resolve) => server.listen(0, '127.0.0.1', () => { port = server.address().port; resolve(); }));

  return {
    available: !!ffmpeg,
    probe,
    startInfo,
    async streamBase() {
      await ready;
      return `http://127.0.0.1:${port}/stream/${token}`;
    },
    async subsBase() {
      await ready;
      return `http://127.0.0.1:${port}/subs/${token}`;
    },
    shutdown() {
      running.forEach((p) => { try { p.kill('SIGKILL'); } catch (e) { /* gone */ } });
      subFiles.forEach((f) => { try { fs.unlinkSync(f); } catch (e) { /* gone */ } });
      server.close();
    },
  };
}

module.exports = { createMediaService, parseProbe };
