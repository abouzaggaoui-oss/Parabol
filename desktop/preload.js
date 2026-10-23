const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('parabolAPI', {
  version: ipcRenderer.sendSync('app:version'),
  probeMedia: (src) => ipcRenderer.invoke('media:probe', src),
  mediaStreamBase: () => ipcRenderer.invoke('media:streamBase'),
  mediaSubsBase: () => ipcRenderer.invoke('media:subsBase'),
  mediaStartInfo: (src, ss, start, audio) => ipcRenderer.invoke('media:startInfo', src, ss, start, audio),
  mediaAvailable: () => ipcRenderer.invoke('media:available'),
  scheduleRecording: (recording) => ipcRenderer.invoke('recordings:schedule', recording),
  cancelRecording: (id) => ipcRenderer.invoke('recordings:cancel', id),
  deleteCompletedRecording: (id) => ipcRenderer.invoke('recordings:deleteCompleted', id),
  getRecordings: () => ipcRenderer.invoke('recordings:getAll'),
  openRecordingsFolder: () => ipcRenderer.invoke('recordings:openFolder'),
  onRecordingsChanged: (callback) => {
    ipcRenderer.on('recordings:changed', (_event, data) => callback(data));
  },
});
