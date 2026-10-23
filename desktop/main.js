const { app, BrowserWindow, Menu, Tray, ipcMain, shell, Notification, protocol, net, session } = require('electron');
const path = require('path');
const fs = require('fs');
const http = require('http');
const https = require('https');
const { pathToFileURL } = require('url');
const { createMediaService } = require('./media');

// Make the PORTABLE build genuinely self-contained: keep all app data (login,
// favorites, recordings) in a folder next to the .exe itself, rather than in
// the OS's roaming profile. electron-builder sets PORTABLE_EXECUTABLE_DIR only
// for the portable target; the installed (Setup) version is unaffected and
// keeps using the normal per-user AppData location.
if (process.env.PORTABLE_EXECUTABLE_DIR) {
  app.setPath('userData', path.join(process.env.PORTABLE_EXECUTABLE_DIR, 'ParabolData'));
}

const isMac = process.platform === 'darwin';

// Recordings are played through parabol-rec://rec/<id>.ts so the in-app player (mpegts.js,
// which reads with fetch) can stream them; only files listed as completed recordings are served.
protocol.registerSchemesAsPrivileged([
  { scheme: 'parabol-rec', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true } },
]);

let mainWindow = null;
let tray = null;
app.isQuiting = false;

// Prevent a second launch from starting a conflicting second process. This
// matters especially because closing the window hides it to the tray rather
// than quitting (see below) — without this lock, double-clicking the exe
// again while Parabol is still running in the tray would start a second,
// separate instance that reads/writes the same storage at the same time,
// which can make it look like logins or settings "aren't remembered".
const gotSingleInstanceLock = app.requestSingleInstanceLock();
if (!gotSingleInstanceLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });
}

// ---------------- Recordings: persisted state ----------------
// Recordings live in the main process (not the renderer's localStorage) because
// they must keep running even while the window is hidden in the system tray.

function recordingsFilePath() {
  return path.join(app.getPath('userData'), 'recordings.json');
}

function recordingsDir() {
  return path.join(app.getPath('videos'), 'Parabol');
}

function loadRecordings() {
  try {
    return JSON.parse(fs.readFileSync(recordingsFilePath(), 'utf-8'));
  } catch (e) {
    return { scheduled: [], completed: [] };
  }
}

function saveRecordings(data) {
  fs.mkdirSync(path.dirname(recordingsFilePath()), { recursive: true });
  fs.writeFileSync(recordingsFilePath(), JSON.stringify(data, null, 2));
}

const activeTimers = new Map(); // recordingId -> Timeout
const activeRequests = new Map(); // recordingId -> http(s) request, for cancellation

function notifyRenderer() {
  if (mainWindow) mainWindow.webContents.send('recordings:changed', publicRecordings());
}

/** Converts internal state (with raw filesystem paths) into renderer-safe data (file:// URLs). */
function publicRecordings() {
  const data = loadRecordings();
  return {
    scheduled: data.scheduled,
    completed: data.completed.map((r) => ({
      ...r,
      fileUrl: pathToFileURL(r.filePath).href,
    })),
  };
}

function scheduleTimer(rec) {
  const delay = Math.max(0, rec.startEpochSeconds * 1000 - Date.now());
  const timer = setTimeout(() => startRecording(rec), delay);
  activeTimers.set(rec.id, timer);
}

function startRecording(rec) {
  const data = loadRecordings();
  const stillScheduled = data.scheduled.find((r) => r.id === rec.id);
  if (!stillScheduled) return; // was cancelled in the meantime

  fs.mkdirSync(recordingsDir(), { recursive: true });
  const safeTitle = (rec.programTitle || 'Opname').replace(/[^A-Za-z0-9 _-]/g, '').trim() || 'Opname';
  const fileName = `Parabol - ${safeTitle} - ${Date.now()}.ts`;
  const filePath = path.join(recordingsDir(), fileName);
  const fileStream = fs.createWriteStream(filePath);

  let checkInterval = null;
  let settled = false;

  function fail(reason) {
    if (settled) return;
    settled = true;
    if (checkInterval) clearInterval(checkInterval);
    fileStream.end();
    if (Notification.isSupported()) {
      new Notification({
        title: 'Opname mislukt',
        body: `${rec.programTitle} (${rec.channelName}): ${reason}`,
      }).show();
    }
    cleanupAndFinish(rec.id, filePath, false);
  }

  function requestUrl(url, redirectsLeft) {
    const client = url.startsWith('https') ? https : http;
    const req = client.get(url, { timeout: 15000 }, (res) => {
      const status = res.statusCode || 0;

      // Follow redirects manually — Node's http/https client does not do this
      // automatically, and some Xtream panels redirect the live URL to a CDN.
      if ([301, 302, 303, 307, 308].includes(status) && res.headers.location && redirectsLeft > 0) {
        res.resume(); // discard this response body
        const nextUrl = new URL(res.headers.location, url).toString();
        requestUrl(nextUrl, redirectsLeft - 1);
        return;
      }

      if (status !== 200) {
        fail(`server antwoordde met HTTP ${status}`);
        return;
      }

      res.pipe(fileStream);

      const endTimeMs = rec.endEpochSeconds * 1000;
      checkInterval = setInterval(() => {
        if (Date.now() >= endTimeMs) {
          clearInterval(checkInterval);
          req.destroy();
        }
      }, 5000);

      activeRequests.set(rec.id, req);
    });

    req.on('timeout', () => {
      req.destroy();
      fail('kon geen verbinding maken (time-out)');
    });

    req.on('error', (err) => {
      fail(err.message || 'netwerkfout');
    });

    activeRequests.set(rec.id, req);
  }

  requestUrl(rec.url, 5);

  fileStream.on('close', () => {
    if (settled) return; // already handled via fail()
    if (checkInterval) clearInterval(checkInterval);
    let ok = false;
    try { ok = fs.statSync(filePath).size > 0; } catch (e) { ok = false; }
    settled = true;
    cleanupAndFinish(rec.id, filePath, ok);
  });
}

function cleanupAndFinish(id, filePath, success) {
  const data = loadRecordings();
  const rec = data.scheduled.find((r) => r.id === id);
  data.scheduled = data.scheduled.filter((r) => r.id !== id);

  if (rec && success) {
    data.completed.push({
      id: 'completed_' + Date.now(),
      profileId: rec.profileId,
      channelName: rec.channelName,
      programTitle: rec.programTitle,
      filePath,
      recordedAtEpochSeconds: Math.floor(Date.now() / 1000),
    });
    if (Notification.isSupported()) {
      new Notification({
        title: 'Opname voltooid',
        body: `${rec.programTitle} (${rec.channelName})`,
      }).show();
    }
  } else if (rec) {
    try { fs.unlinkSync(filePath); } catch (e) { /* best effort */ }
  }

  saveRecordings(data);
  activeTimers.delete(id);
  activeRequests.delete(id);
  notifyRenderer();
}

/** On startup: resume timers for still-future recordings, start any that should already be
 *  recording, and silently drop ones whose entire window passed while the app wasn't running. */
function resumeRecordingsOnStartup() {
  const data = loadRecordings();
  const now = Date.now();
  const kept = [];

  data.scheduled.forEach((rec) => {
    if (rec.endEpochSeconds * 1000 <= now) {
      return; // missed entirely while the app was closed — drop silently
    }
    if (rec.startEpochSeconds * 1000 <= now) {
      kept.push(rec);
      startRecording(rec);
    } else {
      kept.push(rec);
      scheduleTimer(rec);
    }
  });

  data.scheduled = kept;
  saveRecordings(data);
}

// ---------------- IPC handlers ----------------

ipcMain.handle('recordings:getAll', () => publicRecordings());

ipcMain.on('app:version', (event) => { event.returnValue = app.getVersion(); });

// ---------------- Media (probe + on-the-fly conversion with ffmpeg) ----------------

let media = null;
function mediaService() {
  if (!media) {
    media = createMediaService(app, (id) => {
      const rec = loadRecordings().completed.find((r) => r.id === id);
      return rec && fs.existsSync(rec.filePath) ? rec.filePath : null;
    });
  }
  return media;
}
ipcMain.handle('media:probe', (event, src) => mediaService().probe(src));
ipcMain.handle('media:streamBase', () => mediaService().streamBase());
ipcMain.handle('media:subsBase', () => mediaService().subsBase());
ipcMain.handle('media:startInfo', (event, src, ss, start, audio) => mediaService().startInfo(src, ss, start, audio));
ipcMain.handle('media:available', () => mediaService().available);

function registerRecordingProtocol() {
  protocol.handle('parabol-rec', (request) => {
    const id = decodeURIComponent(new URL(request.url).pathname.replace(/^\//, '').replace(/\.ts$/, ''));
    const rec = loadRecordings().completed.find((r) => r.id === id);
    if (!rec || !fs.existsSync(rec.filePath)) return new Response('Not found', { status: 404 });
    return net.fetch(pathToFileURL(rec.filePath).toString());
  });
}

/**
 * Many Xtream servers don't send CORS headers. The app's own page (file://) must still be able
 * to read the API and the HLS/MPEG-TS streams, so add the header when a response lacks it.
 */
function allowCrossOriginResponses() {
  session.defaultSession.webRequest.onHeadersReceived({ urls: ['http://*/*', 'https://*/*'] }, (details, callback) => {
    const headers = details.responseHeaders || {};
    const has = Object.keys(headers).some((k) => k.toLowerCase() === 'access-control-allow-origin');
    if (!has) headers['Access-Control-Allow-Origin'] = ['*'];
    callback({ responseHeaders: headers });
  });
}

ipcMain.handle('recordings:schedule', (event, rec) => {
  const data = loadRecordings();
  data.scheduled.push(rec);
  saveRecordings(data);
  scheduleTimer(rec);
  notifyRenderer();
  if (Notification.isSupported()) {
    const when = new Date(rec.startEpochSeconds * 1000).toLocaleString('nl-NL');
    new Notification({
      title: 'Opname ingepland',
      body: `${rec.programTitle} (${rec.channelName}) — start ${when}`,
    }).show();
  }
  return true;
});

ipcMain.handle('recordings:cancel', (event, id) => {
  const timer = activeTimers.get(id);
  if (timer) clearTimeout(timer);
  const req = activeRequests.get(id);
  if (req) req.destroy();
  activeTimers.delete(id);
  activeRequests.delete(id);

  const data = loadRecordings();
  data.scheduled = data.scheduled.filter((r) => r.id !== id);
  saveRecordings(data);
  notifyRenderer();
  return true;
});

ipcMain.handle('recordings:deleteCompleted', (event, id) => {
  const data = loadRecordings();
  const rec = data.completed.find((r) => r.id === id);
  if (rec) {
    try { fs.unlinkSync(rec.filePath); } catch (e) { /* already gone */ }
  }
  data.completed = data.completed.filter((r) => r.id !== id);
  saveRecordings(data);
  notifyRenderer();
  return true;
});

ipcMain.handle('recordings:openFolder', () => {
  fs.mkdirSync(recordingsDir(), { recursive: true });
  shell.openPath(recordingsDir());
});

// ---------------- Window & tray ----------------

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 600,
    backgroundColor: '#0E1116',
    // macOS takes the icon from the app bundle; Windows uses the .ico.
    ...(isMac ? {} : { icon: path.join(__dirname, 'build', 'icon.ico') }),
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.js'),
    },
  });

  Menu.setApplicationMenu(isMac ? buildMacMenu() : null);
  mainWindow.loadFile(path.join(__dirname, 'app', 'index.html'));

  // Closing the window hides it instead of quitting, so scheduled recordings
  // keep running in the background (tray / menu bar). Quit fully via the tray
  // menu, or on macOS with Cmd+Q.
  mainWindow.on('close', (e) => {
    if (!app.isQuiting) {
      e.preventDefault();
      mainWindow.hide();
    }
  });
}

/**
 * macOS always shows a menu bar. Without it, Cmd+Q, Cmd+C/V (e.g. pasting the server URL
 * at login) and full screen don't work, so give the app the standard menus.
 */
function buildMacMenu() {
  return Menu.buildFromTemplate([
    { role: 'appMenu' },
    { role: 'editMenu' },
    {
      label: 'Weergave',
      submenu: [{ role: 'reload', label: 'Opnieuw laden' }, { type: 'separator' }, { role: 'togglefullscreen', label: 'Volledig scherm' }],
    },
    {
      label: 'Opnames',
      submenu: [{
        label: 'Map met opnames openen',
        click: () => { fs.mkdirSync(recordingsDir(), { recursive: true }); shell.openPath(recordingsDir()); },
      }],
    },
    { role: 'windowMenu' },
  ]);
}

function createTray() {
  // macOS menu bar: a black-and-transparent "template" image that adapts to light/dark.
  const trayIcon = isMac ? 'trayTemplate.png' : process.platform === 'win32' ? 'icon.ico' : 'icon.png';
  tray = new Tray(path.join(__dirname, 'build', trayIcon));
  tray.setToolTip('Parabol');
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: 'Openen', click: () => { if (mainWindow) mainWindow.show(); } },
      { label: 'Map met opnames openen', click: () => { fs.mkdirSync(recordingsDir(), { recursive: true }); shell.openPath(recordingsDir()); } },
      { type: 'separator' },
      { label: 'Afsluiten', click: () => { app.isQuiting = true; app.quit(); } },
    ])
  );
  tray.on('click', () => { if (mainWindow) mainWindow.show(); });
}

if (gotSingleInstanceLock) {
  app.whenReady().then(() => {
    registerRecordingProtocol();
    allowCrossOriginResponses();
    createWindow();
    createTray();
    resumeRecordingsOnStartup();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
      else mainWindow.show();
    });
  });

  app.on('before-quit', () => {
    app.isQuiting = true;
    if (media) media.shutdown();
  });

  app.on('window-all-closed', () => {
    // Intentionally does nothing: the app stays alive in the tray so scheduled
    // recordings keep running. Quit via the tray menu's "Afsluiten".
  });
}
