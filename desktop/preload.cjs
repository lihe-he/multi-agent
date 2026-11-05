const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('workbench', Object.freeze({
  getConnection: () => ipcRenderer.invoke('workbench:connection'),
  selectDirectory: () => ipcRenderer.invoke('workbench:directory'),
  openPath: path => ipcRenderer.invoke('workbench:open-path', path),
  getVersion: () => ipcRenderer.invoke('workbench:version'),
}));
