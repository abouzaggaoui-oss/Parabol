const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('parabolAPI', {
  version: ipcRenderer.sendSync('app:version'),
  probeMedia: (src) => ipcRenderer.invoke('media:probe', src),
  mediaStreamBase: () => ipcRenderer.invoke('media:streamBase'),
  mediaSubsBase: () => ipcRenderer.invoke('media:subsBase'),
  mediaStartInfo: (src, ss, start, audio) => ipcRenderer.invoke('media:startInfo', src, ss, start, audio),
  mediaAvailable: () => ipcRenderer.invoke('media:available'),
  loadContent: (profileId) => ipcRenderer.invoke('content:load', profileId),
  saveContent: (profileId, data) => ipcRenderer.invoke('content:save', profileId, data),
  deleteContent: (profileId) => ipcRenderer.invoke('content:delete', profileId),
});
