const { app, BrowserWindow, Menu, ipcMain, session } = require('electron');
const path = require('path');
const fs = require('fs');
const { createMediaService } = require('./media');

// Make the PORTABLE build genuinely self-contained: keep all app data (login,
// favorites, lists) in a folder next to the .exe itself, rather than in
// the OS's roaming profile. electron-builder sets PORTABLE_EXECUTABLE_DIR only
// for the portable target; the installed (Setup) version is unaffected and
// keeps using the normal per-user AppData location.
if (process.env.PORTABLE_EXECUTABLE_DIR) {
  app.setPath('userData', path.join(process.env.PORTABLE_EXECUTABLE_DIR, 'ParabolData'));
}

const isMac = process.platform === 'darwin';

let mainWindow = null;

// Prevent a second launch from starting a second process that reads/writes the same
// storage at the same time: the running window is brought forward instead.
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

// ---------------- IPC handlers ----------------

// ---------- Content kept on disk per account (shown at once on the next start) ----------

function contentFile(profileId) {
  return path.join(app.getPath('userData'), 'content', String(profileId).replace(/[^A-Za-z0-9_-]/g, '_') + '.json');
}
ipcMain.handle('content:load', (event, profileId) => {
  try { return JSON.parse(fs.readFileSync(contentFile(profileId), 'utf-8')); } catch (e) { return null; }
});
ipcMain.handle('content:save', (event, profileId, data) => {
  try {
    const file = contentFile(profileId);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file + '.tmp', JSON.stringify(data));
    fs.renameSync(file + '.tmp', file); // replace in one step, never half a file
  } catch (e) { /* best effort */ }
});
ipcMain.handle('content:delete', (event, profileId) => {
  try { fs.unlinkSync(contentFile(profileId)); } catch (e) { /* not there */ }
});

ipcMain.on('app:version', (event) => { event.returnValue = app.getVersion(); });

// ---------------- Media (probe + on-the-fly conversion with ffmpeg) ----------------

let media = null;
function mediaService() {
  if (!media) {
    media = createMediaService(app, () => null);
  }
  return media;
}
ipcMain.handle('media:probe', (event, src) => mediaService().probe(src));
ipcMain.handle('media:streamBase', () => mediaService().streamBase());
ipcMain.handle('media:subsBase', () => mediaService().subsBase());
ipcMain.handle('media:startInfo', (event, src, ss, start, audio) => mediaService().startInfo(src, ss, start, audio));
ipcMain.handle('media:available', () => mediaService().available);

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

// ---------------- Window ----------------

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
    { role: 'windowMenu' },
  ]);
}

if (gotSingleInstanceLock) {
  app.whenReady().then(() => {
    allowCrossOriginResponses();
    createWindow();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
      else mainWindow.show();
    });
  });

  app.on('before-quit', () => {
    if (media) media.shutdown();
  });

  // Closing the window quits on Windows; on the Mac the app stays in the Dock, as usual there.
  app.on('window-all-closed', () => {
    if (!isMac) app.quit();
  });
}
