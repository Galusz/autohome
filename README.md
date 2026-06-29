# AutoHome

**Home Assistant in your car** via **Android Auto** (media app). Connects to your HA over WebSocket, shows entities live, lets you control them (toggle / impulse / brightness / setpoint / climate), draws charts from history, and previews cameras — the **layout** is one **YAML** file, the **connection** is set in the app.

> Runs as a **media** app (like Spotify) — it shows up in the car's audio section. No Google publishing required (sideload).

---

## Install (sideload)
1. Install the APK (from **Releases**) on your phone.
2. Open the **Android Auto** app → **Settings** → tap **Version** **10×** to unlock **Developer settings**.
3. In **Developer settings** enable **"Unknown sources"** (a.k.a. *Add unknown sources to launcher*).
4. (Re)connect to your car / DHU — **AutoHome** appears in the media apps. If not, force‑stop Android Auto and reconnect.

> Without "Unknown sources" a sideloaded media app will **not** appear in Android Auto.

---

## Configuration

Open **AutoHome** on the phone — the config screen has two sections:

- **Home Assistant** — URL + Long‑Lived Access Token, **Test**, **Save**. Stored in app settings (`SharedPreferences`), **not** in the YAML. The token never lives in the layout file.
- **Layout (YAML)** — the tabs/widgets editor: **Save**, **Validate** (parse + list missing entities), **Reset**, **Load file** / **Export**.

The layout is read from the on‑device file `…/Android/data/com.zkv.autohome/files/autohome.yaml` (else the bundled `assets/autohome.yaml`). After saving, **restart Android Auto** to reload.

> A `homeassistant:` section in the YAML is still accepted (legacy) and migrated to app settings once.

### Structure
```yaml
tabs:                 # top tabs (MAX 4). style: list | grid
  - id: control
    title: Control
    style: list       # or grid
    items: [ ... ]
```

---

## Types (`type`)

| type | what it does | key fields |
|---|---|---|
| `room` | nested list (drill‑in) | `items:`, `style: list\|grid`, `icon`, `color` |
| `toggle` | on/off — single `entity` **or** `items:` (1‑4) | `icon`, `icon_on`, `items:` |
| `button` | impulse — single `entity` **or** `items:` (1‑4) | `service:`, `icon`, `items:` |
| `light` | on/off + brightness ◀▶ | `entity` (light.*); % ring, color brightens with level |
| `number` | setpoint SET + ◀▶ | `entity` (number/input_number/**climate**), `min`,`max`,`step`,`unit` |
| `gauge` | percentage dial (ring) | `entity`, `min`,`max`,`unit` |
| `progress` | vertical gradient bar + char bar | `entity`, `grad: [..]`, `bar: {fill,empty,width}` |
| `text` | native multi‑line text | `lines: [{label, entity}]`, `sep:` |
| `card` | tile with a chart (best on `grid`) | `chart: ring\|bars\|line\|pie\|ribbon` |
| `camera` | camera snapshot (~5 s) | `entity: camera.xxx` |

### `toggle` / `button` panels
Both take a single `entity` (one control) or `items:` (a panel of 1‑4). The icon layout adapts to the count: **1** = one big, **2** = stacked, **3‑4** = corners. On a list/tile the title is the panel's `title:` (or *Switches*/*Buttons* if omitted); the per‑item state shows via icon color (green=on / grey=off). Tapping opens the player where each item is a button.

```yaml
- { type: toggle, entity: switch.socket, icon: "mdi:power-plug-off", icon_on: "mdi:power-plug" }
- type: button
  title: Gate
  items:
    - { entity: cover.gate, title: Open,  service: cover.open_cover,  icon: "mdi:garage-open" }
    - { entity: cover.gate, title: Close, service: cover.close_cover, icon: "mdi:garage" }
```

### `card` — charts
- `ring` — percentage dial (`min`,`max`,`unit`), value centered.
- `bars` / `line` — **history from HA**; range via `hours:` / `minutes:` (default 24h). Series max/min overlaid; value top‑left.
- `pie` — donut from `parts: [{entity, color}]`, total centered.
- `ribbon` — horizontal bars + %: `parts: [{entity, label, color}]`.

### Icons (`icon`)
- built‑in: `toggle`, `battery`, `progress`, `gauge`, `list`
- **emoji**: `"💡"`, `"🚪"`, `"🌡️"` …
- **`mdi:NAME`** — Material Design Icons (e.g. `mdi:garage`, `mdi:gate`, `mdi:lightbulb`, `mdi:fan`). A curated ~120 icons are bundled (see `MdiIcons.kt`); ask for more.
- letter + color (rooms): `icon: "S"`, `color: "#568CFF"`
- **`icon_on`** — second icon for the ON state of a `toggle` (e.g. `mdi:garage` ↔ `mdi:garage-open`). On lists the icon is also tinted green/grey by state.

### Climate (`number` on `climate.*`)
Player adds 🔥 heat / ❄️ cool / ⏻ off (`climate.set_hvac_mode`); SET + ◀▶ sets the temperature (`climate.set_temperature`).

---

## Common item fields
`group:` (section header) · `icon:` / `icon_on:` · `service:` (button) · `sep:` (separator in `text` / panel titles, default `" · "`).

## Languages (i18n)
UI strings are Android string resources — `res/values/strings.xml` is the default (**English**), `res/values-pl/` is Polish. The app follows the **phone language**. Add a language by copying `strings.xml` into `res/values-<code>/` and translating it — no code changes.

## Limitations (media path)
- **Max 4 top tabs** — for more, use `room` (drill‑in).
- Tapping an item opens the player; there is no inline toggle from the list.
- **"For You"** (Android Auto home panel) is owned by the currently *playing* media app; this app doesn't play audio, so that card shows other apps.
- **Camera = snapshot ~5 s** (not video); player art is square (fit = whole frame with letterbox bars).
- **Bars/Line** come from the HA `recorder`; scale is **relative** (series min–max).
- **Lists auto-refresh ~10 s**; the player updates live (throttled); actions refresh the list immediately.

## Build
```
gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Test without a car: **DHU** (Desktop Head Unit) + the head‑unit server on the phone.

## Credits
Icons: [Material Design Icons](https://pictogrammers.com/library/mdi/) by Pictogrammers — Apache‑2.0.
