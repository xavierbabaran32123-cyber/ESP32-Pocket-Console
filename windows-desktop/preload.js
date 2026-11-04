const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("electronConsole", {
  isElectron: true,
  onBleDevices: (handler) => {
    const listener = (_event, devices) => handler(devices);
    ipcRenderer.on("ble-devices", listener);
    return () => ipcRenderer.removeListener("ble-devices", listener);
  },
  selectBleDevice: (deviceId) => {
    ipcRenderer.send("ble-select", String(deviceId || ""));
  },
  cancelBleScan: () => {
    ipcRenderer.send("ble-cancel");
  },
  toggleFullscreen: () => {
    ipcRenderer.send("window-toggle-fs");
  },
  exitFullscreen: () => {
    ipcRenderer.send("window-exit-fs");
  },
  onFullscreenChanged: (handler) => {
    const listener = (_event, isFs) => handler(Boolean(isFs));
    ipcRenderer.on("fs-state", listener);
    return () => ipcRenderer.removeListener("fs-state", listener);
  }
});
