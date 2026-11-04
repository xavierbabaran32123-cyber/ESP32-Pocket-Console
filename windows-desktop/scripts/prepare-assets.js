const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

const ROOT_DIR = path.resolve(__dirname, "..", "..");
const SRC_HTML = path.join(
  ROOT_DIR,
  "public",
  "assets",
  "aistudio",
  "tomb_of_mask_esp32_pocket_console_9e3a66c9.html"
);
const OUT_HTML = path.resolve(__dirname, "..", "index.html");
const OUT_ICON = path.resolve(__dirname, "..", "icon.png");

// ---------------------------------------------------------------------------
// 1. Generate 512x512 RGBA PNG App Icon (Pure Node.js, zero native deps)
// ---------------------------------------------------------------------------
const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let k = 0; k < 8; k++) {
    c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
  }
  crcTable[n] = c >>> 0;
}

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, "ascii");
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}

function insideRoundedRect(x, y, x0, y0, w, h, r) {
  if (x < x0 || x >= x0 + w || y < y0 || y >= y0 + h) return false;
  const cx = x < x0 + r ? x0 + r : x > x0 + w - 1 - r ? x0 + w - 1 - r : x;
  const cy = y < y0 + r ? y0 + r : y > y0 + h - 1 - r ? y0 + h - 1 - r : y;
  const dx = x - cx;
  const dy = y - cy;
  return dx * dx + dy * dy <= r * r;
}

function generateIconPng() {
  const W = 512;
  const H = 512;
  const raw = Buffer.alloc(H * (1 + W * 4));

  const HERO0 = [
    "0000000000000000", "0000000000000000", "0000000000000000", "007F000000000000",
    "01FFC00000000000", "01FFC00000000000", "01FFC00000000000", "07F3400000000000",
    "07F3400000000000", "0773400000000000", "007FC00000000000", "077F800000000000",
    "1F00000000000000", "1F7E000000000000", "3CBE000000000000", "1DEC000000000000",
    "01F7000000000000", "01C7000000000000", "03C7800000000000", "0386000000000000"
  ].map((h) => BigInt("0x" + h));

  const heroBit = (gx, gy) => {
    if (gx < 0 || gx >= 20 || gy < 0 || gy >= 20) return false;
    return ((HERO0[gy] >> BigInt(63 - gx)) & 1n) === 1n;
  };

  const setPx = (x, y, r, g, b, a) => {
    if (x < 0 || x >= W || y < 0 || y >= H) return;
    const off = y * (1 + W * 4) + 1 + x * 4;
    raw[off] = r;
    raw[off + 1] = g;
    raw[off + 2] = b;
    raw[off + 3] = a;
  };

  for (let y = 0; y < H; y++) {
    raw[y * (1 + W * 4)] = 0; // filter = None
    for (let x = 0; x < W; x++) {
      if (!insideRoundedRect(x, y, 8, 8, 496, 496, 104)) {
        setPx(x, y, 0, 0, 0, 0);
        continue;
      }
      if (!insideRoundedRect(x, y, 24, 24, 464, 464, 90)) {
        // Neon magenta outer border (#ff4fb8)
        setPx(x, y, 255, 79, 184, 255);
      } else if (!insideRoundedRect(x, y, 36, 36, 440, 440, 80)) {
        // Deep violet bevel (#461e64)
        setPx(x, y, 70, 30, 100, 255);
      } else {
        // Tomb obsidian background (#0b0b1a)
        setPx(x, y, 11, 11, 26, 255);
      }
    }
  }

  // Subtle decorative arcade dots in corners
  const dots = [
    [76, 76], [436, 76], [76, 436], [436, 436]
  ];
  for (const [cx, cy] of dots) {
    for (let dy = -8; dy < 8; dy++) {
      for (let dx = -8; dx < 8; dx++) {
        setPx(cx + dx, cy + dy, 95, 227, 161, 255);
      }
    }
  }

  // 20x20 hero mask scaled 16x (320x320) centered at (96, 96)
  const S = 16;
  const OX = 96;
  const OY = 96;

  // Purple pixel shadow
  for (let gy = 0; gy < 20; gy++) {
    for (let gx = 0; gx < 20; gx++) {
      if (!heroBit(gx, gy)) continue;
      for (let py = 0; py < S; py++) {
        for (let px = 0; px < S; px++) {
          setPx(OX + gx * S + px + 10, OY + gy * S + py + 12, 70, 30, 100, 255);
        }
      }
    }
  }

  // Golden mask sprite (#ffe600)
  for (let gy = 0; gy < 20; gy++) {
    for (let gx = 0; gx < 20; gx++) {
      if (!heroBit(gx, gy)) continue;
      for (let py = 0; py < S; py++) {
        for (let px = 0; px < S; px++) {
          setPx(OX + gx * S + px, OY + gy * S + py, 255, 230, 0, 255);
        }
      }
    }
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(W, 0);
  ihdr.writeUInt32BE(H, 4);
  ihdr[8] = 8; // 8-bit depth
  ihdr[9] = 6; // RGBA
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  const compressed = zlib.deflateSync(raw, { level: 9 });
  const png = Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk("IHDR", ihdr),
    pngChunk("IDAT", compressed),
    pngChunk("IEND", Buffer.alloc(0))
  ]);

  fs.writeFileSync(OUT_ICON, png);
  console.log(`Generated 512x512 Windows app icon: ${OUT_ICON} (${png.length} bytes)`);
}

// ---------------------------------------------------------------------------
// 2. Build Windows Desktop index.html from ESP32 Pocket Console HTML
// ---------------------------------------------------------------------------
function generateIndexHtml() {
  if (!fs.existsSync(SRC_HTML)) {
    throw new Error(`Source console HTML not found at ${SRC_HTML}`);
  }
  const baseHtml = fs.readFileSync(SRC_HTML, "utf8");
  const closeBodyIdx = baseHtml.lastIndexOf("</body>");
  if (closeBodyIdx === -1) {
    throw new Error("Could not find </body> in source console HTML");
  }

  const electronEnhancement = `
<style>
  #electronBleModal {
    position: fixed;
    inset: 0;
    z-index: 9999;
    background: rgba(6, 6, 16, 0.82);
    backdrop-filter: blur(4px);
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 16px;
  }
  #electronBleModal[hidden] { display: none; }
  .ble-card {
    width: 100%;
    max-width: 420px;
    background: #14142b;
    border: 2px solid var(--panel-hi);
    border-radius: 18px;
    padding: 18px;
    box-shadow: 0 20px 50px rgba(0, 0, 0, 0.65);
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .ble-head {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .ble-pulse {
    width: 11px;
    height: 11px;
    border-radius: 50%;
    background: var(--accent);
    box-shadow: 0 0 0 0 rgba(255, 79, 184, 0.7);
    animation: blePulse 1.4s infinite;
    flex: none;
  }
  @keyframes blePulse {
    0% { box-shadow: 0 0 0 0 rgba(255, 79, 184, 0.7); }
    70% { box-shadow: 0 0 0 9px rgba(255, 79, 184, 0); }
    100% { box-shadow: 0 0 0 0 rgba(255, 79, 184, 0); }
  }
  .ble-title {
    font-size: 16px;
    font-weight: 700;
    color: #ffe600;
    margin: 0;
  }
  .ble-sub {
    font-size: 12px;
    color: #c8b8ff;
    margin: 0;
    line-height: 1.4;
  }
  .ble-filter {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    color: var(--ink);
    cursor: pointer;
    padding: 4px 2px;
  }
  .ble-filter input {
    accent-color: var(--accent);
    width: 15px;
    height: 15px;
    cursor: pointer;
  }
  .ble-list {
    min-height: 150px;
    max-height: 260px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 4px 0;
  }
  .ble-empty {
    margin: auto 0;
    text-align: center;
    font-size: 12px;
    color: #a899df;
    padding: 20px 12px;
    line-height: 1.5;
  }
  .ble-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    width: 100%;
    text-align: left;
    background: var(--panel);
    color: var(--ink);
    border: 1.5px solid var(--panel-hi);
    border-radius: 12px;
    padding: 10px 12px;
    cursor: pointer;
    font-family: inherit;
    transition: border-color 0.12s, background 0.12s;
  }
  .ble-item:hover {
    border-color: var(--accent);
    background: #222246;
  }
  .ble-item-name {
    font-size: 14px;
    font-weight: 600;
    color: #ffffff;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .ble-item-id {
    font-size: 11px;
    color: #9c8fd0;
    margin-top: 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .ble-badge {
    font-size: 10px;
    font-weight: 700;
    padding: 3px 7px;
    border-radius: 999px;
    background: rgba(95, 227, 161, 0.16);
    border: 1px solid var(--ok);
    color: var(--ok);
    flex: none;
  }
  .ble-actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 4px;
  }
</style>

<div id="electronBleModal" hidden>
  <div class="ble-card" role="dialog" aria-modal="true" aria-labelledby="bleModalTitle">
    <div class="ble-head">
      <span class="ble-pulse"></span>
      <h2 class="ble-title" id="bleModalTitle">Connect ESP32 Console</h2>
    </div>
    <p class="ble-sub" id="bleModalSub">Scanning Bluetooth LE for Nordic UART Service (MaskMaze)…</p>
    <label class="ble-filter">
      <input type="checkbox" id="bleShowAll" />
      <span>Show all nearby Bluetooth LE devices</span>
    </label>
    <div class="ble-list" id="bleDeviceList"></div>
    <div class="ble-actions">
      <button type="button" class="pill" id="bleRescanBtn">Rescan</button>
      <button type="button" class="pill" id="bleCancelBtn">Cancel</button>
    </div>
  </div>
</div>

<script>
(() => {
  const bridge = window.electronConsole;
  if (!bridge || !bridge.isElectron) return;

  const modal = document.getElementById("electronBleModal");
  const listEl = document.getElementById("bleDeviceList");
  const showAllCb = document.getElementById("bleShowAll");
  const rescanBtn = document.getElementById("bleRescanBtn");
  const cancelBtn = document.getElementById("bleCancelBtn");
  const subEl = document.getElementById("bleModalSub");
  const appEl = document.getElementById("app");
  const fsButton = document.getElementById("fsBtn");
  const stageEl = document.getElementById("stage");
  const hintBox = document.getElementById("hint");

  if (hintBox && !hintBox.textContent) {
    hintBox.textContent = "Click Connect to pair with your MaskMaze ESP32 console over Bluetooth LE. Press F11 for full screen.";
  }

  // Native borderless fullscreen with clean canvas (no floating minimize button; exit via Esc, F11, or double-click)
  if (fsButton) {
    fsButton.hidden = false;
    fsButton.title = "Full screen (F11 — press Esc or double-click to exit)";
    fsButton.addEventListener("click", (ev) => {
      ev.preventDefault();
      ev.stopImmediatePropagation();
      bridge.toggleFullscreen();
    }, true);
  }

  bridge.onFullscreenChanged((isFs) => {
    appEl.classList.toggle("fs", isFs);
    if (typeof layout === "function") setTimeout(layout, 50);
  });

  if (stageEl) {
    stageEl.addEventListener("dblclick", () => {
      if (appEl.classList.contains("fs")) {
        bridge.exitFullscreen();
      }
    });
  }

  let modalOpen = false;
  let showAllDevices = false;
  let restartAfterCancel = false;
  let knownDevices = [];

  function isLikelyConsole(name) {
    return /mask|maze|tomb|esp32|pocket|nus|uart/i.test(name || "");
  }

  function renderDevices() {
    listEl.innerHTML = "";
    if (!knownDevices.length) {
      const empty = document.createElement("div");
      empty.className = "ble-empty";
      empty.textContent = "Looking for nearby Pocket Console devices… Make sure your ESP32 is powered on and Windows Bluetooth is enabled.";
      listEl.appendChild(empty);
      return;
    }

    const sorted = [...knownDevices].sort((a, b) => {
      const aMatch = isLikelyConsole(a.deviceName) ? 1 : 0;
      const bMatch = isLikelyConsole(b.deviceName) ? 1 : 0;
      if (aMatch !== bMatch) return bMatch - aMatch;
      return String(a.deviceName || "").localeCompare(String(b.deviceName || ""));
    });

    for (const d of sorted) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "ble-item";

      const info = document.createElement("div");
      info.style.minWidth = "0";
      info.style.flex = "1";

      const title = document.createElement("div");
      title.className = "ble-item-name";
      title.textContent = d.deviceName || "ESP32 / BLE Device";

      const sub = document.createElement("div");
      sub.className = "ble-item-id";
      sub.textContent = d.deviceId || "";

      info.appendChild(title);
      info.appendChild(sub);
      btn.appendChild(info);

      if (!showAllDevices || isLikelyConsole(d.deviceName)) {
        const badge = document.createElement("span");
        badge.className = "ble-badge";
        badge.textContent = "ESP32";
        btn.appendChild(badge);
      }

      btn.addEventListener("click", () => {
        bridge.selectBleDevice(d.deviceId);
      });

      listEl.appendChild(btn);
    }
  }

  bridge.onBleDevices((devices) => {
    if (!modalOpen) return;
    const map = new Map();
    for (const d of devices || []) {
      if (d && d.deviceId) {
        map.set(d.deviceId, d);
      }
    }
    knownDevices = Array.from(map.values());
    renderDevices();
  });

  function openModal() {
    modalOpen = true;
    knownDevices = [];
    subEl.textContent = showAllDevices
      ? "Scanning all nearby Bluetooth LE devices…"
      : "Scanning Bluetooth LE for Nordic UART Service (MaskMaze)…";
    renderDevices();
    modal.hidden = false;
  }

  function closeModal() {
    modalOpen = false;
    modal.hidden = true;
  }

  cancelBtn.addEventListener("click", () => {
    restartAfterCancel = false;
    bridge.cancelBleScan();
    closeModal();
  });

  modal.addEventListener("click", (ev) => {
    if (ev.target === modal) {
      restartAfterCancel = false;
      bridge.cancelBleScan();
      closeModal();
    }
  });

  showAllCb.addEventListener("change", () => {
    showAllDevices = showAllCb.checked;
    if (modalOpen) {
      restartAfterCancel = true;
      bridge.cancelBleScan();
    }
  });

  rescanBtn.addEventListener("click", () => {
    if (modalOpen) {
      restartAfterCancel = true;
      bridge.cancelBleScan();
    }
  });

  window.addEventListener("keydown", (ev) => {
    if (ev.key === "Escape") {
      if (modalOpen) {
        ev.preventDefault();
        restartAfterCancel = false;
        bridge.cancelBleScan();
        closeModal();
      } else if (appEl.classList.contains("fs")) {
        ev.preventDefault();
        bridge.exitFullscreen();
      }
    }
  });

  if (navigator.bluetooth && typeof navigator.bluetooth.requestDevice === "function") {
    const origRequestDevice = navigator.bluetooth.requestDevice.bind(navigator.bluetooth);
    navigator.bluetooth.requestDevice = async function(options) {
      openModal();
      try {
        while (true) {
          restartAfterCancel = false;
          knownDevices = [];
          subEl.textContent = showAllDevices
            ? "Scanning all nearby Bluetooth LE devices…"
            : "Scanning Bluetooth LE for Nordic UART Service (MaskMaze)…";
          renderDevices();
          const reqOpts = showAllDevices
            ? { acceptAllDevices: true, optionalServices: [NUS_SERVICE] }
            : options;
          try {
            return await origRequestDevice(reqOpts);
          } catch (err) {
            if (restartAfterCancel) {
              await new Promise((r) => setTimeout(r, 80));
              continue;
            }
            throw err;
          }
        }
      } finally {
        closeModal();
      }
    };
  }
})();
</script>
`;

  const finalHtml =
    baseHtml.slice(0, closeBodyIdx) +
    electronEnhancement +
    baseHtml.slice(closeBodyIdx);

  fs.writeFileSync(OUT_HTML, finalHtml, "utf8");
  console.log(`Generated Windows desktop HTML: ${OUT_HTML} (${finalHtml.length} chars)`);
}

generateIconPng();
generateIndexHtml();
