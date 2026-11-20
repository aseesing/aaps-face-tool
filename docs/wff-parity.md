# The parity suite of CWF and WFF

The Watch Face Format (WFF) faces that the converter makes must look like our own faces (CWF, the
layer engine of the AAPS wear app). The parity suite checks this one feature at a time. Each test is
a small design with only one feature of the engine: a shape, a font, a blend, a copy of a layer, a
look, the clock, a complication style.

Each test:

1. converts the design into a WFF face (`WffApkBuilder`, as `docs/wff-apk.md`);
2. checks that the face passes the Watch Face Push validator;
3. checks the lint. A feature that WFF cannot show must be said to be left out (`omit`), so a
   designer knows it;
4. with the emulator: shows the face on a Wear OS 6 emulator with the same values at the same
   moment, takes its picture and compares it with the picture of our engine, region by region.
   Not when the whole face is one picture that our engine drew (all parts baked into picture 0):
   the emulator would only show that picture again, so the test says "emulator: not needed".
   One test (`ParitySampleTest.restShapes`) still runs such a face on the emulator: it proves that the
   watch draws a baked picture one to one. (A face of 400 units with pictures of 454 px was drawn
   soft; the converter now writes the face in the size of its pictures.)

The tests are in `src/test/kotlin/app/aaps/watchfaces/wffparity/`, one class per group of features.
`WffParityBase` does the work; a test only builds its design with `ParityDesign` and says what to
expect.

## When to run it

The suite takes minutes, so it is not part of `testDebugUnitTest`. Run it before you commit a change
of the layer engine, the WFF converter, the WFF complications of the wear app, `:wear:cwfrender`,
the manual of the layer engine, or a design zip that will be a WFF face. The AAPS repository has a
skill for this: `.claude/skills/wff-parity/SKILL.md`.

## How to run it

Once: build Pack, `sh tools/build-pack.sh`.

Without the emulator (converts, validates, checks the lint):

```
./gradlew wffParityTest
```

With the emulator: start a Wear OS 6 emulator (API 36), install a debug build of the wear app of the
AAPS checkout that `aapsDir` points to (`./gradlew :wear:assembleFullDebug` there), then:

```
./gradlew wffParityTest -PwffParityEmulator
```

One group: `--tests "*ParityShapes*"`. `-PwffParityWait=<seconds>` sets how long the face may take to
load on the emulator (default 20).

Every test uses the same moment (17 November 2026, 10:08, Europe/Amsterdam) and the same values,
and sets the emulator to that moment and time zone. After the picture the emulator follows the
network time again. Only one test drives the emulator at a time: `tools/wff-emulator.sh` waits for
the lock `~/.cache/aaps-wff/emulator.lock`. The script refuses a real watch.

## The result

Per test, in `build/wff-parity/<class>/<test>/`:

| File | What |
|---|---|
| `result.txt` | the lint, the changed share of each region, and why the test failed |
| `diff.png` | left our engine, in the middle the emulator, right the changes (red: changed) |
| `reference.png`, `emulator.png` | the two pictures |
| `lint.txt`, `face/`, `face.apk`, `validator.txt` | what the converter made |
| `wake_*.png` | for a test of a wake: pictures during the wake on the emulator |

A pixel counts as changed when its colour differs by more than 40 (of 255) in one channel. The edge
of the round screen and the charging mark of the emulator are not compared.

A difference has one of three causes. Find which before you change anything:

- The converter writes a WFF part that means something else: fix the converter (AAPS).
- WFF cannot do it: the converter must bake the part or leave it out and say so in the lint. The
  test then checks the lint.
- The emulator cannot show it (always-on, real sensors): say so in the test.

Do not raise the limit of a region to make a test pass without a comment that says why.
