# Watch Face Format faces of the designs

Watches that started with Wear OS 5 or later, and all watches on Wear OS 6, no longer run code-based
watch faces. They only run Watch Face Format (WFF) faces: an APK with pictures and an XML file, and
no code. This page tells how to turn a design zip into such a face APK on your own computer, and how
to try it on an emulator.

How the converter decides what goes where is in the AAPS repository, in
`docs/wff-converter-design.md` and in the class `WffPlanner`. In short, a converted face has:

- pictures of the static art, drawn once on your computer by the watch's own engine;
- frames of the wake-up effects that only show during the wake (snow, rain);
- live parts that the watch draws itself: the clock, the date, the hands, your own complication
  slots, the glucose with its colour, the arrow, the delta and the age of the reading;
- one see-through "data picture" that the AAPS watch app draws on the watch: everything else that
  shows a value (the graph, the status texts, gauge hands, the sky).

## What you need

| | macOS | Linux | Windows |
|---|---|---|---|
| This project and its AAPS checkout, as in the README | yes | yes | yes |
| Git | Xcode command line tools: `xcode-select --install` | your package manager | [Git for Windows](https://git-scm.com/download/win) |
| A C compiler and linker (Rust needs one) | the same Xcode command line tools | `build-essential` (Debian, Ubuntu) or `gcc` | "Desktop development with C++" of the Visual Studio Build Tools; the Rust installer offers it |
| Rust | [rustup.rs](https://rustup.rs) | [rustup.rs](https://rustup.rs) | [rustup.rs](https://rustup.rs) (`rustup-init.exe`) |
| `curl`, `unzip`, `openssl` | come with macOS | your package manager | `openssl` comes with Git for Windows: add `C:\Program Files\Git\usr\bin` to the `PATH` |

You do not need Android Studio for this. The Android SDK that the project already needs is enough.

## 1. Build Pack (once)

[Pack](https://github.com/google/pack) (Google, Apache-2.0) builds an aligned and signed APK from a
folder with an `AndroidManifest.xml` and `res/`. The script fetches Pack at a fixed commit, fetches
`protoc` for its build (the official release, checked against its SHA-256), builds `pack-cli`, and
makes a signing key for your computer.

macOS and Linux:

```
sh tools/build-pack.sh
```

Windows (PowerShell). This script was written but **not tested on a Windows computer yet**:

```
powershell -ExecutionPolicy Bypass -File tools\build-pack.ps1
```

Everything goes into `~/.cache/aaps-wff` (Windows: `%USERPROFILE%\.cache\aaps-wff`); set
`AAPS_WFF_CACHE` for another folder:

| File | What |
|---|---|
| `pack/target/release/pack-cli` (`.exe` on Windows) | the tool |
| `dev-key.pem` | the certificate and the key that sign your faces. Keep it: a face signed with another key cannot update the one that is installed. |
| `protoc-36.2/` | the compiler of Protocol Buffers, only needed to build Pack |

The first build takes a few minutes. Run the script again after it changed (a new Pack commit); it
then only builds what changed.

Things to know about Pack:

- It reads pictures from `res/drawable`, not from `res/drawable-nodpi`.
- It ends with exit code 0 also after an error. The harness looks for its line
  `Compiled, aligned & signed successfully!`.
- To use it by hand: `pack-cli <folder> <output without extension> <dev-key.pem>` writes
  `<output>.apk` and `<output>.aab`.

## 2. Make the faces

```
./gradlew testDebugUnitTest -PwffOut=build/wff
```

This makes a face of every zip in `cwf-designs`, each in its own test JVM. One design:
`-PwffOnly=Aurora_Town`. Other settings: `-PwffZips=<folder>` (where the zips are) and
`-PwffClient=<application id>` (the wear app that will install the face and feed it; default
`info.nightscout.androidaps`, the full app. A face only works with the app it was made for.)

Per design, in `build/wff/<design>/`:

| File | What |
|---|---|
| `lint.txt` | one line per view and layer: live, baked, in the data picture, or left out, and every warning |
| `face/` | the folder Pack reads: `AndroidManifest.xml`, `res/raw/watchface.xml`, the pictures, the fonts |
| `face.apk` | the face |
| `validator.txt`, `token.txt` | the answer of the Watch Face Push validator, and its token when the face passed |
| `scene/` | for the emulator: the stores of the watch app with fixed values of now, and `reference.png`, the face as our own engine draws it with those values |

Read `lint.txt` after every change of a design. A line with `!` means something looks or behaves in
another way than on the code-based face.

## 3. Try a face on the emulator

You need a Wear OS 6 emulator (in Android Studio: Device Manager, a Wear OS device with API 36) that
runs, and a **debug** build of the AAPS wear app (from the AAPS checkout:
`./gradlew :wear:assembleFullDebug`, the APK is in `wear/build/outputs/apk/full/debug/`).

macOS and Linux:

```
sh tools/wff-emulator.sh build/wff/Aurora_Town /path/to/wear-full-debug.apk
```

The script only works on an emulator and refuses a real watch. It:

1. installs the wear app (only when you give the APK: the first time, and after a change of the app);
2. writes the design and the values of the scene into the app's stores, so no phone is needed;
3. installs the face and makes it the active face;
4. takes a picture after some seconds: `emulator.png`, and `compare.png` with our own engine's
   picture on the left and the emulator on the right.

To see the wake-up effects, run after it (it turns the screen off and on, and takes pictures):

```
sh tools/wff-wake.sh build/wff/Aurora_Town 6
```

It writes `wake.png`, the pictures side by side.

The face's slots start with the sources the design gives them only the first time the watch sees
the face, so the script removes the face before it installs it. Set `WFF_KEEP=1` to update it in
place and keep the sources picked in the editor.

On Windows, do the same steps by hand with `adb` (the script is a short list of `adb` commands), or
run it in Git Bash.

The values in `scene/` are a few minutes old when the face shows them. Make the faces again (step 2)
for fresh ones.

## What is different on a WFF face

- The data picture is made again when new data arrives and every five minutes. Between two
  pictures its parts do not change. The clock, the date, the hands, the glucose and the age of the
  reading are live.
- Wake-up effects that only show during the wake are played as frames, at most 150 frames per face.
  Layers that show for the whole wake share a loop of 2 seconds that repeats. A layer that also shows
  at rest keeps its rest pose and does not move (Aurora Town's lights and aurora).
- A wake-up layer that only shows at some dates or with some options (Aurora Town's Santa) shows on
  the day the face was made, or never: the frames are made for that day.
- A view that only moves during the wake (a blink, a hop) stays at its rest place.
- In always-on the face shows the clock, the glucose, its age and your own slots.
- A tap anywhere on the data picture opens the AAPS menu.
