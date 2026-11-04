# Mask Maze — Tomb of Mask Pocket Console (Android)

[![Download APK](https://img.shields.io/badge/Download_APK-MaskMaze.apk-00E676?style=for-the-badge&logo=android&logoColor=black)](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/releases/latest/download/MaskMaze.apk)
[![Build & Release Android APK](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/actions/workflows/build-apk.yml/badge.svg)](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/actions/workflows/build-apk.yml)

Native Android companion display, real-time chiptune audio synthesizer, and standalone playable Pocket Console for **Tomb of Mask ESP32**.

---

## Download & Install APK

Every push to `main` / `master` (or any `v*` version tag) automatically builds and signs **`MaskMaze.apk`** via GitHub Actions and publishes it to **GitHub Releases**.

### Direct One-Click Download
- **[Download Latest `MaskMaze.apk`](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/releases/latest/download/MaskMaze.apk)**
- **[View All GitHub Releases](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/releases)**

### Alternative — GitHub Actions Build Artifacts
1. Open the **[Actions](https://github.com/xavierbabaran32123-cyber/ESP32-Pocket-Console/actions/workflows/build-apk.yml)** tab in this repository.
2. Select the latest green **Build & Release Android APK** run.
3. Scroll down to **Artifacts** and download **`MaskMaze-APK`**.

---

## Features

- **Live ESP32 BLE Companion (Nordic UART Service)**
  - Connects to MaskMaze ESP32 consoles advertising or exposing Nordic UART Service:
    - Service UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
    - TX Characteristic UUID: `6e400003-b5a3-f393-e0a9-e50e24dcca9e`
  - Decodes real-time binary telemetry (`M`, `E`, `R`, `N`, `n`, `L`, `J`, `S`, `D`, `d`, `b`, `T`, `F`) with automatic BLE reconnection.
- **60fps Pixel-Art Engine**
  - 45 stage-based dungeons across 10 color themes and 15 unlockable hero masks.
  - Endless rising-tide dungeon mode with Shield, Freeze, Magnet power-ups, and boost shafts.
  - Built-in Arcade collection: **Jet Fighter**, **Dot Maze**, and **Blocks**.
- **22,050 Hz PCM Chiptune Audio Synthesizer**
  - Real-time pulse, triangle, sine, sawtooth, and filtered noise voices with 3 BGM tracks, 21 sound effects, BGM ducking, and rising-tide rumble.
- **Play Local Mode**
  - Play all 45 stages, Endless mode, Masks shop, and all 3 Arcade games directly on your Android phone or tablet (Android 7.0+ / API 24+) using swipe gestures or the on-screen D-Pad + A/B/Pause controls.

---

## Building Locally from Source

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

### Optional Release Keystore Secrets (GitHub Actions)
By default, `.github/workflows/build-apk.yml` signs `MaskMaze.apk` automatically so zero setup is required. To sign release builds with your own upload keystore on GitHub, add these repository secrets under **Settings → Secrets and variables → Actions**:
- `RELEASE_KEYSTORE_BASE64` — Base64-encoded `.jks` keystore file
- `STORE_PASSWORD` — Keystore password
- `KEY_PASSWORD` — Key password (for alias `upload`)
