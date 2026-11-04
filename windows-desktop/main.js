const { app, BrowserWindow, ipcMain, Menu, session, shell } = require("electron");
const path = require("path");

// Enable Windows 10/11 WinRT Web Bluetooth and zero-latency WebAudio startup
app.commandLine.appendSwitch("enable-experimental-web-platform-features");
app.commandLine.appendSwitch("enable-web-bluetooth");
app.commandLine.appendSwitch("autoplay-policy", "no-user-gesture-required");

let mainWindow = null;
let pendingBleCallback = null;

function finishBleRequest(deviceId) {
  if (!pendingBleCallback) return;
  const cb = pendingBleCallback;
  pendingBleCallback = null;
  try {
    cb(deviceId);
  } catch (_) {
    // Ignore stale callback errors if chooser already closed
  }
}

function createWindow() {
  Menu.setApplicationMenu(null);

  mainWindow = new BrowserWindow({
    width: 920,
    height: 760,
    minWidth: 420,
    minHeight: 500,
    backgroundColor: "#0b0b1a",
    title: "Mask Maze — Pocket Console",
    icon: path.join(__dirname, "icon.png"),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  if (typeof session.defaultSession.setBluetoothPairingHandler === "function") {
    session.defaultSession.setBluetoothPairingHandler((_details, callback) => {
      callback({ confirmed: true });
    });
  }

  mainWindow.webContents.on("select-bluetooth-device", (event, deviceList, callback) => {
    event.preventDefault();
    pendingBleCallback = callback;
    if (!mainWindow.isDestroyed()) {
      mainWindow.webContents.send("ble-devices", Array.isArray(deviceList) ? deviceList : []);
    }
  });

  mainWindow.on("enter-full-screen", () => {
    if (!mainWindow.isDestroyed()) {
      mainWindow.webContents.send("fs-state", true);
    }
  });

  mainWindow.on("leave-full-screen", () => {
    if (!mainWindow.isDestroyed()) {
      mainWindow.webContents.send("fs-state", false);
    }
  });

  mainWindow.webContents.on("before-input-event", (event, input) => {
    if (input.type !== "keyDown") return;
    if (input.key === "F11") {
      event.preventDefault();
      mainWindow.setFullScreen(!mainWindow.isFullScreen());
    } else if (input.key === "Escape" && mainWindow.isFullScreen() && !pendingBleCallback) {
      event.preventDefault();
      mainWindow.setFullScreen(false);
    }
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url && /^https?:\/\//i.test(url)) {
      shell.openExternal(url);
    }
    return { action: "deny" };
  });

  mainWindow.on("closed", () => {
    finishBleRequest("");
    mainWindow = null;
  });

  mainWindow.loadFile(path.join(__dirname, "index.html"));
}

const gotSingleLock = app.requestSingleInstanceLock();
if (!gotSingleLock) {
  app.quit();
} else {
  app.on("second-instance", () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  ipcMain.on("ble-select", (_event, deviceId) => {
    finishBleRequest(String(deviceId || ""));
  });

  ipcMain.on("ble-cancel", () => {
    finishBleRequest("");
  });

  ipcMain.on("window-toggle-fs", () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.setFullScreen(!mainWindow.isFullScreen());
    }
  });

  ipcMain.on("window-exit-fs", () => {
    if (mainWindow && !mainWindow.isDestroyed() && mainWindow.isFullScreen()) {
      mainWindow.setFullScreen(false);
    }
  });

  app.whenReady().then(createWindow);

  app.on("window-all-closed", () => {
    app.quit();
  });
}
