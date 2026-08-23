# NagramNX

<p align="center">
  <img src="TMessagesProj/src/main/res/drawable-xxxhdpi/ic_launcher.png" width="128" height="128" alt="NagramNX Logo" />
</p>

<p align="center">
  <b>Next-generation Telegram Android client with advanced censorship-circumvention tools and smart proxy routing.</b>
</p>

<p align="center">
  <a href="https://github.com/thesv4k/NagramNX/releases"><img src="https://img.shields.io/github/v/release/thesv4k/NagramNX?style=flat-square&color=blue" alt="Latest Release" /></a>
  <a href="https://github.com/thesv4k/NagramNX/actions"><img src="https://img.shields.io/github/actions/workflow/status/thesv4k/NagramNX/staging.yml?style=flat-square" alt="Build Status" /></a>
  <a href="https://github.com/thesv4k/NagramNX/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v3-green.svg?style=flat-square" alt="License" /></a>
</p>

---

## ⚡ Key Features

### 🛡 Built-in Flowseal TG-WS-Proxy
- **No external server required**: Runs locally as an embedded native service on Android.
- **WebSocket obfuscation**: Wraps standard MTProto Telegram traffic into innocent-looking WebSockets over TLS to bypass Deep Packet Inspection (DPI) and regional ISP blocks.
- **Dynamic port & random secret**: Allocates random unused ports automatically with cryptographic secret generation on each activation.

### 🚀 Embedded Sing-box Core (v1.13.19)
NagramNX embeds the high-performance **Sing-box** universal proxy engine:
- **VLESS + Reality XTLS** (`xtls-rprx-vision`): Next-gen TLS camouflage simulating legitimate websites (no certificate configuration needed).
- **VLESS + HTTPUpgrade TLS**: Standard RFC-compliant HTTP Upgrade transport.
- **VLESS + WebSocket TLS**: Universal WebSocket transport compatible with Cloudflare and CDNs.
- **VLESS + gRPC TLS**: High-throughput HTTP/2 multiplexed transport.
- **Trojan TLS** & **Shadowsocks**: Native Trojan password parsing and Shadowsocks routing.
- **Built-in Standalone DNS Engine**: Self-contained `ipv4_only` resolver inside Sing-box routing, preventing ISP DNS hijacking on Android.

### 📋 Smart Multi-Link Clipboard Import
- Import entire blocks of proxy links (`vless://`, `trojan://`, `ss://`, `tg://`, `https://t.me/proxy...`) in one tap directly from your clipboard.
- Regex URL parser with automatic percent-decoding for emoji server names, country flags, and remark tags (`#🇪🇪 EE | Reality`).

### 🔄 Fixed & Reliable Proxy Auto-Rotation
- Real-time connection stall detection across **all proxy protocols** (MTProto, VLESS, Trojan, SOCKS5).
- Configurable timeout rotation (5s, 10s, 15s, 30s, 60s) with automatic failover to the best working server by lowest latency ping.

### 🥷 Privacy & Customization (Nagram Heritage)
- Ghost mode (read messages without sending read receipts, hide typing status).
- Message edit & delete history logs.
- Custom fonts, themes, tablet mode, and sticker management.
- Google Play Services / UnifiedPush notification support.

---

## 📥 Download

Pre-built release APKs are available on the [**Releases Page**](https://github.com/thesv4k/NagramNX/releases):

| Architecture | Description | Download |
| :--- | :--- | :--- |
| **`arm64-v8a`** | Modern Android smartphones and tablets (64-bit) | [Download Latest APK](https://github.com/thesv4k/NagramNX/releases/latest) |
| **`x86_64`** | Android emulators & Chromebooks (64-bit x86) | [Download Latest APK](https://github.com/thesv4k/NagramNX/releases/latest) |

---

## 🛠 Compilation Guide

### Requirements
- JDK 21+
- Android SDK 35 (Build tools 35.0.0+)
- Android NDK 27+
- CMake 3.22+

### Steps

1. **Clone the repository with submodules:**
   ```bash
   git clone --recursive https://github.com/thesv4k/NagramNX.git NagramNX
   cd NagramNX
   ```

2. **Configure Telegram API credentials:**
   Obtain `TELEGRAM_APP_ID` and `TELEGRAM_APP_HASH` from [my.telegram.org](https://my.telegram.org/auth) and create `local.properties`:
   ```properties
   TELEGRAM_APP_ID=12345678
   TELEGRAM_APP_HASH=0123456789abcdef0123456789abcdef
   ```

3. **Build the APK:**
   ```bash
   ./gradlew assembleRelease
   ```
   The built APKs will be located in `TMessagesProj/build/outputs/apk/release/`.

---

## 🌟 Acknowledgments

NagramNX is built on top of incredible open-source projects and communities:
- [Flowseal / tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) & [amurcanov / tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android)
- [SagerNet / sing-box](https://github.com/SagerNet/sing-box)
- [NagramX / risin42](https://github.com/risin42/NagramX)
- [Nagram / NextAlone](https://github.com/NextAlone/Nagram)
- [Nekogram](https://github.com/Nekogram/Nekogram)
- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Telegram for Android](https://github.com/DrKLO/Telegram)

---

## 📜 License

NagramNX is licensed under the [GNU General Public License v3.0](LICENSE).
