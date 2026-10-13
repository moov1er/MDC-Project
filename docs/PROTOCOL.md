# MouseRelay protocol v1

Транспорт: **TCP**, телефон — сервер (порт `8976` по умолчанию), ПК — клиент.
Каждое сообщение = **одна строка JSON + `\n`** (UTF-8, JSON Lines).
Дискриминатор — поле `type`. Machin-readable схема: [`shared/schema/protocol.schema.json`](../shared/schema/protocol.schema.json).

## Жизненный цикл соединения

```
PC                                                    Phone
 |  ------------------ TCP connect ------------------>  |
 |  --> auth {token,proto,name}                        |  (таймаут 3 с на auth)
 |  <-- auth_ok | auth_fail{reason}+close              |
 |  <-- hello {width,height,density,rotation}          |
 |  --> hello_ack {screen_width,screen_height}         |
 |  <============ ping/pong каждые 2 с ==============> |  timeout 6 с → reconnect
 |  --> enter/move/button/scroll                       |
 |  <-- exit{reason} | state{overlay} | error{code}    |
```

Правила:
1. Первое сообщение от ПК обязано быть `auth`, иначе телефон закрывает соединение через 3 с.
2. Все рабочие сообщения (`enter/move/button/scroll`) игнорируются телефоном до `auth_ok` и до получения `enter`.
3. Телефон принимает **одно** активное подключение; новое вытесняет старое (`error{code:"replaced"}`).
4. `token` в MVP передаётся plain: он одноразовый, перегенерируется при каждом старте сервера и показывается на экране телефона (PIN-модель KDE Connect). Позже — TLS/WireGuard.

## Сообщения

### Handshake

| type | напр. | поля | комментарий |
|---|---|---|---|
| `auth` | PC→T | `token:str`, `proto:int(=1)`, `name:str?` | первое сообщение клиента |
| `auth_ok` | T→PC | — | авторизация успешна |
| `auth_fail` | T→PC | `reason:"bad_token"\|"wrong_proto"` | затем close() |
| `hello` | T→PC | `width:int`,`height:int` (px), `density:int` (dpi), `rotation:int 0\|90\|180\|270` | присылается сразу после auth_ok; также повторно при смене rotation |
| `hello_ack` | PC→T | `screen_width:int`,`screen_height:int` | размер рабочей области ПК (для масштабирования) |

Примеры:
```json
{"type":"auth","token":"4f8a2c","proto":1,"name":"DESKTOP-PC"}
{"type":"auth_ok"}
{"type":"hello","width":1080,"height":2400,"density":420,"rotation":0}
{"type":"hello_ack","screen_width":2560,"screen_height":1440}
```

### Рабочие (PC→Phone)

```json
{"type":"enter","edge":"right","y_ratio":0.42}
{"type":"move","dx":12,"dy":-5}
{"type":"button","button":"left","action":"down"}
{"type":"button","button":"left","action":"up"}
{"type":"button","button":"right","action":"down"}
{"type":"button","button":"right","action":"up"}
{"type":"scroll","dx":0,"dy":-120}
```

- `enter` — ПК объявляет, что курсор перешёл на телефон. `y_ratio = (cursorY - workTop) / workHeight` — точка входа, телефон ставит свой курсор к левому краю на ту же относительную высоту.
- `move.dx/dy` — относительные дельты мыши в пикселях Windows (raw delta хука, без ускорения ОС — см. §Semantics). Ось Y: вниз положительно (как в Win32 и Android).
- `button` — semantics ниже (tap-on-up, long-press).
- `scroll.dy` — сумма дельт колёсика (один «тик» = 120); dy<0 = крутить вверх (контент листается вверх как на ПК).

### Рабочие (Phone→PC)

```json
{"type":"exit","reason":"left_edge"}
{"type":"state","overlay":true}
{"type":"error","code":"gesture_not_allowed","message":"dispatchGesture returned false"}
```

- `exit.reason`: `"left_edge"` (курсор ушёл за левый край экрана телефона), `"user"` (жест/кнопка на телефоне), `"timeout"`, `"disconnect"`.
- `state.overlay` — подтверждение, что оверлей-курсор реально показан/скрыт.
- `error.code`: `"gesture_not_allowed"`, `"replaced"`, `"internal"`.

### Heartbeat

```json
{"type":"ping","timestamp":123456789}
{"type":"pong","timestamp":123456789}
```

Каждая сторона шлёт `ping` каждые 2000 мс; `timestamp` эхо-ится в `pong`. Если за 6000 мс не получено ни одного входящего сообщения — соединение считается мёртвым: ПК принудительно выходит из PhoneMode (fail-safe: управление всегда возвращается человеку) и идёт reconnect с backoff 1→2→4→8→10 с.

## Semantics (детали реализации)

- **ЛКМ**: на `down` ничего не исполняется (позиция запоминается); на `up` — `dispatchGesture(tap)` в текущей позиции оверлей-курсора. Быстрые click&click не теряются. Drag — этап 8.
- **ПКМ**: на `up` — long-press 500 мс в текущей позиции.
- **Scroll**: дельты агрегируются окном 50 мс в один свайп длиной `clamp(|dy|/120*300, 100..600)` px от позиции курсора. Соответствие направлений как на ПК: `dy<0` (колесо «от себя», контент едет вверх) → палец движется **вниз** `(x,y)→(x,y+len)`; `dy>0` → палец вверх. Настройка `invertScroll=false` по умолчанию.
- **Дельты мыши**: ПК снимает dx/dy из `MSLLHOOKSTRUCT` (already-processed ОС координаты). Экспериментальная настройка `useRawDelta` (этап 8) позволит брать raw HID-дельты без mouse acceleration.
- **Масштабирование**: телефон конвертирует px-дельты ПК в свои px: `dx_phone = dx * (phone_w / pc_screen_w)` только если включена настройка `scaleDeltas=true`; MVP по умолчанию `false` (1:1 px).

## Версионирование

`proto:1`. Неизвестные `type` обязаны игнорироваться (forward compatibility). Несовместимые изменения — bump `proto` и отказ `auth` со `wrong_proto`.
