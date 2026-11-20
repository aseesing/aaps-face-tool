# Motion probe

A WFF face written by hand, to check what `docs/wff-motion-model.md` (section 7.1) relies on:
several see-through pictures that move by the clock at the same time (three snow sheets in turned
groups, an aurora under a town cover, one crystal on an exact clock formula).

Make the face folders (plain Python, no packages), build them with Pack (`docs/wff-apk.md`), and run
them on an emulator (the script refuses a real watch, and uses the emulator lock of the other tools):

```
python3 -I tools/probe-motion/make_probe.py build/probe/face4 4
python3 -I tools/probe-motion/make_probe.py build/probe/face2 2
~/.cache/aaps-wff/pack/target/release/pack-cli build/probe/face4 build/probe/probe4 ~/.cache/aaps-wff/dev-key.pem
~/.cache/aaps-wff/pack/target/release/pack-cli build/probe/face2 build/probe/probe2 ~/.cache/aaps-wff/dev-key.pem
sh tools/probe-motion/run_probe.sh build/probe/probe4.apk emulator-5554 build/probe/out4
sh tools/probe-motion/run_probe.sh build/probe/probe2.apk emulator-5558 build/probe/out2
python3 -I tools/probe-motion/mp4frames.py build/probe/out4/probe.mp4
```

`run_probe.sh` takes four pictures a second apart and a screen recording of 4 s, then removes the
probe. `mp4frames.py` counts the frames of the recording (the recording only holds frames that
changed, so frames / 4 is about the frame rate). `wff4-emulator.png` and `wff2-emulator.png` are
what the probe looked like on 2026-11-20.
