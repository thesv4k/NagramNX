# NagramNX

<p align="center">
  <img src="https://raw.githubusercontent.com/thesv4k/NagramNX/refs/heads/main/assets/logos/nagram_blue_round.png" width="128" height="128" alt="NagramNX" />
</p>

<p align="center">
  <b>Форк Telegram для Android с упором на обход блокировок и работу через прокси.</b>
</p>

<p align="center">
  <a href="https://github.com/thesv4k/NagramNX/releases"><img src="https://img.shields.io/github/v/release/thesv4k/NagramNX?style=flat-square&color=blue" alt="Релиз" /></a>
  <a href="https://github.com/thesv4k/NagramNX/actions"><img src="https://img.shields.io/github/actions/workflow/status/thesv4k/NagramNX/build-release.yml?style=flat-square" alt="Статус сборки" /></a>
  <a href="https://github.com/thesv4k/NagramNX/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v3-green.svg?style=flat-square" alt="Лицензия" /></a>
</p>

---

## Что умеет

### Встроенный TG-WS-Proxy (Flowseal)
Локальный MTProto-прокси, который оборачивает трафик в WebSocket и прокидывает через TLS — провайдер видит обычный HTTPS, а не Telegram. Работает прямо на устройстве, без внешних серверов. Порт и секрет генерируются автоматически при каждом запуске.

### Sing-box ядро (v1.13.19)
Полноценный прокси-движок sing-box вшит в приложение. Поддерживаемые протоколы:
- **VLESS + Reality** (`xtls-rprx-vision`) — маскировка под настоящие сайты без своих сертификатов
- **VLESS + HTTPUpgrade** — HTTP Upgrade поверх TLS
- **VLESS + WebSocket** — классический WS-транспорт, работает через CDN
- **VLESS + gRPC** — мультиплексированный HTTP/2 транспорт
- **Trojan** и **Shadowsocks**

DNS-запросы идут через собственный резолвер sing-box — провайдерский DNS не может подменить ответы.

### Импорт ссылок из буфера обмена
Копируешь пачку ссылок (`vless://`, `trojan://`, `ss://`, `tg://proxy`, `https://t.me/proxy`...) — приложение само их распарсит и добавит все разом. Эмодзи-флаги и названия серверов корректно декодируются.

### Авто-ротация прокси
Если текущий прокси залагал или отвалился — клиент автоматически переключится на следующий рабочий. Работает для всех типов: MTProto, VLESS, Trojan, SOCKS5. Таймаут настраивается (5с / 10с / 15с / 30с / 60с).

### Остальное (из Nagram)
- Ghost mode (чтение без «прочитано», скрытие набора текста)
- Логи редактирования и удаления сообщений
- Кастомные шрифты, темы, режим планшета
- Уведомления через GMS / UnifiedPush

---

## Скачать

Готовые APK лежат в [**Releases**](https://github.com/thesv4k/NagramNX/releases):

| Архитектура | Для чего | Ссылка |
| :--- | :--- | :--- |
| `arm64-v8a` | Телефоны и планшеты (64-бит) | [Скачать](https://github.com/thesv4k/NagramNX/releases/latest) |
| `x86_64` | Эмуляторы, Chromebook | [Скачать](https://github.com/thesv4k/NagramNX/releases/latest) |

### ⚠️ Первый вход: fraud prevention

Telegram может разлогинить все сессии, если при входе в новый клиент у тебя **сменился IP** (например, включён/выключен VPN). Это **не баг клиента** — так работает защита от фрода в самом Telegram.

Чтобы этого избежать:
1. Входи в NagramNX с **того же IP**, на котором сейчас работает твой основной клиент.
2. Если используешь VPN — либо включи его в обоих клиентах, либо выключи в обоих.
3. Если всё-таки выкинуло — просто зайди заново с того же IP/сети.

---

## 🔒 Безопасность и открытость

NagramNX полностью открыт (Open Source). Мы гарантируем, что приложение не содержит скрытых закладок, телеметрии и не передаёт ваши данные третьим лицам:

1. **Прозрачный код:**
   Все изменения относительно оригинального NagramX можно посмотреть в один клик:
   👉 [**Сравнить diff с upstream NagramX**](https://github.com/thesv4k/NagramNX/compare/risin42:NagramX:dev...thesv4k:NagramNX:main)
   Добавлены только `SingBoxManager.kt`, `VlessUriParser.kt`, `ProxyRotationController.java` и нативный код `tg-ws-proxy`.

2. **Как проверить трафик самостоятельно (для параноиков):**
   - Установите open-source сниффер трафика без рута [**PCAPdroid**](https://github.com/emanuele-f/PCAPdroid).
   - Выберите `NagramNX` в качестве отслеживаемого приложения.
   - Убедитесь, что соединения идут **только** на официальные дата-центры Telegram (`149.154.167.*`, `91.108.*` и т.д.), ваш локальный порт `127.0.0.1` и указанный вами прокси. Никаких левых хостов, аналитики и трекеров.

3. **Контрольные суммы релиза v12.10.0 (1261):**
   | Файл | SHA-256 |
   | :--- | :--- |
   | `NagramNX-v12.10.0(1261)-arm64-v8a.apk` | `a060a5afda2036f1aafe20efb25901b52987be5f9cd441b6ed15a3c0362d50fa` |
   | `NagramNX-v12.10.0(1261)-x86_64.apk` | `a85ccf7f8d3890852f70be22e2c3787c4acaad80f44c38a55d1858a4866eed5f` |

   *SHA-256 отпечаток сертификата подписи:*
   `DC:15:C7:52:A3:52:FE:69:79:94:6B:41:68:C8:9A:42:00:2B:97:F7:C0:3D:D5:11:1E:73:5D:8F:DD:20:42:C0`

---

## Сборка из исходников

Понадобится: JDK 21+, Android SDK 35, NDK 27+, CMake 3.22+

1. Клонируем с сабмодулями:
   ```bash
   git clone --recursive https://github.com/thesv4k/NagramNX.git
   cd NagramNX
   ```

2. Получаем `APP_ID` и `APP_HASH` на [my.telegram.org](https://my.telegram.org/auth), прописываем в `local.properties`:
   ```properties
   TELEGRAM_APP_ID=12345678
   TELEGRAM_APP_HASH=0123456789abcdef0123456789abcdef
   ```

3. Собираем:
   ```bash
   ./gradlew assembleRelease
   ```
   APK будут в `TMessagesProj/build/outputs/apk/release/`.

---

## Благодарности

- [Flowseal / tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) и [amurcanov / tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android)
- [SagerNet / sing-box](https://github.com/SagerNet/sing-box)
- [NagramX](https://github.com/risin42/NagramX)
- [Nagram](https://github.com/NextAlone/Nagram)
- [Nekogram](https://github.com/Nekogram/Nekogram)
- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Telegram for Android](https://github.com/DrKLO/Telegram)

---

## Лицензия

[GNU General Public License v3.0](LICENSE)
