#!/bin/sh
# Builds Pack (https://github.com/google/pack, Apache-2.0) on macOS or Linux. Pack turns a folder
# with an AndroidManifest.xml and res/ into an aligned and signed APK, with no Android Studio or SDK.
# The WFF harness (docs/wff-apk.md) uses it to make Watch Face Format faces of the designs.
#
# Needs: git, curl, unzip, openssl, a C compiler (Xcode command line tools, or gcc on Linux) and
# Rust (https://rustup.rs). Everything it makes goes into ~/.cache/aaps-wff (or $AAPS_WFF_CACHE):
#   pack/target/release/pack-cli   the tool
#   dev-key.pem                    the signing key of this machine, made once
#
# usage: sh tools/build-pack.sh        prints the path of pack-cli on its last line
set -e
PACK_COMMIT=7b60931e4058d892344ac57e49fcb582fd349b76
PROTOC_VERSION=36.2
DIR="${AAPS_WFF_CACHE:-$HOME/.cache/aaps-wff}"
CARGO="$(command -v cargo || echo "$HOME/.cargo/bin/cargo")"
[ -x "$CARGO" ] || { echo "Rust is needed: install it with rustup (https://rustup.rs)"; exit 1; }
mkdir -p "$DIR"

# Pack's build needs protoc, the compiler of Protocol Buffers. Set PROTOC to use your own; else the
# official release is fetched and checked against its SHA-256.
if [ -z "$PROTOC" ]; then
    case "$(uname -s)-$(uname -m)" in
        Darwin-arm64)   PLATFORM=osx-aarch_64;   SUM=9cd98a532c5c5e0c4161314de0225de27e4c8a323917b6ea7b1b714d3ae23466 ;;
        Darwin-x86_64)  PLATFORM=osx-x86_64;     SUM=228cc7add4616cc14ca5e80dee83209d44449a7aee95a914ae748fa374efb078 ;;
        Linux-x86_64)   PLATFORM=linux-x86_64;   SUM=121f6c7afe1d4d0e3ea6aab9432038599250134cbf4474cb1167d2c7decd4278 ;;
        Linux-aarch64)  PLATFORM=linux-aarch_64; SUM=8b8f18bd2b30346efbc698dd5a73dd7c805f3ef8380f6dfc95c768f3f1852f6a ;;
        *) echo "No protoc known for $(uname -s)-$(uname -m): set PROTOC to a protoc binary"; exit 1 ;;
    esac
    PROTOC="$DIR/protoc-$PROTOC_VERSION/bin/protoc"
    if [ ! -x "$PROTOC" ]; then
        ZIP="$DIR/protoc-$PROTOC_VERSION.zip"
        curl -sL -o "$ZIP" "https://github.com/protocolbuffers/protobuf/releases/download/v$PROTOC_VERSION/protoc-$PROTOC_VERSION-$PLATFORM.zip"
        if command -v sha256sum >/dev/null; then GOT="$(sha256sum "$ZIP" | sed 's/ .*//')"; else GOT="$(shasum -a 256 "$ZIP" | sed 's/ .*//')"; fi
        [ "$GOT" = "$SUM" ] || { rm -f "$ZIP"; echo "The protoc download does not match its SHA-256"; exit 1; }
        unzip -q -o "$ZIP" -d "$DIR/protoc-$PROTOC_VERSION"
        rm -f "$ZIP"
    fi
fi

# Pack itself, at a fixed commit
SRC="$DIR/pack"
[ -d "$SRC/.git" ] || git clone -q https://github.com/google/pack.git "$SRC"
if [ "$(git -C "$SRC" rev-parse HEAD)" != "$PACK_COMMIT" ]; then
    git -C "$SRC" fetch -q origin
    git -C "$SRC" checkout -q "$PACK_COMMIT"
fi
PROTOC="$PROTOC" "$CARGO" build -q --release --manifest-path "$SRC/Cargo.toml" -p pack-cli

# A signing key for the faces built on this machine, made once, so a face built again can update
# the one that is installed. Pack reads the certificate and the key from one PEM file.
KEY="$DIR/dev-key.pem"
if [ ! -f "$KEY" ]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 10000 -subj "/CN=AAPS WFF dev" -keyout "$DIR/dev-key.tmp" -out "$DIR/dev-cert.tmp" 2>/dev/null
    cat "$DIR/dev-cert.tmp" "$DIR/dev-key.tmp" > "$KEY"
    rm -f "$DIR/dev-key.tmp" "$DIR/dev-cert.tmp"
fi
echo "$SRC/target/release/pack-cli"
