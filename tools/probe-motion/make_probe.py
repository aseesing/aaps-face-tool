# Writes a WFF probe face folder: moving see-through sheets (snow bands, aurora, crystal).
# usage: python3 -I make_probe.py <out folder> <wff version 2 or 4>
import math, os, random, struct, sys, zlib

OUT, VERSION = sys.argv[1], int(sys.argv[2])
S = 450
T = "([SECONDS_IN_DAY] + [MILLISECOND] / 1000)"
PKG = "app.aaps.probe.motion"


def png(path, w, h, px):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += px[y * w * 4:(y + 1) * w * 4]
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def town(x):  # roof line of the town, y of the top at column x
    h = 330 + 25 * math.sin(x / 23.0) + (40 if (x // 38) % 3 == 0 else 0) - (30 if (x // 57) % 4 == 1 else 0)
    return min(h, 420)


def blob(px, w, h, cx, cy, r, rgb, a0):  # a soft round flake, straight alpha, wraps in y
    for yy in range(int(cy - r - 1), int(cy + r + 2)):
        for xx in range(int(cx - r - 1), int(cx + r + 2)):
            if not (0 <= xx < w):
                continue
            d = math.hypot(xx - cx, yy - cy) / r
            if d >= 1:
                continue
            a = a0 * (1 if d < 0.3 else (1 - d) / 0.7)
            y2 = yy % h
            i = (y2 * w + xx) * 4
            old = px[i + 3] / 255.0
            na = a + old * (1 - a)
            px[i:i + 3] = bytes(rgb)
            px[i + 3] = int(255 * na)


res = os.path.join(OUT, "res")
for d in ("drawable", "raw", "values", "xml"):
    os.makedirs(os.path.join(res, d), exist_ok=True)
dr = os.path.join(res, "drawable")

# Background: sky and town (opaque); cover: the town only
bg, cover = bytearray(S * S * 4), bytearray(S * S * 4)
for y in range(S):
    for x in range(S):
        i = (y * S + x) * 4
        if y >= town(x):
            bg[i:i + 4] = bytes((28, 22, 40, 255))
            cover[i:i + 4] = bytes((28, 22, 40, 255))
            if (x // 7 + y // 9) % 11 == 0 and y > town(x) + 8:
                bg[i:i + 4] = cover[i:i + 4] = bytes((255, 210, 120, 255))
        else:
            k = y / S
            bg[i:i + 4] = bytes((int(8 + 20 * k), int(14 + 30 * k), int(40 + 50 * k), 255))
png(os.path.join(dr, "bg.png"), S, S, bg)
png(os.path.join(dr, "cover.png"), S, S, cover)

# Aurora: a soft green band, see-through, reaching down behind the roofs
au = bytearray(S * S * 4)
for y in range(S):
    for x in range(S):
        c = 190 + 60 * math.sin(x / 70.0) - 0.15 * x
        d = (y - c) / 55.0
        a = math.exp(-d * d) * (0.55 + 0.45 * math.sin(x / 31.0 + 1) ** 2)
        i = (y * S + x) * 4
        au[i:i + 4] = bytes((60, 255, 140, int(200 * a)))
png(os.path.join(dr, "aurora.png"), S, S, au)

# Snow: three bands, each a sheet 450 x 900 whose pattern repeats every 450 px down
bands = [  # flakes, radius, alpha, speed px/s, lean degrees
    (40, 2.2, 0.55, 34, -30),
    (30, 3.0, 0.75, 43, -35),
    (16, 4.2, 0.95, 52, -40),
]
rnd = random.Random(11)
for k, (n, r, a, v, lean) in enumerate(bands):
    sheet = bytearray(S * 2 * S * 4)
    for _ in range(n):
        x, y, rr = rnd.uniform(0, S), rnd.uniform(0, S), r * rnd.uniform(0.7, 1.3)
        for yy in (y, y + S):
            blob(sheet, S, 2 * S, x, yy, rr, (255, 255, 255), a)
    png(os.path.join(dr, "snow%d.png" % k), S, 2 * S, sheet)

# A crystal for the exact clock motion of one copy
cr = bytearray(24 * 24 * 4)
for t in range(6):
    ang = t * math.pi / 3
    for s in range(0, 110):
        q = s / 10.0
        blob(cr, 24, 24, 12 + q * math.cos(ang), 12 + q * math.sin(ang), 1.6, (220, 235, 255), 0.9)
png(os.path.join(dr, "crystal.png"), 24, 24, cr)
png(os.path.join(dr, "preview.png"), S, S, bg)

# The face
ambient_hide = '<Variant mode="AMBIENT" target="alpha" value="0" />'
blend = ' blendMode="SCREEN"' if VERSION >= 3 else ''
snow = []
for k, (n, r, a, v, lean) in enumerate(bands):
    # TO, never BY: a BY with a changing value runs off the screen (docs/wff-motion-model.md 7.1)
    tr = '<Transform target="y" value="-450 + 450 * fract(%s * %d / 450)" />' % (T, v)
    snow.append('''        <Group name="snow%d" x="0" y="0" width="450" height="450" pivotX="0.5" pivotY="0.5" angle="%d">
            %s
            <PartImage x="0" y="-450" width="450" height="900">
                %s
                <Image resource="snow%d" />
            </PartImage>
        </Group>''' % (k, lean, ambient_hide, tr, k))
mx = "(40 + %s * 15 + sin(%s * 0.8) * 12)" % (T, T)
my = "(20 + %s * 19)" % T
xml = '''<WatchFace width="450" height="450" clipShape="CIRCLE">
    <Metadata key="CLOCK_TYPE" value="DIGITAL" />
    <Scene backgroundColor="#ff000000">
        <PartImage x="0" y="0" width="450" height="450"><Image resource="bg" /></PartImage>
        <PartImage x="0" y="0" width="450" height="450" pivotX="0.5" pivotY="0.6"%s>
            %s
            <Transform target="x" value="14 * sin(%s * 6.2832 / 25)" />
            <Transform target="scaleY" value="1 + 0.06 * sin(%s * 6.2832 / 16)" />
            <Image resource="aurora" />
        </PartImage>
        <PartImage x="0" y="0" width="450" height="450"><Image resource="cover" /></PartImage>
%s
        <PartImage x="0" y="0" width="24" height="24">
            %s
            <Transform target="x" value="%s - 480 * floor(%s / 480) - 40" />
            <Transform target="y" value="%s - 440 * floor(%s / 440) - 20" />
            <Transform target="angle" value="%s * 14 - 360 * floor(%s * 14 / 360)" />
            <Image resource="crystal" />
        </PartImage>
        <PartText x="125" y="40" width="200" height="40">
            <Text align="CENTER"><Font family="SYNC_TO_DEVICE" size="26" color="#FFFFFFFF"><Template>%%s.%%s<Parameter expression="[SECONDS_IN_DAY]" /><Parameter expression="[MILLISECOND]" /></Template></Font></Text>
        </PartText>
    </Scene>
</WatchFace>
''' % (blend, ambient_hide, T, T, "\n".join(snow), ambient_hide, mx, mx, my, my, T, T)
open(os.path.join(res, "raw", "watchface.xml"), "w").write(xml)
open(os.path.join(res, "values", "strings.xml"), "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="app_name">Motion probe</string></resources>\n')
open(os.path.join(res, "xml", "watch_face_info.xml"), "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n<WatchFaceInfo><Preview value="@drawable/preview" /><MultipleInstancesAllowed value="false" /><Editable value="false" /></WatchFaceInfo>\n')
open(os.path.join(OUT, "AndroidManifest.xml"), "w").write('''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="%s" android:versionCode="1" android:versionName="1">
    <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="36" />
    <uses-feature android:name="android.hardware.type.watch" />
    <application android:label="@string/app_name" android:hasCode="false">
        <property android:name="com.google.wear.watchface.format.version" android:value="%d" />
        <property android:name="com.google.wear.watchface.format.publisher" android:value="AAPS probe" />
    </application>
</manifest>
''' % (PKG, VERSION))
print("ok", OUT)
