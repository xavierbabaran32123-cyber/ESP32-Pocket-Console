# Mask Maze — Tomb of Mask Pocket Console (Android & Windows)

[![Download Android APK](https://img.shields.io/badge/Download_Android-MaskMaze.apk-00E676?style=for-the-badge&logo=android&logoColor=black)](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/releases/latest/download/MaskMaze.apk)
[![Download Windows EXE](https://img.shields.io/badge/Download_Windows-MaskMaze--Windows.exe-00B0FF?style=for-the-badge&logo=windows&logoColor=white)](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/releases/latest/download/MaskMaze-Windows.exe)
[![Build & Release Android & Windows](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/actions/workflows/build-apk.yml/badge.svg)](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/actions/workflows/build-apk.yml)

Bluetooth LE companion display and real-time chiptune audio synthesizer for **Tomb of Mask ESP32 Pocket Console** on **Android** and **Windows 10/11**.

---

## Download App (Scan QR Code or Click Direct Link)

| Android (7.0+ APK) | Windows 10/11 (64-bit Portable `.exe`) |
| :---: | :---: |
| <a href="https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/releases/latest/download/MaskMaze.apk"><img src="docs/apk-download-qr.svg" alt="Scan QR code to download MaskMaze.apk" width="210" height="210" /></a> | <a href="https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/releases/latest/download/MaskMaze-Windows.exe"><img src="docs/windows-download-qr.svg" alt="Scan QR code to download MaskMaze-Windows.exe" width="210" height="210" /></a> |
| [**Download `MaskMaze.apk`**](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/releases/latest/download/MaskMaze.apk) | [**Download `MaskMaze-Windows.exe`**](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/releases/latest/download/MaskMaze-Windows.exe) |
| Scan with your phone camera or tap to install | Standalone portable app — no installer needed |

- **All Releases:** [**View GitHub Releases**](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/releases)
- **In-App Share:** Tap the **QR Code** button in the top bar of the Android app to switch between the **Android APK** and **Windows EXE** QR codes at any time.

### Alternative — GitHub Actions Build Artifacts
1. Open the **[Actions](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/actions/workflows/build-apk.yml)** tab in this repository.
2. Select the latest green **Build & Release Android & Windows** run.
3. Scroll down to **Artifacts** and download **`MaskMaze-APK`** (Android) or **`MaskMaze-Windows`** (Windows 64-bit).

---

## Features

- **Live ESP32 BLE Companion (Nordic UART Service)**
  - Connects to MaskMaze ESP32 consoles on Android and Windows 10/11 over Bluetooth LE:
    - Service UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
    - TX Characteristic UUID: `6e400003-b5a3-f393-e0a9-e50e24dcca9e`
  - Decodes real-time binary telemetry (`M`, `E`, `R`, `N`, `n`, `L`, `J`, `S`, `D`, `d`, `b`, `T`, `F`) with automatic BLE reconnection.
- **60fps Pixel-Art Companion Renderer**
  - Renders all 45 stage-based dungeons across 10 color themes and 15 hero masks.
  - Renders Endless rising-tide dungeon mode with Shield, Freeze, Magnet power-ups, and boost shafts.
  - Renders built-in Arcade modes streamed from the console: **Jet Fighter**, **Dot Maze**, and **Blocks**.
  - Clean borderless full-screen mode (exit via `Esc`, `F11`, or double-click on Windows; Back gesture or double-tap on Android).
- **Real-Time Chiptune Audio Synthesizer**
  - Pulse, triangle, sine, sawtooth, and filtered noise voices with 3 BGM tracks, 21 sound effects, BGM ducking, and rising-tide rumble.

---

## Building Locally from Source

### Android (`MaskMaze.apk`)
1. Ensure `.env` exists and decode the included debug signing keystore in the project root:
   ```bash
   [ -f .env ] || cp .env.example .env
   tr -d '[:space:]' < debug.keystore.base64 | base64 -d > debug.keystore
   ```
2. Build the signed debug APK with Gradle `9.3.1` (JDK 21):
   ```bash
   gradle :app:assembleDebug
   ```
   The signed APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Windows 10/11 (`MaskMaze-Windows.exe`)
1. Install [Node.js 20+](https://nodejs.org/) on Windows and run:
   ```bash
   cd windows-desktop
   npm install
   npm run dist:win
   ```
2. The standalone portable executable will be generated at `windows-desktop/dist/MaskMaze-Windows.exe`.

### Optional Release Keystore Secrets (GitHub Actions)
By default, `.github/workflows/build-apk.yml` signs `MaskMaze.apk` automatically so zero setup is required. To sign Android release builds with your own upload keystore on GitHub, add these repository secrets under **Settings → Secrets and variables → Actions**:
- `RELEASE_KEYSTORE_BASE64` — Base64-encoded `.jks` keystore file
- `STORE_PASSWORD` — Keystore password
- `KEY_PASSWORD` — Key password (for alias `upload`)
