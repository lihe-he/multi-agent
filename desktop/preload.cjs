const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('workbench', Object.freeze({
  getConnection: () => ipcRenderer.invoke('workbench:connection'),
  selectDirectory: () => ipcRenderer.invoke('workbench:directory'),
  openPath: path => ipcRenderer.invoke('workbench:open-path', path),
  getVersion: () => ipcRenderer.invoke('workbench:version'),
  desktopPreview: Object.freeze({
    attach: (projectId, sessionId) => ipcRenderer.invoke('preview:attach', projectId, sessionId),
    action: input => ipcRenderer.invoke('preview:action', input),
    onEvent: callback => {
      const listener = (_event, value) => callback(value);
      ipcRenderer.on('preview:event', listener);
      return () => ipcRenderer.removeListener('preview:event', listener);
    },
  }),
}));
