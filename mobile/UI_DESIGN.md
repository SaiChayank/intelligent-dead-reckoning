# UI design system

One system for the whole app. It lives in code in
[`app/src/main/java/com/intelligentdeadreckoning/app/ui/design/`](app/src/main/java/com/intelligentdeadreckoning/app/ui/design/):
`Tokens.kt` (colour, type, spacing, radius, size, motion) and `Components.kt`
(cards, icons, buttons, chips, status, stats, states, dock). `ui/Theme.kt` wires
them into Material 3 and honours the system animation scale. The contract below
is what those files implement; if code and document disagree, fix one of them.

## Principles

- **Dark, professional, automotive.** Near-black surfaces; the data and the map
  are the content. Strong contrast for daylight/outdoor reading (primary text
  `#F5F5F5` on `#070707`).
- **One accent ("signal lime", `#C6F24E`)** for selection, primary actions and
  live metrics. Semantic tones (success/warning/danger/info) stay separate so
  "this is simulated" can never read as "this is selected".
- **No gradients or glass for decoration.** Glass (`IdrEmphasis.GLASS`) exists
  only for chrome floating over the map. `PlaceholderBlock` uses a flat tonal
  gradient as a skeleton, not as decoration.
- **Never a blank screen.** Every surface has a loading, empty, degraded, error
  and success story.

## Tokens

| Area | Rule |
|---|---|
| Spacing | 4 dp scale (`IdrSpace` 2–40 dp). Page gutter `xl` (20 dp), card padding `lg`/`xxl`. |
| Radius | `IdrRadius` 10/14/18/24/28 + pill. Cards `xl`/`xxl`, controls `md`/`lg`, chips pill. |
| Typography | `IdrType`: display/headline/title/body/label scale, `metric`/`metricSmall`/`mono` (monospaced numerics so instrument columns stay stable), `gauge` for the single oversized readout, `brand`, `dockLabel`. No ad-hoc `fontSize` outside `IdrType`. |
| Sizes | `IdrSize`: icons 16/20/24 dp, **touch targets ≥ 44 dp** (`touchTarget`) for every button and chip, `dot`/`dotSm` status markers. |
| Motion | `IdrMotion`: 90/150/220/320/380 ms. Every animated primitive reads `LocalIdrReducedMotion` and collapses to 0 ms when the user disabled animations. Press feedback is a 0.97 scale + colour shift, not a ripple. Screen entrance is alpha-only. Nothing loops or pulses. |

## Surfaces and hierarchy

- `IdrCard(emphasis)`: `PRIMARY` (screen lead), `SECONDARY`, `UTILITY`,
  `GLASS` (over the map only), `QUIET`.
- Buttons: `PRIMARY` (accent) > `SECONDARY` > `GHOST` > `DANGER`, one row of
  actions per decision. Disabled buttons keep their label and read as disabled
  (muted content + dimmed fill), never disappear.
- Chips are selectable toggles; they carry `Selected` semantics for assistive
  technology. `IdrOverlayChip` is the glass chip for map-overlay information.

## States

`StatePanel(title, message, tone)` with `IdrStateTone`:

| Tone | Meaning | Colour |
|---|---|---|
| `LOADING` | work in progress, placeholder rows below | info |
| `EMPTY` | nothing here yet + how to get some | neutral |
| `DEGRADED` | working with reduced quality (e.g. stale fix) — never silent | warning |
| `ERROR` | it failed, and what is not available because of it | danger |
| `SUCCESS` | it completed | success |

Status dots (`StatusDot`) always sit next to a text label that says what the
colour means; colour is never the only signal.

## Honesty rules (non-negotiable)

- **Raw GNSS says raw GNSS.** Platform-reported fixes are labelled as raw, as
  reported, unfiltered — never as corrected, smoothed, fused or DR output.
- **RECORDED says recorded.** Replay and saved sessions are labelled as stored
  rows from the past, never as live acquisition.
- **SYNTHETIC stays clearly synthetic.** The scripted fixture is labelled
  `SYNTHETIC UI FIXTURE` / `SIMULATION` wherever it appears.
- **No invented labels.** Nothing may claim DR, INS, fusion, filtering, road
  matching or routing while those engines do not exist. Missing values render
  as "Unavailable"/"—", never as zero.

These rules are why the design has a separate "source banner" in the header and
a `MAP SOURCE` chip on the map: provenance is stated before interpretation.

## Map overlays

The renderer's layer colours are defined once in `IdrPalette.map*` and consumed
by `MapLibreRenderer` (as CSS strings) and by UI chrome alike. They are the
shipped, screenshot-documented values (`mobile/MAP_DEVICE_VERIFICATION.md`);
changing them requires a device re-verification pass.

| Layer | Colour | Meaning |
|---|---|---|
| trail / position / trail-fix | `#7856D8` purple | raw GNSS positions and segments as recorded/reported |
| heading wedge | `#40228B` | reported heading |
| outage segment | `#D99100` amber | automatic outage interval |
| accuracy ring | `#3E8CD9` | reported fix radius (68%) or calibrated 95% — never conflated |
| comparison line | `#D74545` red, dashed | scripted illustration, not measured output |
| scenario path | `#637888` grey-blue, dashed | scripted fixture path |

The GNSS-loss timeline bar uses the same semantics: lime = fix, amber = loss,
drawn over an observed window only, and described to TalkBack as one sentence.

## Dialogs

`AlertDialog` on the same tokens (surface, radius, type). Provenance pill on
top, per-row stored state verbatim (completion/recovery), incomplete sessions
warned about, empty and busy states explicit, actions use the button hierarchy.

## Accessibility

- Touch targets ≥ 44 dp everywhere (buttons and chips included).
- Content descriptions on data visualisations (gauge, loss timeline, map hero)
  and on numeric readouts that need context; text labels accompany every icon
  and every status colour.
- Chips expose selection; the dock exposes `Role.Tab`.
- Reduced motion is honoured through `LocalIdrReducedMotion`.
- Honesty labels are plain words, readable without colour.

## Testing

`DesignSystemUiTest` (instrumented, compiled in CI and run on the device gate)
pins the state vocabulary: loading, empty, degraded, error, success, disabled,
selected, plus the dialog states and the raw-GNSS/RECORDED honesty wording.
