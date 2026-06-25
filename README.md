# AutoHome

**Home Assistant in your car** via **Android Auto** (media app). The app connects to your HA over WebSocket, shows entities live, lets you control them (toggle / impulse / setpoint / climate), draws charts from history, and previews cameras — all described by a single **YAML** file.

> Runs as a **media** app (like Spotify) — it shows up in the car's audio section. No Google publishing required (sideload).

---

## Install (sideload)
1. Install the APK (from **Releases**) on your phone.
2. Open the **Android Auto** app → **Settings** → scroll to **Version** and tap it **10×** to unlock **Developer settings**.
3. In **Developer settings** enable **"Unknown sources"** (a.k.a. *Add unknown sources to launcher*).
4. (Re)connect to your car / DHU — **AutoHome** appears in the media apps. If it doesn't show, force‑stop Android Auto and reconnect.

> Without "Unknown sources" a sideloaded media app will **not** appear in Android Auto.

---

## Configuration

The app reads its config in this order:
1. **on-device file**: `/sdcard/Android/data/com.zkv.autohome/files/autohome.yaml` (editable without rebuilding — see the in-app panel),
2. bundled `assets/autohome.yaml` (default).

The app's main screen has a panel: **paste YAML / Load file / Save / Test** (Test checks the connection and whether the entities exist).

### `homeassistant` section
```yaml
homeassistant:
  url: https://ha.example.com    # or http://192.168.x.x:8123 / https://...nabu.casa
  token: "<long-lived token>"    # HA -> profile -> Long-Lived Access Tokens
```
⚠️ Keep the token local — **do not commit** the file with the token.

### Structure
```yaml
tabs:           # top tabs (MAX 4). style: list | grid
  - id: ...
    title: ...
    style: list
    items: [ ... ]
home:           # optional: the "For You" screen (AA recents) - quick shortcuts
  - { ... }
```

---

## Types (`type`)

| type | description | key fields |
|---|---|---|
| `room` | nested list (drill-in) | `items:`, `style: list\|grid`, `icon`, `color` |
| `switch` | on/off (toggle) | `entity` (light/switch/fan/input_boolean) |
| `button` | impulse / push | `entity`, `service:` (e.g. `cover.toggle`, `scene.turn_on`) |
| `number` | setpoint SET + ◀▶ | `entity` (number/input_number/**climate**), `min`,`max`,`step`,`unit` |
| `gauge` | percentage dial (ring) | `entity`, `min`,`max`,`unit` |
| `progress` | vertical gradient bar + char bar | `entity`, `grad: [..]`, `bar: {fill,empty,width}` |
| `text` | native multi-line text | `lines: [{label, entity}]` (use `\n` in label to wrap) |
| `card` | Dashboard tile with a chart | `chart: ring\|bars\|line\|pie\|ribbon` |
| `camera` | camera snapshot (every 5 s) | `entity: camera.xxx` |

### `card` — charts
- `chart: ring` — percentage dial (`min`,`max`,`unit`), value in the center.
- `chart: bars` / `line` — **history from HA**; range via `hours:` or `minutes:` (default 24h). Series max/min at top/bottom.
- `chart: pie` — donut from multiple values: `parts: [{entity, color}]`, total in the center.
- `chart: ribbon` — horizontal bars + %: `parts: [{entity, label, color}]`.

### Icons (`icon`)
- built-in: `toggle`, `battery`, `progress`, `gauge`, `list`
- **emoji**: `"💡"`, `"🚪"`, `"🌡️"`, `"📹"` …
- letter + color (rooms): `icon: "S"`, `color: "#568CFF"`
- omitted → default per type

### Climate (`number` on `climate.*`)
Adds extra buttons on the player: 🔥 heat / ❄️ cool / ⏻ off (`climate.set_hvac_mode`), while SET + ◀▶ sets the temperature (`climate.set_temperature`).

---

## Example
```yaml
homeassistant:
  url: https://ha.local:8123
  token: ""

tabs:
  - id: rooms
    title: Rooms
    style: list
    items:
      - type: room
        title: Living room
        icon: "L"
        color: "#568CFF"
        items:
          - { type: switch, entity: light.living, title: Light, icon: "💡" }
          - { type: gauge,  entity: sensor.living_temp, title: Temperature, unit: "°C", min: 0, max: 40 }
      - type: room
        title: Cameras
        icon: "📹"
        style: grid
        items:
          - { type: camera, entity: camera.front, title: Front }
          - { type: camera, entity: camera.yard,  title: Yard }

  - id: dashboard
    title: Dashboard
    style: grid
    items:
      - { type: card, chart: ring, entity: sensor.soc, title: Battery, unit: "%" }
      - { type: card, chart: line, entity: sensor.power, title: Power, unit: " W", hours: 6 }
      - { type: card, chart: pie, title: Energy, unit: " W", parts: [
          { entity: sensor.solar_w, color: "#E0B43C" },
          { entity: sensor.grid_w,  color: "#36C98D" } ] }

home:
  - { type: switch, entity: light.living, title: Light }
  - { type: button, entity: cover.gate, title: Gate, service: cover.toggle, icon: "🚪" }
```

---

## Common item fields
`group:` (section header) · `icon:` · `quick: true` (switch/button → tap toggles instantly, list refreshes, no player) · `sep:` (separator between `text` values in the list).

## Limitations (media path)
- **Max 4 top tabs** — for more, use `room` (drill-in).
- **Inline toggle** via `quick: true`; otherwise tapping an item opens the player.
- **"For You" (Android Auto home panel)** is NOT populated on the legacy `MediaBrowserService` — that needs the Media3 rewrite (planned). `home:` is a placeholder for it.
- **Camera = snapshot every ~5 s** (not video); player art is **square** (fit shows the whole frame with letterbox bars).
- **Bars/Line** come from the HA `recorder`; scale is **relative** (series min–max).
- `bars`/`line` as a standalone `type` don't render (only inside `card chart:`).
- **Lists auto-refresh ~10 s**; the player updates live (throttled).

## Build
```
gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Test without a car: **DHU** (Desktop Head Unit) + the head-unit server on the phone.
