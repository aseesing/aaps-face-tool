# Design: motion on WFF, next to the engine animation

Status: design only, 2026-11-20. Nothing here is built. The owner decides the choices in section 6
before any code is written.

The question: how does an AAPS design JSON describe the motion features of the Watch Face Format
(WFF), next to the animations of our own engine, so that a design can say "on WFF, do this instead"
where an engine animation cannot be converted?

Contents

1. The engine animation, and what the converter does with it today
2. Motion in WFF, per version
3. Why the two do not map one to one
4. The model: the `wff` key
5. Worked examples from real designs
6. The choices for the owner
7. Open questions to test
8. What the engine, the converter and the design tool must do

Sources: the engine manual `docs/watchface-animation.html` (AAPS, branch `local/personal`), the
planner `WffPlanner.kt` (AAPS, branch `local/wear-wff-slim`, commit 380ea15181), the converter in
this repository (branch `wff2-face-zip`, commit 99fdb39) and its design `docs/wff-converter-design.md`,
the WFF schemas 1 to 5 from `validator-push-cli` 1.1.0-alpha01, and the WFF reference pages on
developer.android.com (read 2026-11-20). The four design zips in `~/Projects/aaps-watchfaces`.

---

## 1. The engine animation, and what the converter does with it today

The engine plays a **wake**: a few seconds of motion when the face starts, leaves always-on, becomes
visible again, or is tapped. Formulas read `t` (seconds since the wake started), `env` (the fade in
and out) and more. Layers with `rest` also show while no wake plays, as a still picture that is drawn
again once a minute at most. So the face is still most of the time and costs no battery.

The converter cuts a design into a static picture (baked by the engine on the dev machine), live WFF
parts (clock, hands, the wearer's slots, the glucose and age slots), frames of wake motion
(`PartAnimatedImage`), and the **data picture**: one full screen picture that the engine draws on
the watch for everything that reads AAPS data, refreshed when data arrives and every 5 minutes.

Codes in the table:

- **Live**: WFF draws it and keeps it right by itself.
- **Frames**: baked by the engine into a `SequenceImages` that plays on `ON_VISIBLE`. The motion is
  there, but at a lower frame rate, shorter, and the same at every wake.
- **Rest**: only the still pose is shown. The motion is lost; the lint warns.
- **Data**: drawn in the data picture, at rest. It moves only when the picture is made again.
- **No**: not converted.

| Engine feature | Converter today | Notes |
|---|---|---|
| `durationMs` | Frames | The frames cover the wake. The watch setting "Wake-up animation" (3 to 30 s, off) does not reach the face. |
| `fps` | Frames, partly | The converter uses its own rates: 15 fps for a loop, up to 8 fps for a part of the wake, 4 fps for a whole picture. |
| `fadeMs`, `env` | Frames | Baked into the frames (fade in once, loop, fade out once). |
| Wake on start, out of always-on, visible again | Frames | `AnimationController play="ON_VISIBLE"`. |
| Wake on tap | No | `TAP` is not written. On the emulator a tap restarts a `TAP` loop from frame 0; our engine ignores a tap while a wake plays. |
| `t`, `ms`, `len` | Frames | |
| `rest` (picture, shape, text layers) | Live as a still picture | Part of the static picture. |
| `rest` layer that also moves in the wake | Frames, partly | Its static picture plays as frames of the whole picture at 4 fps; else Rest. |
| `slot` back, views, front | Live | Decides the place in the paint order. |
| Image, shape and text layers that move in the wake only (snow, rain, Santa) | Frames | A layer that shows the whole wake becomes a loop of at most 2 s. Snow: brief, low frame rate, much memory (Aurora's WFF 4 face is 110 MB, over the 100 MB limit). |
| `x`, `y`, `rotation`, `scale*`, `pivot*`, `alpha` read the wake | Frames | |
| Same props read `sec`, `min`, `hour` (not the wake) | Data, with a warning | Lags up to 5 minutes. Live layer hands were deferred by the owner. The expression printer exists but the writer does not use it yet. |
| `visible` by date (Santa at Christmas) | Live `Condition` | `WffShowDays` works out the days. |
| `colors` + `colorIndex`, `gradient`, `glow`, `clip`, `mask`, `maxScale` | Baked | Exact, because the engine draws them. |
| `blend` add, screen | Frames with `blendMode` PLUS / SCREEN | WFF 3 and up. Left out on WFF 2, with a lint line. |
| `group` with erase, inside | Baked | |
| Copies: `count`, `seed`, `points`, `slices` | Baked, or Frames | All copies in one picture. |
| `bpm` | Frames at `fallbackBpm` | Warning: the rate is fixed. |
| `noise`, `hash`, `r1` to `r8` | Baked | |
| `let`, `functions`, `templates` | Expanded | The converter works on the expanded layers. |
| View layer, move in the wake only (sweep, slide, wobble, trace, blink) | Rest | Warning. D2 loses all its needle sweeps. |
| View layer with `rest` that reads values (sun or moon on an arc) | Data | The view moves with the data picture. |
| View layer that moves a live part (clock text, hand) | No | Warning. |
| `rot0`, `dx0`, `cx0`, `cy0` | Data or Rest | Only inside a view layer. |
| `v_<key>` AAPS values in a layer | Data | |
| `@moon` and the moon from layers | Data | Needs the place of the wearer. |
| `looks` that change a layer | Data | |
| `options`, `colourLooks` | Data | The data picture follows the watch setting. Live parts use the design's default. No `ListConfiguration` is written. |
| Templates of the manual: rain, glitch, glint | Frames | |
| Template pulse (reads `bpm`) | Frames at `fallbackBpm` | |
| Templates blink, sweep, slide, wobble, trace (move views) | Rest | |
| Template moon | Data | |

In short: motion that does not depend on data survives as frames, with a cost in memory and smoothness.
Motion of data (needles, blinking values) is lost. Nothing on WFF moves by the clock yet, except the
hands and texts of the clock.

---

## 2. Motion in WFF, per version

WFF version and Wear OS: 1 = Wear OS 4, 2 = Wear OS 5, 3 = Wear OS 5.1, 4 = Wear OS 6, 5 = Wear OS 7.
The converter writes 2 and 4. A face that declares 5 is refused on Wear OS 6 (emulator, 2026-11-20).

| Feature | What it does | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|---|
| `Transform` (`target`, `value`, `mode` TO / BY) | Sets one attribute of a part or group from an expression, every time the expression changes. Targets: `x`, `y`, `width`, `height`, `angle`, `alpha` (0 to 255), `scaleX`, `scaleY`, `pivotX`, `pivotY` (0 to 1 of the part), shape attributes (`endAngle`, `endX`, ... proven on the emulator for `Rectangle width`, `Line endX`, `Arc endAngle`) | yes | yes | yes | yes | yes |
| `Transform` on `tintColor` | | no | no | no | yes | yes |
| `Animation` inside a `Transform` | When the value changes, the attribute moves to it over `duration` seconds, with `interpolation` (LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, OVERSHOOT, CUBIC_BEZIER + `controls`), `angleDirection`, `repeat` (-1 = for ever), `fps` (default 15) | yes | yes | yes | yes | yes |
| Smooth clock sources | `[SECOND_MILLISECOND]` (0 to 59.999, a fraction), `[MILLISECOND]` (0 to 999), `[SECONDS_IN_DAY]` (whole), `[SECOND]`, `[MINUTE]`, `[HOUR_0_23]`, ... A `Transform` that reads them moves while the screen is on. | yes | yes | yes | yes | yes |
| `[SECONDS_SINCE_EPOCH]` | Whole seconds | no | no | yes | yes | yes |
| `Gyro` (`x`, `y`, `scaleX`, `scaleY`, `angle`, `alpha` of a group or part) | Moves a part by the tilt of the watch: `[ACCELEROMETER_ANGLE_X]`, `_Y` (-90 to 90 degrees), `_Z`, `_XY`, `[ACCELEROMETER_X]`..., `[ACCELEROMETER_IS_SUPPORTED]` | yes | yes | yes | yes | yes |
| `Variant mode="AMBIENT"` (`target`, `value`) | Another value of one attribute in always-on | yes | yes | yes | yes | yes |
| `Variant` `duration`, `startOffset`, `interpolation`, `controls`, `angleDirection` | The change between always-on and the active screen is animated. `duration` and `startOffset` are parts (0 to 1) of the maker's own always-on transition time. | no | no | no | yes | yes |
| `PartAnimatedImage` + `AnimationController` | Frames that play on `TAP`, `ON_VISIBLE`, `ON_NEXT_SECOND`, `ON_NEXT_MINUTE`, `ON_NEXT_HOUR` (a list is allowed); `delayPlay`, `delayRepeat`, `repeat`, `loopCount`, `resumePlayBack`, `beforePlaying` and `afterPlaying` (DO_NOTHING, FIRST_FRAME, THUMBNAIL, HIDE) | yes | yes | yes | yes | yes |
| `SequenceImages frameRate` (1 to 60) | Frames per second of a sequence. The watch may skip frames. | no | yes | yes | yes | yes |
| `Condition` (`Expressions`, `Compare`, `Default`) | Shows one branch by an expression. No transition. | yes | yes | yes | yes | yes |
| `Launch` | A tap opens an app or a system screen. Not motion. | yes | yes | yes | yes | yes |
| `blendMode` on a part | PLUS, SCREEN, ... against what lies below | no | no | yes | yes | yes |
| `blendMode` on a `Group` | | no | no | no | no | yes |
| `Reference` (`name`, `source`, `defaultValue`) | Publishes a transformed attribute of a part, read anywhere as `[REFERENCE.name]`. Proven on the emulator: a value inside one complication drives a part outside it. | no | no | no | yes | yes |
| `Group` and `Scene` | A group takes `Transform`, `Variant`, `Gyro` like a part, and moves its children together. It does not clip them. | yes | yes | yes | yes | yes |

What WFF does not have:

- **No wake time.** There is no source for "seconds since the screen came on", "is ambient", "was
  tapped" or "tap count". Only frames (`ON_VISIBLE`, `TAP`) and the `Variant` transition know about
  the wake.
- **No tween on visible.** A `Transform` animates only when its value changes, not when the screen
  comes on.
- **Expressions have no `min`, `max`, `mod`, `atan2`, `noise`.** The converter writes them out or bakes.

Two facts that shape the model:

- On a watch, the active screen lasts only while the wearer looks at it; then it goes to always-on
  (or off), where WFF draws about once a minute. So a `Transform` driven by `[SECOND_MILLISECOND]`
  moves only while the wearer looks. In practice that is close to our wake: motion while the
  wearer looks, still the rest of the time.
- `[SECOND_MILLISECOND]` jumps from 59.999 back to 0. A motion with a period that does not divide 60
  seconds jumps once a minute. `[SECONDS_IN_DAY] + [MILLISECOND] / 1000` is smooth over a whole day.
- `mode="BY"` is only safe with a fixed value. With a value that changes, the part ran off the
  screen on the emulator. `TO` works (7.1).

Seen on the owner's Wear OS 5 watch: a `Variant mode="AMBIENT"` on a needle angle gave no visible
sweep. That fits the table: WFF 2 has no `Variant` transition, so the needle jumps while the screen
changes.

---

## 3. Why the two do not map one to one

| Engine motion | Closest WFF motion | Same? |
|---|---|---|
| A loop during the wake (snow, rain, lights, aurora sway) | A `Transform` on the clock, hidden in always-on | Moves for as long as the wearer looks, not for `durationMs`. Smooth, light on memory. Per-copy motion is not possible: one transform moves the whole sheet of copies. |
| One pass during the wake (Santa crosses, a trace, a glint) | Frames on `ON_VISIBLE` | What the converter does today. |
| A view that sweeps to its value on wake (D2 needles) | A live part with a `Transform` from the glucose slot, plus a `Variant` transition from always-on (WFF 4) | Only when the watch leaves always-on, with the maker's timing. Not on a tap, not on WFF 2. |
| A view that blinks while a value is low | A `Transform` on `alpha` with `[SECOND]`, inside a `Condition` on the value | Blinks the whole time the value is low, not only in the wake. Only for values WFF has (watch battery) or the glucose slot. |
| A beat at the heart rate (pulse) | A `Transform` on `scaleX`, `scaleY` with `[HEART_RATE]` and `[SECOND_MILLISECOND]` | Close. |
| A rest place that follows the clock (moon "across the sky") | A `Transform` on `x`, `y` with the clock sources | The same, and live (today: data picture, up to 5 minutes late). |
| A value that changes (new glucose) | `Animation` inside the `Transform`: the needle glides to the new value | The engine has nothing like this. |
| Nothing | `Gyro`: parallax by the tilt of the watch | The engine has no tilt. |
| Nothing | Frames on `ON_NEXT_HOUR` or `ON_NEXT_MINUTE` | A cuckoo: Santa flies over every hour. |

So a design needs to say, per layer, which of these it wants on WFF. The converter cannot guess
them: "blink the whole time the value is low" or "glide on new data" are design choices.

---

## 4. The model: the `wff` key

### 4.1 Where it goes

One optional key, `wff`, on a layer of `animation.layers` (also on a layer inside a template), and
on a classic view (`"chart": {..., "wff": ...}`). A top-level `wff` holds defaults for the whole face
(4.8).

```json
{"image": "Aurora", "rect": {...}, "rest": 0, "x": "env * 10 * sin(t)",
 "wff": {"transform": {"x": {"value": "6 * sin([SECOND_MILLISECOND] * pi / 15)", "mode": "BY"}}}}
```

The engine never reads `wff` (one exception: `only`, 4.5). The engine draws exactly what it draws
today. The converter reads `wff` from the expanded layer JSON (`Templates.expand` keeps every key).

### 4.2 The forms

| Value | Meaning on WFF |
|---|---|
| no `wff` | The default conversion of section 1. |
| `"wff": false` | Not on WFF, on purpose. The lint says so as information, not as a warning. |
| `"wff": "still"` | Only the still pose, without frames and without a warning. Cheapest. |
| `"wff": {...}` | The layer gets the WFF motion in the object. Its engine wake motion is not converted. |

### 4.3 The keys of the object

The rule for names: **inside `wff`, the names and meanings are WFF's.** Element and attribute names
are WFF's, written in lowerCamel as JSON keys (`Transform` becomes `transform`, `frameRate` stays
`frameRate`). Values have WFF units: `angle` in degrees clockwise, `alpha` 0 to 255, `pivotX` 0 to 1,
mode TO or BY. **One exception: places and sizes are in the design space of 400 x 400**, as
everywhere else in the design JSON; the converter scales them to the face. Keys that WFF does not
have are AAPS extras with their own names, marked "extra" below.

`mode` `"BY"` means what WFF says it means: move by this much from the part's own place. The
converter never writes a `BY` with a value that changes: on the emulator such a part ran off the
screen (7.1). It writes `TO` with the part's place added to the value instead.

| Key | WFF element | Value |
|---|---|---|
| `transform` | `Transform` (+ `Animation`) | A map from target to value. The value is a formula, or an object `{"value", "mode", "animation"}`. `animation` holds the WFF `Animation` attributes: `duration`, `interpolation`, `controls`, `angleDirection`, `repeat`, `fps`. |
| `variant` | `Variant mode="AMBIENT"` | A map from target to the value in always-on, or an object `{"value", "duration", "startOffset", "interpolation", "controls", "angleDirection"}`. |
| `gyro` | `Gyro` | A map from target (`x`, `y`, `scaleX`, `scaleY`, `angle`, `alpha`) to a formula. |
| `condition` | `Condition` | A formula. The part shows only while it is true. |
| `frames` (extra) | `PartAnimatedImage` + `AnimationController` + `SequenceImages` | The engine's wake motion of this layer, baked into frames, played as asked. Keys: the `AnimationController` attributes `play`, `delayPlay`, `delayRepeat`, `repeat`, `loopCount`, `resumePlayBack`, `beforePlaying`, `afterPlaying`; the `SequenceImages` attribute `frameRate`; and two extras: `from` (seconds into the wake where the frames start) and `seconds` (how long). |
| `sheets` (extra) | `Group` + `PartImage` + `Transform` | Settings of the moving sheets the converter bakes from a layer with copies (4.9): `{"bands": 1 to 4}`. `"sheets": false` keeps the layer out of the sheets (then frames or the still pose). |
| `launch` | `Launch` | A tap target, for example `"HEALTH_HEART_RATE"`. Not motion, but it belongs to the same part. |
| `let` (extra) | none | Named formulas for the formulas of this block, as `let` of a layer. Expanded by the converter. |
| `pose` (extra) | none | `{"t": ..., "env": ...}`: the moment of the engine animation whose picture is the still look of the part. Default: `t` = `rest` and `env` = 0 for a layer with `rest`; `t` = 0 and `env` = 1 for a layer without. |
| `only` (extra) | none | `true`: a layer only for WFF. The engine skips it (4.5). |
| `replaces` (extra) | none | A view key or a list: when this part is written, these classic views or view layers are left out of the WFF face. When it cannot be written, they stay. |
| `else` (extra) | none | What a version that cannot write this block does: `"default"` (the default conversion, the default value) or `"omit"`. A layer with `only` is always omitted. |

Short and long forms, so a simple motion stays one line:

```json
"transform": {"alpha": "[SECOND] % 2 == 0 ? 255 : 0"}
"transform": {"angle": {"value": "-55 + 33 * clamp((v_sgv - 36) / 288, 0, 1)",
                        "animation": {"duration": 0.8, "interpolation": "EASE_OUT"}}}
```

### 4.4 Formulas inside `wff`

The formula language is ours (the parser already reads the WFF names, branch `local/wear-wff`), but
only names that WFF can compute while the face runs are allowed:

- WFF sources: `[SECOND_MILLISECOND]`, `[SECONDS_IN_DAY]`, `[MILLISECOND]`, `[HOUR_0_23]`, `[DAY]`,
  `[MONTH]`, `[BATTERY_PERCENT]`, `[HEART_RATE]`, `[STEP_COUNT]`, `[ACCELEROMETER_ANGLE_X]`, ...
  and our names for the same things (`sec`, `min`, `hour`, `v_watch_battery`, ...).
- Two AAPS values the face has as slots: `v_sgv` (the glucose slot: mg/dl, kept in 39 to 400) and
  `v_timestamp` (the age slot: minutes, 0 to 60). The converter reads them inside the slot, or
  through a `Reference` on WFF 4.
- Numbers, `pi`, the functions WFF has, and ours that the converter writes out (`min`, `max`, `mix`,
  `step`, `smoothstep`, `mod`, `if`, `window`, `easeInOut`, `pingpong`).
- The design's `let` entries that read only these names, and the block's own `let`.

Not allowed, with a lint error: `t`, `ms`, `len`, `env`, `bpm` (use `[HEART_RATE]`), `noise`, `hash`,
the copy names (`i`, `n`, `u`, `px`, `py`, `p0` to `p7`, `r1` to `r8`), `rot0` and the other view
names, and the other AAPS values. A layer with copies is one picture on WFF, so its `wff` motion moves
all copies together.

### 4.5 Layers only for WFF, and parts only for the engine

`"wff": {"only": true, ...}` makes a layer that only the WFF face has, for example a live needle
(5.2). The engine skips it. This is the only `wff` key the engine reads: one line in the layer
reader. It is needed because the engine draws every layer it can read.

`"wff": false` is the other side: the layer stays on the engine face only.

`replaces` ties the two together. A layer only for WFF says which engine parts it stands in for. The
converter leaves those out of the WFF face only when it can write the new part for that version.
Otherwise the engine part goes to WFF the default way. So a version that cannot do the new part
never ends up with neither.

### 4.6 How the converter picks, per WFF version

For each layer and each version it writes (now 2 and 4):

1. `"wff": false`: leave the layer out. Lint: information.
2. `"wff": "still"`: the still pose goes into the static picture. No frames.
3. `"wff": {...}`:
   1. Check every key against the version (section 2) and every formula against 4.4.
   2. A key the version lacks is either **decoration** or **essential**.
      - Decoration: `animation`, the transition keys of `variant` (`duration`, `startOffset`,
        `interpolation`, `controls`, `angleDirection`), `gyro`, a `play` trigger. It is dropped;
        the lint says what (for example "WFF 2: the needle jumps out of always-on instead of
        sweeping").
      - Essential: a `transform` target the version cannot move (`tintColor` before WFF 4), a value
        it cannot read there (`v_sgv` outside the glucose slot needs a `Reference`, WFF 4), a
        layer with `blend` `add` or `screen` (needs `blendMode`, WFF 3). The block cannot be written on this version,
        so `else` decides: `"default"` gives the default conversion (and `replaces` keeps its views);
        `"omit"` leaves the layer out. A layer with `only` is left out. The lint warns.
   3. Otherwise the layer becomes its own part. Its still look is the engine's picture of the layer at
      `pose`, with no move, turn, scale or alpha baked in; those go into the part's attributes, and
      the `transform`, `variant`, `gyro` elements go on top, as WFF does it. With `frames` the part
      is a `PartAnimatedImage`.
   4. A part with motion gets `Variant AMBIENT alpha 0` unless the design gives its own `variant`
      for `alpha`. In always-on WFF draws about once a minute, so motion would only freeze there.
4. No `wff`: the default conversion, which becomes better with this model (4.9): a layer whose
   motion can be written as clock motion gets it, copies that drift become moving sheets, and only
   the rest falls back to frames or the still pose as today.

The lint shows one column per version, for example:

```
L24 snow (120 copies)     WFF 4: 3 sheets with L25          WFF 2: 3 sheets with L25
L26 CrystalBig            WFF 4: clock motion x y angle     WFF 2: clock motion x y angle
patient_name (view)       WFF 4: replaced by L28        WFF 2: data picture (L28 needs a Reference)
L28 Needle (only)         WFF 4: transform angle + variant sweep    WFF 2: left out (needs WFF 4)
```

"Visibly absent, never silently dead" in this model:

- A part that would show a value it cannot read is never written. It falls back to the default (the
  engine draws it right in the data picture) or is left out.
- Decoration that a version lacks is dropped, and the face still shows the right thing, only less
  lively. The lint says so.
- A `transform` or `variant` is written only as WFF elements that the version's schema accepts. The
  validator checks this per version.

### 4.7 What the engine does with `wff`

On the watch, nothing, except skipping `only` layers. The engine face keeps its own wake. The
engine does not play the `wff` motion, for two reasons: it has no tilt and no "on value change", and
its rule is to be still outside the wake. The design tool shows both faces: the engine's, and the
WFF face per version (8.3).

Templates: a template's layers may carry `wff` with `$parameters`, so a template like `pulse` or
`sweep` brings its own WFF motion. That needs no engine change. A `wff` key on a `{"use": ...}`
entry is an error today (an unknown parameter); it could later be passed to every layer the
template makes, with a small change in `Templates.kt`.

`looks` may patch `wff` like any other key. The converter reads the design after the looks.

### 4.8 The top-level `wff`

Defaults for the whole face. Only the keys this model needs:

```json
"wff": {"frames": {"play": "ON_VISIBLE TAP", "frameRate": 12}}
```

| Key | Meaning |
|---|---|
| `frames` | Defaults for every `frames` block and for the converter's own frames: `play`, `frameRate`, `loopCount`. |

The other keys of the old hint design (section 6 of `wff-converter-design.md`: `mode`, `motion`,
`block`, `steps`, `sizeKb`, ...) are not part of this model. Blocks are gone (the data picture
replaced them), and `motion` is replaced by the `wff` object. The old rule "hints never name WFF
elements" is reversed for motion, as the owner decided ("align names, keep extras").

### 4.9 Motion baked from the layers: clock motion, moving sheets, covers

The owner asked (2026-11-20) that the converter makes the WFF motion from the engine layers itself,
as cheaply as it can, instead of the designer drawing new pictures. Three tools do this, tried in
this order for every layer whose motion is not already live or set by a `wff` key. The emulator probe
of 7.1 showed that WFF moves several see-through pictures at the same time, on WFF 2 and 4.

**1. Clock motion: write the formula itself.** WFF has no wake time, but while the wearer looks,
time goes on. So the converter writes the engine formula with `t` replaced by
`T` = `[SECONDS_IN_DAY] + [MILLISECOND] / 1000` (seconds of the day, smooth), `ms` by `T * 1000`,
`env` by 1 and `len` by the wake length. The options take their default choice. This works when the
formula then reads only names of 4.4: no `noise`, no `hash`, no AAPS value. Per-copy names are fixed
numbers per copy, so a layer with a few copies (default at most 4) gets one part per copy. Exact:
the part moves as in the engine, for as long as the wearer looks. Examples: Aurora's big crystal, the
tree lights, the star glow's turn and scale (not its `noise` flicker).

Limits:
- `T` jumps once a day, at midnight. The engine's own `t` starts at 0 at every wake, so a formula
  that only makes sense near `t` = 0 (`window(t, 1.5, 4)`, a pass that happens once) is not clock
  motion. The converter tests this: the motion must repeat (it samples the formula over two
  stretches far apart and compares). One pass goes to frames, as today.
- Big values of `T` lose precision in WFF's float numbers. At 86,400 the step is about 0.008, small
  enough for a turn or a place. The printer may also write `T` modulo a period it finds.

**2. Moving sheets: many copies that drift.** For a layer with many copies (snow, rain, stars that
drift) the converter does not write a part per copy. It works out how the copies move and bakes them
into a few pictures that move as one:

1. Sample every copy's `x` and `y` over the wake (20 times a second, `env` = 1). Undo the wraps of
   `mod` (a jump of the same size for every copy) and note the wrap size.
2. Fit a straight line through each copy's path: its speed and direction. What is left is its sway.
   The layer is a candidate when every path is straight to within a few pixels plus a sway that
   swings round 0. Otherwise: frames or the still pose, as today.
3. Put the copies into bands by speed and direction (k-means, at most `sheets.bands`, default 3).
   Fewer bands while every copy stays within about 15 % of its band's speed and 5 degrees of its
   direction. Copies of **several layers** may share a band when they lie next to each other in the
   paint order, in the same `slot`, with the same `blend`: Aurora's small flakes and small crystals
   make three sheets together, not six.
4. Bake each band into one sheet. The sheet is turned so that the band falls straight down in it.
   On a round face the screen is the same circle at any turn, so the sheet is as wide as the face.
   It is two face heights tall and its pattern repeats after one face height (a copy near an edge is
   drawn on both sides). Each copy is drawn by the engine with its own look at the pose (picture,
   size, turn, alpha, glow), at its place on the drift line. The density is the engine's: the copies
   of the wrap area, mapped onto one period of the sheet.
5. Write each sheet as a `Group` turned by the band's lean, holding a `PartImage` with
   `<Transform target="y" value="-H + H * fract(T * s / H)">`, H the face height and s the band's
   speed. The group gets `Variant AMBIENT alpha 0`. A sheet of a layer with `blend` `add` or `screen`
   gets `blendMode` (WFF 3 and up); on WFF 2 it falls back.

What it costs, on a face of 450: one sheet is 450 x 900 x 4 bytes = 1.6 MB of memory, and one large
picture to draw per frame. Three sheets are about 5 MB. Two ways to make it cheaper, to measure when
it is built:
- One picture of one face height, used by **two** `PartImage`s one height apart, instead of one
  picture of two heights. Half the memory, if the runtime and the validator count a picture once.
- Bake the sheet at **half size** and let WFF scale it up. A quarter of the memory. Fine for soft
  flakes of 3 px and more; small hard flakes get blurred. The converter can pick this per band from
  the copies' sizes.

What is lost, and listed by the lint: each copy's own sway, the speed differences inside a band, a
copy's own turn or flicker (frozen at the pose), and the fade in and out.

**3. Covers: a moving picture under a mask that stays.** A layer that moves but is cut by a fixed
`clip` or `mask` (Aurora's sky under `SkyMask`) cannot carry its cut along: the cut would move with
it. WFF has masks (`renderMode="MASK"` in a `Group`), but how a masked group mixes with `SCREEN`
against the face below is not known. The proven way is a **cover**: the converter bakes everything
that lies below the layer, keeps it only where the mask is 0 (the town), and draws that right after
the moving layer. The roofs then lie over the moving aurora. The probe of 7.1 shows it. It costs one
picture cut to the box of the cover. It is exact where the parts below are still; a moving part
below the layer that reaches outside the mask would be covered in its still pose.

How a sheet's own motion is written: props that read no per-copy name (`x`, `y`, `rotation`,
`scale*`, `alpha` of the layer as a whole) are clock motion (1) on the sheet. Props that read
per-copy names stay at the pose, except `x` and `y` of drifting copies (2). So a layer of slices
(the aurora) keeps its sway and its breathing, and loses the waves of its slices.

---

## 5. Worked examples from real designs

### 5.1 Aurora Town: the snow

Today: three layers (120 small flakes, 70 crystals, 1 big crystal) fall during the 15 s wake, with
`alpha` = `env * ...`. The converter bakes a loop of 2 s at 15 fps, played on `ON_VISIBLE`: brief,
not smooth, and a large part of the 110 MB that makes Aurora's WFF 4 face fail.

With the model the converter bakes the snow from these layers itself (4.9). The design needs no new
picture and no `wff` key:

- The small flakes (speed 26 to 50 px a second down, 22 to 42 sideways) and the small crystals
  (35 to 65 down) are split into three speed bands. Copies of both layers with the same speed share
  a band, so there are three sheets, not six.
- Each band is one sheet of 450 x 900 (on a face of 450): the band's copies, drawn by the engine with
  their own size, picture, turn and alpha, twice, so the pattern repeats every 450 px.
- Each sheet sits in a `Group` turned by the band's lean, and falls with
  `y = -450 + 450 * fract(T * s / 450)`, where `T` = `[SECONDS_IN_DAY] + [MILLISECOND] / 1000` and
  `s` is the band's speed. Smooth at the minute; one jump at midnight.
- The big crystal is one copy, so it needs no sheet. Its formulas are written as they are, with
  `t` replaced by `T` and `env` by 1 (`mod(40 + t*15 + sin(t*0.8)*12, 480) - 40` and so on). It
  moves and turns exactly as in the engine.
- Hidden in always-on by the default `variant` (4.6).
- Memory: three sheets of 1.6 MB, about 5 MB, instead of the snow frames. Same on WFF 2 and 4.
- Lost: each flake's own sway, each flake's own speed inside its band, the spin of the small
  crystals, and the fade in and out. The lint lists them.

A designer who wants more or fewer bands, or frames after all, says so:

```json
{"shape": "circle", "count": 120, "...": "...", "wff": {"sheets": {"bands": 2}}}
{"shape": "circle", "count": 120, "...": "...", "wff": {"frames": {"play": "ON_VISIBLE TAP"}}}
```

A `gyro` on a sheet (`"gyro": {"x": "clamp([ACCELEROMETER_ANGLE_X], -30, 30) / 3"}`) lets the snow
lean with the wrist. It is decoration: a watch without an accelerometer shows straight snow.

The aurora gets a sheet of its own. Its two layers (`Aurora`, `AuroraMirrored`, 100 slices each,
`blend` `screen`, clipped by `SkyMask`) each become one picture at the rest pose. Each prop that
reads no per-copy name is written live, with `t` replaced by `T`, `env` by 1 and the options by
their default choice:

| Prop | Engine formula | On WFF |
|---|---|---|
| `x` | `env*am*sin(at*0.25)*10` | live: `10 * sin(T * 0.25)`, a sway of 10 px in 25 s |
| `scaleY` (pivot y 230) | `1 + env*am*0.06*sin(at*0.4)` | live: the aurora grows and shrinks a little |
| `y` | per slice: `sin(u*11 - at*1.3)*5 + noise(...)` | still: reads the slice (`u`) and `noise` |
| `alpha` | per slice, with `noise` | still at the rest pose, 0.85 |

The sheet moves but `SkyMask` must not: the aurora must stay out of the town. The converter draws a
**cover** after the moving sheet: everything below it, cut to where the mask is 0 (the town), so the
roofs lie over the moving aurora. With `blendMode` SCREEN on WFF 4; on WFF 2 the sheet is drawn
without it (the probe showed it still looks right) or falls back to the static picture; that is a
lint choice to make when it is built.

The tree lights (layers 14 to 17) blink in four phases during the wake. Each layer is one colour, all
its lights in the same phase, so each fits a live `alpha`:

```json
{"shape": "circle", "colors": ["#FF4632"], "points": [...], "alpha": "if(env > 0.01, mix(1, step(0.5, fract(t*0.7 + 0*0.25)), env), 1)",
 "wff": {"transform": {"alpha": "fract([SECOND_MILLISECOND] * 0.7 + 0 * 0.25) < 0.5 ? 0 : 255"}}}
```

`0.7 * 60 = 42` whole periods a minute, so there is no jump at the minute. The other three layers
use `1 * 0.25`, `2 * 0.25`, `3 * 0.25`. The lights add their light to what lies below (`blend`
`add`), which needs `blendMode` PLUS (WFF 3 and up). So on WFF 4 they blink live; on WFF 2 the block
cannot be written and the lights fall back to the default conversion, as today.

### 5.2 D2: the glucose needle that sweeps on wake

Today: the needle is the classic view `patient_name` with the picture `Needle`, turned by `dynData`
`bgNeedle` (glucose 36 to 324 mg/dl gives -55 to -22 degrees). The template `sweep` turns it from
the empty end to its value at every wake. On WFF the view is in the data picture and the sweep is
lost.

With the model: a layer only for WFF draws the same picture as a live part that turns with the
glucose slot, glides to a new value, and sweeps up from the empty end when the watch leaves
always-on:

```json
{"image": "Needle", "rect": {"left": 10, "top": 10, "width": 380, "height": 380},
 "wff": {"only": true, "replaces": "patient_name",
         "transform": {"angle": {"value": "-55 + 33 * clamp((v_sgv - 36) / 288, 0, 1)",
                                 "animation": {"duration": 0.8, "interpolation": "EASE_OUT"}}},
         "variant": {"angle": {"value": -55, "duration": 0.7, "interpolation": "OVERSHOOT"}}}}
```

- WFF 4: the converter reads `v_sgv` through a `Reference` published from the glucose slot (proven on
  the emulator). `patient_name` is left out of the data picture. The `variant` gives the sweep, but
  only out of always-on, and with the maker's timing.
- WFF 2: no `Reference`. If a part inside the glucose slot can draw outside the slot's box (open
  question 7.4), the converter puts it there; then only the sweep is lost. Otherwise the block
  cannot be written: the layer is left out and `patient_name` stays in the data picture, as today.
- No reading: the part inside the slot or behind the `Reference` is not drawn when the slot has no
  data (to check, 7.5). It must never point at an old value.
- Engine face: the layer is skipped, `patient_name` and its `sweep` play as before.

The same for the two other needles (`freetext1` sensor, `reservoir` pump) is not possible: their
values are AAPS data with no slot. They stay in the data picture. A later step could let the
converter build the live needle from `dynData` by itself; then the `sweep` template would only need
`"wff": {"variant": {"angle": {"value": "$emptyAngle", "duration": 0.7}}}`.

### 5.3 Cyberpunk Tech: the beating logo, and Aurora's moon across the sky

The template `pulse` scales a picture with the heart rate during the wake. Today: frames at the
fallback rate of 60. With a `wff` block in the template's layer, every design that uses `pulse` gets
a live beat:

```json
"wff": {"let": {"ph": "fract([SECOND_MILLISECOND] * [HEART_RATE] / 60)",
                "beat": "pow(max(0, sin(2 * pi * ph)), 6)"},
        "transform": {"scaleX": "1 + $amplitude * beat", "scaleY": "1 + $amplitude * beat"}}
```

- `[HEART_RATE]` is a whole number, so `ph` has no jump at the minute.
- With no heart rate (0), `ph` is 0 and the logo stands still: visibly still, not wrong.
- The glow (`glowAlpha` with the beat) is baked into the picture at the still pose; its own beat is
  lost. The second beat of the engine (`secondBeat`) could be added to `beat`.
- Whether a face can read `[HEART_RATE]` without a permission of its own must be checked (7.6).

Aurora's moon, option "Across the sky" (`v_opt_moon == 1`), moves along an arc by `hour` and `min`
only. That is a shared formula: once the converter makes live transforms from formulas of 4.4 by
itself (4.6 step 4), it needs no `wff` key. But the moon is drawn from eight layers with groups,
masks and the phase, and the phase needs the place, so the moon is in the data picture. Moving it
live needs the moon as its own picture that the watch makes, and there is no slot left (5 of the
wearer, glucose, age, data picture). So the moon stays in the data picture for now; this is the
honest limit of the model.

---

## 6. The choices for the owner

| # | Question | Options | My recommendation |
|---|---|---|---|
| 1 | Where does the WFF motion live? | a) a `wff` key on the layer itself; b) a separate list `wff.layers` at the top | **a.** The motion sits next to the engine motion it replaces, keeps the paint order, works inside templates, and an AI edits one place. b needs no engine change but loses the order and splits a layer in two places. |
| 2 | Names and units inside `wff` | a) WFF's (`angle`, `alpha` 0 to 255, TO / BY) with design-space places; b) ours (`rotation`, `alpha` 0 to 1) | **a.** It follows "align names" and the owner's "rewrite to the WFF meaning". An AI that knows WFF writes it without a table. The one exception (places in 400 x 400) keeps a design consistent. |
| 3 | Layers only for WFF | a) `"wff": {"only": true}`, the engine skips it (one line); b) no engine change: the design adds `"visible": 0`, the converter ignores it | **a.** One key that says what it means. b is two keys for one idea and easy to get half wrong. Older personal builds would still draw such a layer: acceptable, the design zips go with the app. |
| 4 | A version that lacks a feature | a) decoration dropped, essentials fall back to the default conversion (or `else: "omit"`), `only` layers left out, lint per version; b) the design names a minimum version | **a.** The design stays free of version numbers, and nothing that shows a value is ever frozen. |
| 5 | Does the engine use the WFF part? | a) no, the design tool previews it; b) the engine plays `transform` during its wake | **a.** The engine stays as it is; the tool is where both faces are compared. |
| 6 | WFF motion in templates | a) `wff` inside the template's layers, with `$parameters` (works now); b) also a `wff` key on `{"use": ...}` entries (small change in `Templates.kt`) | **a now, b later** when a design needs it. |
| 7 | The snow sheet | a) the designer supplies the picture; b) the converter bakes the sheet from the layers | **Decided by the owner (2026-11-20): b**, as efficient as possible. See 4.9. |

---

## 7. Open questions to test

On the Wear OS 6 emulator, with probe faces and the probe provider (as in `wff-converter-design.md`
11.1):

1. (Answered, 7.1: `BY` with a changing value is not usable; use `TO`.)
2. `Transform` with an `Animation` driven by `[COMPLICATION.RANGED_VALUE_VALUE]`: does the needle
   glide when the provider sends a new value?
3. A `Transform` and a `Variant` on the same target (`angle`): which wins, and does the `Variant`
   transition run? If the `Transform` wins, the converter can put the `Variant` on a wrapping
   `Group` with the value `-55 - <needle angle>`.
4. Can a part inside a `Complication` draw outside the slot's box (the needle on WFF 2)?
5. With no glucose data, is a part inside the slot, or a `Transform` that reads the `Reference`, not
   drawn, or drawn with its `defaultValue`?
6. Does a face read `[HEART_RATE]` in an expression without its own permission?
7. `Gyro` with the emulator's virtual sensors.
8. Frame rate on a real watch (the emulator gives about 10 a second, 7.1), and whether the motion
   stops in always-on.
9. `ON_NEXT_HOUR` and `ON_NEXT_MINUTE` with a set clock.
10. Do two `PartImage`s with the same picture count once, in the runtime and in the validator's
    memory sum (half the memory of a sheet, 4.9)?
11. A `Group` with `renderMode="MASK"` and a `SCREEN` part inside: does the part still mix with the
    face below? If yes, a mask can replace a cover.

Only on a watch with Wear OS 6 (the emulator shows no always-on): the `Variant` transition timing,
and the battery cost of a face that moves while the wearer looks.

### 7.1 Probe on the emulators (2026-11-20)

A face written by hand (`tools/probe-motion/`), built with Pack, on the Wear OS 6 emulator as WFF 4
and on the Wear OS 5 emulator as WFF 2. It has a sky with a town, an aurora picture (see-through,
`SCREEN` on WFF 4, swaying and breathing by the clock) under a town cover, three snow sheets of
450 x 900 at three speeds and leans, each in a turned `Group`, and one crystal that moves and turns
by the clock formula of Aurora's big crystal, `mod` written with `floor`.

- **Several moving see-through pictures work, on WFF 2 and 4.** All three sheets fall with their own
  speed and lean, the aurora sways behind the roofs, the crystal moves 21 px down in 1.12 s as the
  formula says.
- **Frame rate:** a screen recording of 4 s holds 41 frames on Wear OS 6 (twice, with five moving
  pictures and with fewer) and 38, in a later run 11, on Wear OS 5: up to about 10 frames a second
  on the emulator, and not steady. Only a real watch can say what a wearer sees.
- **`BY` with a changing value:** a part with `<Transform target="x" mode="BY" value="14 * sin(...)">`
  is gone from the screen. `BY` with a fixed value works (also a negative one); a fixed value
  written as an expression of a clock source (`5 + 0 * [SECOND_MILLISECOND]`) moved the part by
  about 10, not 5. So WFF adds a `BY` value again when it works it out again. `TO` with the same
  formula works.
- `pivotX` and `pivotY` on a `PartImage`, a `Transform` on `scaleY`, `blendMode` SCREEN and a turned
  `Group` with a moving part inside all work.
- The probe APK is 68 KB. Its pictures need about 7 MB of memory (sheets 3 x 1.6 MB).

---

## 8. What the engine, the converter and the design tool must do

When the owner agrees, in this order:

### 8.1 Engine (AAPS, small)

- Skip a layer with `"wff": {"only": true}`. A design test proves that a design with and without its
  other `wff` keys draws the same frames.
- The manual (`docs/watchface-animation.html`) gets a short chapter on `wff`, in the same commit
  (the owner's rule: the manual changes with the engine).

### 8.2 Converter (this repository and the planner)

- Read `wff` from the expanded layer JSON; the forms of 4.2; the checks of 4.4 and 4.6.
- Write a layer as its own part with `Transform`, `Animation`, `Variant`, `Gyro`, `Condition`,
  `Launch`, with the expression printer.
- `frames` settings for the existing loops, and `TAP` in `play`.
- `v_sgv` and `v_timestamp` through a `Reference` (WFF 4) or inside the slot (WFF 2).
- `replaces`, and the lint per version.
- Parity tests: one small design per key, on the emulator for WFF 2 and 4.

### 8.3 Design tool

- Preview modes: the engine face, the WFF 4 face, the WFF 2 face.
- Controls for the things WFF motion reads: time of day and seconds, always-on and back (with the
  `Variant` transition), tilt (`gyro`), heart rate, a new glucose value (`animation`), a tap.
- The lint per version, as text for a person and as JSON for an AI.
