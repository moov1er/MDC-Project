# MouseRelay — проект архитектуры (ПК ↔ Телефон по Wi-Fi)

Цель: при упирании курсора в правый край экрана ПК управление мышью «перетекает»
на телефон; на телефоне двигается оверлей-курсор, клики/скролл исполняются через
AccessibilityService. Обратный переход — когда курсор на телефоне уходит за левый край.

---

## 1. Что возможно, а что невозможно (честно)

### Возможно без root/ADB (легальными публичными API)

| Возможность | Механизм | Ограничения |
|---|---|---|
| Инжект тапов, свайпов, скролла на Android | `AccessibilityService.dispatchGesture()` (API 24+) | Работает только при включённом пользователем Accessibility. Нельзя отменить/перехватить жест до исполнения — сначала приложение получит событие, потом наш жест (для кликов это ок; для скролла — нормально). Нельзя инжектировать в некоторые защищённые экраны (системные диалоги безопасности, часть банковских приложений с `FLAG_SECURE`-защитой от доступа через a11y). |
| Оверлей-курсор поверх всего | `TYPE_ACCESSIBILITY_OVERLAY` из AccessibilityService | Не требует `SYSTEM_ALERT_WINDOW`. Не перехватывает touch-события (ставим `FLAG_NOT_TOUCHABLE` — нам это и нужно, клики всё равно шлёт a11y). Не виден на некоторых защищённых окнах. |
| Двигатель мыши «как второй монитор» | На ПК: низкоуровневый хук `WH_MOUSE_LL` + `SetCursorPos` | См. ниже про «блокировку курсора». |
| TCP-сервер на телефоне в фоне | Foreground service (`FOREGROUND_SERVICE_SPECIAL_USE` / Connected Device) | Android 10+ требует foreground-сервис для долгоживущего сокета; нужен `WAKE_LOCK` + исключение из battery optimization. |
| Точное относительное движение мыши на ПК | Raw Input (`WM_INPUT`, `RI_MOUSE`) или дельты из `MSLLHOOKSTRUCT` | Хук даёт дельты через `mouseData` (absolute) — проще использовать Raw Input для чистых relative-дельт. Для MVP хватит разницы координат. |
| Авторизация по токену | Пароль/токен в первом JSON-сообщении + опционально TLS (PSK) | Plain TCP в LAN — токен видно в сети; для MVP приемлемо, позже — TLS. |
| Не даём телефону уснуть во время сеанса | Wake lock в foreground-сервисе + `FLAG_KEEP_SCREEN_ON` на активити | Экран гаснет по таймауту, если пользователь не дал exempt от Doze; keep-screen-on работает, пока есть видимое окно (можно невидимое 1×1 activity). |

### Невозможно / частично без root/ADB — и альтернативы

1. **Полностью «спрятать» системный курсор Windows и эмулировать его движение только логически.**
   Реального скрытия курсора нет, но стандартный приём: при входе в режим мы
   каждый цикл принудительно ставим курсор в «якорь» у правого края (`SetCursorPos`)
   и берём дельты из движения. Курсор визуально «прилип» к краю — это и есть
   классическая реализация edge-warp (как в Barrier/Synergy/Mouse Without Borders).
   Альтернатива: `ShowCursor(false)` — счётчик скрытия, работает, но любое чужое
   показывание курсора ломает счётчик; якорь надёжнее.

2. **Инжект жеста «долгое нажатие с движением» (drag&drop) между приложениями** —
   `dispatchGesture` умеет, но многие приложения блокируют a11y-драги. MVP: тап,
   свайп-скролл, long-press. Drag acceptability зависит от целевого приложения.

3. **Гарантированно фоновый TCP-сервер при выключенном экране** — Some OEM (MIUI,
   Samsung) убивают сокеты в deep-doze. Альтернатива: просить пользователя добавить
   приложение в исключения battery optimization (намерение `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   доступно из обычного приложения, но в Play Store ограничено — объясняем вручную).

4. **Автозапуск сервера после reboot без a11y-включения заново** — Accessibility-сервис
   система поднимает сама после включения пользователем; BroadcastReceiver на BOOT_COMPLETED
   может поднять foreground-сервер. Это легально. Но сам AccessibilityService пользователь
   включает один раз вручную — это unavoidable.

5. **Скролл «колесом» как непрерывное событие** — dispatchGesture — это discrete-жесты
   (свайпы). Эмулируем колесо серией коротких свайпов; ощущение близкое, но инерции
   системной нет. Альтернатива получше: `performAction(ACTION_SCROLL_*)`? Нет такого
   общего действия — остаётся свайп.

6. **Правый клик (long press)** — реального «правого клика» в Android нет;
   эмулируем long-press 500 мс. Поведение = контекстное меню, а не ПК-правый клик.

7. **Видео/зеркалирование экрана** — сознательно не делаем (по требованиям).

Итог: концепция полностью реализуема как MVP без root и ADB. Главные ручные шаги
пользователя: включить AccessibilityService, запустить сервер (foreground),
разрешить уведомления (для foreground-сервиса), желательно — battery exemption.

---

## 2. Архитектура

```
┌─────────────────────────── Windows PC (.NET 8) ───────────────────────┐
│  MouseRelay.WinAgent (WinForms tray app)                              │
│                                                                       │
│  ┌───────────┐   ┌──────────────┐   ┌────────────┐   ┌─────────────┐  │
│  │ MouseHook │──▶│ EdgeWatcher  │──▶│ SessionMgr │◀──│ TcpClient   │  │
│  │ (WH_MOUSE │   │ (right-edge  │   │ (state     │   │ + JSONL     │  │
│  │  _LL +    │   │  detection,  │   │  machine:  │   │ codec,      │  │
│  │  SetCursor│   │  warp-anchor)│   │  Idle/     │   │ token auth) │  │
│  │  Pos)     │   └──────────────┘   │  PhoneMode │   └──────┬──────┘  │
│  └───────────┘                      │  /Cooling  │          │         │
│        ▲                            └─────▲──────┘          │         │
│        │ deltas (dx,dy)                   │ exit(left_edge) │         │
│  ┌─────┴──────┐  hotkey Ctrl+Alt+M   ┌────┴────┐           │         │
│  │ ClickScroll│──────────────────────│Reconnect│◀──────────┘   TCP :8976
│  │ capture    │                       │ backoff │                       │
│  └────────────┘                        └─────────┘            ─────────┼──
└────────────────────────────────────────────────────────────────────────┘
                                                                        │
┌─────────────────────────── Android (Kotlin) ──────────────────────────┼───┐
│  MouseRelay.Android                                                   │   │
│  ┌────────────────┐  ┌──────────────────┐  ┌──────────────────────┐   │   │
│  │ ServerService  │◀─│ TcpServer        │◀─│ JsonLineCodec + Auth │   │   │
│  │ (Foreground,   │  │ (port 8976,      │  │ (token check, hello) │   │   │
│  │  WakeLock,     │  │  loopback only   │  └──────────────────────┘   │   │
│  │  keep screen)  │  │  within app...   │                             │   │
│  └───────┬────────┘  └────────┬─────────┘                             │   │
│          │ events             │                                       │   │
│  ┌───────▼────────────────────▼─────────┐   ┌───────────────────────┐  │   │
│  │ RelayAccessibilityService            │   │ MainActivity          │  │   │
│  │  • CursorOverlayView                 │   │  • IP/port/token UI   │  │   │
│  │    (TYPE_ACCESSIBILITY_OVERLAY,      │   │  • start/stop server  │  │   │
│  │     NOT_TOUCHABLE)                   │   │  • instructions       │  │   │
│  │  • GestureEngine                     │   │  • wake-lock window   │  │   │
│  │    (dispatchGesture: tap/longpress/  │   └───────────────────────┘  │   │
│  │     swipe-scroll)                    │        ┌──────────────────┐   │   │
│  │  • EdgeExitDetector (left edge→exit) │        │ BootReceiver     │   │   │
│  └──────────────────────────────────────┘        └──────────────────┘   │   │
└──────────────────────────────────────────────────────────────────────────┘
```

### Ключевые решения

- **Единая точка истины о состоянии** на ПК: `Idle → PhoneMode → CoolingDown(300 мс) → Idle`.
  Cooling-down нужен, чтобы обратный переход не «дёргался» (edge flapping).
- **Якорь на ПК**: в PhoneMode хук не блокирует ввод глобально (это было бы ловушкой),
  а каждый тик возвращает системный курсор в точку входа у правого края; все дельты
  считаются от движения мыши. Выход по hotkey всегда доступен, т.к. клавиатура не блокируется.
- **На телефоне позиция курсора** хранится в AccessibilityService; вход: x=0,
  y = y_ratio * screenHeight (пропорциональный перенос вертикали).
- **Разделение сервисов Android**: сокет живёт в foreground-сервисе, но исполнителем
  жестов/оверлея является AccessibilityService (он запускается системой). Сервисы
  общаются через локальный singleton + StateFlow (без Binder-сложностей).
- **Протокол**: newline-delimited JSON (JSONL) поверх TCP. Просто, отлаживается через
  `netcat`, легко заменить на MessagePack позже.

---

## 3. Протокол (v1, JSON Lines поверх TCP, порт 8976)

Каждое сообщение = одна строка JSON + `\n`. Кодировка UTF-8.

### Handshake / авторизация

ПК подключается к телефону (телефон — сервер). Первым сообщением ПК обязан прислать auth:

```jsonc
// ПК → телефон (первое сообщение, иначе disconnect через 3 сек)
{"type":"auth","token":"<sha256(token+nonce) или plain для MVP>","proto":1,"name":"DESKTOP-PC"}

// телефон → ПК
{"type":"auth_ok"}            // или {"type":"auth_fail","reason":"bad_token"} → close

// телефон → ПК (сразу после auth_ok)
{"type":"hello","width":1080,"height":2400,"density":420,"rotation":0}

// ПК → телефон (ack)
{"type":"hello_ack","screen_width":2560,"screen_height":1440}
```

Для MVP token отправляется plain (он одноразовый, генерируется при каждом запуске
сервера и показывается QR/текстом — «pin-код» как у KDE Connect). Это защищает от
посторонних в LAN, которые не знают код. Позже — TLS/SPKI-pinning.

### Основной рабочий обмен

| # | Направление | Сообщение | Поля | Смысл |
|---|---|---|---|---|
| 1 | ПК→Т | `enter` | `edge:"right"`, `y_ratio:0..1` | курсор вошёл в телефон |
| 2 | ПК→Т | `move` | `dx`,`dy` (int, px) | относительное движение |
| 3 | ПК→Т | `button` | `button:"left"/"right"`, `action:"down"/"up"` | нажатие/отпускание |
| 4 | ПК→Т | `scroll` | `dx`,`dy` (px, dy>0 = вниз) | колесо мыши |
| 5 | Т→ПК | `exit` | `reason:"left_edge"/"user"/"timeout"` | просит вернуть управление ПК |
| 6 | оба | `ping`/`pong` | `timestamp`:ms | heartbeat каждые 2 с, timeout 6 с |
| 7 | Т→ПК | `state` | `overlay:true/false` | подтверждение состояния оверлея |
| 8 | Т→ПК | `error` | `code`,`message` | например `gesture_not_allowed` |

Форматы ровно как в ТЗ (`enter/move/button/scroll/exit/ping/pong/hello` сохранены
совместимыми; добавлены `auth`, `auth_ok`, `hello_ack`, `state`, `error`).

### Semantics важных деталей

- `button down/up` для left: на `down` телефон запоминает позицию, на `up` выполняет
  `dispatchGesture(tap)` (чтобы fast click&click не терялся) либо drag, если позиция
  изменилась больше порога — это даст drag-to-select как бонус (зависит от приложения).
  Упрощённый MVP: `up` → тап в текущей позиции курсора.
- `button right down/up` → на `up`: long-press 500 мс.
- `scroll`: агрегируем дельты колёсика в окне 50 мс, транслируем в один свайп длиной `dy/120*300px`.
- Все сообщения, кроме `ping/pong/auth*`, игнорируются телефоном до получения `enter`.

---

## 4. Структура папок

```
mouserelay/
├── ARCHITECTURE.md                  (этот файл)
├── docs/PROTOCOL.md                 (спецификация сообщений, генерируется из §3)
├── shared/schema/protocol.schema.json   (JSON Schema для обеих сторон)
│
├── win/                             # Windows-агент, .NET 8 WinForms (tray)
│   ├── MouseRelay.sln
│   └── src/
│       ├── MouseRelay.App/          # трей, настройки, сборка всего
│       │   ├── MouseRelay.App.csproj
│       │   ├── Program.cs
│       │   ├── AppContext.cs        # DI-lite, конфигурация
│       │   ├── TrayUi.cs            # NotifyIcon, меню
│       │   ├── SettingsWindow.cs    # host:port, token, порог края, hotkey
│       │   └── settings.json (sample)
│       ├── MouseRelay.Core/         # без зависимостей от WinForms — юнит-тестируется
│       │   ├── Protocol/Messages.cs     # record-типы + JsonSerializer
│       │   ├── Protocol/JsonLineConnection.cs
│       │   ├── Session/SessionManager.cs    # state machine Idle/Phone/Cooling
│       │   ├── Session/EdgeWatcher.cs       # right-edge detect + hysteresis
│       │   ├── Session/ReconnectPolicy.cs
│       │   └── Util/Logger.cs
│       └── MouseRelay.Input/        # P/Invoke слой (изолируем unsafe/native)
│           ├── MouseHook.cs             # WH_MOUSE_LL
│           ├── RawInputMouse.cs         # опционально, этап 4+
│           ├── CursorControl.cs         # SetCursorPos/GetCursorPos/ClipCursor
│           └── Hotkey.cs                # RegisterHotKey Ctrl+Alt+M
│
└── android/                         # Kotlin, minSdk 26, targetSdk 34
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── app/
        ├── build.gradle.kts
        └── src/main/
            ├── AndroidManifest.xml
            ├── res/xml/accessibility_service_config.xml
            ├── res/{layout,values}/…
            └── java/com/mouserelay/app/
                ├── MainActivity.kt              # IP/port/token, кнопки, инструкции
                ├── ServerService.kt             # foreground: TcpServer + wake lock
                ├── net/
                │   ├── TcpServer.kt             # ServerSocket, accept loop
                │   ├── JsonLine.kt              # codec
                │   ├── Messages.kt              # data classes (kotlinx.serialization)
                │   └── Auth.kt                  # token check
                ├── a11y/
                │   ├── RelayAccessibilityService.kt  # ядро: оверлей + жесты
                │   ├── GestureEngine.kt              # dispatchGesture обёртки
                │   └── CursorOverlayView.kt          # TYPE_ACCESSIBILITY_OVERLAY
                ├── state/
                │   └── SessionState.kt          # object с StateFlow (шина между слоями)
                └── BootReceiver.kt
```

---

## 5. Разрешения Android

| Разрешение | Зачем | Комментарий |
|---|---|---|
| `INTERNET` | TCP-сервер | normal |
| `ACCESS_NETWORK_STATE` | показать LAN IP | normal |
| `FOREGROUND_SERVICE` | foreground-сервис | API 28+ |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | тип FGS для API 34 (bluetooth/networking role) | API 34; используем `connectedDevice`/`specialUse` |
| `POST_NOTIFICATIONS` | уведомление FGS (API 33+, runtime) | спрашиваем в MainActivity |
| `WAKE_LOCK` | не давать CPU/экрану усыпать в сеансе | normal |
| `RECEIVE_BOOT_COMPLETED` | автозапуск сервера после перезагрузки | normal |
| — | НЕ нужен `SYSTEM_ALERT_WINDOW` (используем TYPE_ACCESSIBILITY_OVERLAY) | |
| — | НЕ нужен `ACCESS_FINE_LOCATION` (не сканируем WiFi) | |

Battery optimization: не просим `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` в манифесте
(ограничение Play); в UI — кнопка-инструкция в настройки батареи.

## 6. AndroidManifest (структура)

```xml
<manifest …>
  <uses-permission INTERNET / ACCESS_NETWORK_STATE / FOREGROUND_SERVICE
      / FOREGROUND_SERVICE_CONNECTED_DEVICE / POST_NOTIFICATIONS / WAKE_LOCK
      / RECEIVE_BOOT_COMPLETED/>
  <application>
    <activity MainActivity exported="false" launchMode="singleTask"/>
    <service ServerService foregroundServiceType="connectedDevice"/>
    <service RelayAccessibilityService
        permission="BIND_ACCESSIBILITY_SERVICE" exported="true">
      <intent-filter>android.accessibilityservice.AccessibilityService</intent-filter>
      <meta-data accessibilityServiceConfig=@xml/accessibility_service_config/>
    </service>
    <receiver BootReceiver exported="true" permission=RECEIVE_BOOT_COMPLETED>
      <intent-filter BOOT_COMPLETED/>
    </receiver>
  </application>
</manifest>
```

accessibility_service_config.xml: `canPerformGestures="true"`,
`accessibilityFlags` без requestA11yDataSharing (данные не нужны), eventTypes — минимум
(не подписываемся на windowContent, бережём приватность и батарею).

## 7–9. Компоненты (см. таблицы выше): AccessibilityService — единственный владелец
оверлея и жестов; Socket server — тонкий транспорт, пишет события в `SessionState`;
Windows hook — WH_MOUSE_LL с фильтрацией и дельтами, SetCursorPos-якорь.

## 10. Edge switching (самая деликатная логика)

ПК (Idle):
- Каждый тик (hook event) смотрим `cursor.X >= workArea.Right - EDGE_PX(2)` И
  суммарная «право-дельта» за последние 150 мс ≥ THRESHOLD(например 40 px) → `enter`.
  Порог по накопленной дельте отличает «сел на край» от «просто довёл».
- Отправка `enter{y_ratio = cursorY/workHeight}`; переход в PhoneMode; ClipCursor
  в узкую полосу у края (2 px) — физически не даём курсору уйти дальше вправо.

ПК (PhoneMode):
- Все дельты мыши → `move{dx,dy}` (с sensitivity-коэффициентом, по умолчанию 1.0).
- ЛКМ/ПКМ/wheel перехватываются хуком (возвращаем 1 = consume) и переводятся в
  `button/scroll`; хук продолжает видеть keyboard — не трогаем.
- Якорь: после каждого move делаем `SetCursorPos(anchor)`; clip остаётся.
- Приём `exit{left_edge}` → ReleaseClip, `SetCursorPos(левый край рабочей области + 2)`,
  состояние CoolingDown 300 мс (игнорируем enter-события), затем Idle.
- Hotkey Ctrl+Alt+M → принудительный exit (ReleaseClip + cooling).
- Обрыв сокета/ping timeout → немедленный exit (fail-safe: управление всегда на ПК).

Телефон (PhoneMode):
- Курсор clamped в [-EXIT_SLOP(40) .. w-1]; если позиция была x==0 и пришёл `move{dx<0}` с
  накоплением ≤ -EDGE_DX(30 px) → отправить `exit{left_edge}`, спрятать оверлей.
  (Курсор визуально уходит за левый край — это сигнал «возврат на ПК»; после exit оверлей прячется.)
- Hysteresis: не показывать оверлей и не реагировать на enter в течение 300 мс после exit.

### 10.1 Фиксация решений MVP (по ответам пользователя)

1. **Зона перехода** = `PrimaryScreen.WorkingArea` (один монитор). `EdgeWatcher` получает
   прямоугольник как параметр — для multi-monitor позже подставим VirtualScreen без переделки логики.
2. **Sensitivity** = 1:1 из коробки, но все дельты проходят через `moveSensitivity` из `settings.json`.
3. **Left-click**: хук в PhoneMode потребляет ЛКМ; на `up` телефон делает тап
   (`dispatchGesture`, 50 мс) в текущей позиции оверлей-курсора. Drag — этап 8.
4. **Токен**: телефон при старте сервера генерирует 6-значный код, показывает в UI;
   ПК хранит введённый пользователем код в `settings.json`; `auth_fail` → показать ошибку в трее.
5. **Полный consume в PhoneMode**: `WM_LBUTTONDOWN/UP`, `WM_RBUTTONDOWN/UP`, `WM_MOUSEWHEEL` → hook возвращает 1.
   Средний клик, XBUTTON, клавиатура — passthrough. Выход только: правый edge (обратно), Ctrl+Alt+M, ping-timeout, disconnect.
6. Все перечисленные параметры — ключи `settings.json` (см. §14); hotkey и consume не имеют switch-флага — это поведение по умолчанию.

## 11. Reconnect

- ПК: экспоненциальный backoff 1s→2s→4s→max 10s, бесконечно, пока агент запущен;
  статус в трей-иконке (серый/синий/зелёный).
- Телефон: сервер просто слушает; новое подключение — старый сокет закрывается
  (single-client policy), шлём ПК `exit` если были в режиме.
- Любая рассинхронизация состояния лечится ping-timeout (6 с) → ПК выходит в Idle.

## 12. План реализации по этапам

| Этап | Что | Результат проверки |
|---|---|---|
| 1 | Скелеты проектов: sln + Gradle, пустой tray, пустая Activity | Сборка на обеих платформах |
| 2 | Протокол: Messages/JsonLine на обеих сторонах + unit-тесты round-trip | Тест зелёный |
| 3 | Android TcpServer + ServerService (FGS) + auth + hello; MainActivity показывает IP/token | `nc phone-ip 8976` видит auth_fail; ПК-клиент коннектится |
| 4 | Android AccessibilityService + оверлей-курсор; ручная отправка move из тестера ПК | Курсор двигается по экрану телефона |
| 5 | Android GestureEngine: tap/longpress/swipe-scroll | Тап открывает приложения, свайп скроллит список |
| 6 | Windows MouseHook + CursorControl + EdgeWatcher + SessionManager | Полный цикл: край→телефон→край→ПК |
| 7 | Hotkey, reconnect, ping/pong, cooling-down, fail-safe | Дёрганье края отсутствует; kill телефона → курсор свободен |
| 8 | Polish: настройки (sensitivity, edge px, reverse-axis), wake lock, boot receiver, логи | Ежедневное использование |

## 13. Инструкция по запуску и тестированию (it-runbook)

**Android:** `./gradlew :app:assembleDebug` → `adb install` (или Android Studio).
Открыть приложение → «Разрешить Accessibility» (настройки откроются сами) → вернуться →
«Запустить сервер» → увидеть `192.168.x.x:8976` и token.

**Windows:** `dotnet build win/MouseRelay.sln` → `dotnet run --project win/src/MouseRelay.App`.
В окне настроек ввести IP:порт и token → Connect. Иконка в трее зелёная = connected.

**Ручной тест-план:**
1. Подключение/токен: неверный токен → auth_fail в логе.
2. Медленно к правому краю, толчок вправо → оверлей на телефоне слева, y пропорц.
3. Движение мыши → курсор едет; ЛКМ по иконке → открывается; wheel → скролл.
4. Левый край телефона → возврат; повторить туда-обратно ×10 без залипания.
5. Ctrl+Alt+M в любой момент → мгновенный возврат.
6. Выключить экран телефона (в сеансе) → ping timeout → ПК свободен.
7. Restart сервера на телефоне → ПК переподключается сам.

---

## 14. Уточняющие вопросы — РЕШЕНО (ответы пользователя, зафиксировано как требования)

| # | Вопрос | Решение (MVP) |
|---|---|---|
| 1 | Зона перехода | **PrimaryScreen.WorkingArea**. Multi-monitor — позже, если понадобится. |
| 2 | Sensitivity / оси | **1:1**, но коэффициент выносим в `settings.json` (`moveSensitivity`, default 1.0). Инверсия осей для landscape — **этап 8 (polish)**. |
| 3 | Left-click | **Тап на `button up`** в текущей позиции оверлей-курсора. Drag-support — **этап 8**. |
| 4 | Токен | **Ручной ввод 6-значного кода с экрана телефона** (как KDE Connect). QR / UDP-discovery — позже, при наличии времени. |
| 5 | Ручные шаги (a11y, FGS, battery exemption) | Пользователь готов — считаем unavoidable, UI телефона ведёт чек-лист шагов. |
| 6 | Клики в PhoneMode | **Полный consume**: ЛКМ/ПКМ down/up и колесо не доходят до Windows; курсор «живёт» на телефоне. Выход — только edge/hotkey/ping-timeout/disconnect. |

Фиксированные следствия из решений:
- `EdgeWatcher` (MouseRelay.Core) работает строго в прямоугольнике `SystemInformation.PrimaryMonitorWorkArea`.
- `MouseHook` в состоянии `PhoneMode` возвращает `1` (consume) для `WM_LBUTTONDOWN/UP`, `WM_RBUTTONDOWN/UP`, `WM_MOUSEWHEEL`; `WM_MBUTTONDOWN/UP` и прочее — passthrough. Дельты движения считаются от `pt.x/pt.y` хука (absolute) с учётом возврата курсора якорем (см. §10).
- Настройки: `settings.json` рядом с exe, ключи MVP: `host`, `port` (8976), `token`, `moveSensitivity` (1.0), `edgeThresholdPx` (2), `enterDeltaSumPx` (60), `enterWindowMs` (150), `exitDeadzoneMs` (300), `hotkey` ("Ctrl+Alt+M"), `pingIntervalMs` (2000), `pingTimeoutMs` (6000).
- Реализацию начинаем по плану §12; первый модуль на согласование — Этап 1 (скелеты проектов) либо Этап 2 (протокол + тесты), по выбору пользователя.
